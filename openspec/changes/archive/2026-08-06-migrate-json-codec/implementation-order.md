# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept.

     This file is generated from the specs, spec-lint (all PASS required),
     and design artifacts. The checkbox list at the bottom is the progress
     tracker used by the apply phase (tracks: implementation-progress.md). -->

## Dependency Analysis

<!-- For each spec, list what it introduces and what it consumes.
     This determines the topological sort order. -->

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/json-value-model/spec.md` | `JsonValue` (type alias), `JsonValueCodec` (boundary adapter) | `smithy4s.Document`, `smithy4s.json.Json`, `ujson.Value` (existing) | medium — new type alias + new boundary adapter object; no Ring 6/7/9 |
| 2 | `specs/dynamic-type-builder/spec.md` | (none — pure internal refactor) | `JsonValue` (from #1), `smithy4s.json.Json`, `Schema[A]`, `DynamicValue` (existing) | simple — no new types, ≤1 method implementation swap |
| 3 | `specs/agent-interrupt-resume/spec.md` | (none — field type migration) | `JsonValue` (from #1), `InterruptSignal` (existing), `smithy4s.json.Json` | simple — field type change on existing sealed trait; no new types |
| 4 | `specs/memory-retriever-bridge/spec.md` | (none — field type migration) | `JsonValue` (from #1), `Retriever.Document` (existing), `MemoryHit` (existing) | simple — field type change on existing case class; no new types |
| 5 | `specs/optimizable-surface/spec.md` | (none — field type migration) | `JsonValue` (from #1), `Demo` (existing), `PredictorState` (existing) | simple — field type change on existing case class; no new types |
| 6 | `specs/eval-core/spec.md` | (none — field type migration + error reporting fix) | `JsonValue` (from #1), `TraceEntry` (existing), `EvaluationResult` (existing), `Dataset` (existing), `smithy4s.json.Json` | medium — field type changes + JSON export/import rewrite + error classification logic |
| 7 | `specs/type-aware-sap-coercion/spec.md` | `JsonishParser`, `TypeCoercer.coerce`, `BamlValueWithFlags`, `ParsingContext`, `ParsingError`, `TypeCoercerKernel` (Ring 6 mirror) | `JsonValue` (from #1), `JsonishValue` (existing), `CoercionScore` (existing), `CoercionFlag` (existing), `EnumMatching` (existing), `Schema[A]` (existing), `SchemaAlignedParser` (existing), `JsonFixMiddleware` (existing, deleted) | high — new types AND complex parsing/coercion logic AND Ring 6 formal verification |
| 8 | `specs/unicode-quote-normalization/spec.md` | (none — re-verification of existing) | `JsonishParser` (from #7), `JsonishValue` (existing), `SchemaAlignedParser` (existing, modified by #7) | simple — no new types; existing normalization re-verified against new pipeline |
| 9 | `specs/structured-toolcall/spec.md` | schema-derived tool codec (smithy4s `Schema[A]` + `JsonCodecMaker.make[A]` fallback) | `JsonValue` (from #1), `ToolSchema[A]` (existing), `ToolSchemaError` (existing), `smithy4s.Schema[A]` (existing), `JsonCodecMaker` (existing) | medium — new derivation strategy + error population change; no Ring 6/7/9 |

## Dependency Graph

```
#1 json-value-model (introduces JsonValue, JsonValueCodec)
  ├── #2 dynamic-type-builder (uses JsonValue)
  ├── #3 agent-interrupt-resume (uses JsonValue)
  ├── #4 memory-retriever-bridge (uses JsonValue)
  ├── #5 optimizable-surface (uses JsonValue)
  ├── #6 eval-core (uses JsonValue)
  ├── #7 type-aware-sap-coercion (uses JsonValue; introduces JsonishParser, TypeCoercer)
  │     └── #8 unicode-quote-normalization (uses JsonishParser from #7)
  └── #9 structured-toolcall (uses JsonValue)
```

**Topological sort**: #1 → (#2, #3, #4, #5 in parallel-eligible order) → #6 → #7 → #8 → #9

**Tie-breaking** (independent specs ordered simplest-first for faster feedback):
- #2 (dynamic-type-builder) before #3/#4/#5 — smallest, most isolated; makes `structured-llm` ujson-free first, cleaning the Ring 2 purity story early
- #3 (agent-interrupt-resume) before #4/#5 — touches checkpoint persistence (Ring 4), so verifying it early de-risks the wire-format compatibility story
- #4 (memory-retriever-bridge) before #5 — in-memory only (no Ring 4), simpler than #5
- #5 (optimizable-surface) — last of the simple field migrations
- #6 (eval-core) after #2-#5 — medium complexity (JSON export/import rewrite + error classification), benefits from the field-migration pattern established in #2-#5
- #7 (type-aware-sap-coercion) after #6 — the largest, correctness-critical piece; benefits from all field migrations being done
- #8 (unicode-quote-normalization) after #7 — re-verifies existing normalization against the new pipeline from #7
- #9 (structured-toolcall) after #7 — independent of #7 but placed last because it is the largest mechanical change and benefits from the smithy4s patterns established in #2 and #7

## Ring Applicability

<!-- For each spec, determine which rings apply based on the proposal's
     verification strategy AND the spec's own sections.
     R3 and R8 are MANDATORY for every code-changing spec.
     The Typed Contract column is full / minimal / waiver (waiver requires
     explicit human approval; only for docs/formatting/test-only specs). -->

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | json-value-model | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | ✅ | — | — | ✅ (MANDATORY) | — | full |
| 2 | dynamic-type-builder | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | — | — | — | ✅ | — | minimal |
| 3 | agent-interrupt-resume | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (REQUIRED) | — | — | — | ✅ | — | full |
| 4 | memory-retriever-bridge | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | — | — | — | ✅ | — | full |
| 5 | optimizable-surface | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (fixture-based) | — | — | — | ✅ | — | full |
| 6 | eval-core | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (REQUIRED) | — | — | — | ✅ | — | full |
| 7 | type-aware-sap-coercion | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (21 existing tests) | ✅ | ✅ (selectBest + enumMatchPenalty) | — | ✅ (MANDATORY) | — | full |
| 8 | unicode-quote-normalization | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | — | — | — | ✅ | — | minimal |
| 9 | structured-toolcall | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | ✅ | — | — | ✅ (MANDATORY) | — | full |

## Expected Changed Production Files (Ring 5 targeting)

<!-- Per spec, the production files expected to change. Ring 5 dynamically
     retargets the Stryker mutate list to the files ACTUALLY changed by the
     spec (git diff against the spec's Step 0 baseline SHA), using this
     column as the starting estimate. NEVER rely on a fixed mutate list in
     stryker4s.conf. -->

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | json-value-model | `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValue.scala`, `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValueCodec.scala`, `project/Dependencies.scala`, `project/Versions.scala`, `.scalafix.conf` |
| 2 | dynamic-type-builder | `structured-llm/src/main/scala/org/adk4s/structured/core/DynamicTypeBuilder.scala` |
| 3 | agent-interrupt-resume | `adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`, `adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptResult.scala` |
| 4 | memory-retriever-bridge | `adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala` |
| 5 | optimizable-surface | `adk4s-optimize/src/main/scala/org/adk4s/optimize/Demo.scala` |
| 6 | eval-core | `adk4s-eval/src/main/scala/org/adk4s/eval/TraceEntry.scala`, `adk4s-eval/src/main/scala/org/adk4s/eval/EvaluationResult.scala`, `adk4s-eval/src/main/scala/org/adk4s/eval/Dataset.scala` |
| 7 | type-aware-sap-coercion | `structured-llm/src/main/scala/org/adk4s/structured/sap/JsonishParser.scala` (NEW), `structured-llm/src/main/scala/org/adk4s/structured/sap/TypeCoercer.scala`, `structured-llm/src/main/scala/org/adk4s/structured/sap/BamlValueWithFlags.scala` (NEW), `structured-llm/src/main/scala/org/adk4s/structured/sap/ParsingContext.scala` (NEW), `structured-llm/src/main/scala/org/adk4s/structured/sap/ParsingError.scala` (NEW), `structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/JsonFixMiddleware.scala` (DELETED), `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`, `verified/src/main/scala/org/adk4s/verified/TypeCoercerKernel.scala` (NEW) |
| 8 | unicode-quote-normalization | `structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala` (normalization step position, already modified by #7) |
| 9 | structured-toolcall | `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolInfer.scala` (rewritten or deleted), `adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolCall.scala` |

## Human Gate Tier

<!-- Per spec: `combined` (typed contract + test oracle presented at ONE
     gate) is allowed ONLY when complexity is `simple` AND the proposal's
     correctness risk is `low`. Everything else is `separate` (two gates —
     the default). Both steps are always executed in full either way; the
     tier changes only how many stops the human reviews. Rationale: human
     attention is the scarcest verification resource — a gate the human
     stops reading is worse than no gate. -->

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | json-value-model | separate | complexity=medium (new type alias + new boundary adapter); proposal correctness risk=high (touches checkpoint/eval/optimize persistence) |
| 2 | dynamic-type-builder | combined | complexity=simple (internal implementation swap, signature unchanged); proposal correctness risk for this spec=low (isolated, no persistence change) |
| 3 | agent-interrupt-resume | separate | complexity=simple BUT proposal correctness risk=high (touches checkpoint persistence — Ring 4 REQUIRED) |
| 4 | memory-retriever-bridge | combined | complexity=simple (field type migration, in-memory only); proposal correctness risk for this spec=low (no persistence, no wire format) |
| 5 | optimizable-surface | combined | complexity=simple (field type migration); proposal correctness risk for this spec=low (Demo is not yet round-tripped in Phase 0; fixture-based Ring 4 only) |
| 6 | eval-core | separate | complexity=medium (JSON export/import rewrite + error classification); proposal correctness risk=high (touches EvaluationResult wire format — Ring 4 REQUIRED) |
| 7 | type-aware-sap-coercion | separate | complexity=high (new types + complex parsing/coercion logic + Ring 6); proposal correctness risk=high (touches the sole decode path for every structured LLM response) |
| 8 | unicode-quote-normalization | combined | complexity=simple (re-verification of existing normalization); proposal correctness risk for this spec=low (normalization logic unchanged, only position verified) |
| 9 | structured-toolcall | separate | complexity=medium (new derivation strategy + error population change); proposal correctness risk=high (touches tool argument schema derivation used by every ToolSchema.derive call site) |

## Complexity Guide

<!-- Complexity determines review depth.

     SIMPLE: No new types, ≤1 new method on existing trait, no new error variants.
             Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.

     MEDIUM: New types OR complex business logic OR new error handling paths.
             Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.

     HIGH:   New types AND complex logic AND involves Ring 6/7 or Ring 9.
             Typed contract: full. All applicable rings. -->

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Record baseline SHA (clean tree) + inventory snapshot; read
        openspec/concept-inventory.md — import existing concepts; verify the spec's
        Proof Obligations table is complete
     2. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE (combined-tier specs: merged into gate 3)
     3. Test oracle from spec + contract only (before implementation),
        run once for ORACLE POLARITY (red / green-by-design)
        → human review GATE
     4. Implement through all applicable rings (see table above) — Ring 8
        adversarial review (fresh context) runs BEFORE Rings 5/6/7
     5. Concept delta check (scanner diff) + build-dependency delta +
        update openspec/concept-inventory.md
     6. Mark checkbox below, regenerate tasks.md, COMMIT the spec
     7. STOP for human validation before next spec

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

- [ ] 1. `specs/json-value-model/spec.md` — Introduce `JsonValue` type alias + `JsonValueCodec` boundary adapter + declare `upickle`/`ujson` in Dependencies.scala + add Scalafix `ujson` boundary rule
- [ ] 2. `specs/dynamic-type-builder/spec.md` — Replace `DynamicValue.parse`'s ujson-then-hand-walk with direct `smithy4s.json.Json.read[Document]` (makes structured-llm ujson-free)
- [ ] 3. `specs/agent-interrupt-resume/spec.md` — Migrate `InterruptSignal.Stateful`/`.Composite.state` from `ujson.Value` to `JsonValue` + wire-format compatibility fixtures
- [ ] 4. `specs/memory-retriever-bridge/spec.md` — Migrate `Retriever.Document.metadata` from `Map[String, ujson.Value]` to `Map[String, JsonValue]`
- [ ] 5. `specs/optimizable-surface/spec.md` — Migrate `Demo.input`/`output` from `ujson.Value` to `JsonValue` + fixture-based wire-format check
- [ ] 6. `specs/eval-core/spec.md` — Migrate `TraceEntry`/`EvaluationResult` field types to `JsonValue` + rewrite JSON export/import with smithy4s.json.Json + fix Dataset.fromJsonl error classification
- [ ] 7. `specs/type-aware-sap-coercion/spec.md` — Build `JsonishParser` (tolerant string→JsonishValue parser) + complete `TypeCoercer.coerce` + implement `BamlValueWithFlags`/`ParsingContext`/`ParsingError` + wire into `SchemaAlignedParser` + delete `JsonFixMiddleware` + Ring 6 mirror (`TypeCoercerKernel`)
- [ ] 8. `specs/unicode-quote-normalization/spec.md` — Re-verify Unicode smart-quote normalization against the new `JsonishParser`-based pipeline
- [ ] 9. `specs/structured-toolcall/spec.md` — Replace `ToolInfer`/`ToolSchema.derive` hand-rolled Mirror-based codec with smithy4s `Schema[A]` + `JsonCodecMaker.make[A]` fallback; populate `ToolSchemaError` specific cases; remove 4 `asInstanceOf` sites
