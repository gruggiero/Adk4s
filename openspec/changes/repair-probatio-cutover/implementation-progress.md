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

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `PrePassOutcome`, narrowed `ChainStateReport` factory (human gate)
- [ ] Step 2 — Test oracle: 9 scenarios + 3 properties + 3 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,4,5,6,8
- [ ] Concept-delta + inventory update + checkpoint

| Commit | _(pending)_ |

---

## Spec 3/11: completion-witness-refusal

### 3. completion-witness-refusal

- **Status**: pending
- **BASELINE SHA**: _(recorded at spec start)_

### Step Progress
- [ ] Step 1 — Typed contract: `WitnessVerdict` three variants (human gate)
- [ ] Step 2 — Test oracle: 9 scenarios + 4 properties + 2 compile-negatives (human gate)
- [ ] Step 3 — Implementation
- [ ] Rings 0,1,2,3,5,6,8
- [ ] Concept-delta + inventory update + checkpoint

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
