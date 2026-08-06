# Spec: Optimizable Surface (Delta)

<!-- DELTA spec for the migrate-json-codec change. MODIFIES the `Demo`
     case class field types from `ujson.Value` to `JsonValue`. The
     `Optimizable` typeclass, `PredictorState`, `PredictorPath`, the
     optimizer laws, the toy optimizers, and all other requirements in the
     base spec are UNCHANGED. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| optimizable-surface | `Demo.input`/`output` field types migrate from `ujson.Value` to `JsonValue`; optimizable-surface actions unchanged | `openspec/concepts/optimizable-surface.md` |

No change to the `optimizable-surface.md` concept file's actions, state, or synchronizations — only the `Demo` field types change. The concept's Implementation map row for `Demo` is updated at apply Step 12.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Demo` | case class | `org.adk4s.optimize` |
| `PredictorState` | case class | `org.adk4s.optimize` |
| `Optimizable[P]` | typeclass | `org.adk4s.optimize` |
| `smithy4s.Document` | sealed trait (ADT) | `smithy4s` |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `JsonValue` | type alias (`= smithy4s.Document`) | Introduced by the json-value-model spec; referenced here as the new `Demo` field type |

## MODIFIED Requirements

### Requirement: predictor-state is pure immutable data

The system SHALL provide a predictor-state value with three fields: an instructions string, a list of demo examples (each an input/output pair of `JsonValue` values), and a frozen flag. The state SHALL be plain immutable data with no behavior. The `Demo` case class SHALL carry `input: JsonValue` and `output: JsonValue` (immutable `smithy4s.Document`), NOT `ujson.Value` (mutable). The `Demo` scaladoc's existing claim of "plain immutable data" SHALL become true (it is currently false because `ujson.Value` is mutable).

**Given** a predictor-state with instructions "Answer the question", one demo with `input: JsonValue = DObject(Map("q" -> DString("hello")))` and `output: JsonValue = DString("world")`, and frozen flag false
**When** the state's fields are read
**Then** the instructions string is "Answer the question", the demo list has one example with `JsonValue` fields (immutable), and the frozen flag is false

**Rationale**: `Demo`'s own scaladoc claims "plain immutable data," but `ujson.Value` is mutable (`Obj` wraps `LinkedHashMap`, `Arr` wraps `mutable.ArrayBuffer`). Migrating to `JsonValue` makes the claim true and removes the undeclared transitive `upickle` dependency from `adk4s-optimize`'s public type signatures (the Ring 2 purity rule in `build.sbt` says `adk4s-optimize` MUST NOT depend on the llm4s LLM client — true by import, false by dependency graph while `Demo` carries `ujson.Value`).

#### Scenario: Default predictor-state

**Given** a predictor-state constructed with empty instructions, no demos, and frozen flag false
**When** the state's fields are read
**Then** the instructions string is empty, the demo list is empty, and the frozen flag is false

#### Scenario: Demo with JsonValue fields

**Given** a `Demo` with `input: JsonValue = DObject(Map("q" -> DString("hello")))` and `output: JsonValue = DString("world")`
**When** the demo's fields are inspected
**Then** `input` and `output` are `JsonValue` (immutable `smithy4s.Document`), and the demo is genuinely "plain immutable data" (no mutable AST)

#### Scenario: Demo is immutable

**Given** a `Demo` with `input: JsonValue`
**When** code attempts to mutate `input` in place
**Then** compilation fails — `JsonValue` has no in-place `update` method

#### Scenario: Frozen predictor-state is constructible

**Given** a predictor-state with frozen flag true
**When** the frozen flag is read
**Then** it is true (a frozen state is a valid value — freezing is data, not a type-level distinction)

#### Scenario: Demo state is serializable-ready

**Given** a `Demo` with `JsonValue` fields
**When** the demo is serialized via `smithy4s.json.Json`
**Then** it round-trips (serialize then deserialize yields an equal `Demo`), and all fields are plain immutable values suitable for Phase 2 serialization (no closures, no effect type, no live references, no mutable AST)

## Properties (Ring 3)

### Property: Demo round-trips through JsonValue

**Invariant**: For every `Demo` with `input: JsonValue` and `output: JsonValue`, serializing via `smithy4s.json.Json` and deserializing yields an equal `Demo`.

**Generator strategy**: `genDemo` (constructive — `genJsonValue` for input and output; classify by input/output variant). `genJsonValue` from the json-value-model spec covers all `Document` variants including `DNull`.

```
forAll { (demo: Demo) =>
  val json = smithy4s.json.Json.writeBlob(demo)
  val roundTripped = smithy4s.json.Json.read[Demo](json)
  roundTripped == demo
}
```

### Property: Demo is genuinely immutable

**Invariant**: A `Demo` constructed with `JsonValue` fields cannot have its fields mutated in place — any "update" produces a new `Demo`.

**Generator strategy**: `genDemo` (as above). This is a compile-time property verified by a compile-negative test (see Compile-Negative Obligations), not a runtime property — listed here for completeness.

```
forAll { (demo: Demo) =>
  // demo.input is a JsonValue (smithy4s.Document) — no update method
  // any "update" is demo.copy(input = newJsonValue)
  true // compile-time property, enforced by compile-negative test
}
```

### Property: Optimizer laws hold with JsonValue demos

**Invariant**: The optimizer laws (purity, frozen-preserved, path-set-preserved) hold for programs whose `Demo` fields are `JsonValue`-typed.

**Generator strategy**: `genPredictorState` (constructive — `genString` for instructions, `Gen.vector(genDemo, Range.linear 0 5)` for demos, `Gen.boolean` for frozen; classify by frozen-count). Reuses the existing `OptimizerLaws` testkit from the base spec.

```
forAll { (state: PredictorState) =>
  val program = toyProgram(state)
  val optimized = instructionRewriter.compile(program)
  OptimizerLaws.check(optimized, program) // purity, frozen-preserved, path-set-preserved
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `Demo(input: ujson.Value, output: ujson.Value)` | The field types migrate to `JsonValue` | `assertDoesNotCompile("Demo(ujson.Null, ujson.Null)")` (type mismatch) |
| In-place mutation of `Demo.input` | `JsonValue` is immutable | `assertDoesNotCompile("demo.input.update(...)")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Demo.input is JsonValue | Requirement: predictor-state is pure immutable data + Scenario: Demo with JsonValue fields | type system (field type) + adversarial review | DemoTypeContract |
| Demo.output is JsonValue | Requirement: predictor-state is pure immutable data + Scenario: Demo with JsonValue fields | type system (field type) + adversarial review | DemoTypeContract |
| Demo is genuinely immutable | Requirement: predictor-state is pure immutable data + Scenario: Demo is immutable | compile-negative test | DemoTypeContract |
| Demo round-trips | Requirement: predictor-state is pure immutable data + Scenario: Demo state is serializable-ready + Property: Demo round-trips through JsonValue | Hedgehog property | DemoSpec |
| Optimizer laws hold | Requirement: predictor-state is pure immutable data + Property: Optimizer laws hold with JsonValue demos | Hedgehog property (via OptimizerLaws testkit) | OptimizerLawsSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `Demo` | case class | `adk4s-optimize/src/main/scala/org/adk4s/optimize/Demo.scala` | `input: ujson.Value, output: ujson.Value` → `input: JsonValue, output: JsonValue` |
| `PredictorState` | case class | `adk4s-optimize/src/main/scala/org/adk4s/optimize/PredictorState.scala` | `demos: Vector[Demo]` — unchanged type, but `Demo` fields change |
