# Implementation Progress — repair-probatio-cutover

<!-- Single source of truth for apply-phase progress. tasks.md is regenerated
     from this file at each checkpoint (checkpoint.sh regenerate-tasks) and is
     never hand-maintained in parallel. Section format is parser-contractual:
       - `## Spec N/11: <name>`  — checkpoint.sh report baseline extraction
       - `### N. <name>`         — checkpoint.sh regenerate-tasks pass 1
       - `- **BASELINE SHA**: `…`` — per-spec diff anchor, recorded at Step 0
       - `| Commit | <sha> |`    — completion witness (hex 7-40 = complete)
-->

## Spec 1/11: differential-harness-integrity

### 1. differential-harness-integrity

- **Status**: VALIDATED — human checkpoint approval 2026-09-21; all rings green at commit baseline c59a2aa; R8 fresh-context verified
- **BASELINE SHA**: `d1cf98a3dbb37e13f20d9b6e4fc2044ad3a5e1a9`

### Step Progress
- [x] Prerequisite — five predecessor implementations present; content digests recorded (sha256: chain-state d352951c…, danger-scan da02f5f2…, reconcile 5f923f00…, spec-lint 8f74e86e…, gate 8b1730f5…; live ledger.sh 246ca73d…, checkpoint.sh a8aeb292…)
- [x] Prerequisite — hand-measured predecessor control recorded at `fixtures/predecessor-control.json` (18 failures of 282, per file; measured 2026-09-20 at 817d185; surface identical to baseline d1cf98a — 817d185→d1cf98a delta is openspec docs only)
- [x] Step 0 — Baseline + concept check
  - gate hook installed and firing (post-edit, last_run 2026-09-20T18:13:40Z)
  - inventory snapshot: `inventory-snapshots/differential-harness-integrity-before.md`
  - registry-check: OK — 803 implementation-map tokens, 13 spec concept refs, 5 weak bindings (non-blocking); repaired unparseable `Concepts Used` first cells across all 11 specs to the backtick-identifier convention
  - spec-lint: 11 files, 0 FAIL, 42 WARN (W3 negative-requirement rows; spec 1's forbidden-input scenarios cover its four)
  - danger-scan: clean at baseline (no production .scala changed since HEAD)
  - impact-scan: semantic endpoint unavailable → textual fallback; manual enumeration of `ToolId` sites done (wildcard-import blind spot)
  - defect confirmed: `DifferentialHarness.runSuite` ran both arms in place via `*_OVERRIDE` env vars — no materialisation, both arms resolved the same live shims
  - suite mechanics confirmed: `tests/helpers.bash` resolves `schema_dir` from `BASH_SOURCE` — an arm must be a self-contained tree; `git ls-files`-enumerating tests resolve inside the arm iff the arm is a git worktree
- [x] Step 1 — Typed contract compiled in the real source graph — **APPROVED 2026-09-20**
  - New: `ArmTypes.scala` — `ContentDigest` (opaque, 64-hex), `SeamResolution(seam, implementationDigest, sourcePath)`, `ArmTree` (private ctor; only `ArmTree.materialise` constructs), `ArmDivergence` (`Identical`/`Diverged`)
  - `ToolId` widened +`Ledger`,+`Checkpoint` (both `SeamTypes` and cli `MigrationTypes` duplicate); `swapOrder` → 7 mirroring `SwapOrder`; `overrideEnvVar` → `Option[String]` (None for the two seams that genuinely lack one); new `seamPath`, `predecessorSource`
  - `SwapOrder` +`Checkpoint` before `GateLast` (7 positions)
  - `DifferentialHarness`: `runSuite(ArmTree)`, `divergence`, `compare → Either[Identical, DifferentialResult]`, `exercisedToolPaths`, `unseamedToolPaths`, `checkPredecessorControl → Outcome[Unit]`; `diff`/`verifySuiteDigests` unchanged per anchors
  - `OracleGreenCheck.runDifferential` + `OracleDiffRunner` rewired to materialise→compare; runner reports refusal distinctly and prints not-compared files
  - `CutoverKernel` extended: `ArmVerdict`, `divergenceDecision`, `worseIndices`, `allEqualDigests`, `firstDiffIndex`, `worseFrom`, `countWorse` + 3 laws
  - All exhaustive matches widened (InstallPreciselyOneSpec, SkillDocLintCheck, MigrationTypes decoders); 3 cli generators widened to 7 tools; `HookCutoverSpec` cardinality 6→7
  - Contract files: `DifferentialHarnessIntegrityTypeContract` (pins green; compile-negatives moved to the spec-named artifact in Step 2), `DifferentialHarnessCliTypeContract` (2 tests green)
  - Compile: `probatio-core/Test/compile`, `probatio-cli/Test/compile`, `verified/compile` clean under -Werror
  - Interim RED (expected): `OracleDiffRunner.probatioOracleDiff` fails on `ArmTree.materialise` ??? until Step 3
- [x] Step 2 — Test oracle + ORACLE POLARITY run — **APPROVED 2026-09-20**
  - `DifferentialHarnessSpec` +16 scenario tests + 2 properties (synthetic git-repo fixture; worktree-materialisable; trivial passing bats files — no suite wall-clock in the oracle)
  - New `SwapOrderSpec` (core): seam↔position bijection (enumerated), distinct live seam paths exist, predecessor sources exist
  - New `DifferentialHarnessCompileNegative`: 4 compile-negatives (ArmTree direct ctor, Identical-from-strings, SeamConfiguration-from-string, ToolId.InstallSkills)
  - `CutoverGateSpec` + `comparison-is-monotone-in-failures` property + constructive `genSuiteRunPair` (per-file triples + per-run presence)
  - cli `SwapOrderSpec` corrected to the declared order (`Ledger`, `ChainState` first)
  - `divergence` body → `???` for polarity (real body returns at Step 3; `CutoverKernel.divergenceDecision` + laws remain the proven mirror)
  - `materialise` contract doc refined: arm = the repository tree at baseline via `git worktree`; `root` = materialised schema subtree; `origin` = repo root
  - Control fixture `measuredAtBaseline` recorded as full sha `817d18530cf03cd1484beac31148ad0b9aeb8f39`
  - POLARITY: 18 RED (14 at `???` stubs + 2 retarget-shape assertions demanding the Step-3 bats retarget + 2 properties at `???`); 30 GREEN-BY-DESIGN (SwapOrderSpec 4, compile-negatives 4, CutoverGateSpec 20, pre-existing 4, fixture-shape 1... cli-side SwapOrderSpec 5 + contract 2 additional)
- [x] Step 3 — Implementation — **done 2026-09-21**
  - `ArmTree.materialise`: `git worktree add --detach` at baseline, per-seam resolution recording SHA-256 `ContentDigest`; absent predecessor → `Undetermined` naming the seam
  - `divergence`: positional digest pairing → `Identical` (refusal) / `Diverged` (differing seam pairs); `compare` → `Either[Identical, DifferentialResult]`, requires same origin+baseline
  - `runSuite(arm)`: bats inside `arm.root/tests`, stdin closed (`gate.sh` reads payload via `cat` — open stdin hangs forever), zero-TAP files omitted from results
  - `exercisedToolPaths`/`unseamedToolPaths`: tool-path reference scan; `checkPredecessorControl`: baseline mismatch → `Undetermined`, count mismatch → `Finding` naming all files
  - `workflow-hygiene.bats` D7/D8 retargeted to grep the arm's `workflow/` Scala tree (`install-skills.sh` presence + `sync-skills.sh` absence; `ujson`+`"cwd"` in `HarnessPayloadReader.scala`, no regex/sed extraction)
  - `OracleDiffRunner`: `probatioOracleDiff` + `probatioOracleControl` entry points; `WorktreeCleanup` removes arms' worktree registrations
  - Migration package: 96/98 green — 2 failures are the oracle-immutability git-diff checks, red by design while the retargeted oracle file is uncommitted
- [x] Ring 0 — compile clean under -Werror + exhaustiveness (core, cli, verified)
- [x] Ring 1 — scalafix --check + scalafmtCheck clean on changed files; WartRemover clean; danger-scan clean since baseline
- [x] Ring 2 — `probatioDependencyLint` clean across all four modules
- [x] Ring 3 — 64 focused tests green; **real runs recorded**: `probatioOracleDiff` → arms diverged on all 7 seams, 17-file suite per arm, VERDICT **REVERT** (4 worse files: ambient-capture-wiring +1, chain-state +2, fact-extraction +3, workflow-hygiene +7); `probatioOracleControl` → predecessor arm at `817d185` reproduced the recorded control **exactly** (18/282)
- [x] Ring 5 — Stryker4s **96.67% covered-code** (173 mutants; 4 survivors, all justified equivalents). Files moved test→main for coverage then restored. Debugging trail: `!` exclusion syntax (not `-`), stale `stryker4s-*` target dirs polluted `**` globs, os-lib literal-path macro mutants → `os.RelPath` runtime segments, forked-runner `os.pwd` → `repoRoot` via `git rev-parse`, `stdin=Array.empty` fixed a 44-min `cat` hang in gate.sh
- [x] Ring 6 — Stainless **423/423 VCs valid**; Z3 caught a real lemma bug (`oneDiffDiverges` required `a != c` while index 1 compares `b`/`c` → fixed to `b != c`); inductive postconditions dropped per ring6 playbook, pinned by fixed-size laws + bridge spec
- [x] Ring 8 — adversarial review → `ring8-differential-harness-integrity.md`: **fresh-context: yes** (isolated read-only subagent `devin-subagent-d19f4f3f`, inputs: spec + contract + diff only) — 10 req PASS / 4 PARTIAL at review; findings remediated (ArmTree sealed to final class; ported resolution always writes canonical shim; control parse → Undetermined; worktree leak fixed; MigrationState decoder rejects unknown tools; generators cover all 126 mixed subsets; bridge properties added for divergenceDecision + worseIndices; spec prose corrected); post-remediation re-run 64 core + 9 cli tests green
- [x] Concept-delta check + inventory update + checkpoint — strangler concept updated (7-position order + arm types); `concept-inventory.md` updated (ToolId widened, DifferentialHarness signature repaired, SwapOrder +Checkpoint, 4 new concepts registered); chain-state: 5/5 spec-1 requirements discharged; checkpoint report regenerated (R8 GREEN — fresh-context verified)

| Commit | `c59a2aa` |

---

## Spec 2/11: chain-state-undetermined-fidelity

### 2. chain-state-undetermined-fidelity

- **Status**: VALIDATED — human checkpoint approved (R8 freshness attested: review ran as isolated subagent `3d744b78` on spec + contract + `git diff 523ceb8b` only)
- **BASELINE SHA**: `523ceb8b362200438484123da753602817367104`

### Step Progress
- [x] Step 0 — Baseline + concept check
  - gate hook installed and firing (spec 1 evidence); tree has only pre-existing untracked docs + this spec's inventory snapshot — no tracked modifications, baseline diffs are unaffected
  - inventory snapshot: `inventory-snapshots/chain-state-undetermined-fidelity-before.md` (9 opaque, 129 sealed, 476 case classes, 18 service traits, 62 Smithy, 413 generators)
  - registry-check: OK — 803 implementation-map tokens, 13 spec concept refs, 5 weak bindings (unrelated adk4s concepts)
  - spec-lint: 0 FAIL, 42 WARN (spec 2's three W3 rows are covered by its adversarial scenarios)
  - danger-scan: clean at baseline (no production .scala changed since HEAD)
  - impact-scan: semantic endpoint unavailable → textual fallback; `ChainStateReport`/`compute`/`ChainStateUndetermined` call sites enumerated (core prod, cli prod incl. `RepositoryFactsReader`, ~8 test files, kernel bridge)
  - proof-obligation table complete: every requirement/scenario/property/compile-negative maps to an enforcement + artifact; no MUST-CONFIRM items
  - concept verified: `strangler-migration-protocol` (arm types landed in spec 1; spec 2 relies on the swapped-seam position only)
  - KEY DEFECT FINDING: the port runs `SpecLintEngine.lint` **in-process** and never consults `SPEC_LINT_OVERRIDE` — the "pre-pass cannot run" boundary does not exist. The adapter must spawn the resolved pre-pass (`SPEC_LINT_OVERRIDE` else `<repo>/openspec/schemas/verified-scala3/scanner/spec-lint.sh`) as the execution-boundary witness and classify termination per the predecessor rule (absent / not executable / exit ∉ {0,1} / no recognised completion marker → `DidNotRun`); the in-process per-spec `Outcome[LintReport]` results remain the fold's data. Marker check is mode-aware: graph mode → stdout parses as JSON array (`2>/dev/null`); degraded → stdout+stderr carries `spec-lint: <n> spec file(s)` with `n ==` enumerated count (`2>&1`).
- [x] Step 1 — Typed contract compiled in the real source graph — **APPROVED**
  - New `core/PrePassOutcome.scala`: `enum PrePassOutcome { Completed(lints: Map[String, Outcome[LintReport]]) | DidNotRun(reason: UndeterminedReason) }` + `isCompleted`/`didNotRun` probes; `opaque type UndeterminedReason = String` — no public `apply` (`UndeterminedReason("")` does not compile), `of: String => Either[String, UndeterminedReason]`, `stated` (total route; empty → `unclassifiable` = "the input under inspection"), `.text`, `ReadWriter` (wire read rejects empty)
  - `ChainState.compute` gains `prePass: PrePassOutcome` (replaces the bare lint map): `DidNotRun` → `Left(ChainStateUndetermined)` carrying the stated reason — ledger/reqs never consulted, no report constructed; `Completed(lints)` → `computeCompleted` (existing fold, unchanged semantics)
  - `ChainStateReport.from(prePass: PrePassOutcome.Completed, …)` is the verdict-path factory — `DidNotRun` does not satisfy the parameter type; `fromCounts` narrowed to `private[probatio]` (wire reconstruction + in-package tests only)
  - `ChainStateUndetermined.reason: UndeterminedReason` — a reason naming nothing is unconstructible; no count fields exist
  - `ChainStateInputs.lints` → `prePass: PrePassOutcome`; `emitUndetermined` takes `UndeterminedReason`; adapter `Left` sites convert via `stated`
  - Contract files: `ChainStateUndeterminedFidelityTypeContract` (core, 4 green pins+evals), `ChainStateUndeterminedFidelityCliTypeContract` (cli, pins `ChainStateInputs.prePass`); `ChainStateAttributionTypeContract` pins updated to the new signatures
  - Compile: `probatio-core/Test/compile`, `probatio-cli/Test/compile` clean under -Werror; sanity: ChainStateSpec 40 + both contracts green
  - NOT YET DONE (Step 3): the adapter's pre-pass subprocess probe (SPEC_LINT_OVERRIDE / spec-lint.sh) and its termination classification — `prepareInputs` still produces `Completed` unconditionally
- [x] Step 2 — Test oracle: 9 scenarios + 3 properties + 3 compile-negatives — **APPROVED**
  - `ChainStateSpec` +6 scenarios + property `no counts without a completed pre-pass` (constructive `genVerdictKernelInput`: (prePass, ledger, requirements) triples; populated inputs prove the DidNotRun short-circuit ignores report-producing data)
  - New `ChainStateCompileNegative` (core): 3 probes — `ChainStateReport.from(DidNotRun,…)`, count-bearing `ChainStateUndetermined`, `UndeterminedReason("")`
  - New `ChainStateUndeterminedFidelitySpec` (cli): 12 boundary scenarios driving `ChainStateCmd` through `SPEC_LINT_OVERRIDE` stub executables (absent / non-executable / exit-42 / no-marker / wrong-count / claims-no-specs / graph-non-JSON) + property `exit status is total and disjoint` (constructive (StubKind, LedgerKind, BaseKind) triples, expected status derived from ground truth)
  - `ChainStateParitySpec` + property `undetermined boundary agrees with the predecessor`: 40-case boundary corpus (2 spec shapes × 5 stub behaviours × 4 ledger states), predecessor executed as the model per case
  - Ledger-row baseline fix: rows carry `fullBaseline` (rev-parsed `00d3de1aa4…`) — the predecessor's `ledger.sh` stores resolved SHAs, and `resolveSha` makes `resolvedBaseline` the full SHA
  - POLARITY: 10 RED (9 cli boundary scenarios+property + parity property — the port ignores `SPEC_LINT_OVERRIDE` and measures in-process); 14 GREEN-BY-DESIGN (6 core scenarios + core property + 3 compile-negatives + 4 cli scenarios whose contract the Step-1 boundary already satisfies); 0 broken/vacuous
- [x] Step 3 — Implementation — **done 2026-09-21**
  - `GateCmd.scannerTool`/`GateCmd.runScanner` widened `private` → `private[cli]` (same seam convention as `SpecLintCmd.repoRoot`/`findSpecs` already shared this way)
  - `ChainStateCmd.runPrePassProbe`: resolves `SPEC_LINT_OVERRIDE` else `<repo>/openspec/schemas/verified-scala3/scanner/spec-lint.sh`; absent → `DidNotRun` naming the path; non-executable → `DidNotRun` naming the path; unlaunchable → `DidNotRun`; exit ∉ {0,1} → `DidNotRun`; graph mode → `--format json`, stdout must parse as a JSON array; degraded → `spec-lint: N spec file(s)` with N == enumerated count, or the legitimate-zero message only when enumeration is empty; else `DidNotRun` "no recognised completion message"
  - `prepareInputs`: `runPrePassProbe` runs BEFORE the in-process lint map is built; `Left` → `PrePassOutcome.DidNotRun(stated(reason))`, `Right` → `Completed(lints)` — a did-not-run carries no lint data by type
  - Oracle: all 10 RED turned green (13/13 fidelity suite, parity property green on all 40 boundary cases); full cli suite 668/668
  - Fixture corrections during verification (generator-side, not assertion weakening): matching ledger rows carry `fullBaseline` (rev-parsed SHA — `resolveSha` resolves `--baseline` to the full SHA); `Stale` uses `ffffffff…` — valid hex, not a repo object, so the row never qualifies nor gets forgiven
- [x] Ring 0 — compile clean under -Werror (core, cli, verified); `prePass` is exhaustiveness-escalated by the enum match
- [x] Ring 1 — scalafix --check clean on changed files (3 pre-existing violations in untouched baseline files recorded, not fixed: `ReleaseCheck.scala` NoSysEnv, `ForgiveUnchangedSpec` + `ReleaseManifestIOSpec` import-order/finally); scalafmt clean after targeted format of `ChainState.scala`/`PrePassOutcome.scala`; danger-scan clean — probe classification branches justified with same-line `danger-scan:allow` (`ujson.Arr` type-test, marker-absent catch-all, `NonFatal` catch); kernel "unreachable-obligation boundary" domain-term comments justified
- [x] Ring 2 — `probatioDependencyLint` clean: probatio-core, probatio-cli, sbt-probatio, probatio-verified; `ChainState`/`computeCompleted` remain free of file I/O + subprocesses
- [x] Ring 3 — focused suites green (ChainStateSpec 51, ChainStateUndeterminedFidelitySpec 13, ChainStateParitySpec 2); full cli suite **668/668**; acceptance `chain-state.bats` **6/24 failures = exact recorded predecessor control** (tests 3,5,8,9,16,17 — `discharged:0` symptom is deterministic `git diff` on `correctness-invariant.bats` vs baseline `00d3de1`, artifact changed since baseline → rows legitimately don't qualify); named regressions tests **11+23 PASS** (the +2 over control); real native image rebuilt (`workflow/cli/target/native-image/probatio`) — the stale Sep-20 image shadowed the jar
- [x] Ring 4 — correctness-report contract conforms for both emitted shapes, validated against `scanner/chain-state-report-contract.jq` with real binary output: measured report (all clauses) + undetermined report (null counts, stated reason)
- [x] Ring 5 — Stryker4s **94.8% covered-code** (478 mutants, 297 NoCoverage concentrated in `RepositoryFacts.scala` wire-codec regions outside spec-2 coverage; spec-1 convention = covered-code score). Probe extracted to `ChainStatePrePass.scala` for mutable targeting (file-per-responsibility convention). **4 surgical kills added** to ChainStateSpec: negative-`discharged` + short-`unresolved` + smuggled-`Unbound` + over-counted-`Unresolved` fixtures that make each `fromCounts` clause the UNIQUE decider. **9 survivors, all justified equivalents**: `discharged>resolved`/`resolved>bound`/`bound>total` (a violation forces a negative count-difference always caught by a downstream reason-count clause — entries carry ≥1 reason); 6 diagnostic `StringLiteral` message texts (wire/validation error strings, same class as spec-1's justified survivors)
- [x] Ring 6 — Stainless **431/431 VCs valid** (+8 over spec 1: `computeOutcome` boundary, `didNotRunIgnoresPopulatedEvidence`, `didNotRunCarriesStatedReason`, `completedOutcomeMatchesCompute`, `derivedCountsMonotone`); kernel models `PrePassOutcome`{`PrePassCompleted(lintSuccess)`,`PrePassDidNotRun(reason: BigInt)`}; bridge `VerifiedKernelBridgeSpec` **15/15** (DidNotRun ignores populated evidence, reason preservation, completed delegation, bounded monotone counts)
- [x] Ring 8 — adversarial review → `ring8-chain-state-undetermined-fidelity.md`: **fresh-context: yes** (isolated read-only subagent `3d744b78`, inputs: spec + contract + `git diff 523ceb8b` only) — **34 PASS / 1 PARTIAL / 0 FAIL at review**; the PARTIAL (parity-property generator narrower than declared: shapes {0,1} of 0–3, no findings-run stub) **remediated**: `BoundaryStub.FindingsRun` (exit 1 + valid marker) + `three-req` shape added — corpus now 72 cases, parity property re-run green. **35 PASS / 0 PARTIAL / 0 FAIL after remediation**
- [x] Concept-delta + inventory update — `concept-inventory.md`: `PrePassOutcome`, `UndeterminedReason`, `ChainStatePrePass`, `ChainStateKernel.computeOutcome` registered under `spec:repair-probatio-cutover/chain-state-undetermined-fidelity`; `ChainStateReport`/`ChainStateUndetermined`/`ChainState` rows annotated in place with re-shape provenance
- [x] Checkpoint — implementation committed `9c17261`; 16 PO-exact evidence rows re-recorded at the commit baseline (discharge requires `r.obligation ==` the spec's PO-table cell text AND `r.change == --change`); chain-state report: 46 total / 46 bound / 46 resolved / **8 discharged** (spec-1's 5 + spec-2's 3; remaining 38 belong to specs 3–11); `checkpoint.sh report` → all rings green, R8 `unverified-session` pending human attestation (same as spec 1)

| Commit | `9c17261` |

---

## Spec 3/11: completion-witness-refusal

### 3. completion-witness-refusal

- **Status**: in-progress — all rings green (0,1,2,3,5,6,8); concept-delta + inventory done; checkpoint pending
- **BASELINE SHA**: `f3fe75be44876852c1fbdfbc819cd46720980923`

### Step Progress
- [x] Step 0 — Baseline + concept check
  - gate hook installed and firing (`installed:true`, last_run 2026-09-21T17:49:29Z, event post-edit)
  - working tree clean of tracked modifications (only pre-existing untracked docs); baseline `f3fe75b` = spec-2 checkpoint commit
  - inventory snapshot: `inventory-snapshots/completion-witness-refusal-before.md` (10 opaque, 131 sealed, 478 case classes, 18 service traits, 62 Smithy, 418 generators)
  - registry-check: OK — 803 implementation-map tokens, 13 spec concept refs, 5 weak bindings (unrelated adk4s concepts)
  - spec-lint: 0 FAIL, 42 WARN; spec 3's three W3 rows are covered by its adversarial scenarios
  - danger-scan: clean at baseline (no production .scala changed since HEAD)
  - impact-scan: not applicable — the spec widens NO public type (`WitnessVerdict` is a new closed enum; `BlockReason` gains no variant — verified against `BlockReason.scala`, which already carries `Uncorroborated`)
  - concept verified: `strangler-migration-protocol` — spec 3 changes no action; the completion tier is the last seam (`GateLast`), already swapped
  - proof-obligation table complete: 17 rows cover all 3 requirements, 9 scenarios, 4 properties, 2 compile-negatives, the Ring-6 contract, and the exit criterion; no MUST-CONFIRM items
  - DEFECT CONFIRMED (empirical): `ambient-capture-wiring.bats` test 29 "completion is refused when a green row has no witness" fails — expected exit 1, got 0. Mechanism: the ported tier resolves `reconcile.sh` via `scannerTool(ctx.repo, "RECONCILE_OVERRIDE", …)` — under the repo UNDER TEST. The test's `mk_repo` fixture never installs `reconcile.sh` there, so `toolExists` fails and the corroboration check is silently skipped. The predecessor resolves `$SCANNER/reconcile.sh` relative to the GATE's own install dir (the arm's schema tree), where it always exists. Spec's remedy: read the evidence record in-core (`Ledger.readValidated` + `ReconcileEngine.classify` — both already exist and are the verified mirror of reconcile's jq) and decide via a pure predicate — no subprocess to mis-resolve.
  - SPEC-INTERNAL DISCREPANCY (needs human decision before Step 1): the spec scopes the refusal to rows "at the current baseline" in FIVE consistent places (requirement Given, refusal-iff property + generator edge case, fail-open input list, Ring-6 contract `r.baseline == baseline`). Measured predecessor: reconcile is invoked with NO `--baseline`, so `$baseline == ""` admits ALL baselines — a stale-baseline uncorroborated green REFUSES (verified live: exit 1 on a `baseline:"0000000"` claim). The spec's own parity property (exit-status equality over `genCompletionFixture`, which draws `baseline ∈ {current, other}`) then cannot hold together with the refusal-iff property: one of them must fail under any implementation. Recommendation: predecessor-exact semantics (corroboration judged at each claim's OWN baseline; HEAD is not a scope filter), amending the five places — current-baseline scoping would make the refusal unreachable after the checkpoint commit, i.e. dead at exactly the moment the tier runs.
- [x] Step 1 — Typed contract compiled in the real source graph — **APPROVED 2026-09-22**
  - DECISION RECORDED: spec-literal current-baseline scope (human choice, 2026-09-22). Spec amended: the parity property now declares the stale-baseline divergence explicitly and requires the generator to EXERCISE it (asserted shape, not excluded) — `spec.md` lines ~208-246; spec-lint re-run: 0 FAIL, 42 WARN (unchanged). PO table compile-negative artifacts pointed at the spec-named `CompletionWitnessRefusalCompileNegative` (per-spec convention from specs 1–2).
  - `WitnessVerdict.scala` (new, core): `enum WitnessVerdict` — `Witnessed` / `Unwitnessed(row: ClaimVerdict)` / `Undeterminable(reason: UndeterminedReason)` + `namedRow` probe; `enum CompletionDecision` — `Allow` / `AllowUndetermined(reason)` / `Refuse(unwitnessed: WitnessVerdict.Unwitnessed)` + `isRefusal`/`namedRow` probes (the Ring-6 contract's members)
  - `GateDecisions` gains two pure signatures (`???` bodies until Step 3): `corroborationVerdict(report: ReconcileReport, baseline: String): WitnessVerdict` — current-baseline scoped — and `decideCompletion(verdict: WitnessVerdict, budget: RefusalBudget): CompletionDecision` — `Undeterminable` abstains WITHOUT consuming the budget; `Unwitnessed` refuses only while unspent
  - `ReconcileReport` gains `uncorroborated: List[ClaimVerdict]` — testimony + contradicted in RECORD order (the verdict's warrant set; typed over `Corroboration`, no verdict-string compare)
  - `GateKernel` extended (probatio-verified): `EvidenceRow(isGreen, baseline: BigInt, corroborated)`, `CompletionDecision` = `CompletionAllow`/`CompletionRefuse(rowIndex)`, `decideCompletion(rows, baseline, priorRefusals)` with the spec's three postcondition clauses (`isRefusal iff (priorRefusals == 0 && warranted)`; refusal names a justifying in-range row; spent budget never refuses). `AllowUndetermined` unmodelled — the kernel quantifies over already-read rows only. VC discharge is Ring-6's job (may need a `firstUncorroborated` soundness lemma, same idiom as `markAtFti`)
  - Contract files: `CompletionWitnessRefusalTypeContract` (16 signature pins incl. the kernel's, 2 evaluation tests green), `CompletionWitnessRefusalCompileNegative` (2 compile-errors tests green — `WitnessVerdict.Unknown`, `WitnessVerdict.Unwitnessed()`)
  - Compile evidence: `sbt probatio-core/Test/compile` + `probatio-verified/compile` + `probatio-cli/compile` — clean under -Werror (63s); focused test run 4/4 green
- [x] Step 2 — Test oracle: scenarios + 3 properties + 2 compile-negatives — **APPROVED 2026-09-22** (oracle written, polarity confirmed red on `???`/ported adapter)
  - Core oracle appended to `GateDecisionSpec`: 5 scenario tests + 3 properties (`refusal-iff-an-uncorroborated-green-result-exists` — 200 tests, cover labels for empty-record / current-baseline-warrant / stale-only-uncorroborated / contradicted-warrant; `unreadable-state-never-refuses`; `refusal-budget-is-bounded-and-nonzero`). Generators in `CompletionWitnessRefusalFixtures` — constructive `RowPlan` (green/red × corroborated × current/stale baseline × contradicted × self-observed), `genEvidenceRecord` (0–8 rows, spec's declared generator), `genReason`/`genBudget`/`genAttemptSequence`.
  - CLI oracle: `GateEventSpec` gains 8 completion scenarios over REAL ledger fixtures (validator-shaped rows written to `evidence-ledger.jsonl`, session markers, clean chain-state stub) — replaces the prior reconcile-stub test which asserted argv to a subprocess the spec removes; `GateStateDirSpec` gains the unavailable-state-area scenario.
  - Parity property in `GateBannerCompatSpec`: `parity-with-predecessor-on-the-completion-tier` — generated fixtures evaluated by BOTH the ported `GateCmd.run` (in-process) and `gate.sh.predecessor.bak` (subprocess, `RECONCILE_OVERRIDE`→`reconcile.sh.predecessor.bak`, `CHAIN_STATE_OVERRIDE`→clean stub, `VERIFIED_SCALA3_SESSION_ID`). Asserts `ported == model` OR the declared divergence (stale-only warrant: model refuses, port allows), plus `!(ported==1 && model==0)` (port never refuses where the model allowed). The predecessor subprocess needs stdin=/dev/null — the gate reads hook JSON from stdin and an inherited open pipe blocks it (ProcessBuilder Redirect, not scala.sys.process).
  - POLARITY RUN (red/green-by-design): core — all 8 new tests RED on `NotImplementedError` at `corroborationVerdict`/`decideCompletion` (`???` placeholders), 27 pre-existing green. CLI — 5 RED (refusal scenarios + the unreadable-record stated-reason trace assertion); green-by-design arms pass: witnessed record allows, red row exempt, stale-baseline allows, unparseable record does not refuse (the port allows everything today — the defect), GateStateDirSpec 14/14 green incl. the no-state-dir scenario. Parity property RED: falsified at `CompletionFixturePlan(List(CompletionRowPlan(0,true,false)),false)` — ported=0, model=1 (the defect, current-baseline uncorroborated green); 16 prior cases agreed.
  - Compile evidence: `probatio-core/Test/compile` + `probatio-cli/Test/compile` clean under -Werror; wartremover (`tail`→`drop(1)`), init-checker (fixture `val`s hoisted above test registrations), exhaustivity fixes applied.
- [x] Step 3 — Implementation — **done 2026-09-22**
  - `GateDecisions.corroborationVerdict` implemented: `Undeterminable` passes through the adapter-constructed verdict; `report.uncorroborated` filtered to `row.baseline == baseline` (current-baseline scope — the recorded decision); first hit → `Unwitnessed(row)`, else `Witnessed`. `decideCompletion`: `Undeterminable` → `AllowUndetermined(reason)` WITHOUT consuming the budget; `Unwitnessed` → `Refuse` only while `RefusalBudget` unspent, else `Allow`.
  - `ReconcileReport.uncorroborated` = `testimony ++ contradicted` in record order (typed over `Corroboration`).
  - `SubcommandEntrypoints` completion tier rewired: the `reconcile.sh` subprocess resolution is GONE. `CompletionScan` now carries `verdict: WitnessVerdict` + `detail: String` (the report's own rendered details). `completionScanChange` reads `evidence-ledger.jsonl` via `SubcommandWiring.readLedgerFile` + `Ledger.readValidated` + `ReconcileEngine.classify` in-core; unreadable/absent/invalid record → `Undeterminable` (fail-open with stated reason, trace names the record). `gateBaseline` = long `rev-parse HEAD` (chain-state); `ledgerBaseline` = `rev-parse --short HEAD` resolved ONLY when an active change has a ledger (ledger rows record the short SHA; ledger-less fixtures pay no extra subprocess). `runCompletion` decides via `GateDecisions.decideCompletion(scan.verdict, RefusalBudget.full)`; ordering preserved: undetermined → uncorroborated → unresolved → allow; refusal renders `scan.detail` and writes the per-session `completion-refused-$encoded` marker.
  - `GateKernel.decideCompletion` implemented (Stainless mirror): first in-range uncorroborated green row warrants; `priorRefusals == 0` gates `CompletionRefuse(rowIndex)`.
  - Fixes during bring-up: `SessionId.encoded` verified == predecessor `jq @base64 | tr '+/=' '-_.'`; parity-property bug (marker deleted between ported/predecessor runs even when the fixture SEEDED a prior refusal — now deleted only when `priorRefusal == false`); pre-existing `refusal-budget` property timeout fixed by lazy `ledgerBaseline` (no `git rev-parse` on ledger-less repos); obsolete default-scanners test migrated from reconcile-stub argv assertion to the real-ledger contract; generator coverage tuned (explicit `CompletionRowPlan` shape weights — current-warrant 22 / stale-uncorroborated 25 / current-corroborated 18 / stale-corroborated 8 / red-current 12 / red-stale 15).
  - Evidence: `GateDecisionSpec` 35/35, `GateEventSpec` 123/123, `GateStateDirSpec` 14/14, `GateBannerCompatSpec` 9/9 (parity property green on 100 generated fixtures incl. asserted divergence). `probatio-cli/nativeImage` rebuilt (the shim's `bin/probatio` execs the ORIGIN repo's binary — was stale from Sep 21, pre-spec-3). Real bats suite `ambient-capture-wiring.bats` **32/32 ok** — incl. test 29 (the defect: refused, exit 1, names the row) and test 32 (baseline-matched witness).
- [x] Ring 0 — compile clean under -Werror (core, cli, verified) incl. the new `WitnessVerdict`/`CompletionDecision` enums — exhaustive matches escalate
- [x] Ring 1 — scalafix clean on changed files (1 pre-existing violation in untouched baseline file recorded, not fixed: `ReleaseCheck.scala` NoSysEnv — same class spec 2 recorded); scalafmt clean after targeted format of changed files (unrelated baseline-formatting deltas reverted); danger-scan clean — `Option.get` caught and restructured to a real match; verdict-fold/`case _` arms justified with same-line `danger-scan:allow`
- [x] Ring 2 — `probatioDependencyLint` clean: probatio-core, probatio-cli, sbt-probatio, probatio-verified; `GateDecisions`/`WitnessVerdict` remain pure (no file I/O, no subprocesses)
- [x] Ring 3 — focused suites green: `GateDecisionSpec` 35/35 (incl. 200-case refusal-iff property), `GateEventSpec` 123/123, `GateStateDirSpec` 14/14, `GateBannerCompatSpec` 9/9 (parity property green on 100 generated fixtures incl. asserted stale-only divergence). **Real runs recorded**: `probatioOracleDiff` → arms diverged, VERDICT REVERT — worse files now `fact-extraction.bats` (+3), `workflow-hygiene.bats` (+5) only — **`ambient-capture-wiring.bats` REMOVED from the worse list (was +1, the spec-3 defect; chain-state also off since spec 2)**; `probatioOracleControl` → predecessor arm at `817d185` CONTROL REPRODUCED exactly; real `ambient-capture-wiring.bats` **32/32 ok** against rebuilt native image (test 29 refuses, exit 1, names the row; test 32 baseline-matched witness)
- [x] Ring 5 — Stryker4s **100% covered-code** (269 mutants; 52 killed, 0 survived; 206 NoCoverage in `GateDecisions`' pre-existing helpers outside the spec-3 suites — spec-1 convention = covered-code score). All 3 new-code mutants killed: the `==`→`!=` baseline-scope mutant (line 409) and both `budget.exhausted` conditionals (line 432)
- [x] Ring 6 — Stainless **480/480 VCs valid** (+49 over spec 2): `EvidenceRow`, `CompletionDecision`{`CompletionAllow`,`CompletionRefuse(rowIndex)`}, `firstUncorroborated`, `hasUncorroboratedAtBaseline`, and the three discharge lemmas (`fuSound` — `Some(k)` ⇒ in-range ∧ `rows(k-i)` satisfies the warrant; `uncAtIndexImpliesExists` — satisfied predicate ⇒ `exists`; `fuComplete` — `None` ⇒ `!exists`) instantiated in `decideCompletion`'s body; postcondition discharges the spec's three clauses (refusal-iff, named in-range justifying row, spent budget never refuses). The `verified` (adk4s) module shows 9 pre-existing invalids in `PredictorKernel`/`StackKernel` (untouched since Aug 17 — out of scope)
- [x] Ring 8 — adversarial review → `ring8-completion-witness-refusal.md`: **fresh-context: yes** (isolated read-only subagent `408db1c1`, inputs: spec + changed production files only) — 3 PASS / 7 PARTIAL at review; **3 MAJOR remediated** (`"unknown"` baseline sentinel → `Option[String]` + `Undeterminable` naming the baseline resolution; absent record → `Undeterminable` naming the ledger — both were silent clean allows discarding warrants/violating namesInput; all 3 refuse paths discarded `writeRefusal`'s bound → now the tool-call-tier `!writeRefusal ⇒ fail open` idiom); **2 MINOR resolved** (presentation-marker precondition + changes-enumeration silent allow recorded in the spec's new Applicability paragraph — both predecessor-exact). Post-remediation: 146 focused tests green, native image rebuilt, `ambient-capture-wiring.bats` 32/32 re-verified, spec-lint 0 FAIL
- [x] Concept-delta + inventory update — `concept-inventory.md`: spec-3 section appended (`WitnessVerdict`, `CompletionDecision`, `GateKernel.decideCompletion` rows, provenance `spec:repair-probatio-cutover/completion-witness-refusal`); `GateDecisions`, `ReconcileReport`, `GateKernel` rows annotated in place (+`corroborationVerdict`/`decideCompletion`, +`uncorroborated`, +`decideCompletion` mirror — provenance preserved). Behavioural concept delta: none needed — `strangler-migration-protocol` already covers the completion tier as the `GateLast` seam; spec 3 changes no action/synchronization shape (verified at Step 0). registry-check re-run: OK (803 tokens, 13 spec refs, 5 pre-existing weak bindings). spec-lint post-amendment: 0 FAIL, 42 WARN
- [ ] Checkpoint — commit + PO-exact evidence rows + checkpoint report

| Commit | _(pending)_ |

---

## Spec 4/11: gate-event-compatibility

### 4. gate-event-compatibility

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `EventDispatch` Tier/Injection (human gate)
- [ ] Step 2 — Test oracle: 11 scenarios + 4 properties + 2 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,4,5,6,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 5/11: graph-tool-port

### 5. graph-tool-port

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Prerequisite — predecessor traceability tool runnable on this host
- [ ] Step 1 — Typed contract: graph model + five ops + `Subcommand` case (human gate)
- [ ] Step 2 — Test oracle: 15 scenarios + 4 properties + 4 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,4,5,6,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 6/11: feature-freeze-guard-integrity

### 6. feature-freeze-guard-integrity

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `FixtureCorpus`, `CorpusResolution` (human gate)
- [ ] Step 2 — Test oracle: 10 scenarios + 3 properties + 3 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,5,6,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 7/11: install-tool-surface-parity

### 7. install-tool-surface-parity

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `InstallTarget`, `InstallMode`, `PrerequisiteProbe`, `PrerequisiteReport` (human gate)
- [ ] Step 2 — Test oracle: 13 scenarios + 3 properties + 3 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,5,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 8/11: ledger-checkpoint-cutover

### 8. ledger-checkpoint-cutover

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Prerequisite — two predecessor implementations present and readable
- [ ] Step 1 — Typed contract: swap record gains required comparison field (human gate)
- [ ] Step 2 — Test oracle: 12 scenarios + 4 properties + 3 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,4,5,6,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 9/11: workflow-delivery-hygiene

### 9. workflow-delivery-hygiene

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `ShimTargetScope` required parameter (human gate)
- [ ] Step 2 — Test oracle: 11 scenarios + 3 properties + 2 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,5,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 10/11: schema-rename-completion

### 10. schema-rename-completion

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `RenameDeferral` (human gate)
- [ ] Step 2 — Test oracle: 13 scenarios + 3 properties + 2 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,5,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 11/11: unported-tool-register

### 11. unported-tool-register

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `ToolSurfaceClassification`, `UnportedTool`, `PortBlocker` (human gate)
- [ ] Step 2 — Test oracle: 11 scenarios + 3 properties + 3 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,5,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Change exit criterion

- [ ] Repaired comparison reports no acceptance file worse than the genuine predecessor control
- [ ] Predecessor implementations remain on disk as the revert target
- [ ] Feature-freeze guard green, and red on an empty corpus
- [ ] CI job runs the acceptance suite, the differential comparison and every module suite
