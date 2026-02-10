## Context

The adk4s-examples module currently has 33 examples demonstrating core framework capabilities (graphs, workflows, agents, components). Analysis shows:
- **10 examples** manually parse LLM text responses (string contains/split/regex)
- **5 examples** could benefit from structured outputs but currently use free-form text
- **3 examples** manually parse tool arguments using ujson
- **1 example** (ToolSchemaExample) shows schema inference but not complete StructuredToolCall execution

The structured-llm module provides `StructuredLLM[F]` for type-safe LLM outputs with Schema-Aligned Parsing (SAP). The adk4s-core module provides `StructuredToolCall[F]` for type-safe tool execution. These capabilities are not demonstrated in examples.

**Current State:**
- Users see manual parsing patterns in examples and replicate them
- No examples show Smithy schema integration with StructuredLLM
- No examples show StructuredToolCall typed execution patterns
- SAP error recovery capabilities are undocumented via examples

**Stakeholders:**
- Library users learning type-safe LLM interaction patterns
- Contributors extending examples or adding features
- Documentation maintainers

## Goals / Non-Goals

**Goals:**
1. Provide 15 StructuredLLM examples demonstrating type-safe response parsing with Smithy schemas
2. Provide 3 new StructuredToolCall examples showing typed tool argument/result handling
3. Enhance ToolSchemaExample to demonstrate complete StructuredToolCall execution lifecycle
4. Organize examples by pattern: classification, extraction, multi-agent, chain, workflow
5. Show progression from "excellent candidates" (clear parsing) to "good candidates" (adding structure)
6. Demonstrate SAP error recovery (malformed JSON, markdown fences, trailing commas)
7. Maintain existing examples unchanged (no breaking changes)
8. Update README and run-example.sh for discoverability

**Non-Goals:**
- Modifying existing examples beyond ToolSchemaExample enhancement (preserve user familiarity)
- Adding new library features (use existing StructuredLLM/StructuredToolCall APIs)
- Creating production-ready agent implementations (examples are educational)
- Performance optimization or benchmarking (focus on correctness and clarity)
- Integration tests with real LLM APIs (use MockChatModel for deterministic testing)

## Decisions

### D1: Directory Structure - Separate `structured/` Namespace

**Decision:** Create `adk4s-examples/src/main/scala/org/adk4s/examples/structured/{llm,toolcall}/`

**Rationale:**
- **Separation:** Clearly distinguishes new structured examples from existing manual-parsing examples
- **Discoverability:** Users can find all structured examples in one location
- **Non-invasive:** No changes to existing example file paths
- **Organization:** Mirrors the library architecture (structured-llm vs adk4s-core)

**Alternatives Considered:**
- **Alternative A:** Co-locate with existing examples (e.g., `eino/components/StructuredChatModelExample`)
  - **Rejected:** Would scatter structured examples across many directories, harder to find
- **Alternative B:** Create `examples-structured` separate module
  - **Rejected:** Unnecessary module complexity, increases build time

**Structure:**
```
adk4s-examples/src/main/scala/org/adk4s/examples/structured/
├── llm/
│   ├── classification/   (4 examples: category, role, query, route)
│   ├── extraction/       (4 examples: plan, steps, list, schema)
│   ├── multiagent/       (2 examples: host-routing, specialist-delegation)
│   ├── chain/            (3 examples: typed intermediates, composition)
│   └── workflow/         (2 examples: graph integration, async transforms)
├── toolcall/
│   ├── ReactAgentStructuredExample.scala
│   ├── DynamicToolRegistryStructuredExample.scala
│   └── WIOGraphToolStructuredExample.scala
└── schemas/
    └── examples.smithy   (Smithy definitions for all examples)
```

### D2: Schema Definition Strategy - Single Smithy File

**Decision:** Use one `examples.smithy` file with all example schemas in `org.adk4s.examples.structured.schemas` namespace

**Rationale:**
- **Simplicity:** Single file to compile, single import path for all examples
- **Smithy4s compilation:** Generates all schemas in one pass, faster builds
- **Colocation:** All example types visible together, easier to see patterns
- **Namespace isolation:** `structured.schemas` prevents conflicts with existing example types

**Alternatives Considered:**
- **Alternative A:** One .smithy file per example
  - **Rejected:** 19 files, slower compilation, harder to navigate
- **Alternative B:** Inline Schema[A] definitions without Smithy
  - **Rejected:** Doesn't demonstrate Smithy4s integration, loses code generation benefits

**Schema Naming Convention:**
```smithy
namespace org.adk4s.examples.structured.schemas

// Pattern: <ExampleName><DomainConcept>
structure CategoryClassification { ... }
structure PlanStep { ... }
structure PlanExtraction { ... }
structure ToolArguments { ... }
```

### D3: Example Naming Convention - Mirror Source with "Structured" Suffix

**Decision:** Name examples `<OriginalPattern>StructuredExample.scala`

**Rationale:**
- **Clarity:** Immediately identifies which existing pattern is being demonstrated with structured approach
- **Comparison:** Users can compare `MultiAgentHostExample` vs `MultiAgentHostStructuredExample`
- **Searchability:** Easy to find structured variant of a specific pattern

**Examples:**
- `MultiAgentHostExample` → `llm/multiagent/MultiAgentHostStructuredExample.scala`
- `PlanExecuteExample` → `llm/extraction/PlanExecuteStructuredExample.scala`
- `ReactAgentExample` → `toolcall/ReactAgentStructuredExample.scala`

**Alternatives Considered:**
- **Alternative A:** Generic names like `ClassificationExample1`, `ClassificationExample2`
  - **Rejected:** Loses connection to original pattern, harder to understand what's being demonstrated
- **Alternative B:** Keep original names in new directory
  - **Rejected:** Confusing when both exist, name collisions if ever merged

### D4: Mock vs Real LLM Strategy - Environment Variable Detection

**Decision:** Follow existing ExampleUtils.createChatModel pattern - use OPENAI_API_KEY environment variable to choose between MockChatModel and real LLM

**Rationale:**
- **Consistency:** Matches existing examples pattern (users already familiar)
- **Flexibility:** Developers can test with mock (fast, free) or real LLM (realistic)
- **Determinism:** Mock mode for CI/CD, real mode for validating LLM behavior
- **No barrier:** Examples runnable immediately without API keys

**Implementation:**
```scala
// Reuse ExampleUtils.createChatModel (already exists):
def createChatModel: IO[ChatModel[IO]] =
  val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)
  apiKey match
    case Some(key) =>
      val model: String = sys.env.getOrElse("LLM_MODEL", "gpt-4o-mini")
      IO.println(s"[Using real LLM: $model]") *> createRealClient(key, model)
    case None =>
      IO.println("[Using MockChatModel — set OPENAI_API_KEY for real LLM]") *>
        IO.pure(new StructuredMockChatModel())

// StructuredMockChatModel returns schema-compliant JSON:
class StructuredMockChatModel extends MockChatModel:
  override def generate(conv: Conversation): IO[Completion] =
    val response = if conv.systemPrompt.contains("classifier") then
      """{"category": "math", "confidence": 0.95}"""
    else if conv.systemPrompt.contains("planner") then
      """{"steps": [{"index": 1, "description": "Research topic"}]}"""
    else ...
```

**Environment Variables:**
- `OPENAI_API_KEY`: If set, use real LLM; if unset, use MockChatModel
- `LLM_MODEL`: Optional, defaults to "gpt-4o-mini"
- `OPENAI_BASE_URL`: Optional, defaults to "https://api.openai.com/v1"

**Alternatives Considered:**
- **Alternative A:** Always require real LLM via API key
  - **Rejected:** Barrier to running examples, non-deterministic, costs money
- **Alternative B:** Use recorded HTTP responses (VCR pattern)
  - **Rejected:** Adds complexity, harder to modify for testing edge cases
- **Alternative C:** Separate mock-only and real-only examples
  - **Rejected:** Duplicates code, increases maintenance burden

### D5: SAP Error Recovery Demonstration - Dedicated Examples

**Decision:** Include 2-3 examples specifically demonstrating SAP's recovery capabilities

**Rationale:**
- **Education:** Users need to understand SAP handles LLM errors (trailing commas, quotes, fences)
- **Confidence:** Shows robustness of structured approach vs manual parsing
- **Documentation:** Concrete examples of what SAP fixes

**Examples to Include:**
1. `SAPErrorRecoveryExample`: Shows SAP fixing multiple malformation types
2. Sprinkle malformed responses in 2-3 other examples with comments explaining recovery

**Malformation Patterns to Demonstrate:**
```scala
// Trailing comma
"""{"steps": [{"index": 1, "desc": "test"},]}"""

// Markdown fences
"""```json
{"category": "math"}
```"""

// Single quotes
"""{'name': 'test', 'value': 42}"""

// Missing closing brace
"""{"result": "incomplete"""
```

### D6: ToolSchemaExample Enhancement - Additive Only

**Decision:** Add "Scenario 4: StructuredToolCall Execution" section to existing ToolSchemaExample

**Rationale:**
- **Preservation:** Keeps existing scenarios 1-3 (schema inference, tool creation, JSON fix)
- **Cohesion:** Shows full lifecycle in one example: schema → tool → execution
- **Minimal invasiveness:** Only adds ~40 lines, doesn't change existing code

**New Scenario:**
```scala
// Scenario 4: StructuredToolCall Execution
for
  _ <- ExampleUtils.printSubSection("Scenario 4: Execute with StructuredToolCall")

  // Create StructuredToolCall from registry
  registry = new ToolRegistry(Seq(tool))
  structured = StructuredToolCall.fromRegistry[IO](registry)

  // Execute with typed input/output
  toolCall = ToolCall(id = "1", name = "book_trip", arguments = ujson.Obj(...))
  result <- structured.execute[BookingArgs, BookingResult](toolCall)

  _ <- IO.println(s"   Typed result: $result")
yield ()
```

**Alternatives Considered:**
- **Alternative A:** Create separate `ToolSchemaStructuredExample`
  - **Rejected:** Duplicates scenarios 1-3, increases maintenance burden
- **Alternative B:** Replace entire ToolSchemaExample
  - **Rejected:** Breaking change, users may rely on existing structure

### D7: README Organization - Dedicated "Structured Examples" Section

**Decision:** Add new top-level section "Structured Examples" with subsections for LLM and ToolCall

**Rationale:**
- **Visibility:** Users immediately see structured approach is available
- **Organization:** Groups by capability (LLM parsing vs tool execution)
- **Comparison:** Can reference original examples for before/after comparison

**Structure:**
```markdown
## Structured Examples

### Structured LLM (Type-Safe Response Parsing)

Examples using StructuredLLM for schema-validated LLM outputs:

#### Classification Patterns
- `CategoryClassificationStructuredExample` - Route queries by category
- `RoleDetectionStructuredExample` - Detect speaker role in conversations
...

### Structured ToolCall (Type-Safe Tool Execution)
...
```

## Risks / Trade-offs

### R1: Code Duplication Between Examples
**Risk:** 19 new examples may duplicate boilerplate (MockChatModel setup, common schemas)
**Mitigation:**
- Create `StructuredExampleUtils` with shared setup helpers
- Use Smithy schema inheritance for common patterns
- Accept some duplication for example independence (copy-paste friendly)

### R2: Smithy Compilation Time
**Risk:** Adding 19 example schemas increases smithy4s codegen time
**Mitigation:**
- Use single .smithy file for faster compilation
- Keep schemas simple (no complex unions/mixins)
- Smithy compilation cached by sbt, only regenerates on schema changes
**Measurement:** Expect <2s additional compilation time based on existing structured-llm schemas

### R3: MockChatModel Response Complexity
**Risk:** Maintaining 19 different mock responses becomes brittle
**Mitigation:**
- Use pattern matching on system prompts (simple string contains)
- Keep mock responses minimal (1-3 fields per schema)
- Document mock response patterns in StructuredMockChatModel
**Trade-off:** Accept simplified responses for determinism (real LLMs more verbose)

### R4: Schema Design Learning Curve
**Risk:** Users may not understand Smithy IDL syntax from examples alone
**Mitigation:**
- Add inline comments in examples.smithy explaining each structure
- Reference CLAUDE.md Smithy documentation in example docstrings
- Include "Schema Design Best Practices" section in README
**Trade-off:** Smithy is less familiar than JSON Schema, but more compact and type-safe

### R5: Example Maintenance Burden
**Risk:** 19 new examples increase maintenance when library APIs change
**Mitigation:**
- Follow existing example patterns (IOApp.Simple, printSection structure)
- Keep examples focused (1 concept per example, <150 lines)
- CI runs all examples to catch breakage
**Acceptance:** Value of comprehensive examples outweighs maintenance cost

### R6: StructuredToolCall API Coverage
**Risk:** StructuredToolCall trait may lack methods needed for examples
**Mitigation:**
- Reviewed existing StructuredToolCall.scala - has execute, executeRaw, function, extractor
- All identified use cases covered by current API
- If gaps found, document as "Future Enhancement" rather than blocking examples
**Current Assessment:** API sufficient for planned examples

## Migration Plan

Not applicable - this is additive-only (new examples, no deployment/rollback).

**User Adoption Path:**
1. Users discover new examples via README "Structured Examples" section
2. Compare structured vs manual parsing examples (e.g., MultiAgentHost → MultiAgentHostStructured)
3. Copy structured pattern into their codebase
4. Define Smithy schemas for their domain types
5. Replace manual parsing with StructuredLLM/StructuredToolCall

**Deprecation:** No existing examples deprecated (preserved for compatibility)

## API Gaps & Required Improvements

**Status: ✅ RESOLVED - All critical gaps have been implemented.**

### Gap 1: StructuredLLM Lacks Streaming Support ~~(BLOCKER)~~ ✅ IMPLEMENTED

**Problem:**
- Current StructuredLLM only had `complete[A](prompt): F[A]` (single response)
- No streaming equivalent for progressive token display with final parsed result
- Several existing examples use streaming (ChatModelExample, ReactAgentExample, AsyncNodeExample)
- Users expected structured examples to show streaming patterns

**Solution Implemented:**
Added hybrid streaming approach with two new methods:
```scala
trait StructuredLLM[F[_]]:
  // Stream raw tokens + get final parsed result
  def streamWithResult[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])]

  // Stream without schema injection (when prompt already has schema)
  def streamWithResultRaw[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])]
```

**Implementation Details:**
- Returns tuple of `(Stream[F, String], F[A])` where:
  - Stream emits tokens as they arrive for progressive UI display
  - F[A] provides final type-safe parsed result after stream completes
- Uses callback-based `client.streamComplete` to collect chunks
- Applies SAP parsing to accumulated full response
- Schema automatically injected via `prompt.withOutputFormat[A]`

**Files Modified:**
- `structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`

**Tests Added:**
- `StructuredLLMStreamingTest.scala` with 3 comprehensive tests:
  - Token emission and final result parsing
  - SAP error recovery with malformed JSON
  - Raw mode without schema injection

**Verification:** ✅ All tests pass (3/3)

---

### Gap 2: ToolSchema Lacks Automatic Derivation ~~(MODERATE)~~ ✅ IMPLEMENTED

**Problem:**
- ToolInfer had automatic schema derivation but ToolSchema required manual instances
- Users had to write encoder/decoder by hand for every tool output type
- Inconsistent DX: automatic for input, manual for output

**Solution Implemented:**
Added `ToolSchema.derive[A]` method with automatic encoder generation:
```scala
object ToolSchema:
  inline def derive[A <: Product](using m: Mirror.ProductOf[A]): ToolSchema[A] =
    val jsonSchema: Value = ToolInfer.deriveSchema[A]
    val decoder: Value => Either[ToolSchemaError, A] = (json: Value) =>
      ToolInfer.decodeProduct[A](json).left.map(err => ToolSchemaError.DecodingFailed(err, None))
    val encoder: A => Value = (a: A) => encodeProduct[A](a)
    ToolSchema.instance(jsonSchema, None)(decoder, encoder)
```

**Implementation Details:**
- Reuses ToolInfer's `deriveSchema[A]` for JSON schema generation
- Reuses ToolInfer's `decodeProduct[A]` for decoding logic
- Adds new `encodeProduct[A]` using inline Mirror-based field encoding
- Supports: String, Int, Long, Float, Double, Boolean, Option[T]

**Files Modified:**
- `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala`
  - Added `derive` method
  - Added encoder helpers: `encodeProduct`, `encodeFields`, `encodeField`

**Tests Added:**
- `ToolSchemaDeriveTest.scala` - 7 unit tests covering:
  - JSON schema generation
  - Encoding/decoding
  - All basic types
  - Optional fields
  - Round-trip preservation
  - Error handling
- `StructuredToolCallDeriveTest.scala` - 3 integration tests:
  - Tool argument parsing
  - Tool execution with decoded args
  - Result decoding with derived schema

**Usage:**
```scala
case class BookingResult(confirmation: String, price: Double)
given ToolSchema[BookingResult] = ToolSchema.derive[BookingResult]  // One line!

// In example:
val result: IO[BookingResult] = structured.execute[BookingArgs, BookingResult](toolCall)
```

**Verification:** ✅ All tests pass (10/10 - 7 unit + 3 integration)

---

### Gap 3: Convenience API for Fully-Typed Tools (MINOR)

**Problem:**
- Current workflow: ToolInfer → Registry → StructuredToolCall.fromRegistry → execute[I, O]
- Requires understanding 3 separate APIs for one goal (typed tool)
- Not obvious how to combine ToolInfer + StructuredToolCall

**Current State:**
```scala
// Step 1: Create tool with ToolInfer
val tool = ToolInfer.infer[Args]("name", "desc") { args => IO.pure(Right(ujson.Obj(...))) }

// Step 2: Add to registry
val registry = new ToolRegistry(Seq(tool))

// Step 3: Wrap with StructuredToolCall
val structured = StructuredToolCall.fromRegistry[IO](registry)

// Step 4: Execute (need ToolSchema[Result])
given ToolSchema[Result] = ...
val result = structured.execute[Args, Result](toolCall)
```

**Proposed Solution:**
```scala
object StructuredToolCall:
  // NEW: Create single typed tool directly
  def createTool[F[_], I <: Product, O <: Product](
    name: String,
    description: String
  )(
    impl: I => F[O]
  )(using
    Mirror.ProductOf[I],
    Mirror.ProductOf[O],
    ToolSchema[O]  // Auto-derivable with Gap 2 solution
  ): TypedTool[F, I, O]

trait TypedTool[F[_], I, O]:
  def name: String
  def execute(args: I): F[O]
  def asInvokableTool: InvokableTool[F]  // For registry compatibility
```

**Usage:**
```scala
val bookTool = StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
  "book_trip",
  "Book a trip"
) { args =>
  IO.pure(BookingResult(s"Confirmed: ${args.dest}", args.passengers * 200.0))
}

// Can still use with registry:
val registry = new ToolRegistry(Seq(bookTool.asInvokableTool))
```

**Recommendation:** Add convenience API but NOT a blocker - current workflow functional, just verbose

---

## Implementation Readiness

**Status: ✅ READY TO PROCEED**

**Completed Prerequisites:**
1. ✅ **Gap 1 (StructuredLLM streaming)** - IMPLEMENTED
   - Added `streamWithResult` and `streamWithResultRaw` methods
   - All tests pass (3/3)
   - Enables AsyncNodeStructuredExample and streaming demonstrations
2. ✅ **Gap 2 (ToolSchema.derive)** - IMPLEMENTED
   - Added automatic derivation with encoder generation
   - All tests pass (10/10)
   - Eliminates boilerplate for StructuredToolCall examples
3. ⏸️ **Gap 3 (TypedTool convenience API)** - DEFERRED (optional)
   - Not blocking - current workflow functional
   - Can be added in future enhancement if user feedback indicates need

**API Stability:**
- StructuredLLM API complete and tested
- ToolSchema API complete and tested
- StructuredToolCall API sufficient (execute, executeRaw, function, extractor)

**Next Steps:**
1. Continue OpenSpec workflow to create specs.md artifact
2. Create tasks.md with implementation checklist for 19 examples
3. Begin implementation with example creation

**No blockers remain for example implementation.**

## Open Questions

None - design complete pending API improvement decisions above.
