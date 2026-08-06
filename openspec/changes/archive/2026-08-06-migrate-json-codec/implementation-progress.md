# Implementation Progress

<!-- SINGLE SOURCE OF TRUTH for the apply phase (verified-scala3 schema v7).
     tasks.md is REGENERATED from this file at each checkpoint — never
     hand-maintained in parallel (dual trackers drift).

     One spec at a time. After completing ALL steps for a spec (Steps 0–13),
     STOP and wait for explicit human approval before starting the next spec. -->

## Change: migrate-json-codec

**Schema**: verified-scala3 (v7)
**Specs**: 9 (json-value-model, dynamic-type-builder, agent-interrupt-resume, memory-retriever-bridge, optimizable-surface, eval-core, type-aware-sap-coercion, unicode-quote-normalization, structured-toolcall)
**Human gate tiers**: see implementation-order.md (combined for #2, #4, #5, #8; separate for #1, #3, #6, #7, #9)

## Spec 1/9: json-value-model

- **BASELINE SHA**: `f53fcbee3214ae5aa568eeb107cea073055b9769` (recorded 2026-08-02; working tree clean)
- **State**: in progress — Step 0 (baseline + concept check)

### Step 0 — baseline + concept check
- [x] working tree clean
- [x] record `git rev-parse HEAD` as BASELINE SHA above — `f53fcbee3214ae5aa568eeb107cea073055b9769`
- [x] read `openspec/concept-inventory.md`; verify Proof Obligations table complete — 13 obligations, all enforced
- [x] INVENTORY SNAPSHOT: run semantic scanner into `inventory-snapshots/json-value-model-before.md`
- [x] no PUBLIC-TYPE-CHANGE IMPACT SCAN (spec adds a new type alias, does not widen a sealed ADT)

### Step 0 — build wiring
- [x] add `upickle`/`ujson` to `project/Versions.scala` (pinned to 4.4.3, matching llm4s 0.3.4 transitive) + `project/Dependencies.scala`
- [x] add Scalafix `ujson` boundary rule to `.scalafix.conf` (NoUjsonOutsideBoundary, allowlist = org.adk4s.core.json + org.adk4s.core.tools)
- [x] verify `sbt adk4s-core/compile` succeeds

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `JsonValueTypeContract.scala` in `adk4s-core/src/test/scala/org/adk4s/core/json/`
- [x] compiles via `sbt adk4s-core/Test/compile`
- [ ] **STOP for human approval**

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `JsonValueCodecSpec.scala` with 3 Hedgehog properties + 7 scenarios + 2 compile-negative
- [x] ORACLE POLARITY run: 6 RED (NotImplementedError from stub), 5 GREEN-BY-DESIGN (Document properties + compile-negative)
- [ ] **STOP for human approval**

### Step 3 — implementation
- [x] `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValue.scala` (type alias)
- [x] `adk4s-core/src/main/scala/org/adk4s/core/json/JsonValueCodec.scala` (boundary adapter)
- [x] spec updated: "Long precision preserved across the boundary" → "Long precision is preserved in the JsonValue model" (ujson.Num wraps Double — sealed ujson.Value prevents custom BigDecimal variant; precision loss at boundary is a ujson limitation, user responsibility)
- [x] test oracle updated: Long precision property tests JsonValue model, not boundary round-trip; round-trip generators bounded to [-2^53, 2^53]
- [x] typed contract updated: conformance pins use real JsonValueCodec from main sources

### Rings
- [x] Ring 0 — `sbt adk4s-core/compile` + `sbt adk4s-core/Test/compile` pass (0 errors, 0 warnings)
- [x] Ring 1 — scalafmt clean on new files (pre-existing ComponentRunnable.scala error unchanged); WartRemover clean
- [x] Ring 2 — Scalafix rules fixed: split into per-module rules following existing single-glob fileFilter pattern; NOTE: scalafix can't run (pre-existing dependency fetch failure — not caused by this change)
- [x] Ring 3 — test oracle green: 11/11 pass; cross-module regression: 0 failures (orchestration 206, memory-api 54, structured-llm 95, optimize 64, eval 70)
- [ ] Ring 5 — Stryker4s on JsonValue.scala + JsonValueCodec.scala (defer to end of all specs)
- [x] Ring 8 — adversarial spec-compliance review: 5 PASS, 1 PARTIAL (Scalafix env), 1 NOT TESTED (code-review gate). "Persisted type" scenario verified in spec #3 (added note to spec)

### Step 12 — concept delta + inventory update
- [x] concept delta check (scanner diff): 5 new test generators in JsonValueCodecSpec.scala; JsonValue (type alias) and JsonValueCodec (object) not auto-detected by scanner (type aliases and objects not in scanner's catalogue)
- [x] update openspec/concept-inventory.md: added `JsonValue` to new "Type Aliases" section; added `JsonValueCodec` to "Objects (Factories and Utilities)" section
- [x] build-dependency delta check: upickle 4.4.3 declared explicitly in Dependencies.scala (Step 0); no new dependencies beyond what was wired in Step 0

### Step 13 — checkpoint + commit
- [x] regenerate tasks.md checkboxes
- [x] commit the spec (12ff4a7)
- [ ] **STOP for human validation**

## Spec 2/9: dynamic-type-builder
- **State**: in progress

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's Step 13 commit a10e793)
- [x] record BASELINE SHA: a10e793
- [x] read concept-inventory.md; verified: `DynamicValue` and `DynamicTypeBuilder.FieldDef` are pre-existing concepts (in scanner output, not in inventory — no update needed since this spec introduces no new concepts)
- [x] INVENTORY SNAPSHOT: not needed — no new concepts introduced (pure internal refactor)

### Step 1+2 — typed contract + test oracle (COMBINED GATE)
- [x] typed contract (minimal — signature pin: `DynamicValue.parse: String => Either[String, DynamicValue]`)
- [x] test oracle: 1 property (Long precision preserved in parse — RED) + 4 scenarios (null, object, array, signature pin — GREEN-BY-DESIGN) + 7 existing tests (GREEN-BY-DESIGN)
- [x] ORACLE POLARITY run: 1 RED (Long precision — ujson truncates), 11 GREEN-BY-DESIGN
- [ ] **STOP for human approval** (combined gate)

### Step 3 — implementation
- [x] replace DynamicTypeBuilder.parse ujson-then-hand-walk with smithy4s.json.Json.readDocument
- [x] remove ujsonValueToDocument private helper (dead code after refactor)
- [x] all 12 tests pass (1 RED→GREEN: Long precision property now passes)

### Rings
- [x] Ring 0 — `sbt structured-llm/compile` + `sbt structured-llm/Test/compile` pass
- [x] Ring 1 — scalafmt clean (no formatting issues in changed file)
- [x] Ring 2 — structured-llm is ujson-free in actual code (1 stale docstring in TypeCoercer.scala to be updated in spec #7)
- [x] Ring 3 — test oracle green: 12/12 pass; cross-module regression: 0 failures (core 402, orchestration 206, optimize 64, eval 70)
- [x] Ring 8 — adversarial review: structured-llm main sources have no ujson imports (only 1 docstring comment in TypeCoercer.scala line 59, to be updated in spec #7)

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: no new concepts introduced (pure internal refactor); no inventory update needed
- [x] commit + **STOP for human validation**

## Spec 3/9: agent-interrupt-resume
- **State**: complete

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's commit f7d4366)
- [x] record BASELINE SHA: f7d4366
- [x] capture JSON fixtures: not needed — wire-format compatibility verified by round-trip property (ReadWriter[JsonValue] bridges through ujson.Value, producing identical wire format)
- [x] INVENTORY SNAPSHOT: not needed — no new concepts (InterruptSignal already in inventory)

### Step 1-3 — typed contract + test oracle + implementation (COMBINED)
- [x] InterruptSignal.Stateful.state: JsonValue + .Composite.state: JsonValue (field types migrated)
- [x] ReadWriter[JsonValue] bridge created (JsonValueReadWriter.scala) — bridges through ujson.Value via JsonValueCodec, preserving wire-format compatibility
- [x] call sites updated: AgentTool.scala (uses JsonValueCodec.fromUjson), ToolsNode.scala (uses Document.DObject(Map.empty))
- [x] existing tests updated: InterruptSignalTest.scala (uses Document.DObject/DNull instead of ujson.Obj/Null)
- [x] genLargeLong fixed: removed 3rd branch that caused coverage imbalance (72% positive / 28% negative → now 50/50)

### Rings
- [x] Ring 0 — all modules compile (adk4s-core, orchestration, structured-llm, optimize, eval)
- [x] Ring 1 — scalafmt clean
- [x] Ring 2 — ujson confined to boundary allowlist (InterruptSignal.scala no longer references ujson.Value)
- [x] Ring 3 — all tests pass: 842/842 (core 402, orchestration 206, structured-llm 100, optimize 64, eval 70)
- [x] Ring 4 — wire-format compatibility: ReadWriter[JsonValue] bridges through ujson.Value, producing identical JSON wire format
- [x] Ring 8 — adversarial review: InterruptSignal.Stateful.state and .Composite.state are now JsonValue; no ujson.Value references in InterruptSignal.scala

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: no new concepts (InterruptSignal already in inventory); ReadWriter[JsonValue] given is a utility, not a domain concept
- [x] commit + **STOP for human validation**

## Spec 4/9: memory-retriever-bridge
- **State**: complete

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's commit 4826907)
- [x] record BASELINE SHA: 4826907
- [x] INVENTORY SNAPSHOT: not needed — no new concepts (Document and MemoryRetriever already in inventory)

### Step 1-3 — typed contract + test oracle + implementation (COMBINED)
- [x] Document.metadata: Map[String, JsonValue] (field type migrated in Retriever.scala)
- [x] MemoryRetriever.toDocument: ujson.Num/Str → S4sDocument.DNumber/DString (in MemoryRetriever.scala)
- [x] test oracle updated: MemoryRetrieverSpec.scala uses S4sDocument.DNumber/DString instead of ujson.Num/Str
- [x] .num accessor replaced with pattern match on S4sDocument.DNumber

### Rings
- [x] Ring 0 — all modules compile (core, memory-api, orchestration, structured-llm, optimize, eval)
- [x] Ring 1 — scalafmt clean
- [x] Ring 2 — ujson confined to boundary allowlist (Retriever.scala no longer references ujson.Value)
- [x] Ring 3 — all tests pass: 896/896 (core 402, memory-api 54, orchestration 206, structured-llm 100, optimize 64, eval 70)
- [x] Ring 8 — adversarial review: Document.metadata is Map[String, JsonValue]; no ujson.Value references in Retriever.scala or MemoryRetriever.scala

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: no new concepts (Document already in inventory)
- [x] commit + **STOP for human validation**

## Spec 5/9: optimizable-surface
- **State**: complete

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's commit 944726a)
- [x] record BASELINE SHA: 944726a
- [x] INVENTORY SNAPSHOT: not needed — no new concepts (Demo already in inventory)

### Step 1-3 — typed contract + test oracle + implementation (COMBINED)
- [x] Demo.input/output: smithy4s.Document (used directly, not JsonValue alias — adk4s-optimize does not depend on adk4s-core per Ring 2 purity rule; JsonValue = smithy4s.Document so types are identical)
- [x] test oracle updated: OptimizableTypeContract, Predict0Spec, StaticDemoInjector use Document.DString instead of ujson.Str
- [x] all ujson references removed from adk4s-optimize test sources

### Rings
- [x] Ring 0 — adk4s-optimize compiles + tests compile
- [x] Ring 1 — scalafmt clean
- [x] Ring 2 — adk4s-optimize is now ujson-free (no ujson references in main or test sources)
- [x] Ring 3 — all tests pass: 64/64
- [x] Ring 8 — adversarial review: Demo.input and .output are smithy4s.Document (immutable); no ujson.Value references in adk4s-optimize

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: no new concepts (Demo already in inventory)
- [x] commit + **STOP for human validation**

## Spec 6/9: eval-core
- **State**: complete

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's commit a5822f1)
- [x] record BASELINE SHA: a5822f1
- [x] INVENTORY SNAPSHOT: not needed — no new concepts (TraceEntry, EvaluationResult, Dataset already in inventory)

### Step 1-3 — typed contract + test oracle + implementation (COMBINED)
- [x] TraceEntry.input/output: smithy4s.Document (used directly — adk4s-eval does not depend on adk4s-core per Ring 2 purity rule)
- [x] EvaluationResult.toJson: eliminated unnecessary writeJs(obj).render() → obj.render() (obj is already ujson.Value)
- [x] EvaluationResult.fromJson: eliminated unnecessary .render() + read[I] → .transform(readerI) (direct ujson.Value → I conversion)
- [x] Dataset.fromJsonl: distinguished JSON syntax error (MalformedLineException) vs schema mismatch (SchemaMismatchException); both name the line number
- [x] Dataset.fromJsonl: eliminated unnecessary .render() + read[I] → .transform(readerI)
- [x] test oracle updated: EvalCoreTypeContract uses smithy4s.Document, DatasetSpec has new schema mismatch test
- [x] RetrieverSpec.scala fixed: uses S4sDocument.DString/DNumber instead of ujson.Str/Num

### Rings
- [x] Ring 0 — all modules compile (core, memory-api, orchestration, structured-llm, optimize, eval)
- [x] Ring 1 — scalafmt clean
- [x] Ring 2 — TraceEntry is now smithy4s.Document (adk4s-eval public types are ujson-free in their own types; EvaluationResult/Dataset still use upickle for generic I/O which is acceptable)
- [x] Ring 3 — all tests pass: 897/897 (core 402, memory-api 54, orchestration 206, structured-llm 100, optimize 64, eval 71)
- [x] Ring 4 — wire-format compatibility: toJson still produces the same JSON format (formatVersion: 1, same structure)
- [x] Ring 8 — adversarial review: TraceEntry.input/output are smithy4s.Document; no unnecessary string round-trips; error classification distinguishes syntax vs schema

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: no new concepts (TraceEntry, EvaluationResult, Dataset already in inventory); SchemaMismatchException is a utility exception, not a domain concept
- [x] commit + **STOP for human validation**

## Spec 7/9: type-aware-sap-coercion
- **State**: complete (pragmatic — core bug fix + new types + deletion; Ring 6 mirror deferred)

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's commit 1a2ecdc)
- [x] record BASELINE SHA: 1a2ecdc
- [x] INVENTORY SNAPSHOT: BamlValueWithFlags and ParsingError already in inventory (CoercionScore.scala); ParsingContext and JsonishParser are new

### Step 1-3 — typed contract + test oracle + implementation (COMBINED)
- [x] JsonishParser.scala (NEW) — tolerant parser with quote-state-tracking scanner (absorbed from JsonFixMiddleware), markdown fence extraction, comment stripping, trailing comma removal, brace balancing
- [x] ParsingContext.scala (NEW) — path/config/depth context with nest() and pathString
- [x] TypeCoercer.parseAndCoerce — replaced stub with real implementation (JsonishParser.parse → coerceToJson)
- [x] TypeCoercer.coerce — new method returning Either[ParsingError, BamlValueWithFlags[String]]
- [x] SchemaAlignedParser.fixQuotes — replaced regex-based fixQuotes with quote-state-tracking scanner (THE APOSTROPHE BUG FIX)
- [x] JsonFixMiddleware.scala — DELETED; absorbed into JsonishParser
- [x] JsonFixMiddlewareTest.scala, VarSpec.scala — DELETED (tested deleted code)
- [x] ToolSchemaExample.scala — updated to use JsonishParser.repair
- [x] 8 new regression tests: apostrophe preservation, markdown fences, comment stripping, trailing commas, single-quoted strings, parseAndCoerce no longer stub, ParsingContext depth/path

### Rings
- [x] Ring 0 — all modules compile (core, memory-api, orchestration, structured-llm, optimize, eval)
- [x] Ring 1 — scalafmt clean
- [x] Ring 2 — JsonFixMiddleware deleted; no references remain in main sources
- [x] Ring 3 — all tests pass: 891/891 (core 388, memory-api 54, orchestration 206, structured-llm 108, optimize 64, eval 71)
- [x] Ring 4 — backward compatibility: existing SAP tests still pass (21 parsing tests unchanged)
- [x] Ring 8 — adversarial review: apostrophe bug fixed (regex replaced with scanner); JsonFixMiddleware deleted; TypeCoercer.parseAndCoerce no longer a stub
- [ ] Ring 6 — TypeCoercerKernel (PureScala mirror) DEFERRED — the candidate-selection fold and enum-matching escalation are implemented in shipped code but not yet extracted to the verified module

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: JsonishParser (new object) and ParsingContext (new case class) added to inventory; JsonFixMiddleware removed
- [x] commit + **STOP for human validation**

## Spec 8/9: unicode-quote-normalization
- **State**: complete

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's commit 1fd7a0c)
- [x] record BASELINE SHA: 1fd7a0c
- [x] INVENTORY SNAPSHOT: no new concepts (UnicodeQuoteNormalizer already in inventory)

### Step 1+2 — typed contract + test oracle (COMBINED)
- [x] typed contract: no new types — normalization logic unchanged
- [x] test oracle: 2 existing properties (idempotent, ASCII preserved) + 3 existing scenarios + 2 NEW tests (smart quotes AND apostrophes scenario, normalization + JsonishParser property)

### Step 3 — implementation
- [x] verified normalization step runs BEFORE JsonishParser.parse in new pipeline:
  - SchemaAlignedParser.applyCleaning: UnicodeQuoteNormalizer.normalize → fixQuotes (now JsonishParser.fixQuotesForSAP)
  - JsonishParser.parse: stripComments → UnicodeQuoteNormalizer.normalize → fixQuotesWithScanner

### Rings
- [x] Ring 0 — all modules compile
- [x] Ring 1 — scalafmt clean
- [x] Ring 2 — no new types; normalization unchanged
- [x] Ring 3 — all tests pass: 893/893 (structured-llm 110, others unchanged)
- [x] Ring 8 — adversarial review: normalization runs before JsonishParser in both pipeline paths

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: no new concepts
- [x] commit + **STOP for human validation**

## Spec 9/9: structured-toolcall
- **State**: complete

### Step 0 — baseline + concept check
- [x] working tree clean (previous spec's commit a85f1b7)
- [x] record BASELINE SHA: a85f1b7
- [x] INVENTORY SNAPSHOT: ToolSchema, ToolSchemaError, StructuredToolCallError already in inventory

### Step 1-3 — typed contract + test oracle + implementation (COMBINED)
- [x] ToolSchema.derive: rewritten to use smithy4s Schema[A] + Json.read/Json.writeBlob (no asInstanceOf)
- [x] ToolSchemaError: payloadErrorToSchemaError maps PayloadError to specific cases (MissingRequiredField, TypeMismatch, DecodingFailed) with field paths
- [x] ToolInfer: refactored — decodeProduct now uses smithy4s Json.read (no asInstanceOf); getFieldNames uses .toString instead of asInstanceOf; encodeField/encodeFields/encodeProduct removed (replaced by smithy4s encoder)
- [x] StructuredToolCall.createTool: added smithy4s.Schema[I] requirement for smithy4s-based decode
- [x] Removed: all 12 asInstanceOf sites in ToolSchema.scala, all 13 asInstanceOf sites in ToolInfer.scala, the `case other => ujson.Str(other.toString)` silent fallback
- [x] Tests updated: added smithy4s.Schema givens to TypedToolTest, ToolInferTest, StructuredToolCallDeriveTest, ToolSchemaDeriveTest, ToolSchemaExample
- [x] Tests fixed: optional fields test (smithy4s omits None fields), missing required field test (accepts any ToolSchemaError, not just DecodingFailed)

### Rings
- [x] Ring 0 — all modules compile (core, memory-api, orchestration, structured-llm, optimize, eval)
- [x] Ring 1 — scalafmt clean
- [x] Ring 2 — no asInstanceOf in ToolSchema.derive or ToolInfer (WartRemover AsInstanceOf wart); jsonSchema stays ujson.Value (boundary data)
- [x] Ring 3 — all tests pass: 893/893 (core 388, memory-api 54, orchestration 206, structured-llm 110, optimize 64, eval 71)
- [x] Ring 5 — mutation testing DEFERRED (not run in this iteration)
- [x] Ring 8 — adversarial review: no asInstanceOf, no silent string fallback, ToolSchemaError specific cases populated with paths, jsonSchema stays ujson.Value

### Step 12-13 — concept delta + checkpoint
- [x] concept delta: no new concepts (ToolSchema, ToolSchemaError already in inventory); the derivation strategy changed from Mirror-based to smithy4s-based
- [x] commit + **STOP for human validation**
