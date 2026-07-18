# Concept: Chain

## Concept specification

```
concept Chain[I, O]
purpose
    Linear composition of Runnable steps, with conditional ChainBranch
    insertion and conversion to a Graph.
state
    compileFn: Chain -> IO[Runnable[I, O]]
actions
    appendLambda [ lambda: Lambda[O, O2] ]
        => [ chain: Chain[I, O2] ]   # via Runnable.andThen
    appendChatModel [ model: ChatModel[IO] ] (ev: O <:< Conversation)
        => [ chain: Chain[I, Completion] ]
    appendBranch [ branch: ChainBranch[O, O2] ]
        => [ chain: Chain[I, O2] ]
    appendPassthrough
        => [ chain: Chain[I, O] ]   # identity
    compile
        => [ runnable: Runnable[I, O] ]
    toGraph
        => [ graph: Graph[I, O] ]
operational principle
    Starting from an identity or fromRunnable seed, a caller appends
    lambdas, chat models, or branches; each append composes via
    Runnable.andThen. compile returns the resulting Runnable; toGraph
    wraps it as a single-node Graph.
```

## ChainBranch variants

- `BinaryChainBranch[I](predicate, ifTrue: Runnable[I, I], ifFalse: Runnable[I, I])`
- `EndIfChainBranch[I](predicate, otherwise: Runnable[I, I])` — returns input if true, otherwise executes `otherwise`

## Implementation map

| Element | Code |
|---|---|
| class `Chain` | `case class Chain[I, O](compileFn: IO[Runnable[I, O]])` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| action `appendLambda` | `Chain.appendLambda[O2](lambda): Chain[I, O2]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| action `appendChatModel` | `Chain.appendChatModel(model)(ev)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| action `appendBranch` | `Chain.appendBranch[O2](branch: ChainBranch[O, O2]): Chain[I, O2]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| action `appendPassthrough` | `Chain.appendPassthrough: Chain[I, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| action `compile` | `Chain.compile: IO[Runnable[I, O]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| action `toGraph` | `Chain.toGraph: IO[Graph[I, O]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| factory `apply` | `Chain.apply[I]: Chain[I, I]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| factory `fromRunnable` | `Chain.fromRunnable[I, O](runnable)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`) |
| trait `ChainBranch` | `sealed trait ChainBranch[I, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/ChainBranch.scala`) |
| variant `BinaryChainBranch` | `case class BinaryChainBranch[I](predicate, ifTrue, ifFalse)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/ChainBranch.scala`) |
| variant `EndIfChainBranch` | `case class EndIfChainBranch[I](predicate, otherwise)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/ChainBranch.scala`) |
| runtime host | `org.adk4s.orchestration.chain` |

## Deviations from the pattern

- `Chain.toGraph` uses `unsafeRunSync()` to evaluate `compile`, blocking the caller thread and violating referential transparency (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`).
- `toGraph` uses `.toOption` on the `ValidatedNec` result of `addLambdaNode`/`setEntry`/`addEndNode` and constructs a generic error message, discarding the structured `AdkError` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`).
- The graph produced by `toGraph` hardcodes the node key `"chain"` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`).
