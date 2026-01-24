# Workflows4s Core API Reference

## Overview

**Workflows4s** is a Scala library for building long-running, stateful workflows with native event sourcing, Cats Effect integration, and BPMN rendering. It merges Temporal's execution model with type safety and functional programming.

**Version Analyzed**: Latest (Scala 3.3+, cross-compiles to 2.13)

**Core Philosophy**: Pure, composable workflow definitions with event-sourced execution.

---

## 1. Architecture Overview

### Key Components

```
WIO (Workflow Definition) ←─ Your Business Logic
    ↓
WorkflowRuntime ←─ Runtime Dependencies (storage, clock, etc.)
    ↓
WorkflowInstance ←─ Events (event-sourced state)
    ↓
WorkflowInstanceEngine ←─ KnockerUpper, WorkflowRegistry
```

### Execution Model

1. **First Run**: Workflow executes operations, each producing persisted events
2. **Recovery**: Events replayed from storage to rebuild state without re-executing side effects
3. **Signals**: External events trigger state transitions
4. **Timers**: Scheduled wakeups for time-based logic

---

## 2. Core Abstractions

### 2.1 WIO (Workflow Input/Output)

**Purpose**: Pure value describing workflow structure - the core abstraction.

```scala
sealed trait WIO[-In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext]
```

**Type parameters**:
- `In`: Input state type (contravariant)
- `Err`: Error type (covariant)
- `Out`: Output state type (covariant, must be subtype of context state)
- `Ctx`: Workflow context defining `State` and `Event` types

**Key point**: `WIO` is a **pure value** - no effects executed during construction.

---

### 2.2 WorkflowContext

**Purpose**: Type-level configuration for workflow state and events.

```scala
trait WorkflowContext { ctx: WorkflowContext =>
  type Event  // Your event ADT
  type State  // Your state type
  type Ctx = WorkflowContext.AUX[State, Event]
  
  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[In, Err, Out, Ctx]
  object WIO extends AllBuilders[Ctx] {
    type Branch[-In, +Err, +Out <: State]  = workflows4s.wio.WIO.Branch[In, Err, Out, Ctx, ?]
    type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Ctx, Err, Out]
    type Draft                             = WIO[Any, Nothing, Nothing]
    type Initial                           = workflows4s.wio.WIO.Initial[Ctx]
  }
}
```

**Usage**:
```scala
object MyWorkflowCtx extends WorkflowContext {
  // Define your state type
  case class State(value: String, counter: Int)
  
  // Define your event ADT
  sealed trait Event
  case class ValueChanged(newValue: String) extends Event
  case class Incremented() extends Event
}
```

**Key insight**: `WorkflowContext` provides scoped type aliases and builders for your specific workflow.

---

### 2.3 WIO Constructors (Builders)

All workflow construction goes through fluent builders accessed via `WIO` object.

#### Pure Computation

```scala
object PureBuilder {
  def pure: Step1
  
  // Construct from value
  def pure[Out <: State](value: => Out): WIO[Any, Nothing, Out]
  
  // Construct from function
  def pure.makeFrom[In].value[Out](f: In => Out): WIO[In, Nothing, Out]
  
  // Construct error
  def pure.error[Err](value: => Err): WIO[Any, Err, Nothing]
  def pure.makeFrom[In].error[Err](f: In => Err): WIO[In, Err, Nothing]
  
  // Either-returning function
  def pure.makeFrom[In].apply[Err, Out](f: In => Either[Err, Out]): WIO[In, Err, Out]
}
```

**Example**:
```scala
import MyWorkflowCtx._

// Simple value
val step1: WIO[Any, Nothing, State] = WIO.pure(State("hello", 0))

// From input
val step2: WIO[State, Nothing, State] = 
  WIO.pure.makeFrom[State].value(s => s.copy(counter = s.counter + 1))

// With error handling
val step3: WIO[State, String, State] = 
  WIO.pure.makeFrom[State] { state =>
    if (state.counter > 10) Left("Counter too high")
    else Right(state.copy(counter = state.counter + 1))
  }.done
```

---

#### Side Effects (RunIO)

```scala
object RunIOBuilder {
  def runIO[Input](f: Input => IO[Evt]): Step2[Input, Evt]
  
  // Handle event success
  def handleEvent[Out](f: (In, Evt) => Out): Step3[Out, Nothing]
  
  // Handle event with errors
  def handleEventWithError[Err, Out](f: (In, Evt) => Either[Err, Out]): Step3[Out, Err]
}
```

**Example**:
```scala
case class ApiCalled(response: String) extends Event

val callApi: WIO[State, String, State] = WIO
  .runIO[State] { state =>
    IO.delay {
      // Side effect: HTTP call
      val response = httpClient.get(s"https://api.example.com/${state.value}")
      ApiCalled(response)
    }
  }
  .handleEvent { (state, event) =>
    state.copy(value = event.response)
  }
  .named("Call External API")
```

**Key**: `runIO` captures side effects in `IO`, ensuring they're only executed once and persisted as events.

---

#### Signal Handling

```scala
case class SignalDef[Req, Resp](id: String, explicitName: Option[String])

object HandleSignalBuilder {
  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp]): Step1
  
  // Define intermediate event type
  def using[IntermediateState]: Step2
  
  // Generate event with side effects
  def withSideEffects(f: (In, Req) => IO[Evt]): Step3
  
  // Generate event purely
  def purely(f: (In, Req) => Evt): Step3
  
  // Handle the event
  def handleEvent(f: (In, Evt) => Out): Step4
  def handleEventWithError(f: (In, Evt) => Either[Err, Out]): Step4
  
  // Produce response
  def produceResponse(f: (In, Evt) => Resp): WIO
}
```

**Example**:
```scala
// Define signal
val approveSignal = SignalDef[ApprovalRequest, ApprovalResponse]()

case class ApprovalReceived(approved: Boolean, reason: String) extends Event

val waitForApproval: WIO[State, Nothing, State] = WIO
  .handleSignal(approveSignal)
  .using[State]
  .purely { (state, request) =>
    ApprovalReceived(request.approved, request.reason)
  }
  .handleEvent { (state, event) =>
    if (event.approved) state.copy(value = "approved")
    else state.copy(value = "rejected")
  }
  .produceResponse { (state, event) =>
    ApprovalResponse(s"Processed: ${event.approved}")
  }
  .done
```

---

#### Timers (Await)

```scala
object AwaitBuilder {
  def await(duration: Duration): Step2
  def awaitUntil(instant: Instant): Step2
  def awaitDynamic(f: In => Duration): Step2
  
  // Handle timer release
  def handleEvent(f: (In, Timer.Released) => Out): WIO
}
```

**Example**:
```scala
import scala.concurrent.duration._

val waitOneHour: WIO[State, Nothing, State] = WIO
  .await(1.hour)
  .handleEvent { (state, _) =>
    state.copy(value = "timeout reached")
  }
  .named("Wait 1 Hour")

// Dynamic timeout based on state
val waitDynamic: WIO[State, Nothing, State] = WIO
  .awaitDynamic { state =>
    if (state.counter > 5) 1.hour else 10.minutes
  }
  .handleEvent { (state, _) => state }
  .done
```

---

#### Branching (Fork)

```scala
object ForkBuilder {
  def fork: Step1
  
  def on[BranchIn](condition: In => Option[BranchIn]): BranchStep
  
  def branch(wio: WIO[BranchIn, Err, Out]): ForkBuilder
  
  def otherwise(wio: WIO[In, Err, Out]): WIO
}
```

**Example**:
```scala
val branchWorkflow: WIO[State, String, State] = WIO
  .fork[State, String, State]
  .on { state => 
    if (state.counter > 10) Some(state) else None 
  }
  .branch(
    WIO.pure.makeFrom[State].value(_.copy(value = "high"))
        .named("High Counter Branch")
  )
  .on { state => 
    if (state.counter < 5) Some(state) else None 
  }
  .branch(
    WIO.pure.makeFrom[State].value(_.copy(value = "low"))
        .named("Low Counter Branch")
  )
  .otherwise(
    WIO.pure.makeFrom[State].value(_.copy(value = "medium"))
        .named("Medium Counter Branch")
  )
  .done
```

---

#### Loops

```scala
object LoopBuilder {
  def loop: Step1
  
  def apply(body: WIO[BodyIn, Err, BodyOut]): Step2
  
  // Exit condition
  def stopWhen(condition: BodyOut => Boolean): Step3
  def stopWhen(condition: BodyOut => Either[ReturnIn, Out]): Step3
  
  // Restart logic
  def restart(wio: WIO[ReturnIn, Err, BodyIn]): WIO
}
```

**Example**:
```scala
val retryLoop: WIO[State, String, State] = WIO
  .loop
  .apply(
    callApi.transformInput[State](identity)
  )
  .stopWhen { state =>
    if (state.value.contains("success")) true
    else if (state.counter > 5) true
    else false
  }
  .restart(
    WIO.pure.makeFrom[State].value(s => s.copy(counter = s.counter + 1))
  )
  .named("Retry Until Success")
```

---

#### Parallel Execution

```scala
object ParallelBuilder {
  def parallel: Step1
  
  def apply(wios: Seq[WIO[In, Err, Out]]): Step2
  
  def collectResults[Out](f: Seq[State] => Out): WIO
}
```

**Example**:
```scala
val parallelCalls: WIO[State, String, State] = WIO
  .parallel[State, String, State]
  .apply(Seq(
    callApi1,
    callApi2,
    callApi3
  ))
  .collectResults { results =>
    State(results.map(_.value).mkString(","), results.size)
  }
  .done
```

---

#### ForEach (Map over collection)

```scala
object ForEachBuilder {
  def forEach[Elem](getElements: In => Set[Elem]): Step2
  
  def apply(elemWorkflow: WIO[Elem, Err, ElemOut]): Step3
  
  def buildOutput(f: (In, Map[Elem, ElemOut]) => Out): WIO
}
```

**Example**:
```scala
case class ProcessingState(items: List[String], results: Map[String, String])

val processItems: WIO[ProcessingState, String, ProcessingState] = WIO
  .forEach { (state: ProcessingState) => state.items.toSet }
  .apply { (item: String) =>
    WIO.runIO[String] { item =>
      IO.delay(s"processed: $item")
    }.handleEvent { (_, result) => result }
      .done
  }
  .buildOutput { (state, results) =>
    state.copy(results = results)
  }
  .done
```

---

## 3. WIO Combinators (Extension Methods)

### 3.1 Sequencing

```scala
trait WIOMethods[Ctx, -In, +Err, +Out <: WCState[Ctx]] {
  
  // Monadic bind
  def flatMap[Err1 >: Err, Out1](f: Out => WIO[Out, Err1, Out1]): WIO[In, Err1, Out1]
  
  // Sequential composition (discards intermediate state)
  def andThen[Err1 >: Err, Out1](next: WIO[Out, Err1, Out1]): WIO[In, Err1, Out1]
  def >>>[Err1 >: Err, Out1](next: WIO[Out, Err1, Out1]): WIO[In, Err1, Out1] // Operator alias
  
  // Map output
  def map[Out1](f: Out => Out1): WIO[In, Err, Out1]
}
```

**Usage**:
```scala
// flatMap - depends on previous result
val workflow1 = step1.flatMap { result =>
  if (result.counter > 5) step2A else step2B
}

// andThen - sequential execution
val workflow2 = step1 >>> step2 >>> step3

// map - transform output
val workflow3 = step1.map(state => state.copy(value = state.value.toUpperCase))
```

---

### 3.2 Input/Output Transformation

```scala
trait WIOMethods[Ctx, -In, +Err, +Out] {
  // Transform input
  def transformInput[NewIn](f: NewIn => In): WIO[NewIn, Err, Out]
  def provideInput(value: In): WIO[Any, Err, Out]
  
  // Transform output
  def transformOutput[NewOut](f: (In, Out) => NewOut): WIO[In, Err, NewOut]
  
  // Transform both
  def transform[NewIn, NewOut](f: NewIn => In, g: (NewIn, Out) => NewOut): WIO[NewIn, Err, NewOut]
}
```

**Usage**:
```scala
// Provide constant input
val withInput = workflow.provideInput(State("initial", 0))

// Transform input
val mapped = workflow.transformInput[String](str => State(str, 0))

// Transform output
val enriched = workflow.transformOutput((input, output) => 
  output.copy(value = s"${input.value} -> ${output.value}")
)
```

---

### 3.3 Error Handling

```scala
trait WIOMethods[Ctx, -In, +Err, +Out] {
  // Handle errors with another workflow
  def handleErrorWith[Err1, Out1 >: Out](
    wio: WIO[(WCState[Ctx], Err), Err1, Out1]
  ): WIO[In, Err1, Out1]
}
```

**Usage**:
```scala
val withErrorHandling = workflow.handleErrorWith(
  WIO.pure.makeFrom[(State, String)] { case (state, error) =>
    state.copy(value = s"Error: $error")
  }.done
)
```

---

### 3.4 Retry

```scala
trait WIOMethods[Ctx, -In, +Err, +Out] {
  type Now = Instant
  
  // Retry with custom logic
  def retry(onError: (Throwable, WCState[Ctx], Now) => IO[Option[Instant]]): WIO[In, Err, Out]
  
  // Retry with fixed delays
  def retryIn(onError: PartialFunction[Throwable, Duration]): WIO[In, Err, Out]
}
```

**Usage**:
```scala
val withRetry = callApi.retryIn {
  case _: TimeoutException => 5.seconds
  case _: IOException => 10.seconds
}

// Custom retry logic
val withCustomRetry = callApi.retry { (error, state, now) =>
  IO.pure {
    error match {
      case _: RetryableException if state.counter < 3 =>
        Some(now.plusSeconds(Math.pow(2, state.counter).toLong))
      case _ => None
    }
  }
}
```

---

### 3.5 Interruptions (Timers & Signals)

```scala
trait WIOMethods[Ctx, -In, +Err, +Out] {
  def interruptWith(interruption: WIO.Interruption[Ctx, Err, Out]): WIO.HandleInterruption[Ctx, In, Err, Out]
}

// Interruption builders
val signalInterruption = signalHandler.toInterruption
val timerInterruption = timerHandler.toInterruption

// Use interruption
val interruptibleWorkflow = longRunningTask.interruptWith(signalInterruption)
```

---

### 3.6 Checkpointing

```scala
trait WIOMethods[Ctx, -In, +Err, +Out] {
  def checkpointed[Evt](
    genEvent: (In, Out) => Evt,
    handleEvent: (In, Evt) => Out
  ): WIO[In, Err, Out]
}
```

**Usage**:
```scala
case class Checkpointed(state: State) extends Event

val withCheckpoint = workflow.checkpointed(
  genEvent = (in, out) => Checkpointed(out),
  handleEvent = (in, evt) => evt.state
)
```

---

## 4. Type System & Error Handling

### 4.1 ErrorMeta

**Purpose**: Track error types at compile time for visualization and debugging.

```scala
sealed trait ErrorMeta[T] {
  def nameOpt: Option[String]
}

object ErrorMeta {
  case class NoError[T]() extends ErrorMeta[T]
  case class Present[T](name: String) extends ErrorMeta[T]
  
  // Given instances
  given noError: ErrorMeta[Nothing]
  given fromClassTag[T](using ClassTag[T]): ErrorMeta[T]
}
```

**Usage**:
```scala
// Auto-derived from type
val step: WIO[State, String, State] = 
  WIO.pure.makeFrom[State].error(_.value).done  // ErrorMeta.Present("String")

// Explicit error metadata
val step2: WIO[State, String, State] = 
  WIO.pure.makeFrom[State].error(_.value)(using ErrorMeta.Present("ValidationError")).done
```

---

### 4.2 SignalDef

**Purpose**: Type-safe signal definition with request/response types.

```scala
case class SignalDef[Req, Resp](
  id: String, 
  explicitName: Option[String]
)(using val reqCt: ClassTag[Req], val respCt: ClassTag[Resp])

object SignalDef {
  def apply[Req: ClassTag, Resp: ClassTag](
    id: String = null, 
    name: String = null
  ): SignalDef[Req, Resp]
}
```

**Usage**:
```scala
// Auto-generated ID
val signal1 = SignalDef[ApprovalRequest, ApprovalResponse]()

// Explicit ID and name
val signal2 = SignalDef[ApprovalRequest, ApprovalResponse](
  id = "approval-signal-123",
  name = "Approval Signal"
)
```

---

## 5. Metadata & Naming

All WIO constructors support metadata attachment for visualization and debugging.

### Naming Methods

```scala
// Explicit name
.named("My Step Name")

// Auto-derived from variable name
val myStep = WIO.pure(...).autoNamed  // "My Step"

// With description
.named("API Call", "Calls external service")

// For signals
.named(signalName = "CustomSignalName", operationName = "Process Signal")
```

---

## 6. Integration with Cats Effect

### 6.1 IO in WIO

`WIO` integrates seamlessly with Cats Effect `IO`:

```scala
// RunIO captures IO effects
WIO.runIO[State] { state =>
  for {
    result1 <- externalService.call(state.value)
    result2 <- database.save(result1)
  } yield MyEvent(result2)
}
```

**Key**: Effects in `runIO` are executed exactly once and their results persisted as events.

### 6.2 Event Sourcing Guarantees

- **First execution**: IO runs, event persisted
- **Replay**: Event loaded from storage, IO **not** re-executed
- **Idempotency**: Multiple replays produce same state without side effects

---

## 7. Type Aliases

```scala
// Common type aliases in WorkflowContext
type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[In, Err, Out, Ctx]
type Draft = WIO[Any, Nothing, Nothing]
type Initial = WIO[Any, Nothing, State]
type Branch[-In, +Err, +Out <: State] = workflows4s.wio.WIO.Branch[In, Err, Out, Ctx, ?]
type Interruption[+Err, +Out <: State] = workflows4s.wio.WIO.Interruption[Ctx, Err, Out]

// Package-level aliases
type WCState[Ctx <: WorkflowContext] = WorkflowContext.State[Ctx]
type WCEvent[Ctx <: WorkflowContext] = WorkflowContext.Event[Ctx]
```

---

## 8. Quick Reference

### Creating Workflows

```scala
// Pure computation
WIO.pure(value)
WIO.pure.makeFrom[In].value(f)

// Side effects
WIO.runIO[In](in => IO(event)).handleEvent((in, evt) => out).done

// Signals
WIO.handleSignal(signalDef).using[In].purely(f).handleEvent(f).produceResponse(f).done

// Timers
WIO.await(duration).handleEvent(f).done

// Branching
WIO.fork.on(condition).branch(wio).otherwise(wio).done

// Loops
WIO.loop.apply(body).stopWhen(condition).restart(restart).done
```

### Composing Workflows

```scala
// Sequential
step1 >>> step2 >>> step3
step1.andThen(step2)

// Conditional
step1.flatMap { result => if (result.flag) step2 else step3 }

// Error handling
step1.handleErrorWith(errorHandler)

// Retry
step1.retryIn { case _: Exception => 5.seconds }
```

---

## 9. Next Steps

- **Runtime Patterns**: See `workflows4s-runtime-patterns.md` for execution
- **Usage Examples**: See `workflows4s-usage-examples.md` for real-world code
- **Best Practices**: See `workflows4s-best-practices.md` for gotchas and patterns
