# Spec Lint: llm4s-middleware-and-dedup

<!-- Self-review of all specs against the 12 mandatory checks.
     Each spec is checked independently. Implementation-order may only
     be generated when every spec is PASS. -->

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in concept-inventory.md
7. Every property has a declared generator strategy
8. Every temporal property has a trigger event and a response event
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum extension states how existing pattern matches behave
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism

## Results

### Spec: specs/llm4s-middleware-adoption/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 5 requirements have Given/When/Then; all 10 scenarios have specific Given/When/Then |
| 2 | Then observable | ✅ | Every Then references a return value (Right/Left), call count, log output, or timing |
| 3 | Scenarios testable | ✅ | Uses munit + Hedgehog (detected stack); mock LLMClient with deterministic responses |
| 4 | Error paths specified | ✅ | ParseFailed, ValidationFailed, LLMError retry exhausted, deprecated factory behavior |
| 5 | New concepts declared | ✅ | StructuredOutputMiddleware, ParseRetryTrigger, fromClient(client, middlewares), fromClientWithMiddleware in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | All 16 concepts in Concepts Used exist in concept-inventory.md |
| 7 | Generator strategies | ✅ | 3 properties, each with constructive Gen + classify labels + edge cases |
| 8 | Temporal trigger/response | N/A | No temporal properties (Ring 9 not checked) |
| 9 | No vague words | ✅ | "identical" is defined as same call count/delay/result; "preserved" is defined as field equality |
| 10 | Unreachable claims proven | ✅ | RetryStructuredLLM private — compile-negative test in Compile-Negative Obligations |
| 11 | Enum extension behavior | ✅ | ParseRetryTrigger is a new enum (not extending existing); no existing pattern matches affected |
| 12 | Proof obligations complete | ✅ | 9 obligations covering all requirements, properties, and compile-negative |

**Verdict: PASS**

### Spec: specs/message-type-dedup/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 5 requirements have Given/When/Then; all 10 scenarios have specific clauses |
| 2 | Then observable | ✅ | Every Then references conversation.messages content, field equality, deprecation warning, or deserialized state |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; Conversation construction and inspection are pure |
| 4 | Error paths specified | ✅ | No user message present (edge case), old checkpoint format readability |
| 5 | New concepts declared | ✅ | Prompt(conversation), deprecated Message alias, deprecated Role alias in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | All 16 concepts in Concepts Used exist in concept-inventory.md |
| 7 | Generator strategies | ✅ | 3 properties, each with constructive Gen + classify labels |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | "identical" defined as reference or value equality; "unchanged" defined as same role/content fields |
| 10 | Unreachable claims proven | ✅ | Compile-negative: Prompt(messages=...) construction forbidden |
| 11 | Enum extension behavior | ✅ | Role is being removed (deprecated alias), not extended; no new variants |
| 12 | Proof obligations complete | ✅ | 10 obligations covering all requirements, properties, compile-negative, and Ring 4 fixture |

**Verdict: PASS**

### Spec: specs/tool-abstraction-dedup/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 5 requirements have Given/When/Then; all 9 scenarios have specific clauses |
| 2 | Then observable | ✅ | Every Then references toolFunction.name, execute return value, registry size, or encoded response |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; ToolFunction construction and ToolRegistry inspection are pure |
| 4 | Error paths specified | ✅ | Handler errors surface as ToolCallError.InvalidArguments; silent-drop bug fix is a scenario |
| 5 | New concepts declared | ✅ | ToolWrapper(toolFunction), StructuredToolFunction.toToolFunction in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | All 12 concepts in Concepts Used exist in concept-inventory.md |
| 7 | Generator strategies | ✅ | 3 properties, each with constructive Gen + classify labels |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | "identical" defined as same execute result; "non-empty" defined as size > 0 |
| 10 | Unreachable claims proven | ✅ | Compile-negative: ToolWrapper(originalToolFunction=...) construction forbidden |
| 11 | Enum extension behavior | N/A | No enums extended |
| 12 | Proof obligations complete | ✅ | 7 obligations covering all requirements, properties, and compile-negative |

**Verdict: PASS**

### Spec: specs/error-hierarchy-dedup/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 5 requirements have Given/When/Then; all 11 scenarios have specific clauses |
| 2 | Then observable | ✅ | Every Then references getCause return value, shouldRetry boolean result, or underlying field |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; error construction and getCause are pure |
| 4 | Error paths specified | ✅ | ParseFailed does not trigger LLMError retry; non-LLM error returns false |
| 5 | New concepts declared | ✅ | RetryTrigger.shouldRetry(Throwable), LlmCallError(underlying, cause) in Concepts Introduced |
| 6 | Reused concepts resolved | ✅ | All 13 concepts in Concepts Used exist in concept-inventory.md |
| 7 | Generator strategies | ✅ | 3 properties, each with constructive Gen + classify labels |
| 8 | Temporal trigger/response | N/A | No temporal properties |
| 9 | No vague words | ✅ | "wrapper-agnostic" defined as same result for raw, StructuredLLMError-wrapped, and AdkError-wrapped |
| 10 | Unreachable claims proven | ⚠️ | The compile-negative for shouldRetry signature narrowing is noted as not a true negative (StructuredLLMError is a Throwable, so narrowing compiles). The positive case (raw LLMError compiles) is the real test. No unreachable claims made. |
| 11 | Enum extension behavior | ✅ | RetryTrigger is modified (shouldRetry signature widened), not extended with new variants; existing pattern matches in Retry.withRetry are updated per MODIFIED requirement |
| 12 | Proof obligations complete | ✅ | 7 obligations covering all requirements, properties, and compile tests |

**Verdict: PASS** (with note on check 10: the compile-negative obligation is a positive-compile test, not a true negative; this is acknowledged in the spec's Compile-Negative section)

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/llm4s-middleware-adoption/spec.md | PASS | 0 |
| specs/message-type-dedup/spec.md | PASS | 0 |
| specs/tool-abstraction-dedup/spec.md | PASS | 0 |
| specs/error-hierarchy-dedup/spec.md | PASS | 0 |

**Overall: PASS** — all 4 specs pass all 12 checks. Implementation-order may be generated.
