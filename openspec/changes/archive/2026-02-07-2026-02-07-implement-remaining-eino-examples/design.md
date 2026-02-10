## Context
The first Eino examples change implemented 19 of 26 examples. This change implements the remaining 7 by adding targeted features to close each gap. The gap analysis (`docs/remaining-eino-examples-analysis.md`) identified the recommended solution for each example.

The 7 remaining examples span 4 categories:
- **Graph**: `react_with_interrupt` (human-in-the-loop)
- **Workflow**: `2_field_mapping`, `3_data_only`, `6_stream_field_map`
- **Agent flow**: `react` (streaming), `react/dynamic_option`
- **Batch**: `compose/batch`
- **Components**: `components/tool` (schema inference)

## Goals / Non-Goals
- Goals:
  - Implement all 7 remaining Eino examples as runnable Scala programs
  - Add `BatchExecutor` for parallel batch processing with per-item error isolation
  - Add `ReactAgent` trait with streaming support (`generate` + `stream` methods)
  - Add `DynamicToolRegistry` for runtime tool reconfiguration
  - Add `WIOInterruptNode` + `CheckpointStore` for human-in-the-loop patterns
  - Add `StreamFieldSplitter` / `StreamFieldMerger` for stream-level field decomposition
  - Add `Tool.infer` for compile-time JSON Schema derivation from case classes
  - Each example must work with MockChatModel (no API key required)
  - All new features must have unit tests
  - Achieve 26/26 Eino example coverage

- Non-Goals:
  - Completing the `Workflow.compile` engine (workflow examples use WIOGraph)
  - Full `FieldMapper` DSL (field mapping examples use explicit `WIOPureNode` transforms)
  - Full `DataEdge` abstraction (data-only example uses `WIOParallelNode` composition)
  - Eino callback/tracing parity
  - MCP tool integration

## Decisions

### Decision 1: BatchExecutor uses fs2 `parEvalMap` for concurrency
**What**: `BatchExecutor` wraps a `Runnable[I, O]` and runs it over `List[I]` with bounded parallelism via fs2.
**Why**: fs2 already provides `parEvalMap` with backpressure and cancellation. No need for a custom thread pool.
**Alternatives considered**: cats-effect `parTraverseN` (rejected — no streaming output), manual `Semaphore` (rejected — reinventing fs2).

```scala
trait BatchExecutor[F[_], I, O]:
  def invokeAll(inputs: List[I]): F[List[Either[Throwable, O]]]
  def invokeAllPar(inputs: List[I], concurrency: Int): F[List[Either[Throwable, O]]]
  def stream(inputs: List[I], concurrency: Int): Stream[F, Either[Throwable, O]]

object BatchExecutor:
  def fromRunnable[I, O](runnable: Runnable[I, O]): BatchExecutor[IO, I, O]
```

### Decision 2: ReactAgent builds on WIOGraph internally
**What**: `ReactAgent` constructs a `WIOGraph` with ChatModel → Branch → ToolsNode → Loop internally.
**Why**: Reuses existing graph infrastructure. Gives BPMN visualization for free. Event-sourced execution.
**Alternatives considered**: Imperative loop (rejected — no visualization, no event sourcing), llm4s Agent wrapper (rejected — llm4s Agent is synchronous, no streaming).

```scala
trait ReactAgent[F[_]]:
  def generate(messages: List[Message], maxSteps: Int): F[Message]
  def stream(messages: List[Message], maxSteps: Int): Stream[F, Message]

object ReactAgent:
  def create(
    model: ChatModel[IO],
    tools: List[InvokableTool[IO]],
    systemPrompt: Option[String],
    maxSteps: Int
  ): IO[ReactAgent[IO]]
```

### Decision 3: DynamicToolRegistry uses cats-effect Ref
**What**: Thread-safe mutable tool list via `Ref[F, List[InvokableTool[F]]]`.
**Why**: `Ref` is the standard cats-effect primitive for concurrent mutable state. No locks needed.
**Alternatives considered**: Immutable rebuild (rejected — poor ergonomics for add/remove), `AtomicReference` (rejected — not cats-effect idiomatic).

```scala
final class DynamicToolRegistry[F[_]: Async] private (
  toolsRef: Ref[F, List[InvokableTool[F]]]
):
  def addTool(tool: InvokableTool[F]): F[Unit]
  def removeTool(name: String): F[Unit]
  def currentTools: F[List[InvokableTool[F]]]

object DynamicToolRegistry:
  def create[F[_]: Async](initial: List[InvokableTool[F]]): F[DynamicToolRegistry[F]]
```

### Decision 4: WIOInterruptNode composes with WIOHandleSignalNode
**What**: `WIOInterruptNode` is a convenience that wraps `WIOHandleSignalNode` with a predicate-based interrupt pattern.
**Why**: The workflows4s signal mechanism already supports pause/resume. This adds the "interrupt before node" ergonomic layer.
**Alternatives considered**: New workflows4s primitive (rejected — too invasive), error-based interrupt like Eino (rejected — errors should be errors, not control flow).

```scala
case class WIOInterruptNode[Ctx <: WorkflowContext, I, O](
  shouldInterrupt: I => Boolean,
  onInterrupt: I => IO[InterruptInfo],
  onResume: (I, ResumeData) => IO[O]
) extends WIONode[Ctx, I, Nothing, O]

trait CheckpointStore[F[_]]:
  def get(checkpointId: String): F[Option[Array[Byte]]]
  def set(checkpointId: String, data: Array[Byte]): F[Unit]

object InMemoryCheckpointStore:
  def create[F[_]: Sync]: F[CheckpointStore[F]]
```

### Decision 5: Workflow field mapping and data-only use explicit transforms (no DSL)
**What**: `workflow/2_field_mapping` uses `WIOPureNode` lambdas to extract/combine fields. `workflow/3_data_only` uses `WIOParallelNode` + `WIOPureNode`.
**Why**: Scala's type system makes explicit transforms type-safe and clear. A field-mapping DSL would add complexity for minimal benefit in a typed language.
**Alternatives considered**: Full `FieldMapper` DSL (rejected — YAGNI, Go's reflection-based approach doesn't translate well to Scala).

### Decision 6: StreamFieldSplitter uses fs2 broadcastThrough
**What**: `StreamFieldSplitter.split` fans out a `Stream[F, I]` into multiple derived streams, each extracting a different field. `StreamFieldMerger.merge` combines them back.
**Why**: fs2 `broadcastThrough` provides the fan-out primitive with proper backpressure.
**Alternatives considered**: Manual `Topic` (rejected — more complex), `parJoin` (rejected — doesn't preserve element correspondence).

```scala
object StreamFieldSplitter:
  def split2[F[_]: Concurrent, I, A, B](
    source: Stream[F, I],
    extractA: I => A,
    extractB: I => B
  ): (Stream[F, A], Stream[F, B])

object StreamFieldMerger:
  def merge2[F[_]: Concurrent, A, B, O](
    streamA: Stream[F, A],
    streamB: Stream[F, B],
    combine: (A, B) => O
  ): Stream[F, O]
```

### Decision 7: Tool.infer uses Scala 3 Mirror for schema derivation
**What**: `Tool.infer[I <: Product]` derives JSON Schema from case class fields at compile time using `scala.deriving.Mirror.ProductOf`.
**Why**: Compile-time derivation is type-safe and zero-overhead. No runtime reflection needed.
**Alternatives considered**: Runtime reflection (rejected — not idiomatic Scala 3), manual schema (rejected — that's what we already have).

```scala
object Tool:
  inline def infer[I <: Product](
    name: String,
    description: String
  )(fn: I => IO[Either[String, ujson.Value]])(using m: Mirror.ProductOf[I]): InvokableTool[IO]
```

## Risks / Trade-offs

- **Risk**: `ReactAgent` streaming requires `ChatModel.stream` to return tool calls in the stream
  - Mitigation: MockChatModel already supports this; document the requirement for real LLM providers

- **Risk**: `WIOInterruptNode` may not compose cleanly with `WIOGraph.toRunnable`
  - Mitigation: Test with the existing `compileRunnableFork` pattern; fall back to direct `ActiveWorkflow` execution if needed

- **Risk**: `Tool.infer` macro complexity for nested case classes
  - Mitigation: Start with flat case classes (sufficient for the example); add nested support later

- **Trade-off**: Workflow examples (#4, #5) don't use a field-mapping DSL
  - Rationale: Explicit transforms are clearer in Scala. Comments in the examples explain the Eino equivalent.

- **Trade-off**: `StreamFieldSplitter` requires `Concurrent` constraint
  - Rationale: Fan-out inherently requires concurrency; this is the correct constraint.

## File Structure

```
adk4s-core/src/main/scala/org/adk4s/core/
├── batch/
│   └── BatchExecutor.scala                    # Phase 1
├── streaming/
│   ├── StreamFieldSplitter.scala              # Phase 6
│   └── StreamFieldMerger.scala                # Phase 6
└── tools/
    ├── ToolInfer.scala                        # Phase 7 (Tool.infer)
    └── JsonFixMiddleware.scala                # Phase 7

adk4s-orchestration/src/main/scala/org/adk4s/orchestration/
├── agent/
│   ├── ReactAgent.scala                       # Phase 2
│   └── DynamicToolRegistry.scala              # Phase 3
└── wiograph/
    ├── WIOInterruptNode.scala                 # Phase 4 (or added to WIONode.scala)
    └── CheckpointStore.scala                  # Phase 4

adk4s-examples/src/main/scala/org/adk4s/examples/eino/
├── batch/
│   └── BatchExample.scala                     # Phase 1
├── agent/
│   ├── ReactAgentExample.scala                # Phase 2
│   └── DynamicOptionExample.scala             # Phase 3
├── graph/
│   └── ReactWithInterruptExample.scala        # Phase 4
├── workflow/
│   ├── FieldMappingWorkflowExample.scala      # Phase 5
│   ├── DataOnlyWorkflowExample.scala          # Phase 5
│   └── StreamFieldMapExample.scala            # Phase 6
└── components/
    └── ToolSchemaExample.scala                # Phase 7

adk4s-core/src/test/scala/org/adk4s/core/
├── batch/
│   └── BatchExecutorTest.scala
└── streaming/
    └── StreamFieldSplitterTest.scala

adk4s-orchestration/src/test/scala/org/adk4s/orchestration/
├── agent/
│   ├── ReactAgentTest.scala
│   └── DynamicToolRegistryTest.scala
└── wiograph/
    └── WIOInterruptNodeTest.scala
```

## Eino Example → adk4s Feature Mapping

| Eino Example | adk4s Feature | Phase | New Infra? |
|---|---|---|---|
| `compose/batch` | `BatchExecutor` | 1 | Yes |
| `flow/agent/react` | `ReactAgent` | 2 | Yes |
| `flow/agent/react/dynamic_option` | `DynamicToolRegistry` | 3 | Yes |
| `graph/react_with_interrupt` | `WIOInterruptNode` + `CheckpointStore` | 4 | Yes |
| `workflow/2_field_mapping` | `WIOPureNode` explicit transforms | 5 | No |
| `workflow/3_data_only` | `WIOParallelNode` + `WIOPureNode` | 5 | No |
| `workflow/6_stream_field_map` | `StreamFieldSplitter` + `StreamFieldMerger` | 6 | Yes |
| `components/tool` | `Tool.infer` + `JsonFixMiddleware` | 7 | Yes |

## Open Questions
- Should `ReactAgent` live in `adk4s-orchestration/agent` or a new `adk4s-agent` module?
- Should `BatchExecutor` support interrupt/resume per batch item (like Eino), or is per-item error isolation sufficient for now?
- Should `Tool.infer` support `Option[T]` fields as non-required JSON Schema properties?
