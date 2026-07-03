# Implementation Order

## Dependency Graph

```
unicode-quote-normalization (no deps)
    ↓
constraint-validation (no deps on other specs)
    ↓
retry-policies (no deps on other specs)
    ↓
type-aware-sap-coercion (no deps on other specs — core rewrite)
    ↓
semantic-streaming (depends on: type-aware-sap-coercion — uses JsonishValue, CompletionState)
    ↓
partial-types-streaming (depends on: semantic-streaming — uses streaming infrastructure)
    ↓
output-format-rendering (depends on: type-aware-sap-coercion — uses schema awareness)
    ↓
fallback-round-robin (depends on: retry-policies — composes with retry)
    ↓
error-enrichment (depends on: retry-policies, fallback-round-robin — wraps attempt history)
    ↓
dynamic-type-builder (no deps on other specs)
    ↓
structured-test-framework (depends on: constraint-validation — uses checks/asserts)
```

## Topological Sort

| Order | Spec | Complexity | Typed Contract | Depends On | Rings |
|------|------|------------|-----------------|------------|-------|
| 1 | unicode-quote-normalization | simple | minimal | none | R0, R1, R3, R8 |
| 2 | constraint-validation | medium | full | none | R0, R1, R3, R5, R8 |
| 3 | retry-policies | medium | full | none | R0, R1, R3, R8 |
| 4 | type-aware-sap-coercion | high | full | none | R0, R1, R3, R4, R5, R6, R8 |
| 5 | semantic-streaming | high | full | type-aware-sap-coercion | R0, R1, R3, R8 |
| 6 | partial-types-streaming | medium | full | semantic-streaming | R0, R1, R3, R4, R8 |
| 7 | output-format-rendering | medium | full | type-aware-sap-coercion | R0, R1, R3, R4, R5, R8 |
| 8 | fallback-round-robin | medium | full | retry-policies | R0, R1, R3, R8 |
| 9 | error-enrichment | simple | full | retry-policies, fallback-round-robin | R0, R1, R3, R8 |
| 10 | dynamic-type-builder | medium | full | none | R0, R1, R3, R4, R8 |
| 11 | structured-test-framework | medium | full | constraint-validation | R0, R1, R3, R8 |

## Expected Production File Changes

| Spec | Production Files |
|------|-----------------|
| unicode-quote-normalization | `sap/SchemaAlignedParser.scala` |
| constraint-validation | `core/Schema.scala`, `core/Constraint.scala` (new), `core/StructuredLLM.scala` |
| retry-policies | `core/Retry.scala` (new), `core/StructuredLLM.scala` |
| type-aware-sap-coercion | `sap/JsonishValue.scala` (new), `sap/TypeCoercer.scala` (new), `sap/CoercionScore.scala` (new), `sap/EnumMatching.scala` (new), `sap/UnionCoercion.scala` (new), `sap/SchemaAlignedParser.scala` |
| semantic-streaming | `sap/SemanticStreaming.scala` (new), `core/StructuredLLM.scala`, `core/Schema.scala` |
| partial-types-streaming | `core/Partial.scala` (new), `core/StructuredLLM.scala` |
| output-format-rendering | `core/OutputFormat.scala` (new), `core/Schema.scala` |
| fallback-round-robin | `core/ClientStrategy.scala` (new), `core/StructuredLLM.scala` |
| error-enrichment | `core/ErrorEnrichment.scala` (new), `core/StructuredLLM.scala` |
| dynamic-type-builder | `core/DynamicSchema.scala` (new) |
| structured-test-framework | `core/TestFramework.scala` (new) |

## Ring 5 Mutation Targets (per spec)

| Spec | Mutation Target Files | Threshold |
|------|----------------------|-----------|
| unicode-quote-normalization | `sap/SchemaAlignedParser.scala` | 95% |
| constraint-validation | `core/Constraint.scala` | 90% |
| retry-policies | `core/Retry.scala` | 85% |
| type-aware-sap-coercion | `sap/TypeCoercer.scala`, `sap/EnumMatching.scala`, `sap/UnionCoercion.scala`, `sap/CoercionScore.scala` | 90% |
| semantic-streaming | `sap/SemanticStreaming.scala` | 80% |
| partial-types-streaming | `core/Partial.scala` | 90% |
| output-format-rendering | `core/OutputFormat.scala` | 90% |
| fallback-round-robin | `core/ClientStrategy.scala` | 85% |
| error-enrichment | `core/ErrorEnrichment.scala` | 90% |
| dynamic-type-builder | `core/DynamicSchema.scala` | 85% |
| structured-test-framework | `core/TestFramework.scala` | 85% |

## Implementation Checklist

- [ ] 1. unicode-quote-normalization
- [ ] 2. constraint-validation
- [ ] 3. retry-policies
- [ ] 4. type-aware-sap-coercion
- [ ] 5. semantic-streaming
- [ ] 6. partial-types-streaming
- [ ] 7. output-format-rendering
- [ ] 8. fallback-round-robin
- [ ] 9. error-enrichment
- [ ] 10. dynamic-type-builder
- [ ] 11. structured-test-framework
