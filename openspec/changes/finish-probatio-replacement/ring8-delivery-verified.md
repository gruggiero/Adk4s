# Ring 8: Adversarial Spec-Compliance Review — delivery-verified

Fresh context: yes (isolated subagent; inputs: spec + progress record + `git diff 38bfb22` only — no implementation conversation). **Second pass**: the first pass (`d782de9d`, recorded at `7b49ff6`) covered ≤`d64afb7`; this pass re-reviews the full baseline diff at `980bbdb`, adding the post-first-pass work: envVariance control column, requiredObjects pre-check, parity-fixture env-stabilisation, coverage deflakes, verify.yml hosted-fix commits.

Baseline: `38bfb2239c81a5cb7d1abe9c38e2e682d4fdc3d2`
Diff reviewed: `.github/workflows/{verify,release-probatio}.yml`, `build.sbt`, `workflow/cli/src/main/scala/org/sinemenda/probatio/packaging/{ToolchainIdentity,ToolchainVerdict,ReleaseValidator,ReleaseManifestIO,ReleaseManifest,ReleaseCheck,SbomGenerate}.scala`, `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandWiring.scala`, `workflow/core/src/test/scala/org/sinemenda/probatio/migration/{DifferentialHarness,OracleDiffRunner,DifferentialHarnessSpec}.scala`, `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/PluginSourceLintSpec.scala`, `workflow/cli/src/test/scala/org/sinemenda/probatio/{packaging/*,cli/ChainStateParitySpec,cli/LedgerCmdConformanceSpec,cli/GateBannerCompatSpec}.scala`, `adk4s-record/.../CallKeySpec.scala`, `adk4s-core/.../JsonValueCodecSpec.scala`, `workflow/core/.../SpecLintBridgeSpec.scala`, `openspec/.../fixtures/predecessor-control.json`, `tests/chain-state.bats`, `tests/oracle-sanctions.jsonl`, `stryker4s.conf`. (Spec-6 files in the range — `OracleSanctionGuard`, `NonGoalsGuardSpec`, shape suite — were reviewed under `ring8-oracle-independence.md`; boundary respected here.)

Dangerous patterns found: 0 unjustified (`danger-scan.sh 38bfb22` → OK; every catch-all/`NonFatal`/`getOrElse` judged below)
Oracle tampering: 1 flag (F1, `dec9f80` — see below); the five deflake commits and the envVariance fixture column judged NOT weakenings (evidence below)
Requirements: **6 PASS, 0 PARTIAL, 0 FAIL** — with three recorded soft spots (S1–S3)

## Requirement-by-requirement review

| Requirement | Verdict |
|---|---|
| MUST-CONFIRM — publishing is authorised, not inferred | PASS — the spec's diff performs no push; `verify.yml`/`release-probatio.yml` only react to hosted events. The pushes that happened (`probatio/porting`, two regression branches, PR #3, the `b4dcb04` ancestry anchor, `probatio/control-anchor-00d3de1`) are recorded as maintainer-authorised in `implementation-progress.md`; nothing in code infers them. |
| The CI job is observed to run and pass | PASS — `verify.yml:11-14` triggers `push: [main]` + `pull_request`, no `paths:` filter; zero `if:`/`continue-on-error:` anywhere in the file (grepped). `missingCiSteps` (PluginSourceLintSpec:372-409) requires every suite step unguarded — both neutraliser shapes are adversarially pinned (:456-594: step `if:`, `continue-on-error:`, job-level `if:`, marker-line `- if:`, comment needle, literal-block `- ` masking). Hosted green run **40954397619** recorded in the evidence ledger — an observed run, not an inference. |
| The CI job is observed to fail on a regression | PASS — recorded hosted observations: run **41339809913** (`probatio/regression-acceptance`) failed at the acceptance step naming `evidence-ledger.bats`; run **41340717360** (`probatio/regression-module`) failed at Module test suites naming `ReleaseManifestIOSpec`. Both obligation shapes covered. |
| The CI job runs the tools through the archive | PASS — `verify.yml` order: assembly (`:96-97`) → install-skills (`:103-104`) → acceptance (`:106-107`) → nativeImage (`:114-115`). `bin/probatio:27` execs the native image only `if [ -x ]` — it does not exist before the acceptance step in a fresh clone, so the suite exercises the JAR. `archivePathIssues` (PluginSourceLintSpec:937-979) is job-boundary-aware (`jobStepsRegions` :898-925): a parallel-job `assembly` step and an `assembly`-named matrix item are both reported (adversarial tests :1083-1130). Hosted run 36613787004 observed the ordering directly. |
| The delivered binary is built with the toolchain that was tested | PASS — full chain verified: `ToolchainIdentity.parse` (:81-85) exact `<dist>/<ver>` split, nonempty halves, `case _ → None` (rejection, not default); `readEmbedded` (:112-127) maps both observed markers — modern `GraalVM CE 21.0.2+13.1` → `GraalVM CE/21.0.2` (build-id dropped per doc), legacy `GraalVM 22.3.1 Java 17 CE` → `GraalVM CE/22.3.1` — no marker → `Unreadable` naming the binary. `toolchainVerdict` (ReleaseValidator:130-139) is exact `==` on the case class; `Rejected` carries both identities and the issue names both (:168-171); `Undetermined` names the binary; a `NativeBinary` with **no** read is a could-not-determine issue (:162-163) — the check cannot silently skip. `build.sbt:579-580` pins `graalvm-community`/`21.0.2` matching the release matrix (`release-probatio.yml:46,50,54`) and `TESTED_TOOLCHAIN` (:35); matrix drift is fail-closed — a different toolchain embeds a different marker and the gate rejects naming both. |
| A release candidate is built and validated locally | PASS (code half) — `ReleaseManifestIO.fromDirectory` recomputes sidecar digests against content (:147-162) and `Left`s on mismatch; `validateToolchain` requires a read per `NativeBinary`; `isCIEnvironment` is fail-closed (`env("CI").contains("true")`); `ReleaseCheck.run` requires `tested` — no arity overload exists. Local candidate validation recorded (11 artifacts accepted; `GraalVM CE/22.3.1` rejected naming all three binaries and both identities; `CI` unset refused). |

Property `toolchain-check-accepts-iff-identical`: PASS — asserts `verdict.accepted == candidate.identityOption.contains(tested)` — the spec predicate, not the implementation — plus payload legs naming binary/tested/candidate (ReleaseManifestIOSpec:560-572). `genToolchainPair` constructive over closed dist/version sets, `kind` forced by construction, no filtering.

Compile-Negative (release check that omits the toolchain): PASS — three `compileErrors` pins in `NativeGateDeliveryTypeContract` (1-arg `validateAll`, 3-arg `ReleaseCheck.run`, `Version("")`); `Version` has no public `apply`.

## Post-first-pass additions — verdicts

**DifferentialHarness envVariance control column** (`DifferentialHarness.scala:194-219`, fixture `predecessor-control.json`): the control gains an alternate expected-count column applied only when the probe `bash -c 'cd ""'` fails (bash ≥ 5.2 changes the *predecessor's own* `cd "$REPO" && git rev-parse` fallback inside the arm). Adversarial read: the probe failure direction is honest — if the probe fails, `getOrElse(false)` selects the recorded column, so a genuinely variant run produces a `Finding`, not a pass; a malformed `envVariance` block degrades to `Map.empty` → recorded column → loud `Finding`. The swap is per-file and the alternate count was host-observed (24t/0f) and locally reproduced. Judged a legitimate environment-portability repair, NOT a weakened oracle: the check still fails loudly on any count matching neither recorded column. Soft spot S2 notes the swap has no dedicated unit test.

**OracleDiffRunner requiredObjects pre-check** (`:181-213`): verified correct — the objects are those the *arm's* fixture names: the arm is materialised at `measuredAtBaseline` `817d185`, whose committed `chain-state.bats` has `BASE="00d3de1"` (verified via `git show 817d185:...`). `00d3de1` is the only fixture-named object whose resolution changes verdicts (row contents compare literally). Missing object → loud `UNDETERMINED` + `fail`, placed before materialisation. `b4dcb04` (`-s ours` merge) anchored `00d3de1` into branch ancestry so fresh clones resolve it. Mild over-strictness: on bash ≥ 5.2 the object needn't resolve yet is still required — fail-closed, acceptable. Nit: `.getOrElse(List.empty)` silently skips the pre-check on a malformed section (conservative direction).

**ChainStateParitySpec baseline determinism** (`2a3c47e`/`12fc8f9`/`30fd224`): `baseline` re-set to `fullBaseline` (a full SHA resolving to itself on every bash); the `"zzz"` row's baseline re-set to `fullBaseline` so the row can never match the raw arg under either `cd ""` semantics. This *removes* an env-dependent expectation rather than asserting it — the parity predicate itself (model output == port output) is unchanged; the dropped `deadbee`-matching-row case was untestable deterministically, and unresolvable-arg parity remains covered by `baselineArg = "zzz"`. Legitimate.

**chain-state.bats re-anchor** (`50a7317`, sanctioned): `00d3de1` → `705a926` (mainline ancestor of every pushed branch). Recorded in `oracle-sanctions.jsonl` citing the fixture-repair requirement — guard-covered.

**Coverage deflakes** (`9e3fcb9`, `f556a98`, `068b542`, `846fe75`, `57796f4`, `2a3c47e`'s coverConfig): all are `testLimit` raises (300–1000) and `Gen.frequency1` reweights. Floors, predicates, and leg semantics verified unchanged in every diff — expected class rates now sit multi-sigma above floors. NOT tampering.

**Forgive-unchanged memoization** (`7feccf2`, SubcommandWiring:343-361): `(baseline, artifact) → Boolean` memoised in an `AtomicReference` scoped to one invocation — `git diff --quiet` cannot return a different answer within a predicate's lifetime (same contract the name-only batch already relied on). Behaviour pinned by ForgiveUnchangedSpec. Legitimate latency fix.

**Probe commits** (`6120947`, `2670887`, `d7673df`): temporary diagnostics in `OracleDiffRunner`, fully removed by `980bbdb` — verified none remain.

**verify.yml hosted-fix commits** (`298d8bd`–`436c911`, `6291ad5`, `4db07eb`): bats 1.14.0/openspec/shfmt/stainless provisioning, install-skills ordering, native-image post-acceptance, 20-min oracle-diff timeout. All steps unguarded and observed green on run 22.

**SubcommandEntrypoints.before.scala** (corpus repair, `7e6f02b`): re-verified — fixture's `GateCmd` span (lines 34–1690) is byte-identical to live `GateCmd.scala:24-1678` (diff clean apart from the trailing boundary comment). Legitimate verbatim mirror of the spec-6 moved body.

**Vendored `project/lib/sbt-stainless.jar`** (`.gitignore` -1 line, `186b980`): needed because CI provisions `stainless/` from the pinned upstream bundle while the sbt plugin jar itself is tracked. Not a spec surface.

## Findings

**F1 — oracle weakening, other spec's property (flagged for fix; no spec-7 requirement flipped)**
`dec9f80` changed `CallKeySpec` RL3 (add-adk4s-record) from `Result.assert(kBase != kMutated)` to `if mutated == base then Result.success else assert(...)`. Semantically justified — `genRequestMutation`'s 12-arm `Gen.choice1` of constants genuinely produces no-ops (`ChangeTemperature(0.5)` onto 0.5, `ReorderMessages` on a 1-message request). **But** the property has no coverage leg: were the generator to degrade toward always-no-op mutations, RL3 would pass *vacuously* — before this change it would have failed. Under the strict rule this is an oracle weakening the tests cannot catch.
- Fix class: new targeted cover leg (e.g. `.cover(50, "effective mutation", mutated != base)`), or make the generator value-aware so no-ops are impossible by construction. Recommend fixing before checkpoint sign-off, or human approval noting the property's RL3 obligation is now conditional on generator liveness.

**S1 — soft spot: `discoveredTestModules` discovery scope** (PluginSourceLintSpec:320-326)
Only backticked `` `name` `` `(project in file(...))` declarations are discovered; an un-backticked or `.in(file(...)))`-style declaration with a `src/test` tree would never join the required CI step set. Current build.sbt is uniformly backticked and all 15 discovered modules match verify.yml exactly (verified: `verified`, `probatio-verified`, `probatio-spike` correctly have no `src/test`; `adk4s-agent/src/test` exists but is undeclared — it cannot produce an `adk4s-agent/test` task at all).

**S2 — soft spot: envVariance swap + requiredObjects pre-check lack dedicated tests**
Both new branches are exercised only by the real `probatioOracleControl` run (green on run 22) — the swap could be unit-tested by shadowing `bash`/`git` on the hermetic PATH, and the pre-check by a synthetic control fixture. Absence of tests is not a spec violation (the spec's proof table names other artifacts) but is a resilience gap in harness code that now carries two accepted outcomes.

**S3 — soft spot: `archivePathIssues` is marker-textual**
A step provisioning a *prebuilt* native binary into `workflow/cli/target/native-image/probatio` (e.g. `actions/download-artifact`) before the acceptance step would evade detection — `bin/probatio` would then resolve the native path and the suite would not exercise the archive. Not present in the current configuration; the hosted run observed the real ordering. If workflows grow artifact downloads, extend the needle set.

## Dangerous-pattern judgments (manual, in addition to the clean scanner run)

- `ToolchainIdentity:85` `case _ => None` — malformed recorded form → no identity; `:140-142,167-174` `sys.error` in ReadWriter decoders — decode crash; inside `fromDirectory` the `NonFatal` catch maps to a named `Left`.
- `ReleaseManifestIO:97` outer `NonFatal` → named `Left`; `:118-122` per-binary `NonFatal` → `Unreadable` naming the binary; `:138` non-artifact filename → `None` (an unexpected `probatio-windows-x86_64` still surfaces via platform-coverage, not dropped).
- `ReleaseCheck:71,75,79` — unparseable identity / env lookup / arity → `sys.error`, fail-closed.
- `ReleaseValidator:138` `Unreadable → Undetermined` — could-not-determine, never a pass.
- `DifferentialHarness:213,219` `Try(...).getOrElse` — failure direction selects the *recorded* column / empty variance map → any real divergence surfaces as `Finding`.
- `OracleDiffRunner:196` `getOrElse(List.empty)` — malformed section skips the pre-check (conservative nit, S2).
- `SbomGenerate:28,38` — decode/arity rejection, justified.
- `SubcommandWiring:355,364` `AtomicReference.get` — memo reads, `danger-scan:allow`d; cache maps to a recomputed value on miss, never a default.
- `ReleaseManifestIOSpec:518` `case other => fail(...)` — generator-invariant rejection.
- `CallKeySpec:54` `if mutated == base then Result.success` — **the one site that maps to success**: see F1.

## Gate summary

All six spec-7 requirements PASS. One cross-spec oracle weakening flagged (F1 — CallKeySpec RL3 vacuous-skip, fix: cover leg on effective mutations) plus three soft spots recorded for the checkpoint. First-pass F1 (`archivePathIssues` job-boundary blindness) remains remediated and adversarially pinned.
