# Structured LLM: A BAML-Inspired Wrapper for llm4s

## Overview

This design proposes a type-safe, composable wrapper around [llm4s](https://github.com/llm4s/llm4s) that enforces structured outputs using Smithy schemas. Inspired by [BAML](https://docs.boundaryml.com/guide/introduction/why-baml), it provides:

1. **Type-safe structured outputs** - LLM responses are parsed into Scala case classes
2. **Smithy-based schema definitions** - Output types defined in Smithy, with the schema injected into prompts
3. **Schema-Aligned Parsing (SAP)** - Lenient JSON parsing that recovers from common LLM output errors
4. **Composable prompt templates** - Using Scala 3 custom string interpolators
5. **Cats Effect integration** - `Prompt => IO[A]` signature for composition

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      User Code                                   │
│  val result: IO[Resume] = structuredLLM.complete[Resume](prompt)│
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    StructuredLLM[F[_]]                          │
│  - Injects Smithy schema description into prompt                │
│  - Calls underlying LLMClient                                    │
│  - Parses response with SAP                                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    llm4s LLMClient                               │
│  - Handles provider-specific API calls                           │
│  - OpenAI, Anthropic, Azure, etc.                                │
└─────────────────────────────────────────────────────────────────┘
```

## Core Design Decisions

### 1. Schema Definition: Smithy

Smithy provides rich schema definitions with documentation traits. The output format injected into prompts will be the Smithy IDL itself (compact, human-readable, ~80% fewer tokens than JSON Schema).

```smithy
structure Resume {
  @required
  name: String
  
  @required
  skills: SkillList
  
  education: EducationList
  
  seniority: SeniorityLevel
}

@length(min: 1)
list SkillList {
  member: String
}

enum SeniorityLevel {
  @documentation("0-2 years of experience")
  JUNIOR
  
  @documentation("2-5 years of experience")
  MID
  
  @documentation("5-10 years of experience")  
  SENIOR
  
  @documentation("10+ years, technical leadership")
  STAFF
}
```

### 2. Prompt Templates: Custom String Interpolators

Following our previous discussion, we use Scala 3 custom string interpolators with structured sections:

```scala
val extractResume: PromptTemplate[ResumeInput] = 
  prompt"""
  |<system>
  |You are an expert resume parser. Extract structured information.
  |</system>
  |
  |<user>
  |Parse this resume:
  |---
  |${_.resumeText}
  |---
  |
  |${outputFormat[Resume]}
  |</user>
  """
```

### 3. SAP: Lenient JSON Parsing

The parser attempts recovery from common LLM errors:
- Markdown code fences (```json ... ```)
- Trailing commas
- Single quotes instead of double quotes
- Missing quotes on keys
- Comments in JSON
- Incomplete structures (best-effort partial parsing)

---

## Detailed Implementation

### Module Structure

```
structured-llm/
├── src/main/scala/org/llm4s/structured/
│   ├── package.scala                    # Clean API exports
│   ├── core/
│   │   ├── Schema.scala                 # Schema typeclass + ParseResult
│   │   ├── Prompt.scala                 # Prompt, Message, PromptTemplate
│   │   └── StructuredLLM.scala          # Main wrapper
│   ├── sap/
│   │   └── SchemaAlignedParser.scala    # Lenient JSON parser
│   ├── template/
│   │   └── PromptSyntax.scala           # Custom string interpolators
│   ├── smithy/
│   │   └── SmithySchemaDerivation.scala # Smithy4s integration
│   └── example/
│       └── StructuredLLMExample.scala   # Usage examples
└── build.sbt
```

---

## Complete API Reference

### Core Types

```scala
// The main abstraction: Prompt => F[A]
trait StructuredLLM[F[_]]:
  def complete[A: Schema](prompt: Prompt): F[A]
  def completeRaw[A: Schema](prompt: Prompt): F[A]
  def function[I, A: Schema](template: PromptTemplate[I]): I => F[A]
  def extractor[A: Schema](systemPrompt: String): String => F[A]

// Schema typeclass bridges Smithy to LLM output format
trait Schema[A]:
  def smithyDefinition: String    // Smithy IDL injected into prompts
  def decoder: Decoder[A]         // Circe decoder for JSON parsing
  def outputFormatBlock: String   // Complete prompt block with instructions

// Immutable prompt representation
case class Prompt(messages: Vector[Message]):
  def withOutputFormat[A: Schema]: Prompt
  def ++(other: Prompt): Prompt

// Template that takes input I to produce a Prompt
trait PromptTemplate[-I]:
  def render(input: I): Prompt
  def expecting[A: Schema]: PromptTemplate[I]
  def contramap[I2](f: I2 => I): PromptTemplate[I2]
```

### Template DSL

```scala
import org.llm4s.structured.template.syntax.*

// Simple interpolation
val prompt = prompt"""
  |<s>System message</s>
  |<u>User message with $variable</u>
"""

// Template with input type
val template = promptT[MyInput]"""
  |<s>Parse this: ${_.text}</s>
"""

// Output format injection
val withSchema = prompt.withOutputFormat[MyType]

// Builder DSL
import org.llm4s.structured.template.dsl.*
val built = systemMessage("...")
  .user("...")
  .outputFormat[MyType]
  .build
```

### Smithy Integration

```scala
import org.llm4s.structured.smithy.SmithySchema

// Manual definition (full control)
given Schema[Resume] = SmithySchema.manual(
  """structure Resume {
    |  @required
    |  name: String
    |  skills: StringList
    |}""".stripMargin
)

// Auto-derive from smithy4s types
given Schema[Resume] = SmithySchema.derived[Resume]
```

### SAP (Schema-Aligned Parser)

The parser handles:
- Markdown code fences (```json...```)
- Trailing commas
- Single quotes → double quotes
- Unquoted keys
- JSON comments
- Truncated responses (best-effort recovery)

```scala
import org.llm4s.structured.sap.SchemaAlignedParser

val result: ParseResult[MyType] = SchemaAlignedParser.parse[MyType](llmResponse)
result match
  case ParseResult.Success(value, warnings) => // Use value
  case ParseResult.Failure(errors) => // Handle errors
```

---

## Usage Example

```scala
import cats.effect.{IO, IOApp}
import org.llm4s.structured.*
import org.llm4s.structured.template.syntax.*
import io.circe.generic.auto.*

object ResumeParser extends IOApp.Simple:

  // 1. Define output type with Smithy schema
  case class Resume(name: String, skills: List[String], seniority: String)
  
  given Schema[Resume] = SmithySchema.manual(
    """structure Resume {
      |  @required name: String
      |  @required skills: StringList  
      |  @required seniority: SeniorityLevel
      |}
      |
      |enum SeniorityLevel {
      |  @documentation("0-2 years") JUNIOR
      |  @documentation("2-5 years") MID
      |  @documentation("5-10 years") SENIOR
      |  @documentation("10+ years") STAFF
      |}""".stripMargin
  )
  
  // 2. Create prompt template
  val parseResumeTemplate = promptT[String]"""
    |<s>You are an expert resume parser. Extract structured data.</s>
    |<u>Parse this resume: ${identity}</u>
  """
  
  // 3. Use the structured LLM
  def run: IO[Unit] =
    for
      // Create client (using llm4s)
      llmClient <- IO(???) // LLM.client(...)
      structured = StructuredLLM.fromClient[IO](llmClient)
      
      // Create reusable extractor function
      parseResume = structured.function[String, Resume](
        parseResumeTemplate.expecting[Resume]
      )
      
      // Use it
      resume <- parseResume("John Doe, 5 years Python, MIT 2019")
      _ <- IO.println(s"Extracted: $resume")
    yield ()
```

---

## Design Decisions

### Why Smithy IDL in Prompts?

1. **Token Efficiency**: Smithy is ~80% more compact than JSON Schema
2. **Human Readable**: LLMs understand it better than JSON Schema
3. **Rich Metadata**: @documentation traits guide enum/field understanding
4. **Type Safety**: Smithy4s provides compile-time validation

### Why Lenient Parsing (SAP)?

1. **Postel's Law**: Be liberal in what you accept
2. **LLM Reality**: Models often produce slightly malformed JSON
3. **Better UX**: Fewer retries needed
4. **Recoverable**: Most errors are fixable automatically

### Why `Prompt => IO[A]`?

1. **Composability**: Chains naturally with for-comprehensions
2. **Effect Safety**: IO ensures referential transparency
3. **Error Handling**: MonadError provides clean error recovery
4. **Testability**: Pure functions are easy to test

---

## Future Enhancements

1. **Streaming Support**: Add `streamComplete[A]` for token-by-token structured parsing
2. **Validation**: Add @checks and @asserts like BAML
3. **Caching**: LLM response caching based on prompt hash
4. **Metrics**: Token usage tracking and cost estimation
5. **Multi-Model Fallback**: Automatic retry with different providers
