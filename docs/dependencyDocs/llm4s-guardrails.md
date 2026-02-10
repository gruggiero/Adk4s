# LLM4S Guardrails System

## Overview

LLM4S provides a comprehensive guardrails system in `org.llm4s.agent.guardrails`. Guardrails are pure functions that validate inputs before agent processing and outputs before returning results. The system includes built-in detectors for PII, prompt injection, profanity, and content quality, plus RAG-specific guardrails for grounding, context relevance, and source attribution.

**Key Features**:
- Pure function guardrails (`validate: A => Result[A]`)
- Input guardrails (pre-LLM) and output guardrails (post-LLM)
- Composable with `andThen`
- Three action modes: Block, Fix, Warn
- Built-in detectors: PII, prompt injection, profanity, length, tone, regex
- LLM-as-judge guardrails: factuality, safety, quality, tone
- RAG-specific: grounding, context relevance, source attribution, topic boundary
- Preset configurations for common use cases

---

## Core Traits

### Guardrail[A]

```scala
trait Guardrail[A] {
  def validate(value: A): Result[A]
  def name: String
  def description: Option[String] = None
  def andThen(other: Guardrail[A]): Guardrail[A]
}
```

### InputGuardrail

```scala
trait InputGuardrail extends Guardrail[String] {
  def transform(input: String): String = input  // Optional post-validation transform
}
```

Runs **before** the LLM is called. Validates user queries, system prompts, tool arguments.

### OutputGuardrail

```scala
trait OutputGuardrail extends Guardrail[String] {
  def transform(output: String): String = output  // Optional post-validation transform
}
```

Runs **after** the LLM responds. Validates assistant messages, tool results, final responses.

---

## GuardrailAction

Controls what happens when a violation is detected.

```scala
sealed trait GuardrailAction

object GuardrailAction {
  case object Block extends GuardrailAction   // Stop processing, return error
  case object Fix extends GuardrailAction     // Auto-remediate and continue (falls back to Block on failure)
  case object Warn extends GuardrailAction    // Log warning, allow processing to continue

  val default: GuardrailAction = Block
}
```

**Usage**:
```scala
// Security-critical: block on prompt injection
val injectionGuard = PromptInjectionDetector(onFail = GuardrailAction.Block)

// Privacy: auto-mask PII
val piiGuard = PIIDetector(onFail = GuardrailAction.Fix)

// Monitoring: warn on length issues
val lengthGuard = LengthCheck(1, 10000, onFail = GuardrailAction.Warn)
```

---

## GuardrailResult[A]

```scala
sealed trait GuardrailResult[+A]

object GuardrailResult {
  case class Passed[A](value: A) extends GuardrailResult[A]
  case class Fixed[A](original: A, fixed: A, violations: Seq[String]) extends GuardrailResult[A]
  case class Warned[A](value: A, violations: Seq[String]) extends GuardrailResult[A]
  case class Blocked(violations: Seq[String]) extends GuardrailResult[Nothing]
}

// Extension methods
result.toOption: Option[A]
result.getOrElse(default): A
result.isSuccess: Boolean
result.isBlocked: Boolean
result.hasWarnings: Boolean
```

---

## Built-in Guardrails

### Pattern-Based (No LLM Calls)

| Guardrail | Type | Description |
|---|---|---|
| `PIIDetector` | Input | Detects PII patterns (SSN, email, phone, credit card) |
| `PIIMasker` | Output | Masks detected PII in output |
| `PromptInjectionDetector` | Input | Detects prompt injection attempts |
| `ProfanityFilter` | Input/Output | Filters profane content |
| `LengthCheck` | Input/Output | Validates min/max content length |
| `RegexValidator` | Input/Output | Custom regex-based validation |

### LLM-as-Judge (Requires LLMClient)

| Guardrail | Type | Description |
|---|---|---|
| `LLMFactualityGuardrail` | Output | Checks factual accuracy |
| `LLMSafetyGuardrail` | Output | Checks for harmful content |
| `LLMQualityGuardrail` | Output | Checks response quality |
| `LLMToneGuardrail` | Output | Validates tone appropriateness |
| `ToneValidator` | Output | Pattern-based tone validation |
| `JSONValidator` | Output | Validates JSON structure |

### RAG-Specific Guardrails

| Guardrail | Description |
|---|---|
| `GroundingGuardrail` | Ensures response is grounded in retrieved context |
| `ContextRelevanceGuardrail` | Validates that retrieved context is relevant to the query |
| `SourceAttributionGuardrail` | Ensures proper source attribution in responses |
| `TopicBoundaryGuardrail` | Keeps queries within allowed topic boundaries |

---

## RAG Guardrail Presets

The `RAGGuardrails` object provides preset configurations for common use cases.

### GuardrailConfig

```scala
final case class GuardrailConfig(
  inputGuardrails: Seq[InputGuardrail],
  outputGuardrails: Seq[OutputGuardrail],
  ragGuardrails: Seq[RAGGuardrail]
)
```

### Preset Levels

```scala
// Minimal: PII + prompt injection only (no LLM calls, low latency)
RAGGuardrails.minimal: GuardrailConfig

// Standard: Balanced protection for production
RAGGuardrails.standard(llmClient): GuardrailConfig

// Standard + topic restrictions
RAGGuardrails.standardWithTopics(llmClient, allowedTopics): GuardrailConfig

// Strict: Maximum safety (higher latency, multiple LLM calls)
RAGGuardrails.strict(llmClient, allowedTopics): GuardrailConfig

// Monitoring: Full validation in warn mode (never blocks)
RAGGuardrails.monitoring(llmClient): GuardrailConfig
```

### Domain-Specific Presets

```scala
// Customer support
RAGGuardrails.customerSupport(llmClient, productTopics): GuardrailConfig

// Software documentation
RAGGuardrails.softwareDocumentation(llmClient): GuardrailConfig

// Research assistant
RAGGuardrails.research(llmClient): GuardrailConfig

// Financial applications (strict PII, regulatory compliance)
RAGGuardrails.financial(llmClient, allowedTopics): GuardrailConfig

// Custom combination
RAGGuardrails.custom(
  inputGuardrails = Seq(PIIDetector(), PromptInjectionDetector()),
  outputGuardrails = Seq(PIIMasker()),
  ragGuardrails = Seq(GroundingGuardrail.balanced(llmClient))
): GuardrailConfig
```

### What Each Preset Includes

| Preset | Input | Output | RAG |
|---|---|---|---|
| `minimal` | PII, Injection | PII Masking | — |
| `standard` | PII, Injection (balanced) | PII Masking | Grounding, Context Relevance |
| `strict` | PII (strict), Injection (strict), Topic | PII Masking | Grounding (strict), Context Relevance (strict), Source Attribution (strict) |
| `monitoring` | PII (warn), Injection (monitoring) | PII Masking | Grounding (monitoring), Context Relevance (monitoring), Source Attribution (monitoring) |
| `customerSupport` | PII, Injection, Topic (support) | PII Masking | Grounding, Context Relevance |
| `softwareDocumentation` | Injection, Topic (dev) | — | Grounding, Source Attribution |
| `research` | Injection | PII Masking | Grounding (strict), Context Relevance, Source Attribution (strict) |
| `financial` | PII (financial), Injection (strict), Topic (strict) | PII Masking (financial) | Grounding (strict), Source Attribution (strict) |

---

## Sensitivity Levels

Most guardrails offer sensitivity presets:

```scala
// PIIDetector
PIIDetector()          // Default sensitivity
PIIDetector.strict     // High sensitivity
PIIDetector.financial  // Financial-specific patterns (SSN, credit card, etc.)

// PromptInjectionDetector
PromptInjectionDetector()          // Default
PromptInjectionDetector.balanced   // Balanced false-positive rate
PromptInjectionDetector.strict     // High sensitivity
PromptInjectionDetector.monitoring // Warn-only mode

// GroundingGuardrail
GroundingGuardrail.balanced(llmClient)    // Standard grounding check
GroundingGuardrail.strict(llmClient)      // Strict grounding (rejects loosely grounded)
GroundingGuardrail.monitoring(llmClient)  // Warn-only

// ContextRelevanceGuardrail
ContextRelevanceGuardrail.balanced(llmClient)
ContextRelevanceGuardrail.strict(llmClient)
ContextRelevanceGuardrail.monitoring(llmClient)

// SourceAttributionGuardrail
SourceAttributionGuardrail.balanced(llmClient)
SourceAttributionGuardrail.strict(llmClient)
SourceAttributionGuardrail.monitoring(llmClient)

// TopicBoundaryGuardrail
TopicBoundaryGuardrail.balanced(llmClient, topics)
TopicBoundaryGuardrail.strict(llmClient, topics)
TopicBoundaryGuardrail.customerSupport(llmClient, topics)
TopicBoundaryGuardrail.softwareDevelopment(llmClient)
```

---

## Composing Guardrails

### Sequential Composition

```scala
// Run PII check, then injection check
val combined = PIIDetector() andThen PromptInjectionDetector()

// Validate
combined.validate(userInput) match {
  case Right(safe) => // Process safe input
  case Left(error) => // Handle violation
}
```

### CompositeGuardrail

```scala
// Combine multiple guardrails
val composite = CompositeGuardrail.sequential(Seq(
  PIIDetector(),
  PromptInjectionDetector(),
  LengthCheck(1, 10000)
))
```

---

## Usage with Agent

```scala
val (inputGuards, outputGuards, ragGuards) = {
  val config = RAGGuardrails.standard(llmClient)
  (config.inputGuardrails, config.outputGuardrails, config.ragGuardrails)
}

// Validate input before agent
val validatedInput = inputGuards.foldLeft[Result[String]](Right(userQuery)) { (result, guard) =>
  result.flatMap(guard.validate)
}

validatedInput match {
  case Right(safeQuery) =>
    // Run agent with safe query
    agent.run(safeQuery, tools) match {
      case Right(state) =>
        // Validate output
        val output = state.conversation.messages.last.content
        val validatedOutput = outputGuards.foldLeft[Result[String]](Right(output)) { (result, guard) =>
          result.flatMap(guard.validate)
        }
        validatedOutput match {
          case Right(safeOutput) => println(safeOutput)
          case Left(error) => println(s"Output blocked: ${error.message}")
        }
      case Left(error) => println(s"Agent error: ${error.message}")
    }
  case Left(error) =>
    println(s"Input blocked: ${error.message}")
}
```

---

## Next Steps

- **RAG Pipeline**: See `llm4s-rag-pipeline.md` for document ingestion and retrieval
- **Memory System**: See `llm4s-memory-system.md` for agent memory
- **Orchestration**: See `llm4s-orchestration.md` for multi-agent coordination
- **Agent Patterns**: See `llm4s-agent-patterns.md` for agent execution model
