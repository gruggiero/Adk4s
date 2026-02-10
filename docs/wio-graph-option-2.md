# Option 2 — Embedding Raw `WIO` Constructs in `WIOGraph`

This option keeps the `WIOGraph` node set minimal and gives advanced users a direct escape hatch to the full `workflows4s.wio.WIO` API. Instead of relying on prebuilt graph node types for every advanced construct (looping, timers, parallelism, interruption, etc.), you implement custom `WIONode` instances that return raw `WIO` values.

Use Option 2 when you need:

- custom loop or retry semantics that do not fit the default node API
- custom timer persistence rules
- advanced error handling or interruption logic
- parallel workflows with domain-specific interim state

## Key idea

A `WIONode` is just a type-safe wrapper around a `WIO`:

```scala
final case class RawWIONode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  wio: WIO[I, Err, O, Ctx]
) extends WIONode[Ctx, I, Err, O]:
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    wio
```

You can construct any `WIO` with the `workflows4s` builders or the case classes directly, then embed it in your graph:

```scala
val rawNode: RawWIONode[TestContext.type, TestState, Nothing, TestState] =
  RawWIONode[TestContext.type, TestState, Nothing, TestState](
    WIO.Pure[TestContext.type, TestState, Nothing, TestState](
      (state: TestState) => Right(state),
      WIO.Pure.Meta(ErrorMeta.noError, None)
    )
  )
```

## Custom loop node (raw `WIO.Loop`)

```scala
final case class LoopNode[Ctx <: WorkflowContext](
  body: WIO[TestState, TestError, TestState, Ctx],
  restart: WIO[TestState, TestError, TestState, Ctx]
) extends WIONode[Ctx, TestState, TestError, TestState]:
  def toWIO(using errorMeta: ErrorMeta[TestError]): WIO[TestState, TestError, TestState, Ctx] =
    val stopWhen: TestState => Either[TestState, TestState] = (state: TestState) =>
      if state.done then Right(state) else Left(state)
    val meta: WIO.Loop.Meta = WIO.Loop.Meta(None, None, None)
    WIO.Loop[Ctx, TestState, TestError, TestState, TestState, TestState, TestState](
      body,
      stopWhen,
      restart,
      WIO.Loop.State.Forward[Ctx, TestState, TestError, TestState, TestState](body),
      meta,
      Vector.empty
    )
```

## Custom timer node (raw `WIO.Timer`)

```scala
final case class AwaitNode[Ctx <: WorkflowContext](
  duration: WIO.Timer.DurationSource[TestState]
)(using
  releasedEvidence: WIO.Timer.Released <:< WCEvent[Ctx],
  startedEvidence: WIO.Timer.Started <:< WCEvent[Ctx]
) extends WIONode[Ctx, TestState, TestError, TestState]:
  def toWIO(using errorMeta: ErrorMeta[TestError]): WIO[TestState, TestError, TestState, Ctx] =
    val startedHandler: EventHandler[TestState, Unit, WCEvent[Ctx], WIO.Timer.Started] = EventHandler[
      WCEvent[Ctx],
      TestState,
      Unit,
      WIO.Timer.Started
    ](
      detect0 = (evt: WCEvent[Ctx]) => evt match
        case started: WIO.Timer.Started => Some(started)
        case _ => None,
      convert0 = (evt: WIO.Timer.Started) => startedEvidence(evt),
      handle0 = (_: TestState, _: WIO.Timer.Started) => ()
    )

    val releasedHandler: EventHandler[TestState, Either[TestError, TestState], WCEvent[Ctx], WIO.Timer.Released] = EventHandler[
      WCEvent[Ctx],
      TestState,
      Either[TestError, TestState],
      WIO.Timer.Released
    ](
      detect0 = (evt: WCEvent[Ctx]) => evt match
        case released: WIO.Timer.Released => Some(released)
        case _ => None,
      convert0 = (evt: WIO.Timer.Released) => releasedEvidence(evt),
      handle0 = (state: TestState, _: WIO.Timer.Released) => Right(state)
    )

    WIO.Timer[Ctx, TestState, TestError, TestState](
      duration,
      startedHandler,
      None,
      releasedHandler
    )
```

## Custom parallel node (raw `WIO.Parallel`)

```scala
final case class ParallelNode[Ctx <: WorkflowContext](
  workflows: List[WIO[TestState, TestError, WCState[Ctx], Ctx]]
) extends WIONode[Ctx, TestState, TestError, TestState]:
  def toWIO(using errorMeta: ErrorMeta[TestError]): WIO[TestState, TestError, TestState, Ctx] =
    val elements: List[WIO.Parallel.Element[Ctx, TestState, TestError, WCState[Ctx], TestState]] =
      workflows.map((workflow: WIO[TestState, TestError, WCState[Ctx], Ctx]) =>
        val incorporate: (TestState, WCState[Ctx]) => TestState = (state: TestState, _: WCState[Ctx]) => state
        WIO.Parallel.Element[Ctx, TestState, TestError, WCState[Ctx], TestState](workflow, incorporate)
      )
    val formResult: Seq[WCState[Ctx]] => TestState = (results: Seq[WCState[Ctx]]) => results.headOption match
      case Some(state: TestState) => state
      case _ => TestState.empty
    val initial: TestState => TestState = (state: TestState) => state
    WIO.Parallel[Ctx, TestState, TestError, TestState, TestState](
      elements,
      formResult,
      initial
    )
```

## Recommended pattern

1. Build the `WIO` you need using `WIO.build[Ctx]` or the case classes directly.
2. Wrap it in a custom `WIONode` implementation with explicit input/output types.
3. Add that node to your `WIOGraph` like any other node.

This keeps the graph system small and type-safe while preserving full access to advanced `workflows4s` APIs.
