# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-17
(this session) using the SEMANTIC scanner
(`openspec/schemas/verified-scala3/scanner/scan.sh`, scala-cli + Scalameta,
multi-module, 0 parse failures).
**Consistency check**: CLEAN for spot-verified rows; **the inventory is behind
the scan by a recorded delta** (listed below). Per the instruction's
"fix stale rows, do NOT re-create from scratch" rule, the missing rows are
NOT added here — they belong to changes that landed since the last check
(2026-08-15) and are owned by those changes' apply Step 12. This change
introduces a new `org.sinemenda.probatio` package that reuses zero adk4s
types (R-ARCH1), so the adk4s inventory's staleness is orthogonal to it.

## Scanner run (this session)

```
Found: 6 opaque types, 78 sealed types, 352 case classes, 18 service traits,
62 smithy models, 239 generators
Written to: /tmp/probatio-inventory-scan.md
```

0 parse failures. The scanner discovered every `src/` root across all 13
modules (multi-module discovery, schema v6 fix).

## Delta from last consistency check (2026-08-15)

The inventory's last recorded check (line 488) was 2026-08-15 by
`add-adk4s-record/recorder-sink`, recording: `6 opaque, 76 sealed, 346 case
classes, 18 service traits, 62 smithy, 228 generators`. Today's scan vs that:

| Section | Last check (2026-08-15) | This scan (2026-08-17) | Delta |
|---|---|---|---|
| Opaque types | 6 | 6 | 0 |
| Sealed traits/enums | 76 | 78 | +2 |
| Case classes | 346 | 352 | +6 |
| Service traits | 18 | 18 | 0 |
| Smithy models | 62 | 62 | 0 |
| Generators | 228 | 239 | +11 |

The +2 sealed / +6 case classes / +11 generators are concepts added by
changes that landed between 2026-08-15 and today. They are **missing rows**,
not **stale rows** (a stale row is one whose package/constraint no longer
matches the code). Per the instruction, missing rows are added by the change
that introduced them at its apply Step 12 — not bulk-inserted here, because a
fresh insert loses the `spec:<name>` provenance the inventory promises. This
change (port-scanner-to-probatio) does not introduce adk4s-side types, so it
does not own those missing rows.

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| — | — | — | none — spot check found no stale rows |

**Spot check performed** (per the instruction's "spot-verify with the scanner"
guidance, not a full reconciliation):

- **Opaque types section** (inventory lines 46–55 vs scan lines 8–14): the 6
  plain opaque types match (`RunPath`, `FieldPath`, `ToolSchema`, `Schema`,
  `CallKey`, `ValidatedGraph`). The inventory additionally records 4
  Iron-refined types (`NodeKey`, `ReservedNodeKey`, `Positive`, `NonNegative`)
  that the scanner categorizes separately as Iron `RefinedType` rather than
  plain `opaque type` — this is a categorization difference, not staleness;
  the inventory's rows are accurate (constraints match `project/Versions.scala`
  Iron 3.3.2 and the source expressions). No fix needed.
- **Sealed traits section** (inventory lines 76–137): spot-checked `AdkError`
  (26 variants recorded) and `AgentEvent` (9 variants: `MessageOutput`,
  `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `Interrupted`,
  `ErrorOccurred`, `TokenDelta`, `MemoryRecalled`, `MemoryWritten`) against the
  scan — both match. The +2 sealed delta is new traits not yet recorded, not
  wrong rows.
- **Package paths**: all recorded package paths (`org.adk4s.core.*`,
  `org.adk4s.orchestration.*`, `org.adk4s.memory`, `org.adk4s.memory.testkit`,
  `org.adk4s.structured.*`, `org.adk4s.harness`, `org.adk4s.eval`,
  `org.adk4s.record`, `org.adk4s.record.file`) match real `package` clauses in
  the scanned sources. No stale path found.

A full row-by-row reconciliation of the +19 missing rows is **not performed
here** — it is the owning changes' responsibility, and claiming it was done
when it was not would be the "passes" claim the invariant forbids. The spot
check is honest about its scope.

## Behavioral Concepts (registry pass)

The project has a concept registry at `openspec/concepts/` (36 `.md` files
excluding `README.md`, verified this session via `ls openspec/concepts/*.md |
wc -l` → 36; the inventory's last note said 31 — that count is now stale by
+5, recorded here but not fixed in the inventory prose because the concept
count is a derived fact re-counted by each check, not a provenance row).

**registry-check.sh** (run 2026-08-17, this session):
```
registry-check: OK (803 implementation-map tokens verified, 0 spec concept
references checked, 5 weak binding(s) to tighten)
```

- **Token count**: 803 (was 740 at last check 2026-08-08; +63 tokens from
  changes that landed since).
- **Spec concept references**: 0 (was 15). The drop to 0 is correct: this
  change's `specs` artifact does not yet exist, and no other active change
  has spec concept references at this moment. The 15 at last check were from
  `add-harness-api-phase0`, since archived.
- **Weak bindings**: 5 (was 2). The 5 WEAK rows are pre-existing citations in
  `graph.md` (`GraphCompilationError` cited against `Graph.scala` but exists
  elsewhere) and `tools-node.md` (`executeToolCalls`, `executeFromToolCalls`,
  `toolCalls`, `toolsNode` cited against `ReactAgent.scala` but exist
  elsewhere). **Out of scope for this change** per R-X1's feature freeze —
  this is a port, not a registry-cleanup change. Tightening them is filed for
  a future registry-maintenance change.

**Stale implementation-map rows**: none.
**Unregistered actions / syncs / state components flagged for human review**:
none outstanding.
**New candidate concepts from this change**: none yet — the
`org.sinemenda.probatio` concepts (`Outcome`, `LedgerRecord`, `Ledger`,
`ContractViolation`, `ChainStateReport`, `LintReport`, `GatePayload`,
`BannerEngine`, `DriftScan`, `MetalsClient`, `ProvenanceFields`,
`ValidatedRecord`) are previewed in the proposal's
"New Concepts to Introduce" table and will be registered as behavioral
concepts (if they carry purpose/state/actions/operational-principle) at the
`specs` and apply phases. The porting source-of-truth concepts (the three
`.jq` contracts, the bats oracle, the `*_OVERRIDE` seams) are behavioral
contracts living in `openspec/schemas/verified-scala3/{scanner,tests}/`, not
type-inventory entries — they are referenced in the proposal's "Existing
Concepts to Reuse" table and bound in `specs/migration-protocol/spec.md`.

## Concepts relevant to THIS change

R-ARCH1 isolates probatio from adk4s: the tooling subprojects depend on
NOTHING adk4s-side — no library module, no test util, no shared source. So
this change **reuses zero adk4s type-inventory rows**. The "reuse" column
below is empty for adk4s types; the "introduce" entries are the new
`org.sinemenda.probatio` types from the proposal. These are added to the
project inventory at apply Step 12 with provenance
`spec:port-scanner-to-probatio/<spec>`.

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `Outcome[A]` | sealed enum (`Ran[A]`, `Finding`, `Undetermined`) | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `LedgerRecord` | case class + `Ring` enum (R0–R9) | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `Ledger` | module (`read`/`append`/`validate` only) | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `ContractViolation` | sealed trait (15 clause failures: 12 required-field + 3 provenance) | `org.sinemenda.probatio` | introduce (specs/probatio-core for 12; specs/provenance-validation for +3) |
| `ChainStateReport` | case class | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `SpecLintReport` / `LintReport` | typed AST | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `GatePayload` | case class | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `BannerEngine` | pure function | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `DriftScan` | pure function | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `MetalsClient` | LSP JSON-RPC client | `org.sinemenda.probatio` | introduce (specs/probatio-core) |
| `ProvenanceFields` | immutable case class (sha256, digest, wallTime, source, session — all Option) | `org.sinemenda.probatio` | introduce (specs/provenance-validation) |
| `ValidatedRecord` | immutable case class (LedgerRecord + ProvenanceFields) | `org.sinemenda.probatio` | introduce (specs/provenance-validation) |
| `probatio` multicall binary | native-image launcher | `org.sinemenda.probatio.cli` | introduce (specs/cli-protocol) |
| `sbt-probatio` AutoPlugin | sbt 1.x plugin (Scala 2.12) | `org.sinemenda.probatio.plugin` | introduce (specs/sbt-plugin) |
| `probatioScalacOptions` | build setting (Seq[String]) | (build.sbt) | introduce (specs/probatio-core, R-CS1–R-CS5) |
| dependency-lint rule | build-level check | (build.sbt) | introduce (specs/non-goals-guard, R-ARCH1) |

**Behavioral contracts reused (not type-inventory entries — referenced, not
inventoried):**

| Contract | Location | Reuse / Introduce |
|---|---|---|
| `ledger-record-contract.jq` (15 clauses: 12 required-field + 3 provenance) | `openspec/schemas/verified-scala3/scanner/` | reuse as conformance fixture (R-M2), port to Scala validator (R-C1 for clauses 0–11, R-PV1 for clauses 12–14) |
| `chain-state-report-contract.jq` | `openspec/schemas/verified-scala3/scanner/` | reuse as fixture, port to validator |
| `gate-hookjson-contract.jq` | `openspec/schemas/verified-scala3/scanner/` | reuse as fixture, port to validator |
| bats oracle (17 files) | `openspec/schemas/verified-scala3/tests/*.bats` | reuse as acceptance suite (R-M1), unmodified |
| `*_OVERRIDE` env seams | `gate.sh` / oracle helpers | reuse as strangler swap-points (R-M1) |
| `concept-scanner.scala` | `openspec/schemas/verified-scala3/scanner/` | reuse as porting source (R-N5 spike-gated) |
| `openspec-graph.py` | `openspec/schemas/verified-scala3/scanner/` | reuse as porting source for `graph` subcommand |

## Consequences for downstream artifacts

1. **`specs/*` "Concepts Used" tables**: since R-ARCH1 forbids adk4s-type
   reuse, the specs' "Concepts Used" tables reference only the behavioral
   contracts above (jq/bats/seams), not adk4s type-inventory rows. The
   `concept-inventory` check 6 (reused concepts exist) applies to the
   behavioral contracts, which DO exist (verified this session by `ls`).
2. **`specs/*` "Concepts Introduced" tables**: the 16 introduce entries above
   are the commitments; they are added to `openspec/concept-inventory.md` at
   apply Step 12 with `spec:port-scanner-to-probatio/<spec>` provenance.
3. **The +19 missing adk4s rows are NOT this change's problem**: they are
   owned by the changes that introduced them since 2026-08-15. Recording them
   here as "stale" would misattribute provenance. The spot check is honest
   about its scope (opaque + 2 sealed traits verified; full reconciliation
   not performed).
4. **The 5 WEAK registry bindings are NOT this change's problem** (R-X1
   feature freeze). Filed for a future registry-maintenance change.
5. **The concept-count prose (31) is stale (now 36)** but is a derived count,
   not a provenance row — re-counted by each check. Not fixed in the inventory
   prose here because it is self-correcting on the next check that updates it.
