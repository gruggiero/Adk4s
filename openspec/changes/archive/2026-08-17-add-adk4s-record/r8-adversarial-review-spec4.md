# Ring 8 — Adversarial Review: recorder-verified-model (spec 4)

## Scope

Fresh-context adversarial review of the PureScala models and bridge tests
introduced by spec 4 (`recorder-verified-model`):

- `verified/src/main/scala/org/adk4s/verified/NormalizationModel.scala`
- `verified/src/main/scala/org/adk4s/verified/RecorderCoherenceModel.scala`
- `adk4s-record/src/test/scala/org/adk4s/record/NormalizationBridgeSpec.scala`
- `adk4s-record/src/test/scala/org/adk4s/record/RecorderCoherenceBridgeSpec.scala`
- `adk4s-record/src/test/scala/org/adk4s/record/VerifiedModelTypeContract.scala`

## Checks

### 1. Model faithfully mirrors shipped code — PASS

The `NormalizationModel.normalize` function mirrors the shipped
`normalizeToolCallIds` algorithm:
- Phase 1: build mapping from original ids to positional ids (0, 1, 2, ...)
- Phase 2: apply mapping to all messages

The `RecorderCoherenceModel.record`/`lookup` functions mirror the shipped
`Recorder.inMemory` semantics: record updates the map, lookup returns the
value if present.

### 2. Bridge tests actually exercise the shipped code — PASS

`NormalizationBridgeSpec` calls `normalizeToolCallIds` (shipped) and
`NormalizationModel.normalize` (model) on the same generated conversations.
`RecorderCoherenceBridgeSpec` calls `Recorder.inMemory` (shipped) and
`RecorderCoherenceModel.record`/`lookup` (model) on the same generated
key/record pairs.

### 3. Id space abstraction is sound — PASS

The model uses `BigInt` ids (0, 1, 2, ...) while the shipped code uses
`String` ids ("call_0", "call_1", ...). The bridge spec compares structural
properties (idempotence, order preservation) that are invariant under the
id-space abstraction, not exact equality. This is correct: the properties
depend only on equality and ordering of ids, not on their string representation.

### 4. Hash collision-freedom assumption is stated explicitly — PASS

`RecorderCoherenceModel.injectiveAssumption` states the injectivity axiom
explicitly with an `ensuring` clause. The `digest` function is modeled as
the identity function for runtime execution, with a comment explaining that
in Stainless verification the `ensuring` clause states the injectivity axiom.

### 5. R6 verification results — PASS

All new model VCs are valid:
- `RecorderCoherenceModel.record` postcondition — valid
- `RecorderCoherenceModel.coherenceLemma` postcondition — valid
- `RecorderCoherenceModel.isolationLemma` postcondition — valid
- `RecorderCoherenceModel.injectiveAssumption` postcondition — valid
- `NormalizationModel` termination + exhaustiveness — all valid

The 9 invalid VCs are all from pre-existing `StackKernel` and `PredictorKernel`
(not introduced by spec 4).

### 6. Idempotence lemma is runtime-only — PARTIAL (acknowledged)

The `idempotenceLemma` is stated as a runtime property (no `ensuring` clause)
because the smt-z3 fallback solver crashes on the nested recursive calls.
The bridge spec verifies this property at runtime on generated inputs. With
the native Z3 interface, an `ensuring` clause could be added to discharge it
statically. This is a known limitation, not a defect.

### 7. No mutable state in models — PASS

Both models use only pure functions and immutable data structures
(`stainless.collection.List`, `stainless.lang.Map`). No `var`, no `Ref`,
no side effects.

### 8. No `isInstanceOf`/`asInstanceOf` — PASS

The `VerifiedModelTypeContract` uses pattern matching instead of
`isInstanceOf`/`asInstanceOf`, per the AGENTS.md style guidelines.

### 9. No `Any` type — PASS

No `Any` type is used anywhere in the models or bridge specs.

### 10. Types are explicit — PASS

All expressions in the models have explicit type annotations. The bridge
specs use type inference only for local val bindings within method bodies,
which is consistent with the existing codebase style.

## Summary

- 9 checks PASS
- 1 check PARTIAL (idempotence lemma is runtime-only, acknowledged limitation)
- 0 checks FAIL

No defects found. The implementation is sound and the bridge tests
correctly bind the shipped code to the models.
