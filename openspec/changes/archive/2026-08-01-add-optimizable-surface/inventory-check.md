# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-07-24 (semantic scanner spot-check + manual package-path verification)
**Consistency check**: CLEAN for concepts relevant to this change; 2 minor stale rows observed in unrelated types (flagged below, not fixed — out of scope for this change)

<!-- Verification method: ran the multi-module semantic scanner
     (openspec/schemas/verified-scala3/scanner/scan.sh) to a scratch file
     and diffed the sections relevant to this change's reused concepts
     (StructuredLLM, Prompt, Schema, AgentMemoryLaws, ujson.Value, Hedgehog
     generators). Scanner reported: 5 opaque types, 58 sealed types, 272
     case classes, 14 service traits, 45 smithy models, 97 generators —
     zero parse failures. The higher counts vs. the inventory reflect
     adk4s-examples case classes and verified-module mirrors, which the
     inventory intentionally omits. Package paths and constraints for all
     reused concepts match the source. -->

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| (none fixed by this change) | — | — | — |

## Stale rows observed (NOT fixed — out of scope)

<!-- These are minor staleness in types this change does not touch. Fixing
     them belongs to a change that modifies ToolSchemaError / ParseError.
     Recorded here so the next change touching those types picks them up. -->

| Concept | Inventory says | Source actually has | Action |
|---------|----------------|----------------------|--------|
| `ToolSchemaError` | variants: `MissingRequiredField`, `TypeMismatch` | `MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed` | defer to a change touching `org.adk4s.core.tools` |
| `ParseError` | variants: `JsonSyntaxError`, `SchemaViolation`, `MissingRequiredField`, `UnexpectedEnumValue` | adds `NoJsonFound` | defer to a change touching `org.adk4s.structured.core` |

## Behavioral Concepts (registry pass)

**registry-check.sh**: OK (611 implementation-map tokens verified, 3 spec concept references checked, 2 weak binding(s) to tighten)
**Stale implementation-map rows**: none (2 WEAK bindings in `react-agent.md` — `isDefined` and `foreach` identifiers cited to `ReactAgent.scala` but existing elsewhere; tightening is a `react-agent` concept-doc task, not this change)
**Unregistered actions / syncs / state components**: none — this change introduces no new command/event enum variants, no new consumers/producers/middleware, and no new persisted state components. The new `Optimizable[P]` typeclass is a pure type-level concept (no behavior registry entry required at Phase 0; the `optimizable-surface` and `predictor-state` concept docs to be created are type/concept docs, not behavioral-concept registry entries with action tables).

## Concepts relevant to THIS change

<!-- Orientation for spec authors. "Reuse" entries feed the specs' "Concepts
     Used" tables; "Introduce" entries feed "Concepts Introduced" and are
     appended to the project inventory at apply Step 12 (with provenance
     `spec:add-optimizable-surface/optimizable-surface`). -->

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `StructuredLLM[F[_]]` | service trait | `org.adk4s.structured.core` | reuse — `Predict0` wraps `completeTemplate` |
| `Prompt` | case class | `org.adk4s.structured.core` | reuse — `Predict0` carries a Prompt-shaped template |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` | reuse — `Predict0` output schema |
| `PromptTemplate` | (referenced in plan) | `org.adk4s.structured.core` | reuse — `Predict0` wraps `completeTemplate` which takes a `PromptTemplate` |
| `ujson.Value` | JSON value (upickle/ujson, transitive) | (upickle) | reuse — `Demo.input`/`Demo.output` payloads; repo standard JSON, NOT circe |
| `AdkError` | sealed trait (error-modeling pattern) | `org.adk4s.core.error` | reuse as pattern reference — `OptimizeError` stands alone (design.md decision) |
| `AgentMemoryLaws` | main-scope munit laws suite | `org.adk4s.memory.testkit` | reuse as pattern — `OptimizerLaws` follows the same main-scope laws-suite shape |
| Hedgehog `HedgehogSuite` / `property` | property test kit | `hedgehog.munit` | reuse — all Ring 3 properties + `OptimizerLaws` |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` | reuse — test base classes |
| `Demo` | case class | `org.adk4s.optimize` | introduce |
| `PredictorState` | case class | `org.adk4s.optimize` | introduce |
| `PredictorPath` | case class | `org.adk4s.optimize` | introduce |
| `Optimizable[P]` | typeclass | `org.adk4s.optimize` | introduce |
| `HasPredictorState[Self]` | typeclass | `org.adk4s.optimize` | introduce |
| `Predict0[F, I, O]` | case class (placeholder) | `org.adk4s.optimize` | introduce |
| `OptimizeError` | enum (extends Throwable) | `org.adk4s.optimize` | introduce — `UnknownPath`, `FrozenPath` |
| `OptimizerLaws` | testkit (main-scope munit) | `org.adk4s.optimize` (or a `org.adk4s.optimize.testkit` companion) | introduce — placement to be settled in design.md |
| `UppercaseInstructions` | toy optimizer (test only) | `org.adk4s.optimize` (test sources) | introduce |
| `StaticDemoInjector` | toy optimizer (test only) | `org.adk4s.optimize` (test sources) | introduce |
| `optimizable-surface` (concept doc) | type/concept doc | `openspec/concepts/optimizable-surface.md` | introduce |
| `predictor-state` (concept doc) | type/concept doc | `openspec/concepts/predictor-state.md` | introduce |

**Duplicate-creation check**: none of the introduced concepts collide with existing inventory entries. `PredictorPath` is structurally similar to the existing `FieldPath` (opaque type, `Vector[String]`, `org.adk4s.core.types`) but is a distinct concept — `FieldPath` maps fields between workflow nodes, while `PredictorPath` addresses LM-call sites inside a program for optimization. They are intentionally separate (different domain, different package, `PredictorPath` is a case class with a `render` method, `FieldPath` is an opaque newtype). The spec will cite this distinction.
