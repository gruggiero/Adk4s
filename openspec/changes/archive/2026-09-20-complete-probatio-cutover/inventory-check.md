# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-29
**Consistency check**: **2 stale rows fixed** (listed below)

Verification was performed by reading the probatio source directly
(`workflow/core`, `workflow/cli`, `workflow/plugin`) and comparing every
`org.sinemenda.probatio.*` row against it — 142 inventory rows carry that
package prefix. The semantic scanner (`scanner/scan.sh` → `concept-scanner.scala`)
was **not** used for a full regeneration, per rule B (a fresh scan destroys the
provenance column); it remains available and unmodified (scala-cli, not ported).

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| `BannerEngine` | `object (assembleBanner — pure function over BannerInputs → BannerOutput)` | `object (render: BannerInputs → BannerOutput)` — the method is `render`, not `assembleBanner` | yes — Status `shipped` retained, correction attributed to `spec:complete-probatio-cutover/inventory-check` |
| `ProbatioMain` | `object (dispatch: Array[String] → Int — multicall entry point)` | adds `main: Array[String] → Unit` — the JVM/native entry point introduced by `spec:complete-probatio-porting/cli-wiring` and never recorded | yes — Status `shipped` retained, introduction attributed to `cli-wiring` |

The `ProbatioMain` omission is not incidental. `main` is the surface at which
the argv defect lives (proposal §Why, defect 1); an entry point that was never
inventoried was also never reviewed as a concept.

### Rows checked and found CORRECT (spot-verification evidence)

| Concept | Verified against |
|---------|------------------|
| `UnresolvedReason` — enum (Unbound, Unresolved, Undischarged, Unattributable, Failed) | `ChainStateReport.scala:11-12` — **all five variants exist**. The inventory is right and the proposal's first draft was wrong: this change does not *add* `Unattributable`, it makes an existing-but-unreachable variant reachable (`ChainState.compute` never emits it). Proposal corrected accordingly. |
| `Subcommand` — enum, 16 cases | `Subcommand.scala:23-25` — matches exactly. This change **removes seven** of them (see below). |
| `ContractViolation` — 15 clause variants | `ContractViolation.scala` — matches; provenance-validation's lift from 12 to 15 is recorded correctly. |
| `LedgerRecordOptional` — final case class | `LedgerRecord.scala:36-42` — exists as recorded. **Not a defect in the inventory, but a defect in the code**: it is declared and never referenced by `LedgerRecord`'s `ReadWriter`, so run-mode fields are silently dropped on write. Flagged below. |
| `CliContext`, `StdoutRenderer[A]`, `SubcommandWiring` | `CliContext.scala`, `StdoutRenderer.scala`, `SubcommandWiring.scala` — signatures match. |
| `OracleGreenGate`, `ShimSwap`, `SwapOrder` (test-only) | `workflow/core/src/test/.../migration/` — match. |
| `InstallRootScan`, `DriftWarning`, `DriftScanResult` | `DriftScan.scala` — types match. **Not an inventory defect**: `DriftScan.scala:18` declares `val installRoots` with a scaladoc reading "The six install roots scanned for drift" while listing **three**. The inventory records the type, not the value, so it is not stale — but the value is wrong against the predecessor, which scans six. Flagged below. |

## Behavioral Concepts (registry pass)

**registry-check.sh**: `registry-check: OK (803 implementation-map tokens verified, 0 spec concept references checked, 5 weak binding(s) to tighten)` — exit 0, run 2026-08-29 against the repository root.

**Stale implementation-map rows**: none.

**Weak bindings (warnings, not failures — pre-existing, unrelated to this change)**:
- `graph.md`: `GraphCompilationError` cited in `Graph.scala` but defined elsewhere
- `tools-node.md`: `executeToolCalls`, `executeFromToolCalls`, `toolCalls`, `toolsNode` cited against `ReactAgent.scala` but resolved tree-wide

All five are in `adk4s-*` concepts, untouched by this change. They are recorded
here so the "5 weak bindings" line is not read as this change's residue.

**Unregistered actions / syncs / state components**: the `0 spec concept
references checked` figure means no *active* change spec cited a
`Concept/action` at the time of the run — this change's specs do not exist yet
when this artifact is written. The registry pass will be re-run at the Step 12
removal audit, when the specs are present.

## Concepts relevant to THIS change

### Reuse

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `Outcome[+A]` | enum | `org.sinemenda.probatio.core` | reuse |
| `Ring`, `SpecPhase`, `GateEvent`, `GateDecision`, `BlockReason` | enums | `org.sinemenda.probatio.core` | reuse |
| `LedgerRecord`, `LedgerRecordOptional`, `Ledger.LedgerData` | case classes | `org.sinemenda.probatio.core` | reuse — join `LedgerRecordOptional` into the record codec |
| `Validator`, `ContractViolation` | object / sealed trait | `org.sinemenda.probatio.core` | reuse unchanged |
| `ChainState`, `ChainStateReport`, `ChainStateUndetermined`, `ChainState.Requirement`, `UnresolvedEntry`, `UnmappedObligation`, `UnresolvedReason` | object / case classes / enum | `org.sinemenda.probatio.core` | reuse — extend `compute`, make `Unattributable` reachable |
| `LintReport`, `RequirementVerdict`, `Verdict`, `CheckId`, `LintWarning` | case classes / enums | `org.sinemenda.probatio.core` | reuse as the engine's output type |
| `BannerEngine`, `BannerInputs`, `BannerOutput`, `ActiveChangeWithChainState` | object / case classes | `org.sinemenda.probatio.core` | reuse the renderer; supply real inputs |
| `DriftScan`, `InstallRootScan`, `DriftWarning`, `DriftScanResult` | object / types | `org.sinemenda.probatio.core` | reuse — correct `installRoots` to six |
| `GatePayload`, `HookSpecificOutput` | case classes | `org.sinemenda.probatio.core` | reuse — fix `hookEventName` to emit harness names |
| `PredecessorCheck`, `GrantWaiver`, `PresentationMarker` | objects | `org.sinemenda.probatio.core` | reuse unchanged; wire to a real state reader |
| `SchemaPolicy` | object | `org.sinemenda.probatio.core` | reuse for v14 rename + env-var alias |
| `MetalsClient.*` | object / types | `org.sinemenda.probatio.core` | reuse for `metals start` only |
| `Subcommand`, `MulticallDispatch`, `ExitCode`, `CliError`, `HelpRegistry`, `HelpOutput`, `FlagHelp`, `ExitCodeDoc` | enum / objects / case classes | `org.sinemenda.probatio.cli` | reuse — `Subcommand` shrinks (below) |
| `ProbatioMain` | object | `org.sinemenda.probatio.cli` | reuse — `main`/`dispatch` signatures change |
| `CliContext`, `StdoutRenderer[A]`, `SubcommandWiring` | case class / trait / object | `org.sinemenda.probatio.cli` | reuse and extend |
| `BinaryResolution`, `Platform`, `ReleaseManifest`, `ReleaseValidator`, `ChecksumVerifier`, `SbomModel` | objects / types | `org.sinemenda.probatio.packaging` | reuse unchanged |
| `OracleGreenGate`, `OracleGreenCheck`, `Stage`, `ToolId`, `SeamConfiguration`, `MigrationState` | test-only | `org.sinemenda.probatio.migration` | reuse — back `OracleGreenCheck` with the differential harness |
| `SwapOrder`, `ShimSwap`, `ShimGenerator`, `InstallResolver`, `ExitCodeMapping` | enum / case class / objects | `org.sinemenda.probatio.migration`, `.plugin` | reuse — extend with the revert direction |
| `ChainStateKernel`, `LedgerValidatorKernel`, `BannerEngineKernel`, `ConformanceModel` | Stainless objects | `org.sinemenda.probatio.core`, `.verified` (in `probatio-verified`) | reuse and extend for Ring 6 |

### Introduce

New concepts previewed by the proposal. Each will be committed by the spec named
in its row and appended to the project inventory when implemented — none are
recorded as `shipped` now.

| Concept | Kind | Package (planned) | Committed by |
|---------|------|-------------------|--------------|
| `ProgramArgs` | opaque type over `List[String]` | `org.sinemenda.probatio.cli` | `cli-entrypoint-contract` |
| `SpecDocument`, `RequirementBlock`, `PropertyBlock`, `TemporalBlock` | case classes | `org.sinemenda.probatio.core` | `spec-lint-engine` |
| `ObligationRow`, `ObligationSource` | case class / enum | `org.sinemenda.probatio.core` | `spec-lint-engine` |
| `CheckOutcome` | enum | `org.sinemenda.probatio.core` | `spec-lint-engine` |
| `LintContext` | case class | `org.sinemenda.probatio.core` | `spec-lint-engine` |
| `RepositoryFacts` | case class + reader | `org.sinemenda.probatio.cli` (reader) / `.core` (data) | `live-fact-banner` |
| `DangerPattern`, `DangerHit` | enum / case class | `org.sinemenda.probatio.core` | `danger-reconcile-engines` |
| `Corroboration` | enum | `org.sinemenda.probatio.core` | `danger-reconcile-engines` |
| `HarnessPayload`, `ToolOutcome` | case class / enum | `org.sinemenda.probatio.core` | `gate-event-completeness` |
| `GateStateDir`, `SessionId`, `RefusalBudget` | case class / opaque type / case class | `org.sinemenda.probatio.cli` (reader) / `.core` (data) | `gate-event-completeness` |
| `RingEvidence` | case class | `org.sinemenda.probatio.core` | `ledger-checkpoint-parity` |
| `DifferentialResult`, `CutoverVerdict` | case class / enum | `org.sinemenda.probatio.migration` (test-only) | `cutover-gate` |

**Placement rule** (from `capability-check.md`, R-ARCH1): every *reader* listed
above lives in `probatio-cli`; `probatio-core` receives its result as a value and
performs no file I/O and no `System.getenv`. This mirrors the existing
`PredecessorCheck`/`GrantWaiver` discipline and is compile-negative enforced.

### Remove

This change **removes** seven `Subcommand` variants and their entrypoint objects,
per the proposal's Out-of-Scope section. Recording removals here so the Step 12
removal audit has a declared target rather than a discovered one:

| Concept | Kind | Package | Reason |
|---------|------|---------|--------|
| `Subcommand.RegistryCheck` + `RegistryCheckCmd` | enum case + object | `org.sinemenda.probatio.cli` | stub returning `Ran(0)`; tool stays on `registry-check.sh` |
| `Subcommand.Scan` + `ScanCmd` | enum case + object | `org.sinemenda.probatio.cli` | stub; tool stays on `scan.sh` → `concept-scanner.scala` |
| `Subcommand.RemovalAudit` + `RemovalAuditCmd` | enum case + object | `org.sinemenda.probatio.cli` | stub; tool stays on `removal-audit.sh` |
| `Subcommand.ImpactScan` + `ImpactScanCmd` | enum case + object | `org.sinemenda.probatio.cli` | stub; tool stays on `impact-scan.sh` |
| `Subcommand.ConceptScanner` + `ConceptScannerCmd` | enum case + object | `org.sinemenda.probatio.cli` | stub; blocked on the R-N5 scalameta native-image spike |
| `Subcommand.Graph` + `GraphCmd` | enum case + object | `org.sinemenda.probatio.cli` | stub with a flag surface (`--module`/`--format`) that does not even match `openspec-graph.py`'s subcommands (`export`/`stats`/`impact`/`obligations`/`concept-code`); tool stays on python3 |
| `MetalsCmd` sub-actions `stop` and `call` | match arms | `org.sinemenda.probatio.cli` | stubs; `metals-call.sh`/`metals-start.sh` remain. `metals start` is retained (it is wired to `MetalsClient.initialize`). |

Removing the enum cases makes each unimplemented tool **unparseable** rather than
**silently green** — the same argument `Subcommand`'s own scaladoc already makes
for the absent mutation subcommands ("unparseable vs. denylisted — strictly
stronger"). It also removes 365 NoCoverage mutants from the Ring 5 surface, which
is the mechanical reason `cli-wiring` scored 16.39%.

## Defects found during verification (not inventory staleness)

Recorded here because they were discovered by this artifact's scan and are
inputs to the specs, not to the inventory:

1. **`LedgerRecordOptional` is orphaned.** `LedgerRecord`'s `given ReadWriter`
   serialises exactly ten fields; `sha256`, `digest`, `wallTime`, `source` and
   `session` are dropped on write. `LedgerCmd.runAppend` re-adds `session` and
   `source` by hand to a raw `ujson.Obj`, and run mode adds none of the three
   capture fields. → `ledger-checkpoint-parity`.
2. **`DriftScan.installRoots` lists three roots, its scaladoc says six.** The
   predecessor scans `.agents/skills`, `.claude/skills`, `.pi/skills`,
   `$HOME/.agents/skills`, `$HOME/.claude/skills`, `$HOME/.zcode/skills`. The
   three `$HOME` roots are missing, which is why the port reports "no skill
   installed" on a host where the predecessor detects a v13 drift at
   `~/.zcode/skills`. → `live-fact-banner`.
3. **`ChainStateCmd` double-prefixes its undetermined reason**, emitting
   `chain-state: UNDETERMINED — UNDETERMINED — no ledger at …` because
   `SubcommandWiring.readLedgerFile` already embeds the prefix in the reason
   string. Observed 2026-08-29 by invoking the built binary. A
   byte-compatibility break against the predecessor. → `chain-state-attribution`.

A fourth candidate was investigated and **rejected**: `SubcommandWiring.stampTimestamp`
computes `Instant.now().toString.substring(0, 19) + "Z"`, which looks unsafe if
`Instant.toString` elided a zero seconds field. It does not — unlike
`LocalDateTime.toString`, `Instant.toString` always emits seconds (verified
2026-08-29 against JDK 26 for epoch seconds 0, 60, and two arbitrary instants:
every result was 20 characters). Recorded so a later reader does not re-raise it.
