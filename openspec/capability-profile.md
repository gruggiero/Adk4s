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
| Modules | 8: `structured-llm`, `structured-llm-test-models`, `adk4s-core`, `adk4s-memory-api`, `adk4s-memory-testkit`, `adk4s-orchestration`, `adk4s-examples`, `verified` (leaf, not aggregated) | build.sbt |
| Fatal warnings | `-Werror` NOT active, BUT exhaustiveness escalation IS: `-Wconf:name=PatternMatchExhaustivity:e,name=MatchCaseUnreachable:e` in `scala3Options` — inexhaustive matches over sealed types FAIL Ring 0 (schema consequence rule). This change extends the sealed `AgentEvent` ADT, so the new variant MUST be handled by every existing match or Ring 0 fails. | build.sbt scala3Options |
| scalacOptions | `-deprecation`, `-feature`, `-unchecked`, `-Xkind-projector:underscores`, exhaustiveness `-Wconf` escalations (shared via `scala3Options` val) | build.sbt |
| Dependency management | Centralized: `project/Versions.scala` (all versions), `project/Dependencies.scala` (all ModuleIDs), `build.sbt` imports `Dependencies._` | project/*.scala |
| semanticdb | Enabled (for scalafix semantic rules: RemoveUnused, OrganizeImports) | build.sbt `semanticdbEnabled := true` |

### Module dependency graph (current)

```
adk4s-examples → adk4s-core, adk4s-orchestration, structured-llm, structured-llm-test-models
adk4s-orchestration → adk4s-core, structured-llm            (this change adds → adk4s-memory-api)
adk4s-memory-testkit → adk4s-memory-api                     (main-scope munit — behavioral laws)
adk4s-memory-api → adk4s-core                               (for Retriever/Document/RetrieverConfig)
adk4s-core → structured-llm, llm4s/core
structured-llm → llm4s/core, workflows4s-core, smithy4s (core+json)
structured-llm-test-models → structured-llm (compile->compile, smithy codegen)
verified → (leaf, Scala 3.7.2, Stainless, not aggregated)
```

This change adds a dependency `adk4s-orchestration → adk4s-memory-api` (new package `org.adk4s.orchestration.memory`). That wiring is part of the hook spec's implementation.

## Libraries

| Concern | Detected Library | Version | Notes |
|---------|-----------------|---------|-------|
| Effect system | cats-effect | 3.7.0 | All modules are effectful via `F[_]` / `IO`. `AgentRunner` is concrete `IO`-based (NOT `F[_]`-polymorphic), so the hook is `IO`-based to match. |
| Actors | none | — | No Pekko/Akka |
| HTTP | none | — | No http4s/tapir |
| Persistence | none | — | No Doobie/Skunk/DynamoDB. `CheckpointStore` is an in-process trait (`InMemoryCheckpointStore`). |
| Messaging | none | — | No Kafka |
| Streaming | fs2 (core + io) | 3.13.0 | Used in adk4s-core, adk4s-orchestration, adk4s-examples. `AgentEventEmitter` is `fs2.concurrent.Queue`-backed. |
| JSON | upickle / ujson | (transitive via llm4s) | Used directly in adk4s-core for tool JSON and in `AgentRunner` for `CheckpointState` serialization. NOT circe. |
| IDL / codegen | smithy4s (core + json) | 0.18.55 | Compile dep in structured-llm; sbt-codegen plugin on structured-llm-test-models. NOT touched by this change. |
| Refined types | none | — | No Iron/refined. `MemoryPolicy` fields are plain typed values. |
| Telemetry | none | — | No otel4s/Daut. Ring 9 skip. |
| LLM client | llm4s core | 0.3.4 (Maven Central) | `LLMClient`, `Conversation`, `Message` (`UserMessage`/`AssistantMessage`/`SystemMessage`/`ToolMessage`), `CompletionOptions`, `ToolFunction`, `ToolRegistry`, `Result[A]`. |
| Workflow engine | workflows4s-core | 0.6.2 (Maven Central) | WIO monad, WorkflowContext, event sourcing. workflows4s-bpmn 0.6.2 in examples only. NOT touched by this change. |
| Memory capability | adk4s-memory-api (project-local) | 0.1.0-SNAPSHOT | `AgentMemory[F]`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory`, `MemoryRetriever`. Shipped by archived `2026-07-05-add-memory-api` change. |
| Memory laws | adk4s-memory-testkit (project-local) | 0.1.0-SNAPSHOT | `AgentMemoryLaws` in MAIN scope (munit main-scope). Downstream backends consume it as a regular dep. NOT used by this change (the hook calls `AgentMemory`, not the laws). |
| Configuration | typesafe-config | 1.4.9 | structured-llm, test-models. PureConfig NOT a dependency. |
| Logging | logback-classic | 1.5.34 | examples only; slf4j transitive via llm4s |

## Testing

| Concern | Detected | Consequence |
|---------|----------|-------------|
| Test framework | munit 1.3.3 + munit-cats-effect 2.2.0 | Generated tests use `munit.FunSuite` / `munit.CatsEffectSuite`. NOT ScalaTest, NOT weaver. |
| Property testing | Hedgehog 0.13.1 (hedgehog-munit % Test) | Properties extend `hedgehog.munit.HedgehogSuite` with `property("…") { for x <- gen.forAll yield <Result> }`. Integrated shrinking, NO `Arbitrary` typeclass, explicit `Range` sizing. NOT ScalaCheck/munit-scalacheck. Coverage ASSERTIONS via Hedgehog `cover` (fails when a label's percentage is unmet); seed-fixing via Hedgehog fixed `Seed`. |
| Deterministic concurrency test kit | cats-effect `TestControl` (`cats.effect.unsafe.TestControl`) | Available transitively via cats-effect 3.7.0 (no extra dep needed). This change touches INTERRUPT/RESUME semantics, so its concurrency scenarios MUST use `TestControl` to drive `IO` deterministically — never wall-clock sleeps. munit-cats-effect provides `munit.CatsEffectSuite` for IO assertions. |
| Actor test kits | N/A | No actor framework detected |
| Mutation tool | sbt-stryker4s 0.21.0 + stryker4s.conf | Ring 5 available. stryker4s.conf has a fixed `mutate` list (currently `**/memory/MemoryRetriever.scala`) — MUST retarget to each spec's changed files before running. Thresholds: break=90, low=91, high=95. |
| Formal verification | Stainless (bundled jar + local Maven repo) | Ring 6 available via `verified` module (Scala 3.7.2, `stainlessEnabled := false` by default). Run with `sbt -J-Xmx6g ring6`. NOT applicable to this change (the hook is effectful `IO` wiring, not a PureScala module). |
| Model checking | none | No TLA+/Apalache. Ring 7 skip. |
| Memory test double | `InMemoryAgentMemory` (adk4s-memory-api, main scope) | Used as the `AgentMemory[IO]` implementation in hook tests — no LLM, no network. |

## Static Analysis

| Tool | Active Rules | Inactive/Excluded | Evidence |
|------|-------------|--------------------|----------|
| Scalafix | `DisableSyntax` (noVars, noThrows, noNulls, noReturns, noWhileLoops, noAsInstanceOf, noIsInstanceOf, noFinalize + custom regex: NoConfigFactory, NoSysEnv, NoSystemGetenv, NoKeywordTry/Catch/Finally), `RemoveUnused` (imports, privates, locals, patternvars), `OrganizeImports` (Merge, grouped) | Scoped guards for adk4s-core and structured-llm main sources (NoAdk4sConfig, NoPureConfigDefault, NoEnvReads) — aspirational (PureConfig not yet a dependency). `scalafixOnCompile := false` (run on demand). | .scalafix.conf |
| WartRemover | `Warts.unsafe` minus excluded set (see right) | Temporarily excluded: `TripleQuestionMark` (intentional), `Any` (s"..." interpolation false positive), `DefaultArguments`, `IterableOps`, `AsInstanceOf`, `Throw`, `Var`, `OptionPartial`, `StringPlusAny`. Re-enable each as code is refactored. `verified` module: `wartremoverErrors := Seq.empty` (exempt). `adk4s-examples`: same relaxed set. | build.sbt |
| scalafmt | Config present: scala3 dialect, maxColumn=120, align.preset=more | — | .scalafmt.conf |

## Compile & Test Commands

| Purpose | Command |
|---------|---------|
| Main compile (all) | `sbt compile` |
| Main compile (per module) | `sbt structured-llm/compile`, `sbt adk4s-core/compile`, `sbt adk4s-memory-api/compile`, `sbt adk4s-memory-testkit/compile`, `sbt adk4s-orchestration/compile`, `sbt adk4s-examples/compile`, `sbt structured-llm-test-models/compile` |
| Test compile (typed contracts) | `sbt <module>/Test/compile` |
| Run tests (all) | `sbt test` |
| Run tests (per module) | `sbt adk4s-core/test`, `sbt adk4s-orchestration/test`, `sbt adk4s-memory-api/test`, `sbt structured-llm/test` |
| Single test | `sbt "testOnly org.adk4s.orchestration.memory.MemoryHookSpec"` |
| Lint (scalafix check) | `sbt scalafixAll --check` |
| Lint (scalafix apply) | `sbt scalafixAll` |
| Format check | `sbt scalafmtCheck` |
| Format apply | `sbt scalafmt` |
| Mutation (Ring 5) | Retarget `stryker4s.conf` `mutate` list to changed files, then `sbt "adk4s-orchestration/stryker4s"` |
| Formal verification (Ring 6) | `sbt -J-Xmx6g ring6` (NOT used by this change) |
| Coverage | `sbt coverage test coverageReport` |
| Fat JAR | `sbt assembly` |

## Typed Contract Placement

- Contract location pattern: `<module>/src/test/scala/<pkg>/typecontract/<SpecName>TypeContract.scala`
- This change's contracts land in `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/typecontract/` (hook) and `adk4s-core/src/test/scala/org/adk4s/core/interrupt/typecontract/` (events).
- Compile command: `sbt adk4s-orchestration/Test/compile`, `sbt adk4s-core/Test/compile`
- The `verified` module is NOT for typed contracts — it is a leaf module pinned to Scala 3.7.2 for Stainless only. Typed contracts go in the owning module's test sources.

## Domain Purity Rules (feeds Ring 2)

| Layer/Package | Must NOT import | May import |
|---------------|-----------------|------------|
| `org.adk4s.structured.core` (pure SAP kernel) | fs2, cats-effect, llm4s LLM client, typesafe-config | stdlib, smithy4s, ujson |
| `org.adk4s.structured.sap` (parser) | fs2, cats-effect, llm4s | stdlib, smithy4s, ujson, regex |
| `org.adk4s.core.component` (effectful components) | workflows4s, logback | cats-effect, fs2, llm4s, ujson, structured-llm |
| `org.adk4s.core.tools` (tool execution) | workflows4s | cats-effect, fs2, llm4s, ujson |
| `org.adk4s.core.interrupt` (events) | workflows4s, llm4s LLM client, adk4s-orchestration | cats-effect, fs2, adk4s-core.error |
| `org.adk4s.memory` (memory capability) | workflows4s, llm4s LLM client, fs2-io, adk4s-orchestration | cats-effect, fs2-core, adk4s-core (Retriever/Document) |
| `org.adk4s.memory.testkit` (laws) | workflows4s, llm4s LLM client, adk4s-orchestration | cats-effect, munit (main), adk4s-memory-api |
| `org.adk4s.orchestration.memory` (NEW — this change) | workflows4s, llm4s LLM client, logback, http | cats-effect, fs2, adk4s-orchestration.agent, adk4s-core.interrupt, adk4s-memory-api, llm4s `Message` types (for context injection only) |
| `org.adk4s.orchestration.*` (workflow layer) | logback, http | cats-effect, fs2, workflows4s, adk4s-core, structured-llm |
| `org.adk4s.examples.*` (application edge) | — | everything (examples are edge code) |
| `org.adk4s.verified` (Ring 6 model) | everything project-local (leaf module) | stdlib, Stainless library only |
| Generated smithy4s code | excluded from checks | — |

The new `org.adk4s.orchestration.memory` package is the key Ring 2 boundary this change introduces: it MAY depend on `adk4s-orchestration.agent` (decorates `AgentRunner`), `adk4s-core.interrupt` (emits `AgentEvent`), and `adk4s-memory-api` (calls `AgentMemory`). It MUST NOT reach into `workflows4s`, the llm4s LLM client, or `adk4s-core.tools`. llm4s `Message` types are allowed only for context-injection message construction (the hook prepends/appends a `UserMessage`).

## Ring Availability Summary

| Ring | Available? | If unavailable: impact / setup task |
|------|-----------|--------------------------------------|
| 0 Compile | ✅ | `sbt compile` — all 8 modules. Exhaustiveness escalation active — the new `AgentEvent` variant forces all matches to handle it. |
| 1 Lint | ✅ | Scalafix (DisableSyntax + RemoveUnused + OrganizeImports) + WartRemover (relaxed set) + scalafmt |
| 2 Architecture | ⚠️ Advisory only | No custom scalafix arch rules installed. The `org.adk4s.orchestration.memory` layer rules above are manual (enforced by code review + import audit). |
| 3 Property tests | ✅ | Hedgehog 0.13.1 via hedgehog-munit. Properties extend `HedgehogSuite`. Concurrency scenarios use `TestControl`. |
| 4 Compatibility | ⚠️ Manual | No fixture-based compatibility framework. NOT applicable to this change (no serialization/wire data touched — `AgentRunner` checkpoint serialization is unchanged). |
| 5 Mutation | ✅ | sbt-stryker4s 0.21.0 + stryker4s.conf. Retarget `mutate` list to `**/memory/MemoryHook.scala`, `**/memory/MemoryAwareRunner.scala` per spec. |
| 6 Formal | ✅ but N/A | Stainless available via `verified` module, but the hook is effectful `IO` wiring — NOT a PureScala module. Ring 6 skipped for this change. |
| 7 Model checking | ❌ | No TLA+/Apalache. Skip with stated correctness impact. |
| 8 Adversarial review | ✅ (manual — always available) | Runs BEFORE Rings 5/6/7 in the apply sequence (fresh-context reviewer). |
| 9 Telemetry | ❌ | No otel4s/Daut. Skip with stated impact. |
