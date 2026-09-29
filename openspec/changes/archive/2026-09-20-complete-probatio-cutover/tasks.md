# Tasks

<!-- Derived from implementation-order.md. implementation-progress.md is the
     single source of truth for progress; this file is regenerated from it at
     each checkpoint and is never hand-maintained in parallel. -->

## 1. cli-entrypoint-contract — COMPLETE

- [x] Prerequisite — rebuild `probatio-cli/assembly` and record the pre-change oracle baseline via the predecessor scripts (control) and the current shims (port), so spec 2's harness has a recorded starting point
- [x] Step 1 — typed contract: `ProgramArgs` (opaque over `List[String]`), `InvocationName` (opaque over `String`), `MulticallDispatch.resolveAndSplit(InvocationName, ProgramArgs): Either[CliError, (Subcommand, ProgramArgs)]`, `ProbatioMain.dispatch(InvocationName, ProgramArgs): Int`, `Subcommand` shrunk to 9 cases (compiles under `-Werror`, human gate)
- [x] Step 2 — test oracle: 14 scenarios + 4 properties (`argument-preservation`, `dispatch-equivalence-across-signals`, `no-silent-selection`, `subprocess-agrees-with-in-process`) + 3 compile-negative stubs; ORACLE POLARITY run (human gate)
- [x] Step 3 — implementation: obtain the invocation name from the runtime in `main`; remove the six unported `Subcommand` cases and their entrypoint objects; remove `MetalsCmd`'s `stop`/`call` arms; update `HelpRegistry` and every match; migrate `CliWiringContractSpec`, `MulticallDispatchSpec`, `ProbatioDispatchSpec`, `CliSurfaceSpec` off hand-built argv0-prefixed arrays
- [x] Ring 0 — `sbt "probatio-cli/compile"` clean under `-Werror` + exhaustiveness escalation (the shrunk enum must surface every unhandled match)
- [x] Ring 1 — WartRemover + Scalafix clean; dangerous-pattern scan via `scanner/danger-scan.sh.predecessor.bak` (the shim is a stub until spec 6), invocation recorded
- [x] Ring 2 — `probatio-cli/dependencyLint` clean
- [x] Ring 3 — property + scenario suites green; `SubprocessConformanceSpec` starts every exposed tool from the assembly the shims resolve; **re-measure the full bats oracle and record the new per-file failure counts**
- [x] Ring 8 — fresh-context adversarial review, verifying against the built artifact invoked as a subprocess
- [x] Ring 5 — retarget `stryker4s.conf` `mutate` + `test-filter` to `ProbatioMain.scala`, `MulticallDispatch.scala`, `Subcommand.scala`, `ProgramArgs.scala`, `InvocationName.scala`, `HelpRegistry.scala`; threshold 80%; **read and record the reported score** (`break = 0` never fails the build) — 100% covered-code
- [x] Ring 6 — `DispatchKernel` mirror + `EntrypointBridgeSpec`; add `probatio-verified % Test` to `probatio-cli`; `sbt -J-Xmx6g ring6` — 197/197 VCs valid
- [x] Concept-delta check + update `openspec/concept-inventory.md` (2 added, 6 `Subcommand` cases + 6 entrypoint objects removed) + checkpoint

## 2. cutover-gate — COMPLETE

- [x] Prerequisite — add the `probatioOracleDiff` sbt task to `build.sbt`
- [x] Step 1 — typed contract: `DifferentialResult`, `CutoverVerdict` (`Proceed` | `Revert(DifferentialResult)`), `CutoverGate.decide`, `SeamConfiguration` smart constructor taking only the ported set (compiles, human gate)
- [x] Step 2 — test oracle: 14 scenarios + 5 properties (`proceed-iff-no-file-worse`, `total-improvement-does-not-excuse-a-regression`, `incomplete-comparison-never-proceeds`, `seam-resolves-to-exactly-one`, `revert-restores-every-swapped-seam`) + 4 compile-negative stubs; ORACLE POLARITY run (human gate)
- [x] Step 3 — implementation: `DifferentialHarness` materialises two seam-configured scanner trees inside the repository, verifies suite-file digests against the repository's, runs the suite twice, parses both TAP outputs; `OracleGreenCheck` and `OracleGreenGate` delegate to `CutoverGate`; the revert path restores every swapped seam
- [x] Step 3b — update `openspec/concepts/conformance-property-test-contract.md` (green becomes parity, not `failed == 0`) and `openspec/concepts/strangler-migration-protocol.md` (the Abort action gains its executable form)
- [x] Ring 0 — `sbt "probatio-core/Test/compile"` clean
- [x] Ring 1 — lint clean; dangerous-pattern scan via the predecessor script, recorded
- [x] Ring 2 — `probatio-core/dependencyLint` clean
- [x] Ring 3 — property + scenario suites green; the harness reproduces the recorded 2026-08-29 comparison (predecessor 20 failures, port 122) and the gate refuses it
- [x] Ring 8 — fresh-context adversarial review
- [x] Ring 6 — `CutoverKernel` mirror + `CutoverBridgeSpec`; `sbt -J-Xmx6g ring6`
- [x] Concept-delta check + inventory update (2 added, `SeamConfiguration` modified) + checkpoint

## 3. live-fact-banner — COMPLETE

- [x] Step 1 — typed contract: `RepositoryFacts`, `RepositoryFactsReader.read(root): RepositoryFacts`, `BannerInputs.from(RepositoryFacts)` with the raw constructor made private, `DriftScan.installRoots` widened to six (compiles, human gate)
- [x] Step 2 — test oracle: 13 scenarios + 4 properties (`facts-reflect-repository`, `banner-states-only-read-facts`, `every-searched-root-is-scanned`, `suppression-tracks-facts`) + 3 compile-negative stubs; ORACLE POLARITY run (human gate)
- [x] Step 3 — implementation: the reader reads registry, inventory, profile, six install roots and active changes; `GateCmd` stops constructing `BannerInputs` from literals; active changes carry live chain-state; suppression is keyed on the facts
- [x] Ring 0 — `sbt "probatio-core/compile" "probatio-cli/compile"` clean
- [x] Ring 1 — lint clean; dangerous-pattern scan via the predecessor script, recorded
- [x] Ring 2 — `dependencyLint` clean for both modules
- [x] Ring 3 — property + scenario suites green; **`gate-payload.bats` at parity with the control under `probatioOracleDiff`** (baseline: 0 predecessor failures, 21 ported)
- [x] Ring 8 — fresh-context adversarial review, diffing the emitted banner against the predecessor's on one repository
- [x] Ring 5 — Stryker4s on `RepositoryFactsReader.scala`, `GateStateDir.scala`, `SubcommandEntrypoints.scala`: 95.16% covered-code (≥80%); 12 survivors all classified equivalent/dead-code in implementation-progress.md
- [x] Ring 6 — `BannerEngineKernel.bannerClaims` + helpers + 5 law lemmas verified (223/223 VCs, 0 invalid); `BannerBridgeSpec` green; direct `probatio-verified` invocation (ring6 alias broken in sbt 1.12)
- [x] Concept-delta check + inventory update (13 added, `BannerInputs`/`ActiveChangeWithChainState`/`InstallRootScan`/`DriftWarning`/`DriftScan` modified) + checkpoint

## 4. spec-lint-engine — COMPLETE

- [x] Step 1 — typed contract: `SpecDocument`, `RequirementBlock`, `PropertyBlock`, `TemporalBlock`, `ObligationRow`, `ObligationSource`, `CheckOutcome`, `LintContext`, `SpecDocumentParser.parse`, `SpecLintEngine.lint` (compiles, human gate)
- [x] Step 2 — test oracle: 14 scenarios + 4 properties (`verdict-parity-with-predecessor`, `reachability-is-total`, `unmatched-rows-are-reported-never-dropped`, `applicability-reflects-repository`) + 3 compile-negative stubs; extract the fixture corpus from the predecessor's bats fixtures plus the repository's spec documents; ORACLE POLARITY run (human gate)
- [x] Step 3 — implementation: the F1–F10 checks, the W1–W7 warnings, the Proof-Obligations table parser, the applicability block; `SpecLintCmd` gains the predecessor's invocation surface (positional target, artifacts modifier, facts-only modifier, format modifier)
- [x] Ring 0 — clean under `-Werror`; `CheckOutcome` exhaustiveness escalated
- [x] Ring 1 — lint clean; dangerous-pattern scan via the predecessor script, recorded (14 justified sites)
- [x] Ring 2 — `dependencyLint` clean; compile-negative proves no file I/O or environment read in the engine
- [x] Ring 3 — property + scenario suites green; **`workflow-hygiene.bats` and `fact-extraction.bats` at parity** (`probatioOracleDiff` PROCEED, no file worse across all 17 bats files)
- [x] Ring 8 — fresh-context adversarial review: 6 real parity defects fixed + verified, 1 false positive, 1 intentional divergence (recorded in implementation-progress.md)
- [x] Ring 5 — retargeted to `SpecLintEngine.scala`, `SpecDocumentParser.scala`, `CheckOutcome.scala`; **95.65% total / 96.59% covered** (threshold 90%); 19 undetected mutants all dispositioned equivalent/unreachable
- [x] Ring 6 — `SpecLintKernel` mirror + `SpecLintBridgeSpec` (2/2); Stainless 261/261 VCs valid
- [x] Concept-delta check + inventory update (12 added, 4 annotated) + checkpoint

## 5. chain-state-attribution — COMPLETE

- [x] Step 1 — typed contract: `RequirementSet`, `FactSource`, `RequirementExtractor.extract`, `ChainState.compute` taking `RequirementSet` instead of `List[Requirement]`, smart constructors on `ChainStateReport` and `UnresolvedEntry` — compiled under `-Werror`, `ChainStateAttributionTypeContract` + `ChainStateAttributionSpec` compile-negatives green (APPROVED)
- [x] Step 2 — test oracle: 15 scenarios + 5 properties (`verdict-parity-with-predecessor`, `counts-are-consistent`, `unattributable-is-reachable-and-never-discharged`, `obligation-rows-are-conserved`, `empty-is-not-unreadable`) + 3 compile-negative stubs; ORACLE POLARITY run (human gate)
- [x] Step 3 — implementation: extract requirements from parsed spec documents; attribute obligation rows by exact title; emit `Unattributable` and `unmapped_obligations`; report the extraction path; stop double-prefixing the could-not-determine marker; `ChainStateCmd` gains the predecessor's remaining flags — GREEN: all oracle suites + 414 core + 325 cli tests pass (APPROVED 2026-09-17)
- [x] Ring 0 — clean; the newly-reachable `UnresolvedReason.Unattributable` must be handled in every match
- [x] Ring 1 — lint clean; dangerous-pattern scan via the predecessor script, recorded
- [x] Ring 2 — `dependencyLint` clean
- [x] Ring 3 — property + scenario suites green; **`chain-state.bats` and `discharge-fidelity.bats` at parity** (baseline: 6 and 0 predecessor failures, 17 and 6 ported)
- [x] Ring 4 — `chain-state-report-contract.jq` conforms against the port's output for every fixture; `unmapped_obligations` present when empty
- [x] Ring 8 — fresh-context adversarial review ×3: 1st run FAIL (6 defects F1–F6) → fixed; re-run PARTIAL (N1/N2 dangerous + N3–N7 edge) → N1/N2/N5/N6/N7 fixed; re-run 2 PARTIAL → PROCEED (D-new-1 fixed; N3 path-keying declared for checkpoint human review)
- [x] Ring 5 — retargeted to `ChainState.scala`, `ChainStateReport.scala`, `RequirementExtractor.scala`: **91.41% total / 92.35% covered** (threshold 80); 17 undetected all dispositioned equivalent/dead-code/defensive
- [x] Ring 6 — `ChainStateKernel.chainStateFold` (count ordering + unresolved complement + unattributable-never-discharged) + Manual-ring reconcile + `VerifiedKernelBridgeSpec` non-empty bridge; `probatio-verified` **325/325 VCs valid**
- [x] Concept-delta check + inventory update + checkpoint — inventory updated (6 new rows + reshaped annotations); **VALIDATED — human checkpoint approval 2026-09-17 (N3 signed off)**

## 6. danger-reconcile-engines — COMPLETE

- [x] Step 1 — typed contract: `DangerPattern` (8 cases), `DangerHit`, `DangerReport` with smart constructor, `Corroboration` (5 cases), `ReconcileReport`, `DangerScanEngine.scan`, `ReconcileEngine.classify`, `ChangedFilesReader` (compiles, human gate) — APPROVED
- [x] Step 2 — test oracle: 16 scenarios + 5 properties (`danger-parity-with-predecessor`, `justification-excludes-exactly-its-own-occurrence`, `corroboration-is-total-and-exclusive`, `witness-requires-key-agreement`, `no-discharge-verdict-in-output`) + 4 compile-negative stubs; ORACLE POLARITY 46 RED / 19 GREEN-BY-DESIGN (human gate) — APPROVED
- [x] Step 3 — implementation: the eight pattern classes with same-line justification handling; production-only scope with caller-named additions; the corroboration classifier; both entrypoints gain the predecessor's invocation surfaces
- [x] Ring 0 — clean; both new enums exhaustiveness-escalated
- [x] Ring 1 — lint clean; ported-vs-predecessor agreement recorded by `DangerScanParitySpec` (the property runs the predecessor script as the model on every generated git fixture)
- [x] Ring 2 — `dependencyLint` clean; compile-negative proves no discharge-verdict type is referenced from the corroboration module
- [x] Ring 3 — property + scenario suites green; `probatioOracleDiff` PROCEED — all 17 bats files at parity incl. `ambient-capture-wiring.bats` and `discharge-fidelity.bats`
- [x] Ring 8 — fresh-context adversarial review (`ring8-danger-reconcile-engines.md`): 4 PARTIALs, all fixed (pattern-major emission, `observer-at-wrong-key` cover, parity-property vacuous-pass hatch, `Witnessed` coherence → `(observer, preceding, following)`)
- [x] Ring 5 — retargeted to `DangerScanEngine.scala`, `ReconcileEngine.scala`; 100% covered-code score (61/61 killed; first-run survivor + NoCoverage fixed by 3 new scenario tests)
- [x] Ring 6 — `ReconcileKernel` + `ReconcileBridgeSpec`; 345/345 VCs valid (structural recursion per ring6 experience doc §4 — the spec's `forall`/`zip` ensuring hung the solver)
- [x] Concept-delta check + inventory update + checkpoint — **VALIDATED — human checkpoint approval 2026-09-18** (post-format re-verify on 9f7d6cb: 80/80 spec-6 tests, 345/345 VCs)

## 7. ledger-checkpoint-parity — COMPLETE

- [x] Step 1 — typed contract: `RingEvidence`, `CheckpointReport` with smart constructor, `ReplayVerdict`, `SessionId` (opaque, lossless encoder), `CheckpointEngine.report`, `LedgerRecord` joined with its optional fields and a single total encoder (compiles, human gate) — APPROVED
- [x] Step 2 — test oracle: 20 scenarios + 6 properties (`record-round-trips-all-present-fields`, `self-observed-records-are-distinguishable`, `replay-verdict-is-total-and-sound`, `checkpoint-reports-every-requested-ring`, `marker-written-iff-evidenced-and-discharged`, `parity-with-predecessor`) + 5 compile-negative stubs; ORACLE POLARITY run (human gate) — APPROVED
- [x] Step 3 — implementation: observation fields on the run path; `verify` replay operation; `--session`/`--change-dir`/`--format` parameters; checkpoint `report` + `regenerate-tasks` with per-ring evidence and the same-session review ladder; marker gated on evidenced-and-discharged
- [x] Ring 0 — clean; `ReplayVerdict` exhaustiveness-escalated
- [x] Ring 1 — lint clean; dangerous-pattern scan (ported tool, now available)
- [x] Ring 2 — `dependencyLint` clean
- [x] Ring 3 — property + scenario suites green; `probatioOracleDiff` PROCEED — all 17 bats files at parity incl. `evidence-ledger.bats`, `evidence-capture.bats`, `checkpoint-from-ledger.bats`, `judgment-ring-*.bats` (0 failures)
- [x] Ring 4 — `ledger-record-contract.jq` conforms; all 32 optional-field combinations round-trip; `tests/fixtures/evidence-ledger-v1.jsonl` reads cleanly; an unrecognised version is undetermined, not skipped
- [x] Ring 8 — fresh-context adversarial review (`ring8-ledger-checkpoint-parity.md`): findings remediated (forgiven-row refiltering, version rejection, guarded fact measurement, artifact fallback, apostrophe-token, duplicate-section baseline, graph unbound); N3 path-keying signed off at spec-5 checkpoint
- [x] Ring 5 — core 97.58% (242/248 killed, 6 equivalent, 0 NoCoverage); CLI in-diff 89.9% (446 covered, 401 killed, 3 second-pass kills verified by direct mutant application, 42 equivalent) — global 78.62% diluted by pre-existing specs 1–6 code
- [x] Ring 6 — `LedgerValidatorKernel.markerDecision` + `allEvidencedIsForall` lemma + `CheckpointBridgeSpec`; 355/355 VCs valid, 0 invalid (direct `probatio-verified` invocation — `ring6` alias broken under sbt 1.12)
- [x] Concept-delta check + inventory update (4 added — including `SessionId`, which spec 8 will modify, not introduce — and `LedgerRecord` modified) + checkpoint — **VALIDATED — human checkpoint approval 2026-09-18** (post-format re-verify on `7b278b4`: compile clean, 557/557 workflow tests green, `probatioOracleDiff` PROCEED — no file worse across all 17 bats files, Stainless 355/355 valid)

## 8. gate-event-completeness — COMPLETE

- [x] Step 1 — typed contract: `HarnessPayload`, `ToolOutcome` (`Exit` | `Skip`) with `classify` as the only constructor path, `GateStateDir`, `RefusalBudget`, `HeartbeatRecord`, `GateEvent` gaining its sixth case, `SessionId` resolution order (compiles, human gate) — **APPROVED 2026-09-18**
- [x] Step 2 — test oracle: 24 scenarios + 6 properties (`outcome-classification-is-total-and-conservative`, `post-tool-observation-never-blocks`, `refusal-budget-is-bounded-and-nonzero`, `session-identity-encoding-is-injective`, `unreadable-state-allows`, `envelope-conforms-to-contract`) + 4 compile-negative stubs; ORACLE POLARITY run (human gate) — **APPROVED 2026-09-18**
- [x] Step 3 — implementation: the post-tool observation event and its ambient record writer; `HarnessPayloadReader` reading the input channel at most once in the top-level process; `GateStateDirReader`; the pre-execution and completion tiers wired to it, failing open with a stated reason; the bounded refusal budget; the installation probe; the envelope's harness event names; the escape hatch under both names with the deprecation notice
- [x] Ring 0 — clean; the sixth `GateEvent` case is handled in every existing match (forcing function held)
- [x] Ring 1 — lint clean; dangerous-pattern scan (ported tool) — `danger-scan.sh.predecessor.bak 5cebe3e0 --also <new files>`: OK
- [x] Ring 2 — `dependencyLint` clean; `NoIOInProbatioCore` scalafix rule proves no file I/O or environment read in the gate decision functions
- [x] Ring 3 — property + scenario suites green (`probatio-cli` 595/595, `probatio-core` 584/584); `probatioOracleDiff` PROCEED — all 17 bats files identical incl. `hook-tiers` 0=0, `gate-payload` 0=0, `ambient-capture-wiring` 1=1, `ambient-evidence-capture` 0=0, `oracle-ordering-lock` 7=7, `human-grant-lock` 4=4 (equal residual failures are pre-existing suite staleness — verified against `gate.sh.predecessor.bak` directly)
- [x] Ring 4 — `gate-hookjson-contract.jq` conforms for every event variant, including the sixth (real `jq -e -f` exec, all six events × both formats)
- [x] Ring 8 — fresh-context adversarial review (`ring8-adversarial-review.md`): three rounds — 13 findings remediated, one MAJOR re-flagged (`ownedFileCheck` on non-production paths) remediated + focused re-verify VERIFIED-FIXED; the built artifact driven as a hook (native image rebuilt; end-to-end exit-code parity spot-checked)
- [x] Ring 5 — PASS A (core) 95.65% covered-code, 1 equivalent survivor; PASS B (cli) after 4 remediation rounds: 83.68% covered / **91.91% in-diff covered** (500/544; ≈94% counting verified phantoms + post-run kills) — exceeds the 90% bar
- [x] Ring 6 — `GateKernel` (refusal budget + outcome classification) verified: **401/401 VCs valid, 0 invalid, 0 unknown** via `sbt -J-Xmx6g 'set probatio-verified/stainlessEnabled := true' 'probatio-verified/compile'`; first run stalled on the `count`/`indexWhere` postcondition (documented no-per-VC-timeout trap) — rewritten with `markAtLength`/`markAtCount`/`firstTrueInRange`/`markAtFti` Unit-lemmas; `GateBridgeSpec` 2/2 green
- [x] Concept-delta check + inventory update (5 added, `SessionId` and `GateEvent` modified) + checkpoint — **VALIDATED — human checkpoint approval 2026-09-20** (re-verify on `112fc27`: compile clean, 169 focused tests green covering every touched file, `probatioOracleDiff` PROCEED — no file worse across all 17 bats files, Stainless 401/401 valid)

## 9. native-gate-delivery — COMPLETE

- [x] Prerequisite — install a GraalVM toolchain on the target host and record its version (recorded: coursier `graalvm-java17@22.3.1`, GraalVM CE 22.3.1 / Java 17.0.6 — the recorded build toolchain)
- [x] Step 1 — typed contract: `LatencyMeasurement`, a budget-verdict function that requires a measurement, `ShimGenerator.generateShim` taking a resolution result rather than a path, delegating tasks taking an argument list (compiles, human gate — APPROVED)
- [x] Step 2 — test oracle: 15 scenarios + 5 properties (`budget-verdict-requires-a-measurement`, `per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform`, `exactly-one-warning-on-fallback`, `shim-target-equals-resolution-and-is-repeatable`, `release-complete-iff-every-named-artifact-present-and-matching`) + 3 compile-negative stubs; ORACLE POLARITY run (human gate — APPROVED)
- [x] Step 3 — implementation: `sbt "probatio-cli/nativeImage"` produces the artifact (rebuilt, 29.3s); shim generation binds to the resolution result; `probatioInstall` verifies a present binary against `probatioExpectedSha256` and writes the launcher bound to `probatioAssemblyJar`; each delegating task passes its tool's arguments; `ReleaseValidator` is wired to the release step (`ReleaseCheck` runMain gate in `release-probatio.yml`)
- [x] Step 3b — warm-start latency measured and recorded: `gate --event prompt-submit`, 100 warm runs on linux-x86_64. First run exposed a defect (median 2508.846 ms — per-row `git diff` subprocess fan-out in `forgivePredicate`); fixed by per-baseline batching (parity verified 62/62 pairs). **Re-measured median 129.412 ms — verdict `Met`**, recorded in `evidence-ledger.jsonl` and `implementation-progress.md`
- [x] Ring 0 — `sbt "probatio-cli/compile" "sbt-probatio/compile"` clean
- [x] Ring 1 — lint clean (spec-lint 0 FAIL, 34 WARN); dangerous-pattern scan clean on changed files; shellcheck clean on the launcher and shims
- [x] Ring 2 — `probatioDependencyLint` clean across all four modules; `sbt-probatio` still links no `probatio-core` code
- [x] Ring 3 — property + scenario suites green (cli 651/651, plugin 66/66); `SubprocessConformanceSpec` passes against the **native** artifact (7/7); `sbt probatioOracleDiff` → **PROCEED — no file is worse** (17/17 bats files at parity or better)
- [x] Ring 8 — fresh-context adversarial review: fictional `/usr/local/bin` shim target fixed (shim binds to the real installed `File`; `resolveForShim` runs before install side-effects and its warnings are emitted); `forgivePredicate` parity hardened (`--no-renames`, `-z`, pathspec fallback); `ReleaseManifestIO` verifies sidecar digests against file bytes + closes `Files.list`; `ReleaseCheck` provenance honest; uninstall removes the launcher; `runDelegatingTask` closes writer/source on spawn failure; unquotable shim targets reported as `Left`
- [x] Ring 5 — stryker pass A1 `ProbatioPlugin` **96%** (StringLiteral excluded — the sbt key macro rejects mutated descriptions; 1 documented equivalent survivor in `stripTag`); pass A2 `ShimGenerator`+`InstallResolver` **100%** (27/27 testable killed); pass B cli (`BudgetVerdict`, `LatencyMeasurement`, `ReleaseCheck`, `ReleaseManifestIO`, `ReleaseValidator`, `SubcommandWiring`) **95.92%** / 96.91% covered — all 8 undetected in pre-existing `SubcommandWiring` adapter code outside the spec-9 diff + 1 equivalent `Option[Byte]` mutant
- [x] Concept-delta check + inventory update (registry-check OK; `native-gate-delivery-after.md` snapshot generated — delta is `LatencyMeasurement`/`BudgetVerdict`/`LatencyBudget` + test generators, matching the declared delta)
- [x] Checkpoint — **VALIDATED — human checkpoint approval 2026-09-20**

## Change exit criterion

- [x] `sbt probatioOracleDiff` reports **no bats file worse than the predecessor control** — the deficit measured 2026-08-29 (122 ported failures against 20 predecessor failures, 102 tests) is zero: **VERDICT PROCEED** 2026-09-20 on the final implementation state (`complete=true hasRegression=false`; all 17 bats files at parity or better)
- [x] Every predecessor script under `openspec/schemas/verified-scala3/` that the cutover replaced remains on disk as the revert target until this criterion has held green across a full change cycle — verified on disk (`scanner/*.sh`, `*.predecessor.bak`); the green criterion has now held across the complete change cycle
