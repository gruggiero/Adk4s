# Implementation Progress — finish-probatio-replacement

<!-- Single source of truth for apply-phase progress. tasks.md is regenerated
     from this file at each checkpoint (checkpoint.sh regenerate-tasks) and is
     never hand-maintained in parallel. Section format is parser-contractual:
       - `## Spec N/12: <name>`  — checkpoint.sh report baseline extraction
       - `### N. <name>`         — checkpoint.sh regenerate-tasks pass 1
       - `- **BASELINE SHA**: `…`` — per-spec diff anchor, recorded at Step 0
       - `| Commit | <sha> |`    — completion witness (hex 7-40 = complete)
-->

## Spec 1/12: hermetic-test-processes

### 1. hermetic-test-processes

- **Status**: IN PROGRESS — Step 3 implementation done; Rings 0–3 + Ring 5 COMPLETE, Ring 8 next
- **BASELINE SHA**: `8a0c15f4a10046d041a09ef7fb3a839fb095ad29`

### Step Progress
- [ ] Prerequisite — record, per suite, the results with and without the harness session variable set, as the baseline this spec must make identical — IN PROGRESS: two-environment run in baseline worktree `/home/gruggiero/git/rs/adk4s-wt-baseline` (detached `8a0c15f`, artifacts copied in); bats log `/tmp/baseline-bats-full.log`, sbt log `/tmp/baseline-sbt.log`. BATS BASELINE COMPLETE — identical verdicts in both environments; env-independent baseline reds: chain-state 6/24, correctness-invariant 1/31, human-grant-lock 4/10, oracle-ordering-lock 7/13 (pre-existing, owned by later specs); all other 13 files green in both runs
- [x] Step 0 — Baseline + concept check: baseline `8a0c15f` committed; snapshot `inventory-snapshots/hermetic-test-processes-before.md` (11 opaque, 148 sealed, 515 case classes, 18 service traits, 62 Smithy, 487 generators); registry-check PASS (817 tokens, 15 spec refs, 5 weak bindings non-blocking); danger-scan `8a0c15f` clean; no MUST-CONFIRM; no public type widened
- [x] Step 1 — typed contract: `HermeticEnv` (private constructor, no inherit factory), `ControlledVariable` (closed set of 18, legacy and probatio names) — canonical `workflow/core/.../migration/HermeticEnv.scala`, identical cli copy, `HermeticEnvTypeContract` + `HermeticEnvCompileNegative` pins; `probatio-core/Test/compile` + `probatio-cli/Test/compile` clean under -Werror — **human gate**
- [~] Step 2 — test oracle: 11 scenarios + 2 properties + 2 compile-negative stubs; Hedgehog cover-minimum behaviour established; ORACLE POLARITY run — **human gate** — oracle written and run. GREEN-BY-DESIGN (pin the new helper): SessionIdSpec declared-session + `hermetic-env-contains-only-declared-controls` (9/9), GateEventSpec inherited-session + undeclared-hooks scenarios (137/137), GateBannerCompatSpec same-channel scenario (10/10), OutcomeSpec cover probes — EMPIRICALLY ESTABLISHED: met cover min passes, missed min fails a falsification-free run naming the label. RED-POLARITY (the defect): `parity-with-predecessor-on-the-completion-tier` + `envelope-conforms-to-contract` FAIL with CLAUDE_CODE_SESSION_ID set, pass without; `result-is-independent-of-the-invoking-environment` FALSIFIED — discharge-fidelity.bats has 7 env-dependent tests under the full controlled set (the *_OVERRIDE seams corrupt undeclared); hook-tiers.bats central-clearing test RED (all 18 controlled names survive `load helpers` today). `buildWithExtras` added to both HermeticEnv copies after the first run exposed a PATH-less child env (fixed base must come from the real env). Pre-existing red: `the recorded predecessor control fixture is well-formed` — stale archive path, owned by spec 4. NOTE: parity property falsified once at a different seed with CLAUDE unset — seed-dependent, flagged for Ring-3 two-env characterization.
- [x] Step 3 — implementation: `HermeticEnv` extended with `run`/`capture`/`captureMerged`/`empty`/`withBase`/`HermeticResult` + `ControlledVariable.fromEnvName` (all three copies; plugin copy is the 2.12 encoding with `val _` discards for NonUnitStatements); ALL raw process sites migrated — cli (15 files incl. GateEventSpec 9 sites, GateBannerCompatSpec, SubprocessConformanceSpec `withBase` sandboxed-HOME), core (10 files incl. `os.proc` sites in ConformanceSpec/GraphConformance/ArmTypes/CutoverRevert/OracleGreenCheck/SeamSwapRunner, `sys.process` in DifferentialHarnessSpec/OracleDiffRunner/SpecLintParitySpec/DangerScanParitySpec/SwapOrderSpec), plugin (HookCutoverShimSpec `runProcess`, PluginSourceLintSpec `runGit`); `helpers.bash` central clearing — `HERMETIC_CONTROLLED_VARS` (the same 18 names as the enum) unset at `load helpers`, scattered `env -u` workarounds removed in 6 bats files, explicit declarations kept; oracle green; shellcheck clean. **LINT MECHANISM DISCOVERY**: the spec's `fileFilter`-scoped `DisableSyntax` block could never fire — `fileFilter` is NOT a DisableSyntax field (javap-verified; FilterMatcher is a plain regex on the absolute path, no `glob:` handling, no per-rule file scoping anywhere in scalafix 0.14.7). Every `DisableSyntax.X { fileFilter = ... }` block in .scalafix.conf is inert, including `NoIOInProbatioCore` — pre-existing latent defect, later specs' lints (surface-honesty, legacy-name-retirement) assumed the same mechanism. IMPLEMENTED INSTEAD: `.scalafix-tests.conf` (full root rule set + 4 raw-process regexes) wired `Test / scalafixConfig` on probatio-core/probatio-cli; `.scalafix-tests-2.12.conf` (DisableSyntax-only — plugin's 2.12 build lacks -Ywarn-unused so RemoveUnused/OrganizeImports can't run, same as its Compile scalafix at baseline) on sbt-probatio. `workflow/verify-test-lint.sh` planted-violation check — all three modules reject `new ProcessBuilder`/`scala.sys.process`/`os.proc`/`.environment()` with file and line. scalafix suppression grammar established empirically: `scalafix:ok` must lead the comment; the tail is a comma-separated list of check ids — any other trailing text becomes an "unused" name (ReleaseCheck.scala needed `scalafix:ok DisableSyntax.NoSysEnv,danger-scan:allow env-lookup` — first dual-marker line in the repo). Latent test-file violations fixed with the codebase's own `scalafix:ok` convention: `finally` markers (ConformanceSpec, ReleaseManifestIOSpec, CacheMigrationSpec, MigrationProtocolSpec ×2 — plugin sites reverted after the 2.12 conf dropped keyword bans), doc-comment `sys.env` rewords (HermeticEnv ×2, HermeticEnvTypeContract), compile-negative string suppression (HermeticEnvCompileNegative ×2), `scala.sys.process` mention reworded (HookCutoverShimSpec), import reorganisation applied across test sources.
- [x] Ring 0 — compile clean: `probatio-core/Test/compile`, `probatio-cli/Test/compile`, `sbt-probatio/Test/compile` all green under `-Werror` (WartRemover runs inside compile; 2.12 plugin NonUnitStatements satisfied via chained-redirect discards)
- [x] Ring 1 — `Test/scalafix --check` green on all three workflow modules (core+cli full conf, plugin DisableSyntax-only 2.12 conf); `workflow/verify-test-lint.sh` planted-violation check passes on all three (rejects `new ProcessBuilder`/`scala.sys.process`/`os.proc`/`.environment()` with file+line); WartRemover via compile clean; `danger-scan.sh` OK; `shellcheck -x helpers.bash` clean. KNOWN BASELINE RED: `sbt-probatio/Compile/scalafix` errors at baseline (2.12 lacks -Ywarn-unused → RemoveUnused/OrganizeImports can't run) — unchanged by this spec.
- [x] Ring 2 — `probatioDependencyLint` green: probatio-core, probatio-cli, sbt-probatio, probatio-verified all classpath-clean
- [x] Ring 3 — two-environment runs COMPLETE. BATS: per-file verdicts IDENTICAL both envs (`diff` of failure names identical); 17 pre-existing reds (oracle-ordering-lock 7, chain-state 6, human-grant-lock 4 — later specs' domain); `correctness-invariant` baseline failure now GREEN (central clearing fixed a real env-leak); `discharge-fidelity` green both envs; `hook-tiers` central-clearing oracle green. SCALA core: identical failure sets both envs — OracleGreenGateSpec.R-M1 + OracleGreenCheck (git-diff oracle guards, red mid-flight BY DESIGN while `tests/` is edited — green at commit), NonGoalsGuardSpec (spec-5), DifferentialHarnessSpec stale-fixture (spec-4). SCALA cli: identical failure sets both envs — MigrationProtocolSpec.schema-rename (PRE-EXISTING at baseline `8a0c15f`: recorded deferral says 19 pins, on-disk count is 20 in both trees — stale value owned by spec-12) + LedgerCheckpointCliTypeContract phantom "1 failed, 0 total" (pre-existing munit artifact on a zero-test contract suite; sbt counts it PASS). SCALA plugin: 90/90 green BOTH envs. ENV-INDEPENDENCE PROPERTY: all three prior "falsifications" were COVERAGE-ONLY — `dependent` was empty on every case (file-written diagnostics); full-controlled-set label hit 3% vs 4% min because the generator weighted it 1/20. Rebalanced to 4/20 (2/2/4/12); seed 53363367331174630 replays GREEN. SpecLintBridgeSpec.bridge-guardOutcome flaked once under set env (39% vs 40% not-resolved) — pre-existing suite, seed-dependent, passes both envs in isolation — NOT env-dependent. KEY WIN: GateBannerCompatSpec parity property + envelope-conforms-to-contract — RED under CLAUDE_CODE_SESSION_ID before Step 3, now 10/10 green under `CLAUDE_CODE_SESSION_ID=ring3-simulated-harness`.
- [x] Ring 5 — mutation score **93.75%** (19 mutants, 15 killed, 1 survived, 3 static-ignored): moved `HermeticEnv.scala` (enum + case class + companion + HermeticResult) to core main sources for the run — `HermeticEnvGens` stayed in test (hedgehog is test-scoped) — retargeted `stryker4s.conf` (mutate `src/main/.../HermeticEnv.scala`; test-filter = SessionIdSpec + HermeticEnvSpec + HermeticEnvTypeContract + HermeticEnvCompileNegative + SwapOrderSpec + CutoverRevertSpec; DifferentialHarnessSpec excluded as too slow per mutant); first run 18.75% exposed real coverage gaps → added `HermeticEnvSpec` (9 tests: fromEnvName round-trip, withBase base-only filter, run/capture stdin EOF + stdin-bytes, stream separation, captureMerged merge + stdin, probeChild '='-in-value parse); re-run 81.25% → captureMerged-stdin test killed the remaining trio → 93.75%. Sole survivor: `stdin.isEmpty → false` at captureMerged — benign equivalence (when stdin IS provided the /dev/null redirect is unobservable through the public API). File merged back to test sources post-run; test-scope compile green.
- [~] Ring 8 — fresh-context review COMPLETE → PARTIAL, fixes applied. Verdict: 4 PASS / 1 PARTIAL / 0 FAIL; compile-negative obligations PASS; no oracle tampering; 8 dangerous-pattern hits (5 justified, 3 open). SUBSTANTIVE FINDINGS + RESOLUTIONS: (a) env-independence property's `Nil` domain arm generated a fake `/nonexistent-suite-dir` path that vacuously passed — replaced with a static `lazy val spawningSuites` + a match whose `Nil` arm returns `Result.failure` lifted through `Gen.constant(()).forAll.map` (no fake path exists anywhere); (b) `genControlledSubset` weights still statistically flaky at n=30 — rebalanced 3/3/6/8 → 4/4/6/8 and `testLimit` 30 → 50 (edge-category coverage-miss ≈0.3%/category; combined property flake ~1%); (c) lint evasion vectors — `sys.process` without the `scala.` prefix, parenless `.environment`, and `Runtime.getRuntime` all unmatched — patterns widened to `(?:scala\.)?sys\.process`, `\.environment\b`, `Runtime\s*\.\s*getRuntime` (new `NoRuntimeExec` rule) in BOTH confs; three purity-spec `"sys.process"` string literals sanctioned with `scalafix:ok` markers (regex is text-level); `verify-test-lint.sh` extended to plant and assert ALL FIVE rule ids per module (`os.proc` planted as a string literal for the plugin — no os-lib dep; the text-level rule fires identically) — 5/5 rules fire × 3 modules. POST-FIX VERIFICATION: compile clean; `Test/scalafix --check` green ×3; `verify-test-lint.sh` green ×3 modules; shellcheck clean; `DifferentialHarnessSpec` both envs — env-independence property GREEN at n=50, sole remaining red = spec-4 stale fixture (identical failure sets both envs); `SessionIdSpec` 9/9 both envs; compile-negative assertions strengthened to error-specific checks (private-constructor / not-a-member) — green ×2 modules. SPEC TEXT CORRECTED: `fileFilter` mechanism removed from spec.md proof obligations + anchor and design.md (it never existed; `NoIOInProbatioCore` was dead config). INFORMATIONAL, ACCEPTED AS-IS: `GATE_OVERRIDE` latent in `SeamTypes.ToolId` — installer-swap's domain; `OutcomeSpec` missed-minimum probe is already deterministic (0% generation vs 80% min) and asserts status+label; `captureMerged` stdin-before-drain matches the replaced code (not an env leak); Ring-5 carries over — mutate target `HermeticEnv` class byte-identical post-restore, Ring-8 edits touched only gens/test code outside the mutation surface.
- [ ] Concept-delta check + inventory update + checkpoint

---

## Spec 2/12: jar-launcher-dispatch

### 2. jar-launcher-dispatch

- **Status**: PENDING

---

## Spec 3/12: entrypoint-split

### 3. entrypoint-split

- **Status**: PENDING

---

## Spec 4/12: archive-safe-fixtures

### 4. archive-safe-fixtures

- **Status**: PENDING

---

## Spec 5/12: oracle-independence

### 5. oracle-independence

- **Status**: PENDING

---

## Spec 6/12: oracle-fixture-repair

### 6. oracle-fixture-repair

- **Status**: PENDING

---

## Spec 7/12: delivery-verified

### 7. delivery-verified

- **Status**: PENDING

---

## Spec 8/12: installer-swap

### 8. installer-swap

- **Status**: PENDING

---

## Spec 9/12: surface-honesty

### 9. surface-honesty

- **Status**: PENDING

---

## Spec 10/12: registry-check-port

### 10. registry-check-port (with the cli-wiring delta)

- **Status**: PENDING

---

## Spec 11/12: legacy-name-retirement

### 11. legacy-name-retirement

- **Status**: PENDING

---

## Spec 12/12: schema-directory-rename

### 12. schema-directory-rename

- **Status**: PENDING

---

## Change exit criterion

- [ ] The differential shows **no file worse** than the genuine predecessor control, measured in an **archive-only environment** and **with harness session variables set**
- [ ] A recorded hosted CI run passes at the final commit
- [ ] The oracle-immutability guard is green
- [ ] The seven `.predecessor.bak` files remain as revert targets — their retirement needs a full green cycle after this change
