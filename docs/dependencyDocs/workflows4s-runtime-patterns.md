# Workflows4s Runtime Patterns

## Overview

This guide covers workflow execution, runtime management, and the event-sourced state machine that powers workflows4s.

---

## 1. Runtime Components

### 1.1 WorkflowRuntime

**Purpose**: Factory for creating and managing workflow instances.

```scala
trait WorkflowRuntime[F[_], Ctx <: WorkflowContext] {
  def templateId: String
  def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]]
  def workflow: WIO.Initial[Ctx]
}
```

**Key points**:
- `templateId`: Identifies the workflow template
- `createInstance`: Creates or retrieves an instance by ID
- `workflow`: The pure WIO definition
- `F[_]`: Effect type (`IO`, `Id`, etc.)

---

### 1.2 WorkflowInstance

**Purpose**: Handle for interacting with a specific workflow execution.

```scala
trait WorkflowInstance[F[_], State] {
  def id: WorkflowInstanceId
  
  // Query current state
  def queryState(): F[State]
  
  // Deliver external signals
  def deliverSignal[Req, Resp](
    signalDef: SignalDef[Req, Resp], 
    req: Req
  ): F[Either[UnexpectedSignal, Resp]]
  
  // Wake up for timers or pending work
  def wakeup(): F[Unit]
  
  // Get execution progress for visualization
  def getProgress: F[WIOExecutionProgress[State]]
  
  // Get expected signals at current point
  def getExpectedSignals: F[List[SignalDef[?, ?]]]
}
```

---

### 1.3 ActiveWorkflow

**Purpose**: Internal representation of workflow state (event-sourced).

```scala
case class ActiveWorkflow[Ctx <: WorkflowContext](
  id: WorkflowInstanceId,
  wio: WIO.Initial[Ctx],
  initialState: WCState[Ctx]
) {
  // Current state after all events applied
  def liveState: WCState[Ctx]
  def staticState: WCState[Ctx]
  
  // Next wakeup time (if timer pending)
  def wakeupAt: Option[Instant]
  
  // Expected signals at current point
  def expectedSignals: List[SignalDef[?, ?]]
  
  // Handle signal delivery
  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResult[Event, Resp]
  
  // Handle event replay
  def handleEvent(event: WCEvent[Ctx]): Option[ActiveWorkflow[Ctx]]
  
  // Execute pending RunIO steps
  def proceed(now: Instant): WakeupResult[WCEvent[Ctx]]
  
  // Get execution progress
  def progress: WIOExecutionProgress[WCState[Ctx]]
}
```

**Key insight**: `ActiveWorkflow` is the event-sourced state machine. Events modify the `wio` field, transforming the workflow definition.

---

## 2. Execution Model

### 2.1 Event Sourcing

Workflows4s uses **event sourcing** where:
1. **Events** represent state changes
2. **WIO** is transformed by applying events
3. **Current state** is computed by replaying all events

```
Initial WIO → Event 1 → WIO' → Event 2 → WIO'' → ...
```

### 2.2 Event Types

Events are domain-specific and defined in your `WorkflowContext`:

```scala
object MyWorkflowCtx extends WorkflowContext {
  case class State(value: String, count: Int)
  
  sealed trait Event
  case class ValueSet(newValue: String) extends Event
  case class Incremented() extends Event
  case class ApiCalled(response: String) extends Event
}
```

### 2.3 Event Persistence

- **First run**: Events generated and persisted
- **Recovery**: Events loaded from storage
- **Replay**: Events applied to rebuild state **without** re-executing side effects

**Example flow**:
```
1. Initial: WIO.runIO(callApi)
2. Execute: IO runs, produces ApiCalled("result")
3. Persist: Event saved to storage
4. Restart: Load ApiCalled("result") from storage
5. Apply: Transform WIO without running IO again
```

---

## 3. Runtime Implementations

### 3.1 InMemorySyncRuntime (Synchronous)

**Purpose**: Synchronous, in-memory runtime for testing.

```scala
object InMemorySyncRuntime {
  def create[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine,
    templateId: String = ...
  ): InMemorySyncRuntime[Ctx]
}
```

**Usage**:
```scala
import MyWorkflowCtx._

val workflow: WIO.Initial = WIO
  .pure(State("start", 0))
  .flatMap { state =>
    WIO.pure(state.copy(count = state.count + 1))
  }
  .done

val runtime = InMemorySyncRuntime.create(
  workflow = workflow,
  initialState = State("initial", 0),
  engine = WorkflowInstanceEngine.basic()
)

val instance = runtime.createInstance("instance-1")
val currentState = instance.queryState()
```

**Characteristics**:
- Effect type: `Id` (synchronous)
- Storage: `ConcurrentHashMap` (in-memory)
- Best for: Testing, simple scenarios
- **Not for production**: No persistence

---

### 3.2 InMemoryRuntime (Async)

**Purpose**: Async, in-memory runtime with Cats Effect.

```scala
object InMemoryRuntime {
  def default[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine,
    templateId: String = ...
  ): IO[InMemoryRuntime[Ctx]]
}
```

**Usage**:
```scala
val program: IO[State] = for {
  runtime <- InMemoryRuntime.default(workflow, initialState, engine)
  instance <- runtime.createInstance("instance-1")
  state <- instance.queryState()
} yield state

val result = program.unsafeRunSync()
```

**Characteristics**:
- Effect type: `IO` (async)
- Storage: `Ref[IO, Map[...]]` (in-memory)
- Concurrency-safe with locks (`Semaphore`)
- Best for: Testing async workflows
- **Not for production**: No persistence

---

### 3.3 Production Runtimes

For production, use persistent runtimes (separate modules):

- **`workflows4s-doobie`**: PostgreSQL persistence with Doobie
- **`workflows4s-pekko`**: Apache Pekko integration
- **`workflows4s-filesystem`**: File-based persistence

**Example** (Doobie):
```scala
import workflows4s.doobie.DoobieRuntime

val runtime = DoobieRuntime.create(
  workflow = workflow,
  initialState = initialState,
  transactor = xa,  // Doobie transactor
  engine = engine
)
```

---

## 4. Workflow Instance Engine

### 4.1 WorkflowInstanceEngine

**Purpose**: Pluggable component that intercepts all workflow operations.

```scala
trait WorkflowInstanceEngine {
  def onWakeup[Ctx <: WorkflowContext](
    id: WorkflowInstanceId,
    events: Seq[WCEvent[Ctx]],
    wakeupAt: Option[Instant]
  ): IO[Unit]
  
  def onSignalDelivery[Ctx <: WorkflowContext, Req, Resp](
    id: WorkflowInstanceId,
    signalDef: SignalDef[Req, Resp],
    req: Req,
    event: Option[WCEvent[Ctx]],
    resp: Option[Resp]
  ): IO[Unit]
  
  // ... other hooks
}
```

**Use cases**:
- Logging/metrics
- Wakeup scheduling (KnockerUpper)
- Instance registry
- Distributed tracing

---

### 4.2 Basic Engine

```scala
object WorkflowInstanceEngine {
  def basic(): WorkflowInstanceEngine
}
```

**Features**: No-op implementation, minimal overhead.

---

### 4.3 Enhanced Engines

```scala
// With logging
val engine = WorkflowInstanceEngine
  .basic()
  .withLogging(logger)

// With wakeup scheduling
val engine = WorkflowInstanceEngine
  .basic()
  .withKnockerUpper(knockerUpper)

// With instance registry
val engine = WorkflowInstanceEngine
  .basic()
  .withRegistry(registry)

// Combine all
val engine = WorkflowInstanceEngine
  .basic()
  .withLogging(logger)
  .withKnockerUpper(knockerUpper)
  .withRegistry(registry)
```

---

## 5. Signal Delivery

### 5.1 Delivering Signals

```scala
val approveSignal = SignalDef[ApprovalRequest, ApprovalResponse]()

// Deliver signal
instance.deliverSignal(approveSignal, ApprovalRequest(approved = true)) match {
  case Right(response) => println(s"Approved: $response")
  case Left(UnexpectedSignal(sig)) => println(s"Signal not expected: ${sig.name}")
}
```

### 5.2 Signal Routing (ForEach)

**Purpose**: Route signals to specific sub-workflow instances.

```scala
// Define router in forEach
val router = SignalRouter.create[String]()  // String = element ID

val workflow = WIO
  .forEach { (state: State) => state.items.toSet }
  .withSignalRouter(router)
  .apply { (item: String) =>
    WIO.handleSignal(itemSignal).using[String].purely(...).handleEvent(...).produceResponse(...).done
  }
  .buildOutput(...)
  .done

// Deliver routed signal
instance.deliverRoutedSignal(
  signalRouter = router.sender,
  routingKey = "item-123",
  signalDef = itemSignal,
  req = ItemRequest(...)
)
```

---

## 6. Wakeups & Timers

### 6.1 Wakeup Flow

1. Workflow reaches `Timer` or `RunIO` step
2. Engine registers wakeup time via `KnockerUpper`
3. `KnockerUpper` schedules callback
4. At scheduled time, calls `instance.wakeup()`
5. Instance executes pending operations

### 6.2 KnockerUpper

**Purpose**: Schedule and deliver wakeups.

```scala
trait KnockerUpper {
  def wakeup(instance: WorkflowInstance[IO, ?], at: Instant): IO[Unit]
}
```

**Implementations**:
- `NoOpKnockerUpper`: Does nothing (manual wakeups)
- `SleepingKnockerUpper`: Uses `IO.sleep` (testing)
- `QuartzKnockerUpper`: Uses Quartz Scheduler (production)

**Example**:
```scala
val knockerUpper = QuartzKnockerUpper.create()
val engine = WorkflowInstanceEngine.basic().withKnockerUpper(knockerUpper)
```

---

## 7. Workflow Registry

### 7.1 WorkflowRegistry

**Purpose**: Track active workflow instances and their status.

```scala
trait WorkflowRegistry {
  def register(id: WorkflowInstanceId, status: WorkflowStatus): IO[Unit]
  def unregister(id: WorkflowInstanceId): IO[Unit]
  def find(criteria: SearchCriteria): IO[List[WorkflowInstanceId]]
}

enum WorkflowStatus {
  case Running, Completed, Failed
}
```

**Usage**:
```scala
val registry = InMemoryWorkflowRegistry.create()
val engine = WorkflowInstanceEngine.basic().withRegistry(registry)

// Query active workflows
val activeInstances = registry.find(SearchCriteria(status = Some(Running)))
```

---

## 8. State Management Patterns

### 8.1 Querying State

```scala
// Current state
val state: State = instance.queryState()

// Execution progress (for visualization)
val progress: WIOExecutionProgress[State] = instance.getProgress

// Expected signals
val signals: List[SignalDef[?, ?]] = instance.getExpectedSignals
```

### 8.2 State Transitions

State changes only occur through:
1. **Events**: Generated by `RunIO`, `HandleSignal`, etc.
2. **Pure transformations**: `Pure`, `Transform`, `map`

**Invalid**:
```scala
// ❌ Wrong - mutating state directly
instance.state.counter += 1

// ✅ Correct - produce event that changes state
WIO.pure.makeFrom[State].value(s => s.copy(counter = s.counter + 1)).done
```

---

## 9. Error Handling in Runtime

### 9.1 Workflow Errors

Errors in `WIO` are handled via `handleErrorWith`:

```scala
val workflow = step1
  .flatMap { result =>
    if (result.isValid) step2 
    else WIO.pure.error("Validation failed").done
  }
  .handleErrorWith(
    WIO.pure.makeFrom[(State, String)] { case (state, error) =>
      state.copy(value = s"Error: $error")
    }.done
  )
```

### 9.2 Runtime Errors

IO exceptions in `runIO`:

```scala
val withRetry = WIO
  .runIO[State] { state =>
    IO.delay(callExternalApi(state.value))
      .handleErrorWith { error =>
        IO.raiseError(new RetryableException(error))
      }
  }
  .handleEvent(...)
  .done
  .retryIn {
    case _: RetryableException => 5.seconds
  }
```

---

## 10. Testing Patterns

### 10.1 Synchronous Testing

```scala
import workflows4s.testing.TestUtils

// Create test instance
val workflow: WIO.Initial = ...
val runtime = InMemorySyncRuntime.create(workflow, initialState, engine)
val instance = runtime.createInstance("test-1")

// Test signal handling
val result = instance.deliverSignal(mySignal, request)
assert(result.isRight)

// Test state
val finalState = instance.queryState()
assert(finalState.value == "expected")
```

### 10.2 Async Testing

```scala
val test: IO[Unit] = for {
  runtime <- InMemoryRuntime.default(workflow, initialState, engine)
  instance <- runtime.createInstance("test-1")
  _ <- instance.wakeup()
  state <- instance.queryState()
  _ <- IO(assert(state.value == "expected"))
} yield ()

test.unsafeRunSync()
```

### 10.3 Recording Wakeups

```scala
import workflows4s.testing.RecordingKnockerUpper

val recorder = RecordingKnockerUpper()
val engine = WorkflowInstanceEngine.basic().withKnockerUpper(recorder)

val runtime = InMemorySyncRuntime.create(workflow, initialState, engine)
val instance = runtime.createInstance("test-1")

// Check scheduled wakeups
val wakeups = recorder.getScheduledWakeups()
assert(wakeups.nonEmpty)
```

---

## 11. Production Patterns

### 11.1 Persistent Runtime Setup

```scala
import workflows4s.doobie.DoobieRuntime
import doobie.util.transactor.Transactor

val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
  "org.postgresql.Driver",
  "jdbc:postgresql://localhost/workflows",
  "user",
  "password"
)

val knockerUpper = QuartzKnockerUpper.create()
val registry = PostgresWorkflowRegistry(xa)
val engine = WorkflowInstanceEngine
  .basic()
  .withKnockerUpper(knockerUpper)
  .withRegistry(registry)
  .withLogging(logger)

val runtime: IO[DoobieRuntime[MyWorkflowCtx.Ctx]] = DoobieRuntime.create(
  workflow = myWorkflow,
  initialState = MyWorkflowCtx.State("init", 0),
  transactor = xa,
  engine = engine,
  templateId = "my-workflow-v1"
)
```

### 11.2 Instance Management

```scala
// Create or retrieve instance
val instance: IO[WorkflowInstance[IO, State]] = for {
  rt <- runtime
  inst <- rt.createInstance("user-123-onboarding")
} yield inst

// Deliver signal
instance.flatMap { inst =>
  inst.deliverSignal(approvalSignal, ApprovalRequest(approved = true))
}

// Check status
instance.flatMap { inst =>
  inst.queryState().map { state =>
    println(s"Current value: ${state.value}")
  }
}
```

### 11.3 Monitoring

```scala
// Custom engine for metrics
class MetricsEngine(underlying: WorkflowInstanceEngine) extends WorkflowInstanceEngine {
  override def onWakeup[Ctx](id: WorkflowInstanceId, events: Seq[WCEvent[Ctx]], wakeupAt: Option[Instant]): IO[Unit] = {
    for {
      _ <- IO(metrics.increment("workflow.wakeup"))
      _ <- IO(metrics.gauge("workflow.pending_events", events.size))
      _ <- underlying.onWakeup(id, events, wakeupAt)
    } yield ()
  }
  
  // Override other hooks for metrics
}

val engine = new MetricsEngine(WorkflowInstanceEngine.basic())
```

---

## 12. Visualization

### 12.1 Execution Progress

```scala
val progress: WIOExecutionProgress[State] = instance.getProgress

// Progress contains:
// - Completed steps
// - Current step
// - Pending steps
// - Branch taken/not taken
// - Loop iterations

// Can be rendered to BPMN, Mermaid, etc.
```

### 12.2 Rendering

```scala
import workflows4s.mermaid.MermaidRenderer

val staticDiagram: String = MermaidRenderer.render(workflow)
val progressDiagram: String = MermaidRenderer.render(workflow, progress)
```

---

## 13. Common Patterns

### Pattern 1: Long-Running Approval

```scala
val approvalWorkflow = for {
  _ <- WIO.pure(State("awaiting_approval", 0))
  approval <- WIO.handleSignal(approvalSignal)
                 .using[State]
                 .purely((_, req) => ApprovalEvent(req.approved))
                 .handleEvent((state, evt) => 
                   if (evt.approved) state.copy(value = "approved")
                   else state.copy(value = "rejected")
                 )
                 .produceResponse((_, evt) => ApprovalResponse(evt.approved))
                 .done
  _ <- if (approval.value == "approved") approvedPath else rejectedPath
} yield approval
```

### Pattern 2: Scheduled Retry

```scala
val retryWorkflow = WIO.loop
  .apply(
    WIO.runIO[State](callApi).handleEvent(updateState).done
  )
  .stopWhen { state =>
    state.value.contains("success") || state.counter > 5
  }
  .restart(
    WIO.await(5.minutes).handleEvent((s, _) => s.copy(counter = s.counter + 1)).done
  )
  .done
```

### Pattern 3: Parallel with Timeout

```scala
val parallelWithTimeout = WIO
  .parallel[State, String, State]
  .apply(Seq(task1, task2, task3))
  .collectResults(results => State(results.map(_.value).mkString, results.size))
  .done
  .interruptWith(
    WIO.await(30.seconds)
       .handleEvent((s, _) => s.copy(value = "timeout"))
       .done
       .toInterruption
  )
```

---

## 14. Best Practices

### 1. Use Descriptive IDs
```scala
// ✅ Good
runtime.createInstance("user-123-onboarding")
runtime.createInstance("order-456-fulfillment")

// ❌ Avoid
runtime.createInstance(UUID.randomUUID().toString)
```

### 2. Always Handle Signal Errors
```scala
instance.deliverSignal(signal, request) match {
  case Right(response) => // success
  case Left(UnexpectedSignal(sig)) => 
    logger.warn(s"Signal ${sig.name} not expected at current state")
}
```

### 3. Design for Idempotency
```scala
// Events should be idempotent when replayed
case class ApiCalled(response: String, requestId: String) extends Event

// Handler should handle duplicate events gracefully
def handleEvent(state: State, evt: ApiCalled): State = {
  if (state.processedRequests.contains(evt.requestId)) state
  else state.copy(
    value = evt.response,
    processedRequests = state.processedRequests + evt.requestId
  )
}
```

### 4. Use Named Steps
```scala
// ✅ Good - visible in visualization
WIO.runIO[State](...).handleEvent(...).named("Call Payment API")

// ❌ Avoid - generic names
WIO.runIO[State](...).handleEvent(...).done
```

---

## 15. Next Steps

- **Core API**: See `workflows4s-core-api.md` for WIO builders
- **Usage Examples**: See `workflows4s-usage-examples.md` for real code
- **Best Practices**: See `workflows4s-best-practices.md` for gotchas
