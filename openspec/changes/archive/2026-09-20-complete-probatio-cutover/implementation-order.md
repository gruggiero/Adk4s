# Implementation Order

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/cli-entrypoint-contract/spec.md` | `ProgramArgs`, `InvocationName` | `Subcommand`, `MulticallDispatch`, `ProbatioMain`, `CliError`, `ExitCode`, `HelpRegistry`, `Outcome[+A]` (all from inventory) | medium |
| 2 | `specs/cutover-gate/spec.md` | `DifferentialResult`, `CutoverVerdict` | `OracleGreenGate`, `OracleGreenCheck`, `Stage`, `ToolId`, `SeamConfiguration`, `MigrationState`, `SwapOrder`, `ShimSwap`, `ShimGenerator`, `Outcome[+A]` (all from inventory) | medium |
| 3 | `specs/live-fact-banner/spec.md` | `RepositoryFacts` | `BannerEngine`, `BannerInputs`, `BannerOutput`, `ActiveChangeWithChainState`, `InstallRootScan`, `DriftScan`, `DriftWarning`, `DriftScanResult`, `ChainStateReport`, `ChainStateUndetermined` (inventory) + `LintContext` (spec 4) | medium |
| 4 | `specs/spec-lint-engine/spec.md` | `SpecDocument`, `RequirementBlock`, `PropertyBlock`, `TemporalBlock`, `ObligationRow`, `ObligationSource`, `CheckOutcome`, `LintContext` | `LintReport`, `RequirementVerdict`, `Verdict`, `CheckId`, `LintWarning`, `Outcome[+A]`, `DriftScan`, `StdoutRenderer[A]`, `SubcommandWiring` (inventory) + `RepositoryFacts` (spec 3) | high |
| 5 | `specs/chain-state-attribution/spec.md` | `RequirementSet`, `FactSource` | `ChainState`, `ChainStateReport`, `ChainStateUndetermined`, `UnresolvedEntry`, `UnresolvedReason`, `UnmappedObligation`, `LintReport`, `Ledger.LedgerData`, `LedgerRecord`, `Ring`, `Outcome[+A]` (inventory) + `SpecDocument`, `ObligationRow`, `ObligationSource` (spec 4) | high |
| 6 | `specs/danger-reconcile-engines/spec.md` | `DangerPattern`, `DangerHit`, `DangerReport`, `Corroboration`, `ReconcileReport` | `Outcome[+A]`, `LedgerRecord`, `Ledger.LedgerData`, `Ring`, `Validator`, `ContractViolation`, `SubcommandWiring`, `StdoutRenderer[A]` (all from inventory) | medium |
| 7 | `specs/ledger-checkpoint-parity/spec.md` | `RingEvidence`, `CheckpointReport`, `ReplayVerdict` | `LedgerRecord`, `LedgerRecordOptional`, `Ledger`, `Validator`, `ContractViolation`, `Ring`, `Outcome[+A]`, `ChainStateReport`, `PresentationMarker`, `SubcommandWiring` (inventory) + `SessionId` (spec 8) | high |
| 8 | `specs/gate-event-completeness/spec.md` | `HarnessPayload`, `ToolOutcome`, `GateStateDir`, `SessionId`, `RefusalBudget`, `HeartbeatRecord` | `GateEvent`, `GateDecision`, `BlockReason`, `SpecPhase`, `PredecessorCheck`, `GrantWaiver`, `PresentationMarker`, `GatePayload`, `HookSpecificOutput`, `LedgerRecord`, `Ring`, `Validator`, `Outcome[+A]`, `SchemaPolicy`, `CliContext` (all from inventory) | high |
| 9 | `specs/native-gate-delivery/spec.md` | `LatencyMeasurement` | `BinaryResolution`, `Platform`, `ReleaseManifest`, `ReleaseValidator`, `ChecksumVerifier`, `SbomModel`, `InstallResolver`, `ShimGenerator`, `ExitCodeMapping` (all from inventory) | medium |

**Topological sort rationale**:

- **1 `cli-entrypoint-contract` first, unconditionally.** Until the entry point
  delivers arguments correctly, every oracle measurement conflates "not
  implemented" with "not reachable". Nothing downstream can be honestly measured
  before it. It also introduces no dependency on any other spec.

- **2 `cutover-gate` second.** It introduces the differential harness, which is
  the Ring 3 acceptance mechanism for specs 3–9. Building it second means every
  later spec has a measurable exit criterion from its first commit. It depends
  only on inventory concepts. Placing it after spec 1 (rather than first) is
  deliberate: its own acceptance is a comparison, and a comparison taken while
  the entry point is broken measures the entry point.

- **3 `live-fact-banner` before 4 `spec-lint-engine`.** Both need the repository
  facts reader. The banner spec introduces `RepositoryFacts`; the lint engine's
  `LintContext` is a projection of it. Building the reader under the smaller,
  better-bounded spec first gives the larger one a tested foundation. The
  forward reference is one-directional: spec 3 does not use `LintContext`.

- **4 `spec-lint-engine` before 5 `chain-state-attribution`.** The correctness
  computation consumes parsed spec documents and obligation rows, which the lint
  engine introduces. This is the strongest dependency in the change — spec 5
  cannot produce a non-empty requirement set without spec 4's parser.

- **6 `danger-reconcile-engines` after 5.** No hard dependency; ordered here
  because it is independent, medium-complexity, and gives fast feedback between
  the two high-complexity specs on either side. Its two engines are the last
  wholly-missing tools that the gate's post-edit tier will delegate to.

- **7 `ledger-checkpoint-parity` before 8 `gate-event-completeness`.** Spec 8's
  post-tool observation writes records, and its predecessor/grant tiers read
  presentation markers. Both surfaces must be correct before the gate depends on
  them. The `SessionId` forward reference runs the other way — spec 7's checkpoint
  needs it — so `SessionId` is implemented in spec 7 and *extended* by spec 8,
  recorded as a deliberate exception below.

- **9 `native-gate-delivery` last.** It measures and delivers the artifact every
  other spec produced. Measuring latency before the gate's behaviour is complete
  would measure the wrong program.

**The one forward reference, stated rather than hidden**: `SessionId` is listed
as introduced by spec 8 but is first *needed* by spec 7. Resolution: spec 7
introduces `SessionId` with its lossless-encoding smart constructor and its
injectivity property; spec 8 adds the resolution-order logic (explicit → harness
→ generic override → fallback) that only the gate needs. Spec 8's Concepts
Introduced table is amended at implementation time to record `SessionId` as
*modified*, not introduced. Flagged here so the Step 5 concept-delta check does
not read it as an undeclared concept.

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | cli-entrypoint-contract | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — | full |
| 2 | cutover-gate | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | ✅ | — | full |
| 3 | live-fact-banner | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — | full |
| 4 | spec-lint-engine | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — | full |
| 5 | chain-state-attribution | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | full |
| 6 | danger-reconcile-engines | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — | full |
| 7 | ledger-checkpoint-parity | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | full |
| 8 | gate-event-completeness | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | full |
| 9 | native-gate-delivery | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | minimal |

**Ring rationale**:

- **R0** — all 9. `probatioScalacOptions` (`-Werror`, deprecation and feature
  escalation, `-Wsafe-init`) plus the repo-wide exhaustiveness escalation. Specs
  1 and 8 change enum shapes, so R0 is the forcing function that surfaces every
  unhandled match.
- **R1** — all 9. WartRemover + Scalafix DisableSyntax. **Caveat carried from
  `capability-check.md`**: the dangerous-pattern half of R1 is currently
  unenforceable, because `danger-scan.sh` is a shim onto a stub that returns
  clean without scanning. Until spec 6 lands, R1's scan must be run via
  `scanner/danger-scan.sh.predecessor.bak` and the invocation recorded.
- **R2** — all 9. `probatioDependencyLint` for the dependency half; per-file
  compile-negatives for the "no file I/O in `probatio-core`" half, which no
  dependency rule can express.
- **R3** — all 9, mandatory. munit + Hedgehog. **Acceptance for specs 3–9 is the
  spec's own bats files at parity with the predecessor control under
  `probatioOracleDiff`**, not merely green unit tests. Specs 1 and 2 predate the
  harness, so their acceptance is: spec 1, the subprocess conformance suite plus
  a re-measured oracle baseline; spec 2, the harness reproducing the recorded
  2026-08-29 comparison.
- **R4** — specs 5, 7, 8 only. These three touch the three `.jq` wire contracts
  (report shape, record shape, envelope shape). The others touch no persisted or
  wire data.
- **R5** — all except 2. Spec 2 is test-only migration code, not shipped
  production logic. **Both `mutate` and `test-filter` must be retargeted per
  spec**; `stryker4s.conf` currently points at one unrelated file. Thresholds:
  **90%** for `probatio-core` decision logic (specs 4, 5, 6, 7), **80%** for
  `probatio-cli` adapters (specs 1, 3, 8, 9). **`break = 0` means the tool never
  fails the build on score — the reported score must be read and recorded, not
  inferred from the exit code.**
- **R6** — all except 9. Eight mirrors, five new (`DispatchKernel`,
  `SpecLintKernel`, `GateKernel`, `ReconcileKernel`, `CutoverKernel`) and three
  extended (`ChainStateKernel`, `BannerEngineKernel`, `LedgerValidatorKernel`).
  Each carries a bridge property test in the owning module's ordinary test scope.
  Spec 9 is skipped with a stated reason: its decisions are table lookups and
  conjunctions with no fold or law, and the design's triage table records that
  verdict explicitly.
- **R7** — none. No distributed or event-ordering invariant beyond the record
  set's append-only discipline, which R6 covers.
- **R8** — all 9, mandatory, fresh context, before R5 and R6. **Standing
  instruction for this change**: verify each requirement against the built
  artifact invoked as a subprocess. Two prior R8 reviews passed a port whose
  every subcommand was unreachable and whose banner fabricated its facts; both
  inspected functions, and both functions were correct.
- **R9** — none. No telemetry stack in `workflow/*`.

**Typed contract rationale** (from the proposal's decision table): specs 1–8 are
**full** — each introduces new types and changes public behaviour. Spec 9 is
**minimal** — build and packaging wiring around `BinaryResolution`, which already
exists and is unchanged.

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | cli-entrypoint-contract | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/ProbatioMain.scala`, `MulticallDispatch.scala`, `Subcommand.scala`, `SubcommandEntrypoints.scala` (six objects removed), `HelpRegistry.scala`, `ProgramArgs.scala` (new) |
| 2 | cutover-gate | none in production — test-only (`workflow/core/src/test/scala/org/sinemenda/probatio/migration/`). R5 skipped |
| 3 | live-fact-banner | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/RepositoryFactsReader.scala` (new), `workflow/core/src/main/scala/org/sinemenda/probatio/core/BannerEngine.scala`, `DriftScan.scala`, `SubcommandEntrypoints.scala` (gate banner assembly) |
| 4 | spec-lint-engine | `workflow/core/src/main/scala/org/sinemenda/probatio/core/SpecLintEngine.scala` (new), `SpecDocumentParser.scala` (new), `CheckOutcome.scala` (new), `LintReport.scala`, `workflow/cli/.../SubcommandEntrypoints.scala` (SpecLintCmd), `StdoutRenderer.scala` |
| 5 | chain-state-attribution | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ChainState.scala`, `ChainStateReport.scala`, `RequirementExtractor.scala` (new), `workflow/cli/.../SubcommandEntrypoints.scala` (ChainStateCmd), `SubcommandWiring.scala`, `StdoutRenderer.scala` |
| 6 | danger-reconcile-engines | `workflow/core/src/main/scala/org/sinemenda/probatio/core/DangerScanEngine.scala` (new), `ReconcileEngine.scala` (new), `workflow/cli/.../ChangedFilesReader.scala` (new), `SubcommandEntrypoints.scala` (DangerScanCmd, ReconcileCmd) |
| 7 | ledger-checkpoint-parity | `workflow/core/src/main/scala/org/sinemenda/probatio/core/LedgerRecord.scala`, `Ledger.scala`, `CheckpointEngine.scala` (new), `CheckpointReport.scala` (new), `ReplayVerdict.scala` (new), `SessionId.scala` (new), `workflow/cli/.../SubcommandEntrypoints.scala` (LedgerCmd, CheckpointCmd) |
| 8 | gate-event-completeness | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ToolOutcome.scala` (new), `RefusalBudget.scala` (new), `HeartbeatRecord.scala` (new), `GateEvent.scala`, `SessionId.scala` (extended), `workflow/cli/.../GateStateDirReader.scala` (new), `HarnessPayloadReader.scala` (new), `SubcommandEntrypoints.scala` (GateCmd), `CliContext.scala` |
| 9 | native-gate-delivery | `workflow/cli/src/main/scala/org/sinemenda/probatio/packaging/LatencyMeasurement.scala` (new), `workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin/ProbatioPlugin.scala`, `ShimGenerator.scala`, `InstallResolver.scala`, `build.sbt` |

## Human Gate Tier

| # | Spec | Tier | Justification |
|---|------|------|---------------|
| 1 | cli-entrypoint-contract | **separate** | complexity=medium; proposal risk=high. The entry-point signature and the tool surface both change, and the surface shrinks by six tools — a reviewer should see the typed contract before the oracle |
| 2 | cutover-gate | **separate** | complexity=medium; risk=high. This spec defines what "green" means for every later spec; approving its contract and its oracle together would let one decision carry both |
| 3 | live-fact-banner | **separate** | complexity=medium; risk=high |
| 4 | spec-lint-engine | **separate** | complexity=high; risk=high. The largest single porting surface in the change |
| 5 | chain-state-attribution | **separate** | complexity=high; risk=high. Alters what the workflow *concludes*, not only what it prints |
| 6 | danger-reconcile-engines | **separate** | complexity=medium; risk=high |
| 7 | ledger-checkpoint-parity | **separate** | complexity=high; risk=high. Touches the persisted record format |
| 8 | gate-event-completeness | **separate** | complexity=high; risk=high. The only blocking hook; a bad gate strands every session |
| 9 | native-gate-delivery | **separate** | complexity=medium; risk=high |

**No combined-tier specs.** The combined tier requires complexity=simple AND
proposal risk=low; the proposal's risk is **high** for the whole change, so the
condition cannot be met by any spec regardless of its complexity. Recorded
explicitly rather than left implicit.

## Complexity Guide

- **SIMPLE**: No new types, ≤1 new method on an existing trait, no new error
  variants. Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.
- **MEDIUM**: New types OR complex business logic OR new error handling paths.
  Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.
- **HIGH**: New types AND complex logic AND involves Ring 6/7 or Ring 9. Typed
  contract: full. All applicable rings.

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Record baseline SHA (clean tree) + inventory snapshot; read
        openspec/concept-inventory.md — import existing concepts; verify the spec's
        Proof Obligations table is complete
     2. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE
     3. Test oracle from spec + contract only (before implementation),
        run once for ORACLE POLARITY (red / green-by-design)
        → human review GATE
     4. Implement through all applicable rings (see table above) — Ring 8
        adversarial review (fresh context) runs BEFORE Rings 5/6
     5. Concept delta check + build-dependency delta +
        update openspec/concept-inventory.md
     6. Mark checkbox below, regenerate tasks.md, COMMIT the spec
     7. STOP for human validation before next spec

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

- [x] 1. `specs/cli-entrypoint-contract/spec.md` — fix the argv contract so every tool is reachable through the built artifact; shrink the surface to the nine tools that are actually implemented; add subprocess-level conformance
- [x] 2. `specs/cutover-gate/spec.md` — build the differential harness and the comparison-based gate; redefine "oracle green" as parity with the predecessor control; make the revert path executable
- [x] 3. `specs/live-fact-banner/spec.md` — read the repository facts and assemble the banner from them; scan all six install roots for instruction drift; stop asserting fabricated facts as reads
- [x] 4. `specs/spec-lint-engine/spec.md` — port the F1–F10 checks, the W1–W7 warnings, the obligation-table parser and the applicability block, bound to the predecessor by an executable parity property
- [x] 5. `specs/chain-state-attribution/spec.md` — extract the change's real requirements, compute the correctness verdict over them, and make the unattributable and unmapped-obligation reports reachable
- [x] 6. `specs/danger-reconcile-engines/spec.md` — port the dangerous-pattern scan with its justification handling, and the corroboration classifier that tells a witnessed run from testimony
- [x] 7. `specs/ledger-checkpoint-parity/spec.md` — stop dropping the record's observation fields; add the replay operation; generate the checkpoint from recorded evidence and gate the presentation marker on it
- [x] 8. `specs/gate-event-completeness/spec.md` — add the sixth event and the ambient evidence writer; wire the blocking tiers to a real state reader with a bounded refusal budget; restore the installation probe
- [x] 9. `specs/native-gate-delivery/spec.md` — build and measure the native artifact, bind the shim's target to the resolution result, and give each build task the arguments its tool requires

### Exit criterion for the change as a whole

`probatioOracleDiff` reports **no file worse than the predecessor control** across
all 17 bats files. Measured 2026-08-29, the starting point is 122 ported failures
against 20 predecessor failures — a deficit of 102 tests. The change is complete
when that deficit is zero, and not when the last checkbox is ticked.
