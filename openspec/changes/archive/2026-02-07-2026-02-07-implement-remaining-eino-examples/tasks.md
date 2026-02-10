# Tasks: Implement Remaining Eino Examples

## Phase 1: BatchExecutor (unlocks `compose/batch`)
*Estimated effort: 1-2 days*

- [x] 1.1 Add `BatchExecutor[F, I, O]` trait to `adk4s-core/batch/BatchExecutor.scala`
  - `invokeAll(inputs: List[I]): F[List[Either[Throwable, O]]]` — sequential
  - `invokeAllPar(inputs: List[I], concurrency: Int): F[List[Either[Throwable, O]]]` — parallel
  - `stream(inputs: List[I], concurrency: Int): Stream[F, Either[Throwable, O]]` — streaming
- [x] 1.2 Add `BatchExecutor.fromRunnable[I, O](runnable: Runnable[I, O]): BatchExecutor[IO, I, O]`
- [x] 1.3 Write unit tests for `BatchExecutor` (sequential, parallel, error isolation, streaming)
- [x] 1.4 Implement `batch/BatchExample.scala` (Eino: `compose/batch`)
  - Document review pipeline: sequential, concurrent, error handling, parent graph with reduce

## Phase 2: ReactAgent (unlocks `flow/agent/react`)
*Estimated effort: 2-3 days. No dependency on Phase 1.*

- [x] 2.1 Add `ReactAgent[F]` trait to `adk4s-orchestration/agent/ReactAgent.scala`
  - `generate(messages: List[Message], maxSteps: Int): F[Message]`
  - `stream(messages: List[Message], maxSteps: Int): Stream[F, Message]`
- [x] 2.2 Implement `ReactAgent.create` factory — builds WIOGraph with ChatModel → Branch → ToolsNode → Loop
- [x] 2.3 Implement streaming path: use `ChatModel.stream` for token-by-token output, interleave tool results
- [x] 2.4 Write unit tests for `ReactAgent` (generate, stream, tool execution, max steps)
- [x] 2.5 Implement `agent/ReactAgentExample.scala` (Eino: `flow/agent/react`)
  - Restaurant/dish recommender with streaming output

## Phase 3: DynamicToolRegistry (unlocks `flow/agent/react/dynamic_option`)
*Estimated effort: 1 day. Depends on Phase 2.*

- [x] 3.1 Add `DynamicToolRegistry[F]` to `adk4s-orchestration/agent/DynamicToolRegistry.scala`
  - `addTool(tool: InvokableTool[F]): F[Unit]`
  - `removeTool(name: String): F[Unit]`
  - `currentTools: F[List[InvokableTool[F]]]`
  - Uses cats-effect `Ref` for thread-safe state
- [x] 3.2 Add `DynamicToolRegistry.create` factory
- [x] 3.3 Extend `ReactAgent.create` to accept `DynamicToolRegistry` (queries tools on each loop iteration)
- [x] 3.4 Write unit tests for `DynamicToolRegistry` (add, remove, concurrent access)
- [x] 3.5 Implement `agent/DynamicOptionExample.scala` (Eino: `flow/agent/react/dynamic_option`)
  - Demonstrates adding/removing tools between invocations

## Phase 4: WIOInterruptNode + CheckpointStore (unlocks `graph/react_with_interrupt`)
*Estimated effort: 2-3 days. No dependency on Phases 1-3.*

- [x] 4.1 Add `CheckpointStore[F]` trait to `adk4s-orchestration/wiograph/CheckpointStore.scala`
  - `get(checkpointId: String): F[Option[Array[Byte]]]`
  - `set(checkpointId: String, data: Array[Byte]): F[Unit]`
- [x] 4.2 Add `InMemoryCheckpointStore.create[F]` factory
- [x] 4.3 Add `InterruptibleNode[I, O]` to `WIONode.scala`
  - `shouldInterrupt: I => Boolean` — predicate for pausing
  - `onInterrupt: I => IO[InterruptInfo]` — serialize state for external inspection
  - `onResume: (I, ResumeData) => IO[O]` — continue after human approval
  - Composes with `WIOHandleSignalNode` internally
- [x] 4.4 (Replaced by InterruptibleNode — standalone component, no WIOGraph changes needed)
- [x] 4.5 (Not needed — InterruptibleNode is standalone) in `WIOGraph.scala` to handle `WIOInterruptNode`
- [x] 4.6 Write unit tests for `InterruptibleNode` (interrupt, resume, no-interrupt passthrough)
- [x] 4.7 Write unit tests for `CheckpointStore` (get, set, in-memory)
- [x] 4.8 Implement `graph/ReactWithInterruptExample.scala` (Eino: `graph/react_with_interrupt`)
  - Ticket booking with human approval before tool execution

## Phase 5: Workflow Examples with Explicit Transforms (no new infrastructure)
*Estimated effort: 1 day. No dependencies.*

- [x] 5.1 Implement `workflow/FieldMappingWorkflowExample.scala` (Eino: `workflow/2_field_mapping`)
  - Two word-counter lambdas, input fields extracted via `WIOPureNode` transforms
  - Comment explaining Eino `MapFields`/`MapFieldPaths` equivalent
- [x] 5.2 Implement `workflow/DataOnlyWorkflowExample.scala` (Eino: `workflow/3_data_only`)
  - Adder + multiplier, parallel execution via `WIOParallelNode`, merge via `WIOPureNode`
  - Comment explaining Eino `WithNoDirectDependency` equivalent

## Phase 6: StreamFieldSplitter (unlocks `workflow/6_stream_field_map`)
*Estimated effort: 2-3 days. No dependencies.*

- [x] 6.1 Add `StreamFieldSplitter` to `adk4s-core/streaming/StreamFieldSplitter.scala`
  - `split2[I, A, B](source: Stream[F, I], extractA: I => A, extractB: I => B): (Stream[F, A], Stream[F, B])`
  - Uses fs2 `broadcastThrough` for fan-out
- [x] 6.2 Add `StreamFieldMerger` to `adk4s-core/streaming/StreamFieldMerger.scala`
  - `merge2[A, B, O](streamA: Stream[F, A], streamB: Stream[F, B], combine: (A, B) => O): Stream[F, O]`
- [x] 6.3 Add `withStaticValue` utility for injecting static values into stream elements
- [x] 6.4 Write unit tests for `StreamFieldSplitter` (split, backpressure, error propagation)
- [x] 6.5 Write unit tests for `StreamFieldMerger` (merge, element correspondence)
- [x] 6.6 Implement `workflow/StreamFieldMapExample.scala` (Eino: `workflow/6_stream_field_map`)
  - Stream of messages split into content/reasoning streams, word counting per chunk, merged output

## Phase 7: Tool Schema Inference (unlocks `components/tool`)
*Estimated effort: 2-3 days. No dependencies.*

- [x] 7.1 Add `ToolInfer` object to `adk4s-core/tools/ToolInfer.scala`
  - `inline def infer[I <: Product](name: String, description: String)(fn: I => IO[Either[String, ujson.Value]])(using Mirror.ProductOf[I]): InvokableTool[IO]`
  - Derives JSON Schema from case class fields using Scala 3 `Mirror` + inline macros
  - Supports `String`, `Int`, `Double`, `Boolean`, `Option[T]` field types
- [x] 7.2 Add `JsonFixMiddleware` to `adk4s-core/tools/JsonFixMiddleware.scala`
  - Repairs common LLM JSON errors: trailing commas, unquoted keys, single quotes
  - Implements `ToolMiddleware` trait
- [x] 7.3 Write unit tests for `ToolInfer` (flat case class, optional fields, nested case class)
- [x] 7.4 Write unit tests for `JsonFixMiddleware` (trailing comma, unquoted keys, single quotes)
- [x] 7.5 Implement `components/ToolSchemaExample.scala` (Eino: `components/tool`)
  - Demonstrates `Tool.infer` with a booking tool, JSON Schema output, middleware usage

## Phase 8: Documentation & Verification
*Depends on all previous phases.*

- [x] 8.1 Update `adk4s-examples/README.md` with all 7 new examples
- [x] 8.2 Update `adk4s-examples/run-example.sh` to support new examples
- [x] 8.3 Verify all 26 examples compile: `sbt adk4s-examples/compile`
- [x] 8.4 Run all new tests — all must pass
- [x] 8.5 Update `docs/eino-examples-convertibility-report.md` final score to 26/26
- [x] 8.6 Update `docs/remaining-eino-examples-analysis.md` to mark all items as implemented
