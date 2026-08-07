# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-06 (semantic scanner)
**Consistency check**: 1 stale row fixed (listed below)

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| `AgentEvent` | Variants listed as `MessageOutput, ToolCallRequested, ToolCallCompleted, IterationCompleted, Interrupted, ErrorOccurred, TokenDelta` with note "EXTENDED by this change (events spec adds `MemoryRecalled`, `MemoryWritten`)" | Variants updated to include `MemoryRecalled`, `MemoryWritten`; provenance updated to "pre-existing (`MemoryRecalled`/`MemoryWritten` shipped by archived `2026-07-19-add-memory-orchestration-hook`)" | Yes — the "EXTENDED by this change" note referred to the now-archived memory hook change, not the current change. The variants are in the source (confirmed by scanner). |

## Behavioral Concepts (registry pass)

**registry-check.sh**: `OK (697 implementation-map tokens verified, 0 spec concept references checked, 2 weak binding(s) to tighten)` — run on 2026-08-06. The 2 WEAK rows are pre-existing in `react-agent.md` (`isDefined`, `foreach` not in the cited `ReactAgent.scala` but exist elsewhere); they are NOT caused by this change and are not blocking.
**Stale implementation-map rows**: none
**Unregistered actions / syncs / state components**: none

## Concepts relevant to THIS change

### Reused (existing in inventory)

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `InterruptSignal` | sealed trait | `org.adk4s.core.interrupt` | reuse — HITL middleware raises `InterruptSignal.Stateful` via existing machinery |
| `AgentEvent` / `AgentEventEmitter` | sealed trait / class | `org.adk4s.core.interrupt` | reuse — event emission stays in the loop, middlewares observe via wrapping |
| `RunResult` | sealed trait | `org.adk4s.orchestration.agent` | reuse — `HarnessAgent.generate` returns a `HarnessResult` carrying state |
| `CheckpointStore` | trait | `org.adk4s.orchestration.interrupt` | reuse + generalize — today's `CheckpointStore[IO]` becomes `CheckpointStore[F]` |
| `CheckpointState` | case class | `org.adk4s.orchestration.agent` | replace — replaced by `CheckpointStateV2` (full-fidelity messages + `harnessState`) |
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` | reuse — `baseModelStep` delegates to `ChatModel.generate`; `wrapModelCall` wraps it |
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` | reuse — harness tools are `InvokableTool[F]` |
| `ToolInput` / `ToolOutput` | case class | `org.adk4s.core.tools` | reuse — `ToolCallCtx`/`ToolCallOut` thread `HarnessState` alongside |
| `AdkError` / `AgentInterruptedException` | sealed trait / variant | `org.adk4s.core.error` | reuse + extend — `StateDecodeError` extends the sealed hierarchy |
| `AgentMemory[F[_]]` / `AgentMemoryLaws` | trait / testkit | `org.adk4s.memory` | reuse (pattern) — precedent for the capability-module + testkit pattern |
| `JsonValue` | type alias (`smithy4s.Document`) | `org.adk4s.core.json` | reuse — serialization currency for `HarnessState.snapshot`/`restore`, `CheckpointStateV2.harnessState` |
| `JsonValueCodec` | object | `org.adk4s.core.json` | reuse — boundary adapter for codec typeclass |
| `ReactAgent` | class | `org.adk4s.orchestration.agent` | refactor — re-expressed as empty-stack `HarnessAgent` |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` | refactor — `resume` gains `HarnessState.restore` |

### Introduced (NOT yet in inventory — added during apply Step 12)

| Concept | Kind | Package |
|---------|------|---------|
| `AgentMiddleware[F[_]]` | trait | `org.adk4s.harness` (new module `adk4s-harness-api`) |
| `HarnessState` | final class | `org.adk4s.harness` |
| `StateCell[A]` | final class | `org.adk4s.harness` |
| `CellVisibility` | enum | `org.adk4s.harness` |
| `StateCell.CellId` | opaque type (`String`) | `org.adk4s.harness` |
| `MiddlewareName` | opaque type (`String`) | `org.adk4s.harness` |
| `ModelRequest[F]` / `ModelResponse` | case class | `org.adk4s.harness` |
| `ModelStep[F]` | type alias (`Kleisli[F, ModelRequest[F], ModelResponse]`) | `org.adk4s.harness` |
| `ToolCallCtx` / `ToolCallOut` | case class | `org.adk4s.harness` |
| `ToolStep[F]` | type alias (`Kleisli[F, ToolCallCtx, ToolCallOut]`) | `org.adk4s.harness` |
| `SystemPrompt` / `PromptSection` | case class | `org.adk4s.harness` |
| `MiddlewareStack[F]` | final case class | `org.adk4s.harness` |
| `StackError` | enum | `org.adk4s.harness` |
| `StateDecodeError` | sealed trait (`AdkError` variant) | `org.adk4s.harness` (or `org.adk4s.core.error`) |
| `HarnessAgent[F[_]]` | final class | `org.adk4s.orchestration.agent` (refactored) |
| `HarnessResult` | sealed trait / case class | `org.adk4s.orchestration.agent` |
| `CheckpointStateV2` | case class (derives ReadWriter) | `org.adk4s.orchestration.agent` |
| `AgentMiddlewareLaws` | testkit class | `org.adk4s.harness.testkit` (new module `adk4s-harness-testkit`) |
| `SemilatticeLaws` | testkit property | `org.adk4s.harness.testkit` |

## Scanner summary

```
Concept scan complete (verified openspec/concept-inventory.md):
- 5 opaque types
- 62 sealed types
- 291 case classes
- 15 service traits
- 45 smithy models
- 118 generators
- stale rows fixed: 1 (provenance preserved)
```
