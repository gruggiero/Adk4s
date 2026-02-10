# Remaining Eino Examples: Gap Analysis & Proposed adk4s Implementations

## Overview

Of the 26 Eino examples, **all 26 are now implemented**. This document analyzes the 7 examples (5 partially convertible + 2 unconvertible) that required new adk4s features, and documents the implementations that were added.

> **Status: ✅ ALL IMPLEMENTED** (Feb 2026)

---

## 1. `graph/react_with_interrupt` — ReAct with Human-in-the-Loop

### What the Eino example does
- Builds a graph: ChatTemplate → ChatModel → Branch(hasToolCalls?) → ToolsNode → back to ChatModel (loop)
- **CheckPointStore**: Serializes graph state to bytes for pause/resume across invocations
- **InterruptBeforeNodes**: Pauses execution before ToolsNode, returns an error with embedded state
- **StateModifier**: On resume, the caller can modify state (e.g., edit tool call arguments)
- The user inspects tool call arguments via stdin, optionally modifies them, then resumes

### What adk4s has today
- `WIOLoopNode` for the ChatModel → ToolsNode cycle ✅
- `WIOHandleSignalNode` for external signal-based interruption ✅
- `WIOForkNode` for branching ✅
- Event-sourced state persistence via workflows4s ✅

### Gap
The workflows4s signal mechanism requires the signal to be *sent* externally, not triggered automatically before a node. There's no `InterruptBeforeNodes` equivalent that pauses execution at a specific graph position and returns control to the caller with serialized state.

### Proposed implementation

**Feature: `WIOInterruptNode[Ctx, I, O]`** — a new node type in `WIONode.scala`

```
WIOInterruptNode[Ctx, I, O](
  shouldInterrupt: I => Boolean,          // predicate: should we pause here?
  serializeState: I => IO[Array[Byte]],   // serialize current state for external inspection
  deserializeState: Array[Byte] => IO[I], // deserialize modified state on resume
  onResume: I => IO[O]                    // continue execution after human approval
)
```

This composes with the existing `WIOHandleSignalNode` internally:
1. When `shouldInterrupt` returns true, the node emits a `PendingApproval` event and waits for a `ResumeSignal`
2. The caller receives the serialized state, inspects/modifies it, then sends a `ResumeSignal` with the (possibly modified) state
3. `onResume` processes the resumed state and produces the output

**Companion feature: `CheckpointStore` trait**

```scala
trait CheckpointStore[F[_]]:
  def get(checkpointId: String): F[Option[Array[Byte]]]
  def set(checkpointId: String, data: Array[Byte]): F[Unit]
```

An in-memory implementation (`InMemoryCheckpointStore`) would suffice for the example. The workflows4s event journal already provides persistence, but a `CheckpointStore` gives a simpler API for the interrupt/resume pattern.

**Effort estimate**: Medium (2-3 days). The core signal mechanism exists; this is a convenience layer.

---

## 2. `flow/agent/react` — Streaming ReAct Agent

### What the Eino example does
- Creates a `react.NewAgent` with a ChatModel and tools (restaurant finder, dish recommender)
- Calls `rAgent.Stream()` which returns a stream of message chunks (token-by-token output)
- Uses `ExportGraph` to visualize the agent's internal graph as Mermaid
- Supports custom `StreamToolCallChecker` for models that don't return tool calls in the first chunk

### What adk4s has today
- `ReactMemoryExample` implements the ReAct loop manually using `ChatModel.generate` in a loop ✅
- `ChatModel.stream` returns `Stream[IO, Completion]` for token-by-token output ✅
- `ToolsNode` executes tools ✅

### Gap
No first-class `ReactAgent` abstraction that combines streaming, tool execution, and loop control. The current example uses `ChatModel.generate` (invoke), not `ChatModel.stream`.

### Proposed implementation

**Feature: `ReactAgent[F[_]]` in `adk4s-agent`**

```scala
trait ReactAgent[F[_]]:
  def generate(messages: Seq[Message], maxSteps: Int): F[Message]
  def stream(messages: Seq[Message], maxSteps: Int): Stream[F, Message]
  def exportGraph: WIOGraph[?, ?, ?, ?]  // for BPMN visualization
```

**Implementation: `ReactAgent.create`**

```scala
object ReactAgent:
  def create[F[_]: Async](
    model: ChatModel[F],
    tools: List[InvokableTool[F]],
    systemPrompt: Option[String],
    maxSteps: Int
  ): ReactAgent[F]
```

Internally builds a `WIOGraph` with:
- `ChatModelNode` (supports both invoke and stream)
- `WIOForkNode` (branch on `hasToolCalls`)
- `ToolsNode` (execute tools)
- `WIOLoopNode` (back to ChatModel)

The `stream` method uses `ChatModel.stream` and emits chunks as they arrive, interleaving tool execution results.

The `exportGraph` method returns the underlying `WIOGraph` for BPMN visualization (adk4s equivalent of Eino's Mermaid export).

**Effort estimate**: Medium (2-3 days). Core components exist; this is orchestration + streaming plumbing.

---

## 3. `flow/agent/react/dynamic_option` — Dynamic Agent Options

### What the Eino example does
- A ReAct agent whose tools and system prompt can be reconfigured at runtime
- Uses `react.WithDynamicOptions` to swap tools between invocations
- Demonstrates adding/removing tools without recreating the agent

### What adk4s has today
- Tools are passed at construction time to `ToolsNode` and `ChatModel`
- No runtime reconfiguration mechanism

### Gap
No dynamic tool/prompt reconfiguration. Tools and prompts are fixed at graph construction time.

### Proposed implementation

**Feature: `DynamicToolRegistry` in `adk4s-agent`**

```scala
final class DynamicToolRegistry[F[_]: Async](
  toolsRef: Ref[F, List[InvokableTool[F]]]
):
  def addTool(tool: InvokableTool[F]): F[Unit]
  def removeTool(name: String): F[Unit]
  def currentTools: F[List[InvokableTool[F]]]
  def toToolsNode: F[ToolsNode]
```

Uses a cats-effect `Ref` for thread-safe mutable state. The `ReactAgent` would accept a `DynamicToolRegistry` instead of a static tool list, and query it on each loop iteration.

**Feature: `DynamicPrompt` in `adk4s-core`**

```scala
final class DynamicPrompt[F[_]: Async](
  promptRef: Ref[F, String]
):
  def set(prompt: String): F[Unit]
  def get: F[String]
```

The `ChatTemplate` would read from `DynamicPrompt` on each invocation.

**Effort estimate**: Small (1 day). Straightforward `Ref`-based wrapper.

---

## 4. `workflow/2_field_mapping` — Struct Field Mapping

### What the Eino example does
- A workflow with two lambda nodes (`c1`, `c2`) that count word occurrences
- Input is a `message` struct with `Message.Content`, `Message.ReasoningContent`, and `SubStr` fields
- `MapFields("SubStr", "SubStr")` maps the input's `SubStr` to the lambda's `SubStr` parameter
- `MapFieldPaths(["Message", "Content"], ["FullStr"])` maps a nested field path to a flat parameter
- Output is a `map[string]any` with keys `content_count` and `reasoning_content_count`

### What adk4s has today
- `WIOPureNode` can transform between types manually ✅
- No automatic struct field mapping DSL

### Gap
No declarative field-mapping DSL. Users must write explicit `WIOPureNode` transforms to extract/combine fields.

### Proposed implementation

**Feature: `FieldMapper[I, O]` in `adk4s-orchestration`**

A compile-time-safe field mapping DSL using Scala 3 macros or Shapeless-style derivation:

```scala
object FieldMapper:
  // Map a single field
  def mapField[I, O](from: I => ?, to: String): FieldMapping[I, O]
  
  // Map a nested field path
  def mapPath[I, O](fromPath: List[String], toPath: List[String]): FieldMapping[I, O]
  
  // Combine multiple mappings into a WIOPureNode
  def toNode[Ctx <: WorkflowContext, I, O <: WCState[Ctx]](
    mappings: List[FieldMapping[I, O]]
  ): WIOPureNode[Ctx, I, Nothing, O]
```

**Alternative (simpler)**: Since Scala has strong typing, the field mapping pattern is less necessary than in Go. A `WIOPureNode` with a lambda `(input: Message) => Counter(input.content, input.subStr)` is type-safe and arguably clearer. The example could be implemented using explicit transforms with a comment explaining the Eino field-mapping equivalent.

**Recommendation**: Implement the example using explicit `WIOPureNode` transforms (no new infrastructure needed). Optionally add a `FieldMapper` DSL later if the pattern recurs.

**Effort estimate**: Small (0.5 days) for the example with explicit transforms. Medium (2-3 days) for a full DSL.

---

## 5. `workflow/3_data_only` — Data-Only Dependencies

### What the Eino example does
- A workflow with `adder` and `mul` nodes
- `adder` receives `Add` field from START
- `mul` receives its `A` from `adder`'s output and `B` from START's `Multiply` field
- `WithNoDirectDependency()` on the START→mul edge means START's completion doesn't gate `mul`'s execution — only the data dependency matters
- Result: `(2 + 5) * 3 = 21`

### What adk4s has today
- `WIOGraph` edges are control-flow edges (node B runs after node A completes) ✅
- `WIOParallelNode` runs multiple nodes concurrently ✅
- No separation of control-flow vs data-flow dependencies

### Gap
WIOGraph edges are always control-flow edges. There's no way to say "pass data from A to B without making B wait for A to complete."

### Proposed implementation

**Feature: `DataEdge` in `WIOGraph`**

```scala
// Existing: control-flow edge (B waits for A)
graph.addEdge(aRef, bRef)

// New: data-only edge (B receives data from A but doesn't wait for A's completion)
graph.addDataEdge(aRef, bRef)
```

In the `toRunnable` compilation, data edges would be resolved by:
1. Collecting all data dependencies for a node
2. Running the node as soon as its *control-flow* predecessors complete
3. Injecting data from data-edge sources (which may already be available from earlier execution)

**Alternative (simpler)**: Model this as a `WIOParallelNode` that runs `adder` and a "pass-through for Multiply" in parallel, then a `WIOPureNode` that combines their outputs into `mul`'s input. This is how it would naturally be expressed in a typed graph.

**Recommendation**: Implement the example using `WIOParallelNode` + `WIOPureNode` composition. Add `DataEdge` only if the pattern becomes common.

**Effort estimate**: Small (0.5 days) for the example. Medium (2-3 days) for `DataEdge`.

---

## 6. `workflow/6_stream_field_map` — Stream Field Mapping

### What the Eino example does
- Like `2_field_mapping` but with streaming: input is a `StreamReader[*Message]`
- `TransformableLambda` processes each chunk, mapping fields from the stream
- `StreamReaderWithConvert` transforms each chunk, extracting fields and counting occurrences
- Static values (`SubStr`) are injected and may arrive in any chunk
- Output is a `map[string]int` aggregated from the stream

### What adk4s has today
- `Runnable.fromTransform` handles `Stream[IO, A] => Stream[IO, B]` ✅
- `WIORunnableNode` wraps Runnables in WIOGraph ✅
- No per-field stream mapping

### Gap
No stream-level field decomposition. In Eino, a single stream of structs is decomposed into per-field streams that feed different nodes. In adk4s, streams are opaque `Stream[IO, A]` — you can transform elements but not split a struct stream into field streams.

### Proposed implementation

**Feature: `StreamFieldSplitter[I]` in `adk4s-core`**

```scala
object StreamFieldSplitter:
  def split[I, A, B](
    source: Stream[F, I],
    extractA: I => A,
    extractB: I => B
  ): (Stream[F, A], Stream[F, B])
```

This uses fs2's `broadcastThrough` to fan out a single stream into multiple derived streams, each extracting a different field. The derived streams can then feed into separate `WIORunnableNode`s.

**Feature: `StreamFieldMerger[O]` in `adk4s-core`**

```scala
object StreamFieldMerger:
  def merge[A, B, O](
    streamA: Stream[F, A],
    streamB: Stream[F, B],
    combine: (A, B) => O
  ): Stream[F, O]
```

Merges multiple field streams back into a single output stream.

**Combined with static value injection:**

```scala
def withStaticValue[I, S](
  source: Stream[F, I],
  staticValue: S,
  inject: (I, S) => I
): Stream[F, I]
```

**Effort estimate**: Medium (2-3 days). The fs2 primitives exist; this is a convenience layer with proper typing.

---

## 7. `compose/batch` — Batch Execution

### What the Eino example does
- `BatchNode` processes multiple inputs through a graph/workflow
- Configurable concurrency (sequential or parallel with N workers)
- Per-item error handling (one failure doesn't abort the batch)
- Interrupt/resume support for individual batch items (human-in-the-loop per document)
- Parent graph integration with a `Reduce` node that aggregates batch results
- 7 scenarios: sequential, concurrent, compile options, callbacks, error handling, interrupt/resume, parent graph

### What adk4s has today
- `WIOForEachNode` iterates over a collection ✅
- `WIOParallelNode` runs nodes concurrently ✅
- `Runnable.invoke` processes single inputs ✅
- No batch-level orchestration with per-item error handling

### Gap
No `BatchNode` that runs a graph over multiple inputs with configurable concurrency, per-item error isolation, and interrupt/resume per item.

### Proposed implementation

**Feature: `BatchExecutor[F[_], I, O]` in `adk4s-orchestration`**

```scala
trait BatchExecutor[F[_], I, O]:
  def invokeAll(inputs: List[I]): F[List[Either[Throwable, O]]]
  def invokeAllPar(inputs: List[I], concurrency: Int): F[List[Either[Throwable, O]]]
  def stream(inputs: List[I], concurrency: Int): Stream[F, Either[Throwable, O]]

object BatchExecutor:
  def fromRunnable[F[_]: Async, I, O](
    runnable: Runnable[I, O]
  ): BatchExecutor[F, I, O]
  
  def fromGraph[Ctx <: WorkflowContext, I, O](
    graph: WIOGraph[Ctx, I, ?, O]
  ): BatchExecutor[IO, I, O]
```

Implementation uses fs2's `parEvalMap` for bounded concurrency:

```scala
def invokeAllPar(inputs: List[I], concurrency: Int): F[List[Either[Throwable, O]]] =
  Stream.emits(inputs)
    .parEvalMap(concurrency)(i => runnable.invoke(i).attempt)
    .compile.toList
```

For interrupt/resume per batch item, combine with `WIOInterruptNode` (from proposal #1).

**Feature: `ReduceNode[Ctx, I, O]`** — aggregates batch results

```scala
WIONode.reduce[Ctx, List[O], Summary](
  (results: List[O]) => Summary(results.length, results.count(_.approved), ...)
)
```

This is just a `WIOPureNode` with a descriptive factory method.

**Effort estimate**: Small-Medium (1-2 days). fs2 provides the concurrency primitives; this is orchestration glue.

---

## 8. `components/tool` — Tool Schema Inference

### What the Eino example does
- `utils.InferTool` automatically generates a `ToolInfo` (name, description, JSON schema) from a Go function signature
- `jsonschema` package converts Go struct types to JSON Schema
- Tool middleware: `errorremover` strips error fields, `jsonfix` repairs malformed JSON from LLMs

### What adk4s has today
- `Tool.invokable` requires manual schema definition ✅
- `StructuredToolCall` provides typed tool calls ✅
- `ToolMiddleware` supports pre/post processing ✅

### Gap
No automatic schema inference from Scala case class definitions.

### Proposed implementation

**Feature: `Tool.infer[I]` in `adk4s-core`**

```scala
object Tool:
  inline def infer[F[_], I <: Product: Mirror.ProductOf](
    name: String,
    description: String,
    fn: I => Either[String, ujson.Value]
  ): InvokableTool[F]
```

Uses Scala 3 `Mirror` + inline macros to derive JSON Schema from case class fields at compile time:

```scala
case class BookInput(location: String, passengerName: String, passengerPhoneNumber: String)

val tool = Tool.infer[IO, BookInput](
  "BookTicket",
  "Book a ticket to a location",
  (input: BookInput) => Right(ujson.Obj("status" -> "booked"))
)
// Automatically generates JSON Schema:
// { "type": "object", "properties": { "location": { "type": "string" }, ... }, "required": [...] }
```

**Feature: `JsonFixMiddleware` in `adk4s-core`**

```scala
val jsonFix: ToolMiddleware = ToolMiddleware.jsonFix(
  maxAttempts = 3,
  repairStrategies = List(StripTrailingComma, FixUnquotedKeys, FixSingleQuotes)
)
```

**Effort estimate**: Medium (2-3 days) for schema inference. Small (0.5 days) for JSON fix middleware.

---

## Summary: Priority & Effort Matrix

| # | Example | Proposed Feature | Effort | Priority | Dependencies |
|---|---------|-----------------|--------|----------|-------------|
| 1 | `react_with_interrupt` | `WIOInterruptNode` + `CheckpointStore` | Medium | High | None |
| 2 | `flow/agent/react` | `ReactAgent` trait | Medium | High | None |
| 3 | `react/dynamic_option` | `DynamicToolRegistry` | Small | Medium | #2 |
| 4 | `workflow/2_field_mapping` | Explicit transforms (no infra) or `FieldMapper` DSL | Small | Low | None |
| 5 | `workflow/3_data_only` | `WIOParallelNode` composition (no infra) or `DataEdge` | Small | Low | None |
| 6 | `workflow/6_stream_field_map` | `StreamFieldSplitter` + `StreamFieldMerger` | Medium | Medium | None |
| 7 | `compose/batch` | `BatchExecutor` | Small-Medium | High | None |
| 8 | `components/tool` | `Tool.infer` (schema derivation) + `JsonFixMiddleware` | Medium | Medium | None |

### Recommended implementation order

1. **`BatchExecutor`** (#7) — High value, low effort. Unlocks batch processing patterns.
2. **`ReactAgent`** (#2) — High value. First-class agent abstraction with streaming.
3. **`WIOInterruptNode`** (#1) — Unlocks human-in-the-loop, a key enterprise pattern.
4. **`DynamicToolRegistry`** (#3) — Small effort once `ReactAgent` exists.
5. **`Tool.infer`** (#8) — Developer ergonomics improvement.
6. **`StreamFieldSplitter`** (#6) — Niche but demonstrates advanced streaming.
7. **`workflow/2_field_mapping`** (#4) and **`workflow/3_data_only`** (#5) — Can be implemented with existing primitives; DSL is optional.

### Total effort to reach 26/26 examples: ~2-3 weeks

- **Immediate (no new infra)**: Examples #4 and #5 can be implemented today using `WIOPureNode` + `WIOParallelNode` composition
- **Small features** (1-2 days each): #3, #7
- **Medium features** (2-3 days each): #1, #2, #6, #8
