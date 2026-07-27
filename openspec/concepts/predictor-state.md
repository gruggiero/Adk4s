# Concept: PredictorState

## Concept specification

```
concept PredictorState
purpose
    Carry the tunable state of a single LM-call site: instructions
    (system prompt text), demos (few-shot examples), and a frozen flag
    (optimizer may not modify). This is the data unit that optimizers
    read and write via the Optimizable surface.
state
    instructions: PredictorState -> String
    demos: PredictorState -> Vector[Demo]
    frozen: PredictorState -> Boolean
actions
    (none — pure data, no behavior)
operational principle
    A PredictorState is an immutable snapshot of what an optimizer can
    tune. The frozen flag is data, not a type-level guarantee: the
    Optimizable surface checks it at update time and returns
    FrozenPath error. An optimizer that wants to freeze a predictor
    sets frozen=true in the state; the surface respects it.
```

## Implementation map

| Element | Code |
|---|---|
| value `PredictorState` | `final case class PredictorState(instructions: String, demos: Vector[Demo], frozen: Boolean)` (`adk4s-optimize/src/main/scala/org/adk4s/optimize/PredictorState.scala`) |
| value `Demo` | `final case class Demo(input: ujson.Value, output: ujson.Value)` (`adk4s-optimize/src/main/scala/org/adk4s/optimize/Demo.scala`) |
| runtime host | `org.adk4s.optimize` |

## Synchronizations

```
sync StateReadByCapability
when {
    HasPredictorState/state: called on a predictor
}
then {
    PredictorState: returned as the predictor's tunable state
}
```

impl: `Predict0.hasPredictorStateForPredict0.state(self)` returns `self.state` (`Predict0.scala`).

```
sync StateWrittenByCapability
when {
    HasPredictorState/withState: called on a predictor with new state
}
then {
    PredictorState: replaced in the predictor via copy
}
```

impl: `Predict0.hasPredictorStateForPredict0.withState(self, s)` returns `self.copy(state = s)` (`Predict0.scala`).

## Deviations from the pattern

- `Demo` uses `ujson.Value` for input/output payloads. This is a pragmatic choice for Phase 0 (the placeholder predictor carries state but does not render demos). Phase 2 will replace this with typed schema-backed values when the real `Predict` is introduced.
- The `frozen` flag is a runtime boolean, not a type-level distinction. This means a frozen predictor and an unfrozen one have the same type. The `Optimizable` surface checks `frozen` at update time, returning `FrozenPath` error. A type-level encoding (e.g., `Predict0[Frozen]` vs `Predict0[Unfrozen]`) was considered and rejected as too rigid for the optimizer's workflow.
