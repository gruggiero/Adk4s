# Spec: CLI Wiring

<!-- Delta spec for the complete-probatio-porting change. Defines the
     wiring of the 16 stub subcommand entrypoints in
     SubcommandEntrypoints.scala to read inputs, delegate to probatio-core
     decision logic, and emit the exact stdout/exit-code contract the bash
     originals produce. Each subcommand group is verified against the
     unchanged bats oracle at its *_OVERRIDE seam. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol (NEW — created by this spec) | The oracle-driven incremental porting protocol this spec operates under: a wiring step is complete when the acceptance oracle is green with the ported subcommand substituted at its seam | `openspec/concepts/strangler-migration-protocol.md` |
| Conformance property-test contract (NEW — created by this spec) | The bidirectional equivalence between a ported validator and an executable contract over a generated corpus — the ledger and chain-state wirings are verified this way | `openspec/concepts/conformance-property-test-contract.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required. The strangler migration protocol and
conformance property-test contract are behavioral contracts reused as-is.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `Subcommand` | enum (16 cases) | `org.sinemenda.probatio.cli` |
| `ProbatioMain` | object (dispatch) | `org.sinemenda.probatio.cli` |
| `MulticallDispatch` | object (resolve) | `org.sinemenda.probatio.cli` |
| `ExitCode` | enum (Clean, Finding, Undetermined) | `org.sinemenda.probatio.cli` |
| `HelpRegistry` / `HelpOutput` | object / case class | `org.sinemenda.probatio.cli` |
| `Validator` | object (validateFull — 15 clauses) | `org.sinemenda.probatio.core` |
| `Ledger` | object (read/append/validate) | `org.sinemenda.probatio.core` |
| `ContractViolation` | sealed trait (15 variants) | `org.sinemenda.probatio.core` |
| `LedgerRecord` / `LedgerRecordOptional` | case classes | `org.sinemenda.probatio.core` |
| `ChainState` | object (compute) | `org.sinemenda.probatio.core` |
| `ChainStateReport` | case class | `org.sinemenda.probatio.core` |
| `LintReport` / `CheckId` | case class / enum (F1–F10) | `org.sinemenda.probatio.core` |
| `BannerEngine` / `BannerInputs` / `BannerOutput` | object / case classes | `org.sinemenda.probatio.core` |
| `DriftScan` / `DriftScanResult` | object / case class | `org.sinemenda.probatio.core` |
| `GatePayload` / `HookSpecificOutput` | case classes | `org.sinemenda.probatio.core` |
| `PredecessorCheck` / `GrantWaiver` | objects (pure functions) | `org.sinemenda.probatio.core` |
| `GateDecision` / `BlockReason` / `SpecPhase` / `GateEvent` / `PresentationMarker` | sealed traits / enums | `org.sinemenda.probatio.core` |
| `SchemaPolicy` / `ResolvedValue` / `EnvResolution` / `StampClassification` / `DriftLine` | object / enums / case classes | `org.sinemenda.probatio.core` |
| `MetalsClient` / `LspMessage` / `MetalsError` / `MetalsSession` | object / case classes / sealed trait | `org.sinemenda.probatio.core` |
| `ConformanceModel` / `RecordModel` | object / case class | `org.sinemenda.probatio.verified` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `CliContext` | final case class | Carries resolved paths (repo root, change dir, ledger file, git-dir) and env-var overrides read once at entrypoint start; passed to core calls. Avoids re-reading env per subcommand. |
| `StdoutRenderer[A]` | typeclass (given instances per subcommand) | Renders a core result type (`ChainStateReport`, `LintReport`, `GatePayload`, `BannerOutput`) to the exact stdout string the bash original emits. One instance per subcommand output format. |
| `SubcommandWiring` | object | The I/O adapter layer: reads files via os-lib, parses args via mainargs, calls core, renders via `StdoutRenderer`, maps to `Outcome[Int]`. Pure where possible (arg parse + render); side-effecting only at the os-lib boundary. |

## ADDED Requirements

### Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout

The `ledger` subcommand entrypoint SHALL read its action (`append`, `read`, `validate`) and flags from argv, delegate record validation to `Validator.validateFull` (all 15 clauses), append accepted records to the JSONL file via os-lib, read records via `Ledger.read`, and emit stdout byte-compatible with the predecessor `ledger.sh`. A record rejected by the validator SHALL produce a `Finding` outcome naming the violated clause index and description, and the record SHALL NOT be written. The `run` action SHALL execute the command after `--`, observe its exit code, compute the artifact SHA-256 and stdout/stderr digest, and append a self-observed record. The three-way exit protocol (0 clean, 1 finding, 2 undetermined) SHALL match the predecessor exactly.

**Given** a ledger JSONL file and a `ledger append` invocation with all required flags (`--file`, `--change`, `--spec`, `--ring`, `--obligation`, `--artifact`, `--command`, `--exit`, `--baseline`) and all fields non-empty
**When** the entrypoint validates the record via `Validator.validateFull` and the record satisfies all 15 clauses
**Then** the record is appended to the file with a trailing newline, `v` and `ts` stamped by the entrypoint (not accepted from the caller), and the exit code is 0

**Rationale**: The ledger is the evidence record — a wiring bug that silently accepts an invalid record or silently rejects a conformant one corrupts the correctness chain. Byte-compatibility with the predecessor is verified by the `evidence-ledger.bats` oracle at the `LEDGER_OVERRIDE` seam and by the `ledger-record-contract.jq` conformance property test.

#### Scenario: A conformant record is appended successfully

**Given** an empty ledger file and a `ledger append` invocation with all required fields supplied
**When** the entrypoint runs
**Then** the file contains one line satisfying `ledger-record-contract.jq`, the exit code is 0, and stdout is empty (matching the predecessor)

#### Scenario: A record missing a required field is rejected before writing

**Given** an empty ledger file and a `ledger append` invocation with `--change` omitted
**When** the entrypoint runs
**Then** the file is unchanged (no partial write), the exit code is 1, and stderr names the missing field

#### Scenario: An adversarial-review-ring record without a session is rejected

**Given** a `ledger append` invocation with `--ring` set to the adversarial-review ring and no `--session`
**When** the entrypoint runs
**Then** the exit code is 1 and stderr names the missing session field (clause 14)

#### Scenario: An unreadable ledger file produces undetermined, not clean

**Given** a `ledger read` invocation where the file path does not exist
**When** the entrypoint runs
**Then** the exit code is 2 and stderr contains "UNDETERMINED" (the corrupt-ledger-reads-as-clean defect class is averted)

#### Scenario: The run action observes the command exit code

**Given** a `ledger run` invocation with `-- sbt test` as the command
**When** the entrypoint executes the command and it exits 1
**Then** the appended record has `exit: 1`, `sha256` of the artifact, and `digest` of the command output, and the entrypoint exits 0 (the run itself succeeded; the observed exit is recorded, not reflected)

### Requirement: The chain-state subcommand wires to ChainState.compute and emits the contract-conformant JSON report

The `chain-state` subcommand entrypoint SHALL read the ledger file and spec files, delegate to `ChainState.compute` to produce the bound/resolved/discharged verdict, and emit a JSON report on stdout that satisfies `chain-state-report-contract.jq`. The report SHALL include the `total`, `bound`, `resolved`, `discharged` counts and the `unresolved` list with per-entry reasons. The three-way exit protocol SHALL match the predecessor: exit 0 when the report is emitted successfully, exit 2 when the ledger or spec files cannot be read.

**Given** a change directory with specs and an evidence ledger at a known baseline
**When** the `chain-state` entrypoint runs with `--change-dir`, `--change`, `--baseline`, and `--ledger-file`
**Then** the stdout JSON satisfies `chain-state-report-contract.jq` and the exit code is 0

**Rationale**: Chain-state computes the correctness verdict — a wiring bug that emits a malformed report or miscounts the discharged obligations is the exact defect the schema exists to avert. The `chain-state.bats` oracle at the `CHAIN_STATE_OVERRIDE` seam verifies byte-compatibility.

#### Scenario: A fully discharged change reports zero unresolved

**Given** a change where every requirement has a matching ledger row at the baseline
**When** the `chain-state` entrypoint runs
**Then** the report has `discharged == total` and the `unresolved` list is empty

#### Scenario: An undischarged obligation appears in the unresolved list

**Given** a change where one requirement has no matching ledger row
**When** the `chain-state` entrypoint runs
**Then** the report has `discharged < total` and the `unresolved` list contains one entry with `reason: "undischarged"` naming the spec and requirement

#### Scenario: An unreadable ledger produces undetermined

**Given** a `chain-state` invocation where the ledger file is corrupt (not parseable as JSON Lines)
**When** the entrypoint runs
**Then** the exit code is 2 and stderr contains "UNDETERMINED" (the report is not emitted)

### Requirement: The checkpoint subcommand writes the presentation marker and emits the report

The `checkpoint` subcommand entrypoint SHALL read the ledger, compute the chain-state, write a presentation marker file under the repo's git-dir (`.git/verified-scala3-gate/presentation-<change>-<spec>-<session>`), and emit the checkpoint report on stdout. The marker SHALL be evidence that the chain-state discharge check ran — not just that tests ran. The entrypoint SHALL exit 0 on success, 1 on a finding (e.g. undischarged obligations), and 2 on undetermined (e.g. unreadable ledger).

**Given** a change with a discharged spec and a non-empty session ID
**When** the `checkpoint` entrypoint runs with `--ledger`, `--change`, `--spec`, `--baseline`, `--rings`, `--chain-state-json`, `--format`, `--change-dir`, and `--session`
**Then** a presentation marker file exists under `.git/verified-scala3-gate/` and the exit code is 0

**Rationale**: The gate-checkpoint-lock spec (R-M3) requires a presentation marker before the next spec may proceed. The `checkpoint-from-ledger.bats` oracle verifies the marker is written and the report is emitted.

#### Scenario: A checkpoint for a discharged spec writes the marker

**Given** a spec with all obligations discharged at the baseline
**When** the `checkpoint` entrypoint runs
**Then** the marker file `presentation-<change>-<spec>-<session>` exists and the report on stdout lists the discharged rings

#### Scenario: A checkpoint for an undischarged spec reports the finding

**Given** a spec with unresolved obligations
**When** the `checkpoint` entrypoint runs
**Then** the exit code is 1 and the report names the unresolved obligations

### Requirement: The spec-lint subcommand wires to the F1–F10 checks and emits the CONTEXT block

The `spec-lint` subcommand entrypoint SHALL read the change's spec files, run the F1–F10 mechanical checks via `LintReport` core logic, emit the CONTEXT block (applicability facts read from the repository) via `BannerEngine`, and emit the lint report on stdout. The report SHALL include per-check verdicts (PASS/FAIL/WARN), the CONTEXT block, and the W1–W7 warnings. The three-way exit protocol SHALL match the predecessor: exit 0 when no FAIL, exit 1 when one or more FAIL, exit 2 on undetermined.

**Given** a change directory with spec files
**When** the `spec-lint` entrypoint runs with `--change` and `--spec`
**Then** the stdout contains the F1–F10 verdicts and the CONTEXT block, matching the predecessor's output format

**Rationale**: Spec-lint decides whether a spec may proceed — a wiring bug that silently passes a failing spec or silently fails a passing one is a gate bypass. The `correctness-invariant.bats` and `workflow-hygiene.bats` oracles at the `SPEC_LINT_OVERRIDE` seam verify byte-compatibility.

#### Scenario: A clean spec passes all F1–F10 checks

**Given** a well-formed spec with requirements, scenarios, properties, and proof obligations
**When** the `spec-lint` entrypoint runs
**Then** all F1–F10 verdicts are PASS and the exit code is 0

#### Scenario: A spec with a missing requirements section fails F2

**Given** a spec file with no `## ADDED Requirements` section
**When** the `spec-lint` entrypoint runs
**Then** F2 is FAIL, the exit code is 1, and the report names the missing section

#### Scenario: The CONTEXT block reports applicability facts

**Given** a repository with a behavioural registry and type inventory
**When** the `spec-lint` entrypoint runs with `--context-only`
**Then** the stdout contains the CONTEXT block with schema version, registry presence, inventory presence, and applicability rows for checks 3, 6, 17, 18

### Requirement: The danger-scan subcommand wires to the pattern scanner and emits findings

The `danger-scan` subcommand entrypoint SHALL compute the git diff against the baseline, scan the changed production `.scala` files for dangerous correctness patterns (`case _` catch-alls on sealed types, `asInstanceOf`, silent fallback mappings, partial functions, file I/O in pure functions, `System.getenv` in pure functions), and emit findings on stdout. The three-way exit protocol SHALL match the predecessor: exit 0 when no unjustified hits, exit 1 when hits are reported.

**Given** a git diff with one changed production source file containing an `asInstanceOf` call
**When** the `danger-scan` entrypoint runs with `--baseline`
**Then** the stdout reports the `asInstanceOf` hit with file path and line number, and the exit code is 1

**Rationale**: Danger-scan catches silent defects that pass tests but violate the spec. The `correctness-invariant.bats` oracle at the `DANGER_SCAN_OVERRIDE` seam verifies the pattern set and exit codes.

#### Scenario: A clean diff reports no hits

**Given** a git diff with no dangerous patterns
**When** the `danger-scan` entrypoint runs
**Then** stdout is empty and the exit code is 0

#### Scenario: A case-catch-all on a sealed type is reported

**Given** a changed file with `case _ =>` in a pattern match on a sealed trait
**When** the `danger-scan` entrypoint runs
**Then** the hit is reported with the file path, line number, and pattern, and the exit code is 1

### Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner

The `gate` subcommand entrypoint SHALL dispatch on the `--event` flag to one of 5 events (`session-start`, `prompt-submit`, `post-edit`, `tool-call`, `completion`), assemble the `GatePayload` via `BannerEngine` (invariant block + session context), and implement the blocking logic per event tier. The `session-start` and `prompt-submit` events SHALL always exit 0 (Tier B — informational). The `post-edit` event SHALL run spec-lint or danger-scan on the edited file and return findings (Tier A — informational). The `tool-call` event SHALL block (exit 1) if predecessor specs are not verified+checkpointed or if a grant is required (Tier A — blocking), delegating to `PredecessorCheck` and `GrantWaiver`. The `completion` event SHALL block (exit 1) if a completion claim is made while chain-state is unresolved. The hook banner SHALL be byte-compatible with the predecessor `gate.sh`.

**Given** a `gate` invocation with `--event session-start --format text`
**When** the entrypoint runs
**Then** the stdout contains the invariant block and the session context block (schema version, drift warnings, applicability rows, active change, chain-state), and the exit code is 0

**Rationale**: The gate runs on every turn and its blocking logic controls agent progression. A wiring bug that silently allows blocked edits or blocks allowed edits is the "corrupt ledger reads as clean" defect class. The `hook-tiers.bats`, `human-grant-lock.bats`, `oracle-ordering-lock.bats`, and `gate-payload.bats` oracles at the `GATE_command` seam verify the blocking logic and banner format. The gate is wired LAST per the swap order (R-M3).

#### Scenario: session-start emits the banner and exits 0

**Given** a repository with an active change
**When** the `gate` entrypoint runs with `--event session-start`
**Then** the banner is emitted on stdout and the exit code is 0

#### Scenario: tool-call blocks when a predecessor spec is not checkpointed

**Given** a change where spec-1 is Verified but has no presentation marker, and a `tool-call` event targeting spec-2's production files
**When** the `gate` entrypoint runs
**Then** the exit code is 1 and the block reason is `PredecessorNotCheckpointed` naming spec-1, and the payload includes the escape hatch variable name

#### Scenario: tool-call allows when all predecessors are verified and checkpointed

**Given** a change where spec-1 is Verified and has a presentation marker, and a `tool-call` event targeting spec-2's production files
**When** the `gate` entrypoint runs
**Then** the exit code is 0

#### Scenario: completion blocks when chain-state is unresolved

**Given** a change with unresolved obligations and a `completion` event
**When** the `gate` entrypoint runs
**Then** the exit code is 1 and the block reason names the unresolved obligations

#### Scenario: The escape hatch bypasses the tool-call lock

**Given** `PROBATIO_HOOKS=1` in the environment and a `tool-call` event that would otherwise block
**When** the `gate` entrypoint runs
**Then** the exit code is 0 (the escape hatch bypasses both predecessor check and grant waiver)

### Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout

The `registry-check`, `reconcile`, `scan`, `removal-audit`, `impact-scan`, `metals`, `concept-scanner`, `graph`, `install-skills`, and `install-hooks` subcommand entrypoints SHALL each wire to their respective core logic (or delegate to external tools where the core provides a client), parse their flags from argv, and emit stdout byte-compatible with their predecessor scripts. Each SHALL implement the three-way exit protocol (0 clean, 1 finding, 2 undetermined). The `metals` subcommand SHALL delegate LSP framing to `MetalsClient` (start, stop, call sub-actions). The `install-skills` and `install-hooks` subcommands SHALL perform filesystem installation via os-lib.

**Given** a `registry-check` invocation with `--change`
**When** the entrypoint runs
**Then** the stdout matches the predecessor `registry-check.sh` output format and the exit code follows the three-way protocol

**Rationale**: Each of these subcommands has a predecessor bash script with an established stdout contract. The bats oracle exercises the ones with test suites; the remainder are verified by R8 adversarial review against the predecessor's output format. All are wired in dependency order per R-M3.

#### Scenario: registry-check emits the concept registry edge check report

**Given** a change with specs citing concepts from the registry
**When** the `registry-check` entrypoint runs
**Then** the report lists each concept's implementation-map verification result and the exit code is 0 when all tokens resolve

#### Scenario: metals start launches the LSP server

**Given** a `metals start` invocation
**When** the entrypoint runs
**Then** the Metals LSP server is started via `MetalsClient`, the endpoint URL is emitted on stdout, and the exit code is 0

#### Scenario: install-skills copies skills to agent directories

**Given** an `install-skills` invocation with `--dir`
**When** the entrypoint runs
**Then** the skill files are copied to `.claude/skills/`, `.pi/skills/`, `.devin/skills/` and the exit code is 0

## Properties (Ring 3)

### Property: ledger-append-contract-conformance

**Invariant**: For every record the wired `ledger append` entrypoint accepts and writes, the `ledger-record-contract.jq` executable contract also accepts it; for every record the entrypoint rejects, the contract also rejects it. Equivalence is bidirectional.

**Generator strategy**: `genLedgerRecord` — constructive over records satisfying and violating each of the 15 clauses. For each clause, generates a satisfying record and a violating record. Edge cases: empty record, all-clauses-satisfied, single-clause-violated for every clause, R8-record-without-session, record-with-optional-fields-present, record-with-optional-fields-absent. Corpus augmented with `tests/fixtures/evidence-ledger-v1.jsonl`.

```
property("ledger append contract conformance") {
  for {
    record <- genLedgerRecord.forAll
    contractResult = runJqContract("ledger-record-contract.jq", record)
    entrypointResult = runLedgerAppend(record)
  } yield {
    val contractAccepts = contractResult == 0
    val entrypointAccepts = entrypointResult.isRan
    Result.diff(contractAccepts, entrypointAccepts)(_ == _)
  }
}
```

### Property: chain-state-report-contract-conformance

**Invariant**: For every chain-state report the wired `chain-state` entrypoint emits, the `chain-state-report-contract.jq` executable contract accepts it. The report's `total`, `bound`, `resolved`, `discharged` counts are internally consistent: `bound <= total`, `resolved <= bound`, `discharged <= resolved`.

**Generator strategy**: `genChangeState` — constructive over change states with varying numbers of specs, requirements, and ledger rows. For each spec, generates 0..N discharged obligations. Edge cases: empty change, fully discharged, partially discharged, no ledger rows, corrupt ledger.

```
property("chain-state report contract conformance") {
  for {
    state <- genChangeState.forAll
    report = runChainState(state)
    contractResult = runJqContract("chain-state-report-contract.jq", report)
  } yield {
    Result.assert(contractResult == 0) and
    Result.assert(report.bound <= report.total) and
    Result.assert(report.resolved <= report.bound) and
    Result.assert(report.discharged <= report.resolved)
  }
}
```

### Property: gate-banner-byte-compatibility

**Invariant**: For every `gate --event session-start` invocation, the stdout emitted by the wired entrypoint is byte-identical to the stdout emitted by the predecessor `gate.sh` for the same repository state (same schema version, same active change, same chain-state).

**Generator strategy**: `genRepoState` — constructive over repository states with varying active changes, chain-state results, and drift warnings. Edge cases: no active change, one active change with clean chain-state, one with unresolved obligations, instruction drift present, behavioural registry present/absent.

```
property("gate banner byte compatibility") {
  for {
    state <- genRepoState.forAll
    predecessorOutput = runPredecessorGate(state)
    portedOutput = runPortedGate(state)
  } yield {
    Result.diff(predecessorOutput, portedOutput)(_ == _)
  }
}
```

### Property: three-way-exit-protocol-faithfulness

**Invariant**: For every subcommand and every input, the wired entrypoint's exit code matches the predecessor's exit code. Exit 0 (clean) maps to `Outcome.Ran(0)`; exit 1 (finding) maps to `Outcome.Finding`; exit 2 (undetermined) maps to `Outcome.Undetermined`. No subcommand collapses undetermined into clean.

**Generator strategy**: `genSubcommandInvocation` — constructive over (subcommand, args) pairs covering each of the 16 subcommands with valid, invalid, and undetermined inputs. Edge cases: missing required flags, non-existent file paths, corrupt input files, `--help` flag.

```
property("three-way exit protocol faithfulness") {
  for {
    invocation <- genSubcommandInvocation.forAll
    predecessorExit = runPredecessor(invocation)
    portedExit = runPorted(invocation)
  } yield {
    Result.diff(predecessorExit, portedExit)(_ == _)
  }
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A `--force` or `--skip-validation` flag on `ledger append` | Validation is the gate; a bypass flag is a defect that makes the 15-clause validator advisory | `assertDoesNotCompile("LedgerCmd.append(..., force = true)")` — the parameter does not exist on the append method |
| A `update` or `delete` action on the `ledger` subcommand | The ledger is append-only; a mutation action is unparseable, not denylisted | `assertDoesNotCompile("LedgerCmd.Action.Update")` — the enum has no `Update` case |
| A `case _` catch-all in the gate's event dispatch | The 5 events are a sealed enum; a catch-all would silently swallow a future event | `assertDoesNotCompile("GateEvent(_) => ...")` — exhaustiveness escalation fails Ring 0 |

## Formal Contracts (Ring 6)

No new formal contracts are introduced by this spec. The decision logic (`Validator.validateFull`, `ChainState.compute`, `PredecessorCheck.apply`, `GrantWaiver.apply`) is already Ring-6-verified in `probatio-core` and `probatio-verified`. The wiring layer is I/O adaptation — not a pure kernel. The bridge property test is the oracle-green check at the `*_OVERRIDE` seam (the bats suite proves the wired subcommand produces the same outcomes as the bash original).

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A conformant ledger record is appended successfully | Requirement: The ledger subcommand wires to the 15-clause validator + Scenario: A conformant record is appended successfully | bats oracle at LEDGER_OVERRIDE seam + Hedgehog conformance property test (LedgerCmdConformanceSpec, to be written in apply phase) | `evidence-ledger.bats` |
| A record missing a required field is rejected before writing | Requirement: The ledger subcommand wires to the 15-clause validator + Scenario: A record missing a required field is rejected before writing | bats oracle + Hedgehog property test (genLedgerRecord violating clause 1) | `evidence-ledger.bats` |
| An adversarial-review-ring record without a session is rejected | Requirement: The ledger subcommand wires to the 15-clause validator + Scenario: An adversarial-review-ring record without a session is rejected | bats oracle + Hedgehog property test (genLedgerRecord violating clause 14) | `evidence-ledger.bats` |
| An unreadable ledger produces undetermined, not clean | Requirement: The ledger subcommand wires to the 15-clause validator + Scenario: An unreadable ledger file produces undetermined | bats oracle + Hedgehog three-way exit protocol property (ExitProtocolSpec, to be written in apply phase) | `evidence-ledger.bats` |
| The run action observes the command exit code | Requirement: The ledger subcommand wires to the 15-clause validator + Scenario: The run action observes the command exit code | bats oracle | `evidence-ledger.bats` |
| Chain-state report satisfies the contract | Requirement: The chain-state subcommand wires to ChainState.compute + Scenario: A fully discharged change reports zero unresolved | bats oracle at CHAIN_STATE_OVERRIDE + Hedgehog conformance property test (ChainStateCmdConformanceSpec, to be written in apply phase) | `chain-state.bats` |
| An undischarged obligation appears in the unresolved list | Requirement: The chain-state subcommand wires to ChainState.compute + Scenario: An undischarged obligation appears in the unresolved list | bats oracle | `chain-state.bats` |
| An unreadable ledger produces undetermined for chain-state | Requirement: The chain-state subcommand wires to ChainState.compute + Scenario: An unreadable ledger produces undetermined | bats oracle + Hedgehog three-way exit protocol property (ExitProtocolSpec, to be written in apply phase) | `chain-state.bats` |
| Checkpoint writes the presentation marker | Requirement: The checkpoint subcommand writes the presentation marker + Scenario: A checkpoint for a discharged spec writes the marker | bats oracle | `checkpoint-from-ledger.bats` |
| Checkpoint reports undischarged obligations | Requirement: The checkpoint subcommand writes the presentation marker + Scenario: A checkpoint for an undischarged spec reports the finding | bats oracle | `checkpoint-from-ledger.bats` |
| Spec-lint emits F1–F10 verdicts and CONTEXT block | Requirement: The spec-lint subcommand wires to the F1–F10 checks + Scenario: A clean spec passes all F1–F10 checks | bats oracle at SPEC_LINT_OVERRIDE seam | `correctness-invariant.bats`, `workflow-hygiene.bats` |
| Spec-lint fails on missing requirements section | Requirement: The spec-lint subcommand wires to the F1–F10 checks + Scenario: A spec with a missing requirements section fails F2 | bats oracle | `correctness-invariant.bats` |
| Spec-lint CONTEXT block reports applicability facts | Requirement: The spec-lint subcommand wires to the F1–F10 checks + Scenario: The CONTEXT block reports applicability facts | bats oracle | `correctness-invariant.bats` |
| Danger-scan reports case-catch-all on sealed type | Requirement: The danger-scan subcommand wires to the pattern scanner + Scenario: A case-catch-all on a sealed type is reported | bats oracle at DANGER_SCAN_OVERRIDE seam | `correctness-invariant.bats` |
| Danger-scan clean diff reports no hits | Requirement: The danger-scan subcommand wires to the pattern scanner + Scenario: A clean diff reports no hits | bats oracle | `correctness-invariant.bats` |
| Gate session-start emits banner and exits 0 | Requirement: The gate subcommand wires to the 5-event tier logic + Scenario: session-start emits the banner and exits 0 | bats oracle at GATE_command seam + Hedgehog gate-banner-byte-compatibility property (GateBannerCompatSpec, to be written in apply phase) | `hook-tiers.bats`, `gate-payload.bats` |
| Gate tool-call blocks when predecessor not checkpointed | Requirement: The gate subcommand wires to the 5-event tier logic + Scenario: tool-call blocks when a predecessor spec is not checkpointed | bats oracle | `hook-tiers.bats`, `oracle-ordering-lock.bats` |
| Gate tool-call allows when all predecessors verified+checkpointed | Requirement: The gate subcommand wires to the 5-event tier logic + Scenario: tool-call allows when all predecessors are verified and checkpointed | bats oracle | `hook-tiers.bats` |
| Gate completion blocks when chain-state unresolved | Requirement: The gate subcommand wires to the 5-event tier logic + Scenario: completion blocks when chain-state is unresolved | bats oracle | `hook-tiers.bats` |
| Escape hatch bypasses the tool-call lock | Requirement: The gate subcommand wires to the 5-event tier logic + Scenario: The escape hatch bypasses the tool-call lock | bats oracle | `hook-tiers.bats` |
| registry-check emits edge check report | Requirement: The remaining subcommands wire to their core logic + Scenario: registry-check emits the concept registry edge check report | bats oracle + R8 adversarial review | `judgment-ring-integrity.bats` |
| metals start launches LSP server | Requirement: The remaining subcommands wire to their core logic + Scenario: metals start launches the LSP server | R8 adversarial review (no bats suite for metals; MetalsCmdSpec Hedgehog test to be written in apply phase) | adversarial review |
| install-skills copies skills to agent directories | Requirement: The remaining subcommands wire to their core logic + Scenario: install-skills copies skills to agent directories | bats oracle + R8 adversarial review | `harness-install-verification.bats` |
| ledger-append-contract-conformance | Property: ledger-append-contract-conformance | Hedgehog property test (LedgerCmdConformanceSpec, to be written in apply phase) | `evidence-ledger.bats` |
| chain-state-report-contract-conformance | Property: chain-state-report-contract-conformance | Hedgehog property test (ChainStateCmdConformanceSpec, to be written in apply phase) | `chain-state.bats` |
| gate-banner-byte-compatibility | Property: gate-banner-byte-compatibility | Hedgehog property test (GateBannerCompatSpec, to be written in apply phase) | `hook-tiers.bats` |
| three-way-exit-protocol-faithfulness | Property: three-way-exit-protocol-faithfulness | Hedgehog property test (ExitProtocolSpec, to be written in apply phase) | `evidence-ledger.bats` |
| No --force flag on ledger append | Compile-Negative: --force / --skip-validation flag on ledger append | compile-negative test (assertDoesNotCompile, LedgerCmdCompileNegativeSpec to be written in apply phase) | `evidence-ledger.bats` |
| No update/delete action on ledger | Compile-Negative: update or delete action on the ledger subcommand | compile-negative test (assertDoesNotCompile, LedgerCmdCompileNegativeSpec to be written in apply phase) | `evidence-ledger.bats` |
| No case-catch-all in gate event dispatch | Compile-Negative: case _ catch-all in the gate's event dispatch | compile-negative test (assertDoesNotCompile, GateCmdCompileNegativeSpec to be written in apply phase) + Ring 0 exhaustiveness escalation | `hook-tiers.bats` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `SubcommandEntrypoints.scala` | object (16 entrypoints) | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/` | All 16 `*Cmd.run` methods populated from stubs |
| `CliContext` | case class | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/` | New file — resolved paths + env vars |
| `StdoutRenderer[A]` | typeclass | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/` | New file — given instances per subcommand |
| `SubcommandWiring` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/` | New file — I/O adapter layer |
| `ProbatioMain.scala` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/` | Unchanged — dispatch already wired |
| `sbt probatio-cli/nativeImage` | build step | `workflow/cli/` | Build the native binary after wiring; re-measure gate latency |
