# structured-llm-examples Specification

## Purpose
StructuredLLM examples demonstrate type-safe response parsing and pattern implementation for classification, extraction, multi-agent coordination, chain composition, and workflow integration using Smithy schemas.

## Requirements
### Requirement: StructuredLLM examples SHALL demonstrate classification patterns
The system SHALL provide examples showing how to use StructuredLLM to classify text into typed categories, covering category routing, role detection, query classification and route determination patterns.

#### Scenario: Category classification example
- **WHEN** user runs CategoryClassificationStructuredExample
- **THEN** system classifies input query into a typed category (math/science/history/other) with confidence score using StructuredLLM

#### Scenario: Role detection example
- **WHEN** user runs RoleDetectionStructuredExample
- **THEN** system detects speaker role in conversation (customer/support/manager) using StructuredLLM with schema validation

#### Scenario: Query classification example
- **WHEN** user runs QueryClassificationStructuredExample
- **THEN** system classifies query type (question/command/statement) and intent using StructuredLLM

#### Scenario: Chain routing example
- **WHEN** user runs ChainRouteStructuredExample
- **THEN** system routes conversation to appropriate chain based on classified intent using StructuredLLM

### Requirement: StructuredLLM examples SHALL demonstrate extraction patterns
The system SHALL provide examples showing how to use StructuredLLM to extract structured data from unstructured text, covering plan parsing, step extraction, numbered list parsing and schema-driven extraction.

#### Scenario: Plan extraction example
- **WHEN** user runs PlanExecuteStructuredExample
- **THEN** system extracts typed plan with numbered steps from free-text description using StructuredLLM

#### Scenario: Step list extraction example
- **WHEN** user runs StepsExtractionStructuredExample
- **THEN** system extracts ordered list of steps with metadata (index, description, duration) using StructuredLLM

#### Scenario: Numbered list parsing example
- **WHEN** user runs ListParsingStructuredExample
- **THEN** system parses numbered or bulleted list into typed collection with item metadata using StructuredLLM

#### Scenario: Schema-driven extraction example
- **WHEN** user runs SchemaExtractionStructuredExample
- **THEN** system extracts complex structured data matching Smithy schema from narrative text using StructuredLLM

### Requirement: StructuredLLM examples SHALL demonstrate multi-agent patterns
The system SHALL provide examples showing how to use StructuredLLM for multi-agent coordination, covering host-based routing and specialist delegation patterns.

#### Scenario: Multi-agent host routing example
- **WHEN** user runs MultiAgentHostStructuredExample
- **THEN** system uses StructuredLLM to parse host agent's routing decision and delegates to appropriate specialist agent

#### Scenario: Specialist delegation example
- **WHEN** user runs SpecialistDelegationStructuredExample
- **THEN** system uses StructuredLLM to parse specialist selection with rationale and routes request to selected specialist

### Requirement: StructuredLLM examples SHALL demonstrate chain composition patterns
The system SHALL provide examples showing how to use StructuredLLM for composing typed chain outputs, covering intermediate type transformations and multi-step composition.

#### Scenario: Typed intermediates example
- **WHEN** user runs TypedIntermediatesStructuredExample
- **THEN** system chains multiple StructuredLLM calls with typed intermediate results flowing between stages

#### Scenario: Chain composition example
- **WHEN** user runs ChainCompositionStructuredExample
- **THEN** system composes multiple StructuredLLM parsers with type-safe result composition

#### Scenario: Transform chain example
- **WHEN** user runs TransformChainStructuredExample
- **THEN** system transforms unstructured input through typed stages using chained StructuredLLM calls

### Requirement: StructuredLLM examples SHALL demonstrate workflow integration patterns
The system SHALL provide examples showing how to integrate StructuredLLM with workflows and graphs, covering typed completions in graph nodes and async transformations.

#### Scenario: Graph integration example
- **WHEN** user runs GraphIntegrationStructuredExample
- **THEN** system uses StructuredLLM within WIOGraph nodes to parse typed completions for graph flow control

#### Scenario: Async node transformation example
- **WHEN** user runs AsyncNodeStructuredExample
- **THEN** system uses StructuredLLM with streaming to parse typed results in async graph nodes

### Requirement: StructuredLLM examples SHALL support both mock and real LLM execution
The system SHALL allow all StructuredLLM examples to run with either MockChatModel or real LLM based on OPENAI_API_KEY environment variable.

#### Scenario: Mock mode execution
- **WHEN** user runs any StructuredLLM example without OPENAI_API_KEY set
- **THEN** example uses MockChatModel returning deterministic schema-compliant JSON responses

#### Scenario: Real LLM execution
- **WHEN** user runs any StructuredLLM example with OPENAI_API_KEY environment variable set
- **THEN** example uses real LLM client (OpenAI gpt-4o-mini by default) and prints "[Using real LLM: model-name]"

#### Scenario: Model selection
- **WHEN** user runs example with OPENAI_API_KEY and LLM_MODEL environment variables set
- **THEN** example uses specified model from LLM_MODEL variable

### Requirement: StructuredLLM examples SHALL demonstrate SAP error recovery
The system SHALL include examples demonstrating Schema-Aligned Parser's automatic recovery from malformed LLM responses.

#### Scenario: Markdown fence recovery example
- **WHEN** MockChatModel returns JSON wrapped in markdown code fences (```json...```)
- **THEN** StructuredLLM successfully extracts and parses JSON content via SAP

#### Scenario: Trailing comma recovery example
- **WHEN** MockChatModel returns JSON with trailing commas before closing braces
- **THEN** StructuredLLM automatically removes trailing commas via SAP and successfully parses

#### Scenario: Single quote recovery example
- **WHEN** MockChatModel returns JSON with single quotes instead of double quotes
- **THEN** StructuredLLM automatically converts quotes via SAP and successfully parses

### Requirement: StructuredLLM examples SHALL use Smithy schemas for all output types
The system SHALL define all StructuredLLM example output types using Smithy IDL in a single examples.smithy file.

#### Scenario: Smithy schema compilation
- **WHEN** examples project is compiled
- **THEN** smithy4s generates Scala case classes from examples.smithy definitions

#### Scenario: Schema namespace isolation
- **WHEN** Smithy schemas are defined in examples.smithy
- **THEN** all schemas use org.adk4s.examples.structured.schemas namespace to avoid conflicts

#### Scenario: Schema[A] instance creation
- **WHEN** example needs to parse LLM response into type A
- **THEN** example provides Schema[A] instance wrapping Smithy IDL definition and smithy4s.Schema[A]

### Requirement: StructuredLLM examples SHALL demonstrate streaming support
The system SHALL provide examples showing StructuredLLM streaming with progressive token display and final typed result.

#### Scenario: Streaming with typed result
- **WHEN** user runs streaming StructuredLLM example
- **THEN** system emits tokens progressively for UI display and provides final parsed typed result via streamWithResult

#### Scenario: Streaming progress display
- **WHEN** StructuredLLM streams response
- **THEN** example prints tokens as they arrive showing progressive generation

### Requirement: StructuredLLM examples SHALL be runnable via run-example.sh script
The system SHALL update run-example.sh to include entries for all 15 new StructuredLLM examples.

#### Scenario: Script includes new examples
- **WHEN** user runs run-example.sh --list
- **THEN** script displays all 15 new StructuredLLM examples under "Structured LLM Examples" section

#### Scenario: Script executes example by name
- **WHEN** user runs run-example.sh CategoryClassificationStructuredExample
- **THEN** script executes org.adk4s.examples.structured.llm.classification.CategoryClassificationStructuredExample

### Requirement: StructuredLLM examples SHALL update README with structured examples section
The system SHALL add new "Structured Examples" section to adk4s-examples/README.md documenting all StructuredLLM examples.

#### Scenario: README includes StructuredLLM section
- **WHEN** user reads adk4s-examples/README.md
- **THEN** README contains "Structured LLM (Type-Safe Response Parsing)" section listing all 15 examples organized by pattern

#### Scenario: README cross-references original examples
- **WHEN** structured example mirrors existing manual-parsing example
- **THEN** README documents comparison between manual and structured approaches
