# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-06
**Verification result**: 5 rows corrected (listed below)

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| JSON | Single row: "upickle/ujson (transitive via llm4s)" | Two rows: (1) JSON internal currency = `JsonValue` (= `smithy4s.Document`) 0.18.55, introduced by `migrate-json-codec`; (2) JSON llm4s boundary = upickle/ujson 4.4.3 (explicitly declared), confined by `NoUjsonIn*` Scalafix rules | `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValue.scala`, `project/Versions.scala` (`Upickle = "4.4.3"`), `.scalafix.conf` (`NoUjsonIn{Core,StructuredLLM,Optimize,Eval,Orchestration}`) |
| WartRemover version | 3.5.8 | 3.6.1 (in `project/plugins.sbt`); `project/Versions.scala` still has 3.5.8 (stale — the `sbtWartremover` val in `Dependencies.scala` is unused) | `project/plugins.sbt` line 9 |
| WartRemover `verified` exemption | `wartremoverErrors := Seq.empty` only | `wartremoverErrors := Seq.empty` + `libraryDependencies ~= (_.filterNot(_.organization == "org.wartremover"))` (3.6.1 not published for Scala 3.7.2) | `build.sbt` lines 227–236 |
| Formal verification (Testing section) | "Currently EMPTY — package doc only, no model" | "Contents: `PredictorKernel` ... `adk4s-optimize dependsOn(verified % Test)` is wired; `PredictorModelBridgeSpec` bridge test runs" | `verified/src/main/scala/org/adk4s/verified/PredictorKernel.scala` |
| Scalafix active rules | No mention of `NoUjsonIn*` rules | Added `NoUjsonIn{Core,StructuredLLM,Optimize,Eval,Orchestration}` to active rules list | `.scalafix.conf` lines 130–175 |
| Domain purity — `org.adk4s.core.component` | May import `ujson` | May import `JsonValue` (via `org.adk4s.core.json`); `ujson` confined to `org.adk4s.core.tools` and `org.adk4s.core.json` | `.scalafix.conf` `NoUjsonInCore` excludePackages |
| Domain purity — `org.adk4s.core.json` | Row did not exist | New row: boundary adapter, may import `ujson` (allowlisted) | `.scalafix.conf` `NoUjsonInCore` excludePackages |

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `adk4s-harness-api` (new module) | new sbt subproject | proposal §Approach — depends on `adk4s-core` (for `InvokableTool`, `ToolInput`/`ToolOutput`, `JsonValue`/`JsonValueCodec`), llm4s model types, smithy4s-json |
| `adk4s-harness-testkit` (new module) | new sbt subproject | proposal §Approach — depends on `adk4s-harness-api`, Hedgehog |
| `CheckpointStore[F[_]]` (F-polymorphic) | API generalization | proposal §Affected Capabilities (`specs/checkpoint-store-fpoly/spec.md`) — today's `CheckpointStore[IO]` becomes `CheckpointStore[F]` |
| `CheckpointStateV2` (wire format) | persistence format | proposal §Affected Capabilities — full-fidelity messages + `harnessState: JsonValue` + v1-read compat |

These capabilities are appended to the project profile when the change implements them (Step 12 build-dependency delta). The new modules will add two rows to the module dependency graph and two rows to the Libraries table.

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R5 mutation | yes | Retarget `stryker4s.conf` `mutate` list to changed production files: `**/harness/**/*.scala` (harness-api), `**/orchestration/agent/HarnessAgent.scala`, `**/orchestration/interrupt/CheckpointStore*.scala`, `**/orchestration/agent/CheckpointStateV2.scala`. Threshold 90% (pure domain in harness-api) / 85% (loop + checkpoint adapters in orchestration). |
| R6 formal | yes | `HarnessState` get/set coherence is a pure kernel expressible in PureScala — candidate for the `verified` mirror module. The semilattice merge laws (L11) are likewise pure. Bridge test in `adk4s-harness-api % Test` via `dependsOn(verified % Test)` (following the `adk4s-optimize` precedent). |
| R9 telemetry | no | No otel4s/Daut. Skip with stated impact — `AgentEvent` emission stays in the loop unchanged; no new telemetry signals. |
| Concurrency kit | yes | `cats.effect.unsafe.TestControl` available transitively via cats-effect 3.7.0. Parallel tool-call merge and sub-agent `mergeBack` scenarios MUST use `TestControl` to drive `IO` deterministically. The deterministic `ChatModel` double in `adk4s-harness-testkit` provides the L0 gatekeeper. |
| Code intelligence | yes | Metals MCP endpoint at `http://localhost:8394/mcp` (per-project instance). Apply phase prefers semantic recipes (impact-scan, removal-audit) over grep; git grep is the fallback. |
