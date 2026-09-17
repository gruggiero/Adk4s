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

## 5. chain-state-attribution

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
- [ ] Concept-delta check + inventory update + checkpoint — inventory updated (6 new rows + reshaped annotations); **checkpoint PENDING N3 human sign-off**

## 6. danger-reconcile-engines

- [ ] Step 1 — typed contract: `DangerPattern` (8 cases), `DangerHit`, `DangerReport` with smart constructor, `Corroboration` (5 cases), `ReconcileReport`, `DangerScanEngine.scan`, `ReconcileEngine.classify`, `ChangedFilesReader` (compiles, human gate)
- [ ] Step 2 — test oracle: 16 scenarios + 5 properties (`danger-parity-with-predecessor`, `justification-excludes-exactly-its-own-occurrence`, `corroboration-is-total-and-exclusive`, `witness-requires-key-agreement`, `no-discharge-verdict-in-output`) + 4 compile-negative stubs; ORACLE POLARITY run (human gate)
- [ ] Step 3 — implementation: the eight pattern classes with same-line justification handling; production-only scope with caller-named additions; the corroboration classifier; both entrypoints gain the predecessor's invocation surfaces (positional baseline with working-tree default; the full reconcile parameter set)
- [ ] Ring 0 — clean; both new enums exhaustiveness-escalated
- [ ] Ring 1 — lint clean; **from this point the dangerous-pattern scan may run through the ported tool** — run it both ways once and record that they agree
- [ ] Ring 2 — `dependencyLint` clean; compile-negative proves no discharge-verdict type is referenced from the corroboration module
- [ ] Ring 3 — property + scenario suites green; **`ambient-capture-wiring.bats` and `discharge-fidelity.bats` at parity**
- [ ] Ring 8 — fresh-context adversarial review, comparing the pattern set against the predecessor's documented list
- [ ] Ring 5 — retarget to `DangerScanEngine.scala`, `ReconcileEngine.scala`; threshold 90%; read and record the score
- [ ] Ring 6 — `ReconcileKernel` mirror + `ReconcileBridgeSpec`; `sbt -J-Xmx6g ring6`
- [ ] Concept-delta check + inventory update (5 added) + checkpoint

## 7. ledger-checkpoint-parity

- [ ] Step 1 — typed contract: `RingEvidence`, `CheckpointReport` with smart constructor, `ReplayVerdict`, `SessionId` (opaque, lossless encoder), `CheckpointEngine.report`, `LedgerRecord` joined with its optional fields and a single total encoder (compiles, human gate)
- [ ] Step 2 — test oracle: 20 scenarios + 6 properties (`record-round-trips-all-present-fields`, `self-observed-records-are-distinguishable`, `replay-verdict-is-total-and-sound`, `checkpoint-reports-every-requested-ring`, `marker-written-iff-evidenced-and-discharged`, `parity-with-predecessor`) + 5 compile-negative stubs; ORACLE POLARITY run (human gate)
- [ ] Step 3 — implementation: emit the observation fields on the run path; add the replay operation; add the missing three parameters; add the checkpoint's report and task-regeneration operations with per-ring evidence and the same-session review check; gate the presentation marker on evidenced-and-discharged
- [ ] Ring 0 — clean; `ReplayVerdict` exhaustiveness-escalated
- [ ] Ring 1 — lint clean; dangerous-pattern scan (ported tool, now available)
- [ ] Ring 2 — `dependencyLint` clean
- [ ] Ring 3 — property + scenario suites green; **`evidence-ledger.bats`, `evidence-capture.bats`, `checkpoint-from-ledger.bats`, `judgment-ring-*.bats` must stay at zero failures** — these are currently green and must not regress
- [ ] Ring 4 — `ledger-record-contract.jq` conforms; all 32 optional-field combinations round-trip; `tests/fixtures/evidence-ledger-v1.jsonl` reads cleanly; an unrecognised version is undetermined, not skipped
- [ ] Ring 8 — fresh-context adversarial review
- [ ] Ring 5 — retarget to `LedgerRecord.scala`, `Ledger.scala`, `CheckpointEngine.scala`, `SessionId.scala`; threshold 90%; read and record the score
- [ ] Ring 6 — extend `LedgerValidatorKernel` with the marker decision + `CheckpointBridgeSpec`; `sbt -J-Xmx6g ring6`
- [ ] Concept-delta check + inventory update (4 added — including `SessionId`, which spec 8 will modify, not introduce — and `LedgerRecord` modified) + checkpoint

## 8. gate-event-completeness

- [ ] Step 1 — typed contract: `HarnessPayload`, `ToolOutcome` (`Exit` | `Skip`) with `classify` as the only constructor path, `GateStateDir`, `RefusalBudget`, `HeartbeatRecord`, `GateEvent` gaining its sixth case, `SessionId` resolution order (compiles, human gate)
- [ ] Step 2 — test oracle: 24 scenarios + 6 properties (`outcome-classification-is-total-and-conservative`, `post-tool-observation-never-blocks`, `refusal-budget-is-bounded-and-nonzero`, `session-identity-encoding-is-injective`, `unreadable-state-allows`, `envelope-conforms-to-contract`) + 4 compile-negative stubs; ORACLE POLARITY run (human gate)
- [ ] Step 3 — implementation: the post-tool observation event and its ambient record writer; `HarnessPayloadReader` reading the input channel at most once in the top-level process; `GateStateDirReader`; the pre-execution and completion tiers wired to it, failing open with a stated reason; the bounded refusal budget; the installation probe; the envelope's harness event names; the escape hatch under both names with the deprecation notice
- [ ] Ring 0 — clean; **the sixth `GateEvent` case must be handled in every existing match or Ring 0 fails** (the intended forcing function)
- [ ] Ring 1 — lint clean; dangerous-pattern scan (ported tool)
- [ ] Ring 2 — `dependencyLint` clean; compile-negative proves no file I/O or environment read in the gate decision functions
- [ ] Ring 3 — property + scenario suites green; **`hook-tiers.bats`, `gate-payload.bats`, `ambient-capture-wiring.bats`, `ambient-evidence-capture.bats`, `oracle-ordering-lock.bats`, `human-grant-lock.bats` at parity** (baseline: 0/0/0/0/7/4 predecessor failures, 20/21/21/9/9/7 ported)
- [ ] Ring 4 — `gate-hookjson-contract.jq` conforms for every event variant, including the sixth
- [ ] Ring 8 — fresh-context adversarial review, driving the built artifact as a hook
- [ ] Ring 5 — retarget to `ToolOutcome.scala`, `RefusalBudget.scala`, `GateStateDirReader.scala`, `HarnessPayloadReader.scala`, `SubcommandEntrypoints.scala`; threshold 80%; read and record the score
- [ ] Ring 6 — `GateKernel` mirror (refusal budget + outcome classification) + `GateBridgeSpec`; `sbt -J-Xmx6g ring6`
- [ ] Concept-delta check + inventory update (5 added, `SessionId` and `GateEvent` modified) + checkpoint

## 9. native-gate-delivery

- [ ] Prerequisite — install a GraalVM toolchain on the target host and record its version (not currently detected; recorded as a setup task in `capability-check.md`)
- [ ] Step 1 — typed contract: `LatencyMeasurement`, a budget-verdict function that requires a measurement, `ShimGenerator.generateShim` taking a resolution result rather than a path, delegating tasks taking an argument list (compiles, human gate)
- [ ] Step 2 — test oracle: 15 scenarios + 5 properties (`budget-verdict-requires-a-measurement`, `per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform`, `exactly-one-warning-on-fallback`, `shim-target-equals-resolution-and-is-repeatable`, `release-complete-iff-every-named-artifact-present-and-matching`) + 3 compile-negative stubs; ORACLE POLARITY run (human gate)
- [ ] Step 3 — implementation: `sbt "probatio-cli/nativeImage"` produces the artifact; shim generation binds to the resolution result; `probatioInstall` stops assuming a present binary is checksum-valid and stops writing a launcher referencing an unset environment variable; each delegating task passes its tool's arguments; `ReleaseValidator` is wired to the release step
- [ ] Step 3b — **measure the native artifact's warm start-up latency on linux-x86_64 and record the median with its sample count in the evidence record**; if the median exceeds 150 ms the delivery is blocked and the measurement is the recorded reason (`native-packaging` R-N1 calls an unmet budget a hard blocker, not a degradation)
- [ ] Ring 0 — `sbt "probatio-cli/compile" "sbt-probatio/compile"` clean
- [ ] Ring 1 — lint clean; dangerous-pattern scan (ported tool); shellcheck clean on the launcher and shims
- [ ] Ring 2 — `probatioDependencyLint` clean across all four modules; `sbt-probatio` still links no `probatio-core` code
- [ ] Ring 3 — property + scenario suites green; `SubprocessConformanceSpec` passes against the **native** artifact
- [ ] Ring 8 — fresh-context adversarial review
- [ ] Ring 5 — retarget to `ProbatioPlugin.scala`, `ShimGenerator.scala`, `InstallResolver.scala`, `LatencyMeasurement.scala`; threshold 80%; read and record the score
- [ ] Concept-delta check + inventory update (1 added, `ShimGenerator` modified) + checkpoint

## Change exit criterion

- [ ] `sbt probatioOracleDiff` reports **no bats file worse than the predecessor control** — the deficit measured 2026-08-29 (122 ported failures against 20 predecessor failures, 102 tests) is zero
- [ ] Every predecessor script under `openspec/schemas/verified-scala3/` that the cutover replaced remains on disk as the revert target until this criterion has held green across a full change cycle
