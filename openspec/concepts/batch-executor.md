# Concept: BatchExecutor

## Concept specification

```
concept BatchExecutor[I, O]
purpose
    Execute a Runnable over a list of inputs with sequential, parallel, or
    streaming strategies, returning Either[Throwable, O] per input.
state
    runnable: BatchExecutor -> Runnable[I, O]   # captured by fromRunnable
actions
    invokeAll [ inputs: List[I] ]
        => [ results: IO[List[Either[Throwable, O]]] ]   # sequential
    invokeAllPar [ inputs: List[I] ; concurrency: Int ]
        => [ results: IO[List[Either[Throwable, O]]] ]   # parallel
    stream [ inputs: List[I] ; concurrency: Int ]
        => [ results: Stream[IO, Either[Throwable, O]] ]
    fromRunnable [ runnable: Runnable[I, O] ]
        => [ BatchExecutor[I, O] ]
operational principle
    A caller builds a BatchExecutor from a Runnable. invokeAll runs each
    input sequentially via evalMap; invokeAllPar runs them with bounded
    concurrency via parEvalMap; stream returns a Stream of results. All
    results are wrapped in Either[Throwable, O] via .attempt.
```

## Implementation map

| Element | Code |
|---|---|
| trait `BatchExecutor` | `trait BatchExecutor[I, O]` (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`) |
| action `invokeAll` | `BatchExecutor.invokeAll(inputs): IO[List[Either[Throwable, O]]]` (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`) |
| action `invokeAllPar` | `BatchExecutor.invokeAllPar(inputs, concurrency): IO[List[Either[Throwable, O]]]` (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`) |
| action `stream` | `BatchExecutor.stream(inputs, concurrency): Stream[IO, Either[Throwable, O]]` (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`) |
| factory `fromRunnable` | `BatchExecutor.fromRunnable[I, O](runnable): BatchExecutor[I, O]` (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`) |
| runtime host | `org.adk4s.core.batch` |

## Deviations from the pattern

- `concurrency` is not validated — a value of 0 or negative is passed straight to `parEvalMap`, which has undefined behavior (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`).
- All results are `Either[Throwable, O]`; there is no aggregation, partial-failure summary, or ordering guarantee documentation (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`).
- `invokeAll` (sequential) uses `Stream.emits(inputs).evalMap(...).compile.toList` rather than `List.traverse`, introducing stream overhead for a list operation (`adk4s-core/src/main/scala/org/adk4s/core/batch/BatchExecutor.scala`).
