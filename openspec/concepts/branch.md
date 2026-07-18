# Concept: Branch

## Concept specification

```
concept Branch[I]
purpose
    Conditional routing from a node to one of several target NodeKeys,
    based on either an IO computation on the input or a stream
    classification.
state
    condition: InvokeBranch -> (I => IO[NodeKey])
    streamCondition: StreamBranch -> (Stream[IO, I] => IO[NodeKey])
    endNodes: Branch -> Set[NodeKey]
actions
    apply [ condition: I => IO[NodeKey] ; targets: Set[NodeKey] ]
        => [ branch: InvokeBranch ]
    pure [ condition: I => Boolean ; targets: Set[NodeKey] ]
        => [ branch: InvokeBranch ]
    stream [ condition: Stream[IO, I] => IO[NodeKey] ; targets ]
        => [ branch: StreamBranch ]
    binary [ predicate: I => IO[Boolean] ; ifTrue: NodeKey ; ifFalse: NodeKey ]
        => [ branch: InvokeBranch ]
    endIf [ predicate ; otherwise: NodeKey ]
        => [ branch: InvokeBranch ]   # true branch is END
operational principle
    A graph attaches a Branch to a node via a Router. At routing time the
    Router calls the branch's condition on the input (or stream) and
    returns the target NodeKey. Using the wrong routing method (invoke on
    a StreamBranch, or stream on an InvokeBranch) raises
    IllegalStateException.
```

## Implementation map

| Element | Code |
|---|---|
| trait `Branch` | `sealed trait Branch[I]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| variant `InvokeBranch` | `case class InvokeBranch[I](condition, endNodes)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| variant `StreamBranch` | `case class StreamBranch[I](condition, endNodes)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| factory `apply` | `Branch.apply[I](condition, targets): InvokeBranch` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| factory `pure` | `Branch.pure[I](condition, targets): InvokeBranch` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| factory `stream` | `Branch.stream[I](condition, targets): StreamBranch` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| factory `binary` | `Branch.binary[I](predicate, ifTrue, ifFalse)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| factory `endIf` | `Branch.endIf[I](predicate, otherwise)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`) |
| router `Router` | `case class Router[I](branches: Map[NodeKey, Branch[I]])` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`) |
| action `route` | `Router.route(fromNode, input): IO[NodeKey]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`) |
| action `routeStream` | `Router.routeStream(fromNode, input): IO[NodeKey]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`) |
| error `IllegalStateException` | `"Cannot use invoke routing with stream branch"` / `"No branch defined for node <key>"` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`) |
| runtime host | `org.adk4s.orchestration.branch` |

## Deviations from the pattern

- `Router.route` on a `StreamBranch` and `Router.routeStream` on an `InvokeBranch` raise `IllegalStateException` at runtime — the type system does not prevent the mismatch (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`).
- `Router.routeStream` on an `InvokeBranch` compiles the entire stream via `.compile.last` before calling the condition, materializing the stream (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`).
- No validation that `endNodes` in a `Branch` correspond to actual nodes in the target graph — routing can return a NodeKey that does not exist (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`).
