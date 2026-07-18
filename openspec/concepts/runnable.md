# Concept: Runnable

## Concept specification

```
concept Runnable[I, O]
purpose
    A universal computation with four execution modes (invoke, stream,
    collect, transform) and composable combinators (andThen, map, evalMap,
    contramap, timeout, handleError, withRetry, withFallback, parallel).
state
    # stateless interface; implementations capture mode functions
actions
    invoke [ input: I ]
        => [ output: O ]
    stream [ input: I ]
        => [ chunks: Stream[IO, O] ]
    collect [ input: Stream[IO, I] ]
        => [ output: O ]
    transform [ input: Stream[IO, I] ]
        => [ chunks: Stream[IO, O] ]
    andThen [ next: Runnable[O, O2] ]
        => [ Runnable[I, O2] ]
    parallel [ r1: Runnable[I, O1] ; r2: Runnable[I, O2] ]
        => [ Runnable[I, (O1, O2)] ]
    timeout [ duration: FiniteDuration ]
        => [ Runnable[I, O] ]   # raises TimeoutException on expiry
    handleError [ handler: Throwable => IO[O] ]
        => [ Runnable[I, O] ]
operational principle
    A caller builds a Runnable via one of the four constructors
    (fromInvoke, fromStream, fromCollect, fromTransform) or `full` for all
    four. Each mode derives the others: e.g. fromInvoke's stream wraps the
    result in a single-element stream; fromStream's invoke takes
    .compile.lastOrError. Combinators return new Runnables that delegate to
    the underlying modes.
```

## Implementation map

| Element | Code |
|---|---|
| trait `Runnable` | `trait Runnable[I, O]` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| action `invoke` | `Runnable.invoke(input: I): IO[O]` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| action `stream` | `Runnable.stream(input: I): Stream[IO, O]` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| action `collect` | `Runnable.collect(input: Stream[IO, I]): IO[O]` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| action `transform` | `Runnable.transform(input: Stream[IO, I]): Stream[IO, O]` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| factory `fromInvoke` | `Runnable.fromInvoke[I, O](f: I => IO[O])` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| factory `fromStream` | `Runnable.fromStream[I, O](f: I => Stream[IO, O])` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| factory `fromCollect` | `Runnable.fromCollect[I, O](f: Stream[IO, I] => IO[O])` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| factory `fromTransform` | `Runnable.fromTransform[I, O](f: Stream[IO, I] => Stream[IO, O])` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| factory `full` | `Runnable.full[I, O](invokeFn, streamFn, collectFn, transformFn)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`) |
| combinator `andThen` | `RunnableOps.andThen[O2](next)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `map` | `RunnableOps.map[O2](f)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `evalMap` | `RunnableOps.evalMap[O2](f)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `contramap` | `RunnableOps.contramap[I2](f)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `timeout` | `RunnableOps.timeout(duration)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `handleError` | `RunnableOps.handleError(handler)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `withRetry` | `RunnableOps.withRetry(maxRetries, initialDelay)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `withFallback` | `RunnableOps.withFallback(fallback, semantic)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| combinator `parallel` | `RunnableOps.parallel[I, O1, O2](r1, r2)` (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`) |
| runtime host | `org.adk4s.core.runnable` |

## Deviations from the pattern

- `withRetry` is not streaming-aware: `stream`/`collect`/`transform` delegate to `invoke` for retry, breaking streaming semantics (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`).
- `parallel`'s `transform` materializes the entire input stream via `.compile.toList` before zipping, defeating lazy streaming (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`).
- The `collect` mode uses `.compile.lastOrError` in derived implementations, which raises `NoSuchElementException` on empty streams — empty input is an error, not a defined result (`adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`).
- `withFallback`'s `Atomic` and `BeforeFirstElement` semantics compile the stream to a list before attempting the fallback, losing streaming behavior (`adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`).
