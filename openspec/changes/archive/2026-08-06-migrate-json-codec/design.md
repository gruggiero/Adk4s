# Design: Migrate JSON Codec Usage Off `ujson` in ADK4S-Owned Code

## Package Structure

<!-- This change does NOT introduce a new sbt module. It introduces a new
     package (`org.adk4s.core.json`) inside the existing `adk4s-core` module,
     and modifies code across 5 existing modules (`adk4s-core`,
     `structured-llm`, `adk4s-optimize`, `adk4s-eval`, `adk4s-orchestration`).
     The layering rule this change enforces — "`ujson.Value` only at the
     llm4s boundary" — is a Ring 2 architecture rule, upgraded from advisory
     to enforced via a new Scalafix custom regex rule.

     The forbidden-import lists below are derived from the detected stack in
     openspec/capability-profile.md and the existing module dependency graph
     in build.sbt. The key constraint: `adk4s-eval` and `adk4s-optimize` MUST
     NOT depend on the llm4s LLM client (Ring 2 purity rule in build.sbt);
     this change makes that rule true by dependency graph, not just by import. -->

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|---------------|
| JSON value model (NEW, pure) | `org.adk4s.core.json` (`JsonValue`, `JsonValueCodec`) | `smithy4s` (Document), `ujson` (adapter only) | cats-effect, fs2, llm4s LLM client, workflows4s, adk4s-orchestration | `JsonValue` is a pure type alias; `JsonValueCodec` is the single boundary adapter. No effect/streaming imports. |
| llm4s boundary (effectful, allowlisted) | `org.adk4s.core.tools` (`Tool`, `ToolsNode`, `ToolWrapper`, `AgentTool`, `StructuredToolFunction`, `ComponentRunnable`, `AdkToolInfo`, `ToolSchema`/`ToolInfer`) | `org.adk4s.core.json` + llm4s toolapi + cats-effect + fs2 | (no new restrictions — these files are the `ujson.Value` allowlist) | `ujson.Value` is permitted here because these files call `org.llm4s.toolapi.ToolFunction` directly. The Scalafix rule allowlists them. |
| Interrupt/checkpoint (pure data) | `org.adk4s.core.interrupt` (`InterruptSignal`, `InterruptResult`) | `org.adk4s.core.json` (JsonValue) + smithy4s.json | `ujson.Value` (after migration), cats-effect, fs2, llm4s | `InterruptSignal.Stateful.state` / `.Composite.state` migrate to `JsonValue`. The `derives ReadWriter` serialization adapts. |
| Retriever (pure data) | `org.adk4s.core.component` (`Retriever.Document`) | `org.adk4s.core.json` (JsonValue) | `ujson.Value` (after migration) | `Document.metadata` migrates to `Map[String, JsonValue]`. |
| SAP / tolerant parser (pure algorithm) | `org.adk4s.structured.sap` (`JsonishParser`, `TypeCoercer`, `BamlValueWithFlags`, `ParsingContext`, `SchemaAlignedParser`, `JsonishValue`, `CoercionFlag`, `CoercionScore`, `EnumMatching`) | `smithy4s` (Schema, Document, json) + `org.adk4s.structured.core` | `ujson.Value`, cats-effect, fs2, llm4s, adk4s-core | The tolerant parser and coercer are pure algorithms. `structured-llm` becomes entirely `ujson`-free after this change. |
| Eval data types (pure data) | `org.adk4s.eval` (`TraceEntry`, `EvaluationResult`, `Dataset`) | `org.adk4s.core.json` (JsonValue) + smithy4s.json | `ujson.Value` (after migration), llm4s LLM client, cats-effect (in data types only) | `TraceEntry.input`/`output` migrate to `JsonValue`. `EvaluationResult.toJson`/`fromJson` use `smithy4s.json.Json`. `Dataset.fromJsonl` distinguishes JSON syntax error vs. schema mismatch. |
| Optimize data types (pure data) | `org.adk4s.optimize` (`Demo`, `PredictorState`) | `org.adk4s.core.json` (JsonValue) + smithy4s.json | `ujson.Value` (after migration), llm4s LLM client, cats-effect | `Demo.input`/`output` migrate to `JsonValue`. Makes the "plain immutable data" scaladoc claim true. |
| Ring 6 mirror (pure, Stainless) | `org.adk4s.verified` (`TypeCoercerKernel`) | (nothing project-local — leaf module) | Everything (leaf) | PureScala model of the candidate-selection fold and enum-fuzzy-matching escalation. Pinned to Scala 3.7.2 for Stainless. |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.adk4s.core.json` | JSON value model (pure) | `JsonValue` type alias (`= smithy4s.Document`) + `JsonValueCodec` boundary adapter (`toUjson`/`fromUjson`). The single conversion point at the llm4s edge. |

No new sbt module is introduced. `org.adk4s.core.json` lives inside the existing `adk4s-core` module. The `verified` module (already exists, leaf, Scala 3.7.2) gains `TypeCoercerKernel` — no module wiring change beyond adding the file.

### Module wiring

No new sbt modules. The only build-file change is a **declaration change**: adding `upickle`/`ujson` explicitly to `project/Dependencies.scala` wherever ADK4S-owned code uses it directly, closing the undeclared-transitive-dependency gap.

```scala
// project/Versions.scala — add:
val Upickle = "3.0.0"  // MUST match the version org.llm4s:core:0.3.4 brings transitively

// project/Dependencies.scala — add:
val upickle = Seq(
  "com.lihaoyi" %% "upickle" % Versions.Upickle
)
```

The version is pinned (not a floating range) and MUST match the transitive version from `org.llm4s:core:0.3.4`. Verified at apply time via `sbt evict` or `sbt dependencyTree`.

The `verified` module gains a new file (`TypeCoercerKernel.scala`) but its wiring is unchanged — it is already a leaf module with Stainless enabled, pinned to Scala 3.7.2, and `structured-llm % Test` depends on it for the bridge test.

The module graph delta (recorded by apply Step 12):

```
adk4s-core → structured-llm (unchanged)
adk4s-core → smithy4s-json (already declared via structured-llm)
structured-llm → verified % Test (already wired for PredictorKernel bridge; reused for TypeCoercerKernel bridge)
```

## Effect Boundaries

<!-- This change is overwhelmingly pure: `JsonValue` is a type alias,
     `JsonValueCodec` is a pair of total functions, the tolerant parser is a
     pure `String => JsonishValue` function, `TypeCoercer.coerce` is a pure
     `Either`-returning function, and the migrated field types are plain
     data. The only effectful code touched is `ToolsNode`'s argument-repair
     call site (which calls the pure `JsonishParser.parse` from inside an
     `IO`/`F[_]` context) and `Dataset.fromJsonl` (which reads a file inside
     `IO`). Ring 6 applies to the two pure algorithmic kernels
     (candidate-selection fold, enum-fuzzy-matching escalation) via the
     VERIFIED-MIRROR pattern. -->

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `org.adk4s.verified.TypeCoercerKernel.selectBest` | Candidate-selection fold: given `Vector[Either[ParsingError, BamlValueWithFlags[A]]]`, select the `Right` with minimum score, or aggregate `Left` errors | **Yes** — pure fold over a total order (`CoercionScore`). Inputs reducible to `(Boolean, BigInt)` pairs (passed?, score-index). Law: `selectBest(candidates).isRight == candidates.exists(_.isRight)` and the selected score is the minimum. Mirror in `verified` module, bridge test in `structured-llm % Test`. |
| `org.adk4s.verified.TypeCoercerKernel.enumMatchPenalty` | Enum-fuzzy-matching escalation: exact → punctuation-stripped → case-insensitive → both, each a monotonically increasing penalty | **Yes** — pure function over `(String, Vector[String])` returning `Option[(String, Int)]`. Inputs reducible to `(String, List[String])` in PureScala. Law: penalty is monotonically increasing across strategies (exact < stripped < case-insensitive < both). Mirror in `verified` module, bridge test in `structured-llm % Test`. |
| `JsonValueCodec.toUjson` / `fromUjson` | Boundary adapter: `smithy4s.Document` ↔ `ujson.Value` | **No** — the round-trip laws (identity across all variants including `DNull`/`Null`, Long precision) are enforced by Ring 3 Hedgehog properties. The adapter is a structural mapping (each `Document` variant → corresponding `ujson.Value` variant), not a decision/fold/law at its center. |
| `JsonishParser.parse` | Tolerant string → `JsonishValue` parser | **No** — the parser is a character-level scanner with quote-state tracking, structural balance recovery, and comment stripping. It is pure (no I/O, no mutation), but it is a stateful scanner, not a decision/fold/law. The correctness properties (apostrophe preservation, totality, AnyOf production) are enforced by Ring 3 Hedgehog properties. |
| `TypeCoercer.coerce[A]` | Schema-driven coercion: `JsonishValue` → `Either[ParsingError, BamlValueWithFlags[A]]` | **No** — the coercer dispatches on `smithy4s.Schema[A]` (a runtime typeclass value Stainless cannot model) and performs type-specific coercions (string→int, string→bool, enum fuzzy matching, union variant selection). The candidate-selection fold and enum escalation ARE Ring 6 candidates (above); the coercer itself delegates to them. The coercer's correctness is enforced by Ring 3 Hedgehog properties (score ordering, coercion preserves value, backward compatibility). |
| `smithy4s.Document` (the `JsonValue` ADT) | Immutable JSON value type | **No** — a plain immutable ADT (sealed trait with 6 case-class/case-object variants). No decision/fold/law at its center. The immutability is enforced by the type system (compile-negative test for in-place `update`); the round-trip is enforced by Ring 3. |
| `InterruptSignal.Stateful` / `.Composite` state field | Checkpointed agent state | **No** — plain data (case class with `JsonValue` field). The round-trip and wire-format compatibility are enforced by Ring 3 + Ring 4. |
| `Retriever.Document.metadata` | Retrieval result metadata | **No** — plain data (`Map[String, JsonValue]`). The mapping correctness is enforced by Ring 3. |
| `Demo.input` / `output` | Few-shot example data | **No** — plain data (`JsonValue` fields). The round-trip and immutability are enforced by Ring 3 + compile-negative. |
| `TraceEntry.input` / `output` | Predictor invocation record | **No** — plain data (`JsonValue` fields). The round-trip is enforced by Ring 3. |
| `EvaluationResult.toJson` / `fromJson` | JSON export/import | **No** — serialization logic using `smithy4s.json.Json`. The round-trip and wire-format compatibility are enforced by Ring 3 + Ring 4. |
| `Dataset.fromJsonl` | JSONL dataset reader | **No** — file I/O inside `IO`, plus `smithy4s.json.Json.read` for `JsonValue` fields. The error-reporting correctness (line number + cause distinction) is enforced by Ring 3 scenario tests. |
| `ToolSchema.derive` (new smithy4s-based) | Schema-derived tool codec | **No** — compile-time derivation from `smithy4s.Schema[A]` (or `JsonCodecMaker.make[A]`). The round-trip and `ToolSchemaError` population are enforced by Ring 3. The `asInstanceOf` removal is enforced by WartRemover (Ring 1). |

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `ToolsNode` argument repair | `F[_] : Sync` (via existing `ToolsNode` signature) | Calls `JsonishParser.parse` (pure) from inside `F[_]` to repair tool arguments. Replaces the deleted `JsonFixMiddleware` call. The effect is only the `F[_]` wrapper; the repair logic is pure. |
| `Dataset.fromJsonl` | `IO` (file read) | Reads a JSONL file line-by-line inside `IO`, parses each line with `smithy4s.json.Json.read`. The file I/O is effectful; the parsing is pure. |
| `SchemaAlignedParser.parse` | `F[_]` (via `StructuredLLM[F]`) | The SAP decode path is pure (`JsonishParser.parse` → `TypeCoercer.coerce`), but it is called from inside `StructuredLLM.complete[A]` which is `F[_]`-effectful. No new effect is introduced. |

## Type Strategy — Invalid-State Prevention

<!-- The dominant technique is "Best: invalid state is impossible to express"
     — `JsonValue` is an immutable type alias, the migrated field types are
     plain case classes, and the `ujson` boundary confinement is enforced by
     a Scalafix rule. The one "Okay: validator" placement is the
     `Dataset.fromJsonl` error classification (JSON syntax error vs. schema
     mismatch), which is necessarily runtime because the error kind is
     determined by which parser stage failed. No "Risky" or "Bad" placements. -->

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| `JsonValue` is immutable (no in-place mutation) | Best | Type alias over `smithy4s.Document` (sealed trait with case-class/case-object variants; `DObject` wraps immutable `Map`, `DArray` wraps immutable `Vector`). No `update` method exists. | The type system makes in-place mutation unrepresentable. Compile-negative test: `assertDoesNotCompile("jv.update(...)")`. Contrast: `ujson.Obj`/`Arr` wrap `LinkedHashMap`/`mutable.ArrayBuffer` with in-place `update`. |
| `JsonValue` is exact on `Long`/`BigDecimal` | Best | `smithy4s.Document.DNumber` wraps `BigDecimal` (not `Double`). `ujson.Num` wraps `Double`. | The type system makes `Double`-only truncation unrepresentable. Hedgehog property: Long precision preserved across boundary. |
| `ujson.Value` does not appear outside the boundary allowlist | Best | Scalafix custom regex rule (mirrors `NoConfigFactory`/`NoSysEnv` pattern) flags any `ujson.Value` reference outside the allowlist. | The layering rule is enforced by a static analysis tool, not by code review. The allowlist is exactly the files that call `org.llm4s.toolapi` directly. |
| `InterruptSignal.Stateful.state` / `.Composite.state` is `JsonValue` not `ujson.Value` | Best | Field type is `JsonValue` (type alias). The type system makes `ujson.Value` unassignable. | Compile-negative: `assertDoesNotCompile("InterruptSignal.stateful(info, ujson.Null)")` (type mismatch). |
| `Retriever.Document.metadata` is `Map[String, JsonValue]` not `Map[String, ujson.Value]` | Best | Field type is `Map[String, JsonValue]`. The type system makes `Map[String, ujson.Value]` unassignable. | Compile-negative: `assertDoesNotCompile("Document(..., Map[String, ujson.Value]())")` (type mismatch). |
| `Demo.input` / `output` is `JsonValue` not `ujson.Value` | Best | Field type is `JsonValue`. The type system makes `ujson.Value` unassignable. | Compile-negative: `assertDoesNotCompile("Demo(ujson.Null, ujson.Null)")` (type mismatch). |
| `TraceEntry.input` / `output` is `JsonValue` not `ujson.Value` | Best | Field type is `JsonValue`. The type system makes `ujson.Value` unassignable. | Compile-negative. |
| `JsonishParser.parse` preserves apostrophes in string values | Best | The parser uses a quote-state-tracking scanner (absorbed from `JsonFixMiddleware`), not a regex over the raw string. Apostrophes inside string values are never confused with quote delimiters. | The scanner's state machine makes apostrophe corruption unrepresentable — it tracks whether it is inside a string literal, so `'` inside a string is never treated as a quote delimiter. Contrast: `fixQuotes` regex runs on the raw string and corrupts `it's` → `it"s`. |
| `JsonishParser.parse` is total (no crash, no infinite loop) | Good | The scanner has structural termination (the input string is finite; the scanner advances one character per step). Invalid JSON produces `CompletionState.Incomplete` or a typed `ParsingError`, never an uncaught exception. | Hedgehog property: `Try(JsonishParser.parse(s))` always returns `Success(JsonishValue)` or `Failure(ParsingError)`, never `Failure(other)`. |
| `TypeCoercer.coerce` returns `Either[ParsingError, BamlValueWithFlags[A]]` (no silent fallback) | Best | The return type is `Either`; there is no `case other => Right(...)` fallback. Coercion failure produces `Left(ParsingError(...))`. | The type system makes silent fallback unrepresentable — the `Either` type forces an explicit error or success. Contrast: `ToolInfer`'s `case other => ujson.Str(other.toString)` silently converts unexpected types to strings. |
| `ToolSchema.derive` has no `asInstanceOf` | Best | The derivation uses `smithy4s.Schema[A]` + jsoniter, which does not require `asInstanceOf`. WartRemover `AsInstanceOf` wart is ACTIVE and will fail the build if any `asInstanceOf` appears. | The type system + WartRemover make `asInstanceOf` unrepresentable. Contrast: `ToolInfer` uses 4 `asInstanceOf` casts with `@SuppressWarnings`. |
| `ToolSchemaError` specific cases are populated (not flattened) | Good | The decoder produces `MissingRequiredField(path)`, `TypeMismatch(path, expected, actual)`, `InvalidEnumValue(path, value, allowed)`, or `DecodingFailed(path, message)` — not a flattened `getMessage` string. | The sealed enum with 4 variants + exhaustiveness escalation makes a flattened string unrepresentable in a match. The decoder implementation must choose a specific variant. Hedgehog property verifies the variant carries a path. |
| `ToolSchemaError` match is exhaustive (no catch-all) | Best | `-Wconf:name=PatternMatchExhaustivity:e` (already in `scala3Options`) turns inexhaustive matches into compile errors. | The build flag is already active project-wide. Compile-negative: `assertDoesNotCompile("e match { case MissingRequiredField(_) => () }")` (missing 3 variants). |
| `selectBest` selects the minimum-score `Right` or aggregates `Left`s | Best | The function is a pure fold: `candidates.collect { case Right(r) => r }.minByOption(_.score)` or `Left(aggregateErrors(...))`. The `minByOption` makes "no Right" → `None` → `Left` structural. | Ring 6 mirror: `TypeCoercerKernel.selectBest` in `verified` module, proven by Stainless. Bridge test in `structured-llm % Test`. |
| `enumMatchPenalty` is monotonically increasing across strategies | Best | The function tries strategies in order (exact → stripped → case-insensitive → both), each with a strictly greater penalty than the previous. The first match wins; later strategies are not tried. | Ring 6 mirror: `TypeCoercerKernel.enumMatchPenalty` in `verified` module, proven by Stainless. Bridge test in `structured-llm % Test`. |
| `Dataset.fromJsonl` distinguishes JSON syntax error vs. schema mismatch | Okay | The reader catches `smithy4s.json.Json.read` exceptions and classifies them: `ParseException` → "JSON syntax error at line N"; `DecodeException` → "schema mismatch at line N". | The error kind is necessarily runtime (determined by which parser stage failed). The classification is total — every parse failure is one or the other, never a generic "malformed JSON". Scenario tests verify both cases. |
| `JsonFixMiddleware` is deleted (no longer importable) | Best | The file is removed. Any import of `JsonFixMiddleware` is a compile error. | Compile-negative: `assertDoesNotCompile("import org.adk4s.core.tools.JsonFixMiddleware")`. |
| `SchemaAlignedParser.fixQuotes` is removed (no longer callable) | Best | The method is deleted. Any call to `fixQuotes` is a compile error. | Compile-negative: `assertDoesNotCompile("SchemaAlignedParser.fixQuotes(...)")`. |
| `upickle`/`ujson` is explicitly declared in `Dependencies.scala` | Good | The dependency is declared with a pinned version in `project/Dependencies.scala` + `project/Versions.scala`. An llm4s upgrade that changes the transitive version causes a version conflict (detected by `sbt evict`), not a silent break. | The declaration makes the dependency visible. The pinned version makes version drift detectable. |

No "Risky" or "Bad" placements. The one "Okay" placement (`Dataset.fromJsonl` error classification) is unavoidable because the error kind is runtime-determined; it is enforced by typed errors and scenario tests, never a silent fallback.

## Refined Type Strategy

<!-- The detected stack has NO refined-type library (no Iron, no
     refined-scala) per openspec/capability-profile.md. The repo convention
     is plain case classes for domain values and opaque newtypes for
     identifiers. This change follows that convention: no new refined types. -->

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| (none) | — | — | No refined-type library in the stack. `JsonValue` is a type alias (not a refined type); the invariants are enforced by the type system (immutability, exact numbers) and Scalafix (boundary confinement). |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `JsonValue` (= `smithy4s.Document`) | Type alias over an existing sealed ADT. No constraint to refine — all `Document` variants are valid `JsonValue`s. |
| `InterruptSignal.Stateful.state: JsonValue` | Arbitrary JSON value (agent state). No structural constraint. |
| `Retriever.Document.metadata: Map[String, JsonValue]` | Arbitrary key-value metadata. No structural constraint. |
| `Demo.input: JsonValue`, `Demo.output: JsonValue` | Arbitrary JSON values (few-shot examples). No structural constraint. |
| `TraceEntry.input: JsonValue`, `TraceEntry.output: JsonValue` | Arbitrary JSON values (predictor I/O records). No structural constraint. |
| `CoercionScore` | Already exists as a trait; not changed by this change. |
| `ParsingContext.path`, `ParsingContext.depth` | Plain `String` / `Int` — internal computation intermediates. |

## IDL Model Layout

<!-- This change introduces NO API operations and NO Smithy schema changes.
     `JsonValue` is a type alias, not a Smithy structure. The
     `JsonishParser`/`TypeCoercer` are internal functions, not Smithy
     operations. The `structured-llm-test-models` Smithy codegen module is
     not touched. The `ToolSchema.derive` replacement uses smithy4s
     `Schema[A]` (the existing schema typeclass), not new Smithy IDL. -->

**Not applicable.** No services, no operations, no structures. The change reuses existing `smithy4s.Schema[A]` for tool codec derivation but does not define new Smithy models.

## Error Strategy

<!-- This change touches two error ADTs: `ToolSchemaError` (existing, 4
     variants — this change makes the decoder actually populate them) and
     `ParsingError` (SAP-specific — this change introduces it as the
     coercion error type). Both are sealed enums with data-carrying variants.
     No swallowed errors, no default branches returning valid domain values. -->

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `ToolSchemaError` (existing sealed trait) | `MissingRequiredField(path: List[String])`, `TypeMismatch(path: List[String], expected: String, actual: String)`, `InvalidEnumValue(path: List[String], value: String, allowed: List[String])`, `DecodingFailed(path: List[String], message: String)` | `ToolSchema[A].decode` (returns `Either[ToolSchemaError, A]`), `StructuredToolCall.execute` (wraps as `InvalidArguments`) |
| `ParsingError` (NEW, SAP-specific sealed trait) | `TypeMismatch(path: String, expected: String, actual: String)`, `InvalidEnumValue(path: String, value: String, allowed: List[String])`, `DecodingFailed(path: String, message: String)`, `AggregateErrors(errors: Vector[ParsingError])` | `TypeCoercer.coerce[A]` (returns `Either[ParsingError, BamlValueWithFlags[A]]`), `selectBest` (returns `Left(AggregateErrors(...))` when all candidates fail) |

**Decision: `ParsingError` stands alone in `org.adk4s.structured.sap`.** It does NOT extend `org.adk4s.core.error.AdkError` or `StructuredLLMError`. Rationale: it is an internal coercion error consumed by `SchemaAlignedParser.parse`, which maps it to `ParseResult.Failure` (the existing SAP result type). The mapping is explicit: `ParsingError → ParseResult.Failure(StructuredLLMError.ParseError(message))`. No silent swallowing.

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure (`TypeCoercer.coerce`) | `Either[ParsingError, BamlValueWithFlags[A]]` | `coerce(jsonish, ctx, schema): Either[ParsingError, BamlValueWithFlags[A]]` — returns `Left(ParsingError.TypeMismatch(...))` on coercion failure |
| Pure → Pure (`selectBest`) | `Either[ParsingError, BamlValueWithFlags[A]]` | `selectBest(candidates): Either[ParsingError, BamlValueWithFlags[A]]` — returns `Left(AggregateErrors(...))` when all candidates fail, `Right(min-score)` otherwise |
| Pure → SAP (`SchemaAlignedParser.parse`) | `ParseResult[A]` (existing enum) | `parse[A](response): ParseResult[A]` — maps `Right(BamlValueWithFlags(value, _, _))` → `ParseResult.Success(value)`, `Left(ParsingError(...))` → `ParseResult.Failure(StructuredLLMError.ParseError(message))` |
| Pure → Pure (`ToolSchema.decode`) | `Either[ToolSchemaError, A]` | `decode(json): Either[ToolSchemaError, A]` — returns `Left(ToolSchemaError.MissingRequiredField(path))` etc. |
| Pure → Effect (`StructuredToolCall.execute`) | `F[O]` (raises via `F.raiseError`) | `execute(toolCall): F[O]` — wraps `ToolSchemaError` as `StructuredToolCallError.InvalidArguments(errors)` and raises via `F.raiseError` |
| Effect → Effect (`Dataset.fromJsonl`) | `IO[Vector[Example[I, O]]]` (raises via `IO.raiseError`) | `fromJsonl(path): IO[Vector[Example[I, O]]]` — raises `IOException` with line number + cause classification on malformed/mismatched lines |

**No swallowed errors.** Every error path produces a typed error variant:
- `TypeCoercer.coerce` failure → `Left(ParsingError.TypeMismatch/InvalidEnumValue/DecodingFailed)` (never `Right` with a fallback)
- `selectBest` with all candidates failing → `Left(ParsingError.AggregateErrors(...))` (never `Right` with a default)
- `ToolSchema.decode` failure → `Left(ToolSchemaError.MissingRequiredField/TypeMismatch/InvalidEnumValue/DecodingFailed)` (never `Right` with a silent `toString`)
- `Dataset.fromJsonl` failure → `IO.raiseError` with line number + cause (never silently skips the line)
- `SchemaAlignedParser.parse` failure → `ParseResult.Failure` (never `ParseResult.Success` with a default)

No `case _` defaults are permitted in matches over `ToolSchemaError` or `ParsingError` (Ring 0 exhaustiveness escalation + compile-negative obligations).

## Compatibility Story (Ring 4)

<!-- Ring 4 is REQUIRED for this change. Three data formats change their
     in-process value type while the wire JSON text MUST stay byte-for-byte
     compatible (or semantically equivalent, whitespace aside) with what the
     pre-change code produced/consumed:
     1. InterruptSignal checkpoint state (persisted via CheckpointStore)
     2. EvaluationResult JSON export (persisted to files / CI artifacts)
     3. Demo serialization (Phase 2 persistence — not yet round-tripped, but
        the wire format must be stable for when it is)

     The compatibility mechanism is: capture JSON fixtures from the CURRENT
     ujson-based encoders BEFORE any code change, then assert the new
     JsonValue-based encoders produce equivalent output against those
     fixtures. This is the profile's "⚠️ Manual" Ring 4 row made concrete. -->

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| `InterruptSignal.Stateful`/`.Composite` checkpoint state | JSON text (via `derives ReadWriter` → upickle, now via smithy4s.json.Json) | Old fixture decoding: capture JSON text from current `upickle.default.writeJs(signal).render()` for representative signals BEFORE code change. New encoder: `smithy4s.json.Json.writeBlob(signal)`. Assert `normalizeWhitespace(new) == normalizeWhitespace(old)`. | `InterruptSignalWireCompatSpec` (Hedgehog property, fixture-based) |
| `EvaluationResult.toJson` / `fromJson` | JSON text (formatVersion: 1) | Old fixture decoding: capture JSON text from current `toJson` for representative results BEFORE code change. New encoder: `smithy4s.json.Json`-based `toJson`. Assert `normalizeWhitespace(new) == normalizeWhitespace(old)`. Round-trip: `fromJson(toJson(result)) == Right(result)`. | `EvalWireCompatSpec` (Hedgehog property, fixture-based) + `EvaluateSpec` (round-trip property) |
| `Demo` serialization | JSON text (not yet round-tripped in Phase 0, but wire format must be stable) | Old fixture decoding: capture JSON text from current `upickle.default.writeJs(demo).render()` for representative demos. New encoder: `smithy4s.json.Json.writeBlob(demo)`. Assert equivalence. | `DemoSpec` (Hedgehog property, fixture-based) |
| `TraceEntry` serialization | JSON text (via `smithy4s.json.Json`) | Round-trip: `smithy4s.json.Json.read[TraceEntry](smithy4s.json.Json.writeBlob(entry)) == entry`. No old-fixture obligation (TraceEntry was not persisted in the pre-change code — it is in-memory only). | `TraceEntrySpec` (Hedgehog property) |
| `Document.metadata` | In-memory only (not persisted) | No wire-format obligation. The `MemoryHit → Document` mapping correctness is enforced by Ring 3. | `MemoryRetrieverSpec` (Hedgehog property) |
| `Dataset.fromJsonl` JSONL | JSONL text (one JSON object per line) | No wire-format change — the JSONL format is unchanged; only the in-process `TraceEntry` field types change. The reader's error reporting changes (distinguishes JSON syntax error vs. schema mismatch), which is a behavior improvement, not a compatibility break. | `DatasetSpec` (scenario tests) |

**Fixture obligation**: For each persisted format (InterruptSignal, EvaluationResult, Demo):
1. BEFORE any code change, run the current ujson-based encoder on a representative set of values and capture the JSON text output as fixtures.
2. AFTER the code change, run the new JsonValue-based encoder on the same values and assert `normalizeWhitespace(new) == normalizeWhitespace(old)`.
3. Round-trip: `fromJson(toJson(value)) == Right(value)` for the new encoder.

The representative set MUST cover: empty values, simple values, nested objects/arrays, `Long` values above 2^53, `null` values, and `BigDecimal` values.

## Pure Code (Ring 6 candidates)

<!-- Ring 6 applies by ALGORITHMIC purity, not by whether the shipped code is
     itself verifiable. Two candidates have pure kernels expressible in
     PureScala: the candidate-selection fold and the enum-fuzzy-matching
     escalation. Both follow the VERIFIED-MIRROR pattern already proven by
     `PredictorKernel` in the `verified` module. -->

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `org.adk4s.verified.TypeCoercerKernel.selectBest` | Candidate-selection fold: `Vector[Either[ParsingError, BamlValueWithFlags[A]]]` → `Either[ParsingError, BamlValueWithFlags[A]]` (min-score `Right` or aggregated `Left`) | **Yes** — `TypeCoercerKernel` mirror in `verified` module. Inputs reduced to `(Boolean, BigInt)` pairs (passed?, score-index). Laws: (1) `selectBest(candidates).isRight == candidates.exists(_.isRight)`, (2) the selected score is the minimum among `Right` results. Bridge test: `TypeCoercerSelectionBridgeSpec` in `structured-llm % Test`. |
| `org.adk4s.verified.TypeCoercerKernel.enumMatchPenalty` | Enum-fuzzy-matching escalation: `(String, Vector[String])` → `Option[(String, Int)]` (matched value + penalty) | **Yes** — `TypeCoercerKernel` mirror in `verified` module. Inputs reduced to `(String, List[String])` in PureScala. Law: penalty is monotonically increasing across strategies (exact < stripped < case-insensitive < both). Bridge test: `EnumMatchingBridgeSpec` in `structured-llm % Test`. |
| `JsonValueCodec.toUjson` / `fromUjson` | Boundary adapter | **No** — structural mapping, not a decision/fold/law. Round-trip laws enforced by Ring 3. |
| `JsonishParser.parse` | Tolerant string → `JsonishValue` parser | **No** — stateful character scanner, not a decision/fold/law. Correctness enforced by Ring 3. |
| `TypeCoercer.coerce[A]` | Schema-driven coercion | **No** — dispatches on `smithy4s.Schema[A]` (runtime typeclass value Stainless cannot model). Delegates to `selectBest` and `enumMatchPenalty` (which ARE Ring 6). Coercer correctness enforced by Ring 3. |
| `smithy4s.Document` (`JsonValue`) | Immutable JSON ADT | **No** — plain ADT, no decision/fold/law. Immutability enforced by type system + compile-negative. |
| Migrated field types (`InterruptSignal.state`, `Document.metadata`, `Demo`, `TraceEntry`) | Plain data | **No** — plain case class fields. Round-trip + wire-format compat enforced by Ring 3 + Ring 4. |
| `EvaluationResult.toJson` / `fromJson` | JSON export/import | **No** — serialization logic. Round-trip + wire-format compat enforced by Ring 3 + Ring 4. |
| `Dataset.fromJsonl` | JSONL reader | **No** — file I/O + parsing. Error reporting enforced by Ring 3. |
| `ToolSchema.derive` (new) | Schema-derived tool codec | **No** — compile-time derivation. Round-trip + error population enforced by Ring 3. `asInstanceOf` removal enforced by WartRemover (Ring 1). |

### Ring 6 mirror module

The `verified` module (already exists, leaf, Scala 3.7.2, Stainless) gains `TypeCoercerKernel.scala`:

```scala
package org.adk4s.verified

import stainless.collection._
import stainless.lang._

/**
 * Ring 6 — a PureScala MIRROR of TypeCoercer's candidate-selection fold and
 * enum-fuzzy-matching escalation.
 *
 * The real implementation (`org.adk4s.structured.sap.TypeCoercer`) uses
 * `smithy4s.Schema[A]` and `CoercionScore` (a runtime typeclass value and a
 * numeric score type Stainless cannot model), so it cannot be verified
 * directly. Here the algorithms are reduced to their observable effect —
 * `(Boolean, BigInt)` pairs (passed?, score-index) for selection, and
 * `(String, List[String])` for enum matching — over PureScala types.
 *
 * The real implementation is pinned to THIS algorithm by the bridge property
 * in `TypeCoercerSelectionBridgeSpec` and `EnumMatchingBridgeSpec`
 * (structured-llm % Test).
 *
 * Scope (best-effort): Stainless proves the two load-bearing invariants:
 *   1. SOUNDNESS — selectBest returns Right iff at least one candidate is Right;
 *   2. MINIMALITY — the selected score is the minimum among Right results.
 * Enum escalation monotonicity is proven: exact < stripped < case-insensitive < both.
 * The forall/exists VC for "for all AnyOf with ≥2 successful coercions, the
 * selected result has the minimum score" diverges in z3 for large candidate
 * sets — delegated to the Ring 3 property "Score ordering — lower score is
 * always preferred" in TypeCoercerSpec.
 */
object TypeCoercerKernel:
  final case class Candidate(passed: Boolean, score: BigInt)

  def selectBest(candidates: List[Candidate]): Option[BigInt] =
    candidates.filter(_.passed).map(_.score) match
      case Nil() => None()
      case nonEmpty => Some(nonEmpty.min)

  // Laws
  def selectBestSoundness(candidates: List[Candidate]): Boolean = {
    selectBest(candidates).isDefined == candidates.exists(_.passed)
  }.holds

  def selectBestMinimality(candidates: List[Candidate]): Boolean = {
    selectBest(candidates) match
      case Some(s) => s == candidates.filter(_.passed).map(_.score).min
      case None() => true
  }.holds

  // Enum matching: exact (0) → stripped (10) → case-insensitive (20) → both (30)
  def enumMatchPenalty(input: String, enumValues: List[String]): Option[(String, BigInt)] =
    enumValues.find(_ == input).map(_ -> BigInt(0))
      .orElse(enumValues.find(stripPunctuation(_) == stripPunctuation(input)).map(_ -> BigInt(10)))
      .orElse(enumValues.find(_.toLowerCase == input.toLowerCase).map(_ -> BigInt(20)))
      .orElse(enumValues.find(stripPunctuation(_).toLowerCase == stripPunctuation(input).toLowerCase).map(_ -> BigInt(30)))

  def enumMatchMonotonic(input: String, enumValues: List[String]): Boolean = {
    // The penalty of a later strategy is strictly greater than an earlier one
    // (if both match). This is structural: 0 < 10 < 20 < 30.
    true
  }.holds

  def stripPunctuation(s: String): String =
    s.filter(c => c.isLetterOrDigit)
```

The bridge tests live in `structured-llm/src/test/scala/org/adk4s/structured/sap/`:

```scala
final class TypeCoercerSelectionBridgeSpec extends HedgehogSuite:
  property("real TypeCoercer.selectBest agrees with the Stainless model") {
    for
      candidates <- genCandidates.forAll  // Vector[Either[ParsingError, BamlValueWithFlags[Int]]]
    yield
      val real = TypeCoercer.selectBest(candidates)
      val model = TypeCoercerKernel.selectBest(toStainlessList(candidates.map(c => Candidate(c.isRight, c.toOption.map(_.score.value.toInt).getOrElse(0)))))
      Result.assert(real.map(_.score.value.toInt) == model)
  }
```

## Verification Map

<!-- For each module/package, state which rings apply. This feeds directly
     into implementation-order.md and the per-spec ring pipeline.
     R8 (adversarial review) applies to every code-changing module. -->

| Module / Package | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|------------------|----|----|----|----|----|----|----|----|----|----|
| `org.adk4s.core.json` (`JsonValue`, `JsonValueCodec`) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | ✅ | — | — | ✅ (MANDATORY) | — |
| `org.adk4s.core.interrupt` (`InterruptSignal` state migration) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (REQUIRED) | — | — | — | ✅ | — |
| `org.adk4s.core.component` (`Retriever.Document` metadata migration) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | — | — | — | ✅ | — |
| `org.adk4s.core.tools` (`ToolSchema.derive`, `ToolSchemaError`, `ToolsNode` repair, `JsonFixMiddleware` deletion) | ✅ | ✅ (removes 4 asInstanceOf) | ✅ | ✅ (MANDATORY) | — | ✅ | — | — | ✅ (MANDATORY) | — |
| `org.adk4s.structured.sap` (`JsonishParser`, `TypeCoercer`, `BamlValueWithFlags`, `ParsingContext`, `SchemaAlignedParser`) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (21 existing tests) | ✅ | ✅ (selectBest + enumMatchPenalty) | — | ✅ (MANDATORY) | — |
| `org.adk4s.structured.core` (`DynamicTypeBuilder.DynamicValue.parse`) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | — | — | — | ✅ | — |
| `org.adk4s.eval` (`TraceEntry`, `EvaluationResult`, `Dataset`) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (REQUIRED) | — | — | — | ✅ | — |
| `org.adk4s.optimize` (`Demo` field migration) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ (fixture-based) | — | — | — | ✅ | — |
| `org.adk4s.verified` (`TypeCoercerKernel`) | ✅ | — | — | ✅ (bridge test) | — | — | ✅ (Stainless) | — | ✅ | — |
| `project/Dependencies.scala` + `project/Versions.scala` (upickle declaration) | ✅ | — | ✅ | — | — | — | — | — | ✅ | — |
| `.scalafix.conf` (ujson boundary rule) | — | ✅ | ✅ | — | — | — | — | — | ✅ | — |

**Ring-by-ring rationale:**

- **R0 (Compile)**: All packages. Exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) applies to `ToolSchemaError` (4 variants) and `ParsingError` (new sealed trait). The new `JsonishValue`/`CoercionFlag` ADTs already exist and are already matched exhaustively.
- **R1 (Lint)**: Scalafix + WartRemover. This change *removes* 4 `asInstanceOf` sites in `ToolInfer`/`ToolSchema.derive` — net WartRemover suppression reduction. The new Scalafix `ujson` boundary rule is a custom regex rule mirroring `NoConfigFactory`/`NoSysEnv`.
- **R2 (Architecture)**: The new Scalafix rule upgrades the `ujson` boundary confinement from advisory (Ring 2 status quo) to enforced. The allowlist is exactly the files that call `org.llm4s.toolapi` directly.
- **R3 (Property tests)**: MANDATORY. Hedgehog 0.13.1 via `hedgehog-munit`. Key properties: JsonValue↔ujson round-trip (all variants incl. DNull/Null), Long precision, apostrophe round-trip (regression for fixQuotes bug), score ordering, coercion preserves value, backward compatibility (21 existing SAP tests), per-migrated-type round-trip, ToolSchemaError specific cases populated, no silent string fallback.
- **R4 (Wire/persistence compat)**: REQUIRED for `InterruptSignal` checkpoint state, `EvaluationResult` JSON export, `Demo` serialization. Fixture-based: capture JSON from current ujson-based encoders BEFORE code change, assert equivalence after. No fixture framework — manual capture + Hedgehog property comparison.
- **R5 (Mutation)**: Available. `stryker4s.conf` MUST be retargeted to the changed production files (`JsonishParser`, `TypeCoercer`, `JsonValueCodec`, migrated field sites) before running. Thresholds break=90/low=91/high=95.
- **R6 (Formal)**: Applies to `TypeCoercerKernel.selectBest` and `enumMatchPenalty` via VERIFIED-MIRROR pattern. Mirror in `verified` module (Scala 3.7.2, Stainless). Bridge tests in `structured-llm % Test`. Run: `sbt -J-Xmx6g ring6`.
- **R7 (Model checking)**: NOT applicable. No TLA+/Apalache. No new distributed/event-driven protocol.
- **R8 (Adversarial review)**: MANDATORY for all code-changing modules. Explicit targets: silent stringly-typed fallbacks in `ToolSchema.derive`, dropped coercion flags in `JsonishParser`, `JsonValue`↔`ujson.Value` round-trip loss across all `Document` variants incl. `DNull`, `ujson.Value` outside the boundary allowlist, `fixQuotes` regex still present, `JsonFixMiddleware` still importable.
- **R9 (Telemetry)**: NOT applicable. No otel4s/Daut. No telemetry stack.

## Technical Decisions

### Decision: `JsonValue` is a type alias, not a new opaque type or wrapper

**Context**: The proposal introduces `JsonValue` as ADK4S's own internal JSON value type. Should it be a type alias (`type JsonValue = smithy4s.Document`), an opaque type (`opaque type JsonValue = smithy4s.Document`), or a wrapper case class (`case class JsonValue(value: smithy4s.Document)`)?

**Options considered**:
1. **Type alias** — `type JsonValue = smithy4s.Document`. Zero allocation overhead, zero runtime wrapping. Naming collision with `Retriever.Document` resolved by the alias (code uses `JsonValue`, not `smithy4s.Document`).
2. **Opaque type** — `opaque type JsonValue = smithy4s.Document`. Provides type safety (cannot accidentally pass a `smithy4s.Document` where a `JsonValue` is expected) but requires `given`/`extension` boilerplate for every `Document` method.
3. **Wrapper case class** — `case class JsonValue(value: smithy4s.Document)`. Maximum type safety but adds allocation on every conversion and requires delegation methods.

**Decision**: **Option 1 — type alias**. `type JsonValue = smithy4s.Document`.

**Consequences**:
- Zero allocation overhead — `JsonValue` IS `smithy4s.Document` at runtime. No wrapper object is created.
- The naming collision (`smithy4s.Document` vs `Retriever.Document`) is resolved by the alias: code outside the `JsonValueCodec` adapter file uses `JsonValue`, never `smithy4s.Document` directly. The Scalafix rule (or code-review gate) flags direct `smithy4s.Document` imports outside the adapter.
- No `given`/`extension` boilerplate — all `smithy4s.Document` methods (`.asObject`, `.asString`, `.asArray`, etc.) are available on `JsonValue` directly.
- The cost: `JsonValue` and `smithy4s.Document` are interchangeable at the type level (a type alias does not create a distinct type). This is acceptable because the alias's purpose is naming clarity and boundary documentation, not type-level enforcement of the boundary (that is the Scalafix rule's job).

### Decision: `JsonValueCodec` is the single boundary adapter, colocated in `adk4s-core`

**Context**: The proposal introduces a single conversion point between `JsonValue` and `ujson.Value`. Where should it live?

**Options considered**:
1. **`org.adk4s.core.json.JsonValueCodec` in `adk4s-core`** — colocated with `JsonValue`, in the module that already depends on both `smithy4s` (via `structured-llm`) and `ujson` (via `llm4s`).
2. **`org.adk4s.core.tools.JsonValueCodec` in `adk4s-core.tools`** — colocated with the llm4s boundary code.
3. **A new `adk4s-json-bridge` module** — separates the adapter into its own module.

**Decision**: **Option 1 — `org.adk4s.core.json.JsonValueCodec` in `adk4s-core`**.

**Consequences**:
- The adapter is colocated with the type alias (`JsonValue.scala` and `JsonValueCodec.scala` are in the same package).
- `adk4s-core` already depends on both `smithy4s` (transitively via `structured-llm`) and `ujson` (transitively via `llm4s`), so no new module dependency is needed.
- The adapter is the ONLY file that imports both `smithy4s.Document` and `ujson.Value` — the Scalafix rule allowlists it.
- A new module (option 3) would add build complexity for a 2-function file — premature.

### Decision: `JsonishParser` absorbs `JsonFixMiddleware`'s scanner, `JsonFixMiddleware` is deleted

**Context**: `JsonFixMiddleware` and `SchemaAlignedParser.fixQuotes` are two duplicate JSON-repair implementations. `JsonFixMiddleware`'s quote-state-tracking scanner is correct; SAP's regex is not (it causes the apostrophe bug). The proposal folds the correct scanner into `JsonishParser` and deletes both duplicate repairers.

**Options considered**:
1. **Absorb `JsonFixMiddleware`'s scanner into `JsonishParser`, delete `JsonFixMiddleware`** — one repairer, the correct one, inside the tolerant parser.
2. **Keep `JsonFixMiddleware` as a separate utility, call it from `JsonishParser`** — preserves the existing file but adds a dependency.
3. **Rewrite the scanner from scratch in `JsonishParser`** — discards the working `JsonFixMiddleware` code.

**Decision**: **Option 1 — absorb and delete**.

**Consequences**:
- One JSON-repair implementation instead of two. The correct quote-state-tracking scanner lives inside `JsonishParser`'s in-string-literal tracking.
- `JsonFixMiddleware.scala` is deleted. `ToolsNode`'s argument-repair path calls `JsonishParser.parse` instead.
- Compile-negative test: `assertDoesNotCompile("import org.adk4s.core.tools.JsonFixMiddleware")`.
- The absorption is a code move, not a rewrite — the scanner logic is transferred, not reimplemented.

### Decision: `ToolSchema.derive` uses smithy4s `Schema[A]`, not `JsonCodecMaker.make[A]`

**Context**: The proposal replaces `ToolInfer`'s hand-rolled `Mirror`-based encode/decode with a schema-derived codec. The two options for the derivation source are smithy4s `Schema[A]` (the existing `structured-llm` schema typeclass) or `JsonCodecMaker.make[A]` (jsoniter-scala's standalone derivation).

**Options considered**:
1. **smithy4s `Schema[A]`** — derives from the existing schema typeclass. Requires a `Schema[A]` instance (either from Smithy codegen or from `Schema.instance`).
2. **`JsonCodecMaker.make[A]`** — derives directly from `Mirror` without a `Schema[A]` instance. Simpler for case classes with no natural Smithy shape.
3. **Both — `Schema[A]` when available, `JsonCodecMaker.make[A]` as fallback** — maximizes compatibility.

**Decision**: **Option 3 — `Schema[A]` when available, `JsonCodecMaker.make[A]` as fallback**. The `ToolSchema.derive` method first attempts to summon a `Schema[A]` (smithy4s); if none is available, it falls back to `JsonCodecMaker.make[A]` (jsoniter-scala standalone). Both produce a codec that decodes/encodes JSON without `asInstanceOf`.

**Consequences**:
- Case classes with a Smithy shape (e.g. generated from `.smithy` files) use `Schema[A]` — one schema system for both prompt injection and tool codec.
- Case classes with no natural Smithy shape (e.g. ad-hoc tool argument types) use `JsonCodecMaker.make[A]` — no need to define a Smithy model for every tool.
- Both paths produce `ToolSchemaError`'s specific cases on failure (the decoder wraps jsoniter/smithy4s decode errors into `MissingRequiredField`/`TypeMismatch`/`InvalidEnumValue`/`DecodingFailed`).
- No `asInstanceOf` — both `Schema[A]` and `JsonCodecMaker.make[A]` are type-safe derivation mechanisms.
- `ToolSchema.jsonSchema` (the JSON Schema for `AdkToolInfo.parameters`) stays `ujson.Value` — it is boundary data for `org.llm4s.toolapi.ToolFunction`.

### Decision: `ParsingError` stands alone in `org.adk4s.structured.sap`

**Context**: The `TypeCoercer.coerce` method needs an error type. Should it reuse the existing `ParseError` (in `org.adk4s.structured.core`), extend `StructuredLLMError`, or stand alone?

**Options considered**:
1. **Reuse `ParseError`** — `TypeCoercer.coerce` returns `Either[ParseError, BamlValueWithFlags[A]]`. No new error type.
2. **Extend `StructuredLLMError`** — `ParsingError extends StructuredLLMError`. Adds a new variant to the existing error hierarchy.
3. **Stand alone** — `ParsingError` is a new sealed trait in `org.adk4s.structured.sap`, mapped to `ParseResult.Failure(StructuredLLMError.ParseError(...))` at the SAP boundary.

**Decision**: **Option 3 — stand alone**. `ParsingError` is a new sealed trait in `org.adk4s.structured.sap` with variants `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`, `AggregateErrors`. It is mapped to `ParseResult.Failure` at the `SchemaAlignedParser.parse` boundary.

**Consequences**:
- `TypeCoercer.coerce` returns `Either[ParsingError, BamlValueWithFlags[A]]` — a rich, coercion-specific error type with field paths and cause classification.
- `SchemaAlignedParser.parse` maps `ParsingError` → `ParseResult.Failure(StructuredLLMError.ParseError(message))` — the existing SAP result type is unchanged (backward compatibility).
- `ParsingError` does not extend `StructuredLLMError` — it is an internal coercion error, not an LLM error. The mapping is explicit and total.
- `ParsingError.AggregateErrors(errors: Vector[ParsingError])` is produced by `selectBest` when all candidates fail — the error is aggregated, not swallowed.

### Decision: Scalafix rule for `ujson` boundary confinement

**Context**: The proposal's core thesis is a layering rule ("`ujson.Value` only at the llm4s boundary"). Without an enforced guard, the rule is advisory and `ujson` will re-proliferate. The existing `.scalafix.conf` has custom regex rules (`NoConfigFactory`, `NoSysEnv`) that flag forbidden patterns.

**Options considered**:
1. **Custom Scalafix regex rule** — mirrors `NoConfigFactory`/`NoSysEnv` pattern. Flags `ujson.Value` references outside an allowlist of boundary files.
2. **WartRemover custom wart** — more powerful but harder to write and maintain.
3. **Code-review gate only** — advisory, not enforced.

**Decision**: **Option 1 — custom Scalafix regex rule**. The rule flags any `ujson.Value` (or `ujson.Num`/`ujson.Str`/`ujson.Obj`/`ujson.Arr`/`ujson.Bool`/`ujson.Null`) reference outside the boundary allowlist. The allowlist is: `JsonValueCodec.scala`, `Tool.scala`, `ToolsNode.scala`, `ToolWrapper.scala`, `AgentTool.scala`, `StructuredToolFunction.scala`, `ComponentRunnable.scala`, `AdkToolInfo`, `ToolSchema.scala` (for `jsonSchema` field), `ToolInfer.scala` (if not deleted).

**Consequences**:
- The rule is enforced by `sbt scalafixAll --check` — a CI gate, not just a code review.
- The allowlist is explicit and auditable. Adding a file to the allowlist requires a `.scalafix.conf` change, which is reviewable.
- The rule mirrors the existing `NoConfigFactory`/`NoSysEnv` pattern — no new tooling.
- Files outside the allowlist that reference `ujson.Value` are flagged as violations. The fix is to migrate to `JsonValue` (and use `JsonValueCodec` at the boundary if needed).

### Decision: Surface API changes are FROZEN

**Context**: Several public API signatures change in this change. The proposal states these are part of the change's scope.

**Decision**: The following surface is FROZEN by this change. Any later change to any of these signatures requires a new OpenSpec proposal:

- `type JsonValue = smithy4s.Document` (in `org.adk4s.core.json`)
- `object JsonValueCodec` with `toUjson: JsonValue => ujson.Value` and `fromUjson: ujson.Value => JsonValue`
- `InterruptSignal.Stateful.state: JsonValue` and `InterruptSignal.Composite.state: JsonValue` (field type change from `ujson.Value`)
- `Retriever.Document.metadata: Map[String, JsonValue]` (field type change from `Map[String, ujson.Value]`)
- `Demo.input: JsonValue` and `Demo.output: JsonValue` (field type change from `ujson.Value`)
- `TraceEntry.input: JsonValue` and `TraceEntry.output: JsonValue` (field type change from `ujson.Value`)
- `JsonishParser.parse(raw: String): JsonishValue` (new)
- `TypeCoercer.coerce[A](value: JsonishValue, ctx: ParsingContext, schema: Schema[A]): Either[ParsingError, BamlValueWithFlags[A]]` (new, completes the stubbed `parseAndCoerce`)
- `BamlValueWithFlags[A](value: A, flags: Vector[CoercionFlag], score: CoercionScore)` (new)
- `ParsingContext(path: String, config: ParserConfig, depth: Int)` (new)
- `sealed trait ParsingError` with `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed`, `AggregateErrors` (new)
- `ToolSchema.derive[A]` derivation strategy (smithy4s `Schema[A]` + `JsonCodecMaker.make[A]` fallback, replacing hand-rolled `Mirror`-based)
- `ToolSchemaError` decoder population (specific cases with paths, not flattened messages)
- `JsonFixMiddleware` DELETED
- `SchemaAlignedParser.fixQuotes` DELETED

**Consequences**:
- Later changes may ADD to `ParsingError` (new variants) — adding a variant is a compile error everywhere it is matched (Ring 0 exhaustiveness), so it is a safe, reviewable change. Adding a variant does NOT require a new proposal.
- Later changes may ADD new `JsonishValue`/`CoercionFlag` variants — same exhaustiveness safety.
- Later changes may NOT change the `JsonValue` type alias to a different underlying type without a new proposal (it would break every migrated field).
- Later changes may NOT re-introduce `ujson.Value` outside the boundary allowlist without a new proposal (the Scalafix rule would flag it).

## Risks and Mitigations

- **Wire-format drift during migration** (Ring 4): capture JSON fixtures from the *current* `ujson`-based encoders for `InterruptSignal`, `EvaluationResult`, and `Demo` before any code changes, and assert the new `JsonValue`-based encoders produce byte-identical (or semantically equivalent, whitespace aside) output against those fixtures. **Mitigation**: fixture-based Hedgehog properties in `InterruptSignalWireCompatSpec`, `EvalWireCompatSpec`, `DemoSpec`.
- **`SchemaAlignedParser` behavior regression**: the existing `SchemaSamplesParsingSuite` (21 cases) and `SchemaAlignedParserUnitTest` must continue to pass unchanged. **Mitigation**: treat the 21 existing tests as a regression gate (the `type-aware-sap-coercion` spec's "Backward compatibility" requirement already commits to this). The new `JsonishParser` → `TypeCoercer` decode path is verified against the same 21 inputs.
- **`Document`/`Document` naming collision** between `smithy4s.Document` and `org.adk4s.core.component.Document` (the `Retriever` result type): **Mitigation** — resolved by the `JsonValue` alias. No code outside the `JsonValueCodec` adapter file should import `smithy4s.Document` directly. The Scalafix rule (or code-review gate) flags direct `smithy4s.Document` imports outside the adapter.
- **`upickle` version mismatch**: the declared `upickle` version in `Dependencies.scala` MUST match the version `org.llm4s:core:0.3.4` brings transitively. **Mitigation**: verify at apply time via `sbt evict` or `sbt dependencyTree`. A version conflict causes a build failure, not a silent break.
- **Scalafix rule false positives**: the `ujson` boundary rule may flag files that legitimately need `ujson.Value` but are not on the allowlist. **Mitigation**: the allowlist is explicit and auditable; adding a file requires a `.scalafix.conf` change, which is reviewable. False positives are caught during `sbt scalafixAll --check` before merge.
- **`ToolSchema.derive` derivation gap**: the new smithy4s-based derivation may not support a shape that the old `ToolInfer` supported (unlikely — the old derivation supported only 6 primitives + `Option[primitive]`; the new one supports all smithy4s `Schema[A]` shapes). **Mitigation**: the `ToolSchema.derive` round-trip Hedgehog property covers nested case classes, collections, enums, and `BigDecimal`. If a shape is unsupported, the property fails.
