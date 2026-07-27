# Concept: Optimizable

## Concept specification

```
concept Optimizable[P]
purpose
    Treat a program type P as an opaque, type-erased product from the
    optimizer's point of view. Enumerate every tunable LM-call site with
    a stable path and its current PredictorState, and apply pure updates
    to individual predictors by path. This is the optimizer-facing surface
    for the DSPy port (Phase 0).
state
    derived: Optimizable -> Mirror.ProductOf[P]
actions
    predictors [ p: P ]
        => [ entries: Vector[(PredictorPath, PredictorState)] ]
    updateEither [ p, path: PredictorPath, f: PredictorState -> PredictorState ]
        => [ updated: P ]
    updateEither [ p, path, f ]
        => [ error: UnknownPath(path) ]
    updateEither [ p, path, f ]
        => [ error: FrozenPath(path) ]
    updateAll [ p, f: (PredictorPath, PredictorState) -> PredictorState ]
        => [ updated: P ]
operational principle
    For a case class P with a Mirror.ProductOf instance, each field is
    inspected in declaration order: if the field type has an Optimizable
    instance, it contributes a subtree (field name prepended to paths);
    else if it has a HasPredictorState instance, it contributes a leaf
    (path = field name); else if it is Vector[Elem] with
    HasPredictorState[Elem], it contributes indexed leaves; otherwise
    the field is skipped. updateEither walks the same tree to find the
    addressed predictor, checks the frozen flag, and applies f.
```

## Implementation map

| Element | Code |
|---|---|
| trait `Optimizable` | `trait Optimizable[P]` (`adk4s-optimize/src/main/scala/org/adk4s/optimize/Optimizable.scala`) |
| derived | `Optimizable.derived[P]` using `Mirror.ProductOf[P]` (`adk4s-optimize/src/main/scala/org/adk4s/optimize/Optimizable.scala`) |
| action `predictors` | `Optimizable.predictors(p: P): Vector[(PredictorPath, PredictorState)]` |
| action `updateEither` | `Optimizable.updateEither(p, path, f): Either[OptimizeError, P]` |
| action `update` | `Optimizable.update(p, path, f): P` (fold over updateEither) |
| action `updateAll` | `Optimizable.updateAll(p, f): P` (filter non-frozen, foldLeft update) |
| helper `predictorsImpl` | inline derivation via `predictorsWalk` -> `predictField` -> `predictCollection` |
| helper `updateEitherImpl` | inline derivation via `updateWalk` -> `updateField` -> `updateVectorViaPredict` / `updateScalarField` |
| helper `predictCollectionWithUpdater` | resolves `HasPredictorState[Elem]` via `summonFrom` and returns updater closures |
| error `UnknownPath` | `OptimizeError.UnknownPath(path: PredictorPath)` |
| error `FrozenPath` | `OptimizeError.FrozenPath(path: PredictorPath)` |
| runtime host | `org.adk4s.optimize` |

## Synchronizations

```
sync LeafPrediction
when {
    Optimizable/predictors: encounters a field with HasPredictorState[T]
}
then {
    HasPredictorState/state: reads PredictorState from the field value
}
```

impl: `predictField` calls `leaf.state(value)` for fields with `HasPredictorState` (`Optimizable.scala`).

```
sync SubtreeRecursion
when {
    Optimizable/predictors: encounters a field with Optimizable[T]
}
then {
    Optimizable/predictors: recurses into the subtree, prepending field name
}
```

impl: `predictField` calls `subOpt.predictors(value)` and prepends `Vector(fieldName)` to each path (`Optimizable.scala`).

```
sync CollectionIndexing
when {
    Optimizable/predictors: encounters a Vector[Elem] field with HasPredictorState[Elem]
}
then {
    HasPredictorState/state: reads PredictorState from each element
    path: field name + index as string
}
```

impl: `predictCollection` indexes each element and calls `leaf.state(e)` (`Optimizable.scala`).

## Deviations from the pattern

- The update path for collections uses `predictCollectionWithUpdater` (a closure-based approach) instead of resolving `HasPredictorState[Elem]` directly in the update walk. This is because `summonFrom` for `HasPredictorState[elem]` fails to resolve at the inline expansion depth of the update path, while it succeeds in the predict path. The closure captures the resolved instance and delegates the actual update to a runtime helper.
- `update` (the raising variant) throws `OptimizeError` on the Left branch. This is suppressed with `@SuppressWarnings(Array("org.wartremover.warts.Throw"))` and is the standard pattern for a total-but-raising convenience method.
