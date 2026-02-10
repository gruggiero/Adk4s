## 1. Package and Type Foundation

- [x] 1.1 Create package `org.adk4s.orchestration.wiograph` with package object
- [x] 1.2 Create `WIONodeRef[Ctx, I, O]` case class with phantom types for type-safe references
- [x] 1.3 Create `NodeKey` opaque type (or reuse from existing graph package)
- [x] 1.4 Create `WIOGraphError` sealed trait with error variants (CycleDetected, MissingEntry, UnreachableEnd)

## 2. WIONode Sealed Trait Hierarchy

- [x] 2.1 Create `WIONode[Ctx, I, Err, O]` sealed trait with `toWIO` method signature
- [x] 2.2 Implement `WIOPureNode` with factory methods `pure`, `pureEither`, `error`
- [x] 2.3 Implement `WIORunIONode` with event type bound `Evt <: WCEvent[Ctx]` and factory methods
- [x] 2.4 Implement `WIOForkNode` with branch predicates and `binaryFork` factory method
- [x] 2.5 Implement `WIOLoopNode` with body, stopWhen, and restart workflows
- [x] 2.6 Implement `WIOAwaitNode` with static and dynamic duration variants
- [x] 2.7 Implement `WIOHandleSignalNode` with SignalDef, handlers, and response generation
- [x] 2.8 Implement `WIOSubGraphNode` for nested graph embedding
- [x] 2.9 Implement `WIOParallelNode` for concurrent workflow execution with result collection

## 3. WIOGraph Builder

- [x] 3.1 Create `WIOGraph[Ctx, In, Err, Out]` case class with private constructor
- [x] 3.2 Add companion object with `apply` and `withError` factory methods
- [x] 3.3 Implement `addNode` method with eager duplicate key validation (throws)
- [x] 3.4 Implement `setEntry` with compile-time input type matching
- [x] 3.5 Implement `addEndNode` with compile-time output type validation
- [x] 3.6 Implement type-safe `addEdge` using phantom types on WIONodeRef

## 4. Graph Validation

- [x] 4.1 Implement eager validation for edge target existence in `addEdge`
- [x] 4.2 Implement lazy cycle detection using DFS in `toWIO`
- [x] 4.3 Implement lazy entry node validation in `toWIO`
- [x] 4.4 Implement lazy end node reachability validation in `toWIO`
- [x] 4.5 Return `Either[NonEmptyChain[WIOGraphError], WIO[...]]` from `toWIO`

## 5. WIO Compilation

- [x] 5.1 Implement `toWIO` for each WIONode variant (PureNode, RunIONode, etc.)
- [x] 5.2 Implement type-preserving fold in WIOGraph to compose nodes via `WIO.AndThen`
- [x] 5.3 Implement branch compilation for WIOForkNode using `WIO.Fork`
- [x] 5.4 Implement recursive sub-graph compilation for WIOSubGraphNode
- [x] 5.5 Verify no `asInstanceOf` or `isInstanceOf` in compilation code

## 6. Testing

- [x] 6.1 Create test WorkflowContext with concrete State and Event types
- [x] 6.2 Test WIOPureNode creation and WIO compilation
- [x] 6.3 Test WIORunIONode with event type enforcement
- [x] 6.4 Test WIOForkNode branching logic
- [x] 6.5 Test WIOGraph builder with type-safe edge validation (compile failures)
- [x] 6.6 Test eager validation (duplicate keys, missing targets)
- [x] 6.7 Test lazy validation (cycles, missing entry, unreachable ends)
- [x] 6.8 Test linear graph compilation (A -> B -> C)
- [x] 6.9 Test branching graph compilation with fork
- [x] 6.10 Test nested sub-graph compilation
