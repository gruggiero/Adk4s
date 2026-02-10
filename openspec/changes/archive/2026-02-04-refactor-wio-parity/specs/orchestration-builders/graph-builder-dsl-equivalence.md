# GraphBuilder DSL — Current API Equivalence Guide

The spec originally called for a fluent GraphBuilder DSL (Task 2.6) with constructors and combinators mirroring the WIO API. This document shows how the current `WIOGraph` / `WIONode` / `WIONodeModifier` API already covers every planned operation, making a separate DSL unnecessary.

---

## Constructors

### pure

**Planned DSL:** `GraphBuilder.pure(f)`
**Current API:**
```scala
// WIONode.scala — factory method
def pure[Ctx <: WorkflowContext, I, O <: WCState[Ctx]](
  f: I => O
)(using errorMeta: ErrorMeta[Nothing]): WIOPureNode[Ctx, I, Nothing, O]

// With error channel
def pureEither[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  f: I => Either[Err, O]
)(using errorMeta: ErrorMeta[Err]): WIOPureNode[Ctx, I, Err, O]
```

**Usage:**
```scala
val node = WIONode.pure[Ctx, Int, TestState]((i: Int) => TestState(i))
graph.addNode("transform", node)
```

---

### runIO

**Planned DSL:** `GraphBuilder.runIO(effect, handler)`
**Current API:**
```scala
// WIONode.scala — two variants
def runIO[Ctx <: WorkflowContext, I, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
  runIO: I => IO[Evt],
  handleEvent: (I, Evt) => O
)(using errorMeta: ErrorMeta[Nothing], evtCt: ClassTag[Evt]): WIORunIONode[Ctx, I, Nothing, Evt, O]

def runIOWithError[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
  runIO: I => IO[Evt],
  handleEvent: (I, Evt) => Either[Err, O]
)(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIORunIONode[Ctx, I, Err, Evt, O]
```

**Usage:**
```scala
val node = WIONode.runIO[Ctx, Int, ValueAdded, TestState](
  runIO = (i: Int) => IO.pure(ValueAdded(i * 3)),
  handleEvent = (i: Int, evt: ValueAdded) => TestState(evt.delta)
)
graph.addNode("compute", node)
```

---

### handleSignal

**Planned DSL:** `GraphBuilder.handleSignal(signalDef, handler)`
**Current API:**
```scala
// WIONode.scala — effectful variant
def handleSignal[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Req, Resp, Evt <: WCEvent[Ctx]](
  signalDef: SignalDef[Req, Resp],
  signalHandler: (I, Req) => IO[Evt],
  eventHandler: (I, Evt) => Either[Err, O],
  responseHandler: (I, Evt) => Resp,
  operationName: Option[String] = None
)(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIOHandleSignalNode[Ctx, I, Err, O, Req, Resp, Evt]

// Pure variant
def handleSignalPurely[...](
  signalDef: SignalDef[Req, Resp],
  signalHandler: (I, Req) => Evt,      // pure, not IO
  eventHandler: (I, Evt) => Either[Err, O],
  responseHandler: (I, Evt) => Resp,
  operationName: Option[String] = None
)(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIOHandleSignalNode[...]
```

**Usage:**
```scala
val node = WIONode.handleSignal[Ctx, TestState, Nothing, TestState, MySignal, Unit, SignalReceived](
  signalDef = IncrementSignalDef,
  signalHandler = (state, req) => IO.pure(SignalReceived(req.amount)),
  eventHandler = (state, evt) => Right(TestState(state.value + evt.amount)),
  responseHandler = (_, _) => ()
)
graph.addNode("waitForSignal", node)
```

---

### await

**Planned DSL:** `GraphBuilder.await(duration, handler)`
**Current API:**
```scala
// WIONode.scala — static duration
def await[Ctx <: WorkflowContext, I, O <: WCState[Ctx]](
  duration: FiniteDuration,
  handleEvent: (I, WIO.Timer.Released) => O,
  name: Option[String] = None
)(using
  errorMeta: ErrorMeta[Nothing],
  releasedEvidence: WIO.Timer.Released <:< WCEvent[Ctx],
  startedEvidence: WIO.Timer.Started <:< WCEvent[Ctx]
): WIOAwaitNode[Ctx, I, Nothing, O]

// Dynamic duration computed from input
def awaitDynamic[Ctx <: WorkflowContext, I, O <: WCState[Ctx]](
  getDuration: I => FiniteDuration,
  handleEvent: (I, WIO.Timer.Released) => O,
  name: Option[String] = None
)(using ...): WIOAwaitNode[Ctx, I, Nothing, O]
```

**Usage:**
```scala
val node = WIONode.await[Ctx, TestState, TestState](
  duration = 5.seconds,
  handleEvent = (in, _) => TestState(in.value + 1)
)
graph.addNode("delay", node)
```

---

### fork

**Planned DSL:** `GraphBuilder.fork(condition, ifTrue, ifFalse)`
**Current API:**
```scala
// WIOForkNode companion — binary fork
def binaryFork[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  condition: I => Boolean,
  ifTrue: WIO[I, Err, O, Ctx],
  ifFalse: WIO[I, Err, O, Ctx],
  name: Option[String] = None
)(using errorMeta: ErrorMeta[Err]): WIOForkNode[Ctx, I, Err, O]

// Multi-way fork with catch-all
def withOtherwise[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  branches: List[Branch[Ctx, I, Err, O]],
  otherwise: WIO[I, Err, O, Ctx],
  name: Option[String] = None
)(using errorMeta: ErrorMeta[Err]): WIOForkNode[Ctx, I, Err, O]

// Raw fork with custom predicates
WIOForkNode(branches = List(
  WIOForkNode.Branch(predicate = (i: I) => if cond(i) then Some(i) else None, workflow = branchWIO)
), name = Some("router"))
```

**Usage (binary):**
```scala
val forkNode = WIOForkNode.binaryFork[Ctx, Int, Nothing, TestState](
  condition = (i: Int) => i > 50,
  ifTrue = highPathWIO,
  ifFalse = lowPathWIO
)
graph
  .addNode("fork", forkNode)
  .addEdge(forkRef, highNextRef)   // edge for true branch
  .addEdge(forkRef, lowNextRef)    // edge for false branch
```

**Usage (with otherwise):**
```scala
val forkNode = WIOForkNode.withOtherwise[Ctx, Int, Nothing, TestState](
  branches = List(
    WIOForkNode.Branch((i: Int) => if i > 100 then Some(i) else None, highWIO, Some("high")),
    WIOForkNode.Branch((i: Int) => if i > 50 then Some(i) else None, medWIO, Some("med"))
  ),
  otherwise = fallbackWIO
)
// Edges only for branches that continue to other graph nodes.
// Trailing branches without edges (like otherwise) use their workflow directly.
graph
  .addNode("fork", forkNode)
  .addEdge(forkRef, afterHighRef)  // only high branch chains to next node
```

---

### loop

**Planned DSL:** `GraphBuilder.loop(body, stopCondition, restart)`
**Current API:**
```scala
// WIONode.scala — boolean stop condition (requires O <:< I)
def loop[Ctx <: WorkflowContext, I <: WCState[Ctx], Err, O <: WCState[Ctx]](
  body: WIO[I, Err, O, Ctx],
  stopCondition: O => Boolean,
  restart: WIO[I, Err, I, Ctx]
)(using errorMeta: ErrorMeta[Err], ev: O <:< I): WIOLoopNode[Ctx, I, Err, O]

// Either-based exit condition (more flexible)
def loopEither[Ctx <: WorkflowContext, I <: WCState[Ctx], Err, O <: WCState[Ctx]](
  body: WIO[I, Err, O, Ctx],
  stopWhen: O => Either[I, O],
  restart: WIO[I, Err, I, Ctx]
)(using errorMeta: ErrorMeta[Err]): WIOLoopNode[Ctx, I, Err, O]
```

**Usage:**
```scala
val node = WIONode.loop[Ctx, TestState, Nothing, TestState](
  body = incrementWIO,
  stopCondition = (out: TestState) => out.value >= 100,
  restart = identityWIO
)
graph.addNode("retryLoop", node)
```

---

### parallel

**Planned DSL:** `GraphBuilder.parallel(workflows, collect)`
**Current API:**
```scala
// WIONode.scala — simple parallel (all workflows share input type)
def parallel[Ctx <: WorkflowContext, I <: WCState[Ctx], Err, O <: WCState[Ctx]](
  workflows: List[WIO[I, Err, WCState[Ctx], Ctx]],
  collectResults: List[WCState[Ctx]] => O
)(using errorMeta: ErrorMeta[Err]): WIOParallelNode[Ctx, I, Err, O, I]

// With explicit interim state tracking
def parallelWithState[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], InterimState <: WCState[Ctx]](
  elements: List[WIOParallelNode.Element[Ctx, I, Err, InterimState, ? <: WCState[Ctx]]],
  formResult: Seq[WCState[Ctx]] => O,
  initialInterimState: I => InterimState
)(using errorMeta: ErrorMeta[Err]): WIOParallelNode[Ctx, I, Err, O, InterimState]
```

**Usage:**
```scala
val node = WIONode.parallel[Ctx, TestState, Nothing, TestState](
  workflows = List(task1WIO, task2WIO, task3WIO),
  collectResults = (results: List[WCState[Ctx]]) => TestState(results.size)
)
graph.addNode("fanOut", node)
```

---

### forEach

**Planned DSL:** `GraphBuilder.forEach(getElements, elemWorkflow, ...)`
**Current API:**
```scala
// WIONode.scala — 8 type parameters mirroring WIO.ForEach
def forEach[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Elem,
            InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx],
            InterimState <: WCState[Ctx]](
  getElements: I => Set[Elem],
  elemWorkflow: WIO[Elem, Err, ElemOut, InnerCtx],
  initialElemState: () => WCState[InnerCtx],
  eventEmbedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]],
  interimStateBuilder: (I, Map[Elem, WCState[InnerCtx]]) => InterimState,
  buildOutput: (I, Map[Elem, ElemOut]) => O,
  signalRouter: SignalRouter.Receiver[Elem, InterimState],
  name: Option[String] = None
)(using errorMeta: ErrorMeta[Err]): WIOForEachNode[...]
```

**Usage:**
```scala
val node = WIONode.forEach[Ctx, Input, Nothing, Output, String, InnerCtx, InnerOut, Interim](
  getElements = (input: Input) => input.items.toSet,
  elemWorkflow = perElementWIO,
  initialElemState = () => InnerState.empty,
  eventEmbedding = myEventEmbedding,
  interimStateBuilder = (input, elemStates) => Interim(elemStates),
  buildOutput = (input, completed) => Output(completed),
  signalRouter = mySignalRouter
)
graph.addNode("processEach", node)
```

---

### checkpoint (modifier)

**Planned DSL:** `GraphBuilder.checkpoint(body, genEvent, handleEvent)`
**Current API — applied as a modifier on any existing node:**
```scala
// WIOGraph.scala
def withCheckpoint[I, O <: Out, Evt <: WCEvent[Ctx]](
  nodeRef: WIONodeRef[Ctx, I, O],
  genEvent: (I, O) => Evt,
  handleEvent: (I, Evt) => O
)(using evtCt: ClassTag[Evt]): WIOGraph[Ctx, In, Err, Out]
```

**Usage:**
```scala
graph
  .addNode("compute", computeNode)
  .withCheckpoint[Int, TestState, ValueAdded](
    computeRef,
    genEvent = (_: Int, out: TestState) => ValueAdded(out.value),
    handleEvent = (_: Int, evt: ValueAdded) => TestState(evt.delta)
  )
```

Checkpoint wraps the node's WIO in `WIO.Checkpoint`. On replay, the checkpoint event restores state without re-executing the body. This is orthogonal to the node type — any node (pure, runIO, signal, etc.) can be checkpointed.

---

### retry (modifier)

**Planned DSL:** `GraphBuilder.retry(body, policy)`
**Current API — applied as a modifier on any existing node:**
```scala
// WIOGraph.scala
def withRetry[I, O <: Out](
  nodeRef: WIONodeRef[Ctx, I, O],
  onError: (Throwable, WCState[Ctx], Instant) => IO[Option[Instant]]
): WIOGraph[Ctx, In, Err, Out]
```

The `onError` callback receives the error, current state, and current time. Return `Some(retryAt)` to schedule a retry, or `None` to give up.

**Usage:**
```scala
graph
  .addNode("apiCall", apiCallNode)
  .withRetry[Int, TestState](
    apiCallRef,
    onError = (_: Throwable, _: WCState[Ctx], now: Instant) =>
      IO.pure(Some(now.plusSeconds(5)))  // retry after 5 seconds
  )
```

---

### interrupt (modifier)

**Planned DSL:** `GraphBuilder.interrupt(body, interruptCondition)`
**Current API — applied as a modifier on any existing node:**
```scala
// WIOGraph.scala
def withInterruption[I, O <: Out](
  nodeRef: WIONodeRef[Ctx, I, O],
  interruption: WIO.Interruption[Ctx, Err, O]
): WIOGraph[Ctx, In, Err, Out]
```

**Usage:**
```scala
val interruption: WIO.Interruption[Ctx, Nothing, TestState] =
  WIO.Interruption(handler = interruptHandlerWIO, tpe = HandleInterruption.InterruptionType.Signal)

graph
  .addNode("longRunning", longRunningNode)
  .withInterruption[Int, TestState](longRunningRef, interruption)
```

---

## Combinators

### andThen (sequential composition)

**Planned DSL:** `step1.andThen(step2)`
**Current API — edges between nodes:**
```scala
// WIOGraph.scala
def addEdge[A, B <: Out, C <: Out](
  from: WIONodeRef[Ctx, A, B],
  to: WIONodeRef[Ctx, B, C]
): WIOGraph[Ctx, In, Err, Out]
```

Edges are type-safe: the output type `B` of the source node must match the input type `B` of the target node. During compilation, edges become `WIO.AndThen(sourceWIO, targetWIO)`.

**Usage:**
```scala
graph
  .addNode("step1", node1)      // Int => TestState
  .addNode("step2", node2)      // TestState => TestState
  .addNode("step3", node3)      // TestState => TestState
  .addEdge(step1Ref, step2Ref)  // step1 -> step2
  .addEdge(step2Ref, step3Ref)  // step2 -> step3
  .setEntry(step1Ref)
  .addEndNode(step3Ref)
```

---

### map / transform (output transformation)

**Planned DSL:** `step.map(f)`
**Current API — insert a pure transformation node and connect with an edge:**

There is no direct `.map` combinator. Instead, insert a `WIOPureNode` that applies the transformation and chain it with an edge.

**Usage:**
```scala
val computeNode = WIONode.runIO[Ctx, Int, ValueAdded, TestState](...)
val transformNode = WIONode.pure[Ctx, TestState, TestState](
  (s: TestState) => TestState(s.value * 2)
)

graph
  .addNode("compute", computeNode)
  .addNode("double", transformNode)
  .addEdge(computeRef, doubleRef)
```

This is one extra node compared to a `.map` combinator, but it makes the transformation explicit in the graph DAG.

---

### flatMap (dynamic dispatch)

**Planned DSL:** `step.flatMap(f)`
**Current API — use a fork node with predicate-based routing:**

True `flatMap` (creating graph structure dynamically at runtime) is not supported — the graph must be fully defined before compilation. However, conditional dispatch is covered by `WIOForkNode`:

**Usage:**
```scala
val forkNode = WIOForkNode.binaryFork[Ctx, TestState, Nothing, TestState](
  condition = (s: TestState) => s.value > threshold,
  ifTrue = highPathWIO,
  ifFalse = lowPathWIO
)

graph
  .addNode("dispatch", forkNode)
  .addEdge(dispatchRef, afterHighRef)
  .addEdge(dispatchRef, afterLowRef)
```

For multi-way dispatch, use `WIOForkNode.withOtherwise` or construct branches manually with custom predicates.

---

### handleErrorWith (error recovery)

**Planned DSL:** `step.handleErrorWith(recovery)`
**Current API — handle errors within node implementations:**

There is no graph-level error routing mechanism. Errors are handled within individual nodes using the `Either[Err, O]` return type:

**Usage:**
```scala
val resilientNode = WIONode.pureEither[Ctx, Int, MyError, TestState](
  (i: Int) =>
    if i < 0 then Left(MyError("negative"))
    else Right(TestState(i))
)
```

For IO-based error recovery, use `runIOWithError` and handle the error in the event handler. For cross-cutting error recovery, the `RetryModifier` covers the most common case (retrying on `Throwable`).

---

## Modifier Composition

Modifiers can be stacked on a single node. They are applied in order during compilation:

```scala
graph
  .addNode("apiCall", apiCallNode)
  .withCheckpoint[Int, TestState, ValueAdded](apiCallRef, genEvt, handleEvt)
  .withRetry[Int, TestState](apiCallRef, retryPolicy)
  .withInterruption[Int, TestState](apiCallRef, interruption)
```

The resulting WIO is: `Interruption(Retry(Checkpoint(baseWIO)))` — interruption wraps retry which wraps checkpoint which wraps the node's base WIO.

---

## Summary

| DSL Operation | Current API | Mechanism |
|---|---|---|
| `pure` | `WIONode.pure` / `pureEither` | Direct factory method |
| `runIO` | `WIONode.runIO` / `runIOWithError` | Direct factory method |
| `handleSignal` | `WIONode.handleSignal` / `handleSignalPurely` | Direct factory method |
| `await` | `WIONode.await` / `awaitDynamic` | Direct factory method |
| `fork` | `WIOForkNode.binaryFork` / `withOtherwise` | Direct factory method |
| `loop` | `WIONode.loop` / `loopEither` | Direct factory method |
| `parallel` | `WIONode.parallel` / `parallelWithState` | Direct factory method |
| `forEach` | `WIONode.forEach` | Direct factory method |
| `checkpoint` | `graph.withCheckpoint(nodeRef, ...)` | Modifier on NodeEntry |
| `retry` | `graph.withRetry(nodeRef, ...)` | Modifier on NodeEntry |
| `interrupt` | `graph.withInterruption(nodeRef, ...)` | Modifier on NodeEntry |
| `andThen` | `graph.addEdge(fromRef, toRef)` | Edge in DAG |
| `map/transform` | Insert `WIONode.pure` + `addEdge` | Extra pure node |
| `flatMap` | `WIOForkNode` with predicates | Fork-based dispatch |
| `handleErrorWith` | `pureEither` / `runIOWithError` | Node-level error handling |

All 11 constructors have direct equivalents. All 4 combinators are achievable through the existing API, though `map` requires an extra node and `handleErrorWith` is node-scoped rather than graph-scoped.
