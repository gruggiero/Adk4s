# Capability Profile

<!-- PROJECT-SCOPED LIVING DOCUMENT — lives at openspec/capability-profile.md
     (sibling of the openspec/concepts/ registry), NOT in a change directory.
     Each change's capability-check artifact verifies and refreshes it.
     Seeded 2026-07-18 from the add-memory-orchestration-hook change's
     profile (schema v6 migration); change-specific remarks below ("this
     change ...") refer to that change and are pruned as rows are refreshed.

     DETECTED project capabilities. Populated by inspecting build.sbt,
     project/plugins.sbt, project/Versions.scala, project/Dependencies.scala,
     source code, and tool configs — NEVER assumed.
     All later artifacts (specs, design, apply phase) must generate code
     and tests for THIS stack. If this file disagrees with openspec/config.yaml,
     this file wins — update config.yaml. -->

## Build & Language

| Item | Detected Value | Evidence (file) |
|------|---------------|-----------------|
| Scala version | 3.8.4 (main modules); 3.7.2 (`verified` module — Stainless frontend pin) | build.sbt, project/Versions.scala |
| sbt version | 1.12.12 | project/build.properties |
| JDK | 26 (Homebrew OpenJDK) | runtime |
| Modules | 17: `structured-llm`, `structured-llm-test-models`, `adk4s-core`, `adk4s-harness-api`, `adk4s-harness-testkit`, `adk4s-memory-api`, `adk4s-memory-testkit`, `adk4s-optimize`, `adk4s-orchestration`, `adk4s-eval`, `adk4s-examples`, `adk4s-record`, `verified` (leaf, not aggregated), `probatio-core`, `probatio-cli`, `sbt-probatio`, `probatio-verified` (leaf, not aggregated). Plus `probatio-spike` (throwaway V1/V2 spike, not aggregated, excluded from dependency-lint). The 5 probatio modules (4 aggregated-or-leaf + `probatio-spike`) live under `workflow/` (`workflow/core`, `workflow/cli`, `workflow/plugin`) and `verified/probatio`; they are isolated from all adk4s code by R-ARCH1 (no cats/cats-effect/fs2/llm4s/workflows4s/adk4s deps). Re-verified 2026-09-20 by `repair-probatio-cutover` capability-check: `build.sbt` declares exactly 18 `lazy val … = project` definitions — the 17 listed plus `probatio-spike`; the set is unchanged since 2026-08-25. | build.sbt |
| Fatal warnings | `-Werror` NOT active, BUT exhaustiveness escalation IS: `-Wconf:name=PatternMatchExhaustivity:e,name=MatchCaseUnreachable:e` in `scala3Options` — inexhaustive matches over sealed types FAIL Ring 0 (schema consequence rule). Any change extending a sealed ADT (e.g. `AgentEvent`, `AdkError`) MUST handle the new variant in every existing match or Ring 0 fails. | build.sbt scala3Options |
| scalacOptions | `-deprecation`, `-feature`, `-unchecked`, `-Xkind-projector:underscores`, exhaustiveness `-Wconf` escalations (shared via `scala3Options` val) | build.sbt |
| Dependency management | Centralized: `project/Versions.scala` (all versions), `project/Dependencies.scala` (all ModuleIDs), `build.sbt` imports `Dependencies._` | project/*.scala |
| semanticdb | Enabled (for scalafix semantic rules: RemoveUnused, OrganizeImports) | build.sbt `semanticdbEnabled := true` |

### Module dependency graph (current)

```
adk4s-examples → adk4s-core, adk4s-orchestration, structured-llm, structured-llm-test-models, adk4s-eval
adk4s-examples % Test → adk4s-memory-testkit                (test-scope — FileBackedAgentMemorySpec laws; landed by archived 2026-07-26-add-cross-run-memory-example)
adk4s-eval → structured-llm                                  (eval harness; landed by add-eval-core)
adk4s-eval % Test → cats-effect-testkit                      (TestControl for deterministic concurrency)
adk4s-orchestration → adk4s-core, structured-llm, adk4s-memory-api, adk4s-harness-api, adk4s-harness-testkit % Test
adk4s-record → adk4s-core, verified % Test                   (deterministic call recording; landed by add-adk4s-record spec 1)
adk4s-harness-api → adk4s-core, verified % Test               (Ring 6 bridge wired)
adk4s-harness-testkit → adk4s-harness-api, verified % Test     (main-scope munit — middleware laws)
adk4s-optimize → structured-llm, verified % Test              (Ring 6 bridge; landed by archived 2026-08-01-add-optimizable-surface)
adk4s-memory-testkit → adk4s-memory-api                     (main-scope munit — behavioral laws)
adk4s-memory-api → adk4s-core                               (for Retriever/Document/RetrieverConfig)
adk4s-core → structured-llm, llm4s/core
structured-llm → llm4s/core, workflows4s-core, smithy4s (core+json)
structured-llm-test-models → structured-llm (compile->compile, smithy codegen)
verified → (leaf, Scala 3.7.2, Stainless, not aggregated)
probatio-core → probatio-verified % Test (Scala 3.8.4, pure, R-ARCH1 isolated)
probatio-cli → probatio-core (Scala 3.8.4, mainargs + os-lib + uPickle, NativeImagePlugin)
sbt-probatio → (Scala 2.12.20, sbt 1.x AutoPlugin, links NO probatio-core code — R-S1)
probatio-verified → (leaf, Scala 3.7.2, Stainless, not aggregated)
probatio-spike → (throwaway V1/V2 spike, not aggregated, not production)
```

## Libraries

| Concern | Detected Library | Version | Notes |
|---------|-----------------|---------|-------|
| Effect system | cats-effect | 3.7.0 | All modules are effectful via `F[_]` / `IO`. `AgentRunner` is concrete `IO`-based (NOT `F[_]`-polymorphic), so the hook is `IO`-based to match. |
| Actors | none | — | No Pekko/Akka |
| HTTP | none | — | No http4s/tapir |
| Persistence | none | — | No Doobie/Skunk/DynamoDB. `CheckpointStore` is an in-process trait (`InMemoryCheckpointStore`). |
| Messaging | none | — | No Kafka |
| Streaming | fs2 (core + io) | 3.13.0 | Used in adk4s-core, adk4s-orchestration, adk4s-examples. `AgentEventEmitter` is `fs2.concurrent.Queue`-backed. |
| JSON (internal currency) | smithy4s `Document` (aliased as `JsonValue`) | 0.18.55 | `JsonValue` (= `smithy4s.Document`) is ADK4S's internal JSON currency, introduced by the archived `migrate-json-codec` change (2026-08-06). `JsonValue` is defined in `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValue.scala`; `JsonValueCodec` bridges to/from `ujson.Value` at the llm4s boundary. `InterruptSignal.Stateful.state` is `JsonValue`; `Retriever.Document.metadata` is `Map[String, JsonValue]`. |
| JSON (llm4s boundary) | upickle / ujson | 4.4.3 (explicitly declared; MUST match llm4s 0.3.4 transitive) | `ujson.Value` is confined to the llm4s boundary (`org.adk4s.core.json`, `org.adk4s.core.tools`) by Scalafix `NoUjsonIn*` rules. NOT circe. |
| IDL / codegen | smithy4s (core + json) | 0.18.55 | Compile dep in structured-llm; sbt-codegen plugin on structured-llm-test-models. NOT touched by this change. |
| Refined types | Iron (`iron` + `iron-cats`) + `iron-upickle` | 3.3.2 | Used in `structured-llm`, `adk4s-core`, `adk4s-harness-api` (full `iron` + `ironUpickle`); `adk4s-orchestration` (`ironUpickle` only). Established refined newtypes: `NodeKey` (`NonEmpty & Not[Reserved]`), `Positive`/`NonNegative` (`numeric.Positive`/`Positive0`), `MiddlewareName` (`NonEmpty`), `StateCell.CellId` (`NonEmpty & Match["[^/]+/[^/]+"]`), `CheckpointStore.CheckpointId` (`NonEmpty`). The `add-iron-refined-types` change migrated the project's newtypes to Iron. `iron-cats` provides Eq/Show/Order instances. |
| Telemetry | none | — | No otel4s/Daut. Ring 9 skip. |
| LLM client | llm4s core | 0.3.4 (Maven Central) | `LLMClient`, `Conversation`, `Message` (`UserMessage`/`AssistantMessage`/`SystemMessage`/`ToolMessage`), `CompletionOptions`, `ToolFunction`, `ToolRegistry`, `Result[A]`. |
| Workflow engine | workflows4s-core | 0.6.2 (Maven Central) | WIO monad, WorkflowContext, event sourcing. workflows4s-bpmn 0.6.2 in examples only. NOT touched by this change. |
| Memory capability | adk4s-memory-api (project-local) | 0.1.0-SNAPSHOT | `AgentMemory[F]`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory`, `MemoryRetriever`. Shipped by archived `2026-07-05-add-memory-api` change. |
| Memory laws | adk4s-memory-testkit (project-local) | 0.1.0-SNAPSHOT | `AgentMemoryLaws` in MAIN scope (munit main-scope). Downstream backends consume it as a regular dep. Consumed by `adk4s-examples % Test` (FileBackedAgentMemorySpec) via the archived `2026-07-26-add-cross-run-memory-example` change. |
| Harness API | adk4s-harness-api (project-local) | 0.1.0-SNAPSHOT | `ModelStep`/`ToolStep` (Kleisli middleware), `MiddlewareStack`, `StateCell`/`HarnessState`, `SystemPrompt`/`PromptSection`. Uses Iron refined types. Shipped by archived `add-harness-api-phase0` change. |
| Harness laws | adk4s-harness-testkit (project-local) | 0.1.0-SNAPSHOT | `AgentMiddlewareLaws` (L0–L10), `SemilatticeLaws` (L11), `DeterministicChatModel` (test double), Hedgehog `Generators` in MAIN scope. Downstream middleware authors consume as a regular dep. |
| Evaluation | adk4s-eval (project-local) | 0.1.0-SNAPSHOT | `Evaluate`, `Dataset`, `Example`, `Metric`/`Metrics`, `Judges`, `EvalConfig`, `Trace`/`TraceEntry`, `EvalOutcome`/`EvalError`. LLM-based evaluation harness. Shipped by archived `add-eval-core` change. |
| Configuration | typesafe-config | 1.4.9 | structured-llm, test-models. PureConfig NOT a dependency. |
| Logging | logback-classic | 1.5.34 | examples only; slf4j transitive via llm4s |
| CLI arg parsing (probatio) | mainargs | 0.7.8 | `probatio-cli` only (com.lihaoyi). NOT used by adk4s modules. |
| Filesystem (probatio) | os-lib | 0.11.8 | `probatio-core`, `probatio-cli`, `probatio-spike` only (com.lihaoyi). NOT used by adk4s modules. R-ARCH1: allowed for probatio; forbidden for adk4s. |
| Native image (probatio) | sbt-native-image | 0.4.0 | `probatio-cli` only (org.scalameta). GraalVM CE 21.0.2, `--no-fallback -O1`. |

## Testing

| Concern | Detected | Consequence |
|---------|----------|-------------|
| Test framework | munit 1.3.3 + munit-cats-effect 2.2.0 | Generated tests use `munit.FunSuite` / `munit.CatsEffectSuite`. NOT ScalaTest, NOT weaver. |
| Property testing | Hedgehog 0.13.1 (hedgehog-munit % Test) | Properties extend `hedgehog.munit.HedgehogSuite` with `property("…") { for x <- gen.forAll yield <Result> }`. Integrated shrinking, NO `Arbitrary` typeclass, explicit `Range` sizing. NOT ScalaCheck/munit-scalacheck. Coverage ASSERTIONS via Hedgehog `cover` (fails when a label's percentage is unmet); seed-fixing via Hedgehog fixed `Seed`. |
| Deterministic concurrency test kit | cats-effect `TestControl` (`cats.effect.unsafe.TestControl`) | Available transitively via cats-effect 3.7.0 (no extra dep needed). Any change touching concurrency/timeouts/cancellation/interruption MUST use `TestControl` to drive `IO` deterministically — never wall-clock sleeps. munit-cats-effect provides `munit.CatsEffectSuite` for IO assertions. |
| Actor test kits | N/A | No actor framework detected |
| Mutation tool | sbt-stryker4s 0.21.0 + stryker4s.conf | Ring 5 available. stryker4s.conf has a fixed `mutate` list (currently SIX files, the spec-9 PASS B target: `**/probatio/packaging/{BudgetVerdict,LatencyMeasurement,ReleaseCheck,ReleaseManifestIO,ReleaseValidator}.scala` and `**/probatio/cli/SubcommandWiring.scala`; **no `test-filter` key is present** — re-verified 2026-09-20 by `repair-probatio-cutover` capability-check, supersedes the ONE-file + test-filter state recorded 2026-08-29) — MUST retarget `mutate` to each spec's changed files before running, and add a `test-filter` only when a spec needs one (its absence means Stryker's per-mutant coverage mapping selects the covering tests). **Stryker4s collects coverage for MAIN sources only**: where the implementation lives in test sources (the `migration`/`guard` packages), the target files must be moved to main sources for the run and moved back — recorded in the conf's own header. Thresholds AS CONFIGURED: `high = 90`, `low = 80`, `break = 0` (re-verified 2026-08-29; supersedes the previously recorded break=90/low=91/high=95, which the file has never contained). `break = 0` means Stryker NEVER fails the build on score — a low score is a REPORT, not a gate, so a spec claiming Ring 5 green must read the score, not the exit code. Ring 5 covers **Scala only**: there is no mutation tooling for the workflow's bash scripts. |
| Formal verification | Stainless (bundled jar + local Maven repo) | **Frontend Scala version**: 3.7.2 (the `verified` and `probatio-verified` leaf modules are pinned to it; the rest of the build stays on 3.8.4 — a version mismatch is NOT "Ring 6 unavailable", it is why the mirror is a separately-pinned leaf). **Mirror modules**: `verified/` (StainlessPlugin, `stainlessEnabled := false` by default, not aggregated) and `verified/probatio/` (`probatio-verified`, same setup). Alias `sbt -J-Xmx6g ring6` enables BOTH mirrors. **Contents**: `verified/` has `PredictorKernel` (predictor-enumeration mirror, landed by archived `2026-08-01-add-optimizable-surface`); `probatio-verified/` has NINE PureScala objects (re-verified 2026-09-20 by `repair-probatio-cutover`; supersedes the FOUR recorded 2026-08-29): `ConformanceModel` (ledger-record/chain-state/gate-hookjson contract conformance mirror, landed by archived `2026-08-25-port-scanner-to-probatio`), plus `ChainStateKernel`, `LedgerValidatorKernel`, `BannerEngineKernel`, `DispatchKernel`, `SpecLintKernel`, `CutoverKernel`, `ReconcileKernel` and `GateKernel` (landed across the `complete-probatio-cutover` specs). `adk4s-optimize dependsOn(verified % Test)` and `probatio-core dependsOn(probatio-verified % Test)` are wired; bridge tests run in the owning modules' ordinary `test`. Stainless 0.9.9.3 with smt-z3 fallback (Z3 4.13.4). Candidate kernels for future changes: SAP coercion/parse decisions, `ToolSchema` derivation, WIOGraph topological ordering/validation. |
| Model checking | none | No TLA+/Apalache. Ring 7 skip. |
| Memory test double | `InMemoryAgentMemory` (adk4s-memory-api, main scope) | Used as the `AgentMemory[IO]` implementation in hook tests — no LLM, no network. |

## Static Analysis

| Tool | Active Rules | Inactive/Excluded | Evidence |
|------|-------------|--------------------|----------|
| Scalafix | `DisableSyntax` (noVars, noThrows, noNulls, noReturns, noWhileLoops, noAsInstanceOf, noIsInstanceOf, noFinalize + custom regex: NoConfigFactory, NoSysEnv, NoSystemGetenv, NoKeywordTry/Catch/Finally, **NoUjsonIn{Core,StructuredLLM,Optimize,Eval,Orchestration}**), `RemoveUnused` (imports, privates, locals, patternvars), `OrganizeImports` (Merge, grouped) | Scoped guards for adk4s-core and structured-llm main sources (NoAdk4sConfig, NoPureConfigDefault, NoEnvReads) — aspirational (PureConfig not yet a dependency). `scalafixOnCompile := false` (run on demand). The `NoUjsonIn*` rules (landed by `migrate-json-codec`) confine `ujson.Value` to the llm4s boundary (`org.adk4s.core.json`, `org.adk4s.core.tools`); all other ADK4S-owned main sources must use `JsonValue`. | .scalafix.conf |
| WartRemover | `Warts.unsafe` minus excluded set (see right) | Temporarily excluded (3 only): `TripleQuestionMark` (intentional — stubs), `Any` (s"..." string interpolation false positive — StringContext.s takes `Any*`), `DefaultArguments` (valid API design feature for config case classes; 47 sites across 15 files). All other unsafe warts are ACTIVE: `IterableOps`, `AsInstanceOf`, `Throw`, `Var`, `OptionPartial`, `StringPlusAny`, etc. `verified` module: `wartremoverErrors := Seq.empty` + `libraryDependencies ~= (_.filterNot(_.organization == "org.wartremover"))` (exempt — 3.6.1 not published for Scala 3.7.2). `adk4s-examples`: same 3-exclusion relaxed set. **Version**: sbt-wartremover 3.6.1 (in `project/plugins.sbt`); `project/Versions.scala` still has 3.5.8 (stale — the `sbtWartremover` val in `Dependencies.scala` is unused). **Known issue**: `-Xss4m` in `.jvmopts` works around a `Null`-wart `StackOverflowError` on deep ASTs (see `docs/known-issues/wartremover-null-stackoverflow.md`). | build.sbt, project/plugins.sbt |
| scalafmt | Config present: scala3 dialect, maxColumn=120, align.preset=more | — | .scalafmt.conf |

## Shell / Script Tooling (workflow scripts)

<!-- Added 2026-08-08 by add-correctness-substratum. The workflow's own gate
     checks are bash, and until now the profile described only the Scala
     stack — so a change shipping bash had no detected stack to target and
     would have had to assume one. Detected, not assumed. -->

The schema ships **11 git-tracked shell scripts** under
`openspec/schemas/verified-scala3/{scanner,hooks}/`. They are production code
for the workflow: `gate.sh` runs on every session, and `spec-lint.sh` /
`registry-check.sh` / `danger-scan.sh` decide whether a spec may proceed.

| Item | Detected | Consequence |
|------|----------|-------------|
| `shellcheck` | ✅ **0.11.0** (`/home/linuxbrew/.linuxbrew/bin`) | Ring 1 for shell is **available** and gates the whole tree. **Re-established 2026-08-08 by `spec:add-correctness-substratum/correctness-invariant`, superseding the earlier baseline** ("8/11 clean; 5 findings; `metals-call.sh` SC2034 possibly dead code"): all five findings now carry justifying annotations and shellcheck exits 0 over **12/12** files (11 `.sh` + `tests/helpers.bash`). The SC2034 question is answered — `init_resp` is unused deliberately; the assignment keeps the response body off stdout. Suppressions must carry a reason, mirroring the `// danger-scan:allow` convention. |
| `bats` / `bats-core` | ✅ **1.14.0** (`/home/linuxbrew/.linuxbrew/bin`) | Ring 3 for shell is **available**. Bats is the detected framework — generate `.bats` files, NOT a hand-rolled assertion runner. |
| `shfmt` | ✅ **3.13.1** (`/home/linuxbrew/.linuxbrew/bin`) | **Invoke as `shfmt -i 2 -ci`** — the project's observed convention is 2-space indent with indented `case` arms. Measured 2026-08-08 over the 11 tracked scripts: bare `shfmt` (tabs) = 1987 diff lines; `-i 2` = 690; **`-i 2 -ci` = 515**; `-i 2 -ci -sr` = 837. The setting is detected from the existing scripts, not chosen. The tree still predates the formatter, so `shfmt` gates **changed/new files only** until a one-time reformat lands. |
| `jq` | ✅ **1.6** (`/bin/jq`) | **ALLOWED** — the previous ban is lifted (see the prerequisite rule below). New scripts may parse and emit JSON with `jq` instead of hand-rolled sed/awk. |
| Existing tests for the 11 scripts | ✅ **17 `.bats` files** | `openspec/schemas/verified-scala3/tests/*.bats` (chain-state, checkpoint-from-ledger, correctness-invariant, discharge-fidelity, evidence-capture, evidence-ledger, fact-extraction, gate-payload, harness-install-verification, hook-tiers, human-grant-lock, judgment-ring-integrity, judgment-ring-provenance, oracle-ordering-lock, ambient-capture-wiring, ambient-evidence-capture, workflow-hygiene) + `tests/helpers.bash`. Re-verified 2026-08-17 by `port-scanner-to-probatio` capability-check. The `.bats` layout and conventions are ESTABLISHED — this is the oracle the porting change (R-M1) substitutes against via the `*_OVERRIDE` env seams. |
| `python3` | ✅ 3.14.5 | `scanner/openspec-graph.py` already depends on it — a soft dependency of the tooling, not of the gate checks. |
| `scala-cli` | (needed by `scanner/concept-scanner.scala`) | The semantic inventory scanner is Scalameta-based and needs scala-cli; the gate checks do not. |

**PREREQUISITE RULE (supersedes the old portability rule)** — changed
2026-08-08 by human decision (`add-correctness-substratum`).

The prior rule, from `hooks/README.md`, was *bash + git only — no JVM, no
network, no `jq`* on the reasoning that *"a check that only runs on one machine
is a check that stops running."* That reasoning is retained, but the mechanism
changes: instead of a zero-dependency constraint, the workflow now declares an
explicit, installable **prerequisite set**:

| Prerequisite | Purpose | Required by |
|---|---|---|
| `bash`, `git` | baseline | everything |
| `jq` | JSON parse/emit in hooks and scanners | `gate.sh` and future hook adapters |
| `shellcheck` | Ring 1 (shell lint) | CI + apply Step 4 |
| `bats` | Ring 3 (shell tests) | CI + apply Step 6 |
| `shfmt` | shell format check | CI + apply Step 4 |

Still excluded: **JVM** and **network** for any gate check.

Consequences of the change:
1. A shell test harness does NOT need to be hand-rolled — **use bats**.
2. `gate.sh`'s hand-rolled sed/awk JSON escaping is no longer required (it may
   be simplified to `jq`, but that is a refactor, not an obligation).
3. **`hooks/README.md` and `gate.sh`'s header comment still assert the old
   "no jq" rule and are now WRONG.** Amending them is in scope for
   `add-correctness-substratum`; leaving them is exactly the
   recorded-but-contradicted drift this workflow exists to remove.
4. The tools live in `/home/linuxbrew/.linuxbrew/bin` on this host — a CI
   runner must install them explicitly. The CI templates in
   `openspec/schemas/verified-scala3/ci/` need an install step; until they have
   one, the prerequisite set is satisfied locally but not in CI.

**CI COVERAGE** — **re-established 2026-08-08 by
`spec:add-correctness-substratum/correctness-invariant`, superseding the
earlier record** ("runs only `registry-check.sh`; `spec-lint.sh` and
`danger-scan.sh` are not CI-enforced"). All three templates now install and
**verify** the prerequisite set, then run shellcheck, shfmt (added files),
bats, `registry-check.sh` and `spec-lint.sh`. `danger-scan.sh` remains
apply-phase only: it is diff-scoped to a per-spec baseline, which CI does not
have.

**`danger-scan.sh` IS SCALA-ONLY.** It selects `git diff --name-only
<baseline> -- '*.scala' | grep '/src/main/'`, so on a change shipping no Scala
it reports "no production .scala files changed" and exits 0. That is **not
applicable — never a clean scan.** Recording it as clean is the
empty-scan-reported-as-OK shape the schema changelog names three times. A
shell-only change is judged by shellcheck plus Ring 8 instead, and its
danger-scan row is recorded N/A with this reason.

## Code Intelligence

<!-- The apply phase prefers the schema's semantic recipes over grep when
     the endpoint is running (see openspec-code-intel skill); git grep is
     the fallback and the only CI tool. Semantic answers trusted only
     post-compile. -->

| Item | Detected Value | Evidence |
|------|---------------|----------|
| Metals MCP endpoint | `http://localhost:8394/mcp` — PER-PROJECT instance (Metals is workspace-scoped; graphStore runs its own on :8395); discovery via `.metals/mcp.url`; start/stop: `openspec/schemas/verified-scala3/scanner/metals-start.sh` (auto-detects JDK 17+, free port). **STATUS 2026-09-20: NOT RUNNING.** `metals-call.sh probe` reports the endpoint unreachable and `.metals/mcp.pid` (961961) names a dead process — the recorded URL is configuration, not a live endpoint. A change that wants the semantic recipes must start the server and re-probe; otherwise git grep is the tool. | `openspec/schemas/verified-scala3/scanner/metals-call.sh probe`, `ps -p $(cat .metals/mcp.pid)` |
| Metals version | 1.6.7 (pinned — MCP tool names are not yet a stable contract) | `cs install metals-mcp` |
| JDK for Metals | 17+ required; default java on this host is 11 → set `JAVA_HOME` to the Homebrew JDK 26 | UnsupportedClassVersionError without it |
| External-dep API lookup | cellar CLI available (`~/.local/share/coursier/bin/cellar`) — use for llm4s/workflows4s APIs instead of sources-jar extraction | which cellar |
| Known limits (Metals 1.6.7) | `glob-search` needs `fileInFocus`; sealed-trait `inspect` lists members not variants; opaque-type companions may return empty usages (scripts fall back textually) | prototype session 2026-07-19 |

## Compile & Test Commands

| Purpose | Command |
|---------|---------|
| Main compile (all) | `sbt compile` |
| Main compile (per module) | `sbt structured-llm/compile`, `sbt adk4s-core/compile`, `sbt adk4s-harness-api/compile`, `sbt adk4s-harness-testkit/compile`, `sbt adk4s-memory-api/compile`, `sbt adk4s-memory-testkit/compile`, `sbt adk4s-optimize/compile`, `sbt adk4s-orchestration/compile`, `sbt adk4s-eval/compile`, `sbt adk4s-examples/compile`, `sbt structured-llm-test-models/compile`, `sbt probatio-core/compile`, `sbt probatio-cli/compile`, `sbt sbt-probatio/compile`, `sbt probatio-verified/compile` |
| Test compile (typed contracts) | `sbt <module>/Test/compile` |
| Run tests (all) | `sbt test` |
| Run tests (per module) | `sbt adk4s-core/test`, `sbt adk4s-orchestration/test`, `sbt adk4s-harness-api/test`, `sbt adk4s-harness-testkit/test`, `sbt adk4s-memory-api/test`, `sbt adk4s-memory-testkit/test`, `sbt adk4s-optimize/test`, `sbt adk4s-eval/test`, `sbt structured-llm/test`, `sbt probatio-core/test`, `sbt probatio-cli/test`, `sbt sbt-probatio/test` |
| Single test | `sbt "testOnly <fully.qualified.Spec>"` |
| Lint (scalafix check) | `sbt scalafixAll --check` |
| Lint (scalafix apply) | `sbt scalafixAll` |
| Format check | `sbt scalafmtCheck` |
| Format apply | `sbt scalafmt` |
| Mutation (Ring 5) | Retarget `stryker4s.conf` `mutate` list to changed files, then `sbt "<module>/stryker4s"` |
| Formal verification (Ring 6) | `sbt -J-Xmx6g ring6` — verifies the `verified` leaf module's PureScala mirrors. Bridge tests that bind shipped code to a mirror run in the owning module's ordinary `test` (model compiled, not re-verified). |
| Coverage | `sbt coverage test coverageReport` |
| Fat JAR | `sbt assembly` |
| Native binary (probatio) | `sbt probatio-cli/nativeImage` → `workflow/cli/target/native-image/probatio` (GraalVM CE 21.0.2, `--no-fallback -O1`) |
| Dependency lint (probatio) | `sbt probatioDependencyLint` — R-ARCH1: no adk4s/cats/cats-effect/fs2/llm4s/workflows4s/scalacheck in `workflow/*`; no `org.sinemenda.probatio` cross-dep in sbt-probatio |

## Typed Contract Placement

- Contract location pattern: `<module>/src/test/scala/<pkg>/typecontract/<SpecName>TypeContract.scala`
- Compile command: `sbt <module>/Test/compile` for the owning module.
- The `verified` module is NOT for typed contracts — it is a leaf module pinned to Scala 3.7.2 for Stainless only. Typed contracts go in the owning module's test sources.

## Domain Purity Rules (feeds Ring 2)

| Layer/Package | Must NOT import | May import |
|---------------|-----------------|------------|
| `org.adk4s.structured.core` (pure SAP kernel) | fs2, cats-effect, llm4s LLM client, typesafe-config | stdlib, smithy4s, ujson |
| `org.adk4s.structured.sap` (parser) | fs2, cats-effect, llm4s | stdlib, smithy4s, ujson, regex |
| `org.adk4s.core.component` (effectful components) | workflows4s, logback | cats-effect, fs2, llm4s, JsonValue (via `org.adk4s.core.json`), structured-llm |
| `org.adk4s.core.tools` (tool execution) | workflows4s | cats-effect, fs2, llm4s, ujson (llm4s boundary — `NoUjsonInCore` allowlisted) |
| `org.adk4s.core.json` (JsonValue codec bridge) | workflows4s, llm4s LLM client | cats-effect, smithy4s, ujson (boundary adapter — `NoUjsonInCore` allowlisted) |
| `org.adk4s.core.interrupt` (events) | workflows4s, llm4s LLM client, adk4s-orchestration | cats-effect, fs2, adk4s-core.error |
| `org.adk4s.memory` (memory capability) | workflows4s, llm4s LLM client, fs2-io, adk4s-orchestration | cats-effect, fs2-core, adk4s-core (Retriever/Document) |
| `org.adk4s.memory.testkit` (laws) | workflows4s, llm4s LLM client, adk4s-orchestration | cats-effect, munit (main), adk4s-memory-api |
| `org.adk4s.harness` (middleware/harness API) | workflows4s, llm4s LLM client, adk4s-orchestration | cats-effect, adk4s-core, Iron (refined types), upickle |
| `org.adk4s.harness.testkit` (middleware laws) | workflows4s, llm4s LLM client, adk4s-orchestration | cats-effect, munit (main), hedgehog (main), adk4s-harness-api |
| `org.adk4s.eval` (evaluation harness) | workflows4s, llm4s LLM client, adk4s-core, adk4s-orchestration | cats-effect, fs2-core, structured-llm |
| `org.adk4s.optimize` (optimizable surface) | workflows4s, llm4s LLM client, adk4s-core, adk4s-orchestration | cats-effect, fs2-core, structured-llm, ujson, munit (main), hedgehog (main) |
| `org.adk4s.orchestration.memory` (memory orchestration hook) | workflows4s, llm4s LLM client, logback, http | cats-effect, fs2, adk4s-orchestration.agent, adk4s-core.interrupt, adk4s-memory-api, llm4s `Message` types (for context injection only) |
| `org.adk4s.orchestration.*` (workflow layer) | logback, http | cats-effect, fs2, workflows4s, adk4s-core, structured-llm |
| `org.adk4s.examples.*` (application edge) | — | everything (examples are edge code) |
| `org.adk4s.verified` (Ring 6 model) | everything project-local (leaf module) | stdlib, Stainless library only |
| `org.sinemenda.probatio.core` (ported logic) | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck | stdlib, os-lib, ujson (upickle), `probatio-verified % Test` |
| `org.sinemenda.probatio.cli` (CLI entrypoints) | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck | stdlib, os-lib, ujson (upickle), mainargs, `probatio-core` |
| `org.sinemenda.probatio.plugin` (sbt AutoPlugin) | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck, `probatio-core` (R-S1: links NO probatio-core code) | stdlib, sbt APIs (Scala 2.12) |
| `org.sinemenda.probatio.verified` (Ring 6 model) | everything project-local (leaf module) | stdlib, Stainless library only |
| Generated smithy4s code | excluded from checks | — |

The `org.adk4s.orchestration.memory` package is a Ring 2 boundary: it MAY depend on `adk4s-orchestration.agent` (decorates `AgentRunner`), `adk4s-core.interrupt` (emits `AgentEvent`), and `adk4s-memory-api` (calls `AgentMemory`). It MUST NOT reach into `workflows4s`, the llm4s LLM client, or `adk4s-core.tools`. llm4s `Message` types are allowed only for context-injection message construction (the hook prepends/appends a `UserMessage`).

## Ring Availability Summary

| Ring | Available? | If unavailable: impact / setup task |
|------|-----------|--------------------------------------|
| 0 Compile | ✅ | `sbt compile` — all 17 modules (13 adk4s + 4 probatio). Exhaustiveness escalation active — any new sealed-ADT variant forces all matches to handle it. The probatio modules use `probatioScalacOptions` (stricter: `-Werror`, `-Wconf:cat=deprecation:e`, `-Wconf:cat=feature:e`, `-Wsafe-init`) in addition to `scala3Options`. Re-verified 2026-09-20 against build.sbt:71–76: the list is exactly those FOUR flags — `-Wvalue:discard` appears only in the R-CS3 comment and is **not** an active flag, and the init check is `-Wsafe-init`, not `-Ysafe-init`. |
| 1 Lint | ✅ | Scalafix (DisableSyntax + RemoveUnused + OrganizeImports) + WartRemover (relaxed set) + scalafmt |
| 2 Architecture | ⚠️ Advisory only | No custom scalafix arch rules installed. The layer rules above are manual (enforced by code review + import audit). |
| 3 Property tests | ✅ | Hedgehog 0.13.1 via hedgehog-munit. Properties extend `HedgehogSuite`. Concurrency scenarios use `TestControl` — **in adk4s modules only**. `TestControl` ships with cats-effect, and R-ARCH1 forbids cats-effect on the `workflow/*` classpath, so it is unreachable from `probatio-core`/`probatio-cli`. probatio specs touching subprocesses, timeouts or clocks obtain determinism from recorded process outcomes and an injected clock seam instead — a stated substitution, not a waiver (recorded 2026-09-20 by `repair-probatio-cutover`). |
| 4 Compatibility | ⚠️ Manual | No fixture-based compatibility framework. Applies only to changes touching serialization/wire data. |
| 5 Mutation | ✅ | sbt-stryker4s 0.21.0 + stryker4s.conf. Retarget `mutate` list to each spec's changed files before running. |
| 6 Formal | ✅ available — applicability by ALGORITHMIC purity (schema v10) | Stainless via the `verified` and `probatio-verified` leaf modules. Ring 6 is NOT limited to code that is itself PureScala: where the shipped code uses `Mirror`/`inline`/`ujson`/Iron/cats/`IO`, the VERIFIED-MIRROR pattern applies — a PureScala model of the algorithm reduced to observable effect, plus a mandatory bridge property test binding shipped code to the model (templates/verified-mirror.md). **Status**: `verified/` contains `PredictorKernel` (predictor-enumeration mirror, landed by archived `2026-08-01-add-optimizable-surface`); `probatio-verified/` contains NINE objects — `ConformanceModel`, `ChainStateKernel`, `LedgerValidatorKernel`, `BannerEngineKernel`, `DispatchKernel`, `SpecLintKernel`, `CutoverKernel`, `ReconcileKernel`, `GateKernel` (contract-conformance + verdict-logic mirrors; re-verified 2026-09-20). `adk4s-optimize dependsOn(verified % Test)` and `probatio-core dependsOn(probatio-verified % Test)` are wired. Stainless 0.9.9.3 with smt-z3 fallback (Z3 4.13.4). |
| 7 Model checking | ❌ | No TLA+/Apalache. Skip with stated correctness impact. |
| 8 Adversarial review | ✅ (manual — always available) | Runs BEFORE Rings 5/6/7 in the apply sequence (fresh-context reviewer). |
| 9 Telemetry | ❌ | No otel4s/Daut. Skip with stated impact. |

### Ring availability for SHELL components (workflow scripts)

<!-- Added 2026-08-08 by add-correctness-substratum. The rows above describe
     the Scala stack only. A change that ships bash needs its own row set,
     or it silently inherits Scala verdicts that do not apply to it. -->

| Ring | Available for shell? | Note |
|------|---------------------|------|
| 0 Compile | ⚠️ substitute | No compiler. `bash -n <script>` is the syntax gate; `schema.yaml` must parse as YAML and `openspec` must still load the schema. |
| 1 Lint | ✅ | `shellcheck` 0.11.0 on every changed script + `shfmt -d` for formatting. Catches the quoting/word-splitting class that `bash -n` misses — the dominant bash defect class, and precisely where hand-rolled string surgery in `gate.sh` lives. |
| 2 Architecture | ⚠️ substitute | No layer graph. The binding rule is the PREREQUISITE RULE above (declared set only; no JVM, no network), asserted per changed script. |
| 3 Behavioural tests | ✅ **framework available, no tests yet** | bats 1.14.0. Generate `.bats` files. Zero tests exist for the 11 scripts, so the first bash change also establishes the layout/conventions — but this is now a convention gap, NOT a missing capability. |
| 4 Compatibility | ⚠️ manual | Applies when a script reads/writes a persisted format (e.g. a ledger file): round-trip + old-fixture parse. Hand-written as `.bats` cases; no fixture framework. |
| 5 Mutation | ❌ | No mutation tooling for bash. Skip with stated impact. |
| 6 Formal | ❌ | Stainless is Scala-only. N/A for shell. |
| 7 Model checking | ❌ | No TLA+/Apalache. |
| 8 Adversarial review | ✅ | Manual, fresh-context. No longer the *only* substantive ring for shell now that 1 and 3 are available. |
| 9 Telemetry | ❌ | N/A. |

**CI COVERAGE** — all three CI templates (`github-actions.yml`,
`azure-pipelines.yml`, `gitlab-ci.yml`) now install and verify the
prerequisite set, then run `shellcheck`, `shfmt` (changed files), `bats`,
`registry-check.sh`, and `spec-lint.sh`. `danger-scan.sh` remains
apply-phase only (diff-scoped to a per-spec baseline).
