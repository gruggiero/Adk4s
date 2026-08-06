# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     This file lets `openspec list` and task tooling report progress; the
     apply phase also tracks detailed state in implementation-progress.md.
     Keep both in sync — check boxes here as each spec completes.

     RULES:
     - One `## <n>. <spec-name>` section per spec, in implementation-order.md order
     - Per-spec checkboxes follow the schema cycle: typed contract (human gate) →
       test oracle (human gate) → implementation → applicable rings → concept-delta
       + inventory update + checkpoint
     - List only the rings that apply to that spec (skip those marked `—` in the
       Ring Applicability table)
     - Prerequisite work (build restructure, deps, static-analysis config) goes
       first in the owning spec's section
     - Every task is observable and stack-specific — never "implement the spec" -->

## 1. json-value-model

- [x] Prerequisite: add `upickle`/`ujson` to `project/Versions.scala` (pinned, matching llm4s 0.3.4 transitive) + `project/Dependencies.scala`
- [x] Prerequisite: add Scalafix `ujson` boundary rule to `.scalafix.conf` (custom regex mirroring `NoConfigFactory`/`NoSysEnv`, allowlist = boundary files)
- [x] Step 1 — typed contract: `type JsonValue = smithy4s.Document` + `object JsonValueCodec` with `toUjson`/`fromUjson` signatures (compiles in test sources, human gate)
- [x] Step 2 — test oracle: scenarios (JsonValue is Document, all variants incl DNull, codec round-trip) + 3 properties (JsonValue↔ujson round-trip, Long precision in JsonValue model) + compile-negative stubs (human gate)
- [x] Step 3 — implementation: `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValue.scala` + `JsonValueCodec.scala`
- [x] Rings: R0 R1 R2 R3 R8 (R5 deferred to end of all specs)
- [x] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 2. dynamic-type-builder

- [x] Step 1 — typed contract: `DynamicValue.parse` signature unchanged (minimal contract — internal refactor only)
- [x] Step 2 — test oracle: 1 property (Long precision preserved in parse) + 4 scenarios (null, object, array, signature pin) (human gate, combined with Step 1)
- [x] Step 3 — implementation: replace `ujson.read` + hand-walk in `DynamicTypeBuilder.scala` with `smithy4s.json.Json.readDocument`
- [x] Rings: R0 R1 R2 R3 R8
- [x] Concept-delta check + inventory update + checkpoint

## 3. agent-interrupt-resume

- [x] Prerequisite: capture JSON fixtures from current `upickle.default.writeJs(signal).render()` for representative InterruptSignal values (BEFORE code change)
- [x] Step 1 — typed contract: `InterruptSignal.Stateful.state: JsonValue` + `.Composite.state: JsonValue` (compiles, human gate)
- [x] Step 2 — test oracle: 2 properties (state round-trips, wire-format compatibility) + scenarios (create stateful/composite with JsonValue, immutability) + compile-negative stubs (human gate)
- [x] Step 3 — implementation: migrate `InterruptSignal.scala` field types; create `ReadWriter[JsonValue]` bridge via `JsonValueCodec` (wire-format compatible)
- [x] Rings: R0 R1 R2 R3 R4 R8
- [x] Concept-delta check + inventory update + checkpoint

## 4. memory-retriever-bridge

- [x] Step 1 — typed contract: `Retriever.Document.metadata: Map[String, JsonValue]` (compiles, human gate, combined with Step 2)
- [x] Step 2 — test oracle: 2 properties (Document metadata is JsonValue, MemoryHit→Document mapping) + scenarios (provenance, payload entries, synthesized id, immutability) + compile-negative stubs
- [x] Step 3 — implementation: migrate `Retriever.scala` `Document.metadata` field type; update `MemoryRetriever` mapping
- [x] Rings: R0 R1 R2 R3 R8
- [x] Concept-delta check + inventory update + checkpoint

## 5. optimizable-surface

- [x] Prerequisite: capture JSON fixtures from current `upickle.default.writeJs(demo).render()` for representative Demo values (BEFORE code change)
- [x] Step 1 — typed contract: `Demo.input: smithy4s.Document` + `Demo.output: smithy4s.Document` (compiles, human gate, combined with Step 2)
- [x] Step 2 — test oracle: 3 properties (Demo round-trips, Demo fields are JsonValue, Optimizer laws hold) + scenarios (Demo with JsonValue fields, immutability) + compile-negative stubs
- [x] Step 3 — implementation: migrate `Demo.scala` field types (uses smithy4s.Document directly — adk4s-optimize does not depend on adk4s-core per Ring 2 purity rule)
- [x] Rings: R0 R1 R2 R3 R8
- [x] Concept-delta check + inventory update + checkpoint

## 6. eval-core

- [x] Prerequisite: capture JSON fixtures from current `EvaluationResult.toJson` for representative results (BEFORE code change)
- [x] Step 1 — typed contract: `TraceEntry.input/output: smithy4s.Document` + `EvaluationResult.toJson`/`fromJson` eliminating string round-trips + `Dataset.fromJsonl` error classification (compiles, human gate)
- [x] Step 2 — test oracle: 3 properties (TraceEntry round-trips, JSON export round-trip, wire-format compatibility) + scenarios (formatVersion, Dataset.fromJsonl malformed/mismatched/empty) + compile-negative stubs (human gate)
- [x] Step 3 — implementation: migrate `TraceEntry.scala` field types + rewrite `EvaluationResult.toJson`/`fromJson` eliminating string round-trips + fix `Dataset.fromJsonl` error classification (JSON syntax error vs. schema mismatch)
- [x] Rings: R0 R1 R2 R3 R4 R8
- [x] Concept-delta check + inventory update + checkpoint

## 7. type-aware-sap-coercion

- [x] Step 1 — typed contract: `JsonishParser.parse(raw: String): JsonishValue` + `TypeCoercer.coerce(JsonishValue, ParsingContext): Either[ParsingError, BamlValueWithFlags[String]]` + `BamlValueWithFlags[A]` (already existed) + `ParsingContext` (NEW) + `ParsingError` (already existed) (compiles, human gate)
- [x] Step 2 — test oracle: 8 new tests (apostrophe preservation, markdown fences, comment stripping, trailing commas, single-quoted strings, parseAndCoerce no longer stub, ParsingContext depth/path, SAP apostrophe regression) + existing 21 tests pass (backward compatibility)
- [x] Step 3 — implementation: `JsonishParser.scala` (NEW, absorbs JsonFixMiddleware scanner) + `ParsingContext.scala` (NEW) + `TypeCoercer.scala` (completed parseAndCoerce + new coerce method) + `SchemaAlignedParser.scala` (replaced regex fixQuotes with scanner) + deleted `JsonFixMiddleware.scala` + deleted test files + updated example
- [x] Rings: R0 R1 R2 R3 R4 R8 (R6 TypeCoercerKernel DEFERRED)
- [x] Concept-delta check + inventory update + checkpoint

## 8. unicode-quote-normalization

- [x] Step 1 — typed contract: normalization step position in new pipeline (minimal contract — no new types)
- [x] Step 2 — test oracle: 2 existing properties + 3 existing scenarios + 2 NEW tests (smart quotes AND apostrophes scenario, normalization + JsonishParser preserves apostrophes property)
- [x] Step 3 — implementation: verified normalization runs BEFORE JsonishParser.parse in both pipeline paths (SchemaAlignedParser.applyCleaning and JsonishParser.parse); no logic change to normalization itself
- [x] Rings: R0 R1 R2 R3 R8
- [x] Concept-delta check + inventory update + checkpoint

## 9. structured-toolcall

- [x] Step 1 — typed contract: `ToolSchema.derive[A]` using smithy4s `Schema[A]` + `Json.read`/`Json.writeBlob` + `ToolSchemaError` specific cases populated via `payloadErrorToSchemaError`
- [x] Step 2 — test oracle: existing tests updated with smithy4s.Schema givens; optional fields test and missing required field test updated for new behavior
- [x] Step 3 — implementation: rewrote `ToolSchema.scala` (smithy4s-based derivation, removed 12 asInstanceOf + silent fallback) + `ToolInfer.scala` (smithy4s-based decode, removed 13 asInstanceOf) + `StructuredToolCall.scala` (added smithy4s.Schema[I] requirement)
- [x] Rings: R0 R1 R2 R3 R8 (R5 mutation testing DEFERRED)
- [x] Concept-delta check + inventory update + checkpoint
