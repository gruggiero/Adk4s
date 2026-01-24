# Workflows4s Usage Examples

Real-world examples from the workflows4s test suite demonstrating common patterns.

---

## Example 1: Simple Pure Workflow

**Source**: `WIOPureTest.scala`

**Purpose**: Pure computation workflow without side effects.

```scala
object MyWorkflowCtx extends WorkflowContext {
  case class State(value: String, counter: Int)
  sealed trait Event
}

import MyWorkflowCtx._

// Simple value workflow
val workflow: WIO.Initial = WIO
  .pure(State("hello", 0))
  .done

// Runtime setup
val runtime = InMemorySyncRuntime.create(
  workflow = workflow,
  initialState = State("initial", 0),
  engine = WorkflowInstanceEngine.basic()
)

val instance = runtime.createInstance("instance-1")
val state = instance.queryState()
// state = State("hello", 0)
```

---

## Example 2: Sequential Composition

**Source**: `WIOFlatMapTest.scala`

**Purpose**: Chain multiple steps together.

```scala
case class State(value: String, count: Int)

// Define steps
val step1: WIO[State, Nothing, State] = WIO
  .pure.makeFrom[State].value { state =>
    state.copy(value = "step1")
  }
  .named("Step 1")

val step2: WIO[State, Nothing, State] = WIO
  .pure.makeFrom[State].value { state =>
    state.copy(count = state.count + 1)
  }
  .named("Step 2")

// Compose with flatMap
val workflow = step1.flatMap { result =>
  step2
}

// Or use >>> operator
val workflow2 = step1 >>> step2

val runtime = InMemorySyncRuntime.create(workflow, State("initial", 0), engine)
val instance = runtime.createInstance("seq-1")
val finalState = instance.queryState()
// finalState = State("step1", 1)
```

---

## Example 3: Signal Handling

**Source**: `WIOHandleSignalTest.scala`

**Purpose**: Wait for external signals to proceed.

```scala
case class ApprovalRequest(approved: Boolean, reason: String)
case class ApprovalResponse(message: String)
case class ApprovalEvent(approved: Boolean, reason: String) extends Event

// Define signal
val approvalSignal = SignalDef[ApprovalRequest, ApprovalResponse]()

// Create workflow that waits for signal
val workflow: WIO.Initial = WIO
  .handleSignal(approvalSignal)
  .using[State]
  .purely { (state, request) =>
    ApprovalEvent(request.approved, request.reason)
  }
  .handleEvent { (state, event) =>
    if (event.approved) state.copy(value = "approved")
    else state.copy(value = "rejected")
  }
  .produceResponse { (state, event) =>
    ApprovalResponse(s"Processed: ${event.approved}")
  }
  .named("Wait for Approval")

val runtime = InMemorySyncRuntime.create(workflow, State("pending", 0), engine)
val instance = runtime.createInstance("approval-1")

// Check expected signals
val expectedSignals = instance.getExpectedSignals
// expectedSignals = List(approvalSignal)

// Deliver signal
val result = instance.deliverSignal(
  approvalSignal, 
  ApprovalRequest(approved = true, reason = "Looks good")
)

result match {
  case Right(response) => 
    println(s"Response: ${response.message}")
    // Response: Processed: true
  case Left(UnexpectedSignal(sig)) =>
    println(s"Signal ${sig.name} not expected")
}

val finalState = instance.queryState()
// finalState = State("approved", 0)
```

---

## Example 4: Side Effects with RunIO

**Source**: `WIORunIOTest.scala`

**Purpose**: Execute IO effects and persist results.

```scala
case class ApiCallEvent(response: String) extends Event

val callExternalApi: WIO[State, String, State] = WIO
  .runIO[State] { state =>
    IO.delay {
      // Actual HTTP call
      val response = httpClient.get(s"https://api.example.com/data?id=${state.value}")
      ApiCallEvent(response)
    }
  }
  .handleEvent { (state, event) =>
    state.copy(value = event.response)
  }
  .named("Call External API")

// With error handling
val callWithError: WIO[State, String, State] = WIO
  .runIO[State] { state =>
    IO.delay {
      if (state.count > 5) throw new RuntimeException("Too many calls")
      ApiCallEvent("success")
    }
  }
  .handleEventWithError { (state, event) =>
    Right(state.copy(value = event.response))
  }
  .done
  .handleErrorWith(
    WIO.pure.makeFrom[(State, String)] { case (state, error) =>
      state.copy(value = s"Error: $error")
    }.done
  )

// Create runtime and execute
val runtime = InMemorySyncRuntime.create(callExternalApi, State("initial", 0), engine)
val instance = runtime.createInstance("api-1")

// Trigger execution
instance.wakeup()

val state = instance.queryState()
// state = State("<api response>", 0)
```

---

## Example 5: Branching

**Source**: `WIOForkTest.scala` (implied)

**Purpose**: Conditional workflow execution.

```scala
case class State(value: String, score: Int)

val highScoreBranch: WIO[State, Nothing, State] = WIO
  .pure.makeFrom[State].value(_.copy(value = "high_score"))
  .named("High Score Branch")

val lowScoreBranch: WIO[State, Nothing, State] = WIO
  .pure.makeFrom[State].value(_.copy(value = "low_score"))
  .named("Low Score Branch")

val mediumScoreBranch: WIO[State, Nothing, State] = WIO
  .pure.makeFrom[State].value(_.copy(value = "medium_score"))
  .named("Medium Score Branch")

val workflow: WIO.Initial = WIO
  .fork[State, Nothing, State]
  .on { state => 
    if (state.score > 80) Some(state) else None 
  }
  .branch(highScoreBranch)
  .on { state => 
    if (state.score < 40) Some(state) else None 
  }
  .branch(lowScoreBranch)
  .otherwise(mediumScoreBranch)
  .done

val runtime = InMemorySyncRuntime.create(workflow, State("initial", 0), engine)

// Test high score
val instance1 = runtime.createInstance("test-1")
val state1 = instance1.queryState()
// state1 = State("high_score", 90) if initial score was 90

// Test low score
val instance2 = runtime.createInstance("test-2")  
val state2 = instance2.queryState()
// state2 = State("low_score", 30) if initial score was 30
```

---

## Example 6: Loops

**Source**: `WIOLoopTest.scala`

**Purpose**: Retry logic with loop construct.

```scala
case class State(attempts: Int, success: Boolean, result: String)

val attemptCall: WIO[State, String, State] = WIO
  .runIO[State] { state =>
    IO.delay {
      val success = scala.util.Random.nextBoolean()
      ApiCallEvent(if (success) "success" else "failed")
    }
  }
  .handleEvent { (state, event) =>
    state.copy(
      attempts = state.attempts + 1,
      success = event.response == "success",
      result = event.response
    )
  }
  .named("Attempt API Call")

val incrementAttempts: WIO[State, String, State] = WIO
  .pure.makeFrom[State].value { state =>
    state.copy(attempts = state.attempts + 1)
  }
  .named("Increment Attempts")

val retryWorkflow: WIO.Initial = WIO
  .loop
  .apply(attemptCall)
  .stopWhen { state =>
    if (state.success) true
    else if (state.attempts >= 5) true
    else false
  }
  .restart(incrementAttempts)
  .named("Retry Loop")

val runtime = InMemorySyncRuntime.create(
  retryWorkflow, 
  State(0, false, ""), 
  engine
)

val instance = runtime.createInstance("retry-1")

// Execute loop (will retry until success or max attempts)
var attempts = 0
while (attempts < 10) {
  instance.wakeup()
  val state = instance.queryState()
  if (state.success || state.attempts >= 5) {
    println(s"Completed after ${state.attempts} attempts: ${state.result}")
    // Completed after 3 attempts: success
    attempts = 10
  }
  attempts += 1
}
```

---

## Example 7: Parallel Execution

**Source**: `WIOParallelTest.scala`

**Purpose**: Execute multiple workflows concurrently.

```scala
case class State(results: List[String], count: Int)

val task1: WIO[State, String, String] = WIO
  .runIO[State] { _ =>
    IO.delay {
      Thread.sleep(100)
      TaskEvent("task1-result")
    }
  }
  .handleEvent { (_, event) => event.result }
  .named("Task 1")

val task2: WIO[State, String, String] = WIO
  .runIO[State] { _ =>
    IO.delay {
      Thread.sleep(150)
      TaskEvent("task2-result")
    }
  }
  .handleEvent { (_, event) => event.result }
  .named("Task 2")

val task3: WIO[State, String, String] = WIO
  .runIO[State] { _ =>
    IO.delay {
      Thread.sleep(50)
      TaskEvent("task3-result")
    }
  }
  .handleEvent { (_, event) => event.result }
  .named("Task 3")

val parallelWorkflow: WIO.Initial = WIO
  .parallel[State, String, State]
  .apply(Seq(task1, task2, task3))
  .collectResults { results =>
    State(results.toList, results.size)
  }
  .named("Parallel Execution")

val runtime = InMemorySyncRuntime.create(
  parallelWorkflow, 
  State(List(), 0), 
  engine
)

val instance = runtime.createInstance("parallel-1")

// All tasks execute
instance.wakeup()

val finalState = instance.queryState()
// finalState = State(List("task1-result", "task2-result", "task3-result"), 3)
```

---

## Example 8: ForEach (Map over Collection)

**Source**: `WIOForEachTest.scala`

**Purpose**: Process each element of a collection.

```scala
case class State(items: Set[String], results: Map[String, String])
case class ItemProcessed(item: String, result: String) extends Event

val processItem: WIO[String, String, String] = WIO
  .runIO[String] { item =>
    IO.delay {
      // Process item
      val processed = item.toUpperCase
      ItemProcessed(item, processed)
    }
  }
  .handleEvent { (item, event) =>
    event.result
  }
  .named("Process Item")

val forEachWorkflow: WIO.Initial = WIO
  .forEach { (state: State) => state.items }
  .apply(processItem)
  .buildOutput { (state, results) =>
    state.copy(results = results)
  }
  .named("Process All Items")

val runtime = InMemorySyncRuntime.create(
  forEachWorkflow,
  State(Set("item1", "item2", "item3"), Map()),
  engine
)

val instance = runtime.createInstance("foreach-1")

// Process all items
instance.wakeup()

val finalState = instance.queryState()
// finalState = State(
//   Set("item1", "item2", "item3"),
//   Map("item1" -> "ITEM1", "item2" -> "ITEM2", "item3" -> "ITEM3")
// )
```

---

## Example 9: Timers

**Source**: Test utilities

**Purpose**: Schedule workflow wakeups.

```scala
import scala.concurrent.duration._

case class State(value: String, timedOut: Boolean)
case class TimerReleased(at: Instant) extends Event

val waitOneHour: WIO[State, Nothing, State] = WIO
  .await(1.hour)
  .handleEvent { (state, _) =>
    state.copy(timedOut = true, value = "timeout")
  }
  .named("Wait 1 Hour")

// Dynamic timeout based on state
val dynamicTimeout: WIO[State, Nothing, State] = WIO
  .awaitDynamic { state =>
    if (state.value == "urgent") 5.minutes
    else 1.hour
  }
  .handleEvent { (state, _) =>
    state.copy(timedOut = true)
  }
  .named("Dynamic Timeout")

val runtime = InMemorySyncRuntime.create(
  waitOneHour,
  State("waiting", false),
  engine
)

val instance = runtime.createInstance("timer-1")

// Timer is now pending
val wakeupTime = instance.getProgress  // Shows timer waiting

// In real runtime, KnockerUpper would call wakeup() at scheduled time
// For testing, manually trigger:
instance.wakeup()

val state = instance.queryState()
// state = State("timeout", true)
```

---

## Example 10: Retry with Backoff

**Source**: `WIORetryTest.scala`

**Purpose**: Automatic retry with exponential backoff.

```scala
case class State(attempts: Int, result: Option[String])
case class ApiCallEvent(success: Boolean, data: String) extends Event

val unreliableApi: WIO[State, String, State] = WIO
  .runIO[State] { state =>
    IO.delay {
      if (scala.util.Random.nextBoolean()) {
        ApiCallEvent(true, "success data")
      } else {
        throw new IOException("Network error")
      }
    }
  }
  .handleEvent { (state, event) =>
    state.copy(result = Some(event.data))
  }
  .named("Unreliable API Call")

// Retry with fixed delay
val withFixedRetry = unreliableApi.retryIn {
  case _: IOException => 5.seconds
  case _: TimeoutException => 10.seconds
}

// Retry with exponential backoff
val withExponentialRetry = unreliableApi.retry { (error, state, now) =>
  IO.pure {
    error match {
      case _: IOException if state.attempts < 5 =>
        val delaySeconds = Math.pow(2, state.attempts).toLong
        Some(now.plusSeconds(delaySeconds))
      case _ => None  // Don't retry
    }
  }
}

val runtime = InMemorySyncRuntime.create(
  withFixedRetry,
  State(0, None),
  engine
)

val instance = runtime.createInstance("retry-1")

// First attempt fails, schedules retry
instance.wakeup()

// Manually advance time and retry (in real runtime, KnockerUpper handles this)
// ... retry happens automatically

val finalState = instance.queryState()
// finalState = State(3, Some("success data")) after successful retry
```

---

## Example 11: Interruption (Signal or Timer)

**Source**: `WIOHandleInterruptionTest.scala` (implied)

**Purpose**: Interrupt long-running task with signal or timeout.

```scala
case class CancellationSignal() 
case class CancellationResponse(cancelled: Boolean)

val cancelSignal = SignalDef[CancellationSignal, CancellationResponse]()

val longRunningTask: WIO[State, String, State] = WIO
  .runIO[State] { state =>
    IO.sleep(1.hour) *> IO.pure(TaskCompleted())
  }
  .handleEvent { (state, _) =>
    state.copy(value = "completed")
  }
  .named("Long Running Task")

// Interrupt with signal
val cancellationHandler: WIO[State, String, State] = WIO
  .handleSignal(cancelSignal)
  .using[State]
  .purely { (_, _) => CancellationEvent() }
  .handleEvent { (state, _) =>
    state.copy(value = "cancelled")
  }
  .produceResponse { (_, _) =>
    CancellationResponse(cancelled = true)
  }
  .done

val interruptibleWithSignal = longRunningTask.interruptWith(
  cancellationHandler.toInterruption
)

// Interrupt with timeout
val timeoutHandler: WIO[State, String, State] = WIO
  .await(30.minutes)
  .handleEvent { (state, _) =>
    state.copy(value = "timeout")
  }
  .done

val interruptibleWithTimeout = longRunningTask.interruptWith(
  timeoutHandler.toInterruption
)

val runtime = InMemorySyncRuntime.create(
  interruptibleWithSignal,
  State("running", 0),
  engine
)

val instance = runtime.createInstance("interrupt-1")

// Start long task
instance.wakeup()

// Deliver cancellation signal before task completes
val response = instance.deliverSignal(cancelSignal, CancellationSignal())
// response = Right(CancellationResponse(true))

val state = instance.queryState()
// state = State("cancelled", 0)
```

---

## Example 12: Checkpointing

**Source**: `WIOCheckpointTest.scala` (implied)

**Purpose**: Create intermediate checkpoints for recovery.

```scala
case class CheckpointEvent(state: State) extends Event

val step1: WIO[State, Nothing, State] = WIO
  .pure.makeFrom[State].value { state =>
    state.copy(value = "step1", count = state.count + 1)
  }
  .named("Step 1")

val step2: WIO[State, Nothing, State] = WIO
  .runIO[State] { state =>
    IO.delay(ProcessingEvent("step2"))
  }
  .handleEvent { (state, _) =>
    state.copy(value = "step2", count = state.count + 1)
  }
  .named("Step 2")

// Add checkpoints
val withCheckpoints = (step1.checkpointed(
  genEvent = (in, out) => CheckpointEvent(out),
  handleEvent = (in, evt) => evt.state
) >>> step2.checkpointed(
  genEvent = (in, out) => CheckpointEvent(out),
  handleEvent = (in, evt) => evt.state
))

// If workflow crashes after step1, it resumes from checkpoint
val runtime = InMemorySyncRuntime.create(
  withCheckpoints,
  State("initial", 0),
  engine
)

val instance = runtime.createInstance("checkpoint-1")
val state = instance.queryState()
// state = State("step2", 2)

// On replay, checkpoint events are used instead of re-executing
```

---

## Common Patterns

### Pattern 1: Async IO with Error Handling

```scala
val robustApiCall = WIO
  .runIO[State] { state =>
    IO.blocking {
      httpClient.get(state.value)
    }.timeout(30.seconds)
      .handleErrorWith {
        case _: TimeoutException => IO.raiseError(new RetryableException("Timeout"))
        case _: IOException => IO.raiseError(new RetryableException("Network error"))
        case e => IO.raiseError(e)
      }
  }
  .handleEventWithError { (state, event) =>
    Right(state.copy(result = Some(event.response)))
  }
  .done
  .retryIn {
    case _: RetryableException => 5.seconds
  }
  .handleErrorWith(
    WIO.pure.makeFrom[(State, Throwable)] { case (state, error) =>
      state.copy(error = Some(error.getMessage))
    }.done
  )
```

### Pattern 2: Conditional Retry

```scala
val conditionalRetry = apiCall.retry { (error, state, now) =>
  IO.pure {
    if (state.retries < 3 && isRetryable(error)) {
      Some(now.plusSeconds(Math.pow(2, state.retries).toLong))
    } else None
  }
}
```

### Pattern 3: Parallel with Partial Failure

```scala
val robustParallel = WIO
  .parallel[State, String, State]
  .apply(Seq(
    task1.handleErrorWith(errorHandler),
    task2.handleErrorWith(errorHandler),
    task3.handleErrorWith(errorHandler)
  ))
  .collectResults { results =>
    State(successfulResults = results.filter(_.success))
  }
  .done
```

---

## Next Steps

- **Core API**: See `workflows4s-core-api.md` for WIO builders
- **Runtime**: See `workflows4s-runtime-patterns.md` for execution
- **Best Practices**: See `workflows4s-best-practices.md` for gotchas
