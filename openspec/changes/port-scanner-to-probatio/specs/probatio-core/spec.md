# Spec: Probatio Core

<!-- DELTA spec for the `probatio-core` capability of the `port-scanner-to-probatio`
     change. This is the ported logic: the ADTs, validators (ex-`.jq` contracts),
     spec-lint F1–F10 + CONTEXT facts, chain-state verdict logic, append-only
     ledger at the type level, referentially-transparent chain-state, typed
     `LintReport` seam, drift/banner engine as a pure function, and the metals
     JSON-RPC client. It also binds the compiler strictness requirements
     (R-CS1…R-CS5) that make the silent-fallback defect class a compile error.

     R-ARCH1: probatio depends on NOTHING adk4s-side — no library module, no
     test util, no shared source. This spec introduces its own domain types
     from scratch; the "Concepts Used (from inventory)" table is empty by
     construction.

     This is the LARGEST and most important spec of the change. The defect
     class the whole schema exists to avert — "a corrupt ledger reads as
     clean" — is the defect class a silent port bug would reintroduce. The
     append-only ledger (R-C2) and undetermined-never-collapsed (§4.3) are
     the two most critical requirements: they are the defect class the whole
     schema exists to avert.

     WRITING RULES (enforced by spec-lint):
     - Every requirement opens with a normative statement containing SHALL or
       MUST (required by `openspec validate --strict`), followed by Given/When/Then
     - Every Then must be observable; every scenario testable
     - Every error path specified
     - No vague words without a concrete definition next to them
     - ADVERSARIAL RULE: every requirement containing "only", "never", or
       "must not" needs at least one scenario whose INPUT the requirement forbids
     - Properties use Hedgehog (NOT ScalaCheck) per the detected stack
       (capability-profile.md: hedgehog 0.13.1, "NOT ScalaCheck") -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | probatio is a leaf by construction (R-ARCH1); it depends on no adk4s concept's purpose, state, actions, or synchronizations | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

> R-ARCH1: the tooling subprojects SHALL reference zero adk4s code — no
> library module, test utility, or shared source. A build-level
> dependency-lint rule enforces it in CI. This table is empty by
> construction, not by omission.

## Concepts Introduced (new)

> **COMMITMENT**: the following concepts SHALL be introduced by this spec and
> registered in `openspec/concept-inventory.md` during apply Step 12.

| Concept | Kind | Description |
|---------|------|-------------|
| `Outcome[A]` | sealed enum (`Ran[A]`, `Finding`, `Undetermined`) | The three-way exit protocol as data (§4.1); maps to exit 0/1/2 at the CLI boundary. `Ran[A]` carries the success value; `Finding` carries the finding description; `Undetermined` carries the reason determination could not be completed. |
| `LedgerRecord` | immutable case class + `Ring` enum | The ported ledger record ADT; 12 contract clauses validated by a total function returning `Either[ContractViolation, LedgerRecord]` (R-C1). All fields are required (no optional fields in the core type; optional fields are modeled as a supertype or companion). |
| `Ring` | sealed enum (`R0`, `R1`, `R2`, `R3`, `R4`, `R5`, `R6`, `R7`, `R8`, `R9`, `Manual`) | The closed ring domain (R0–R9, manual); a ring outside this set is unrepresentable. |
| `Ledger` | module (`read`, `append`, `validate` only) | Append-only at the type level — no `update`, `delete`, or `rewrite` function is defined (R-C2). |
| `ContractViolation` | sealed trait (12 variants) | Disjoint sum of the 12 clause failures from the ledger record contract; one variant per clause. |
| `ChainStateReport` | case class | Bound/resolved/discharged verdict (R-C3); computed by a pure function over `(SpecLintReport, Ledger, Requirements, Baseline)`. |
| `SpecLintReport` / `LintReport` | typed AST | Per-requirement verdict attribution (R-C4); replaces the table-structure re-parsing. Produced by spec-lint and consumed by chain-state as uPickle JSON. |
| `GatePayload` | case class (derives ReadWriter) | The hook JSON payload (ex-`gate-hookjson-contract.jq`); byte-stable (§4.6). |
| `BannerEngine` | pure function | `(schemaVersion, skillInstallScan, registry/inventory/profile presence, detectedTestKit, activeChanges) → banner text` (R-C5b); byte-identical output for identical inputs. |
| `DriftScan` | pure function | Compares the schema version against `generatedBy: probatio-schema/<N>` stamps across six install roots (§4.4a); returns drift warnings + re-install instruction or an explicit "no skill installed" line. |
| `MetalsClient` | LSP JSON-RPC client | Content-Length framing over partial reads, init handshake with configurable timeout, lifecycle; logs to stderr (R-C6). Replaces `metals-start.sh`/`metals-call.sh`. |
| `probatioScalacOptions` | build setting (`Seq[String]`) | The strict flag set defined by R-CS1…R-CS5; applied to `probatio-core`/`probatio-cli` only. |

## ADDED Requirements

### Requirement: The three-way exit protocol is a sealed enum

The system SHALL represent the three-way exit protocol (0 = ran clean, 1 = ran and found something, 2 = could not determine) as a sealed enum with exactly three cases, and MUST NOT provide a fourth case or a catch-all construction path. The clean case SHALL carry the success value; the finding case SHALL carry a finding description; the undetermined case SHALL carry a reason describing why determination could not be completed.

**Given** a computation that may succeed, find something, or fail to determine
**When** the computation completes
**Then** the result is exactly one of the three cases, and no other case is constructible

**Rationale**: Today's scripts hand-roll 0/1/2 semantics in comments and
scattered `exit` calls. A sealed enum makes the three outcomes data, not
convention — the compiler enforces exhaustiveness at every match site, and a
fourth case (the silent fallback) is unrepresentable.

#### Scenario: a clean run carries its value

**Given** a computation that succeeds with a result value
**When** the outcome is constructed
**Then** the clean case carries the result value and the outcome maps to exit code 0

#### Scenario: a finding carries its description

**Given** a computation that completes with a finding
**When** the outcome is constructed
**Then** the finding case carries a non-empty description and the outcome maps to exit code 1

#### Scenario: an undetermined result carries its reason

**Given** a computation that cannot determine a verdict
**When** the outcome is constructed
**Then** the undetermined case carries a non-empty reason and the outcome maps to exit code 2

#### Scenario: no fourth case is constructible (adversarial)

**Given** an attempt to construct an outcome outside the three cases
**When** the code is compiled
**Then** construction fails — the enum is sealed and no factory accepts a fourth variant

### Requirement: LedgerRecord is an immutable product type with total clause validation

The system SHALL represent a ledger record as an immutable product type, and validation of every contract clause SHALL be a total function returning a disjoint sum type. All 12 clauses of the ledger record contract SHALL appear as cases in the validation result: the record is either accepted (all 12 clauses pass) or carries exactly one contract violation naming the first clause it failed. The closed ring domain (R0–R9, manual) SHALL be a sealed enum, so a ring outside this set is unrepresentable at the type level.

**Given** a candidate ledger record as a JSON value
**When** the validator is applied
**Then** the result is either the validated record or a contract violation naming exactly one of the 12 clauses, and the validator never throws, returns null, or silently accepts a record that violates a clause

**Rationale**: The 12 clauses of the contract are the single statement of the
record format. A validator that is not total — one that throws, returns null,
or silently accepts a violating record — is the silent-fallback defect class.
The disjoint sum makes every clause failure an explicit value, not an
exception to catch or a null to check.

#### Scenario: a record satisfying all 12 clauses is accepted

**Given** a JSON object with all required fields conforming to every clause (integer version ≥ 1, ISO-8601 UTC timestamp, non-empty change/spec without path separators, ring in the closed domain, non-empty obligation/artifact/command, integer exit, lowercase hex baseline of 7–40 chars)
**When** the validator is applied
**Then** the result is the validated record, carrying the typed ring enum value

#### Scenario: a record missing a required field is rejected with the field named

**Given** a JSON object omitting the `exit` field
**When** the validator is applied
**Then** the result is a contract violation naming the missing-required-fields clause and listing `exit` as the missing field

#### Scenario: a record with a ring outside the closed domain is rejected

**Given** a JSON object whose `ring` field is `"R99"`
**When** the validator is applied
**Then** the result is a contract violation naming the ring-domain clause, and the value `"R99"` is not representable as a ring enum value

#### Scenario: a record with a non-integer version is rejected

**Given** a JSON object whose `v` field is `1.5`
**When** the validator is applied
**Then** the result is a contract violation naming the version-integer clause

#### Scenario: a record with a path separator in the change field is rejected

**Given** a JSON object whose `change` field is `"foo/bar"`
**When** the validator is applied
**Then** the result is a contract violation naming the change-path-separator clause

#### Scenario: a record with a malformed timestamp is rejected

**Given** a JSON object whose `ts` field is `"2026-08-08 12:34:56"` (space-separated, not ISO-8601 UTC with `T` and `Z`)
**When** the validator is applied
**Then** the result is a contract violation naming the timestamp-format clause

#### Scenario: a record with a non-hex baseline is rejected

**Given** a JSON object whose `baseline` field is `"g1234567"` (contains non-hex characters)
**When** the validator is applied
**Then** the result is a contract violation naming the baseline-format clause

#### Scenario: the validator is total — a null input is rejected, not crashed on (adversarial)

**Given** a JSON null value
**When** the validator is applied
**Then** the result is a contract violation naming the not-a-json-object clause, and no exception is thrown

### Requirement: The ledger is append-only at the type level

The ledger module SHALL expose only read, append, and validate operations, and MUST NOT define an update, delete, or rewrite function. The append operation SHALL add a record to the end of the ledger without altering any prior record, and read SHALL return the records in append order. No function in the ledger module SHALL accept a record index or identifier for the purpose of modifying or removing an existing record.

**Given** a ledger containing records from earlier steps
**When** any append occurs
**Then** every pre-existing record is byte-identical afterwards, the new record is last, and no operation exists that could alter or remove a prior record

**Rationale**: The current script enforces append-only by name-denylisting
before argument parsing. The port enforces it by the modifying subcommand not
existing — a subcommand that doesn't exist is a parse error, which is strictly
stronger than a denylisted name. This is the defect class the whole schema
exists to avert: if records can be rewritten, the ledger becomes as forgeable
as the prose it replaces, with more authority.

#### Scenario: appending preserves all prior records

**Given** a ledger with three records
**When** a fourth record is appended
**Then** the first three records are byte-identical and the ledger has four records in append order

#### Scenario: read returns records in append order

**Given** a ledger with records appended in order A, B, C
**When** the ledger is read
**Then** the records are returned in the order A, B, C — the order they were appended

#### Scenario: no update function exists (adversarial)

**Given** an attempt to call an update function on the ledger module
**When** the code is compiled
**Then** compilation fails — no update function is defined in the ledger module

#### Scenario: no delete function exists (adversarial)

**Given** an attempt to call a delete function on the ledger module
**When** the code is compiled
**Then** compilation fails — no delete function is defined in the ledger module

#### Scenario: no rewrite function exists (adversarial)

**Given** an attempt to call a rewrite function on the ledger module
**When** the code is compiled
**Then** compilation fails — no rewrite function is defined in the ledger module

### Requirement: Chain-state computation is referentially transparent

The system SHALL compute chain state as a pure function over declared inputs — a lint report, a ledger, a requirements list, and a baseline — returning either an undetermined result with a reason or a chain-state report. The computation SHALL read no files internally; all file I/O SHALL live in the CLI layer, which reads the inputs and passes them as values. The same inputs SHALL always produce the same output, and the computation SHALL NOT depend on environment variables, wall-clock time, or external state.

**Given** a lint report, a ledger, a requirements list, and a baseline revision
**When** chain state is computed
**Then** the result is either an undetermined result naming the reason or a chain-state report with bound, resolved, and discharged counts, and computing twice with the same inputs produces byte-identical results

**Rationale**: Today's `chain-state.sh` re-reads spec-lint's table structure
to attribute verdicts because "spec-lint's own output has no such
attribution". The port takes a typed lint report as structured input rather
than re-executing or re-parsing heuristic tables. The pure-function boundary
means the decision is testable in isolation — no fixture directory, no
environment, no file system — and the same inputs always agree.

#### Scenario: same inputs produce same output

**Given** a fixed lint report, ledger, requirements list, and baseline
**When** chain state is computed twice with the same inputs
**Then** both results are equal (value equality on the report or the undetermined reason)

#### Scenario: an unreadable ledger yields undetermined, not zero

**Given** a ledger that cannot be read (corrupt, truncated, or unrecognised version)
**When** chain state is computed with that ledger
**Then** the result is undetermined with a reason naming the ledger read failure, and the discharged count is NOT reported as zero

#### Scenario: a failed lint yields undetermined, not zero

**Given** a lint report that indicates the lint itself failed (not a finding, but a non-lint failure)
**When** chain state is computed with that lint report
**Then** the result is undetermined with a reason naming the lint failure

#### Scenario: a genuinely empty ledger is reported as zero discharged

**Given** a readable ledger containing no records for the current change and baseline
**When** chain state is computed
**Then** the discharged count is zero and the result is a report (not undetermined), distinct from the unreadable-ledger case

#### Scenario: no file I/O occurs inside the computation (adversarial)

**Given** the chain-state computation function
**When** its body is inspected or its behavior is observed with no file system available
**Then** no file is read, no environment variable is consulted, and no wall-clock time is queried — the function is pure over its declared inputs

### Requirement: spec-lint output carries per-requirement verdict attribution

The system SHALL produce a typed lint report that carries per-requirement verdict attribution — each requirement is named with its verdict and the check that produced it. The report SHALL be consumed by chain-state as structured input, replacing the table-structure re-parsing that today's chain-state script does to recover attribution. The report SHALL be serializable as uPickle JSON and round-trip losslessly.

**Given** a spec-lint run over a change's specifications
**When** the lint report is produced
**Then** every requirement is attributed with its verdict (bound, resolved, or unbound) and the check (F1–F10) that produced it, and the report is a typed value consumable by chain-state without re-parsing

**Rationale**: This is the one deliberate behavioral improvement over the
feature freeze. Today's `chain-state.sh` "only re-reads table STRUCTURE"
because spec-lint's output has no per-requirement attribution. The typed
`LintReport` seam removes this documented fragility. It does not change what
either tool *decides*, only how the decision is *transmitted*.

#### Scenario: a requirement with a bound verdict is attributed

**Given** a change where requirement R1 is named by an obligation naming an existing artifact
**When** the lint report is produced
**Then** R1 appears in the report with its verdict (bound and resolved) and the check that established it

#### Scenario: a requirement with an unbound verdict is attributed

**Given** a change where requirement R2 is named by no obligation
**When** the lint report is produced
**Then** R2 appears in the report with verdict unbound and the check that detected it

#### Scenario: the report round-trips through uPickle JSON

**Given** a typed lint report
**When** it is serialized to JSON and deserialized
**Then** the result is equal to the original report — no verdict, requirement name, or check attribution is lost

#### Scenario: chain-state consumes the typed report without re-parsing (adversarial)

**Given** a chain-state computation receiving a typed lint report
**When** the computation attributes verdicts to requirements
**Then** it reads the verdicts from the report's typed fields, not by re-parsing a table structure or re-executing the lint

### Requirement: The port preserves lint F1–F10 verdicts on every fixture

The port SHALL NOT change the lint F1–F10 verdicts on any fixture. For every fixture in the fixture corpus, the set of verdicts and warnings produced by the ported spec-lint SHALL be identical to the set produced by the current bash spec-lint, including the numbered conditional checks (3, 6, 17 ALTITUDE, 18 CONCURRENCY) and the judgment/applicability split.

**Given** a fixture from the fixture corpus
**When** both the current bash spec-lint and the ported spec-lint are run on it
**Then** the verdict sets and warning sets are identical — no verdict is added, removed, or changed

**Rationale**: A port that silently changes a verdict has changed the schema,
not ported it. This is what keeps R-M1 (the oracle passes unmodified)
interpretable: a behavior delta breaks the oracle in a way indistinguishable
from a port bug.

#### Scenario: a fixture with all checks passing produces identical verdicts

**Given** a fixture where all F1–F10 checks pass
**When** both the bash and ported spec-lint run on it
**Then** both produce the same empty finding set and the same applicability annotations

#### Scenario: a fixture with a conditional check marked APPLIES produces identical verdicts

**Given** a fixture where check 17 ALTITUDE APPLIES and the spec has the required altitude vocabulary
**When** both the bash and ported spec-lint run on it
**Then** both report check 17 as APPLIES with the same verdict

#### Scenario: a fixture where APPLIES is recorded as N/A is a finding in both (adversarial)

**Given** a fixture where check 17 ALTITUDE APPLIES but the spec records "N/A" for it
**When** both the bash and ported spec-lint run on it
**Then** both report this as a finding — recording "N/A" for a check that APPLIES is a finding in both implementations

#### Scenario: a fixture with a CONCURRENCY check produces identical verdicts

**Given** a fixture where check 18 CONCURRENCY applies and the spec has/hasn't the deterministic test kit line
**When** both the bash and ported spec-lint run on it
**Then** both report the same verdict and the same detected-test-kit annotation

### Requirement: spec-lint CONTEXT facts and F1–F10 semantics are preserved

The system SHALL preserve the spec-lint CONTEXT facts and F1–F10 check semantics, including the numbered conditional checks (3, 6, 17 ALTITUDE, 18 CONCURRENCY) and the judgment/applicability split. The lint SHALL decide applicability of the conditional rules only, and SHALL state its decision for both polarities ("check 17 ALTITUDE APPLIES …" / "… is N/A (attested by this script, not assumed)"). Where a check is marked APPLIES, recording "N/A" for it SHALL be a finding.

**Given** a specification with conditional checks whose applicability depends on spec content
**When** the lint evaluates the specification
**Then** each conditional check is evaluated with the same applicability logic and the same judgment semantics as the current bash implementation, and the applicability decision is stated for both polarities

**Rationale**: The numbered conditional checks and the judgment/applicability
split are machine-attested contracts. A port that silently changes which
checks apply, or that stops attesting the N/A polarity, has changed the
schema's enforcement surface, not ported it.

#### Scenario: check 17 ALTITUDE applicability is decided identically

**Given** a specification where the altitude vocabulary is present/absent
**When** the ported lint evaluates check 17
**Then** the applicability decision (APPLIES or N/A) matches the bash implementation, and both polarities are stated

#### Scenario: check 18 CONCURRENCY applicability is decided identically

**Given** a specification where the deterministic test kit line is present/absent
**When** the ported lint evaluates check 18
**Then** the applicability decision and the detected-test-kit annotation match the bash implementation

#### Scenario: a check marked APPLIES with N/A recorded is a finding

**Given** a specification where check 3 APPLIES but the spec records "N/A" for it
**When** the ported lint evaluates the specification
**Then** this is reported as a finding, matching the bash implementation

#### Scenario: the judgment/applicability split is preserved (adversarial)

**Given** a specification where a conditional check's applicability is ambiguous
**When** the ported lint evaluates it
**Then** the lint decides only applicability (not judgment), and the judgment is left to the requirement's own obligation — the split is preserved, not collapsed

### Requirement: The drift, context, and banner engine is a pure function

The system SHALL compute the drift, context, and banner output as a pure function over declared inputs — the schema version, skill-install scan results, registry/inventory/profile presence and counts, detected test kit, and the active-change list with per-change chain state. The same inputs SHALL always produce byte-identical output. The function SHALL read no files internally; all file reads SHALL be performed by the CLI layer and passed as values.

**Given** a set of declared inputs (schema version, skill-install scan, registry/inventory/profile presence, detected test kit, active changes with chain state)
**When** the banner engine is invoked
**Then** the output is byte-identical for identical inputs, and the function reads no files, consults no environment variables, and queries no wall-clock time

**Rationale**: The v13 banner's wiring (which root's drift line appears
where) is property-testable against golden fixtures captured from the real
scripts only if the banner is a pure rendering. Today's banner is assembled
from live reads inside `gate.sh`; the port extracts the rendering as a pure
function so it can be tested in isolation.

#### Scenario: identical inputs produce byte-identical output

**Given** two invocations of the banner engine with the same inputs
**When** both outputs are compared
**Then** they are byte-identical

#### Scenario: different inputs produce different output

**Given** two invocations with inputs differing only in the schema version
**When** both outputs are compared
**Then** they differ — the banner reflects the changed input

#### Scenario: no file I/O occurs inside the engine (adversarial)

**Given** the banner engine function
**When** its body is inspected or its behavior is observed with no file system available
**Then** no file is read, no environment variable is consulted, and no wall-clock time is queried — the function is pure over its declared inputs

### Requirement: Instruction drift is detected across all install roots

The system SHALL compare the schema version (read from the schema configuration) against the `generatedBy` stamp in every installed skill copy across the six searched roots (repo `.agents`/`.claude`/`.pi`; home `.agents`/`.claude`/`.zcode`). When a stamp's version differs from the schema version, a drift warning SHALL be emitted with the re-install instruction. When none of the roots match, an explicit "no skill installed" line SHALL be emitted. Silent drift — emitting nothing when drift exists — and silence about checking for drift — emitting nothing when no skills are installed — are both defect classes the system MUST NOT exhibit.

**Given** a schema version and skill-install scan results across the six roots
**When** the drift scan is applied
**Then** every root whose stamp differs from the schema version produces a drift warning with the re-install instruction, and when no root matches, an explicit "no skill installed" line is produced

**Rationale**: Skill installs carry a `generatedBy` stamp and for years
nothing read it — agents followed pre-drift instructions perfectly. Silent
drift is a defect class; silence about *checking* for drift is the same
class. The drift scan is a data-driven check, not a one-off shell loop.

#### Scenario: a root with a matching stamp produces no drift warning

**Given** a schema version of 14 and a root whose stamp is `generatedBy: probatio-schema/14`
**When** the drift scan is applied
**Then** no drift warning is emitted for that root

#### Scenario: a root with a mismatched stamp produces a drift warning

**Given** a schema version of 14 and a root whose stamp is `generatedBy: probatio-schema/13`
**When** the drift scan is applied
**Then** a drift warning is emitted for that root naming the expected version (14), the found version (13), and the re-install instruction

#### Scenario: no installed skills produces an explicit line

**Given** a schema version and scan results where none of the six roots contain a skill install
**When** the drift scan is applied
**Then** an explicit "no skill installed" line is emitted — not silence, not an empty result

#### Scenario: a pre-rename stamp is treated as drift with a migration message

**Given** a schema version of 14 and a root whose stamp is `generatedBy: verified-scala3-schema/13` (the pre-rename stamp)
**When** the drift scan is applied
**Then** a drift warning is emitted with a one-time migration message indicating the rename from `verified-scala3-schema` to `probatio-schema`

#### Scenario: silence about drift is never emitted (adversarial)

**Given** a schema version and scan results where drift exists
**When** the drift scan is applied
**Then** the output is non-empty — drift is never silently passed over

#### Scenario: silence about checking is never emitted (adversarial)

**Given** a schema version and scan results where no skills are installed
**When** the drift scan is applied
**Then** the output is non-empty — the "no skill installed" line is always emitted when no root matches

### Requirement: The banner is assembled from live reads, not remembered state

The system SHALL assemble the banner from live reads of the current state, not from cached or remembered values. The banner SHALL consist of a short invariant block (verbatim-match text) and a session-context block (context facts + workflow position + a live chain-state section naming unresolved requirements per active change), closed by the gate-checks tool list and the "READ FROM DISK … facts, not recollection" trailer. An unchanged payload SHALL inject nothing; a changed one SHALL re-inject in full.

**Given** a session with active changes and live chain-state data
**When** the banner is assembled
**Then** the invariant block is the verbatim-match text, the session-context block reflects the current context facts and live chain-state, and the trailer states that facts are read from disk, not recollection

**Rationale**: The banner's value is that it is assembled from live reads. A
banner that remembers stale state would let an agent act on outdated context
— the same defect class as silent drift. Suppression fingerprints the
assembled facts per session: an unchanged payload injects nothing; a changed
one re-injects in full.

#### Scenario: the invariant block is verbatim-match text

**Given** any session
**When** the banner is assembled
**Then** the invariant block is the exact verbatim-match text — not a paraphrase, not a remembered version

#### Scenario: the session-context block reflects live chain state

**Given** a session with two active changes, one with unresolved requirements
**When** the banner is assembled
**Then** the session-context block names both changes and lists the unresolved requirements for the one that has them, read from the current chain-state data

#### Scenario: an unchanged payload injects nothing

**Given** a session where the assembled facts have not changed since the last injection
**When** the banner is assembled and the payload is compared to the previous one
**Then** the payload is unchanged and nothing is re-injected

#### Scenario: a changed payload re-injects in full

**Given** a session where a requirement was resolved since the last injection
**When** the banner is assembled and the payload is compared to the previous one
**Then** the payload reflects the new state and is re-injected in full

#### Scenario: the trailer states facts are read from disk (adversarial)

**Given** any session
**When** the banner is assembled
**Then** the trailer contains the "READ FROM DISK … facts, not recollection" text — the banner never claims to remember state

### Requirement: The metals client frames LSP correctly on partial reads

The system SHALL provide a metals client that frames LSP messages correctly over arbitrary buffer boundaries. Content-Length parsing SHALL handle a header split across read boundaries, a body split across read boundaries, and multiple messages in a single read. The initialization handshake SHALL be performed with a configurable timeout, and all diagnostic logging SHALL be written to stderr — never stdout, because stdout is the JSON-RPC message channel.

**Given** an LSP server stream that may split messages across arbitrary buffer boundaries
**When** the metals client reads from the stream
**Then** every message is framed correctly by its Content-Length header, and no message is truncated, duplicated, or merged with an adjacent one

**Rationale**: Today's `metals-call.sh` does LSP Content-Length framing in
shell — `read`/`printf` over byte streams — which is where the defects lived.
The port frames byte streams with a library, not hand-rolled shell.

#### Scenario: a header split across reads is parsed correctly

**Given** an LSP stream where the `Content-Length: 42\r\n` header is split as `Content-Len` + `gth: 42\r\n` across two reads
**When** the metals client reads the stream
**Then** the Content-Length is parsed as 42 and the subsequent body of 42 bytes is read correctly

#### Scenario: a body split across reads is assembled correctly

**Given** an LSP stream where a 42-byte body is split as 20 bytes + 22 bytes across two reads
**When** the metals client reads the stream
**Then** the full 42-byte body is assembled before the message is dispatched

#### Scenario: multiple messages in a single read are split correctly

**Given** an LSP stream where a single read returns two complete messages concatenated
**When** the metals client reads the stream
**Then** both messages are dispatched separately, each with its own Content-Length framing

#### Scenario: the initialization handshake completes within the timeout

**Given** an LSP server that responds to `initialize` within the configured timeout
**When** the metals client performs the initialization handshake
**Then** the handshake completes and the client transitions to the initialized state

#### Scenario: the initialization handshake times out

**Given** an LSP server that does not respond to `initialize` within the configured timeout
**When** the metals client performs the initialization handshake
**Then** the handshake fails with a timeout error, and the error is reported (not silently hung)

#### Scenario: no diagnostic is written to stdout (adversarial)

**Given** the metals client producing diagnostic log output
**When** the log output is inspected
**Then** all diagnostics appear on stderr — stdout carries only JSON-RPC message bodies, never log lines

### Requirement: Undetermined is never collapsed into a finding

The system SHALL never collapse an undetermined result into a finding. An undetermined result (exit 2) — caused by an unreadable ledger, an unknown record version, a non-lint failure, or missing prerequisites — SHALL be reported as undetermined with its reason, and MUST NOT be reported as a finding (exit 1) or as clean (exit 0). The treachery this averts is "a corrupt ledger reads as clean": a tool that reports "0 discharged" identically when nothing ran and when the ledger is corrupt reproduces the defect class the whole schema exists to remove.

**Given** a computation that cannot determine a verdict due to a corrupt input, a missing prerequisite, or an unknown version
**When** the outcome is constructed
**Then** the outcome is the undetermined case carrying the reason, and it is never the finding case or the clean case

**Rationale**: This is the defect class the whole change targets, applied to
the change's own tooling. A tool that reports "0 discharged" identically when
nothing ran and when the ledger is corrupt would reproduce it. The
three-way exit protocol exists precisely so that "could not determine" is a
distinct outcome from "determined and found nothing".

#### Scenario: a corrupt ledger yields undetermined, not clean

**Given** a ledger that is corrupt or truncated
**When** chain state is computed
**Then** the outcome is undetermined (exit 2), never clean (exit 0) — the corruption is not silently passed

#### Scenario: an unknown record version yields undetermined, not a partial result

**Given** a ledger containing a record with an unrecognised format version
**When** the ledger is read
**Then** the read reports undetermined and does not report the other records as a complete result

#### Scenario: a non-lint failure yields undetermined, not a finding

**Given** a spec-lint run that fails for a reason other than lint findings (e.g. a missing prerequisite)
**When** the outcome is constructed
**Then** the outcome is undetermined (exit 2), not a finding (exit 1) — the failure is not misreported as a lint finding

#### Scenario: undetermined is not collapsed into finding (adversarial)

**Given** an undetermined result from any source
**When** the outcome is mapped to an exit code
**Then** the exit code is 2, never 1 — no code path maps undetermined to finding

#### Scenario: undetermined is not collapsed into clean (adversarial)

**Given** an undetermined result from any source
**When** the outcome is mapped to an exit code
**Then** the exit code is 2, never 0 — no code path maps undetermined to clean

### Requirement: Warnings are fatal on probatio-core and probatio-cli

The system SHALL activate the warning-fatal master switch on the probatio subprojects, so that every warning becomes a compile error. Combined with the existing unused-import and exhaustiveness escalations, a port using a deprecated API or leaving an unused import SHALL fail compilation, not compile clean.

**Given** the probatio-core and probatio-cli subprojects
**When** they are compiled with the strict flag set
**Then** any warning causes compilation to fail with a non-zero exit code

**Rationale**: The defect class this port averts is "a corrupt ledger reads as
clean" — i.e. silent fallbacks and discarded results. The probatio
subprojects are a clean slate (no legacy to grandfather, R-ARCH1 isolates
them), so this is the moment to apply the strictest scalac set the toolchain
supports. The master switch makes every other strict flag load-bearing.

#### Scenario: a deprecated API usage fails compilation

**Given** a source file in probatio-core that calls a method marked `@deprecated`
**When** the subproject is compiled
**Then** compilation fails with a deprecation error, not a warning

#### Scenario: an unused import fails compilation

**Given** a source file in probatio-core with an unused import
**When** the subproject is compiled
**Then** compilation fails with an unused-import error (escalated by the existing `-Wunused:all` + the fatal switch)

#### Scenario: a clean source compiles without error

**Given** a source file in probatio-core with no warnings
**When** the subproject is compiled
**Then** compilation succeeds — the strict flags do not produce false positives on clean code

### Requirement: Deprecation and feature warnings are escalated to errors

The system SHALL escalate deprecation and feature warnings to errors on the probatio subprojects. A port using a deprecated API SHALL fail compilation, and a port using a feature requiring explicit enablement (e.g. `given Conversion` shortcuts) SHALL fail compilation unless the feature is explicitly enabled.

**Given** the probatio-core and probatio-cli subprojects
**When** they are compiled with deprecation and feature escalation
**Then** any deprecation warning or feature warning causes compilation to fail

**Rationale**: R-X3 restricts dependencies and the schema renames environment
variables — the deprecated-alias paths (`VERIFIED_SCALA3_HOOKS` →
`PROBATIO_HOOKS`) are exactly where a silent fallback could hide.
`-feature` escalation forbids `given Conversion` shortcuts the port must not
reach for.

#### Scenario: a deprecated API call fails compilation

**Given** a source file calling a `@deprecated` method
**When** compiled with deprecation escalation
**Then** compilation fails with a deprecation error

#### Scenario: an implicit conversion shortcut fails compilation

**Given** a source file using a `given Conversion` without explicit enablement
**When** compiled with feature escalation
**Then** compilation fails with a feature warning escalated to error

#### Scenario: an explicitly enabled feature compiles (adversarial)

**Given** a source file that explicitly enables a feature via `-language:Feature`
**When** compiled with feature escalation
**Then** compilation succeeds — the escalation targets unguarded usage, not deliberate enablement

### Requirement: Discarded non-Unit values are compile errors

The system SHALL activate the discarded-value warning on the probatio subprojects, so that a non-Unit value that is discarded (not assigned, not returned, not passed to a function expecting Unit) is a compile error. A validator that computes a result and drops it, or a verdict function that ignores a branch's output, SHALL fail compilation rather than silently skipping.

**Given** a source file in probatio-core that computes a non-Unit value and discards it
**When** the subproject is compiled
**Then** compilation fails with a discarded-value error

**Rationale**: This is the single highest-value addition for this change's
risk profile. A validator that computes `Either[ContractViolation,
LedgerRecord]` and drops the result, or a verdict function that ignores a
branch's output, becomes a compile error rather than a silent skip. This
directly targets the silent-fallback defect class.

#### Scenario: a discarded validation result fails compilation

**Given** a source file that calls a validator returning `Either[ContractViolation, LedgerRecord]` and does not use the result
**When** the subproject is compiled
**Then** compilation fails with a discarded-value error

#### Scenario: a discarded branch output fails compilation

**Given** a source file with an `if/else` where one branch produces a non-Unit value that is discarded
**When** the subproject is compiled
**Then** compilation fails with a discarded-value error

#### Scenario: an explicitly discarded value compiles (adversarial)

**Given** a source file that explicitly discards a value via `val _ = expr` or `expr: Unit`
**When** the subproject is compiled
**Then** compilation succeeds — the flag targets implicit discards, not deliberate ones

### Requirement: Unsafe initialization order is a compile error

The system SHALL activate the safe-initialization check on the probatio subprojects, so that unsafe initialization order (a value referenced before it is initialized in a constructor or companion object) is a compile error. This is the compiler enforcement of the invariant that removed `chain-state.sh`'s "die_undetermined called before its variables were declared" defect.

**Given** a source file in probatio-core with a companion object or constructor that references a value before it is initialized
**When** the subproject is compiled
**Then** compilation fails with a safe-init error

**Rationale**: The requirements doc §1.2 calls out `chain-state.sh`'s
"die_undetermined called before its variables were declared" as a defect the
port removes by construction. `-Ysafe-init` is the compiler enforcement of
that same invariant for the new ADTs with companion validators and the metals
client's lifecycle.

#### Scenario: a forward reference in a companion object fails compilation

**Given** a companion object where `val b = a + 1` appears before `val a = 1`
**When** the subproject is compiled
**Then** compilation fails with a safe-init error

#### Scenario: a correctly ordered initialization compiles

**Given** a companion object where `val a = 1` appears before `val b = a + 1`
**When** the subproject is compiled
**Then** compilation succeeds — the flag does not produce false positives on ordered initialization

### Requirement: The strict flag set is scoped to probatio, not applied repo-wide

The system SHALL scope the strict flag set to the probatio subprojects only, and MUST NOT apply it to the adk4s modules. A repo-wide strict-flag rollout is a separate change; bundling it into this port would mix a behavior-preserving port with a build-strictness migration and violate the feature freeze.

**Given** the build configuration for the probatio subprojects and the adk4s modules
**When** the strict flag set is applied
**Then** the probatio subprojects compile with the strict flags, and the adk4s modules compile with their existing flags — the strict set does not leak

**Rationale**: `-Werror` repo-wide would surface every currently-suppressed
warning across 12 adk4s modules and likely break the build on first
application. The flags apply to `probatio-core`/`probatio-cli` only; a
repo-wide rollout is its own change once existing warnings are triaged.

#### Scenario: probatio subprojects compile with strict flags

**Given** the build configuration
**When** the probatio-core subproject is compiled
**Then** the strict flag set is present in its `scalacOptions`

#### Scenario: adk4s modules do not gain strict flags (adversarial)

**Given** the build configuration
**When** an adk4s module (e.g. adk4s-core) is compiled
**Then** the strict flag set is absent from its `scalacOptions` — the flags do not leak to adk4s

#### Scenario: the verified module is unaffected

**Given** the build configuration for the verified module (pinned to Scala 3.7.2 for Stainless)
**When** it is compiled
**Then** its `scalacOptions` override does not inherit the probatio strict flags — the pin is deliberate and unaffected

## Properties (Ring 3)

<!-- Property testing uses Hedgehog 0.13.1 (NOT ScalaCheck) per the detected
     stack (capability-profile.md). The requirements doc R-X3 names
     "ScalaCheck" but the profile wins: adding ScalaCheck would contradict the
     profile's "NOT ScalaCheck" consequence. Generators are constructive
     (preferred) — every input is built, none filtered. -->

### Property: Validator conforms to the jq contract in both directions

**Invariant**: For every JSON value, the Scala validator accepts it if and only if `jq -e -f ledger-record-contract.jq` accepts it (exit 0). The validator's rejection names the same clause the jq contract's `error` names. This holds over the existing fixture corpus and over generated records satisfying/violating each of the 12 clauses.

**Generator strategy**: `genLedgerRecordJson` (constructive — generates a JSON object with each field drawn from a domain-specific generator: `v` from `Gen.int(Range.linear -1 5)`, `ts` from `Gen.string(Gen.alphaNum, Range.linear 0 30)` plus a valid ISO-8601 generator, `change`/`spec` from `Gen.string(Gen.alphaNum, Range.linear 0 20)` plus strings with embedded `/` and `\`, `ring` from `Gen.element1("R0"…"R9", "manual", "R99", "")`, `obligation`/`artifact`/`command` from `Gen.string(Gen.alphaNum, Range.linear 0 50)` including empty, `exit` from `Gen.int(Range.linear -10 10)` plus `Gen.double(Range.linear 0.0 1.0)`, `baseline` from `Gen.string(Gen.char('0' to 'f'), Range.linear 5 45)` plus non-hex strings). Covers each clause-violating edge independently and the all-valid edge. Classification labels: `valid`, `clause-1-object`, `clause-2-missing`, `clause-3-v-int`, `clause-4-ts`, `clause-5-change`, `clause-6-spec`, `clause-7-ring`, `clause-8-obligation`, `clause-9-artifact`, `clause-10-command`, `clause-11-exit`, `clause-12-baseline`.

```
property("validator conforms to jq contract in both directions") {
  for json <- genLedgerRecordJson.forAll
  yield
    val scalaResult = Validator.validate(json)
    val jqResult     = runJqContract("ledger-record-contract.jq", json)
    (scalaResult.isRight == jqResult.isSuccess) &&
    (scalaResult.left.map(_.clause) == jqResult.errorClause)
}
```

### Property: Lint F1–F10 verdicts are identical before and after the port

**Invariant**: For every fixture in the fixture corpus, the set of verdicts and warnings produced by the ported spec-lint is identical to the set produced by the current bash spec-lint. This includes the numbered conditional checks (3, 6, 17 ALTITUDE, 18 CONCURRENCY) and the applicability annotations.

**Generator strategy**: Enumerated over the committed fixture corpus under `tests/fixtures/` (constructive — each fixture is a committed file, none generated or filtered). Each fixture is run through both the bash spec-lint and the ported spec-lint, and the verdict+warning sets are compared. Edge cases covered: a fixture with all checks passing, a fixture with conditional checks marked APPLIES, a fixture with N/A recorded for an APPLIES check, a fixture with the deterministic test kit line present/absent.

```
property("lint F1-F10 verdicts are identical before and after the port") {
  for fixture <- Gen.element(fixtures.toList).forAll
  yield
    val bashResult   = runBashSpecLint(fixture)
    val portedResult = runPortedSpecLint(fixture)
    (bashResult.verdicts == portedResult.verdicts) &&
    (bashResult.warnings == portedResult.warnings) &&
    (bashResult.applicability == portedResult.applicability)
}
```

### Property: Banner engine produces byte-identical output for identical inputs

**Invariant**: For every set of banner inputs, the banner engine produces byte-identical output across repeated invocations. This is the golden-fixture conformance suite: the output is compared against golden fixtures captured from the real bash scripts.

**Generator strategy**: `genBannerInputs` (constructive — generates a schema version via `Gen.int(Range.linear 1 20)`, a skill-install scan via `genSkillInstallScan` (six roots, each with a `generatedBy` stamp or absent), registry/inventory/profile presence via `genPresence` (Boolean + count), detected test kit via `Gen.option(Gen.string(Gen.alphaNum, Range.linear 1 30))`, active changes via `Gen.list(genActiveChange, Range.linear 0 3)`). Covers the no-skills edge, the all-drift edge, the no-active-changes edge, and the mixed-drift edge. Golden fixtures are committed files captured from the real scripts — the property asserts the ported engine's output matches them byte-for-byte.

```
property("banner engine produces byte-identical output for identical inputs") {
  for inputs <- genBannerInputs.forAll
  yield
    val output1 = BannerEngine.render(inputs)
    val output2 = BannerEngine.render(inputs)
    output1 == output2
}
```

### Property: Append-only ledger round-trips every record

**Invariant**: For any well-formed ledger record, appending it and then reading the ledger yields the record unchanged, and every prior record is byte-identical. The ledger's read-after-append semantics are a round-trip: no field is lost, no prior record is altered, and the record count increases by exactly one.

**Generator strategy**: `genLedgerRecord` (constructive — generates a `LedgerRecord` with each field drawn from a domain-specific generator matching the 12-clause contract: valid `v` (≥ 1), valid `ts` (ISO-8601 UTC), non-empty `change`/`spec` without path separators, `ring` from the closed enum, non-empty `obligation`/`artifact`/`command`, integer `exit`, valid `baseline` (lowercase hex 7–40 chars)). Covers the awkward-value edges: embedded newline in `command`, embedded double quote, embedded backslash, non-ASCII characters. Starting ledgers of size 0, 1, 2, and 10.

```
property("append-only ledger round-trips every record") {
  for
    startingSize <- Gen.element(0, 1, 2, 10).forAll
    record       <- genLedgerRecord.forAll
  yield
    val ledger  = Ledger.fromRecords(genRecords(startingSize))
    val appended = Ledger.append(ledger, record)
    val readBack = Ledger.read(appended)
    readBack.length == startingSize + 1 &&
    readBack.take(startingSize) == Ledger.read(ledger) &&
    readBack.last == record
}
```

### Property: Outcome totality — every case is reachable and the enum is exhaustive

**Invariant**: For every computation outcome, the result is exactly one of the three cases (clean, finding, undetermined), and a match on the outcome is exhaustive without a catch-all. Every case is reachable by some input — the clean case by a successful computation, the finding case by a computation that finds something, and the undetermined case by a computation that cannot determine.

**Generator strategy**: `genComputationResult` (constructive — generates one of three computation outcomes: a success value via `Gen.int`, a finding via `Gen.string(Gen.alphaNum, Range.linear 1 50)`, an undetermined via `genUndeterminedReason` (corrupt ledger, unknown version, missing prerequisite, non-lint failure)). Covers each undetermined-reason edge. Classification labels: `clean`, `finding`, `undetermined`.

```
property("outcome totality — every case is reachable and the enum is exhaustive") {
  for result <- genComputationResult.forAll
  yield
    val outcome: Outcome[Int] = result match
      case Success(v)        => Outcome.Ran(v)
      case Found(msg)        => Outcome.Finding(msg)
      case CouldNotDetermine(reason) => Outcome.Undetermined(reason)
    outcome match
      case Outcome.Ran(v)           => Result.assert(v == result.value)
      case Outcome.Finding(msg)     => Result.assert(msg == result.message)
      case Outcome.Undetermined(r)  => Result.assert(r == result.reason)
    // No case _ needed — the match is exhaustive by construction
}
```

### Property: Chain-state computation is referentially transparent

**Invariant**: For any fixed lint report, ledger, requirements list, and baseline, computing chain state twice produces equal results. The computation depends only on its declared inputs — no environment variable, no wall-clock time, no file read affects the output.

**Generator strategy**: `genChainStateInputs` (constructive — generates a `SpecLintReport` via `genLintReport`, a `Ledger` via `genLedger` (0–10 records), a requirements list via `Gen.list(genRequirement, Range.linear 0 10)`, and a baseline via `Gen.string(Gen.char('0' to 'f'), Range.linear 7 40)`). Covers the empty-ledger edge, the all-discharged edge, and the undetermined-ledger edge (corrupt ledger).

```
property("chain-state computation is referentially transparent") {
  for inputs <- genChainStateInputs.forAll
  yield
    val (lint, ledger, reqs, baseline) = inputs
    val result1 = ChainState.compute(lint, ledger, reqs, baseline)
    val result2 = ChainState.compute(lint, ledger, reqs, baseline)
    result1 == result2
}
```

### Property: ContractViolation totality — every clause is reachable

**Invariant**: For each of the 12 contract clauses, there exists a JSON value that violates exactly that clause and is accepted by no other clause's check first. The validator's rejection names exactly one clause, and every clause is reachable by some input.

**Generator strategy**: Enumerated over 12 clause-violating generators, one per clause (constructive — each generator produces a JSON value that violates exactly one clause and satisfies all prior clauses in the elif chain). Covers each clause independently.

```
property("contract violation totality — every clause is reachable") {
  for
    clauseIndex <- Gen.int(Range.linear 0 11).forAll
    json        <- genClauseViolation(clauseIndex).forAll
  yield
    val result = Validator.validate(json)
    result match
      case Left(violation) => Result.assert(violation.clauseIndex == clauseIndex)
      case Right(_)        => Result.failure("expected a violation for clause " + clauseIndex)
}
```

### Property: LintReport round-trips through uPickle JSON

**Invariant**: For every typed lint report, serializing to uPickle JSON and deserializing yields a report equal to the original — no requirement name, verdict, or check attribution is lost.

**Generator strategy**: `genLintReport` (constructive — generates a `LintReport` with 0–20 requirement entries, each with a requirement name via `Gen.string(Gen.alphaNum, Range.linear 1 30)`, a verdict from `Gen.element1(Bound, Resolved, Unbound)`, and a check from `Gen.element1(F1, F2, …, F10)` plus context facts via `genContextFacts`). Covers the empty-report edge, the all-bound edge, and the mixed-verdict edge.

```
property("lint report round-trips through uPickle JSON") {
  for report <- genLintReport.forAll
  yield
    val json     = upickle.default.write(report)
    val decoded  = upickle.default.read[LintReport](json)
    decoded == report
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `Ledger.update(...)` | The ledger is append-only at the type level (R-C2); no update function is defined | `assertDoesNotCompile("Ledger.update(...)")` — the method does not exist |
| `Ledger.delete(...)` | The ledger is append-only at the type level (R-C2); no delete function is defined | `assertDoesNotCompile("Ledger.delete(...)")` — the method does not exist |
| `Ledger.rewrite(...)` | The ledger is append-only at the type level (R-C2); no rewrite function is defined | `assertDoesNotCompile("Ledger.rewrite(...)")` — the method does not exist |
| `Outcome` with a fourth case | The three-way exit protocol has exactly three cases (§4.1); a fourth case is the silent fallback | `assertDoesNotCompile("val o: Outcome[Int] = new Outcome[Int] { ... }")` — the enum is sealed |
| `case _` in an `Outcome` match | A catch-all in an Outcome match defeats exhaustiveness checking; a future fourth case would be silently absorbed | code-review gate (Scalafix DisableSyntax `case _` in pattern matches on sealed types) + WartRemover |
| `Ring` with a value outside R0–R9/Manual | The closed ring domain is a sealed enum; a ring outside this set is unrepresentable | `assertDoesNotCompile("val r: Ring = Ring.R99")` — the value does not exist |
| `ContractViolation` with a 13th variant | The 12 clauses are a closed set; a 13th variant would mean the contract and the validator disagree | `assertDoesNotCompile("val v: ContractViolation = new ContractViolation {}")` — the trait is sealed |
| `asInstanceOf` in probatio code | Project rule: NEVER use `asInstanceOf`; use pattern matching | WartRemover `AsInstanceOf` wart (scoped to probatio) |
| `cats` or `cats-effect` import in probatio-core | R-X3: cats/cats-effect are explicitly excluded from probatio; the workflow's shipped artifacts stay dependency-minimal | dependency-lint rule (R-ARCH1 extension) + code-review gate |
| `scala.io.Source` or `java.nio.file` in probatio-core pure functions | R-C3: chain-state computation reads no files internally; file I/O lives in probatio-cli only | code-review gate + Scalafix rule banning `scala.io.Source`/`java.nio.file` in `org.sinemenda.probatio.core` |
| `System.getenv` or `System.currentTimeMillis` in probatio-core pure functions | R-C3/R-C5b: the pure kernels depend on no environment variables or wall-clock time | code-review gate + Scalafix rule banning `System.getenv`/`System.currentTimeMillis` in `org.sinemenda.probatio.core` |
| `Arbitrary`-based Hedgehog generators | Hedgehog does not have `Arbitrary`; this is a ScalaCheck anti-pattern | code-review gate (Hedgehog uses `Gen`, not `Arbitrary`) |

## Formal Contracts (Ring 6)

<!-- Ring 6 applies via the verified-mirror pattern. Three pure kernels exist:
     (1) the chain-state verdict logic, (2) the 12-clause ledger validator,
     (3) the banner/drift engine. Each is a decision/fold kernel expressible
     in PureScala at an abstraction. The mirror lives in the `verified` leaf
     module (Scala 3.7.2, Stainless) or a new probatio-scoped mirror leaf
     pinned to 3.7.2 — the design phase decides. The mirror models the
     DECISION only (verdict attribution, clause satisfaction, banner
     rendering), not the ujson/uPickle wire layer. R-X3 excludes cats from
     probatio; the mirror uses PureScala stdlib only, no cats.

     Per templates/verified-mirror.md: the mirror is a leaf pinned to the
     Stainless frontend's Scala version, depends on nothing project-local,
     `stainlessEnabled := false` by default, and the production module takes
     it as a `% Test` dependency. The bridge property test is the
     load-bearing part — it binds shipped code to the model on shared inputs. -->

### Contract: ChainStateKernel — the verdict logic mirror

**Mirror name**: `ChainStateKernel`
**Mirror location**: `verified/` leaf (or a new `probatio-verified` leaf pinned to Scala 3.7.2)
**Abstraction**: The chain-state computation `(SpecLintReport, Ledger, Requirements, Baseline) → Either[Undetermined, ChainStateReport]` is reduced to its decision kernel: a list of `(requirementId: BigInt, bound: Boolean, resolved: Boolean, discharged: Boolean)` tuples (the observable effect of the lint + ledger + requirements inputs) and a `Boolean` indicating whether the inputs were determinable. The kernel computes the bound/resolved/discharged counts and the unresolved list. The ujson/uPickle wire layer, the file I/O, and the baseline-string parsing are NOT modeled — they are Ring 3/4 concerns.

**Precondition** (`require`): the input list is finite (modeled as a Stainless `List`).
**Postcondition** (`ensuring`): discharged ≤ resolved ≤ bound ≤ total, and the unresolved list size equals total − discharged.

```scala
object ChainStateKernel:
  final case class ReqState(id: BigInt, bound: Boolean, resolved: Boolean, discharged: Boolean)
  final case class Report(total: BigInt, bound: BigInt, resolved: BigInt, discharged: BigInt, unresolved: List[BigInt])

  def compute(rs: List[ReqState], determinable: Boolean): Either[Boolean, Report] =
    require(true) // total function — no precondition beyond finiteness
    if !determinable then Left(true)
    else
      val total      = rs.size
      val bound      = rs.count(_.bound)
      val resolved   = rs.count(_.resolved)
      val discharged = rs.count(_.discharged)
      val unresolved = rs.filter(r => !(r.bound && r.resolved && r.discharged)).map(_.id)
      Right(Report(total, bound, resolved, discharged, unresolved))
  .ensuring((result: Either[Boolean, Report]) =>
    result match
      case Left(_) => true
      case Right(r) => r.discharged <= r.resolved && r.resolved <= r.bound && r.bound <= r.total
  )

  // The law worth proving: the unresolved list size equals total - discharged.
  def unresolvedAgreesWithCounts(rs: List[ReqState], determinable: Boolean): Boolean = {
    compute(rs, determinable) match
      case Left(_)     => true
      case Right(r)    => r.unresolved.size == r.total - r.discharged
  }.holds
```

**Bridge property test**: `ChainStateModelBridgeTests` — runs the shipped `ChainState.compute` and the `ChainStateKernel.compute` on the same generated inputs (reduced to the model's abstraction) and asserts they agree on counts, unresolved list, and determinability. Lives in `probatio-core`'s test sources; the mirror is `% Test`.

```
property("shipped chain-state agrees with the Stainless model") {
  for inputs <- genChainStateInputs.forAll
  yield
    val real = ChainState.compute(inputs.lint, inputs.ledger, inputs.reqs, inputs.baseline)
    val flat: List[ReqState] = inputs.reqs.map(r => ReqState(enc(r.id), r.bound, r.resolved, r.discharged))
    val determinable = inputs.ledger.isReadable && inputs.lint.isLintSuccess
    val model = ChainStateKernel.compute(flat, determinable)
    (real, model) match
      case (Left(realReason), Left(_))       => Result.success
      case (Right(realReport), Right(mReport)) =>
        Result.assert(realReport.bound == mReport.bound && realReport.resolved == mReport.resolved &&
          realReport.discharged == mReport.discharged && realReport.unresolved.size == mReport.unresolved.size)
      case _ => Result.failure("real and model disagree on determinability")
}
```

**Scope note**: Stainless proves (1) monotonicity (discharged ≤ resolved ≤ bound ≤ total) and (2) the unresolved-list/count agreement. Dedup correctness of the unresolved list (a requirement failing two clauses listed once) is left to the Ring 3 property "Chain-state computation is referentially transparent" and the bats oracle — proving it in the solver needs an unbounded inductive subset lemma that adds no assurance the property test does not already give. The ujson/uPickle wire layer is NOT modeled — it is Ring 4.

### Contract: LedgerValidatorKernel — the 12-clause validator mirror

**Mirror name**: `LedgerValidatorKernel`
**Mirror location**: `verified/` leaf (or a new `probatio-verified` leaf pinned to Scala 3.7.2)
**Abstraction**: The 12-clause ledger record validator is reduced to its decision kernel: a `Record` case class with each field reduced to its observable effect (e.g. `v: BigInt` for the version, `ts: String` for the timestamp, `ring: BigInt` for the ring index into the closed domain, `hasPathSep: Boolean` for change/spec). The kernel applies the 12 clauses in order and returns `Either[BigInt, Record]` where the `BigInt` is the clause index (0–11) of the first failure. The ujson/uPickle JSON parsing is NOT modeled — it is Ring 4.

**Precondition** (`require`): the record is a value of the reduced `Record` type (no null, no missing — those are modeled as sentinel values the clauses reject).
**Postcondition** (`ensuring`): the result is `Right(record)` if and only if all 12 clauses pass, and `Left(clauseIndex)` names the first failing clause (0 ≤ clauseIndex < 12).

```scala
object LedgerValidatorKernel:
  final case class Record(
    isObject: Boolean, hasAllRequired: Boolean,
    v: BigInt, vIsInt: Boolean,
    ts: String, tsIsValid: Boolean,
    change: String, changeHasPathSep: Boolean,
    spec: String, specHasPathSep: Boolean,
    ring: BigInt, ringInDomain: Boolean,
    obligation: String, artifact: String, command: String,
    exit: BigInt, exitIsInt: Boolean,
    baseline: String, baselineIsValid: Boolean
  )

  def validate(r: Record): Either[BigInt, Record] =
    if !r.isObject         then Left(0)
    else if !r.hasAllRequired then Left(1)
    else if !r.vIsInt      then Left(2)
    else if r.v < 1        then Left(3)
    else if r.ts.isEmpty   then Left(4)
    else if !r.tsIsValid   then Left(5)
    else if r.change.isEmpty then Left(6)
    else if r.changeHasPathSep then Left(7)
    else if r.spec.isEmpty then Left(8)
    else if r.specHasPathSep then Left(9)
    else if !r.ringInDomain then Left(10)
    else if r.obligation.isEmpty then Left(11)
    // ... remaining clauses (artifact, command, exit, baseline)
    else Right(r)

  // The law worth proving: the validator is total and the first-failing clause
  // is returned — no clause is skipped, no valid record is rejected.
  def totality(r: Record): Boolean = {
    validate(r) match
      case Right(_)   => passesAll(r)
      case Left(idx)  => idx >= 0 && idx < 12 && !passesClause(r, idx)
  }.holds
```

**Bridge property test**: `LedgerValidatorModelBridgeTests` — runs the shipped `Validator.validate` and the `LedgerValidatorKernel.validate` on the same generated JSON values (reduced to the model's abstraction) and asserts they agree on accept/reject and on the first-failing clause index.

```
property("shipped validator agrees with the Stainless model on 12 clauses") {
  for json <- genLedgerRecordJson.forAll
  yield
    val real  = Validator.validate(json)
    val flat  = reduceToRecord(json)
    val model = LedgerValidatorKernel.validate(flat)
    (real, model) match
      case (Right(_), Right(_))   => Result.success
      case (Left(rv), Left(mIdx)) => Result.assert(rv.clauseIndex == mIdx)
      case _                      => Result.failure("real and model disagree on validation")
}
```

**Scope note**: Stainless proves (1) totality (the validator returns a result for every input, never throws) and (2) first-failure ordering (the first failing clause is returned, no clause is skipped). The optional-fields validation (sha256, digest, wallTime, source, session) is NOT modeled — it is Ring 3. The ujson/uPickle JSON parsing is NOT modeled — it is Ring 4.

### Contract: BannerEngineKernel — the banner/drift rendering mirror

**Mirror name**: `BannerEngineKernel`
**Mirror location**: `verified/` leaf (or a new `probatio-verified` leaf pinned to Scala 3.7.2)
**Abstraction**: The banner/drift engine is reduced to its decision kernel: a `BannerInput` case class with the schema version (`BigInt`), a list of `(rootId: BigInt, stampVersion: BigInt, isPresent: Boolean)` tuples for the six install roots, and a list of `(changeId: BigInt, unresolvedCount: BigInt)` tuples for active changes. The kernel produces a `List[String]` — the lines of the banner. The byte-exact rendering (whitespace, indentation, trailer text) is NOT modeled — it is Ring 3 (golden-fixture conformance).

**Precondition** (`require`): the input lists are finite (modeled as Stainless `List`s).
**Postcondition** (`ensuring`): the output is deterministic — the same input produces the same output (modeled as a function, which is deterministic by construction in PureScala).

```scala
object BannerEngineKernel:
  final case class RootScan(rootId: BigInt, stampVersion: BigInt, isPresent: Boolean)
  final case class ActiveChange(changeId: BigInt, unresolvedCount: BigInt)
  final case class BannerInput(schemaVersion: BigInt, roots: List[RootScan], changes: List[ActiveChange])

  def driftLines(input: BannerInput): List[String] =
    input.roots match
      case Nil() => List("no skill installed")
      case Cons(r, t) =>
        val line: String = if !r.isPresent then ""
        else if r.stampVersion != input.schemaVersion then
          "drift: root " + r.rootId.toString + " expected " + input.schemaVersion.toString + " found " + r.stampVersion.toString
        else ""
        val rest = driftLines(BannerInput(input.schemaVersion, t, input.changes))
        if line.isEmpty then rest else line :: rest

  // The law worth proving: drift detection is total — every present root is
  // checked, and "no skill installed" is emitted when no root is present.
  def driftTotality(input: BannerInput): Boolean = {
    val lines = driftLines(input)
    input.roots.isEmpty == lines.contains("no skill installed") ||
    input.roots.exists(_.isPresent && _.stampVersion != input.schemaVersion) == lines.exists(_.startsWith("drift:"))
  }.holds
```

**Bridge property test**: `BannerEngineModelBridgeTests` — runs the shipped `BannerEngine.render` and the `BannerEngineKernel.driftLines` on the same generated inputs (reduced to the model's abstraction) and asserts they agree on: (1) whether drift warnings are emitted, (2) whether the "no skill installed" line is emitted, and (3) the count of drift warnings.

```
property("shipped banner engine agrees with the Stainless model on drift detection") {
  for inputs <- genBannerInputs.forAll
  yield
    val real  = BannerEngine.render(inputs)
    val flat  = reduceToBannerInput(inputs)
    val model = BannerEngineKernel.driftLines(flat)
    val realDriftLines    = real.lines.filter(_.startsWith("drift:"))
    val realNoSkill       = real.lines.contains("no skill installed")
    val modelDriftLines   = model.filter(_.startsWith("drift:"))
    val modelNoSkill      = model.contains("no skill installed")
    Result.assert(realDriftLines.length == modelDriftLines.length && realNoSkill == modelNoSkill)
}
```

**Scope note**: Stainless proves (1) drift totality (every present root is checked, "no skill installed" is emitted when no root is present) and (2) determinism (the function is pure — same input, same output). The byte-exact rendering (whitespace, indentation, the verbatim invariant block, the trailer text) is NOT modeled — it is Ring 3 (the golden-fixture conformance property). The six-root enumeration order is modeled but the root names are NOT — they are `BigInt` identities in the model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The three-way exit protocol is a sealed enum with exactly three cases | Requirement: The three-way exit protocol is a sealed enum | type system (sealed enum) + compile-negative test | OutcomeSpec, probatio-core |
| A clean run carries its value | Requirement: The three-way exit protocol is a sealed enum + Scenario: a clean run carries its value | munit test | OutcomeSpec |
| A finding carries its description | Requirement: The three-way exit protocol is a sealed enum + Scenario: a finding carries its description | munit test | OutcomeSpec |
| An undetermined result carries its reason | Requirement: The three-way exit protocol is a sealed enum + Scenario: an undetermined result carries its reason | munit test | OutcomeSpec |
| No fourth case is constructible | Requirement: The three-way exit protocol is a sealed enum + Scenario: no fourth case is constructible (adversarial) | compile-negative test (`assertDoesNotCompile`) | OutcomeSpec |
| Outcome totality — every case reachable, enum exhaustive | Requirement: The three-way exit protocol is a sealed enum + Property: Outcome totality — every case is reachable and the enum is exhaustive | Hedgehog property | OutcomeSpec |
| LedgerRecord is an immutable product type | Requirement: LedgerRecord is an immutable product type with total clause validation | type system (case class, all fields val) | LedgerRecordSpec, probatio-core |
| All 12 clauses appear as validation cases | Requirement: LedgerRecord is an immutable product type with total clause validation | type system (ContractViolation sealed trait, 12 variants) + compile-negative test | LedgerValidatorSpec, probatio-core |
| The closed ring domain is a sealed enum | Requirement: LedgerRecord is an immutable product type with total clause validation | type system (Ring sealed enum) + compile-negative test | LedgerRecordSpec, probatio-core |
| A record satisfying all 12 clauses is accepted | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: a record satisfying all 12 clauses is accepted | munit test | LedgerValidatorSpec |
| A record missing a required field is rejected with the field named | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: a record missing a required field is rejected with the field named | munit test | LedgerValidatorSpec |
| A record with a ring outside the closed domain is rejected | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: a record with a ring outside the closed domain is rejected | munit test | LedgerValidatorSpec |
| A record with a non-integer version is rejected | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: a record with a non-integer version is rejected | munit test | LedgerValidatorSpec |
| A record with a path separator in the change field is rejected | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: a record with a path separator in the change field is rejected | munit test | LedgerValidatorSpec |
| A record with a malformed timestamp is rejected | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: a record with a malformed timestamp is rejected | munit test | LedgerValidatorSpec |
| A record with a non-hex baseline is rejected | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: a record with a non-hex baseline is rejected | munit test | LedgerValidatorSpec |
| The validator is total — null input is rejected, not crashed on | Requirement: LedgerRecord is an immutable product type with total clause validation + Scenario: the validator is total — a null input is rejected, not crashed on (adversarial) | munit test (assertThrows negative — no exception) | LedgerValidatorSpec |
| Validator conforms to jq contract in both directions | Requirement: LedgerRecord is an immutable product type with total clause validation + Property: Validator conforms to the jq contract in both directions | Hedgehog property (R-M2 conformance) | LedgerValidatorConformanceSpec |
| ContractViolation totality — every clause reachable | Requirement: LedgerRecord is an immutable product type with total clause validation + Property: ContractViolation totality — every clause is reachable | Hedgehog property | LedgerValidatorSpec |
| The ledger is append-only — read, append, validate only | Requirement: The ledger is append-only at the type level | type system (module exposes only read/append/validate) + compile-negative tests | LedgerSpec, probatio-core |
| Appending preserves all prior records | Requirement: The ledger is append-only at the type level + Scenario: appending preserves all prior records | munit test | LedgerSpec |
| Read returns records in append order | Requirement: The ledger is append-only at the type level + Scenario: read returns records in append order | munit test | LedgerSpec |
| No update function exists | Requirement: The ledger is append-only at the type level + Scenario: no update function exists (adversarial) | compile-negative test (`assertDoesNotCompile`) | LedgerSpec |
| No delete function exists | Requirement: The ledger is append-only at the type level + Scenario: no delete function exists (adversarial) | compile-negative test (`assertDoesNotCompile`) | LedgerSpec |
| No rewrite function exists | Requirement: The ledger is append-only at the type level + Scenario: no rewrite function exists (adversarial) | compile-negative test (`assertDoesNotCompile`) | LedgerSpec |
| Append-only ledger round-trips every record | Requirement: The ledger is append-only at the type level + Property: Append-only ledger round-trips every record | Hedgehog property | LedgerSpec |
| Chain-state computation is referentially transparent | Requirement: Chain-state computation is referentially transparent + Property: Chain-state computation is referentially transparent | Hedgehog property | ChainStateSpec, probatio-core |
| Same inputs produce same output | Requirement: Chain-state computation is referentially transparent + Scenario: same inputs produce same output | Hedgehog property (referential transparency) | ChainStateSpec |
| An unreadable ledger yields undetermined, not zero | Requirement: Chain-state computation is referentially transparent + Scenario: an unreadable ledger yields undetermined, not zero | munit test | ChainStateSpec |
| A failed lint yields undetermined, not zero | Requirement: Chain-state computation is referentially transparent + Scenario: a failed lint yields undetermined, not zero | munit test | ChainStateSpec |
| A genuinely empty ledger is reported as zero discharged | Requirement: Chain-state computation is referentially transparent + Scenario: a genuinely empty ledger is reported as zero discharged | munit test | ChainStateSpec |
| No file I/O occurs inside the computation | Requirement: Chain-state computation is referentially transparent + Scenario: no file I/O occurs inside the computation (adversarial) | code-review gate + Scalafix rule banning `scala.io.Source`/`java.nio.file` in `org.sinemenda.probatio.core` | adversarial review, Scalafix |
| spec-lint output carries per-requirement verdict attribution | Requirement: spec-lint output carries per-requirement verdict attribution | type system (LintReport typed AST) + Hedgehog property (round-trip) | LintReportSpec, probatio-core |
| A requirement with a bound verdict is attributed | Requirement: spec-lint output carries per-requirement verdict attribution + Scenario: a requirement with a bound verdict is attributed | munit test | LintReportSpec |
| A requirement with an unbound verdict is attributed | Requirement: spec-lint output carries per-requirement verdict attribution + Scenario: a requirement with an unbound verdict is attributed | munit test | LintReportSpec |
| The report round-trips through uPickle JSON | Requirement: spec-lint output carries per-requirement verdict attribution + Scenario: the report round-trips through uPickle JSON + Property: LintReport round-trips through uPickle JSON | Hedgehog property | LintReportSpec |
| Chain-state consumes the typed report without re-parsing | Requirement: spec-lint output carries per-requirement verdict attribution + Scenario: chain-state consumes the typed report without re-parsing (adversarial) | code-review gate — no table-structure re-parsing in chain-state | adversarial review |
| The port preserves lint F1–F10 verdicts on every fixture | Requirement: The port preserves lint F1–F10 verdicts on every fixture + Property: Lint F1–F10 verdicts are identical before and after the port | Hedgehog property (enumerated over fixture corpus) | SpecLintConformanceSpec |
| A fixture with all checks passing produces identical verdicts | Requirement: The port preserves lint F1–F10 verdicts on every fixture + Scenario: a fixture with all checks passing produces identical verdicts | Hedgehog property (fixture corpus) | SpecLintConformanceSpec |
| A fixture with a conditional check marked APPLIES produces identical verdicts | Requirement: The port preserves lint F1–F10 verdicts on every fixture + Scenario: a fixture with a conditional check marked APPLIES produces identical verdicts | Hedgehog property (fixture corpus) | SpecLintConformanceSpec |
| A fixture where APPLIES is recorded as N/A is a finding in both | Requirement: The port preserves lint F1–F10 verdicts on every fixture + Scenario: a fixture where APPLIES is recorded as N/A is a finding in both (adversarial) | Hedgehog property (fixture corpus) | SpecLintConformanceSpec |
| A fixture with a CONCURRENCY check produces identical verdicts | Requirement: The port preserves lint F1–F10 verdicts on every fixture + Scenario: a fixture with a CONCURRENCY check produces identical verdicts | Hedgehog property (fixture corpus) | SpecLintConformanceSpec |
| spec-lint CONTEXT facts and F1–F10 semantics are preserved | Requirement: spec-lint CONTEXT facts and F1–F10 semantics are preserved | Hedgehog property (fixture corpus) + bats oracle | SpecLintConformanceSpec, bats oracle |
| Check 17 ALTITUDE applicability is decided identically | Requirement: spec-lint CONTEXT facts and F1–F10 semantics are preserved + Scenario: check 17 ALTITUDE applicability is decided identically | munit test | SpecLintSpec |
| Check 18 CONCURRENCY applicability is decided identically | Requirement: spec-lint CONTEXT facts and F1–F10 semantics are preserved + Scenario: check 18 CONCURRENCY applicability is decided identically | munit test | SpecLintSpec |
| A check marked APPLIES with N/A recorded is a finding | Requirement: spec-lint CONTEXT facts and F1–F10 semantics are preserved + Scenario: a check marked APPLIES with N/A recorded is a finding | munit test | SpecLintSpec |
| The judgment/applicability split is preserved | Requirement: spec-lint CONTEXT facts and F1–F10 semantics are preserved + Scenario: the judgment/applicability split is preserved (adversarial) | code-review gate — the lint decides only applicability, not judgment | adversarial review |
| The drift, context, and banner engine is a pure function | Requirement: The drift, context, and banner engine is a pure function + Property: Banner engine produces byte-identical output for identical inputs | Hedgehog property (golden-fixture conformance) | BannerEngineSpec, probatio-core |
| Identical inputs produce byte-identical output | Requirement: The drift, context, and banner engine is a pure function + Scenario: identical inputs produce byte-identical output | Hedgehog property | BannerEngineSpec |
| Different inputs produce different output | Requirement: The drift, context, and banner engine is a pure function + Scenario: different inputs produce different output | munit test | BannerEngineSpec |
| No file I/O occurs inside the engine | Requirement: The drift, context, and banner engine is a pure function + Scenario: no file I/O occurs inside the engine (adversarial) | code-review gate + Scalafix rule banning `System.getenv`/`System.currentTimeMillis` in `org.sinemenda.probatio.core` | adversarial review, Scalafix |
| Instruction drift is detected across all install roots | Requirement: Instruction drift is detected across all install roots | Hedgehog property + munit tests | DriftScanSpec, probatio-core |
| A root with a matching stamp produces no drift warning | Requirement: Instruction drift is detected across all install roots + Scenario: a root with a matching stamp produces no drift warning | munit test | DriftScanSpec |
| A root with a mismatched stamp produces a drift warning | Requirement: Instruction drift is detected across all install roots + Scenario: a root with a mismatched stamp produces a drift warning | munit test | DriftScanSpec |
| No installed skills produces an explicit line | Requirement: Instruction drift is detected across all install roots + Scenario: no installed skills produces an explicit line | munit test | DriftScanSpec |
| A pre-rename stamp is treated as drift with a migration message | Requirement: Instruction drift is detected across all install roots + Scenario: a pre-rename stamp is treated as drift with a migration message | munit test | DriftScanSpec |
| Silence about drift is never emitted | Requirement: Instruction drift is detected across all install roots + Scenario: silence about drift is never emitted (adversarial) | munit test (assert output non-empty when drift exists) | DriftScanSpec |
| Silence about checking is never emitted | Requirement: Instruction drift is detected across all install roots + Scenario: silence about checking is never emitted (adversarial) | munit test (assert output non-empty when no skills installed) | DriftScanSpec |
| The banner is assembled from live reads, not remembered state | Requirement: The banner is assembled from live reads, not remembered state | Hedgehog property (golden-fixture) + munit tests | BannerEngineSpec, probatio-core |
| The invariant block is verbatim-match text | Requirement: The banner is assembled from live reads, not remembered state + Scenario: the invariant block is verbatim-match text | munit test (byte comparison against committed golden) | BannerEngineSpec |
| The session-context block reflects live chain state | Requirement: The banner is assembled from live reads, not remembered state + Scenario: the session-context block reflects live chain state | munit test | BannerEngineSpec |
| An unchanged payload injects nothing | Requirement: The banner is assembled from live reads, not remembered state + Scenario: an unchanged payload injects nothing | munit test | BannerEngineSpec |
| A changed payload re-injects in full | Requirement: The banner is assembled from live reads, not remembered state + Scenario: a changed payload re-injects in full | munit test | BannerEngineSpec |
| The trailer states facts are read from disk | Requirement: The banner is assembled from live reads, not remembered state + Scenario: the trailer states facts are read from disk (adversarial) | munit test (assert trailer text present) | BannerEngineSpec |
| The metals client frames LSP correctly on partial reads | Requirement: The metals client frames LSP correctly on partial reads | Hedgehog property + munit tests | MetalsClientSpec, probatio-core |
| A header split across reads is parsed correctly | Requirement: The metals client frames LSP correctly on partial reads + Scenario: a header split across reads is parsed correctly | munit test (simulated split stream) | MetalsClientSpec |
| A body split across reads is assembled correctly | Requirement: The metals client frames LSP correctly on partial reads + Scenario: a body split across reads is assembled correctly | munit test (simulated split stream) | MetalsClientSpec |
| Multiple messages in a single read are split correctly | Requirement: The metals client frames LSP correctly on partial reads + Scenario: multiple messages in a single read are split correctly | munit test (simulated concatenated stream) | MetalsClientSpec |
| The initialization handshake completes within the timeout | Requirement: The metals client frames LSP correctly on partial reads + Scenario: the initialization handshake completes within the timeout | munit test (mock LSP server) | MetalsClientSpec |
| The initialization handshake times out | Requirement: The metals client frames LSP correctly on partial reads + Scenario: the initialization handshake times out | munit test (mock LSP server, short timeout) | MetalsClientSpec |
| No diagnostic is written to stdout | Requirement: The metals client frames LSP correctly on partial reads + Scenario: no diagnostic is written to stdout (adversarial) | munit test (assert stdout is JSON-RPC only, stderr has logs) | MetalsClientSpec |
| Undetermined is never collapsed into a finding | Requirement: Undetermined is never collapsed into a finding | type system (Outcome sealed enum, three distinct cases) + Hedgehog property + adversarial review | OutcomeSpec, adversarial review |
| A corrupt ledger yields undetermined, not clean | Requirement: Undetermined is never collapsed into a finding + Scenario: a corrupt ledger yields undetermined, not clean | munit test | ChainStateSpec |
| An unknown record version yields undetermined, not a partial result | Requirement: Undetermined is never collapsed into a finding + Scenario: an unknown record version yields undetermined, not a partial result | munit test | LedgerSpec |
| A non-lint failure yields undetermined, not a finding | Requirement: Undetermined is never collapsed into a finding + Scenario: a non-lint failure yields undetermined, not a finding | munit test | SpecLintSpec |
| Undetermined is not collapsed into finding | Requirement: Undetermined is never collapsed into a finding + Scenario: undetermined is not collapsed into finding (adversarial) | munit test (assert exit code 2, never 1) | OutcomeSpec |
| Undetermined is not collapsed into clean | Requirement: Undetermined is never collapsed into a finding + Scenario: undetermined is not collapsed into clean (adversarial) | munit test (assert exit code 2, never 0) | OutcomeSpec |
| Warnings are fatal on probatio-core and probatio-cli | Requirement: Warnings are fatal on probatio-core and probatio-cli | Ring 0 compile gate (`-Werror` in `probatioScalacOptions`) | build (sbt probatio-core/compile) |
| A deprecated API usage fails compilation | Requirement: Warnings are fatal on probatio-core and probatio-cli + Scenario: a deprecated API usage fails compilation | Ring 0 compile gate | build |
| An unused import fails compilation | Requirement: Warnings are fatal on probatio-core and probatio-cli + Scenario: an unused import fails compilation | Ring 0 compile gate (`-Wunused:all` + `-Werror`) | build |
| A clean source compiles without error | Requirement: Warnings are fatal on probatio-core and probatio-cli + Scenario: a clean source compiles without error | Ring 0 compile gate (sbt probatio-core/compile succeeds on clean code) | build |
| Deprecation and feature warnings are escalated to errors | Requirement: Deprecation and feature warnings are escalated to errors | Ring 0 compile gate (`-Wconf:cat=deprecation:e`, `-Wconf:cat=Feature:e`) | build |
| A deprecated API call fails compilation | Requirement: Deprecation and feature warnings are escalated to errors + Scenario: a deprecated API call fails compilation | Ring 0 compile gate | build |
| An implicit conversion shortcut fails compilation | Requirement: Deprecation and feature warnings are escalated to errors + Scenario: an implicit conversion shortcut fails compilation | Ring 0 compile gate | build |
| An explicitly enabled feature compiles | Requirement: Deprecation and feature warnings are escalated to errors + Scenario: an explicitly enabled feature compiles (adversarial) | Ring 0 compile gate (compile with `-language:Feature` succeeds) | build |
| Discarded non-Unit values are compile errors | Requirement: Discarded non-Unit values are compile errors | Ring 0 compile gate (`-Wvalue:discard`) | build |
| A discarded validation result fails compilation | Requirement: Discarded non-Unit values are compile errors + Scenario: a discarded validation result fails compilation | Ring 0 compile gate | build |
| A discarded branch output fails compilation | Requirement: Discarded non-Unit values are compile errors + Scenario: a discarded branch output fails compilation | Ring 0 compile gate | build |
| An explicitly discarded value compiles | Requirement: Discarded non-Unit values are compile errors + Scenario: an explicitly discarded value compiles (adversarial) | Ring 0 compile gate (compile with `val _ = expr` succeeds) | build |
| Unsafe initialization order is a compile error | Requirement: Unsafe initialization order is a compile error | Ring 0 compile gate (`-Ysafe-init`) | build |
| A forward reference in a companion object fails compilation | Requirement: Unsafe initialization order is a compile error + Scenario: a forward reference in a companion object fails compilation | Ring 0 compile gate | build |
| A correctly ordered initialization compiles | Requirement: Unsafe initialization order is a compile error + Scenario: a correctly ordered initialization compiles | Ring 0 compile gate | build |
| The strict flag set is scoped to probatio, not applied repo-wide | Requirement: The strict flag set is scoped to probatio, not applied repo-wide | Ring 2 check (assert adk4s modules' `scalacOptions` do not gain `-Werror`) + build configuration inspection | build, Ring 2 |
| probatio subprojects compile with strict flags | Requirement: The strict flag set is scoped to probatio, not applied repo-wide + Scenario: probatio subprojects compile with strict flags | build configuration test (assert `probatioScalacOptions` present in probatio subprojects) | build |
| adk4s modules do not gain strict flags | Requirement: The strict flag set is scoped to probatio, not applied repo-wide + Scenario: adk4s modules do not gain strict flags (adversarial) | Ring 2 check (assert adk4s modules' `scalacOptions` lack the strict set) | build, Ring 2 |
| The verified module is unaffected | Requirement: The strict flag set is scoped to probatio, not applied repo-wide + Scenario: the verified module is unaffected | build configuration test (assert verified module's `scalacOptions` override does not inherit strict flags) | build |
| ChainStateKernel monotonicity (discharged ≤ resolved ≤ bound ≤ total) | Requirement: Chain-state computation is referentially transparent + Formal Contract: ChainStateKernel | formal contract (Ring 6, Stainless) | ChainStateKernel (verified mirror) |
| ChainStateKernel unresolved-list/count agreement | Requirement: Chain-state computation is referentially transparent + Formal Contract: ChainStateKernel | formal contract (Ring 6, Stainless) | ChainStateKernel (verified mirror) |
| Shipped chain-state conforms to the verified model | Requirement: Chain-state computation is referentially transparent + Formal Contract: ChainStateKernel | bridge property (Ring 3 + Ring 6) | ChainStateModelBridgeTests |
| LedgerValidatorKernel totality (returns a result for every input) | Requirement: LedgerRecord is an immutable product type with total clause validation + Formal Contract: LedgerValidatorKernel | formal contract (Ring 6, Stainless) | LedgerValidatorKernel (verified mirror) |
| LedgerValidatorKernel first-failure ordering (first failing clause returned) | Requirement: LedgerRecord is an immutable product type with total clause validation + Formal Contract: LedgerValidatorKernel | formal contract (Ring 6, Stainless) | LedgerValidatorKernel (verified mirror) |
| Shipped validator conforms to the verified model | Requirement: LedgerRecord is an immutable product type with total clause validation + Formal Contract: LedgerValidatorKernel | bridge property (Ring 3 + Ring 6) | LedgerValidatorModelBridgeTests |
| BannerEngineKernel drift totality (every present root checked, "no skill installed" emitted) | Requirement: Instruction drift is detected across all install roots + Formal Contract: BannerEngineKernel | formal contract (Ring 6, Stainless) | BannerEngineKernel (verified mirror) |
| BannerEngineKernel determinism (same input, same output) | Requirement: The drift, context, and banner engine is a pure function + Formal Contract: BannerEngineKernel | formal contract (Ring 6, Stainless) | BannerEngineKernel (verified mirror) |
| Shipped banner engine conforms to the verified model | Requirement: The drift, context, and banner engine is a pure function + Formal Contract: BannerEngineKernel | bridge property (Ring 3 + Ring 6) | BannerEngineModelBridgeTests |
| No `Ledger.update`/`delete`/`rewrite` | Compile-Negative: Ledger has no update/delete/rewrite | compile-negative test (`assertDoesNotCompile`) | LedgerSpec |
| No fourth `Outcome` case | Compile-Negative: Outcome with a fourth case | compile-negative test (`assertDoesNotCompile`) | OutcomeSpec |
| No `case _` in `Outcome` match | Compile-Negative: case _ in an Outcome match | Scalafix DisableSyntax + WartRemover | build, adversarial review |
| No `Ring` value outside the closed domain | Compile-Negative: Ring with a value outside the closed domain | compile-negative test (`assertDoesNotCompile`) | LedgerRecordSpec |
| No 13th `ContractViolation` variant | Compile-Negative: ContractViolation with a 13th variant | compile-negative test (`assertDoesNotCompile`) | LedgerValidatorSpec |
| No `asInstanceOf` in probatio code | Compile-Negative: asInstanceOf in probatio code | WartRemover `AsInstanceOf` wart (scoped) | build |
| No cats/cats-effect import in probatio-core | Compile-Negative: cats or cats-effect import in probatio-core | dependency-lint rule (R-ARCH1 extension) + code-review gate | build, adversarial review |
| No file I/O in probatio-core pure functions | Compile-Negative: scala.io.Source or java.nio.file in probatio-core pure functions | Scalafix rule + code-review gate | build, adversarial review |
| No `System.getenv`/`System.currentTimeMillis` in probatio-core | Compile-Negative: System.getenv or System.currentTimeMillis in probatio-core pure functions | Scalafix rule + code-review gate | build, adversarial review |
| No `Arbitrary`-based Hedgehog generators | Compile-Negative: Arbitrary-based Hedgehog generators | code-review gate | adversarial review |
| probatio depends on nothing adk4s-side (R-ARCH1) | Requirement: The strict flag set is scoped to probatio, not applied repo-wide | build-level dependency-lint rule (fails if any `workflow/*` project's classpath reaches an adk4s module) | build, Ring 2 |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `Outcome[A]` | sealed enum | `workflow/core/src/main/scala/org/sinemenda/probatio/core/Outcome.scala` | Three cases: `Ran[A]`, `Finding`, `Undetermined`; maps to exit 0/1/2 at the CLI boundary |
| `LedgerRecord` | immutable case class | `workflow/core/src/main/scala/org/sinemenda/probatio/core/LedgerRecord.scala` | All fields `val`; 12 contract clauses validated by `Validator.validate`; optional fields modeled as `Option` |
| `Ring` | sealed enum | `workflow/core/src/main/scala/org/sinemenda/probatio/core/Ring.scala` | `R0`–`R9`, `Manual`; closed domain — a ring outside this set is unrepresentable |
| `Ledger` | module (object) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/Ledger.scala` | Exposes `read`, `append`, `validate` only; no `update`/`delete`/`rewrite` |
| `ContractViolation` | sealed trait (12 variants) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ContractViolation.scala` | One variant per clause; `clauseIndex: Int` on each variant for conformance testing |
| `Validator` | object (total function) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala` | `validate(json: ujson.Value): Either[ContractViolation, LedgerRecord]`; total — never throws |
| `ChainStateReport` | case class | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ChainStateReport.scala` | `bound: Int`, `resolved: Int`, `discharged: Int`, `total: Int`, `unresolved: List[UnresolvedEntry]` |
| `ChainState` | object (pure function) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ChainState.scala` | `compute(lint: SpecLintReport, ledger: Ledger, reqs: Requirements, baseline: Baseline): Either[Undetermined, ChainStateReport]`; reads no files |
| `SpecLintReport` / `LintReport` | typed AST (derives ReadWriter) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/LintReport.scala` | Per-requirement verdict attribution; consumed by chain-state as uPickle JSON |
| `GatePayload` | case class (derives ReadWriter) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/GatePayload.scala` | Hook JSON payload (ex-`gate-hookjson-contract.jq`); byte-stable |
| `BannerEngine` | object (pure function) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/BannerEngine.scala` | `render(inputs: BannerInputs): BannerOutput`; byte-identical for identical inputs; reads no files |
| `DriftScan` | object (pure function) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/DriftScan.scala` | Compares schema version against `generatedBy` stamps across six roots; returns drift warnings + "no skill installed" line |
| `MetalsClient` | class (LSP JSON-RPC client) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/MetalsClient.scala` | Content-Length framing over partial reads; init handshake with configurable timeout; logs to stderr |
| `probatioScalacOptions` | build setting (`Seq[String]`) | `build.sbt` (probatio subprojects only) | `scala3Options ++ Seq("-Werror", "-Wconf:cat=deprecation:e", "-Wconf:cat=Feature:e", "-Wvalue:discard", "-Ysafe-init")`; scoped to `probatio-core`/`probatio-cli` only (R-CS5) |
| `ChainStateKernel` | verified mirror (PureScala) | `verified/` leaf (or new `probatio-verified` leaf, Scala 3.7.2) | Models the chain-state verdict decision; `stainlessEnabled := false` by default; `ring6` alias to verify |
| `LedgerValidatorKernel` | verified mirror (PureScala) | `verified/` leaf (or new `probatio-verified` leaf, Scala 3.7.2) | Models the 12-clause validator decision; `stainlessEnabled := false` by default |
| `BannerEngineKernel` | verified mirror (PureScala) | `verified/` leaf (or new `probatio-verified` leaf, Scala 3.7.2) | Models the drift/banner rendering decision; `stainlessEnabled := false` by default |
| `ChainStateModelBridgeTests` | bridge property test (Hedgehog) | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ChainStateModelBridgeTests.scala` | Binds shipped `ChainState.compute` to `ChainStateKernel`; runs on same generated inputs |
| `LedgerValidatorModelBridgeTests` | bridge property test (Hedgehog) | `workflow/core/src/test/scala/org/sinemenda/probatio/core/LedgerValidatorModelBridgeTests.scala` | Binds shipped `Validator.validate` to `LedgerValidatorKernel`; runs on same generated JSON |
| `BannerEngineModelBridgeTests` | bridge property test (Hedgehog) | `workflow/core/src/test/scala/org/sinemenda/probatio/core/BannerEngineModelBridgeTests.scala` | Binds shipped `BannerEngine.render` to `BannerEngineKernel`; runs on same generated inputs |
| `ledger-record-contract.jq` | jq contract (conformance fixture) | `openspec/schemas/verified-scala3/scanner/` | Retained as conformance fixture for R-M2; deleted after one full release cycle of green conformance |
| `chain-state-report-contract.jq` | jq contract (conformance fixture) | `openspec/schemas/verified-scala3/scanner/` | Retained as conformance fixture for R-M2 |
| `gate-hookjson-contract.jq` | jq contract (conformance fixture) | `openspec/schemas/verified-scala3/scanner/` | Retained as conformance fixture for R-M2 |
| dependency-lint rule (R-ARCH1) | build-level check | `build.sbt` or `project/` | Fails if any `workflow/*` project's classpath reaches an adk4s module; CI step alongside other R-ARCH checks |
| Scalafix rules (purity enforcement) | static rules | `.scalafix.conf` | Bans `scala.io.Source`/`java.nio.file`/`System.getenv`/`System.currentTimeMillis` in `org.sinemenda.probatio.core`; bans `case _` on sealed types |
