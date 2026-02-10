# Cast Refactoring Analysis

This document analyzes the state of `asInstanceOf` and `isInstanceOf` usage in the adk4s codebase after the refactoring effort to remove runtime casts from the orchestration module.

## Summary

The refactoring successfully removed casts from the core Chain, Workflow, and Graph construction APIs. However, casts remain in the execution layer where heterogeneous graph nodes are composed and executed at runtime.

## Changes Made

### 1. Removed `Runnable.identityToType[A, B]`

**File:** `adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`

The unsafe method that used `asInstanceOf` to coerce between unrelated types was removed:

```scala
// REMOVED
def identityToType[A, B]: Runnable[A, B] =
  full(
    invokeFn = (input: A) => IO.pure(input.asInstanceOf[B]),
    streamFn = (input: A) => Stream.emit(input.asInstanceOf[B]),
    collectFn = (input: Stream[IO, A]) => input.compile.lastOrError.map(_.asInstanceOf[B]),
    transformFn = (input: Stream[IO, A]) => input.map(_.asInstanceOf[B])
  )
```

### 2. Fixed `Chain.apply` to be Type-Safe

**File:** `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`

Changed from unsafe two-type-parameter factory to type-safe single-type-parameter:

```scala
// BEFORE (unsafe)
def apply[I, O]: Chain[I, O] = Chain(IO.delay(Runnable.identityToType[I, O]))

// AFTER (type-safe)
def apply[I]: Chain[I, I] = Chain(IO.delay(Runnable.identity[I]))
```

Chains now start as identity (`I → I`) and the output type changes when steps are appended.

### 3. Fixed `Workflow.compile`

**File:** `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`

Changed from returning an unsafe identity stub to properly indicating the feature is not implemented:

```scala
// BEFORE (unsafe cast hidden in stub)
def compile: IO[Runnable[I, O]] = IO.delay {
  Runnable.identityToType[I, O]
}

// AFTER (explicit about missing implementation)
def compile: IO[Runnable[I, O]] = IO.raiseError(
  new UnsupportedOperationException("Workflow execution not yet implemented")
)
```

## Remaining Casts in Execution Layer

The following casts remain in the graph execution layer. These exist because graphs support heterogeneous node types (each node can have different input/output types), and at runtime the actual types flow through while the compiler sees `Any`.

### NodeExecutable.scala

| Line | Code | Purpose |
|------|------|---------|
| 30 | `input.asInstanceOf[I]` | Cast `Any` input to typed input in `invokeAny` |
| 36 | `input.asInstanceOf[I]` | Cast `Any` input to typed input in `toWIO` |
| 47 | `input.asInstanceOf[I]` | Cast `Any` input to typed input in pure `invokeAny` |
| 54 | `input.asInstanceOf[I]` | Cast `Any` input to typed input in pure `toWIO` |

### WIOExecutor.scala

| Line | Code | Purpose |
|------|------|---------|
| 132 | `input.asInstanceOf[t]` | Cast input for branch condition evaluation |
| 155 | `input.asInstanceOf[t]` | Cast input for END branch condition |
| 228 | `nodeOutput.asInstanceOf[O]` | Cast terminal node output to graph output type |
| 232 | `nodeOutput.asInstanceOf[O]` | Cast end node output to graph output type |
| 238 | `nodeOutput.asInstanceOf[O]` | Cast output after branch routes to END |
| 264 | `input.asInstanceOf[t]` | Cast input for InvokeBranch evaluation |
| 268 | `input.asInstanceOf[t]` | Cast input for StreamBranch evaluation |

## Graph Execution Architecture

The graph execution uses the following architecture:

```
Graph[I, O, S]
    │
    ├── GraphNode[A, B]
    │       │
    │       └── executable: NodeExecutable[A, B]
    │               ├── invoke(input: A): IO[B]      (typed)
    │               ├── invokeAny(input: Any): IO[Any] (untyped, uses cast)
    │               └── toWIO: WIO[Any, Nothing, Any, GraphWorkflowContext.type]
    │
    └── WIOExecutor
            ├── toWIO(graph): GraphWIO[I]    (compiles to Workflows4s WIO)
            └── execute(graph, input): IO[O] (direct IO execution)
```

### Why Casts Are Needed

1. **Heterogeneous Nodes**: A graph can contain nodes with different input/output types:
   - `LambdaNode[Int, String]`
   - `ChatModelNode` (Conversation → Completion)
   - `ToolsNode` (List[ToolCall] → List[ToolMessage])

2. **Common Composition Type**: To compose these in a single WIO chain, `GraphWorkflowContext` uses `State = Any`:
   ```scala
   object GraphWorkflowContext extends WorkflowContext:
     override type State = Any
   ```

3. **Runtime Type Flow**: The actual types flow correctly at runtime, but the compiler only sees `Any` at the composition boundaries.

## Possible Refactoring Approaches

### Option A: Tagged Union with Pattern Matching

Create a sealed trait for all possible node output types and use pattern matching:

```scala
sealed trait NodeOutput
case class IntOutput(value: Int) extends NodeOutput
case class StringOutput(value: String) extends NodeOutput
// ... etc
```

**Pros**: Type-safe, no casts
**Cons**: Requires enumerating all possible types, very verbose

### Option B: Type-Preserving Graph Edges

Carry type evidence through the graph structure:

```scala
case class TypedEdge[A, B, C](from: NodeRef[A, B], to: NodeRef[B, C])
```

**Pros**: Full type safety at compile time
**Cons**: Complex type signatures, may not work with Workflows4s WIO

### Option C: Accept Boundary Casts

Accept that casts at the execution boundary layer are a necessary trade-off for supporting heterogeneous graphs.

**Pros**: Simple, works with existing Workflows4s integration
**Cons**: Casts remain in codebase (though isolated to execution layer)

### Option D: Monomorphic Graphs

Require all nodes in a graph to have the same input/output type:

```scala
Graph[T, T, S]  // All nodes are T → T
```

**Pros**: No casts needed
**Cons**: Severely limits graph expressiveness

## Current Status

| Component | Cast-Free |
|-----------|-----------|
| `Runnable` (adk4s-core) | ✅ Yes |
| `Chain` (construction) | ✅ Yes |
| `Workflow` (construction) | ✅ Yes |
| `Graph` (construction) | ✅ Yes |
| `Branch` tests | ✅ Yes |
| `NodeExecutable` (execution) | ❌ No |
| `WIOExecutor` (execution) | ❌ No |

## Recommendation

The current state represents a reasonable trade-off:

1. **Construction APIs are type-safe**: Users build graphs with full type safety
2. **Execution layer uses casts**: Isolated to `NodeExecutable` and `WIOExecutor`
3. **Casts are sound**: The graph validation ensures type-compatible edges before execution

The casts in the execution layer are analogous to how serialization libraries work - types are erased at boundaries but flow correctly at runtime.

To fully eliminate casts would require either:
- Significant changes to the graph model (monomorphic nodes)
- Changes to how Workflows4s WIO handles heterogeneous steps
- A complex type-level encoding of the graph structure

These trade-offs should be evaluated against the practical benefits of the current approach.
