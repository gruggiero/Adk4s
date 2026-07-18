# Concept: InterruptSignal

## Concept specification

```
concept InterruptSignal
purpose
    Represent an agent's request to pause execution and await external
    input, carrying an address for routing and optional state for resumption.
state
    address: InterruptSignal -> List[AddressSegment]
    info: InterruptSignal -> String
    state: Stateful -> ujson.Value
    children: Composite -> List[InterruptSignal]
actions
    simple [ info: String ]
        => [ signal: Simple ]   # empty address
    stateful [ info: String ; state: ujson.Value ]
        => [ signal: Stateful ]
    composite [ info: String ; state: ujson.Value ; children: List[InterruptSignal] ]
        => [ signal: Composite ]
    withAddress [ newAddress: List[AddressSegment] ]
        => [ signal: InterruptSignal ]
operational principle
    A tool or agent raises AgentInterruptedException(signal). The signal's
    address routes the eventual resume data to the right nested agent/tool.
    A Composite signal wraps children from nested agent-tools, each carrying
    its own state; the parent's state is the agent-tool's serialized
    AgentToolState.
```

## AddressSegment variants

- `Agent(name: String)`
- `Tool(name: String)`

## Implementation map

| Element | Code |
|---|---|
| trait `InterruptSignal` | `sealed trait InterruptSignal` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| variant `Simple` | `final case class Simple(address, info)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| variant `Stateful` | `final case class Stateful(address, info, state: ujson.Value)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| variant `Composite` | `final case class Composite(address, info, state, children)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| action `withAddress` | `InterruptSignal.withAddress(newAddress): InterruptSignal` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| factory `simple` | `InterruptSignal.simple(info): Simple` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| factory `stateful` | `InterruptSignal.stateful(info, state): Stateful` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| factory `composite` | `InterruptSignal.composite(info, state, children): Composite` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`) |
| trait `AddressSegment` | `sealed trait AddressSegment` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AddressSegment.scala`) |
| variant `Agent` | `AddressSegment.Agent(name: String)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AddressSegment.scala`) |
| variant `Tool` | `AddressSegment.Tool(name: String)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AddressSegment.scala`) |
| error `AgentInterruptedException` | `case class AgentInterruptedException(signal: InterruptSignal)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.core.interrupt` |

## Deviations from the pattern

- The factory methods `simple`, `stateful`, `composite` create signals with `address = List.empty[AddressSegment]`; callers must call `withAddress` after construction, so an interrupt can be raised with an empty address by mistake (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`).
- `InterruptSignal` and `AddressSegment` derive upickle `ReadWriter`, coupling the abstraction to a specific serialization library (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`).
- `Composite` carries both `state` and `children`; the relationship between the parent state and the children's state is implicit — `AgentRunner.resume` does not route to children, it flattens everything into user messages (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`).
