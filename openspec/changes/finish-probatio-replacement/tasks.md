# Tasks: finish-probatio-replacement

Derived from `implementation-order.md`. `implementation-progress.md` is the single source of
truth for progress; this file is regenerated from it at each checkpoint and is never
hand-maintained in parallel.

Every spec takes **two separate human gates** — the change's correctness risk is high.

## 1. hermetic-test-processes

- [x] Prerequisite — record, per suite, the results with and without the harness session variable set, as the baseline this spec must make identical
- [x] Step 1 — typed contract: `HermeticEnv` with a private constructor and no inherit factory, `ControlledVariable` as the closed set of variables the tools read (legacy and probatio names both) (compiles; **human gate**)
- [x] Step 2 — test oracle: 11 scenarios + 2 properties (`result-is-independent-of-the-invoking-environment`, `hermetic-env-contains-only-declared-controls`) + 2 compile-negative stubs; establish whether a missed Hedgehog cover minimum fails a passing run; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: the shared helper for Scala and bats; migrate the 19 process-spawning suites and the bats shared setup; add the no-raw-process lint to `.scalafix.conf`; triage and record every test that turns red once isolated
- [x] Ring 0 — `sbt "probatio-core/Test/compile" "probatio-cli/Test/compile" "sbt-probatio/Test/compile"` clean
- [x] Ring 1 — scalafix (including the new lint, with a planted violation), WartRemover, dangerous-pattern scan, shellcheck on `helpers.bash`
- [x] Ring 2 — `probatioDependencyLint` clean
- [x] Ring 3 — suites green in both environments; **the completion-tier parity property passes with and without the harness variable**
- [x] Ring 5 — move test sources to main, retarget, run, move back; read the score (80%)
- [x] Ring 8 — fresh-context review, in an archive-only tree with harness session variables set
- [x] Concept-delta check + inventory update + **checkpoint**

## 2. jar-launcher-dispatch

- [x] Step 1 — typed contract: `InvocationSource` (`NamedExecutable`, `Archive`), `InvocationName` constructed only from it (compiles; **human gate**)
- [x] Step 2 — test oracle: 12 scenarios + 3 properties (`archive-and-generic-dispatch-agree`, `named-executable-strictness-is-preserved`, `archive-conformance-matches-native-conformance`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: classify the invocation source; dispatch an archive source as the generic name; run the subprocess conformance against the archive too; provision the built tool into each comparison arm; remove the symlink workaround from `ChainStateParitySpec`
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean; dangerous-pattern scan; shellcheck on `bin/probatio`
- [x] Ring 2 — dependency lint clean; the dispatch decision reads no runtime state
- [x] Ring 3 — suites green; **the acceptance suite passes in an archive-only tree**; the predecessor arm matches an independent control per file
- [x] Ring 5 — retarget to the dispatch files and `ArmTypes` (move-to-main for the latter); read the score (90% core / 80% adapter)
- [x] Ring 6 — extend `DispatchKernel` with the archive-source contract; bridge property green; invoke `probatio-verified` directly
- [x] Ring 8 — fresh-context review in an archive-only tree with harness session variables set
- [x] Concept-delta check + inventory update + **checkpoint**

## 3. entrypoint-split

- [x] Prerequisite — record the acceptance suite's per-file counts and the conformance corpus's outputs **before** the split
- [x] Step 1 — typed contract: the eleven entrypoint signatures, unchanged, each in its own file (compiles; **human gate**)
- [x] Step 2 — test oracle: 6 scenarios + 1 property (`split-preserves-every-observable`); the layout check and the byte-for-byte move comparison; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: move each entrypoint to its own file and the shared helpers to one file; no edit inside any moved body
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean
- [x] Ring 2 — dependency lint clean; no decision logic moves into the adapter
- [x] Ring 3 — every observable identical before and after, through both artifacts, hermetically; every moved body identical
- [x] Ring 5 — stated skip (no logic changed). Record a per-file baseline score for the later specs
- [x] Ring 8 — fresh-context review comparing each moved body against its origin
- [x] Concept-delta check + inventory update + **checkpoint**

## 4. archive-safe-fixtures

- [x] Step 1 — typed contract: `ChangeLocation` (`Active`, `Archived`, `Absent(searched)`) (compiles; **human gate**)
- [x] Step 2 — test oracle: 9 scenarios + 2 properties (`resolution-is-location-independent`, `absent-names-every-searched-location`) + 1 compile-negative stub; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: generalise the guard's resolver; route every fixture read through it; add the literal-path lint (Scala) and its bats-side check
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean, including the new lint with a planted literal and a permitted synthetic path
- [x] Ring 2 — dependency lint clean
- [x] Ring 3 — suites green; **`DifferentialHarnessSpec` green with the fixture in the archive**
- [x] Ring 5 — move test sources to main, retarget, run, move back; read the score (80%)
- [x] Ring 8 — fresh-context review
- [x] Concept-delta check + inventory update + **checkpoint**

## 5. oracle-independence

- [ ] Prerequisite — record the oracle baseline commit explicitly
- [ ] Step 1 — typed contract: `OracleTestKind`, `OracleSanction` (requires spec and requirement), `SanctionVerdict` (three variants), `OracleBaseline` (compiles; **human gate**)
- [ ] Step 2 — test oracle: 13 scenarios + 3 properties (`guard-passes-iff-all-sanctioned`, `sanction-record-round-trips`, `oracle-contains-no-structural-test`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: classify every oracle test; move the structural ones to `tests/shape/`; write the sanction record; replace commit-message baseline discovery with the recorded baseline; resolve each historical modification (moved, sanctioned or reverted); update the migration concept file
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; shellcheck on the shape suite
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — suites green; **the immutability guard green, and red on a planted unsanctioned edit**
- [ ] Ring 4 — the sanction record round-trips, including titles with quotes, em-dashes and non-ASCII characters
- [ ] Ring 5 — move the guard sources to main, retarget, run, move back; read the score (90%)
- [ ] Ring 6 — extend `SpecLintKernel` with the sanction contract; bridge property green
- [ ] Ring 8 — fresh-context review; check each sanction's cited requirement actually names its test
- [ ] Concept-delta check + inventory update + **checkpoint**

## 6. oracle-fixture-repair

- [x] Prerequisite — **root-cause the six shared `chain-state.bats` failures and record each cause with its evidence** before Step 1
- [x] Prerequisite — confirm the Devin hook payload's tool-name field against Devin's documentation; record the source
- [x] Step 1 — typed contract: `ToolNameSource` (`Supplied`, `Absent`) at the pre-execution decision (compiles; **human gate**)
- [x] Step 2 — test oracle: 10 scenarios + 2 properties (`lock-decision-is-independent-of-name-case-and-channel`, `absent-name-always-allows-and-says-so`) + 1 compile-negative stub; ORACLE POLARITY run (**human gate**)
- [x] Step 3 — implementation: supply tool names in the lock fixtures (sanctioned); state an absent tool name in the diagnostic; repair each stale `chain-state.bats` fixture (sanctioned), or record a shared implementation defect and stop
- [x] Ring 0 — compile clean
- [x] Ring 1 — lint clean; shellcheck on the edited suite files
- [x] Ring 2 — dependency lint clean
- [x] Ring 3 — **the three suite files pass under both implementations**; the guard stays green
- [x] Ring 5 — retarget to the gate's entrypoint file; read the score (80%)
- [x] Ring 8 — fresh-context review; reject any fixture change with no recorded root cause
- [x] Concept-delta check + inventory update + **checkpoint**

## 7. delivery-verified

- [ ] Step 1 — typed contract: `ToolchainIdentity`; the release check requires the tested identity (compiles; **human gate**)
- [ ] Step 2 — test oracle: 11 scenarios + 1 property (`toolchain-check-accepts-iff-identical`) + 1 compile-negative stub; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: pin the native toolchain and re-establish conformance and latency on it; keep the CI acceptance step on the archive; add the toolchain comparison to the release check
- [ ] **Ask the maintainer to authorise publishing the branch** — no push without explicit approval
- [ ] Observe a hosted CI run passing at a green commit; record its identifier and outcome
- [ ] Observe a hosted CI run failing on a deliberately regressing branch, naming the file; record it
- [ ] Build and validate a release candidate locally with the release check; record it
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; shellcheck on the workflow steps
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — property + scenario suites green
- [ ] Ring 5 — retarget to the release check; read the score (80%)
- [ ] Ring 8 — fresh-context review; reject any evidence entry that infers a hosted outcome from a local command
- [ ] **Checkpoint**

## 8. installer-swap

- [ ] Step 1 — typed contract: `ToolId` and `SwapOrder` gain the two installer entries (compiles; **human gate**)
- [ ] Step 2 — test oracle: 8 scenarios + 2 properties (`seam-set-covers-the-swap-order`, `installer-swap-requires-a-complete-comparison`) + 1 compile-negative stub; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: add the seams; compare; swap each installer at parity with its predecessor kept; update the migration concept file
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; shellcheck on the two new forwarding scripts
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — **both installers compared and at control parity**; the hook installer merges with no python3 on the search path
- [ ] Ring 5 — move the seam sources to main, retarget, run, move back; read the score (80%)
- [ ] Ring 8 — fresh-context review
- [ ] Concept-delta check + inventory update + **checkpoint**

## 9. surface-honesty

- [ ] Step 1 — typed contract: `LiveRoute`; `ToolSurfaceClassification.Ported(subcommand, route)`; `Subcommand` without `Metals` (compiles; **human gate**)
- [ ] Step 2 — test oracle: 9 scenarios + 2 properties (`ported-iff-live-route-reaches-the-port`, `surface-names-only-working-subcommands`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: remove the code-intelligence subcommand; register both code-intelligence scripts; classify by live route; retire the predecessor graph script to `.predecessor.bak`; rewrite the tutorial's traceability examples; update the migration concept file
- [ ] Ring 0 — compile clean; every former `Metals` arm removed
- [ ] Ring 1 — lint clean
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — **the register classifies every tool with no finding**; no instruction runs the predecessor graph script
- [ ] Ring 5 — retarget to the register and classification; read the score (90%)
- [ ] Ring 8 — fresh-context review
- [ ] Concept-delta check + inventory update + **checkpoint**

## 10. registry-check-port (with the cli-wiring delta)

- [ ] Step 1 — typed contract: `RegistryRow`, `BindingVerdict`, `RegistryReport` with a derived pass; `Subcommand` and `ToolId` gain the verifier (compiles; **human gate**)
- [ ] Step 2 — test oracle: 11 scenarios + 2 properties (`verdicts-agree-with-the-predecessor`, `passed-iff-no-stale-and-no-spec-problem`) + 2 compile-negative stubs; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: the pure engine and its adapter; could-not-determine on unreadable input; the seam and swap at parity; CI and the banner invoke the port; remove the verifier's register entry
- [ ] Run the scalameta spike with the release toolchain; record the build log and parse output; set the concept scanner's blocker from the result
- [ ] Apply the `cli-wiring` delta — the restated requirement now matches the surface
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; shellcheck on the new forwarding script
- [ ] Ring 2 — dependency lint clean; the engine reads no file
- [ ] Ring 3 — **report lines identical to the predecessor's** on the repository and on generated registries; swapped at parity
- [ ] Ring 4 — the report lines, as the format CI and the banner consume
- [ ] Ring 5 — retarget to the engine and its entrypoint; read the score (90% core / 80% adapter)
- [ ] Ring 6 — extend `SpecLintKernel` with the pass contract; bridge property green
- [ ] Ring 8 — fresh-context review
- [ ] Concept-delta check + inventory update + **checkpoint**

## 11. legacy-name-retirement

- [ ] Step 1 — typed contract: `LegacyAlias` with a required closing version; `GateStateDir` with first-use migration (compiles; **human gate**)
- [ ] Step 2 — test oracle: 14 scenarios + 2 properties (`alias-resolution-is-ordered-and-bounded`, `state-migration-is-idempotent-and-lossless`) + 1 compile-negative stub + 1 static rule; ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: route every environment read through the alias table; rename user-facing messages; rename and migrate the state directory; rename the pi adapter; add the main-sources lint; edit the named oracle files only where sanctioned
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean, including the new lint with a planted direct read
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — **no legacy-only variable remains**; the seven named oracle files at parity
- [ ] Ring 4 — the state-directory migration carries every state-file kind; legacy aliases honoured through v15
- [ ] Ring 5 — retarget to the alias table and the migration; read the score (90% core / 80% adapter)
- [ ] Ring 8 — fresh-context review
- [ ] Concept-delta check + inventory update + **checkpoint**

## 12. schema-directory-rename

- [ ] Prerequisite (MUST-CONFIRM) — re-establish the openspec CLI's alias resolution on the version in use, in a scratch worktree; record the version and result
- [ ] Step 1 — typed contract: `SchemaAlias`; the discharged `RenameDeferral` (compiles; **human gate**)
- [ ] Step 2 — test oracle: 9 scenarios + 2 properties (`every-active-change-resolves`, `no-live-reference-to-the-previous-path`); ORACLE POLARITY run (**human gate**)
- [ ] Step 3 — implementation: move the directory with history; add the alias link; set the project configuration; rewrite the live references; discharge the deferral; leave the archived pins as they are
- [ ] Ring 0 — compile clean
- [ ] Ring 1 — lint clean; shellcheck on every moved script
- [ ] Ring 2 — dependency lint clean
- [ ] Ring 3 — **every active change resolves under both names**; no live reference to the previous path
- [ ] Ring 4 — schema resolution of active changes; a checkout with symbolic links disabled is reported
- [ ] Ring 8 — fresh-context review
- [ ] Concept-delta check + inventory update + **checkpoint**

## Change exit criterion

- [ ] The differential shows **no file worse** than the genuine predecessor control, measured in an **archive-only environment** and **with harness session variables set**
- [ ] A recorded hosted CI run passes at the final commit
- [ ] The oracle-immutability guard is green
- [ ] The seven `.predecessor.bak` files remain as revert targets — their retirement needs a full green cycle after this change
