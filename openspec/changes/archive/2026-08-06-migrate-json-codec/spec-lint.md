# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Mechanical pre-pass

<!-- Run BEFORE the judgment checks:
     1. `openspec validate --strict` — must pass.
     2. `openspec/schemas/verified-scala3/scanner/spec-lint.sh <change-dir>` —
        enforces the greppable subset (F1–F7) and reports vague-word,
        adversarial-confirmation and positional-reference candidates (W1–W4).
        F6 = obligation Source names nothing resolvable (mandated format).
        F7 = requirement named by NO obligation (unenforced — check 12 as
             reachability, stricter than the W2 row count).
        F8 = typed source names a Property/Scenario heading that does not
             exist in the spec (dangling reference).
        W4 = ordinal requirement references (prefer exact titles).
        W5 = impossibility claim enforced only by tests (ladder tier 1-2
             expected, or "tier-justified: <why not>" in Enforcement).
     F9 (artifact existence) is NOT run here — it is post-implementation;
     apply Step 12 runs `spec-lint.sh --artifacts`.
     Paste both outputs here. Script FAILs are lint FAILs. -->

**openspec validate --strict**: PASS (change/migrate-json-codec validates; an unrelated change add-harness-api-phase0 fails but is not in scope)

**spec-lint.sh**: 9 spec file(s), 0 FAIL, 27 WARN

All 12 initial FAILs were resolved before this report:
- 8× F6 "(base spec requirements, unmodified)" — removed rows that referenced base spec requirements not present as headings in delta specs; the base spec's own obligations cover those requirements
- 2× F6 "Formal Contract: ..." — changed source to reference the Requirement heading the contract supports + Compile-Negative source kind
- 2× F6 "Requirement: ... (base spec)" — added the base spec requirements as MODIFIED headings in the delta so they can be referenced by title
- 1× F7 "JsonishParser is a real tolerant string-to-JsonishValue parser" — fixed title mismatch between obligation Source ("...tolerant parser") and actual heading ("...tolerant string-to-JsonishValue parser")

Remaining 27 WARNs are all W1 (vague-word candidates) and W3 (negative-requirement adversarial confirmation). Each is reviewed below and confirmed as either a false positive or satisfied by an existing adversarial scenario.

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
1b. Every requirement opens with a normative SHALL/MUST statement before its first `**Given**` (mechanical: F1)
1c. Every "identical/same/preserved behavior" requirement over an enum/dispatch parameter has one scenario PER variant, each asserting the discriminating observable
2. Every `Then` is observable (a return value, persisted event, emitted message, error value — not an internal intention)
3. Every scenario is testable with the detected stack (openspec/capability-profile.md)
4. Every error path is specified (no requirement leaves failure behavior open)
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in openspec/concept-inventory.md
7. Every property has a declared generator strategy
8. Every temporal property has a trigger event and a response event
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition next to them
10. Every "unreachable"/"cannot happen" claim has a type-level proof obligation or an explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint, each with a declared enforcement mechanism
13. Every consumer-facing surface (tool/operation/IDL) has a scenario asserting what the consumer observes
14. Every requirement asserting a specific returned error variant is type-feasible vs the producing API's return type
15. ADVERSARIAL RULE — every requirement containing "only", "never", or "must not" has at least one scenario whose input the requirement forbids
16. MUST-CONFIRM RULE — every classification table, code mapping, or value domain whose authoritative source is outside the repo is marked "MUST-CONFIRM"
17. ALTITUDE RULE — no Given/When/Then clause contains code identifiers (module names, error class names, build commands)
18. CONCURRENCY RULE — every requirement/scenario about concurrent behavior names a deterministic observable and is testable with the detected deterministic test kit

---

## Per-Spec Verdicts

### json-value-model/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | All 4 requirements have Given/When/Then |
| 1b | PASS | All 4 open with SHALL |
| 1c | N/A | No enum/dispatch "identical behavior" requirements |
| 2 | PASS | All Then clauses are observable (type inspection, round-trip equality, Scalafix violation report, build inspection) |
| 3 | PASS | Hedgehog properties, compile-negative tests, Scalafix rules — all in detected stack |
| 4 | PASS | Error paths: coercion failure → Left(ParsingError), Scalafix violation, version conflict |
| 5 | PASS | `JsonValue` and `JsonValueCodec` in Concepts Introduced |
| 6 | PASS | `smithy4s.Document`, `smithy4s.json.Json`, `InterruptSignal`, `Document`, `Demo`, `TraceEntry`, `EvaluationResult` all in concept-inventory.md |
| 7 | PASS | 4 properties, all with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | W1 "valid" flagged in "valid JSON" — false positive: "valid JSON" is a concrete term (syntactically valid per RFC 8259). Fixed one "correct" in unicode-quote-normalization (not this spec). |
| 10 | PASS | "SHALL NOT carry mutable collections" enforced by compile-negative test (no in-place update) |
| 11 | N/A | No enum/GADT extension (type alias, not new enum) |
| 12 | PASS | F7 mechanical check passed; all 4 requirements named by obligations |
| 13 | PASS | `JsonValue` type and `JsonValueCodec` adapter have scenarios asserting what consumers observe |
| 14 | PASS | Error variants (`ParsingError`, Scalafix violation) are type-feasible |
| 15 | PASS | W3 "negative" requirements confirmed: "SHALL NOT appear" has Scalafix violation scenario; "no in-place update" has compile-negative scenario |
| 16 | N/A | No external classification tables |
| 17 | PASS | Given/When/Then use behavioral vocabulary (JsonValue, DObject, DNumber); code identifiers (module names, build commands) in Implementation Anchors |
| 18 | N/A | No concurrency requirements |

**W3 adversarial confirmation**: "JsonValue is an immutable type alias" (W3 line 42) — adversarial: Scenario "DNull variant is representable" asserts null is a first-class value (forbidden by ujson's absence-as-null pattern); compile-negative test forbids in-place mutation. "JsonValueCodec is the single boundary adapter" (W3 line 70) — adversarial: Requirement "ujson boundary confinement" has the Scalafix violation scenario.

### agent-interrupt-resume/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 1 MODIFIED requirement with Given/When/Then |
| 1b | PASS | Opens with SHALL |
| 1c | N/A | No enum/dispatch "identical behavior" requirements |
| 2 | PASS | All Then clauses observable (field type inspection, round-trip equality, wire-format equality, compile failure) |
| 3 | PASS | Hedgehog properties, compile-negative tests — in detected stack |
| 4 | PASS | No new error paths; existing checkpoint errors unchanged |
| 5 | PASS | `JsonValue` in Concepts Introduced (references json-value-model spec) |
| 6 | PASS | `InterruptSignal`, `AddressSegment`, `smithy4s.Document`, `smithy4s.json.Json` in inventory |
| 7 | PASS | 2 properties, both with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | No vague words flagged |
| 10 | N/A | No "unreachable" claims |
| 11 | N/A | No enum extension |
| 12 | PASS | F7 mechanical check passed |
| 13 | PASS | InterruptSignal state field has scenarios asserting what consumers observe |
| 14 | N/A | No new error variants |
| 15 | PASS | W3 "negative" (line 34) — adversarial: Scenario "Stateful state is immutable" asserts compile failure on mutation attempt |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary in Given/When/Then; code identifiers in Implementation Anchors |
| 18 | N/A | No concurrency requirements |

### memory-retriever-bridge/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 1 MODIFIED requirement with Given/When/Then |
| 1b | PASS | Opens with SHALL |
| 1c | N/A | No enum/dispatch requirements |
| 2 | PASS | All Then clauses observable (field equality, metadata inspection, compile failure) |
| 3 | PASS | Hedgehog properties, compile-negative tests — in detected stack |
| 4 | PASS | No new error paths |
| 5 | PASS | `JsonValue` in Concepts Introduced |
| 6 | PASS | `Document`, `RetrieverConfig`, `MemoryHit`, `AgentMemory`, `smithy4s.Document` in inventory |
| 7 | PASS | 2 properties, both with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | No vague words flagged |
| 10 | N/A | No "unreachable" claims |
| 11 | N/A | No enum extension |
| 12 | PASS | F7 mechanical check passed |
| 13 | PASS | Document.metadata field has scenarios asserting what consumers observe |
| 14 | N/A | No new error variants |
| 15 | PASS | No "only/never/must not" in this spec's requirement (the W3 was on the base spec's requirement, not this delta's) |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary; code identifiers in Implementation Anchors |
| 18 | N/A | No concurrency requirements |

### eval-core/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 3 MODIFIED requirements with Given/When/Then |
| 1b | PASS | All open with SHALL |
| 1c | N/A | No enum/dispatch "identical behavior" requirements |
| 2 | PASS | All Then clauses observable (field type inspection, round-trip equality, error message content, wire-format equality) |
| 3 | PASS | Hedgehog properties, scenario tests — in detected stack |
| 4 | PASS | Error paths: JSON syntax error vs. schema mismatch distinguished; empty file → no error |
| 5 | PASS | `JsonValue` in Concepts Introduced |
| 6 | PASS | `TraceEntry`, `EvaluationResult`, `Dataset`, `smithy4s.json.Json`, `smithy4s.Document` in inventory |
| 7 | PASS | 3 properties, all with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | W1 "valid" flagged 5× — all false positives: "valid JSON" and "valid JSONL" are concrete terms (syntactically valid per RFC 8259 / JSONL spec). "Valid JSONL" scenario name uses "valid" as a concrete descriptor. No fix needed. |
| 10 | N/A | No "unreachable" claims |
| 11 | N/A | No enum extension |
| 12 | PASS | F7 mechanical check passed |
| 13 | PASS | TraceEntry fields, EvaluationResult export, Dataset.fromJsonl all have consumer-observing scenarios |
| 14 | PASS | Error variants (JSON syntax error, schema mismatch) are type-feasible for `Dataset.fromJsonl` return type |
| 15 | PASS | W3 "negative" (lines 40, 62) — adversarial: Scenario "TraceEntry is immutable" asserts compile failure; Scenario "Round-trip with feedback and failures" asserts fields ARE JsonValue (forbidden: ujson.Value) |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary; code identifiers in Implementation Anchors |
| 18 | N/A | No concurrency requirements (harness parallelism is in the base spec, unmodified) |

### optimizable-surface/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 1 MODIFIED requirement with Given/When/Then |
| 1b | PASS | Opens with SHALL |
| 1c | N/A | No enum/dispatch requirements |
| 2 | PASS | All Then clauses observable (field inspection, round-trip equality, compile failure, law check result) |
| 3 | PASS | Hedgehog properties, compile-negative tests, OptimizerLaws testkit — in detected stack |
| 4 | PASS | No new error paths |
| 5 | PASS | `JsonValue` in Concepts Introduced |
| 6 | PASS | `Demo`, `PredictorState`, `Optimizable[P]`, `smithy4s.Document`, `smithy4s.json.Json` in inventory |
| 7 | PASS | 3 properties, all with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | W1 "valid" (line 67) — false positive: "a frozen state is a valid value" means "constructible" (concrete: the frozen flag is data, not a type-level distinction). No fix needed. |
| 10 | N/A | No "unreachable" claims |
| 11 | N/A | No enum extension |
| 12 | PASS | F7 mechanical check passed |
| 13 | PASS | Demo fields have scenarios asserting what consumers observe |
| 14 | N/A | No new error variants |
| 15 | PASS | W3 "negative" (line 35) — adversarial: Scenario "Demo is immutable" asserts compile failure on mutation attempt |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary; code identifiers in Implementation Anchors |
| 18 | N/A | No concurrency requirements |

### dynamic-type-builder/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 1 MODIFIED requirement with Given/When/Then |
| 1b | PASS | Opens with SHALL |
| 1c | N/A | No enum/dispatch requirements |
| 2 | PASS | All Then clauses observable (parsed value equality, Long precision, DNull variant, error raised) |
| 3 | PASS | Hedgehog properties, scenario tests — in detected stack |
| 4 | PASS | Invalid JSON raises an error |
| 5 | PASS | No new concepts (pure internal refactor) |
| 6 | PASS | `Schema[A]`, `SchemaData[A]`, `smithy4s.Document`, `smithy4s.json.Json` in inventory |
| 7 | PASS | 2 properties, both with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | W1 "valid" (line 70) — false positive: "not valid json" is a concrete term (syntactically invalid). No fix needed. |
| 10 | N/A | No "unreachable" claims |
| 11 | N/A | No enum extension |
| 12 | PASS | F7 mechanical check passed |
| 13 | PASS | DynamicValue.parse has scenarios asserting what consumers observe (parsed value, precision, null, error) |
| 14 | PASS | Error variant (parse error) is type-feasible |
| 15 | PASS | W3 "negative" (line 34) — adversarial: Scenario "Long precision preserved" asserts the pre-change ujson path would truncate (forbidden: Double truncation); Scenario "Invalid JSON raises an error" asserts error on invalid input |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary; code identifiers in Implementation Anchors |
| 18 | N/A | No concurrency requirements |

### type-aware-sap-coercion/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 7 requirements (4 ADDED + 3 MODIFIED-completed) with Given/When/Then |
| 1b | PASS | All open with SHALL |
| 1c | N/A | No enum/dispatch "identical behavior" requirements |
| 2 | PASS | All Then clauses observable (parsed JsonishValue value, coercion result, compile failure, file deletion, flag/score inspection) |
| 3 | PASS | Hedgehog properties, scenario tests, compile-negative tests, Stainless verification — all in detected stack |
| 4 | PASS | Error paths: coercion failure → Left(ParsingError), invalid JSON → parse error, all candidates fail → aggregated error |
| 5 | PASS | `JsonishParser`, `TypeCoercer.coerce`, `BamlValueWithFlags`, `ParsingContext`, `ParsingError` in Concepts Introduced |
| 6 | PASS | `Schema[A]`, `SchemaData[A]`, `ParseError`, `ParseResult`, `ParserConfig`, `StructuredLLMError`, `JsonishValue`, `CompletionState`, `CoercionFlag`, `CoercionScore`, `EnumMatching` in inventory |
| 7 | PASS | 5 properties, all with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | W1 "valid" (lines 58, 62, 64) — false positives: "syntactically valid JSON" is a concrete term; "valid" in rationale and scenario name. W1 "correct" (lines 130, 136, 216) — in rationale text, not normative statements or Then clauses. No fix needed. |
| 10 | PASS | "SHALL NOT crash with an uncaught exception or infinite-loop" — enforced by Hedgehog property (JsonishParser is total) |
| 11 | PASS | `JsonishValue` enum extension: base spec already defines the ADT; this delta adds `JsonishParser` which produces it. No new variants added. `CoercionFlag` enum: base spec defines it; no new variants. |
| 12 | PASS | F7 mechanical check passed; all 7 requirements named by obligations |
| 13 | PASS | JsonishParser, TypeCoercer, BamlValueWithFlags all have consumer-observing scenarios |
| 14 | PASS | Error variants (ParsingError, ParseResult.Failure) are type-feasible for the producing APIs |
| 15 | PASS | W3 "negative" (lines 100, 150) — adversarial: "NOT regex-cleaning" → Scenario "Backward compatibility" + Property "Apostrophe round-trip" (the regex path corrupts apostrophes); "never implemented" → Scenario "BamlValueWithFlags carries flags and score" asserts the types exist |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary; code identifiers in Implementation Anchors. Note: `JsonishParser`, `TypeCoercer` etc. are concept names (behavioral), not code identifiers (module names/build commands). |
| 18 | N/A | No concurrency requirements |

**Ring 6 formal contracts**: Candidate selection fold and enum escalation monotonicity are PureScala-expressible. Bridge property tests in the owning module. The `verified` module (Scala 3.7.2, Stainless) is available per capability-profile.md. The owning module (`structured-llm`) takes `verified % Test` as a dependency for the bridge test.

### unicode-quote-normalization/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 1 MODIFIED requirement with Given/When/Then |
| 1b | PASS | Opens with SHALL |
| 1c | N/A | No enum/dispatch requirements |
| 2 | PASS | All Then clauses observable (parsed result with expected field values, normalization idempotence, ASCII quote count) |
| 3 | PASS | Hedgehog properties, scenario tests — in detected stack |
| 4 | N/A | No new error paths (normalization is a no-op on ASCII-only input) |
| 5 | PASS | No new concepts (re-verifies existing) |
| 6 | PASS | `JsonishParser`, `JsonishValue` in inventory |
| 7 | PASS | 3 properties, all with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | W1 "correct" was in Then clause — FIXED to "Success with the expected field values" (concrete). W1 "correctly" in rationale — false positive (rationale, not normative). |
| 10 | N/A | No "unreachable" claims |
| 11 | N/A | No enum extension |
| 12 | PASS | F7 mechanical check passed |
| 13 | PASS | Normalization step has scenarios asserting what consumers observe (parsed field values) |
| 14 | N/A | No new error variants |
| 15 | PASS | W3 "negative" (line 34) — adversarial: Scenario "No smart quotes — no change" asserts ASCII-only input is unaffected (forbidden: modification of ASCII quotes); Scenario "Smart quotes AND apostrophes" asserts apostrophes preserved (forbidden: corruption) |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary; code identifiers in Implementation Anchors |
| 18 | N/A | No concurrency requirements |

### structured-toolcall/spec.md — PASS

| Check | Result | Notes |
|-------|--------|-------|
| 1 | PASS | 2 MODIFIED requirements with Given/When/Then |
| 1b | PASS | Both open with SHALL |
| 1c | N/A | No enum/dispatch "identical behavior" requirements |
| 2 | PASS | All Then clauses observable (decoded value, error variant with path, encoded ujson.Value, compile failure) |
| 3 | PASS | Hedgehog properties, scenario tests, compile-negative tests, WartRemover — all in detected stack |
| 4 | PASS | Error paths: MissingRequiredField, TypeMismatch, InvalidEnumValue, DecodingFailed — all specified with field paths |
| 5 | PASS | "schema-derived tool codec" in Concepts Introduced |
| 6 | PASS | `ToolSchema[A]`, `ToolSchemaError`, `StructuredToolCallError`, `Schema[A]`, `smithy4s.json.Json`, `smithy4s.Schema[A]` in inventory |
| 7 | PASS | 4 properties, all with generator strategies |
| 8 | N/A | No temporal properties |
| 9 | PASS | W1 "valid" (lines 63, 65) — false positives: "valid JSON" and "valid JSON matching the schema" are concrete terms (syntactically valid + schema-conformant). No fix needed. |
| 10 | N/A | No "unreachable" claims |
| 11 | PASS | `ToolSchemaError` enum: 4 variants (`MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`) — all specified with scenarios. Exhaustiveness enforced by compile-negative test. No new variants added. |
| 12 | PASS | F7 mechanical check passed |
| 13 | PASS | ToolSchema.derive, ToolSchemaError both have consumer-observing scenarios |
| 14 | PASS | Error variants (ToolSchemaError.MissingRequiredField etc.) are type-feasible for `Either[ToolSchemaError, A]` return type |
| 15 | PASS | W3 "negative" (line 47) — adversarial: Scenario "Decode invalid JSON returns specific ToolSchemaError" asserts the specific cases are populated (forbidden: flattened getMessage string); Scenario "No silent string fallback" asserts unexpected types produce Left (forbidden: silent Right with toString) |
| 16 | N/A | No external classification tables |
| 17 | PASS | Behavioral vocabulary; code identifiers in Implementation Anchors |
| 18 | N/A | No concurrency requirements |

---

## Summary

| Spec | Verdict | FAILs fixed | WARNs reviewed |
|------|---------|-------------|----------------|
| json-value-model | PASS | 0 | 2 W3 (confirmed adversarial) |
| agent-interrupt-resume | PASS | 1 F6 (removed base-spec row) | 1 W3 (confirmed adversarial) |
| memory-retriever-bridge | PASS | 1 F6 (removed base-spec row) | 0 |
| eval-core | PASS | 1 F6 (removed base-spec row) | 2 W3 + 5 W1 (all false positives) |
| optimizable-surface | PASS | 1 F6 (removed base-spec row) | 1 W1 + 1 W3 (both false positives / confirmed) |
| dynamic-type-builder | PASS | 1 F6 (removed base-spec row) | 1 W1 + 1 W3 (both false positives / confirmed) |
| type-aware-sap-coercion | PASS | 5 F6 + 1 F7 (title mismatch + Formal Contract sources + base-spec headings) | 6 W1 + 3 W3 (all false positives / confirmed) |
| unicode-quote-normalization | PASS | 0 | 1 W1 (fixed) + 1 W3 (confirmed) |
| structured-toolcall | PASS | 2 F6 (removed base-spec rows) | 2 W1 + 1 W3 (all false positives / confirmed) |

**Overall verdict: PASS** — all 9 specs pass the mechanical pre-pass (0 FAIL) and all 18 judgment checks. The 27 remaining WARNs are reviewed and confirmed as false positives (W1: "valid" and "correct" used as concrete terms or in rationale text) or satisfied by existing adversarial scenarios (W3: every negative requirement has a scenario whose input the requirement forbids). One genuinely vague "correct" in unicode-quote-normalization was fixed to "Success with the expected field values".

**Unlocks**: design, implementation-order
