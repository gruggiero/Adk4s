# Structured-LLM vs BAML: Gap Analysis and Feature Porting Report

**Date:** 2026-06-27
**Author:** Analysis of `adk4s/structured-llm` against updated `BoundaryML/baml` codebase
**Purpose:** Identify features from the updated BAML codebase that can be added to `structured-llm` (and other adk4s modules)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current State of `structured-llm`](#2-current-state-of-structured-llm)
3. [BAML Architecture Overview](#3-baml-architecture-overview)
4. [Gap Analysis by Feature Area](#4-gap-analysis-by-feature-area)
   - 4.1 [Type-Aware SAP Coercion (HIGH)](#41-type-aware-sap-coercion-high-priority)
   - 4.2 [Semantic Streaming with Completion State (HIGH)](#42-semantic-streaming-with-completion-state-high-priority)
   - 4.3 [Constraints: `@check` and `@assert` (HIGH)](#43-constraints-check-and-assert-validation-high-priority)
   - 4.4 [Retry Policies (MEDIUM)](#44-retry-policies-with-exponential-backoff-medium-priority)
   - 4.5 [Client Strategies: Fallback and Round-Robin (MEDIUM)](#45-client-strategies-fallback-and-round-robin-medium-priority)
   - 4.6 [Configurable Output Format Rendering (MEDIUM)](#46-richer-output-format-rendering-medium-priority)
   - 4.7 [Partial Types for Streaming (MEDIUM)](#47-partial-types-for-streaming-medium-priority)
   - 4.8 [Dynamic Type Builder (LOW-MEDIUM)](#48-dynamic-type-builder-low-medium-priority)
   - 4.9 [Prompt Optimization via GEPA (LOW)](#49-prompt-optimization-via-gepa-low-priority--research-feature)
   - 4.10 [Test Framework (LOW-MEDIUM)](#410-test-framework-for-structured-outputs-low-medium-priority)
   - 4.11 [Error Enrichment with Fallback History (LOW)](#411-error-enrichment-with-fallback-history-low-priority)
   - 4.12 [Unicode Quote Normalization (LOW — quick win)](#412-unicode-quote-normalization-low-priority--quick-win)
   - 4.13 [llm4s Overlap Analysis](#413-llm4s-overlap-analysis---what-already-exists-in-the-dependency)
5. [Priority Matrix](#5-priority-matrix)
6. [Recommended Implementation Order](#6-recommended-implementation-order)
7. [Appendix: File References](#7-appendix-file-references)

---

## 1. Executive Summary

The `structured-llm` module in adk4s provides type-safe structured LLM outputs by injecting Smithy IDL schemas into prompts and parsing LLM responses with a lenient JSON parser (Schema-Aligned Parser, SAP). It was inspired by BAML but built against an **older version** of the BAML codebase.

The BAML codebase has since been significantly updated with several major features that `structured-llm` does not have:

| BAML Feature | `structured-llm` Status |
|---|---|
| Schema-aligned type coercion (the core SAP innovation) | ❌ Missing — current SAP is schema-agnostic |
| Semantic streaming with `CompletionState` | ❌ Missing — only token-level streaming |
| `@check` / `@assert` validation constraints | ❌ Missing |
| Retry policies (exponential backoff, constant delay) | ❌ Missing |
| Fallback / round-robin client strategies | ❌ Missing |
| Configurable output format rendering | ❌ Missing — fixed format |
| Partial types for streaming | ❌ Missing |
| Dynamic type builder | ❌ Missing |
| GEPA prompt optimization | ❌ Missing |
| Test framework for structured outputs | ❌ Missing (only standard munit tests) |
| Error enrichment with attempt history | ❌ Missing |
| Unicode quote normalization | ❌ Missing (only single→double quote) |

The **single highest-impact improvement** is porting BAML's type-aware coercion system. The current SAP is essentially a "JSON syntax fixer" — it repairs malformed JSON text and then delegates to smithy4s' strict decoder. BAML's approach is fundamentally different: it parses into an intermediate `jsonish::Value` (which can be ambiguous/incomplete), then **coerces** that value against the target schema, trying multiple interpretations and scoring them. This is what enables BAML to achieve state-of-the-art accuracy on the Berkeley Function Calling Leaderboard.

---

## 2. Current State of `structured-llm`

### 2.1 Module Structure

```
structured-llm/src/main/scala/org/adk4s/structured/
├── package.scala                  # Type aliases, exports
├── core/
│   ├── Schema.scala               # Schema typeclass (Smithy IDL + smithy4s Schema)
│   ├── Prompt.scala               # Prompt, Message, Role, PromptTemplate
│   └── StructuredLLM.scala        # Main API trait + implementation
├── sap/
│   └── SchemaAlignedParser.scala  # Lenient JSON parser with recovery
└── template/
    └── PromptSyntax.scala         # String interpolators / DSL
```

### 2.2 Public API Surface

**`Schema[A]`** — opaque type bridging Smithy IDL (for prompts) and smithy4s Schema (for decoding):
```scala
opaque type Schema[A] = Schema.SchemaData[A]
// SchemaData has: smithyDefinition: String, description: Option[String], smithySchema: SmithySchema[A]
```

**`StructuredLLM[F[_]]`** — main API:
```scala
trait StructuredLLM[F[_]]:
  def complete[A: Schema](prompt: Prompt): F[A]              // Auto-injects schema
  def completeRaw[A: Schema](prompt: Prompt): F[A]           // No schema injection
  def completeTemplate[I, A: Schema](template, input): F[A]
  def function[I, A: Schema](template): I => F[A]
  def extractor[A: Schema](systemPrompt: String): String => F[A]
  def streamWithResult[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])]
  def streamWithResultRaw[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])]
```

**`SchemaAlignedParser`** — lenient JSON parser:
```scala
object SchemaAlignedParser:
  def parse[A: Schema](response: String): ParseResult[A]
  def parseWithConfig[A: Schema](response: String, config: ParserConfig): ParseResult[A]
  final case class ParserConfig(maxRecoveryAttempts: Int = 3, allowPartialResults: Boolean = false, strictMode: Boolean = false)
```

### 2.3 Current SAP Recovery Strategies

The current `SchemaAlignedParser` (`structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala`) applies these strategies:

**Candidate extraction (in order):**
1. Markdown fence extraction (```json``` blocks)
2. JSON segment extraction (find balanced `{...}` / `[...]`)
3. Aggregation of multiple segments into array
4. Whole response as candidate
5. Fallback to JSON string encoding

**Cleaning strategies (applied to all candidates):**
1. `removeMarkdownFences` — strips code blocks
2. `removeComments` — removes `//` and `/* */` comments
3. `fixLeadingCommas` — removes leading commas
4. `fixQuotes` — single→double quotes, unquoted key fixing
5. `removeTrailingCommas` — removes trailing commas before `]` and `}`

**Structural recovery (applied after cleaning):**
1. `applyCloseUnbalanced` — auto-close missing `}`, `]`, `"`
2. `applyInsertMissingCommas` — insert commas between object fields
3. `applyFillMissingValues` — fill missing values with `null`
4. `applyCoerceNumericStrings` — remove quotes around numeric strings
5. `applyTrimTrailingGarbage` — trim content after balanced JSON
6. `applyUnwrapSmithyMember` — unwrap `{"field": {"member": [...]}}` → `{"field": [...]}`

**Key limitation:** All of these are **text-level regex fixes**. The parser does not use the target schema to guide recovery. It tries candidates in order and returns the first that smithy4s can decode.

### 2.4 Current Test Coverage

- `SchemaAlignedParserUnitTest.scala` (7 tests) — markdown fences, missing brackets, trailing commas, string fallback, multiple JSON blocks, Smithy sanitization, member unwrapping
- `SimpleResumeTest.scala` (1 test) — end-to-end with mock LLM
- `SmithyValidationTest.scala` (2 tests) — Smithy file validation
- `ResumeSchemaTest.scala` (3 tests) — smithy4s schema extraction
- `SchemaSamplesParsingSuite.scala` (21 tests) — parsing 21 different schema samples
- `StructuredLLMStreamingTest.scala` (3 tests) — streaming with mock LLM

---

## 3. BAML Architecture Overview

### 3.1 Repository Structure

```
engine/                                    # Legacy production runtime
├── baml-runtime/src/
│   ├── internal/
│   │   ├── llm_client/
│   │   │   ├── orchestrator/              # Fallback, retry, round-robin orchestration
│   │   │   ├── primitive/                 # Provider implementations (OpenAI, Anthropic, etc.)
│   │   │   ├── strategy/                  # Fallback + round-robin strategies
│   │   │   ├── traits/                    # Chat/completion traits
│   │   │   └── retry_policy.rs            # Retry policy (constant, exponential)
│   │   └── prompt_renderer/
│   │       ├── render_output_format.rs    # Schema → prompt text (897 lines)
│   │       ├── class_walker.rs
│   │       └── enum_walker.rs
│   ├── runtime_methods/
│   │   ├── stream_function.rs             # Streaming entry point
│   │   ├── call_function.rs
│   │   └── prepare_function.rs
│   ├── optimize/                          # GEPA prompt optimization (6,761 lines)
│   │   ├── orchestrator.rs
│   │   ├── evaluator.rs
│   │   ├── pareto.rs
│   │   ├── candidate.rs
│   │   ├── applier.rs
│   │   └── storage.rs
│   ├── type_builder/mod.rs                # Dynamic type builder (1,217 lines)
│   └── test_constraints.rs                # @check/@assert evaluation
├── baml-lib/
│   ├── jsonish/                           # The SAP parser
│   │   ├── src/jsonish/
│   │   │   ├── value.rs                   # JsonishValue enum (331 lines)
│   │   │   └── parser/
│   │   │       ├── entry.rs
│   │   │       ├── fixing_parser.rs       # Forgiving JSON parser
│   │   │       ├── markdown_parser.rs
│   │   │       └── multi_json_parser.rs
│   │   └── src/deserializer/
│   │       ├── coercer/                   # Type coercion (2,800 lines total)
│   │       │   ├── mod.rs
│   │       │   ├── coerce_primitive.rs    # String/Int/Float/Bool coercion
│   │       │   ├── coerce_union.rs        # Union variant selection
│   │       │   ├── coerce_array.rs        # Array coercion
│   │       │   ├── coerce_map.rs          # Map coercion
│   │       │   ├── coerce_literal.rs      # Literal type coercion
│   │       │   ├── match_string.rs        # Fuzzy enum/string matching (424 lines)
│   │       │   └── field_type.rs
│   │       ├── deserialize_flags.rs       # Flag tracking (322 lines)
│   │       ├── score.rs                   # Scoring system (95 lines)
│   │       └── semantic_streaming.rs      # Streaming state validation (493 lines)
│   ├── baml-types/src/
│   │   ├── baml_value.rs                  # BamlValue enum, CompletionState
│   │   ├── constraint.rs                  # @check/@assert constraint types
│   │   └── ir_type/
│   │       ├── type_meta.rs               # StreamingBehavior, TypeMeta
│   │       └── display.rs                 # @stream.done/not_null/with_state display
│   └── jinja-runtime/src/
│       └── output_format/
│           ├── mod.rs                     # Configurable output format (237 lines)
│           └── types.rs                   # Rendering logic (3,580 lines)

baml_language/                             # Next-gen compiler (Bex)
├── crates/
│   ├── bex_sap/                           # Redesigned SAP
│   ├── baml_compiler_parser/
│   ├── baml_compiler_tir/
│   └── bex_vm/
```

### 3.2 BAML's SAP Pipeline (What Makes It Different)

BAML's parsing is a **three-stage** process:

```
LLM Response (string)
    │
    ▼
┌─────────────────────────┐
│ 1. Jsonish Parser       │  Forgiving JSON parser → JsonishValue
│ (fixing_parser.rs)      │  Handles: markdown, malformed JSON, multiple JSONs,
│                         │  Unicode quotes, chain-of-thought, truncation
└─────────────────────────┘
    │
    ▼ JsonishValue (can be AnyOf — multiple interpretations)
┌─────────────────────────┐
│ 2. Type Coercer         │  Schema-driven coercion → BamlValueWithFlags
│ (coercer/mod.rs)        │  Uses TypeIR to guide: string→int, enum matching,
│                         │  union variant selection, object→map, single→array
└─────────────────────────┘
    │
    ▼ BamlValueWithFlags (typed value + recovery flags)
┌─────────────────────────┐
│ 3. Scoring + Selection  │  Pick best parse among candidates
│ (score.rs)              │  Lower score = fewer coercions = better
└─────────────────────────┘
    │
    ▼ Final typed value
```

**Key insight:** The current `structured-llm` SAP skips stages 2 and 3 entirely. It does stage 1 (text fixing) and then hands directly to smithy4s' strict decoder. This means it cannot:
- Coerce `"42"` to `42` when the target is `Int`
- Fuzzy-match enum variants
- Select the best union variant
- Score multiple parse attempts

### 3.3 BAML's `JsonishValue` Type

```rust
// engine/baml-lib/jsonish/src/jsonish/value.rs
pub enum Value {
    String(String, CompletionState),
    Number(Number, CompletionState),
    Boolean(bool),
    Null,
    Object(Vec<(String, Value)>, CompletionState),
    Array(Vec<Value>, CompletionState),
    Markdown(String, Box<Value>, CompletionState),
    FixedJson(Box<Value>, Vec<Fixes>),
    AnyOf(Vec<Value>, String),  // Multiple interpretations + original string
}
```

The `AnyOf` variant is critical — it represents ambiguity in parsing. When the parser can't determine if `"42"` is a string or a number, it produces `AnyOf([String("42"), Number(42)], "42")`. The coercer then uses the target schema to disambiguate.

### 3.4 BAML's Type Coercion System

```rust
// engine/baml-lib/jsonish/src/deserializer/coercer/mod.rs
pub trait TypeCoercer {
    fn coerce(&self, ctx: &ParsingContext, target: &TypeIR, value: Option<&Value>)
        -> Result<BamlValueWithFlags, ParsingError>;
    fn try_cast(&self, ctx: &ParsingContext, target: &TypeIR, value: Option<&Value>)
        -> Option<BamlValueWithFlags>;
}
```

Specialized coercers:
- `coerce_primitive.rs` (570 lines) — String/Int/Float/Bool with extensive coercion
- `coerce_union.rs` (177 lines) — Tries each variant, scores, picks best
- `coerce_array.rs` (155 lines) — Single→array, union variant hinting
- `coerce_map.rs` (190 lines) — Object→map coercion
- `coerce_literal.rs` (147 lines) — Literal type matching
- `match_string.rs` (424 lines) — Fuzzy enum/string matching with 4 strategies

### 3.5 BAML's Scoring System

```rust
// engine/baml-lib/jsonish/src/deserializer/score.rs
pub trait WithScore {
    fn score(&self) -> i32;  // Lower is better
}
```

Each coercion adds "flags" (recovery actions taken), and each flag has a penalty:
- `OptionalDefaultFromNoValue` → 1 (minimal penalty)
- `ObjectToString` → 2 (object coerced to string)
- `StrippedNonAlphaNumeric` → 3 (punctuation stripped for matching)
- `DefaultFromNoValue` → 100 (field was missing, default used)
- `ArrayItemParseError` → 1 + error_count (array item failed to parse)

When multiple parses succeed, the one with the **lowest total score** wins.

### 3.6 BAML's CompletionState and Streaming

```rust
// engine/baml-lib/baml-types/src/baml_value.rs
pub enum CompletionState {
    Pending,      // No value yet
    Incomplete,   // Partial value (streaming)
    Complete,     // Fully parsed
}

pub struct Completion {
    pub state: CompletionState,
    pub display: bool,         // Should this be shown to user?
    pub required_done: bool,   // Must this be complete before display?
}
```

```rust
// engine/baml-lib/baml-types/src/ir_type/type_meta.rs
pub struct StreamingBehavior {
    pub needed: bool,   // @stream.not_null — don't show until non-null
    pub done: bool,     // @stream.done — don't show until complete
    pub state: bool,    // @stream.with_state — wrap in {value, state}
}
```

The `semantic_streaming.rs` module (493 lines) walks the value tree and enforces these constraints during streaming — deleting incomplete `@stream.done` fields, nulling missing `@stream.not_null` fields, sorting fields to match class definition order, etc.

### 3.7 BAML's Constraint System

```rust
// engine/baml-lib/baml-types/src/constraint.rs
pub struct Constraint {
    pub level: ConstraintLevel,  // Check or Assert
    pub expression: JinjaExpression,
    pub label: Option<String>,
}

pub enum ConstraintLevel {
    Check,   // Non-failing, results collected for inspection
    Assert,  // Strict, raises exception on failure
}
```

Constraints use Jinja expressions evaluated against the parsed value:
```baml
class Student {
  age int @check(old_enough, {{ this > 5 }})
  concentration string @assert({{ this.regex_match("[Math|Science]") }})
  @@check(age_threshold, {{ this.concentration != "calculus" or this.age > 12 }})
}
```

### 3.8 BAML's Retry and Client Strategies

```rust
// engine/baml-runtime/src/internal/llm_client/retry_policy.rs
pub struct CallablePolicy {
    max_retries: u32,
    strategy: RetryPolicyStrategy,  // ExponentialBackoff or ConstantDelay
    current: Duration,
    counter: u32,
}
```

```rust
// engine/baml-runtime/src/internal/llm_client/strategy/
pub struct FallbackStrategy {       // Try clients in order
    client_specs: Vec<ClientSpec>,
}
pub struct RoundRobinStrategy {     // Rotate through clients
    client_specs: Vec<ClientSpec>,
    current_index: AtomicUsize,
}
```

### 3.9 BAML's Configurable Output Format

```jinja
{{ ctx.output_format(
  prefix="Answer correctly and I'll tip $400:\n",
  always_hoist_enums=true,
  union_separator=" | ",
  hoist_classes="auto",
  map_style="inline",
  quote_class_fields=true,
  enum_value_prefix="",
  hoisted_class_prefix="  "
) }}
```

The rendering logic (`output_format/types.rs`, 3,580 lines) handles:
- Recursive type detection and forward references
- Class hoisting strategies (auto, all, subset)
- Enum hoisting
- Map style (inline vs verbose)
- Field quoting
- Union separator customization
- Prefix/suffix injection

---

## 4. Gap Analysis by Feature Area

### 4.1 Type-Aware SAP Coercion (HIGH PRIORITY)

#### What BAML Has

BAML's parser uses the target schema (`TypeIR`) to drive coercion via a `TypeCoercer` trait. The key capabilities:

| BAML Coercion Capability | Current SAP? | BAML File |
|---|---|---|
| String → Int/Float/Bool coercion (`"42"` → `42`, `"true"` → `true`) | ❌ Only numeric-string via regex | `coerce_primitive.rs` |
| Enum variant fuzzy matching (case-insensitive, punctuation-stripped, substring) | ❌ Not at all | `match_string.rs` (424 lines) |
| Union variant selection (try each variant, score, pick best) | ❌ Not at all | `coerce_union.rs` (177 lines) |
| Object → Map coercion (when target is `map<string, T>`) | ❌ Not at all | `coerce_map.rs` (190 lines) |
| Object → String coercion (fallback when target is string) | ❌ Only whole-response-as-string | `coerce_primitive.rs` |
| Single value → Array coercion (`"foo"` → `["foo"]` when target is `T[]`) | ❌ Not at all | `coerce_array.rs` (155 lines) |
| Extra key handling (ignore unknown fields, flag them) | ❌ Smithy4s handles but no flags | `deserialize_flags.rs` |
| Implied key matching (fuzzy field name matching) | ❌ Not at all | `deserialize_flags.rs` |
| **Scoring system** — pick best parse among multiple candidates | ❌ First-success-wins | `score.rs` (95 lines) |
| **AnyOf** — multiple parse interpretations of same text | ❌ Not at all | `jsonish/value.rs` |

#### BAML's Fuzzy String Matching (4 Strategies)

From `match_string.rs`, BAML tries these in escalating order:

1. **Case-sensitive match** (ignoring punctuation) — exact match against candidate names and aliases
2. **Strip punctuation, case-sensitive** — remove punctuation from both input and candidates, retry
3. **Case-sensitive match without punctuation** (second pass with different logic)
4. **Case-insensitive match without punctuation** — last resort, could yield false positives

Each strategy adds escalating "flags" (penalties) to the score.

#### BAML's Union Variant Selection

From `coerce_union.rs`:
1. If value is `Null` and union is optional → return null
2. If there's a **variant hint** from a previous array element → try that first (optimization)
3. Try `try_cast` on each variant — short-circuit if perfect match (score 0)
4. Collect all successful casts, pick the one with lowest score
5. If no cast succeeds, try full `coerce` on each variant

#### Recommendation

**This is the single highest-impact improvement.** Transform the SAP from a "JSON syntax fixer" into a true schema-aligned parser.

**Concrete plan:**

1. **Introduce a `JsonishValue` ADT** (Scala sealed trait/enum):
   ```scala
   enum JsonishValue:
     case Str(value: String, state: CompletionState)
     case Num(value: BigDecimal, state: CompletionState)
     case Bool(value: Boolean)
     case Null
     case Obj(fields: Vector[(String, JsonishValue)], state: CompletionState)
     case Arr(items: Vector[JsonishValue], state: CompletionState)
     case Markdown(raw: String, inner: JsonishValue, state: CompletionState)
     case FixedJson(inner: JsonishValue, fixes: Vector[Fix])
     case AnyOf(choices: Vector[JsonishValue], original: String)
   ```
   This replaces the current "parse to String → hand to smithy4s" flow.

2. **Implement a `TypeCoercer[A]` typeclass**:
   ```scala
   trait TypeCoercer[A]:
     def coerce(value: Option[JsonishValue], ctx: ParsingContext): Either[ParsingError, A]
     def tryCast(value: Option[JsonishValue], ctx: ParsingContext): Option[A]
   ```
   Derive instances from smithy4s schemas (which already encode the structure).

3. **Implement scoring** — port BAML's `WithScore` trait:
   ```scala
   trait WithScore:
     def score: Int  // Lower is better
   ```

4. **Implement enum fuzzy matching** — port `match_string.rs` logic with 4 escalating strategies.

5. **Implement union variant selection** — port `coerce_union.rs` logic with variant hints.

**Effort:** Large (this is essentially rewriting the parser core).
**Impact:** Very high — this is BAML's core innovation.

---

### 4.2 Semantic Streaming with Completion State (HIGH PRIORITY)

#### What BAML Has

BAML tracks completion state on every value node during streaming and provides field-level streaming control via `@stream` annotations:

| Annotation | Behavior |
|---|---|
| `@stream.done` | Field only appears when fully complete (numbers aren't streamed as `1` → `12` → `129.9`) |
| `@stream.not_null` | Containing object won't stream until this field has a non-null value |
| `@stream.with_state` | Wraps value in `{value: T, streaming_state: "Incomplete" \| "Complete"}` |

The `semantic_streaming.rs` module (493 lines) implements `validate_streaming_state` which:
- Walks the value tree comparing completion state against streaming behavior
- Deletes incomplete `@stream.done` fields (replaces with null)
- Validates `@stream.not_null` fields are present
- Sorts fields to match class definition order
- Handles recursive type aliases
- Handles union types (determines if union requires "done" based on variants)

#### What `structured-llm` Has

The current `streamWithResult` is **token-level only**. It:
- Streams raw text tokens via a callback
- Accumulates chunks in a `ListBuffer`
- Parses the **complete** response only at the end
- Returns `(Stream[F, String], F[A])` — token stream + final result

There is:
- No partial/intermediate structured result streaming
- No completion state tracking
- No `@stream.done` / `@stream.not_null` / `@stream.with_state` equivalents
- No "partial types" (all fields nullable during streaming)

#### Recommendation

**Add semantic streaming to `structured-llm`:**

1. **`CompletionState` enum**:
   ```scala
   enum CompletionState:
     case Pending, Incomplete, Complete
   ```

2. **`StreamingBehavior` config** per field:
   ```scala
   final case class StreamingBehavior(done: Boolean, needed: Boolean, withState: Boolean)
   object StreamingBehavior:
     val default: StreamingBehavior = StreamingBehavior(done = false, needed = false, withState = false)
   ```

3. **`Schema` extension** — add streaming metadata to `SchemaData`:
   ```scala
   final case class SchemaData[A](
     smithyDefinition: String,
     description: Option[String],
     smithySchema: SmithySchema[A],
     streamingBehavior: StreamingBehavior = StreamingBehavior.default
   )
   ```

4. **Partial parsing during stream** — re-parse the accumulated text on each chunk (throttled, like BAML's 50ms loop) and emit partial `A` values with `CompletionState` metadata.

5. **`StreamState[A]` wrapper** for `@stream.with_state`:
   ```scala
   final case class StreamState[A](value: Option[A], state: CompletionState)
   ```

6. **New API method**:
   ```scala
   def streamPartial[A: Schema](prompt: Prompt): Stream[F, StreamState[A]]
   ```

**Effort:** Medium-large.
**Impact:** High — enables real-time UI updates with partial structured data.

> **llm4s overlap:** `llm4s` already provides `StreamingAccumulator` (content + thinking + tool-call + token-usage accumulation, see <ref_file file="/home/gruggiero/git/llm4s/llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/streaming/StreamingAccumulator.scala" />) and a rich `AgentEvent` ADT (`TextDelta`, `TextComplete`, `ToolCallStarted/Completed/Failed`, `StepStarted/Completed`, `AgentStarted/Completed/Failed`, `HandoffStarted/Completed`, guardrail events — see <ref_file file="/home/gruggiero/git/llm4s/llm4s/modules/core/src/main/scala/org/llm4s/agent/streaming/AgentEvent.scala" />). `adk4s-core` already bridges llm4s callback streaming to `fs2.Stream` via `StreamingLLMClient` and `ChunkAccumulator`. The gap is therefore **not** "no streaming infrastructure" but "no *semantic* streaming of structured values" — i.e., no `CompletionState`, no `@stream.done`/`not_null`/`with_state`, no partial-type emission. The work is to add a `SemanticStreamingAccumulator` on top of the existing `StreamingAccumulator` that re-parses accumulated text into partial `JsonishValue`/`A` and applies streaming-behavior filters.

---

### 4.3 Constraints: `@check` and `@assert` Validation (HIGH PRIORITY)

#### What BAML Has

BAML supports field-level and class-level validation constraints:

```baml
class Student {
  age int @check(old_enough, {{ this > 5 }})
  concentration string @assert({{ this.regex_match("[Math|Science]") }})
  @@check(age_threshold, {{ this.concentration != "calculus" or this.age > 12 }})
}
```

- `@check` — non-failing validation, results collected as `ResponseCheck` for inspection
- `@assert` — strict validation, raises `BamlValidationError` on failure
- Constraints use Jinja expressions evaluated against the parsed value
- `this` refers to the field value (or whole object for `@@check`/`@@assert`)

BAML's constraint types:
```rust
pub struct Constraint {
    pub level: ConstraintLevel,  // Check or Assert
    pub expression: JinjaExpression,
    pub label: Option<String>,
}

pub struct ResponseCheck {
    pub name: String,
    pub expression: String,
    pub status: String,  // "succeeded" or "failed"
}
```

#### What `structured-llm` Has

**Nothing.** No post-parse validation at all. The parser either succeeds or fails; there's no way to express "the `age` field must be > 5" or "if concentration is calculus, age must be > 12".

#### Recommendation

**Add a validation layer to `structured-llm`:**

1. **`Constraint[A]` type**:
   ```scala
   enum ConstraintLevel:
     case Check, Assert

   final case class Constraint[A](
     label: String,
     level: ConstraintLevel,
     predicate: A => Boolean
   )
   ```

2. **`Schema` extension** — attach constraints to schemas:
   ```scala
   extension [A](schema: Schema[A]):
     def withCheck(label: String)(predicate: A => Boolean): Schema[A]
     def withAssert(label: String)(predicate: A => Boolean): Schema[A]
   ```

3. **`StructuredLLM` validation** — after parsing, evaluate constraints:
   - `Check` failures → collect into `ValidationResult.warnings`
   - `Assert` failures → raise `StructuredLLMError.ValidationFailed`

4. **Result type enrichment**:
   ```scala
   final case class ResponseCheck(name: String, expression: String, status: CheckStatus)
   enum CheckStatus:
     case Succeeded, Failed

   final case class ValidationResult[A](
     value: A,
     checks: Vector[ResponseCheck],
     failedAsserts: Vector[String]
   )
   ```

5. **New API method**:
   ```scala
   def completeValidated[A: Schema](prompt: Prompt): F[ValidationResult[A]]
   ```

**Note:** BAML uses Jinja expressions for constraints. In Scala, we can use plain functions (`A => Boolean`), which is more type-safe and doesn't require a template engine. This is actually **better** than BAML's approach.

> **llm4s overlap:** `llm4s` already ships a `Guardrail[A]` trait with `InputGuardrail` / `OutputGuardrail` subtypes and `CompositeGuardrail` composition (see <ref_file file="/home/gruggiero/git/llm4s/llm4s/modules/core/src/main/scala/org/llm4s/agent/guardrails/Guardrail.scala" />), plus built-in validators (`LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator`, `ToneValidator`) and LLM-as-judge guardrails. The gap for `structured-llm` is therefore **not** "no validation exists" but "no validation is *wired into the structured-output parse path*." The work is: (1) attach `Constraint[A]` metadata to `Schema[A]`, (2) evaluate constraints after SAP parsing, (3) optionally bridge to llm4s `OutputGuardrail` for the `String`-level checks. The llm4s guardrail framework can be reused rather than reinvented.

**Effort:** Small-medium.
**Impact:** High — validation is essential for production use.

---

### 4.4 Retry Policies with Exponential Backoff (MEDIUM PRIORITY)

#### What BAML Has

```baml
retry_policy MyPolicy {
  max_retries 3
  strategy {
    type exponential_backoff
    delay_ms 200
    multiplier 1.5
    max_delay_ms 10000
  }
}
```

Two strategies:
- `constant_delay` — fixed delay between retries
- `exponential_backoff` — exponential delay with multiplier and cap

BAML's implementation (`retry_policy.rs`, 61 lines):
```rust
pub struct CallablePolicy {
    max_retries: u32,
    strategy: RetryPolicyStrategy,
    current: Duration,
    counter: u32,
}

// Iterator that yields delays
impl Iterator for CallablePolicy {
    type Item = Duration;
    fn next(&mut self) -> Option<Duration> { ... }
}
```

#### What `structured-llm` Has

**No retry logic.** If the LLM call fails or parsing fails, it just raises an error. The `complete` method is a single attempt.

#### Recommendation

**Add retry support to `StructuredLLM`:**

1. **`RetryPolicy` config**:
   ```scala
   enum RetryStrategy:
     case ConstantDelay(delay: FiniteDuration)
     case ExponentialBackoff(initialDelay: FiniteDuration, multiplier: Double, maxDelay: FiniteDuration)

   final case class RetryPolicy(maxRetries: Int, strategy: RetryStrategy)
   ```

2. **Retry on both LLM errors AND parse failures** — BAML retries on validation/constraint failures too, which is valuable (the LLM might produce valid output on retry).

3. **Use Cats Effect** — `Temporal[F].sleep` + recursive retry, or `cats-retry` library.

4. **Factory method**:
   ```scala
   def fromClientWithRetry[F[_]: Async](
     client: LLMClient,
     policy: RetryPolicy,
     defaultOptions: CompletionOptions
   ): StructuredLLM[F]
   ```

5. **Configurable retry triggers**:
   ```scala
   enum RetryTrigger:
     case LLMError, ParseFailure, ValidationFailure, All
   ```

**Effort:** Small.
**Impact:** Medium — important for production reliability.

> **llm4s overlap:** `llm4s` already ships `RetryPolicy` and `ReliableClient` (see <ref_file file="/home/gruggiero/git/llm4s/llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/middleware/RetryPolicy.scala" />) with constant-delay and exponential-backoff strategies. The gap for `structured-llm` is narrower than it appears: the work is **wiring** `StructuredLLM` to retry on *parse failures* (not just LLM errors), since `llm4s`'s `ReliableClient` only retries on `LLMError`. A thin adapter that lifts `ParseFailed` into a retryable error would close most of this gap.

---

### 4.5 Client Strategies: Fallback and Round-Robin (MEDIUM PRIORITY)

#### What BAML Has

BAML supports client orchestration strategies:

**Fallback** (`strategy/fallback.rs`, 102 lines):
- Try clients in order
- If one fails, move to the next
- Each client can have its own retry policy

**Round-Robin** (`strategy/roundrobin.rs`, 135 lines):
- Distribute calls across clients for load balancing
- Atomic index counter for thread-safe rotation
- Optional random start index

#### What `structured-llm` Has

Single client only. `StructuredLLM.fromClient` takes one `LLMClient`.

#### Recommendation

**Add multi-client support:**

1. **`StructuredLLM.withFallback(clients: List[LLMClient])`** — tries clients in order
2. **`StructuredLLM.withRoundRobin(clients: List[LLMClient])`** — rotates through clients
3. These compose with retry policies (retry on primary, then fallback)

This could live in `structured-llm` or in `adk4s-core` (since it's a general LLM client concern). Given the project structure, `adk4s-core` is more appropriate since it already has `ChatModel`.

> **llm4s overlap:** `llm4s` has `LLMMiddleware` (composable wrappers via `MiddlewareClient`) and `ReliableClient`, but **no built-in fallback or round-robin strategy** — the docs describe only a manual fallback pattern (pattern-match on `Result` and try the next client). So this gap is real: BAML's `FallbackStrategy` and `RoundRobinStrategy` have no llm4s equivalent. The natural home is a new `llm4s` middleware (`FallbackMiddleware`, `RoundRobinMiddleware`) or an `adk4s-core` `ChatModel` wrapper, since `ChatModel[F]` is already the abstraction `adk4s` uses for multi-provider composition.

**Effort:** Small-medium.
**Impact:** Medium.

---

### 4.6 Richer Output Format Rendering (MEDIUM PRIORITY)

#### What BAML Has

BAML's `ctx.output_format` is highly configurable with these options:

| Option | Description |
|---|---|
| `prefix` | Text before the schema |
| `always_hoist_enums` | Always show enum definitions at top |
| `union_separator` | How to separate union variants (default `" \| "`) |
| `hoist_classes` | `"auto"` \| `true` \| `false` \| list of class names |
| `map_style` | `"inline"` \| `"verbose"` |
| `quote_class_fields` | Quote field names in output |
| `enum_value_prefix` | Prefix for enum values |
| `hoisted_class_prefix` | Prefix for hoisted class definitions |

The rendering logic (`output_format/types.rs`, 3,580 lines) handles:
- Recursive type detection and forward references
- Class hoisting strategies (auto, all, subset)
- Enum hoisting
- Map style (inline vs verbose)
- Field quoting
- Union separator customization
- Prefix/suffix injection
- Dynamic types (via TypeBuilder overrides)

#### What `structured-llm` Has

A fixed `outputFormatBlock` with no configuration:
```scala
s"""Respond with JSON matching this schema:
   |```smithy
   |$sanitized
   |```
   |
   |Important:
   |- Respond ONLY with valid JSON, no additional text
   |- Use the exact field names shown
   |- Include all @required fields""".stripMargin
```

#### Recommendation

**Add configurable output format rendering:**

1. **`OutputFormatOptions` case class**:
   ```scala
   enum HoistStrategy:
     case Auto, All, None, Subset(classes: List[String])

   enum MapStyle:
     case Inline, Verbose

   final case class OutputFormatOptions(
     prefix: Option[String] = None,
     unionSeparator: String = " | ",
     hoistClasses: HoistStrategy = HoistStrategy.Auto,
     quoteClassFields: Boolean = false,
     enumValuePrefix: String = "",
     alwaysHoistEnums: Boolean = false,
     hoistedClassPrefix: String = "  "
   )
   ```

2. **`Schema.withOutputFormat(options: OutputFormatOptions)`** — render with options

3. **Recursive type handling** — the current Smithy IDL approach doesn't handle recursive structures well. BAML's renderer detects recursion and uses forward references. This requires the schema to know about all referenced types, not just the top-level one.

**Effort:** Medium.
**Impact:** Medium — better prompts lead to better LLM accuracy.

---

### 4.7 Partial Types for Streaming (MEDIUM PRIORITY)

#### What BAML Has

BAML auto-generates "partial types" where all fields are nullable, used during streaming:
```python
# Original
class ReceiptInfo {
  items Item[]
  total float
}

# Generated partial type
class PartialReceiptInfo(BaseModel):
    items: Optional[List[PartialItem]] = None
    total: Optional[float] = None
```

The streaming behavior transformation:

| BAML Type | Generated Type | Description |
|---|---|---|
| `T` | `Partial[T]?` | Default: nullable and partial |
| `T @stream.done` | `T?` | Nullable but always complete |
| `T @stream.not_null` | `Partial[T]` | Always present, may be partial |
| `T @stream.with_state` | `StreamState[T]` | With completion metadata |

#### What `structured-llm` Has

No partial type concept. Streaming only produces the final complete result.

#### Recommendation

**Add `Partial[A]` typeclass** that makes all fields optional:

1. **`Partial[A]`** — a typeclass that produces a "loosened" version of `A`:
   ```scala
   trait Partial[A]:
     type Repr
     def partialSchema: Schema[Repr]
     def fromPartial(partial: Repr): A  // fill defaults
   ```

2. This could be derived from smithy4s schemas by wrapping each field in `Option`.

3. **`streamPartial[A: Partial]`** — streams `Partial[A]` values as tokens arrive, then converts to `A` at the end.

**Effort:** Medium-large (requires schema transformation).
**Impact:** Medium — useful for streaming UIs.

---

### 4.8 Dynamic Type Builder (LOW-MEDIUM PRIORITY)

#### What BAML Has

BAML's `TypeBuilder` (`type_builder/mod.rs`, 1,217 lines) allows runtime schema modification:

```python
tb = TypeBuilder()
tb.User.add_field("new_field", tb.string())
tb.add_class("DynamicClass")
tb.add_enum("DynamicEnum", ["A", "B", "C"])
```

Features:
- `ClassBuilder` — add fields, set aliases, descriptions, skip fields
- `EnumBuilder` — add values, set aliases
- `withMeta` / `getMeta` — attach metadata to types
- `@@dynamic` class attribute — marks classes as extensible

#### What `structured-llm` Has

Schemas are static `given Schema[A]` instances. No runtime modification.

#### Recommendation

**Add a `SchemaBuilder` for dynamic schemas:**

```scala
class SchemaBuilder:
  def string(): Schema[String]
  def int(): Schema[Int]
  def float(): Schema[Double]
  def bool(): Schema[Boolean]
  def list[A](elem: Schema[A]): Schema[Vector[A]]
  def union[A](variants: Schema[?]*): Schema[A]
  def map[A](value: Schema[A]): Schema[Map[String, A]]
  def addClass(name: String, fields: (String, Schema[?])*): Schema[DynamicRecord]
  def addEnum(name: String, values: String*): Schema[DynamicEnum]
```

This would produce schemas backed by `ujson.Value` or a dynamic record type rather than compiled case classes.

**Effort:** Medium.
**Impact:** Low-medium — useful for runtime-configurable agents but not critical.

---

### 4.9 Prompt Optimization via GEPA (LOW PRIORITY — research feature)

#### What BAML Has

BAML has a full **prompt optimization** system (`optimize/`, 6,761 lines total) implementing the GEPA algorithm (BEP-005):

| Component | File | Lines | Purpose |
|---|---|---|---|
| Orchestrator | `orchestrator.rs` | 807 | Main optimization loop |
| Evaluator | `evaluator.rs` | 535 | Runs tests, collects scores |
| Pareto frontier | `pareto.rs` | 655 | Multi-objective optimization |
| Candidate | `candidate.rs` | 362 | Prompt/schema candidate generation |
| Applier | `applier.rs` | 948 | Applies candidate changes to runtime |
| Storage | `storage.rs` | 446 | Checkpoint persistence |
| Schema extractor | `schema_extractor.rs` | 584 | Extracts optimizable types from IR |
| GEPA runtime | `gepa_runtime.rs` | 449 | Loads/executes GEPA functions |
| TUI | `tui.rs` | 1,949 | Terminal UI for optimization |

The GEPA algorithm:
1. Initialize with current prompt/schema
2. Evaluate candidate on tests
3. Reflect on failures
4. Propose improvements
5. Update Pareto frontier
6. Iterate until budget exhausted

#### What `structured-llm` Has

Nothing.

#### Recommendation

**This is a large research feature.** Recommend deferring unless there's specific interest. If pursued, it would be a new module (`adk4s-optimize`) rather than an addition to `structured-llm`. The core idea — automatically improving prompts based on test outcomes — is valuable but requires a test framework first (see item 4.10).

**Effort:** Very large.
**Impact:** Unknown — research-grade.

---

### 4.10 Test Framework for Structured Outputs (LOW-MEDIUM PRIORITY)

#### What BAML Has

BAML has first-class test definitions:
```baml
test MyTest {
  functions [ExtractResume]
  args { text "John Doe is a software engineer..." }
  @@check(nonempty, {{ this|length > 0 }})
  @@assert({{ this.name == "John Doe" }})
}
```

With CLI execution (`baml-cli test`):
- `--parallel` — concurrency control (default: 10)
- `--include` / `--exclude` — pattern matching
- `--require-human-eval` — fail if human evaluation needed
- Exit codes: 0 (pass), 1 (fail), 2 (needs human eval), 3 (cancelled), 4 (no tests)

#### What `structured-llm` Has

Tests exist but they're standard munit tests, not a dedicated framework for evaluating LLM output quality.

#### Recommendation

**Add a lightweight evaluation framework:**

```scala
final case class StructuredTest[I, A](
  name: String,
  template: PromptTemplate[I],
  input: I,
  schema: Schema[A],
  checks: Vector[A => Boolean],
  asserts: Vector[A => Boolean]
)

final case class TestReport(
  results: Vector[TestResult],
  passed: Int,
  failed: Int,
  humanEvalRequired: Int
)

def runTests[F[_]: Async](
  tests: Vector[StructuredTest[?, ?]],
  llm: StructuredLLM[F]
): F[TestReport]
```

This would be useful for CI/CD evaluation of structured output quality and would be a prerequisite for GEPA optimization.

**Effort:** Small-medium.
**Impact:** Medium.

---

### 4.11 Error Enrichment with Fallback History (LOW PRIORITY)

#### What BAML Has

When using fallback/retry, BAML's errors include `detailed_message` with the **complete history** of all attempts:
```
Attempt 1 (OpenAI/gpt-4o): ParseFailed...
Attempt 2 (Anthropic/claude): ParseFailed...
Attempt 3 (OpenAI/gpt-4o-mini): Success
```

#### What `structured-llm` Has

Errors only contain the last failure:
```scala
case class ParseFailed(errors: List[ParseError], rawResponse: String) extends StructuredLLMError
```

#### Recommendation

If retry/fallback is added (items 4.4-4.5), enrich errors with attempt history:

```scala
case class AttemptRecord(
  client: String,
  error: StructuredLLMError,
  rawResponse: String,
  timestamp: Instant
)

case class AggregatedError(
  attempts: Vector[AttemptRecord],
  finalError: StructuredLLMError
) extends StructuredLLMError
```

**Effort:** Small (once retry exists).
**Impact:** Low-medium.

---

### 4.12 Unicode Quote Normalization (LOW PRIORITY — quick win)

#### What BAML Has

The jsonish parser normalizes Unicode quotes: `"` `"` `'` `'` → standard `"` `'`.

#### What `structured-llm` Has

The `fixQuotes` method only handles single → double quote conversion. It does **not** handle Unicode smart quotes.

#### Recommendation

**Quick win** — add Unicode quote normalization to `fixQuotes`:

```scala
// Smart double quotes: " " „ „
val unicodeDoubleQuotes: Regex = """[\u201C\u201D\u201E\u201F]""".r
// Smart single quotes: ' ' ‚ ‛
val unicodeSingleQuotes: Regex = """[\u2018\u2019\u201A\u201B]""".r

def normalizeUnicodeQuotes(text: String): String =
  unicodeDoubleQuotes.replaceAllIn(text, "\"")
  unicodeSingleQuotes.replaceAllIn(text, "'")
```

**Effort:** Trivial.
**Impact:** Low but real — LLMs (especially Claude) sometimes produce smart quotes.

---

### 4.13 llm4s Overlap Analysis — What Already Exists in the Dependency

A significant portion of the BAML feature gap is **already addressed by `llm4s`**, the library that `adk4s` (and `structured-llm` in particular) depends on. Failing to account for this leads to duplicated work and integration friction. This section catalogs the overlap and refines the recommendations.

#### 4.13.1 What llm4s Already Provides

| BAML Feature Area | llm4s Equivalent | Location | Reuse Potential |
|---|---|---|---|
| Retry policies (constant, exponential) | `RetryPolicy` + `ReliableClient` | `llmconnect/middleware/RetryPolicy.scala` | **High** — wrap, don't reinvent |
| Composable client behaviors | `LLMMiddleware` + `MiddlewareClient` | `llmconnect/middleware/` | **High** — add structured-llm middleware here |
| Logging with redaction | `LoggingMiddleware` + `ContentRedactor` | `llmconnect/middleware/LoggingMiddleware.scala` | **High** — already production-grade |
| Rate limiting (token bucket) | `RateLimitingMiddleware` | `llmconnect/middleware/RateLimitingMiddleware.scala` | **High** |
| Semantic response caching | `CachingLLMClient` | `llmconnect/` | **Medium** — cache key may need schema awareness |
| Streaming accumulation | `StreamingAccumulator` | `llmconnect/streaming/StreamingAccumulator.scala` | **High** — extend for semantic streaming |
| Agent event streaming | `AgentEvent` ADT (16 event types) | `agent/streaming/AgentEvent.scala` | **High** — emit structured-output events into this |
| Input/output guardrails | `Guardrail[A]`, `InputGuardrail`, `OutputGuardrail`, `CompositeGuardrail` | `agent/guardrails/` | **High** — wire into structured-output parse path |
| Built-in guardrails | `LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator`, `ToneValidator`, LLM-as-judge | `agent/guardrails/builtin/` | **High** |
| Context window management | `ContextManager` (4-step pipeline) | `context/ContextManager.scala` | **Medium** — for long structured-output prompts |
| File/multimedia extraction | `UniversalExtractor` (PDF, DOCX, images, audio, video) | `llmconnect/extractors/UniversalExtractor.scala` | **Low** — orthogonal to structured outputs |
| MCP tool integration | `MCPClient` trait | `mcp/MCPClient.scala` | **Low** — orthogonal |
| Multi-provider dispatch | `LLMConnect` (10 providers, config-driven) | `llmconnect/LLMConnect.scala` | **High** — already used by `StructuredLLM` |
| Reasoning effort control | `CompletionOptions.withReasoning(ReasoningEffort)` | `llmconnect/model/` | **Medium** — pass through to structured calls |

#### 4.13.2 What llm4s Does NOT Provide (Gaps That Remain Real)

| BAML Feature | llm4s Status | Action |
|---|---|---|
| Type-aware SAP coercion (`JsonishValue` + `TypeCoercer`) | ❌ Not present | **Build in `structured-llm/sap`** — this is the core differentiator |
| Semantic streaming with `CompletionState` | ❌ Not present (only token-level) | **Build in `structured-llm`** on top of `StreamingAccumulator` |
| `@check` / `@assert` schema-attached constraints | ❌ Guardrails exist but are not schema-attached | **Bridge**: attach `Constraint[A]` to `Schema[A]`, evaluate post-parse, optionally delegate to `OutputGuardrail` |
| Fallback / round-robin client strategies | ❌ Only manual pattern | **Build** as `llm4s` middleware or `adk4s-core` `ChatModel` wrapper |
| Configurable output format rendering | ❌ Not present | **Build in `structured-llm`** |
| Partial types for streaming | ❌ Not present | **Build in `structured-llm`** |
| Dynamic type builder | ❌ Not present | **Build in `structured-llm`** (low priority) |
| GEPA prompt optimization | ❌ Not present | **Defer** (research) |
| Test framework for structured outputs | ❌ Not present | **Build** (prerequisite for GEPA) |
| Error enrichment with attempt history | ❌ Not present | **Build** once retry/fallback exist |
| Unicode quote normalization | ❌ Not present | **Quick win** in `structured-llm/sap` |
| Scoring system for parse candidates | ❌ Not present | **Build** as part of type-aware SAP |

#### 4.13.3 Architecture Recommendation: Middleware, Not Monolith

The existence of `LLMMiddleware` in `llm4s` suggests a cleaner integration pattern than "add everything to `StructuredLLM`":

```
LLMClient (raw)
    │
    ▼ wrapped by
RetryMiddleware (llm4s: ReliableClient)        ← reuse for LLM errors
    │
    ▼ wrapped by
RateLimitingMiddleware (llm4s)                  ← reuse as-is
    │
    ▼ wrapped by
LoggingMiddleware (llm4s)                       ← reuse as-is
    │
    ▼ wrapped by
StructuredLLMMiddleware (new, structured-llm)   ← adds schema injection + SAP + constraints
    │  - inject Schema into prompt
    │  - parse response via type-aware SAP
    │  - evaluate @check/@assert constraints
    │  - retry on ParseFailure (configurable)
    ▼
F[ValidatedResult[A]]
```

This keeps `StructuredLLM` as a **thin orchestration layer** over reusable `llm4s` middleware, rather than reimplementing retry/logging/rate-limiting inside the structured-output module.

#### 4.13.4 Existing Duplication to Consolidate

The subagent analysis identified three areas where `adk4s` duplicates `llm4s` types. These are not BAML-gap items but are worth noting because they affect any implementation work:

1. **Message types** — `structured-llm` defines its own `Message(role: Role, content: String)` and `Role` enum, then converts to llm4s `SystemMessage`/`UserMessage`/etc. via `MessageConverter`. The conversion is **lossy** (drops `toolCallId`, drops `toolCalls` on `AssistantMessage`). Consider using llm4s message types directly and keeping `Prompt` as a convenience wrapper over `Conversation`.

2. **Tool abstractions** — `adk4s-core` has `Tool`/`InvokableTool`/`StreamableTool`/`ToolWrapper`/`StructuredToolFunction`/`StructuredToolCall`/`TypedTool` overlapping llm4s `ToolFunction`/`ToolRegistry`. `ToolWrapper` stores both `originalToolFunction` and `executable` because the reverse cast fails at runtime. This is acknowledged in `CLAUDE.md` as necessary but fragile.

3. **Error hierarchies** — `adk4s` has `AdkError` (15+ types), `StructuredLLMError`, `StructuredToolCallError`, `ToolSchemaError` wrapping llm4s `LLMError`/`ToolCallError`. The wrapping is consistent but adds a layer.

These duplications are **out of scope** for the BAML gap analysis but should be tracked separately as tech-debt items, since they complicate the implementation of the BAML-inspired features (e.g., retry-on-parse-failure must bridge two error hierarchies).

---

## 5. Priority Matrix

| # | Feature | Priority | Effort | Module | Dependencies | llm4s Reuse |
|---|---|---|---|---|---|---|
| 1 | Type-aware SAP coercion | 🔴 High | Large | `structured-llm/sap` | None | None — core innovation |
| 2 | Semantic streaming + completion state | 🔴 High | Med-Large | `structured-llm/core` | Builds on #1 | Extends `StreamingAccumulator` + `AgentEvent` |
| 3 | `@check`/`@assert` constraints | 🔴 High | Small | `structured-llm/core` | None | Bridges to `Guardrail`/`OutputGuardrail` |
| 4 | Retry policies | 🟡 Medium | Trivial | `structured-llm/core` | None | **Reuse** `RetryPolicy`/`ReliableClient` — add parse-failure retry adapter |
| 5 | Fallback/round-robin clients | 🟡 Medium | Small-Med | `adk4s-core` or `llm4s` | Builds on #4 | Reuse `LLMMiddleware` pattern; no existing strategy |
| 6 | Configurable output format | 🟡 Medium | Medium | `structured-llm/core` | Builds on #1 | None |
| 7 | Partial types for streaming | 🟡 Medium | Med-Large | `structured-llm/core` | Builds on #2 | None |
| 8 | Dynamic type builder | 🟢 Low-Med | Medium | `structured-llm/core` | None | None |
| 9 | GEPA prompt optimization | 🟢 Low | Very Large | New module | Requires #10 | None |
| 10 | Test framework | 🟢 Low-Med | Small-Med | `structured-llm-test` | None | None |
| 11 | Error enrichment | 🟢 Low | Small | `structured-llm/core` | Requires #4, #5 | None |
| 12 | Unicode quote normalization | 🟢 Low | Trivial | `structured-llm/sap` | None | None |

---

## 6. Recommended Implementation Order

### Phase 1: Quick Wins (no dependencies)

1. **#12** (Unicode quotes) — trivial quick win, do immediately
2. **#4** (Retry policies) — **trivial** given llm4s `RetryPolicy`/`ReliableClient` already exist; only need a parse-failure retry adapter
3. **#3** (Constraints) — small effort, high value; bridge to llm4s `Guardrail` rather than building from scratch

### Phase 2: Core Parser Rewrite

4. **#1** (Type-aware SAP) — the core differentiator, largest effort
   - Introduce `JsonishValue` ADT
   - Implement `TypeCoercer[A]` typeclass
   - Implement scoring system
   - Implement enum fuzzy matching
   - Implement union variant selection

### Phase 3: Streaming (builds on Phase 2)

5. **#2** (Semantic streaming) — builds on #1's `JsonishValue` with `CompletionState`; extend llm4s `StreamingAccumulator`
6. **#7** (Partial types) — builds on #2's streaming

### Phase 4: Polish (builds on Phases 1-3)

7. **#6** (Configurable output format) — builds on #1's schema awareness
8. **#5** (Fallback clients) — builds on #4's retry; implement as `llm4s` middleware or `adk4s-core` `ChatModel` wrapper
9. **#11** (Error enrichment) — builds on #4, #5

### Phase 5: Optional / Research

10. **#10** (Test framework) — useful for CI/CD
11. **#8** (Dynamic type builder) — useful for runtime-configurable agents
12. **#9** (GEPA optimization) — research-grade, requires #10

> **Key change from prior plan:** Retry (#4) moved from "small effort" to "trivial" because `llm4s` already provides `RetryPolicy`/`ReliableClient`. Constraints (#3) moved from "small-medium" to "small" because `llm4s` already provides the `Guardrail` framework. The middleware architecture (§4.13.3) should be adopted early so that retry/logging/rate-limiting are composed via `LLMMiddleware` rather than reimplemented inside `StructuredLLM`.

---

## 7. Appendix: File References

### `structured-llm` Files

| File | Purpose |
|---|---|
| `structured-llm/src/main/scala/org/adk4s/structured/core/Schema.scala` | Schema typeclass |
| `structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala` | Prompt, Message, PromptTemplate |
| `structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala` | Main API |
| `structured-llm/src/main/scala/org/adk4s/structured/sap/SchemaAlignedParser.scala` | Lenient JSON parser |
| `structured-llm/src/main/scala/org/adk4s/structured/template/PromptSyntax.scala` | String interpolators |

### BAML Files (Key References)

| File | Lines | Purpose |
|---|---|---|
| `engine/baml-lib/jsonish/src/jsonish/value.rs` | 331 | JsonishValue enum |
| `engine/baml-lib/jsonish/src/jsonish/parser/fixing_parser.rs` | 216 | Forgiving JSON parser |
| `engine/baml-lib/jsonish/src/jsonish/parser/markdown_parser.rs` | 350 | Markdown extraction |
| `engine/baml-lib/jsonish/src/jsonish/parser/multi_json_parser.rs` | 153 | Multiple JSON detection |
| `engine/baml-lib/jsonish/src/deserializer/coercer/mod.rs` | 340 | TypeCoercer trait |
| `engine/baml-lib/jsonish/src/deserializer/coercer/coerce_primitive.rs` | 570 | Primitive coercion |
| `engine/baml-lib/jsonish/src/deserializer/coercer/coerce_union.rs` | 177 | Union variant selection |
| `engine/baml-lib/jsonish/src/deserializer/coercer/coerce_array.rs` | 155 | Array coercion |
| `engine/baml-lib/jsonish/src/deserializer/coercer/coerce_map.rs` | 190 | Map coercion |
| `engine/baml-lib/jsonish/src/deserializer/coercer/coerce_literal.rs` | 147 | Literal type coercion |
| `engine/baml-lib/jsonish/src/deserializer/coercer/match_string.rs` | 424 | Fuzzy string/enum matching |
| `engine/baml-lib/jsonish/src/deserializer/coercer/field_type.rs` | 483 | Field type coercion |
| `engine/baml-lib/jsonish/src/deserializer/deserialize_flags.rs` | 322 | Flag tracking |
| `engine/baml-lib/jsonish/src/deserializer/score.rs` | 95 | Scoring system |
| `engine/baml-lib/jsonish/src/deserializer/semantic_streaming.rs` | 493 | Streaming state validation |
| `engine/baml-lib/baml-types/src/baml_value.rs` | 1567 | BamlValue, CompletionState |
| `engine/baml-lib/baml-types/src/constraint.rs` | 84 | Constraint types |
| `engine/baml-lib/baml-types/src/ir_type/type_meta.rs` | 132 | StreamingBehavior, TypeMeta |
| `engine/baml-lib/jinja-runtime/src/output_format/mod.rs` | 237 | Configurable output format |
| `engine/baml-lib/jinja-runtime/src/output_format/types.rs` | 3580 | Rendering logic |
| `engine/baml-runtime/src/internal/llm_client/retry_policy.rs` | 61 | Retry policy |
| `engine/baml-runtime/src/internal/llm_client/strategy/fallback.rs` | 102 | Fallback strategy |
| `engine/baml-runtime/src/internal/llm_client/strategy/roundrobin.rs` | 135 | Round-robin strategy |
| `engine/baml-runtime/src/type_builder/mod.rs` | 1217 | Dynamic type builder |
| `engine/baml-runtime/src/test_constraints.rs` | 467 | Constraint evaluation |
| `engine/baml-runtime/src/optimize/` | 6761 | GEPA prompt optimization |
| `engine/baml-runtime/src/internal/prompt_renderer/render_output_format.rs` | 897 | Schema → prompt rendering |

### llm4s Files (Overlap References)

| File | Purpose | Reuse For |
|---|---|---|
| `llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/middleware/RetryPolicy.scala` | Retry policy + `ReliableClient` | #4 (retry) |
| `llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/middleware/LLMMiddleware.scala` | Middleware trait + `MiddlewareClient` | Architecture (§4.13.3) |
| `llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/middleware/LoggingMiddleware.scala` | Logging with redaction | Reuse as-is |
| `llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/middleware/RateLimitingMiddleware.scala` | Token-bucket rate limiting | Reuse as-is |
| `llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/streaming/StreamingAccumulator.scala` | Chunk accumulation | #2 (semantic streaming) |
| `llm4s/modules/core/src/main/scala/org/llm4s/agent/streaming/AgentEvent.scala` | 16 event types for agent execution | #2 (emit structured-output events) |
| `llm4s/modules/core/src/main/scala/org/llm4s/agent/guardrails/Guardrail.scala` | Guardrail trait + composition | #3 (constraints) |
| `llm4s/modules/core/src/main/scala/org/llm4s/context/ContextManager.scala` | 4-step context compression pipeline | Long-prompt management |
| `llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/LLMConnect.scala` | Multi-provider client factory | Already used by `StructuredLLM` |
| `llm4s/modules/core/src/main/scala/org/llm4s/llmconnect/extractors/UniversalExtractor.scala` | File/multimedia extraction | Orthogonal |
| `llm4s/modules/core/src/main/scala/org/llm4s/mcp/MCPClient.scala` | MCP tool integration | Orthogonal |

---

*End of report*
