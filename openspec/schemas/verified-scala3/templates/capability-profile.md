# Capability Profile

<!-- PROJECT-SCOPED LIVING DOCUMENT — lives at openspec/capability-profile.md
     (sibling of the openspec/concepts/ registry), NOT in a change directory.
     Each change's capability-check artifact verifies and refreshes it;
     per-change copies drift and die with archiving.

     DETECTED project capabilities. Populated by inspecting build.sbt,
     project/plugins.sbt, project/Versions.scala, project/Dependencies.scala,
     source code, and tool configs — NEVER assumed.
     All later artifacts (specs, design, apply phase) must generate code
     and tests for THIS stack. If this file disagrees with openspec/config.yaml,
     this file wins — update config.yaml.

     The values below are EXAMPLES from the pilot project — replace every
     row with your project's detected values. -->

## Build & Language

| Item | Detected Value | Evidence (file) |
|------|---------------|-----------------|
| Scala version | 3.8.4 (main modules); 3.7.2 (`verified` module — Stainless frontend pin) | build.sbt, project/Versions.scala |
| sbt version | 1.12.12 | project/build.properties |
| JDK | 26 (Homebrew OpenJDK) | runtime |
| Modules | `structured-llm`, `structured-llm-test-models`, `adk4s-core`, `adk4s-orchestration`, `adk4s-examples`, `verified` (leaf, not aggregated) | build.sbt |
| Fatal warnings | `-Werror` NOT active, BUT exhaustiveness escalation IS: `-Wconf:name=PatternMatchExhaustivity:e,name=MatchCaseUnreachable:e` in `scala3Options` — inexhaustive matches over sealed types FAIL Ring 0 (schema consequence rule) | build.sbt scala3Options |
| scalacOptions | `-deprecation`, `-feature`, `-unchecked`, `-Xkind-projector:underscores`, exhaustiveness `-Wconf` escalations (shared via `scala3Options` val) | build.sbt |
| Dependency management | Centralized: `project/Versions.scala` (all versions), `project/Dependencies.scala` (all ModuleIDs), `build.sbt` imports `Dependencies._` | project/*.scala |
| semanticdb | Enabled (for scalafix semantic rules: RemoveUnused, OrganizeImports) | build.sbt `semanticdbEnabled := true` |

## Libraries

| Concern | Detected Library | Version | Notes |
|---------|-----------------|---------|-------|
| Effect system | cats-effect | 3.7.0 | All modules are effectful via `F[_]` / `IO` |
| Actors | none | — | No Pekko/Akka |
| HTTP | none | — | No http4s/tapir |
| Persistence | none | — | No Doobie/Skunk/DynamoDB |
| Messaging | none | — | No Kafka |
| Streaming | fs2 (core + io) | 3.13.0 | Used in adk4s-core, adk4s-orchestration, adk4s-examples |
| JSON | upickle / ujson | (transitive via llm4s) | Used directly in adk4s-core for tool JSON. NOT circe. |
| IDL / codegen | smithy4s (core + json) | 0.18.55 | Compile dep in structured-llm; sbt-codegen plugin on structured-llm-test-models |
| Refined types | none | — | No Iron/refined |
| Telemetry | none | — | No otel4s/Daut. Ring 9 skip or setup task. |
| LLM client | llm4s core | 0.3.4 (Maven Central) | LLMClient, Conversation, Message, ToolFunction, ToolRegistry, Result[A] |
| Workflow engine | workflows4s-core | 0.6.2 (Maven Central) | WIO monad, WorkflowContext, event sourcing. workflows4s-bpmn 0.6.2 in examples only. |
| Configuration | typesafe-config | 1.4.9 | structured-llm, test-models. PureConfig NOT a dependency. |
| Logging | logback-classic | 1.5.34 | examples only; slf4j transitive via llm4s |

## Testing

| Concern | Detected | Consequence |
|---------|----------|-------------|
| Test framework | munit 1.3.3 + munit-cats-effect 2.2.0 | Generated tests use `munit.FunSuite` / `munit.CatsEffectSuite`. NOT ScalaTest, NOT weaver. |
| Property testing | Hedgehog 0.13.1 (hedgehog-munit % Test) | Properties extend `hedgehog.munit.HedgehogSuite` with `property("…") { for x <- gen.forAll yield <Result> }`. Integrated shrinking, NO Arbitrary typeclass, explicit `Range` sizing. NOT ScalaCheck/munit-scalacheck. |
| Actor test kits | N/A | No actor framework detected |
| Mutation tool | sbt-stryker4s 0.21.0 + stryker4s.conf | Ring 5 available. stryker4s.conf has a fixed `mutate` list — MUST retarget to each spec's changed files before running. Thresholds: break=90, low=91, high=95. |
| Formal verification | Stainless 0.9.9.3 (bundled jar + local Maven repo) | Ring 6 available via `verified` module (Scala 3.7.2, `stainlessEnabled := false` by default). Run with `sbt -J-Xmx6g ring6`. |
| Model checking | none | No TLA+/Apalache. Ring 7 skip. |
| Test count | ~551 tests total (11 + 26 + 153 + 361) | Across structured-llm, structured-llm-test-models, adk4s-orchestration, adk4s-core |

## Static Analysis

| Tool | Active Rules | Inactive/Excluded | Evidence |
|------|-------------|--------------------|----------|
| Scalafix | `DisableSyntax` (noVars, noThrows, noNulls, noReturns, noWhileLoops, noAsInstanceOf, noIsInstanceOf, noFinalize + custom regex: NoConfigFactory, NoSysEnv, NoSystemGetenv, NoKeywordTry/Catch/Finally), `RemoveUnused` (imports, privates, locals, patternvars), `OrganizeImports` (Merge, grouped) | Scoped guards for adk4s-core and structured-llm main sources (NoAdk4sConfig, NoPureConfigDefault, NoEnvReads) — aspirational (PureConfig not yet a dependency) | .scalafix.conf |
| WartRemover | `Warts.unsafe` minus excluded set (see right) | Temporarily excluded: `TripleQuestionMark` (intentional), `Any` (s"..." interpolation false positive), `DefaultArguments`, `IterableOps`, `AsInstanceOf`, `Throw`, `Var`, `OptionPartial`, `StringPlusAny`. Re-enable each as code is refactored. `verified` module: `wartremoverErrors := Seq.empty` (exempt). `adk4s-examples`: same relaxed set. | build.sbt |
| scalafmt | Config present: scala3 dialect, maxColumn=120, align.preset=more | — | .scalafmt.conf |

## Code Intelligence

<!-- DETECTED, like everything else. If a Metals MCP endpoint is running (or
     startable), the apply phase prefers the schema's semantic recipes
     (scanner/metals-call.sh, impact-scan.sh, removal-audit.sh — see the
     openspec-code-intel skill) over grep for symbol questions. Git grep
     remains the fallback and the ONLY tool for restricted CI. Semantic
     answers are index-based — trusted only post-compile. -->

| Item | Detected Value | Evidence |
|------|---------------|----------|
| Metals MCP endpoint | <!-- e.g. http://localhost:8394/mcp — PER-PROJECT instance (Metals is workspace-scoped); discovery via .metals/mcp.url; start/stop: scanner/metals-start.sh --> | <!-- metals-call.sh probe --> |
| Metals version | <!-- e.g. 1.6.7 (pin: MCP tool names are not yet a stable contract) --> | <!-- cs install metals-mcp --> |
| JDK for Metals | <!-- 17+ required; metals-start.sh auto-detects from JAVA_HOME or PATH java --> | <!-- java -version --> |
| External-dep API lookup | <!-- e.g. cellar CLI available / sources-jar extraction --> | <!-- which cellar --> |

## Compile & Test Commands

<!-- The EXACT commands that genuinely compile/run code in this project.
     Typed contracts and tests are only trustworthy if compiled with these. -->

| Purpose | Command |
|---------|---------|
| Main compile (all) | `sbt compile` |
| Main compile (per module) | `sbt structured-llm/compile`, `sbt adk4s-core/compile`, `sbt adk4s-orchestration/compile`, `sbt adk4s-examples/compile`, `sbt structured-llm-test-models/compile` |
| Test compile (typed contracts) | `sbt <module>/Test/compile` |
| Run tests (all) | `sbt test` |
| Run tests (per module) | `sbt adk4s-core/test`, `sbt adk4s-orchestration/test`, `sbt structured-llm/test` |
| Single test | `sbt "testOnly org.adk4s.structured.SimpleResumeTest"` |
| Lint (scalafix check) | `sbt scalafixAll --check` |
| Lint (scalafix apply) | `sbt scalafixAll` |
| Format check | `sbt scalafmtCheck` |
| Format apply | `sbt scalafmt` |
| Mutation (Ring 5) | Retarget `stryker4s.conf` mutate list, then `sbt stryker4s` |
| Formal verification (Ring 6) | `sbt -J-Xmx6g ring6` |
| Coverage | `sbt coverage test coverageReport` |
| Fat JAR | `sbt assembly` |

## Typed Contract Placement

<!-- Where typed-contract files must live so sbt genuinely compiles them.
     Files under openspec/changes/... are NOT compiled — never place them there. -->

- Contract location pattern: `<module>/src/test/scala/<pkg>/typecontract/<SpecName>TypeContract.scala`
- Compile command: `sbt <module>/Test/compile`
- The `verified` module is NOT for typed contracts — it is a leaf module pinned to Scala 3.7.2 for Stainless only. Typed contracts go in the owning module's test sources.

## Domain Purity Rules (feeds Ring 2)

<!-- Project-specific layer constraints derived from the detected stack.
     Currently no architecture enforcement plugin is active (no custom scalafix
     arch rules). These are advisory layer rules to follow manually. -->

| Layer/Package | Must NOT import | May import |
|---------------|-----------------|------------|
| `org.adk4s.structured.core` (pure SAP kernel) | fs2, cats-effect, llm4s LLM client, typesafe-config | stdlib, smithy4s, ujson |
| `org.adk4s.structured.sap` (parser) | fs2, cats-effect, llm4s | stdlib, smithy4s, ujson, regex |
| `org.adk4s.core.component` (effectful components) | workflows4s, logback | cats-effect, fs2, llm4s, ujson, structured-llm |
| `org.adk4s.core.tools` (tool execution) | workflows4s | cats-effect, fs2, llm4s, ujson |
| `org.adk4s.orchestration.*` (workflow layer) | logback, http | cats-effect, fs2, workflows4s, adk4s-core, structured-llm |
| `org.adk4s.examples.*` (application edge) | — | everything (examples are edge code) |
| `org.adk4s.verified` (Ring 6 model) | everything project-local (leaf module) | stdlib, Stainless library only |
| Generated smithy4s code | excluded from checks | — |

## Ring Availability Summary

| Ring | Available? | If unavailable: impact / setup task |
|------|-----------|--------------------------------------|
| 0 Compile | ✅ | `sbt compile` — all 6 modules |
| 1 Lint | ✅ | Scalafix (DisableSyntax + RemoveUnused + OrganizeImports) + WartRemover (relaxed set) + scalafmt |
| 2 Architecture | ⚠️ Advisory only | No custom scalafix arch rules installed. Layer rules in table above are manual. |
| 3 Property tests | ✅ | Hedgehog 0.13.1 via hedgehog-munit. Properties extend `HedgehogSuite`. |
| 4 Compatibility | ⚠️ Manual | No fixture-based compatibility framework. Test round-trips manually for serialization changes. |
| 5 Mutation | ✅ | sbt-stryker4s 0.21.0 + stryker4s.conf. Retarget `mutate` list per spec. |
| 6 Formal | ✅ | Stainless 0.9.9.3 via `verified` module (Scala 3.7.2). Run `sbt -J-Xmx6g ring6`. |
| 7 Model checking | ❌ | No TLA+/Apalache. Skip with stated correctness impact. |
| 8 Adversarial review | ✅ (manual — always available) | |
| 9 Telemetry | ❌ | No otel4s/Daut. Skip with stated impact, or add setup task. |
