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

- **Status**: VALIDATED — human checkpoint approved (R8 freshness attested: review ran as isolated subagent `408db1c1` on spec + changed production files only); all rings green at commit baseline `67416c2`
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
- [x] Checkpoint — implementation committed `67416c2`; 26 evidence rows re-recorded at the commit baseline (9 ring-summary + 17 PO-exact; discharge requires `r.obligation ==` the spec's PO-table cell text AND `r.change == --change`; R8 row carries `--session devin-subagent-408db1c1` per the R8 contract); `hook-tiers.bats` **27/27** run for the two PO rows citing it (first-refusal-issued, second-attempt-not-refused); chain-state report: 46 total / 46 bound / 46 resolved / **11 discharged** (spec-1's 5 + spec-2's 3 + spec-3's 3; remaining 35 belong to specs 4–11; `unmapped_obligations: []`); `checkpoint.sh report` → R0/R1/R2/R3/R5/R6 all green, R8 `unverified-session` pending human attestation (same as specs 1–2)

| Commit | `67416c2` |

---

## Spec 4/11: gate-event-compatibility

### 4. gate-event-compatibility

- **Status**: VALIDATED — human checkpoint approved (R8 freshness attested: review re-ran as isolated subagent `437b30fb` on spec + typed contract + baseline diff only after the human declined to attest `c39873b4`); all rings green at commit baseline `ccbad87` except R3 FAILED honestly recorded (workflow-hygiene.bats +3 residual deferred to owning specs per human decision)
- **BASELINE SHA**: `2950ad10a4a0b63bf1540efb07282781ba1315ac`

### Step Progress
- [x] Step 0 — Baseline + concept check
  - gate hook installed and firing (`installed:true`, last_run 2026-09-22T13:59:55Z, event post-edit)
  - working tree clean of tracked modifications (only pre-existing untracked docs); baseline `2950ad1` = spec-3 VALIDATED marker commit
  - inventory snapshot: `inventory-snapshots/gate-event-compatibility-before.md` (10 opaque, 134 sealed, 481 case classes, 18 service traits, 62 Smithy, 426 generators)
  - registry-check: OK — 803 implementation-map tokens, 13 spec concept refs, 5 weak bindings (unrelated adk4s concepts, same as specs 1–3)
  - spec-lint: 0 FAIL, 42 WARN; spec 4's three W3 rows are covered by its adversarial scenarios (unrecognised-name→error forbidden input ← "arbitrary unrecognised name does not error" + empty-name edge case; silent fallback ← "recognised name produces no unrecognised-name diagnostic"; internal enum name in envelope ← "no envelope carries an internal enum name", all six events × both formats)
  - danger-scan: clean at baseline (no production .scala changed since HEAD)
  - impact-scan: semantic endpoint unavailable → textual fallback. `GateCmd.parseEvent` is private with ONE call site (`SubcommandEntrypoints.scala:188`); the `None → Outcome.Finding` mapping at :196-198 is the defect site. `GateEvent` consumers are all total matches (`eventToken` heartbeat/trace, `GateEvent.harnessName` envelope, `HarnessPayloadReader.consumesPayload`, `runEvent` dispatch) — no public type widened. Test call sites: `GateEventSpec:130-142` ("an event name no adapter sends is rejected" — the OLD contract this spec reverses; rewritten by the oracle) and `LiveFactBannerSpec:891` (compatible — tool-call still Ran(0)). The spec anchor names `eventFromString`; the method is `parseEvent` — same site.
  - DEFECT CONFIRMED (empirical): ported `gate --event user-prompt-submit --repo <openspec-repo>` → stderr `gate: unknown event 'user-prompt-submit'`, **exit 1**. Predecessor on the identical repo → injection tier runs (banner emitted), **exit 0**. Fidelity details measured from the predecessor: heartbeat `event` field carries the RAW supplied name (`"user-prompt-submit"`); trace lines likewise; the hook-json envelope's `hookEventName` maps `session-start→SessionStart`, `prompt-submit→UserPromptSubmit`, `*)→SessionStart`; an unrecognised name does NOT get prompt-submit's grant/refusal-marker side effects (`NEEDS_PAYLOAD=0` — stdin read only when `--repo` is absent, for `.cwd` fallback).
  - MUST-CONFIRM resolved: the shipped adapters (claude.settings.json, devin.hooks.v1.json, pi/verified-scala3-gate.ts) all emit the gate's own six names; the envelope contract records the harness names (`SessionStart`/`UserPromptSubmit`, `PostToolUse` for post-edit/post-bash per README). `user-prompt-submit` appears only in `workflow-hygiene.bats` — the alternate-name representative. No new name is written into the implementation (the fix is a fallback, not an alias table), so no external harness documentation lookup is required.
  - concept verified: `strangler-migration-protocol` — the gate is the last seam swapped (`GateLast`); spec 4 changes no protocol action, only restores predecessor dispatch behaviour at that seam
  - proof-obligation table complete: 17 rows cover all 4 requirements, 9 scenarios, 4 properties, 2 compile-negatives, the Ring-6 contract, and the exit criterion; the MUST-CONFIRM section is resolved above
- [x] Step 1 — Typed contract compiled in the real source graph — **APPROVED 2026-09-22**
  - New `core/EventDispatch.scala`: `enum EventDispatch { Tier(event: GateEvent) | Injection(suppliedName: String) }` + `isTier`/`isInjection` probes; `Injection` requires the supplied name (a fallback that discards what it fell back from is unconstructible). `EventDispatch.classify: String => EventDispatch` — total, single-sourced on `recognisedTable` (the six (token, event) pairs in predecessor dispatch order); `recognisedNames` derived from the same table so the closed name set and the classification cannot drift. No `Option`, no `Either` — a failable parse is unconstructible.
  - `SubcommandEntrypoints.scala` rewired: `parseEvent` → `EventDispatch` (delegates to `EventDispatch.classify`, widened to `private[cli]` for the contract pin); the call site computes `consumesPayload` per dispatch (`Injection` → `false`, matching the predecessor's `NEEDS_PAYLOAD=0` — stdin read only when `--repo` is absent). `GateContext.event: GateEvent` → `dispatch: EventDispatch`; new `dispatchToken` (Tier→canonical token, Injection→supplied name verbatim — predecessor `$EVENT`) drives heartbeat + trace; `emitBanner` takes the dispatch (Tier→`GateEvent.harnessName`, Injection→`SessionStart`, the predecessor's `*)` envelope arm). `runEvent` takes the dispatch; the Injection arm delegates to `runInjection(ctx, env, supplied)` — `???` until Step 3.
  - `DispatchKernel` extended (probatio-verified): `EventDispatchModel`{`EventTier(event)`|`EventInjection(supplied)`}, `recognisedEventNames` (codes 1–6), `classifyEvent` with the spec's three postcondition clauses (totality, recognised⇔tier, injection-carries-name), `classifyEventInjective` + `recognisedEventNamesDistinct` laws (`noDup` helper — stainless `List` has no `distinct`).
  - Contract file: spec-4 section added to `GateEventCompletenessTypeContract` (the PO table's named artifact — 5 signature pins, 2 evaluation tests, 2 compile-negatives green; 9/9).
  - Compile: `probatio-core/Test/compile`, `probatio-cli/Test/compile`, `probatio-verified/compile` clean under -Werror; init-checker required the new pin vals hoisted above test registrations (spec-3 convention).
  - Interim RED (expected): `GateEventSpec` "an event name no adapter sends is rejected" now fails — `runInjection` is `???`; the oracle rewrites this scenario in Step 2.
  - danger-scan: 1 pre-existing catch-all in `DispatchKernel.scala:79` justified in place (non-Sep head keeps tokens verbatim — inside a now-modified file).
- [x] Step 2 — Test oracle: scenarios + 4 properties + 2 compile-negatives — **APPROVED 2026-09-22**
  - `EventDispatchFixtures.scala` (new, cli test): `genEventName` per the spec's constructive three-alphabet union (six recognised names; recorded harness alternates only — `user-prompt-submit`, the five PascalCase harness names from the adapter JSON/envelope contract, the pi extension's `before_agent_start`/`tool_call`/`tool_result`; arbitrary strings 0–40 over a mixed alphabet incl. hyphens + Unicode, with the spec's edge cases: empty, case-variant, whitespace-padded, very long); `genUnrecognisedName` maps recognised draws onto a distinct `unrecognised-` prefix (constructive, not filtered); `eventNameCode` — the Ring-6 name-code abstraction (1–6 by `recognisedNames` position, else 0) shared with `DispatchKernel`.
  - `GateEventSpec` (PO artifact): the superseded "an event name no adapter sends is rejected" rewritten to the spec-4 contract (routes to injection, exits clean, diagnostic names it, banner emits) + 8 new scenario tests covering all 7 GateEventSpec-mapped spec scenarios (each recognised name → its own tier, injective; all six end-to-end with no injection diagnostic; pre-execution name not absorbed; alternate prompt name → injection w/ heartbeat-verbatim fidelity AND no prompt-submit grant side-effects; empty name → injection; name in diagnostic; recognised name → no unrecognised line) + an injection-envelope fidelity test (hook-json → `SessionStart`, the predecessor `*)` arm).
  - Properties (3 in `GateEventSpec`): `event-dispatch-is-total` (totality + injection carries the supplied name), `recognised-names-never-fall-back` (enumerated finite domain — all-tier + injective + the shipped table equals the oracle's closed set), `unrecognised-names-exit-clean` (Ran(0) + diagnostic names the supplied value over `genUnrecognisedName`).
  - `SubprocessConformanceSpec` (PO artifact): `parity-with-predecessor-on-event-dispatch` — ported in-process (Outcome→exit = the binary boundary's own mapping) vs `gate.sh.predecessor.bak` as the reference subprocess; `genEventName` × {workflow repo, no-openspec repo}; compares (exit status, stdout-presence) per the spec's determinism rule — stderr deliberately excluded (the added diagnostic line is the spec-intended divergence); shared gate-state reset between runs (spec-3 marker-reset convention) so fingerprint suppression can't fault the fixture.
  - `GateBridgeSpec` (PO artifact): `bridge-classifyEvent` — shipped `EventDispatch.classify` vs `DispatchKernel.classifyEvent` over `genEventName` under the name-code abstraction; the tier arm re-checks the shipped event's own token so a name→event drift inside the shipped table is caught.
  - Compile-negatives: the two PO-mapped `assertDoesNotCompile` pins already live in `GateEventCompletenessTypeContract` (Step 1 — `Option[EventDispatch] = classify("x")` and `EventDispatch.Injection()`), green-by-design.
  - **ORACLE POLARITY run** (`probatio-cli/testOnly`): GateEventSpec **6 red / 128 green** — every red is `NotImplementedError` at `runInjection` (`SubcommandEntrypoints.scala:479`), the unimplemented Step-3 site: the rewritten supersession test, alternate-prompt, empty-name, named-diagnostic, SessionStart-envelope, `unrecognised-names-exit-clean`. Green-by-design: both recognised-name scenario tests, no-diagnostic-for-recognised, `event-dispatch-is-total`, `recognised-names-never-fall-back`. SubprocessConformanceSpec **1 red / 7 green** — `parity-with-predecessor-on-event-dispatch` errors on the first unrecognised draw (same `???`). GateBridgeSpec **3/3 green** (bridge-classifyEvent green-by-design). GateEventCompletenessTypeContract **9/9** (Step-1 evidence, unchanged).
  - Bats oracle (PO artifacts, unchanged): `workflow-hygiene.bats` tests 5/6/9/10 still invoke `--event user-prompt-submit` against `hooks/gate.sh` → `bin/probatio` (stale image still prints `gate: unknown event` — stays red until the Step-3 rebuild); `gate-payload.bats` carries the two envelope PO rows.
  - danger-scan: clean (3 `danger-scan:allow` justifications on test-assertion catch-all arms, the established convention).
  - Note recorded: `--event` absent stays an error (`--event is required`) — a missing flag is not a supplied name; spec 4's domain is supplied names only. `--event ""` IS a supplied name → injection.
- [x] Step 3 — Implementation
  - `runInjection` implemented: emits the diagnostic `gate: unrecognised event '<supplied>' — running the context-injection tier` on stderr (the spec-added line; the predecessor's fallback is silent), records the fallback in the opt-in trace, then runs `runBanner` — the predecessor's fallthrough IS the banner path (session-start's own tier). Returns `Outcome.Ran(0)` via runBanner's fail-open boundary. Prologue order preserved: the diagnostic fires only when the gate actually runs (post relevance-guard), matching the predecessor's silence on non-openspec repos.
  - Oracle re-run post-implementation: GateEventSpec **134/134**, SubprocessConformanceSpec **8/8** (parity property green — 100 generated names × repo shapes vs the predecessor subprocess, exit + stdout-presence agree), GateBridgeSpec **3/3**. All previously-red tests green; no `???` remains in the dispatch path.
- [x] Rings 0,1,2,3,4,5,6,8
  - [x] Ring 0 — compile clean: `probatio-verified/compile`, `probatio-cli/compile`, `probatio-cli/Test/compile` under -Werror (evidenced across Steps 1–3; post-formatting recompile clean)
  - [x] Ring 1 — scalafix clean on the diff (2 violations in untouched baseline files `ReleaseCheck.scala`/`ReleaseManifestIOSpec.scala` — same pre-existing class spec 3 recorded); scalafmt applied to `SubcommandEntrypoints.scala` + `DispatchKernel.scala` only, baseline hunks reverted; danger-scan clean (pre-existing `DispatchKernel.scala:79` catch-all justified in place)
  - [x] Ring 2 — `probatioDependencyLint` clean on all four modules (`EventDispatch` is pure — no I/O, no env, no clock)
  - [x] Ring 3 — native image rebuilt (`probatio-cli/nativeImage`, 52s). Real runs: `probatioOracleDiff` → arms diverged on all 7 seams, VERDICT REVERT — worse files `fact-extraction.bats` (+3, pre-existing since spec 3, other specs' scope) and `workflow-hygiene.bats` **+5 → +3**; direct bats runs — `workflow-hygiene.bats` 7/10 in the live tree, **8/10 in a reproduced ported worktree arm** (test 3 fails only in-arm). `gate-payload.bats` 0 regressions both arms.
  - [x] Ring 4 — hook-envelope contract EXECUTED, all six events × both formats, fresh repo per cell: hook-json — `session-start→SessionStart`, `prompt-submit→UserPromptSubmit` envelopes; `post-edit`/`tool-call`/`post-bash`/`completion`→ empty exit 0. Text — banner for session-start/prompt-submit; empty for the other four. All twelve cells identical between ported binary and `gate.sh.predecessor.bak` on identical fresh fixtures.
  - [x] Ring 5 — `probatio-core/stryker` on `EventDispatch.scala` (GateEventCompletenessTypeContract filter): **100.0%** covered-code (9 mutants; 6 static-ignored, 3 tested, all killed). `probatio-cli/stryker` on `SubcommandEntrypoints.scala` (12 gate-entry-covering suites filter, 2h03m): **77.26% covered-code** (1435 mutants; 735 NoCoverage outside the gate filter, 36 static-ignored, 664 tested, 513 killed, 151 survived) — below the 80% adapter reference at FILE level; survivor analysis: **in-diff survivors = 3**, of which 1 killed by the added trace test (`:484` trace-text — new test "the injection fallback is recorded in the opt-in trace" asserts the opt-in trace channel) and 2 justified equivalents (`:191` `Injection(_) => false→true` on `consumes` — the payload value is never consulted on the Injection arm, consumption only drains stdin, outcome-identical; `:1631` `banner.payload.isEmpty→false` — the banner payload is always non-empty, dead branch). Remaining ~148 survivors sit in pre-existing regions outside the spec-4 diff hunks (other subcommands covered only incidentally by the gate-suite filter). Score recorded on pre-strengthening test code — the Ring-8 strengthenings only make assertions stricter.
  - **RESIDUAL — recorded per human decision (defer to owning specs)**: `workflow-hygiene.bats` +3 decomposes as (a) **test 3** — greps the arm's `ledger.sh`, which under the ported arm is the 2-line exec shim; the dead-case-arm property belongs to `ledger-checkpoint-cutover`; (b) **tests 9,10** — the stub chain-state emits unresolved entries without `spec`; the real tool (predecessor `.bak:714` and ported `probatio chain-state`) always emits it, and the ported `parseReport` enforces the sealed `UnresolvedEntry` contract (spec 2, VALIDATED) → report undetermined. The spec's PO-table claim that all four regressions stemmed from the rejected name is falsified — the event defect masked both. Verified: `gate.sh.predecessor.bak` passes all three on identical fixtures (renders `req-N (undischarged)` + `+50 more`). Spec-4 exit criterion recorded as: event-dispatch defect fixed (tests 5,6 green, the alternate-name regressions), residual +3 attributed to owning specs, suite not yet at full control parity.
  - [x] Ring 8 — fresh-context adversarial review → `ring8-gate-event-compatibility.md` (isolated read-only subagent `c39873b4`, inputs: spec + baseline diff only): **11 PASS / 0 PARTIAL / 0 FAIL**, 0 unjustified dangerous patterns, no oracle tampering, no verdict change for recognised events. Observation 4 remediated: three diagnostic assertions strengthened to the quoted-name form + new trace-fidelity test (GateEventSpec 134→135/135 green). Observation 1 (`--event` absent → Finding vs predecessor's session-start default) recorded as a known residual parity gap — outside the spec's supplied-name domain, pre-existing at baseline. danger-scan clean since HEAD.
  - [x] Ring 8 RE-RUN 2026-09-23 → `ring8-gate-event-compatibility-rerun.md` — the human declined to attest `c39873b4`'s freshness; review re-run in a verifiably fresh context (isolated read-only subagent `devin-subagent-437b30fb`; inputs: spec + typed contract + baseline diff only — no implementation conversation, no prior report): **4 requirements PASS / 0 PARTIAL / 0 FAIL**, 4 properties PASS, 2 compile-negatives PASS, Ring-6 classify contract PASS, no oracle tampering. The reviewer caught that the supplied diff omitted `DispatchKernel.scala` by path scoping; the hunk was supplied as a supplement and all verdicts confirmed unchanged. 6 non-blocking observations recorded (incl. the carried-over `--event`-absent residual parity gap). New R8 ledger row supersedes `c39873b4` (last-row-wins).
  - [x] Ring 6 — Stainless **490/490 VCs valid, 0 invalid, 0 unknown** (`probatio-verified` with stainlessEnabled, nativez3): `EventDispatchModel`/`classifyEvent` postcondition clauses (totality, recognised⇔tier, injection-carries-name) + `classifyEventInjective` + `recognisedEventNamesDistinct` all verified. `GateBridgeSpec` 3/3 incl. `bridge-classifyEvent` (shipped classify vs kernel over generated names).
- [x] Concept-delta + inventory update — `concept-inventory.md`: spec-4 section appended (`EventDispatch`, `DispatchKernel.classifyEvent` rows, provenance `spec:repair-probatio-cutover/gate-event-compatibility`); no existing rows needed annotation. Behavioural concept delta: none needed — `strangler-migration-protocol` unchanged; spec 4 restores dispatch fidelity at the `GateLast` seam, no action/synchronization shape changes (verified at Step 0).
- [x] Checkpoint — implementation committed `ccbad87`; 28 evidence rows recorded at the commit baseline (10 ring-summary + 17 PO-exact + superseding R8 re-run row; discharge requires `r.obligation ==` the spec's PO-table cell text AND `r.change == --change`; R8 row carries `--session devin-subagent-437b30fb` per the R8 contract — re-run after the human declined to attest `c39873b4`); `gate-payload.bats` **24/24** and `workflow-hygiene.bats` tests 5,6 scoped-green run for the PO rows citing them; chain-state report: 46 total / 46 bound / 46 resolved / **12 discharged** with **0 gate-event-compatibility requirements unresolved**; `checkpoint report` → R0/R1/R2/R4/R5/R6 green, **R3 FAILED** — the control-parity PO row records exit 1 honestly: `workflow-hygiene.bats` +3 residual deferred to owning specs per human decision (test 3 → ledger-checkpoint-cutover, tests 9/10 → chain-state report contract); the alternate-name regressions (tests 5,6) are green — the spec-4 defect is fixed; R8 `unverified-session` pending human attestation of the re-run session `devin-subagent-437b30fb` (same as specs 1–3)

| Commit | `ccbad87` |

---

## Spec 5/11: graph-tool-port

### 5. graph-tool-port

- **Status**: VALIDATED — human checkpoint approved (R8 freshness attested: review ran as isolated subagent `3fae7c0c` on spec + typed contract + implementation diff only); all rings green at commit baseline `c3e3d54` — `checkpoint report --session closed-earth` confirms R8 fresh-context verified (review session `devin-subagent-3fae7c0c` ≠ implementing session)
- **BASELINE SHA**: `7d9cdff36fe10792928ed42e3375023ebcdd7669`

### Step Progress
- [x] Prerequisite — predecessor traceability tool runnable on this host: `python3 openspec-graph.py` (Python 3.14.7/3.12.4 both present) — `stats` on the repo corpus: **1033 nodes** (action=132, artifact=71, code=66, concept=37, oblig=197, req=46, spec=11, sync=21, type=452), **1060 edges** (cites=13, declares=165, defines-sync=21, enforced-by=142, has-req=46, implemented-by=327, introduces=28, uses=88, verified-by=230), 12 warnings
- [x] Step 0 — Baseline + concept check
  - gate hook installed and firing (`installed:true`, last_run 2026-09-23T08:39:24Z, event post-edit)
  - working tree clean of tracked modifications (only pre-existing untracked docs + this spec's inventory snapshot); baseline `7d9cdff` = spec-4 VALIDATED marker commit
  - inventory snapshot: `inventory-snapshots/graph-tool-port-before.md` (10 opaque, 136 sealed, 483 case classes, 18 service traits, 62 Smithy, 432 generators)
  - registry-check: OK — 803 implementation-map tokens, 13 spec concept refs, 5 weak bindings (unrelated adk4s concepts, same as specs 1–4)
  - spec-lint: 0 FAIL, 42 WARN; spec 5's six W3 rows are covered by its adversarial scenarios (unreadable-source→undetermined ← "MUST NOT emit a partial graph"; unresolvable-row-in-unlinkable ← "MUST NOT discard silently"; constructor-needs-unlinkable compile-negative; artifactless-obligation-not-enforced ← "MUST NOT report as enforced on an unresolving artifact"; per-node disagreement report ← "equal node/edge sets" negative form; unknown-operation rejected ← "MUST NOT accept an operation name that has no implementation")
  - danger-scan: clean at baseline (no production .scala changed since HEAD)
  - impact-scan: semantic endpoint unavailable → textual fallback. `Subcommand` match sites enumerated: `cliName` (`Subcommand.scala:47`, exhaustive match), `fromString` (`:41`, total via `values.find` — no new arm), `HelpRegistry.helpFor` match (`:19`) + help-entry vals, `ProbatioMain.dispatch` match (`:68`), `HelpRegistry.topLevelUsage` (`values.map` — automatic). The `sub match` at `SubcommandEntrypoints.scala:3070` is over `String` (checkpoint ops), not `Subcommand`. Graph seam: `ChainStateCmd.graphExport` (`:2388`, private — shells out to `python3 openspec-graph.py export` via `PROBATIO_SCANNER_DIR`), consumed by `prepareInputs` (`:2221`) → `RequirementExtractor.extract(namedSpecs, exportJson)`. Test sites needing the widened enum: `CliSurfaceSpec` (`values.length == 10`, `exposedToolSet`), `EntrypointContractTypeContract:68` (`Subcommand.Graph` compile-negative — the OLD contract this spec reverses), `MulticallDispatchSpec`/`EntrypointContractSpec` (`values.toList.drop(1)` — automatic)
  - DEFECT CONFIRMED (empirical): `bin/probatio graph stats` → `unknown subcommand: graph`, exit 1 — the traceability tool is not nameable on the ported surface. The chain-state graph seam currently delegates to the PREDECESSOR subprocess (`python3 openspec-graph.py export`), not a ported implementation — the seam spec 5 replaces with in-process graph construction
  - concept verified: `strangler-migration-protocol` (Swap/Gate apply to this seam unchanged) + `conformance-property-test-contract` (the agreement property is the bidirectional-equivalence discipline); spec 5 changes no action/synchronization shape — whether the traceability graph warrants its own concept file is flagged for the human gate per the spec
  - proof-obligation table complete in spec: 22 rows cover all 6 requirements, 15 scenarios, 4 properties, 4 compile-negatives, the Ring-6 contract, and the exit criterion; no MUST-CONFIRM items
- [x] Step 1 — Typed contract compiled in the real source graph — **AWAITING HUMAN GATE 2026-09-23**
  - New core types (all in `workflow/core/.../probatio/core/`): `GraphNode` sealed hierarchy (Concept/Action/Sync/TypeEntry/Spec/Requirement/Obligation/Artifact/Code — derived `id`, no string-kind ctor), `GraphEdge` closed enum + `Edge` record (`planned`/`link` as `Option`s — predecessor sets them on only 2 rels) + `ObligationLink` + `ObligationSourceKind` (the 12 typed-source words), `TraceabilityGraph(nodes, edges, unlinkable)` — 3-field ctor, no defaults, `GraphBuild` (graph + warnings + rowsRead/rowsBound) as `build`'s result, `GraphQuery` closed enum (5 ops; `Export`/`Obligations` carry `Option`s), `ReachabilityResult` (3 lists, no count ctor), `UnlinkableRow` + `UnlinkableReason` opaque (no public apply; `of`/`stated` routes), `ConceptRegistryDoc`/`ImplMapRow`, `InventoryDoc`/`InventoryRow` (raw `cells` — column layout varies per section), `SpecGraphDoc`/`SpecTableRow` (reuses `SpecDocumentParser` for requirements; own `table_rows` semantics for the 4 graph tables), `GraphAudit` (`audit → Option[ReachabilityResult]` — None on empty corpus; `reaches`), `GraphWire` (`writeExport`/`readExport`/`writeChangePayload`; export adds an `unlinkable` array — predecessor consumers ignore unknown keys)
  - `Subcommand` widened +`Graph` (11 cases); `cliName`/`helpFor`/`ProbatioMain` matches extended explicitly — no catch-all; `helpFor` pins the 5 ops + their args + three-way exit
  - `GraphCmd` object added in `SubcommandEntrypoints.scala`: `run(args) → Outcome[Int]`, `buildGraph(repoRoot) → Either[String, GraphBuild]` (Left = could-not-determine naming the unreadable source), `exportObligations(repoRoot, change) → Either[String, ujson.Value]` — the seam `ChainStateCmd.graphExport` rewires onto in Step 3 (predecessor subprocess body retained meanwhile)
  - `ReachabilityKernel` (verified/probatio): `reaches(edges, from, artifacts, fuel)` with `fuel >= 0` + `fuel >= edges.length` requires and grounded+complete ensures; `audit` with conservation + disjointness ensures; `pathExists`/`disjoint` spec helpers — bodies `???` pending Step 3
  - Contract pins green: `GraphToolPortTypeContract` 11/11 (signature pins, 9 node-kind ids + wire words, 9 rels, 12 source kinds, 3 link words, 5 ops, 4 compile-negatives); reversed cli contract inverted — `Subcommand.Graph` positive pins in `EntrypointContractTypeContract`/`EntrypointContractSpec`; `CliSurfaceSpec` updated to 11 tools
  - Compile: `probatio-core/compile` + `probatio-cli/compile` + `probatio-verified/compile` + both `Test/compile` clean under -Werror; focused runs green (11 + 52 tests)
  - GATE QUESTIONS RAISED: (a) impl-map binding semantics — strict (unresolvable path-shaped citation → `unlinkable`, costs parity by 1 node + 1 edge on the real corpus: `CheckpointMessageConverter.toCheckpoint/fromCheckpoint`) vs predecessor-exact (any path-shaped backticked token binds); (b) optionally remediate that corpus row so strict binding keeps unconditional parity; (c) whether the traceability graph gets its own behavioural concept file (Step-0 flag)
- [x] Step 2 — Test oracle written; ORACLE POLARITY run done — **AWAITING HUMAN GATE 2026-09-23**
  - Gate decisions applied first: strict binding chosen (a), and the malformed corpus citation fixed in `openspec/concepts/checkpoint-store.md` (symbol tokens de-pathified, path citation parenthesised); corpus re-measured: **1032 nodes** (code=65), **1060 edges**, 12 warnings — the malformed row now binds to the real file. New concept file `openspec/concepts/traceability-graph.md` created (name `TraceabilityGraph` — `concept:Graph` already taken by the WIO graph).
  - Edge-direction correction discovered against the predecessor source: `enforced-by` runs requirement → obligation (carries `link`), `verified-by` runs obligation → artifact — `GraphEdge` docs, `GraphFixtures` chains, and `GraphAudit` traversal fixed accordingly.
  - Oracle placement per the PO table: 12 graph scenarios + `unlinkable-rows-are-conserved` + `reachability-is-transitive-and-grounded` in `SpecLintEngineSpec`; 4 compile-negatives in `SpecLintEngineTypeContract` (moved from the wrong file per PO naming); `export-round-trips` + `export-agrees-with-the-predecessor` + per-node disagreement + repo-corpus parity in `ConformanceSpec` with `GraphConformance` fixture object (materialise/readRepo/exportPorted/exportPredecessor/diffExports); five-ops dispatch + unknown-op rejection in `CliSurfaceSpec`; help surface in `CliHelpSpec`; kernel bridge test + property in `VerifiedKernelBridgeSpec` (encoding helpers in `GraphFixtures`).
  - `fact-extraction.bats` retargeted: tests 2/6 now drive `bin/probatio graph export` + `probatio chain-state` with `OPENSPEC_ROOT`; test 9's degraded trigger retargeted from absent-python3 to an unreadable source (removed `concept-inventory.md`), asserting the "graph unavailable" statement names the source; test 10 asserts no verdict-from-narrowed-set. `mk_repo` gained `openspec/concepts/` + `concept-inventory.md` so all three sources are readable.
  - POLARITY RUN: probatio-core 16 RED (all `NotImplementedError` on `???` stubs: parsers, build, audit, wire), SpecLintEngineTypeContract 7/7 green (compile-negatives hold against stubs), ConformanceSpec 3 graph RED; probatio-cli: CliSurfaceSpec 2 graph RED, CliHelpSpec 6/6 green (help already shipped). Nothing pre-existing broke.
- [x] Step 3 — Implementation (all green under `-Werror`)
  - 3a parsers: `GraphParse` shared helpers (linesOf/cellsOf/isSeparator/isMarkerCell/tableRows/sectionBounds/firstSymbol/backticks/tokens + the regex vocabulary), `ConceptRegistryDoc` (actions blocks incl. fenced-section termination, sync scanning with the predecessor's 60-line window, implementation-map citation rows), `InventoryDoc` (raw `|` scan — no header drop, preserving the `type:Type` quirk; no marker vocabulary), `SpecGraphDoc` (4 graph tables via `sectionBounds`+`tableRows`; `(none)`/`<!--` marker cells are not-read not-unlinkable in the 3 concept tables; every obligation-table row is consumed for numbering)
  - 3b `TraceabilityGraph.build` — pure: resolver predicates injected (`resolveCodePath`, `resolveArtifact`); strict binding per gate decision (a): unresolvable path-shaped citations → `unlinkable` rows + warnings (sanctioned divergence from the predecessor's unconditional bind); falsy-attr merge parity; row accounting read/bound
  - 3c `GraphAudit` (reaches via `enforced-by`/`verified-by` to a RESOLVING artifact; unresolving artifacts are transparent, not termini; fuel = edges.length) + `ReachabilityKernel` (Stainless mirror, BigInt ids; `reaches` fuel-bounded DFS with grounded/complete ensures via scan lemmas; `audit` conservation + disjointness)
  - 3d `GraphWire` — predecessor-compatible export + `unlinkable` array; `readExport` with shape-checked `*Opt` accessors; `writeChangePayload`
  - 3e `GraphCmd` (parse/buildGraph/dispatch + renderers for stats/impact/obligations/concept-code/export) + `ChainStateCmd.graphExport` rewired in-process via `exportObligations`; degraded path now emits a report-level `degraded` field (spec: degraded output states the graph was unavailable — `ChainStateUndeterminedFidelitySpec` pins that degraded mode still produces a verdict, so the field is the statement); `ChainStateParitySpec.normalise` projects `degraded` away (sanctioned divergence, predecessor has no vocabulary)
  - Parity corrections found while porting: `Backtick.findAllIn` returns the WHOLE match (Python `findall` returns group 1) → `backticks` helper maps group(1); `PlannedMarker` case-insensitive `(?i)`; sync window off-by-one; impl-map `(none)` filter removed (predecessor binds impl-map citations unconditionally — ported keeps strict path-resolution but only for path-shaped tokens); inventory has no marker vocabulary
  - Fixture bugs fixed in the oracle: `stripMargin` eats the leading `|` of interpolated continuation lines → rows joined with `\n      |`; generator made citations non-empty whenever registry rows exist (20% label floor)
- [x] Rings 0,1,2 — compile clean under `-Werror` all modules; danger-scan clean; classpath lint passes on all four modules; new core sources have no `java.nio.file`/`java.io`/`sys.env` (pure-core boundary — resolvers injected)
- [x] Ring 3 — `GraphToolPortTypeContract` 11/11 + `SpecLintEngineTypeContract` compile-negatives hold
- [x] Ring 4 — `SpecLintEngineSpec` 115/115 (scenarios + properties + surgical kills), `ConformanceSpec` green incl. repo-corpus parity (`diffExports` filters the two sanctioned divergence classes: ported-only `unlinkable` rows, predecessor-only binds explained by a ported unlinkable/warning), `VerifiedKernelBridgeSpec` green (`encodeForKernel` encodes only RESOLVING artifacts — shipped audit requires resolution), `fact-extraction.bats` 10/10 on the rebuilt native image; CLI suite 695 green. Known-unrelated residuals: `NonGoalsGuardSpec` F1–F10 (pre-existing — fixture path `complete-probatio-cutover` archived at `817d185`; spec 6's scope), `OracleGreenGate*` +3 (commit-boundary `git diff` gates + the recorded spec-4 `workflow-hygiene +3` residual deferred to owning specs)
- [x] Ring 5 — Stryker4s **96.12%** covered-code — `probatio-core/stryker`, mutate list retargeted to the 13 graph production files, test-filter = `SpecLintEngineSpec` + `GraphToolPortTypeContract` + `VerifiedKernelBridgeSpec` (`ConformanceSpec` excluded — it subprocesses `python3 openspec-graph.py` and cannot resolve the repo root inside the Stryker sandbox; GraphWire coverage supplied by the SpecLintEngineSpec round-trip tests). 586 mutants, 121 static-ignored, 464 tested, 446 killed, **18 survived — all justified equivalents**: 8× `""` `getOrElse`/`lift` fallback arms in `TraceabilityGraph` guarded unreachable by `cells.length` checks (174/190/218/236/257/260/261/313); 3× `GraphAudit:95` fuel guard (`fuel <= 0` → `<`/`==`/`false`) — fuel starts at `edges.length`, each enqueued id consumes a distinct followed edge so total decrements ≤ initial fuel, the guard never fires before `todo` empties; `InventoryDoc:75` backtick-strip fallback — only runs when `firstSymbol` found no capitalised symbol, so stripping cannot reveal one; `InventoryDoc:76` + `GraphParse:138` `exists`→`forall` on `take(1)` of a provably non-empty string (short-circuit / `[^\]]+` ≥1 char); `InventoryDoc:72` + `SpecGraphDoc:87` unreachable `getOrElse("")` on always-non-empty `cells`; `GraphParse:126` `"## "` — `sectionBounds`' `end` is discarded by the sole caller (`Some((start, _))`), dead computation; `GraphParse:103` `>=`→`==` — index only ever reaches `length` exactly (start ≤ length, step +1). Debugging trail: stale `target/stryker4s-*` dirs polluted the `**` mutate globs (5541 phantom mutants → rm + rerun); `stripMargin` fixture bug recurred once (continuation rows lost `|`). Surgical-kill round added ~40 tests: actions-block fence/section termination, sync-window boundary, impl-map partial/total resolution, all `firstSymbol` branches, table-row EOF, inventory quirks (lowercase/comment/header-row), spec marker rows in all tables, typed-source classification, explicit-ordinal bounds, inferred 2-token boundary, artifact warn paths, merge/overwrite, incoming/outgoing filters, all 9 rels in wire round-trip, write-side field pins, read-side malformed JSON, id-boundary splits (`spec:/lead`, `req:#5`), title-link first-match (`&&`→`||` killed by a source cell containing two requirement titles)
- [x] Ring 6 — Stainless **658/658 VCs valid, 0 invalid, 0 unknown** (`probatio-verified` with stainlessEnabled, nativez3 non-batched, 28s): `reaches` postcondition verified in full (grounded: `res ⇒ pathToAny` via `gwRF`/`gwScan` mutual witness recursion + `pathToAnyIntro`; complete: `pathToAny ⇒ res` via `pathToAnyWit` + `monoRF`/`monoScan` mutual monotonicity recursion); `audit` postcondition verified (size conservation via self-referential `partitionOf` postcondition + `disjoint` via `memFirst`/`memSecond`/`disjointExtend`/`partitionDisjoint` infeasibility chain). `VerifiedKernelBridgeSpec` 17/17 re-run green. **Debugging trail (the hang):** six runs stalled on a hard VC (progress froze at 6–277 of ~600). Root cause was a *cross-site lambda-identity* hazard, not the usual §4 HOF trap: `filterConserves`'s postcondition proved conservation for the literal `(x) => !p(x)` while `audit` built its second list with the literal `(r) => !p(r)` — distinct source sites ⇒ distinct function symbols ⇒ the fact never connected and Z3 unfolded `filterOf` unboundedly. Fix discipline — a lambda literal never crosses a function boundary: both partition lists are produced by one self-verifying `partitionOf` recursion; predicate applications live behind named defs (`pathToAny`, `disjoint`); the negation is expressed as a boolean expression (`!p(x)`), not a predicate term. Also dropped: tuple `decreases` measures → single `BigInt` encoding `(fuel+1)*(E+1)` / `fuel*(E+1)+rem.size`; HOF calls → `anyOf`/`allOf`/`partitionOf` structural helpers.
- [x] Ring 8 — fresh-context adversarial review → `ring8-graph-tool-port.md` (isolated read-only subagent; inputs: spec + typed contract + 38-file diff only): **no requirement FAILs; 3 real defects remediated** — (F1) `GraphAudit.reaches` fuel abort dropped pending artifacts: fuel now bounds edge-follows only, already-enqueued nodes are still inspected at fuel 0 (kernel parity — target check before fuel test) + dedicated regression test; (F2) conservation property was self-referential and `SourceCorpus.rowCount` silently returned 0 for registry/spec rows (blank line after each heading broke `takeWhile`): counters now replicate the parser's read-set independently (`tableRows` shape, separator/marker/header handling, inventory section gating) and the property asserts `corpus.rowCount == build.rowsRead` as well as conservation; (F3) `diffExports` substring masking — `strictBindingExplained` now requires exact path-token membership, not `contains`. Observations resolved: `Files.isRegularFile` is exact `os.path.isfile` parity (not git-tracking-scoped — predecessor semantics); vacuous bats `jq` pipeline fixed to isolate the JSON line. danger-scan clean; post-remediation: SpecLintEngineSpec 116/116, ConformanceSpec 13/13, VerifiedKernelBridgeSpec 17/17. Note: the F1 fix post-dates the recorded Ring-5 run — the `GraphAudit:95` survivors were re-classified under the new semantics and remain justified equivalents (`fuel < 0` unreachable — enqueues ≤ edges.length; `fuel == 0`/`false` mutants drain the queue with identical artifact checks).
- [x] Concept-delta + inventory update — `openspec/concepts/traceability-graph.md` added (registry-check OK: 817 impl-map tokens verified, 5 weak bindings all pre-existing in `graph.md`/`tools-node.md`); `concept-inventory.md` spec-5 section appended (11 rows: `GraphNode`/`GraphEdge`/`Edge`/`ObligationLink`, `UnlinkableRow`/`UnlinkableReason`, `TraceabilityGraph`, `GraphBuild`, the three parsers + `GraphParse`, `GraphQuery`, `GraphAudit`/`ReachabilityResult`, `GraphWire`/`ExportedGraph`, `ReachabilityKernel`, `GraphConformance`/`diffExports`); `Subcommand` row annotated in place (10 → 11 cases, `Graph` restored)
- [x] Checkpoint — implementation committed `c3e3d54`; 32 evidence rows recorded at the commit baseline (6 ring-summary + 26 PO-exact; discharge requires `r.obligation ==` the spec's PO-table cell text AND `r.change == --change`; R8 row carries `session: devin-subagent-3fae7c0c` per the R8 record contract); `fact-extraction.bats` **10/10** direct and green inside a materialised ported arm (`PROBATIO` follows the seam shim's exec target — the arm worktree has no built binary); chain-state report: 46 total / 46 bound / 46 resolved / **18 discharged** with **0 graph-tool-port requirements unresolved**; `checkpoint report` → R0/R1/R2/R3/R4/R5/R6 green, R8 `unverified-session` pending human attestation (same as specs 1–3); `probatioOracleDiff` verdict REVERT is the recorded spec-4 `workflow-hygiene.bats` +3 residual deferred to owning specs — `fact-extraction.bats` is better than the predecessor arm (0 vs 3 failures — the retargeted tests exercise the ported seam the predecessor cannot satisfy)

| Commit | `c3e3d54` |

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
