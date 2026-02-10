## ADDED Requirements

### Requirement: User-Defined WorkflowContext
The system SHALL require users to define their own WorkflowContext with explicit State and Event types for WIOGraph construction, enabling the 3-parameter WIO type alias pattern from workflows4s.

#### Scenario: Define custom workflow context
- **GIVEN** a user wanting to create a WIOGraph
- **WHEN** they define an `object MyContext extends WorkflowContext` with `type State` and `type Event`
- **THEN** they can import `MyContext._` to get the scoped `WIO[-In, +Err, +Out <: State]` type alias
- **AND** construct a `WIOGraph[MyContext.Ctx, In, Err, Out]` using that context

#### Scenario: Access scoped WIO type alias
- **GIVEN** a custom WorkflowContext `AgentCtx` with `type State = AgentState`
- **WHEN** `import AgentCtx._` is used
- **THEN** `WIO[In, Err, Out]` is equivalent to `workflows4s.wio.WIO[In, Err, Out, AgentCtx.Ctx]`
- **AND** the constraint `Out <: AgentState` is enforced by the compiler

#### Scenario: Reject Any as State type
- **GIVEN** a WorkflowContext with `type State = Any`
- **WHEN** the user attempts to use it with WIOGraph
- **THEN** the compiler should emit a warning (via Scala 3 given priority) recommending a concrete state type

### Requirement: WIOGraph Builder
The system SHALL provide a `WIOGraph[Ctx, In, Err, Out]` builder that creates DAG-based workflows with WIO-compatible nodes, explicit entry and end nodes, and direct compilation to `WIO[In, Err, Out, Ctx]` without type casts.

#### Scenario: Create empty WIOGraph
- **GIVEN** a custom WorkflowContext `Ctx` with concrete State type
- **WHEN** `WIOGraph[Ctx, In, Out]` is called
- **THEN** a `WIOGraph[Ctx, In, Nothing, Out]` instance is returned with empty nodes and edges
- **AND** the entry node is unset and end nodes are empty

#### Scenario: Create WIOGraph with error type
- **GIVEN** a custom WorkflowContext `Ctx` and error type `Err`
- **WHEN** `WIOGraph.withError[Ctx, In, Err, Out]` is called
- **THEN** a `WIOGraph[Ctx, In, Err, Out]` instance is returned

#### Scenario: Set entry node with type checking
- **GIVEN** a WIOGraph and a node reference accepting input type `In`
- **WHEN** `graph.setEntry(nodeRef)` is called
- **THEN** the entry node is set
- **AND** the compiler ensures the node input type matches the graph input type `In`

#### Scenario: Add end node with type checking
- **GIVEN** a WIOGraph and a node reference producing output type `Out`
- **WHEN** `graph.addEndNode(nodeRef)` is called
- **THEN** the end node is added
- **AND** the compiler ensures the node output type conforms to `Out <: WCState[Ctx]`

#### Scenario: Add edge with type checking
- **GIVEN** two node references where the first outputs type `A` and the second inputs type `A`
- **WHEN** `graph.addEdge(from, to)` is called
- **THEN** the edge is added
- **AND** the compiler ensures type compatibility (no runtime cast needed)

#### Scenario: Reject edge with incompatible types at compile time
- **GIVEN** two node references where output type differs from input type
- **WHEN** `graph.addEdge(from, to)` is attempted
- **THEN** the call is rejected at compile time (not runtime)

#### Scenario: Compile to WIO without casts
- **GIVEN** a valid WIOGraph with entry and end nodes
- **WHEN** `graph.toWIO` is called
- **THEN** a `WIO[In, Err, Out, Ctx]` is returned
- **AND** the compilation does NOT use `asInstanceOf` anywhere

### Requirement: WIOPureNode for Pure Transformations
The system SHALL provide a `WIOPureNode[Ctx, I, Err, O]` for pure (non-IO) transformations that do not require event handling.

#### Scenario: Create pure node with success transformation
- **GIVEN** a pure function `I => O`
- **WHEN** `WIONode.pure[Ctx, I, O](f)` is called
- **THEN** a `WIOPureNode[Ctx, I, Nothing, O]` is created
- **AND** its `toWIO` produces `WIO.pure.makeFrom[I].value(f).done`

#### Scenario: Create pure node with Either transformation
- **GIVEN** a function `I => Either[Err, O]`
- **WHEN** `WIONode.pureEither[Ctx, I, Err, O](f)` is called
- **THEN** a `WIOPureNode[Ctx, I, Err, O]` is created
- **AND** its `toWIO` produces `WIO.pure.makeFrom[I].apply(f).done`

#### Scenario: Create pure error node
- **GIVEN** an error value `Err`
- **WHEN** `WIONode.error[Ctx, Err](value)` is called
- **THEN** a `WIOPureNode[Ctx, Any, Err, Nothing]` is created
- **AND** its `toWIO` produces `WIO.pure.error(value).done`

### Requirement: WIORunIONode for Side Effects
The system SHALL provide a `WIORunIONode[Ctx, I, Err, Evt, O]` for IO-based operations with explicit event handling, ensuring all effects are captured as events for replay.

#### Scenario: Create runIO node with success handling
- **GIVEN** a function `I => IO[Evt]` and an event handler `(I, Evt) => O`
- **WHEN** `WIONode.runIO[Ctx, I, Evt, O](runIO, handleEvent)` is called
- **THEN** a `WIORunIONode[Ctx, I, Nothing, Evt, O]` is created
- **AND** `Evt` must be a subtype of `WCEvent[Ctx]`
- **AND** its `toWIO` produces `WIO.runIO[I](runIO).handleEvent(handleEvent).done`

#### Scenario: Create runIO node with error handling
- **GIVEN** a function `I => IO[Evt]` and an event handler `(I, Evt) => Either[Err, O]`
- **WHEN** `WIONode.runIOWithError[Ctx, I, Err, Evt, O](runIO, handleEvent)` is called
- **THEN** a `WIORunIONode[Ctx, I, Err, Evt, O]` is created
- **AND** its `toWIO` produces `WIO.runIO[I](runIO).handleEventWithError(handleEvent).done`

#### Scenario: Event type must extend context Event
- **GIVEN** a custom WorkflowContext with `sealed trait Event`
- **WHEN** creating a WIORunIONode
- **THEN** the event type `Evt` must be a subtype of the context's Event type
- **AND** this is enforced at compile time

### Requirement: WIOForkNode for Branching
The system SHALL provide a `WIOForkNode[Ctx, I, Err, O]` for conditional branching using WIO.Fork semantics with ordered case predicates.

#### Scenario: Create fork with two cases
- **GIVEN** two predicates `I => Option[A]` and `I => Option[B]` with corresponding workflows
- **WHEN** a WIOForkNode is created with these cases
- **THEN** its `toWIO` produces `WIO.Fork(branches, name, None)`
- **AND** each branch is a `WIO.Branch` with the predicate and workflow

#### Scenario: Create binary fork
- **GIVEN** a predicate `I => Boolean` and two workflows for true/false branches
- **WHEN** `WIONode.binaryFork[Ctx, I, Err, O](condition, ifTrue, ifFalse)` is called
- **THEN** a `WIOForkNode` is created with two branches

#### Scenario: Fork selects first matching case
- **GIVEN** a WIOForkNode with multiple cases
- **WHEN** compiled to WIO and evaluated
- **THEN** the first predicate returning `Some` is selected (ordered evaluation)

### Requirement: WIOLoopNode for Iteration
The system SHALL provide a `WIOLoopNode[Ctx, I, Err, O]` for loop constructs with body workflow, stop condition, and restart logic.

#### Scenario: Create loop with stop condition
- **GIVEN** a body workflow, a stop condition `BodyOut => Boolean`, and a restart workflow
- **WHEN** `WIONode.loop[Ctx, I, Err, O](body, stopWhen, restart)` is called
- **THEN** a `WIOLoopNode` is created
- **AND** its `toWIO` produces `WIO.loop.apply(body).stopWhen(stopWhen).restart(restart).done`

#### Scenario: Create loop with Either stop condition
- **GIVEN** a body workflow and a stop condition `BodyOut => Either[ReturnIn, O]`
- **WHEN** the loop is created with this condition
- **THEN** `Left(returnIn)` causes restart, `Right(out)` causes exit

### Requirement: WIOAwaitNode for Timers
The system SHALL provide a `WIOAwaitNode[Ctx, I, Err, O]` for timer-based waiting with event handling.

#### Scenario: Create static await
- **GIVEN** a duration `FiniteDuration` and an event handler `(I, Timer.Released) => O`
- **WHEN** `WIONode.await[Ctx, I, O](duration, handleEvent)` is called
- **THEN** a `WIOAwaitNode` is created
- **AND** its `toWIO` produces `WIO.await(duration).handleEvent(handleEvent).done`

#### Scenario: Create dynamic await
- **GIVEN** a function `I => FiniteDuration` and an event handler
- **WHEN** `WIONode.awaitDynamic[Ctx, I, O](getDuration, handleEvent)` is called
- **THEN** a `WIOAwaitNode` is created with dynamic duration source

### Requirement: WIOHandleSignalNode for External Signals
The system SHALL provide a `WIOHandleSignalNode[Ctx, I, Err, O, Req, Resp]` for handling external signals with type-safe request/response.

#### Scenario: Create signal handler
- **GIVEN** a `SignalDef[Req, Resp]`, a signal handler `(I, Req) => Evt`, and event/response handlers
- **WHEN** `WIONode.handleSignal[Ctx, I, Err, O, Req, Resp, Evt](signalDef, signalHandler, eventHandler, responseHandler)` is called
- **THEN** a `WIOHandleSignalNode` is created
- **AND** its `toWIO` produces the equivalent `WIO.handleSignal(signalDef).using[I].purely(signalHandler).handleEvent(eventHandler).produceResponse(responseHandler).done`

#### Scenario: Create pure signal handler
- **GIVEN** a `SignalDef[Req, Resp]` and pure handlers
- **WHEN** `WIONode.handleSignalPurely[...]` is called
- **THEN** the handler uses `.purely` instead of `.withSideEffects`

### Requirement: WIOSubGraphNode for Nested Graphs
The system SHALL provide a `WIOSubGraphNode[Ctx, I, Err, O]` for embedding a WIOGraph as a node.

#### Scenario: Create sub-graph node
- **GIVEN** a valid `WIOGraph[Ctx, A, Err, B]`
- **WHEN** `WIONode.subGraph[Ctx, A, Err, B](graph)` is called
- **THEN** a `WIOSubGraphNode[Ctx, A, Err, B]` is created
- **AND** its `toWIO` recursively compiles the sub-graph

#### Scenario: Sub-graph shares same context
- **GIVEN** a parent graph with context `Ctx`
- **WHEN** a sub-graph is added
- **THEN** the sub-graph MUST use the same `Ctx`
- **AND** this is enforced at compile time

### Requirement: WIOParallelNode for Concurrent Execution
The system SHALL provide a `WIOParallelNode[Ctx, I, Err, O]` for parallel execution of multiple workflows with result collection.

#### Scenario: Create parallel node
- **GIVEN** a sequence of workflows `Seq[WIO[I, Err, ElemOut, Ctx]]` and a result collector
- **WHEN** `WIONode.parallel[Ctx, I, Err, O](workflows, collectResults)` is called
- **THEN** a `WIOParallelNode` is created
- **AND** its `toWIO` produces `WIO.parallel[...].apply(workflows).collectResults(collectResults).done`

### Requirement: Typed Edge and Reference System
The system SHALL provide a type-safe reference system for nodes and edges that preserves input/output type relationships at compile time.

#### Scenario: Node reference preserves types
- **GIVEN** a node with input type `A` and output type `B`
- **WHEN** `graph.addNode(...)` returns a reference
- **THEN** the reference is typed as `WIONodeRef[Ctx, A, B]`
- **AND** this type is used for compile-time edge validation

#### Scenario: Edge between compatible nodes
- **GIVEN** a reference `WIONodeRef[Ctx, A, B]` and a reference `WIONodeRef[Ctx, B, C]`
- **WHEN** `graph.addEdge(from, to)` is called
- **THEN** the edge is valid because output `B` matches input `B`

#### Scenario: Edge between incompatible nodes rejected
- **GIVEN** a reference `WIONodeRef[Ctx, A, B]` and a reference `WIONodeRef[Ctx, C, D]` where `B != C`
- **WHEN** `graph.addEdge(from, to)` is attempted
- **THEN** the call is rejected at compile time with a type mismatch error

### Requirement: Direct WIO Compilation
The system SHALL provide direct compilation from WIOGraph to WIO without intermediate representations or unsafe casts.

#### Scenario: Compile linear graph
- **GIVEN** a WIOGraph with nodes `A -> B -> C`
- **WHEN** `graph.toWIO` is called
- **THEN** the result is `nodeA.toWIO >>> nodeB.toWIO >>> nodeC.toWIO`
- **AND** the composition uses `WIO.AndThen` with proper types

#### Scenario: Compile branching graph
- **GIVEN** a WIOGraph with a fork node
- **WHEN** `graph.toWIO` is called
- **THEN** the fork is compiled using `WIO.Fork` with branches
- **AND** each branch is recursively compiled

#### Scenario: No asInstanceOf in compilation
- **GIVEN** any valid WIOGraph
- **WHEN** `graph.toWIO` is called
- **THEN** the implementation MUST NOT use `asInstanceOf`
- **AND** the implementation MUST NOT use `isInstanceOf`
- **AND** type safety is guaranteed by the type system

### Requirement: Event Type Safety
The system SHALL enforce that all event types used in nodes are subtypes of the context's Event type.

#### Scenario: Event type validation
- **GIVEN** a WorkflowContext with `sealed trait Event`
- **WHEN** creating a WIORunIONode with event type `Evt`
- **THEN** `Evt <: WCEvent[Ctx]` must hold at compile time

#### Scenario: Custom event ADT
- **GIVEN** a context with events `case class ApiCalled(response: String) extends Event`
- **WHEN** creating a WIORunIONode using `ApiCalled` as the event type
- **THEN** the node is valid because `ApiCalled <: Event`

### Requirement: State Type Safety
The system SHALL enforce that all output types used in nodes are subtypes of the context's State type.

#### Scenario: Output type validation
- **GIVEN** a WorkflowContext with `case class MyState(...)`
- **WHEN** creating a node with output type `O`
- **THEN** `O <: MyState` must hold at compile time

#### Scenario: Reject Any as output type
- **GIVEN** a node attempting to output type `Any`
- **WHEN** the node is added to a WIOGraph
- **THEN** it is rejected because `Any` is not a valid subtype of a concrete State

### Requirement: Validation Timing
The system SHALL use a hybrid validation approach: eager validation for simple errors during construction, compile-time validation for type constraints, and lazy validation for global graph properties at `toWIO` time.

#### Scenario: Eager validation for node key uniqueness
- **GIVEN** a WIOGraph with an existing node "node1"
- **WHEN** `graph.addNode("node1", node)` is called with the same key
- **THEN** an `IllegalArgumentException` is thrown immediately

#### Scenario: Eager validation for edge targets
- **GIVEN** a WIOGraph with nodes "node1" and "node2"
- **WHEN** `graph.addEdge(ref1, nonExistentRef)` is called
- **THEN** an `IllegalArgumentException` is thrown immediately

#### Scenario: Compile-time edge type validation
- **GIVEN** node references with mismatched types
- **WHEN** `graph.addEdge(from, to)` is attempted
- **THEN** the compiler rejects the call at compile time

#### Scenario: Lazy validation for cycles at toWIO
- **GIVEN** a WIOGraph with edges forming a cycle
- **WHEN** `graph.toWIO` is called
- **THEN** an `Either[NonEmptyChain[WIOGraphError], WIO[...]]` is returned with cycle detection error

#### Scenario: Lazy validation for missing entry node
- **GIVEN** a WIOGraph without an entry node set
- **WHEN** `graph.toWIO` is called
- **THEN** an `Either[NonEmptyChain[WIOGraphError], WIO[...]]` is returned with missing entry error

#### Scenario: Lazy validation for unreachable end nodes
- **GIVEN** a WIOGraph where not all paths lead to an end node
- **WHEN** `graph.toWIO` is called
- **THEN** an `Either[NonEmptyChain[WIOGraphError], WIO[...]]` is returned with unreachable end error
