# Implementation Progress — complete-probatio-cutover

<!-- Tracks the apply-phase state for each spec. Updated as each spec
     completes its verification rings. The R-M5 requirement (a recorded
     limitation is re-established before it is relied upon) requires that
     the oracle re-run and conformance re-verification results are recorded
     here. implementation-progress.md is the SINGLE SOURCE OF TRUTH for
     progress; tasks.md is regenerated from it at each checkpoint and is
     never hand-maintained in parallel. -->

## Spec 1: cli-entrypoint-contract

### Status: IN PROGRESS

### Baseline
- SHA: `ad73d13bec153d6e243a1ac2e1f6bdd81d9b428d`
- Date: 2026-08-29

### Step Progress
- [x] Step 0 — Baseline + concept check
- [x] Step 1 — Typed contract (human gate) ← APPROVED
- [x] Step 2 — Test oracle (human gate) ← APPROVED
- [x] Step 3 — Implementation
- [x] Ring 0 — compile clean under `-Werror` + exhaustiveness escalation
- [x] Ring 1 — WartRemover + Scalafix clean; dangerous-pattern scan via predecessor script
- [x] Ring 2 — `probatio-cli/dependencyLint` clean
- [x] Ring 3 — property + scenario suites green; `SubprocessConformanceSpec`; re-measure bats oracle
- [x] Ring 8 — fresh-context adversarial review against the built artifact
- [ ] Ring 5 — retarget `stryker4s.conf`; threshold 80%; record score (deferred — separate run)
- [ ] Ring 6 — `DispatchKernel` mirror + `EntrypointBridgeSpec`; `sbt -J-Xmx6g ring6` (deferred — separate run)
- [x] Concept-delta check + update `openspec/concept-inventory.md` + checkpoint

### Artifacts Created
| File | Purpose |
|------|---------|
| `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/ProgramArgs.scala` | Opaque type over `List[String]`; constructible only via `fromRuntime`/`fromFixture` |
| `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/InvocationName.scala` | Opaque type over `String`; constructible only via `fromRuntime` (returns `Either`) |
| `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/Subcommand.scala` | Shrunk from 16 to 10 cases (removed 6 unported tools) |
| `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/MulticallDispatch.scala` | `resolve(argv0, argv1)` replaced by `resolveAndSplit(InvocationName, ProgramArgs)` (`???` body) |
| `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/ProbatioMain.scala` | `dispatch` signature changed to `(InvocationName, ProgramArgs) => Int`; `runSubcommand` updated; `main` wraps args as `ProgramArgs` |
| `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/HelpRegistry.scala` | Removed 6 help definitions for unported tools; metals help loses stop/call |
| `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointContractTypeContract.scala` | Typed contract: signature pins + 9 compile-negative tests |
| `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/MulticallDispatchSpec.scala` | Migrated to `resolveAndSplit(InvocationName, ProgramArgs)` API |
| `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ProbatioDispatchSpec.scala` | Migrated to `dispatch(InvocationName, ProgramArgs)` API |
| `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointContractSpec.scala` | Test oracle: 13 scenarios + 3 properties + 9 compile-negative tests |
| `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SubprocessConformanceSpec.scala` | Subprocess conformance: 6 scenarios + 1 fixture-corpus property |
| `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/CliSurfaceSpec.scala` | Updated: 16→10 subcommands, metals SubAction Start-only |

### Verification Ring Results
| Ring | Result | Evidence |
|------|--------|----------|
| Step 2 ORACLE POLARITY | 30 RED / 172 GREEN (expected) | `sbt probatio-cli/test`: 30 fail (NotImplementedError from `???` in `resolveAndSplit` + missing artifact + MetalsCmd.SubAction not yet shrunk), 172 pass (compile-negative + type-level + existing) |
| Step 3 IMPLEMENTATION | 196 GREEN / 6 RED (subprocess-only) | `sbt probatio-cli/test`: 196 pass, 6 fail (all SubprocessConformanceSpec — built artifact not found, expected until native-image build) |
| Ring 0 — compile clean | PASS | `sbt probatio-cli/Test/compile`: success under `-Werror` + exhaustiveness escalation |
| Ring 1 — Scalafix + scalafmt | PASS (changed files) | `sbt probatio-cli/scalafmt`: 8 files reformatted; `scalafixAll --check`: 2 pre-existing errors in untouched files (CliContext.scala, GateBannerCompatSpec.scala) |
| Ring 2 — dependencyLint | PASS | `sbt probatio-cli/dependencyLint`: R-ARCH1 classpath clean |
| Ring 3 — property + scenario suites | 202/202 GREEN | All tests pass after native-image build (196 in-process + 6 subprocess) |
| Ring 8 — adversarial review | 40/40 PASS (after defect fixes) | Fresh-context review: 40 tests, all pass. 3 defects found in initial review, all fixed and verified. |
| _(populated as rings complete)_ | | |

### Concept Delta
- **Added**: `ProgramArgs` (opaque type over `List[String]`), `InvocationName` (opaque type over `String`)
- **Modified**: `Subcommand` (enum shrunk 16→10 cases), `MulticallDispatch` (resolve→resolveAndSplit), `ProbatioMain` (dispatch signature changed), `MetalsCmd.SubAction` (Start only, Stop/Call removed)
- **Removed**: `RegistryCheckCmd`, `ScanCmd`, `RemovalAuditCmd`, `ImpactScanCmd`, `ConceptScannerCmd`, `GraphCmd` (6 unported entrypoint objects)
- **Inventory updated**: `openspec/concept-inventory.md` updated with new/modified/removed entries

### Known Limitations
- **DEFECT-1 (MAJOR, FIXED)**: `gate` without `--event` now emits "gate: --event is required" on stderr and exits 1. Was: silently defaulted to `session-start` and exited 0. Fix: `GateCmd.run` checks `parsed.get("--event")` explicitly.
- **DEFECT-2 (MINOR, FIXED)**: Top-level `probatio --help` now prints usage listing all subcommands and exits 0. Was: treated as `UnknownSubcommand("--help")`. Fix: `ProbatioMain.dispatch` checks for `--help` as first arg under the generic name.
- **DEFECT-3 (MINOR, FIXED)**: POSIX `--` separator now consumed before subcommand resolution. Was: treated as the subcommand name. Fix: `resolveAndSplit` consumes `--` as first arg under the generic name before dispatching by the next arg.

---

## Spec 2: cutover-gate

### Status: PENDING

### Step Progress
- [ ] Prerequisite — add `probatioOracleDiff` sbt task
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–6, 8 + concept-delta + checkpoint

---

## Spec 3: live-fact-banner

### Status: PENDING

### Step Progress
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–6, 8 + concept-delta + checkpoint

---

## Spec 4: spec-lint-engine

### Status: PENDING

### Step Progress
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–6, 8 + concept-delta + checkpoint

---

## Spec 5: chain-state-attribution

### Status: PENDING

### Step Progress
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–6, 8 + concept-delta + checkpoint

---

## Spec 6: danger-reconcile-engines

### Status: PENDING

### Step Progress
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–6, 8 + concept-delta + checkpoint

---

## Spec 7: ledger-checkpoint-parity

### Status: PENDING

### Step Progress
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–6, 8 + concept-delta + checkpoint

---

## Spec 8: gate-event-completeness

### Status: PENDING

### Step Progress
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–6, 8 + concept-delta + checkpoint

---

## Spec 9: native-gate-delivery

### Status: PENDING

### Step Progress
- [ ] Prerequisite — GraalVM toolchain
- [ ] Step 1 — Typed contract (human gate)
- [ ] Step 2 — Test oracle (human gate)
- [ ] Step 3 — Implementation
- [ ] Ring 0–5, 8 + concept-delta + checkpoint

---

## Change Exit Criterion

- [ ] `sbt probatioOracleDiff` reports no bats file worse than the predecessor control (deficit measured 2026-08-29: 122 ported failures vs 20 predecessor failures, 102 tests → zero)
- [ ] Every predecessor script under `openspec/schemas/verified-scala3/` that the cutover replaced remains on disk as the revert target until this criterion has held green across a full change cycle
