# Workflows4s Best Practices and Gotchas

## Overview

This guide covers common pitfalls, best practices, and patterns for effective use of workflows4s.

---

## Common Gotchas

### 1. WIO is a Pure Value

**Issue**: `WIO` is a description, not an execution.

```scala
// ❌ Wrong - side effect executed immediately
val workflow = WIO.pure {
  println("This runs immediately!")  // Bad!
  State("value", 0)
}.done

// ✅ Correct - side effect deferred
val workflow = WIO.pure(State("value", 0)).done

// ✅ Correct - use runIO for side effects
val workflow = WIO.runIO[State] { _ =>
  IO.delay {
    println("This runs when workflow executes")
    MyEvent()
  }
}.handleEvent(...).done
```

**Key**: Construction of `WIO` should be **pure**. Use `runIO` for effects.

---

### 2. Events Must Be Serializable

**Issue**: Events are persisted, so they must be serializable.

```scala
// ❌ Wrong - contains non-serializable closure
case class MyEvent(callback: () => Unit) extends Event  // Bad!

// ✅ Correct - only data
case class MyEvent(userId: String, timestamp: Long) extends Event
```

**Best practice**: Events should be **data-only** (case classes with serializable fields).

---

### 3. IO Effects Run Only Once

**Issue**: `runIO` effects execute once and are cached via events.

```scala
val workflow = WIO.runIO[State] { state =>
  IO.delay {
    val random = scala.util.Random.nextInt()  // Runs once
    RandomGenerated(random)
  }
}.handleEvent((state, evt) => state.copy(value = evt.value)).done

// First execution: IO runs, generates RandomGenerated(42)
// After restart: Event RandomGenerated(42) replayed from storage
// Result: Same value every time - deterministic!
```

**Key**: This is **intentional** for event sourcing. Don't rely on side effects re-running.

---

### 4. State Updates Only Through Events

**Issue**: State changes must go through event handling.

```scala
// ❌ Wrong - direct mutation
var state = State("initial", 0)
state.counter += 1  // Not tracked!

// ✅ Correct - through WIO
WIO.pure.makeFrom[State].value { state =>
  state.copy(counter = state.counter + 1)
}.done
```

---

### 5. Signal Delivery is One-Shot

**Issue**: Signals consumed once they're handled.

```scala
val instance = runtime.createInstance("id")

// First delivery: success
instance.deliverSignal(mySignal, request1)  // Right(response)

// Second delivery: fails (signal already handled)
instance.deliverSignal(mySignal, request2)  // Left(UnexpectedSignal)
```

**Solution**: Design workflows to handle multiple signals if needed:
```scala
WIO.loop
  .apply(WIO.handleSignal(mySignal)...)
  .stopWhen(...)
  .restart(...)
  .done
```

---

### 6. Parallel Doesn't Mean Concurrent

**Issue**: `parallel` combines results but doesn't guarantee concurrency.

```scala
// All elements execute, but execution model depends on runtime
WIO.parallel[State, String, State]
  .apply(Seq(task1, task2, task3))
  .collectResults(...)
  .done

// In InMemorySyncRuntime: sequential execution
// In async runtime: may be concurrent
```

**Key**: `parallel` is about **logical parallelism**, not necessarily physical.

---

### 7. Timer Precision Depends on KnockerUpper

**Issue**: Timer accuracy limited by wakeup mechanism.

```scala
WIO.await(100.millis).handleEvent(...).done

// Actual wakeup time depends on:
// - Clock precision
// - KnockerUpper implementation
// - System scheduling
```

**Production**: Use Quartz or similar for reliable scheduling.

---

### 8. Checkpoints Don't Prevent Re-execution

**Issue**: Checkpoints mark progress but don't prevent all re-execution.

```scala
val workflow = step1.checkpointed(...) >>> step2

// On restart:
// - Checkpoint event for step1 is replayed (no re-execution)
// - step2 executes normally if not checkpointed
```

**Key**: Checkpoint **events**, not **computations**.

---

### 9. Error Types Must Be Explicit

**Issue**: Error types need `ErrorMeta` for visualization.

```scala
// ❌ Not visible in diagrams
val workflow: WIO[State, String, State] = ???

// ✅ Explicit error metadata
val workflow = WIO.pure.makeFrom[State].error { state =>
  "Validation failed"
}(using ErrorMeta.Present("ValidationError")).done
```

---

### 10. WorkflowContext Types Must Match

**Issue**: Mixing different `WorkflowContext` types causes compile errors.

```scala
object Ctx1 extends WorkflowContext {
  type State = String
  trait Event
}

object Ctx2 extends WorkflowContext {
  type State = Int
  trait Event
}

// ❌ Won't compile - different contexts
val workflow = ctx1Workflow >>> ctx2Workflow

// ✅ Use embedding for sub-workflows
val embedded = WIO.embed(ctx2Workflow, embedding)
```

---

## Best Practices

### Error Handling

#### 1. Use Descriptive Error Types

```scala
// ✅ Good - specific error types
sealed trait WorkflowError
case class ValidationError(field: String, message: String) extends WorkflowError
case class ExternalServiceError(service: String, code: Int) extends WorkflowError
case class TimeoutError(operation: String) extends WorkflowError

// ❌ Avoid - generic errors
type Error = String
```

#### 2. Handle Errors Close to Source

```scala
// ✅ Good - handle where error occurs
val robustStep = WIO
  .runIO[State](callApi)
  .handleEventWithError {
    case (state, ApiSuccess(data)) => Right(state.copy(data = data))
    case (state, ApiFailure(error)) => Left(error)
  }
  .done
  .handleErrorWith(errorHandler)

// ❌ Avoid - letting errors bubble up unhandled
val fragileStep = WIO.runIO[State](callApi).handleEvent(...).done
```

#### 3. Use Retry for Transient Failures

```scala
// ✅ Good - automatic retry
val withRetry = apiCall.retryIn {
  case _: IOException => 5.seconds
  case _: TimeoutException => 10.seconds
}

// ❌ Avoid - manual retry logic in business code
```

---

### State Management

#### 1. Keep State Minimal

```scala
// ✅ Good - minimal state
case class State(
  orderId: String,
  status: OrderStatus,
  lastUpdate: Instant
)

// ❌ Avoid - unnecessary data
case class State(
  orderId: String,
  status: OrderStatus,
  lastUpdate: Instant,
  apiClient: HttpClient,        // Don't store!
  logger: Logger,                // Don't store!
  configuration: Config          // Don't store!
)
```

#### 2. Use Opaque Types for IDs

```scala
// ✅ Good - type-safe IDs
opaque type UserId = String
opaque type OrderId = String

case class State(userId: UserId, orderId: OrderId)

// ❌ Avoid - stringly typed
case class State(userId: String, orderId: String)
```

#### 3. Design for Serializability

```scala
// ✅ Good - simple case classes
case class State(
  id: String,
  count: Int,
  tags: List[String],
  metadata: Map[String, String]
)

// ❌ Avoid - complex mutable structures
case class State(
  buffer: java.nio.ByteBuffer,
  cache: scala.collection.mutable.Map[String, Any]
)
```

---

### Workflow Design

#### 1. Name All Steps

```scala
// ✅ Good - named steps
val workflow = WIO
  .runIO[State](...)
  .handleEvent(...)
  .named("Call Payment Gateway")
  >>> WIO.await(5.minutes).handleEvent(...).named("Wait for Confirmation")
  >>> WIO.pure(...).named("Finalize Order")

// ❌ Avoid - anonymous steps
val workflow = WIO.runIO[State](...).handleEvent(...).done
```

#### 2. Use AutoNamed for Variables

```scala
// ✅ Good - auto-naming
val callPaymentGateway = WIO.runIO[State](...).handleEvent(...).autoNamed
// Becomes "Call Payment Gateway" in diagrams

val waitForConfirmation = WIO.await(5.minutes).handleEvent(...).autoNamed
// Becomes "Wait For Confirmation"
```

#### 3. Extract Reusable Workflows

```scala
// ✅ Good - reusable components
object Workflows {
  def validateInput[S <: HasInput]: WIO[S, ValidationError, S] = ???
  def sendNotification[S <: HasUserId]: WIO[S, NotificationError, S] = ???
  def logEvent[S]: WIO[S, Nothing, S] = ???
}

val workflow = 
  Workflows.validateInput >>> 
  businessLogic >>> 
  Workflows.sendNotification >>> 
  Workflows.logEvent
```

#### 4. Keep Workflows Focused

```scala
// ✅ Good - single responsibility
val orderWorkflow = 
  validateOrder >>> 
  chargePayment >>> 
  createShipment >>> 
  sendConfirmation

// ❌ Avoid - monolithic workflows
val everythingWorkflow = 
  validateOrder >>> chargePayment >>> createShipment >>> 
  sendConfirmation >>> handleReturns >>> processRefunds >>> 
  updateInventory >>> generateReports >>> ...
```

---

### Signal Design

#### 1. Use Typed Signals

```scala
// ✅ Good - type-safe signals
case class ApprovalRequest(orderId: String, amount: BigDecimal)
case class ApprovalResponse(approved: Boolean, reason: String)

val approvalSignal = SignalDef[ApprovalRequest, ApprovalResponse]()

// ❌ Avoid - untyped signals
val approvalSignal = SignalDef[String, String]()  // What does string mean?
```

#### 2. Provide Meaningful Names

```scala
// ✅ Good - descriptive names
val approvalSignal = SignalDef[ApprovalRequest, ApprovalResponse](
  name = "Approval Required"
)

// ❌ Avoid - generic names
val signal1 = SignalDef[Request, Response]()
```

#### 3. Validate Signal Requests

```scala
// ✅ Good - validation in signal handler
WIO.handleSignal(approvalSignal)
   .using[State]
   .purely { (state, request) =>
     if (request.amount > 0) ApprovalEvent(request)
     else throw new IllegalArgumentException("Amount must be positive")
   }
   .handleEvent(...)
   .produceResponse(...)
   .done
```

---

### Testing

#### 1. Use InMemorySyncRuntime for Tests

```scala
// ✅ Good - synchronous testing
val runtime = InMemorySyncRuntime.create(workflow, initialState, engine)
val instance = runtime.createInstance("test-1")
val state = instance.queryState()
assert(state.value == "expected")
```

#### 2. Test Event Replay

```scala
// ✅ Good - verify idempotency
val events = List(event1, event2, event3)

// Apply events
val state1 = events.foldLeft(initialState)(applyEvent)

// Replay events
val state2 = events.foldLeft(initialState)(applyEvent)

assert(state1 == state2)  // Must be identical
```

#### 3. Use RecordingKnockerUpper

```scala
// ✅ Good - verify wakeup scheduling
val recorder = RecordingKnockerUpper()
val engine = WorkflowInstanceEngine.basic().withKnockerUpper(recorder)
val runtime = InMemorySyncRuntime.create(workflow, initialState, engine)

val instance = runtime.createInstance("test-1")
val wakeups = recorder.getScheduledWakeups()
assert(wakeups.size == 1)
assert(wakeups.head.instant == expectedTime)
```

#### 4. Test Error Paths

```scala
// ✅ Good - test error handling
val errorWorkflow = workflow.handleErrorWith(errorHandler)
val runtime = InMemorySyncRuntime.create(errorWorkflow, initialState, engine)

// Trigger error condition
val instance = runtime.createInstance("error-test")
val state = instance.queryState()
assert(state.errors.nonEmpty)
```

---

### Performance

#### 1. Minimize Event Size

```scala
// ✅ Good - compact events
case class OrderCreated(orderId: String, timestamp: Long) extends Event

// ❌ Avoid - large events
case class OrderCreated(
  orderId: String,
  entireOrderJson: String,  // Potentially huge
  customerHistory: List[Order],  // Unnecessary
  catalogSnapshot: Map[String, Product]  // Don't embed!
) extends Event
```

#### 2. Use Checkpoints Strategically

```scala
// ✅ Good - checkpoint after expensive operations
val workflow = 
  expensiveApiCall.checkpointed(...) >>>
  longComputation.checkpointed(...) >>>
  anotherExpensiveCall.checkpointed(...)

// ❌ Avoid - too many checkpoints
val workflow = step1.checkpointed(...) >>> step2.checkpointed(...) >>> 
               step3.checkpointed(...) >>> step4.checkpointed(...)  // Overkill
```

#### 3. Batch Operations in ForEach

```scala
// ✅ Good - process in batches if possible
WIO.forEach { state => state.items.grouped(100).toSet }
   .apply { batch => processBatch(batch) }
   .buildOutput(...)

// ❌ Avoid - too many fine-grained iterations
WIO.forEach { state => state.items }  // If items is 10,000+
   .apply { item => processOne(item) }
   .buildOutput(...)
```

---

### Production

#### 1. Use Persistent Runtime

```scala
// ✅ Good - production runtime
val runtime = DoobieRuntime.create(
  workflow = workflow,
  initialState = initialState,
  transactor = xa,
  engine = engine
)

// ❌ Avoid - in-memory in production
val runtime = InMemoryRuntime.default(...)  // Data loss on restart!
```

#### 2. Enable Logging Engine

```scala
// ✅ Good - observable workflows
val engine = WorkflowInstanceEngine
  .basic()
  .withLogging(logger)
  .withKnockerUpper(knockerUpper)
  .withRegistry(registry)
```

#### 3. Use Unique Template IDs

```scala
// ✅ Good - version in template ID
val runtime = DoobieRuntime.create(
  ...
  templateId = "order-workflow-v2"
)

// ❌ Avoid - generic IDs
val runtime = DoobieRuntime.create(
  ...
  templateId = "workflow"
)
```

#### 4. Monitor Instance Registry

```scala
// ✅ Good - track active workflows
val registry = PostgresWorkflowRegistry(xa)
val activeCount = registry.find(SearchCriteria(status = Some(Running)))
  .map(_.size)

// Alert if too many stuck workflows
if (activeCount > 10000) {
  alerting.send("Too many active workflows")
}
```

---

## Patterns Checklist

### When Designing a Workflow

- [ ] States are minimal and serializable
- [ ] Events are data-only (no functions/closures)
- [ ] All side effects use `runIO`
- [ ] All steps are named
- [ ] Error types are explicit
- [ ] Retry logic for transient failures
- [ ] Timeout handling for long operations
- [ ] Signal requests are validated

### When Testing

- [ ] Use `InMemorySyncRuntime`
- [ ] Test happy path
- [ ] Test error paths
- [ ] Test signal delivery
- [ ] Test timeout behavior
- [ ] Verify event replay idempotency

### When Deploying

- [ ] Use persistent runtime
- [ ] Configure reliable `KnockerUpper`
- [ ] Enable logging engine
- [ ] Set up monitoring/alerting
- [ ] Version template IDs
- [ ] Test recovery from crashes

---

## Common Anti-Patterns

### Anti-Pattern 1: Side Effects in Pure Workflows

```scala
// ❌ Bad
WIO.pure {
  database.save(record)  // Side effect!
  State("saved", 0)
}.done

// ✅ Good
WIO.runIO[State] { _ =>
  IO.delay {
    database.save(record)
    RecordSaved()
  }
}.handleEvent(...).done
```

### Anti-Pattern 2: Mutable State

```scala
// ❌ Bad
var counter = 0
WIO.pure.makeFrom[State].value { state =>
  counter += 1  // Mutable variable!
  state.copy(count = counter)
}.done

// ✅ Good
WIO.pure.makeFrom[State].value { state =>
  state.copy(count = state.count + 1)
}.done
```

### Anti-Pattern 3: Catching All Errors Silently

```scala
// ❌ Bad
apiCall.handleErrorWith(
  WIO.pure.makeFrom[(State, Error)].value { case (state, _) =>
    state  // Silently ignore all errors
  }.done
)

// ✅ Good
apiCall.handleErrorWith(
  WIO.pure.makeFrom[(State, Error)] { case (state, error) =>
    state.copy(
      errors = state.errors :+ error,
      status = "failed"
    )
  }.done
)
```

### Anti-Pattern 4: Ignoring Signal Responses

```scala
// ❌ Bad
instance.deliverSignal(signal, request)  // Ignore result

// ✅ Good
instance.deliverSignal(signal, request) match {
  case Right(response) => // Handle success
  case Left(UnexpectedSignal(sig)) => 
    logger.warn(s"Signal ${sig.name} not expected")
}
```

---

## Debugging Tips

### 1. Enable Debug Logging

```scala
val logger = LoggerFactory.getLogger("workflows4s")
logger.setLevel(Level.DEBUG)

val engine = WorkflowInstanceEngine.basic().withLogging(logger)
```

### 2. Visualize Workflow Progress

```scala
val progress = instance.getProgress
val diagram = MermaidRenderer.render(workflow, progress)
println(diagram)
```

### 3. Inspect Event History

```scala
// For persistent runtimes
val events = eventStore.loadEvents(instanceId)
events.foreach(evt => println(s"Event: $evt"))
```

### 4. Use Dump Methods

```scala
// In tests
val wf = workflow.toWorkflow(initialState)
wf.liveState  // Current computed state
wf.staticState  // State without effectless proceed
wf.expectedSignals  // What signals are awaited
```

---

## Migration Guide

### From Temporal

**Temporal**:
```java
@WorkflowInterface
interface OrderWorkflow {
  @WorkflowMethod
  String processOrder(Order order);
}
```

**Workflows4s**:
```scala
object OrderWorkflowCtx extends WorkflowContext {
  case class State(order: Order, status: String)
  sealed trait Event
}

val workflow: WIO.Initial = 
  validateOrder >>> 
  processPayment >>> 
  createShipment
```

**Key differences**:
- No annotations, pure values
- Explicit state types
- Event sourcing built-in

---

## Quick Reference

### Common Imports

```scala
import workflows4s.wio._
import workflows4s.runtime._
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import cats.effect.IO
import scala.concurrent.duration._
```

### Workflow Construction

```scala
// Pure
WIO.pure(value).done
WIO.pure.makeFrom[In].value(f).done

// Side effects
WIO.runIO[In](f).handleEvent(g).done

// Signals
WIO.handleSignal(sig).using[In].purely(f).handleEvent(g).produceResponse(h).done

// Timers
WIO.await(duration).handleEvent(f).done

// Branching
WIO.fork.on(cond).branch(wio).otherwise(wio).done

// Loops
WIO.loop.apply(body).stopWhen(cond).restart(restart).done
```

### Runtime Setup

```scala
// Testing
val runtime = InMemorySyncRuntime.create(workflow, initialState, engine)

// Production
val runtime = DoobieRuntime.create(workflow, initialState, xa, engine)
```

---

## Next Steps

- **Core API**: See `workflows4s-core-api.md` for WIO builders
- **Runtime**: See `workflows4s-runtime-patterns.md` for execution
- **Examples**: See `workflows4s-usage-examples.md` for real code
