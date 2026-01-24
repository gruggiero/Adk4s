# Project Context

## Purpose
adk4s is an Agent Development Kit for Scala, inspired by industry-leading agent frameworks including Eino (CloudWego), Google ADK, Dapr Agents, and Letta. The goal is to provide a comprehensive toolkit for building production-ready AI agents with type-safe, composable abstractions.

**Current Scope**: The project is in early development and currently includes only the LLM integration layer (`structured-llm`), which provides type-safe, composable structured outputs from Large Language Models. It wraps the llm4s library with Smithy-based schema definitions, enforcing structured output parsing through Schema-Aligned Parsing (SAP). Inspired by BAML, this component enables developers to define output types as Scala case classes with Smithy schemas that guide LLM responses, ensuring type-safe parsing with automatic recovery from common LLM JSON errors.

**Vision**: adk4s aims to expand beyond LLM integration to include:
- Multi-agent orchestration and coordination
- Tool/function calling with type-safe abstractions
- Memory management (short-term, long-term, vector stores)
- Agent state persistence and checkpointing
- Event-driven agent communication
- Workflow composition and DAG execution
- Monitoring, observability, and debugging tools

## Tech Stack

### Current (LLM Module)
- **Language**: Scala 3.7.3
- **Build Tool**: sbt 1.11.7
- **Core Libraries**:
  - Cats Effect 3.6.3 - For IO monad and effect management
  - Smithy4s 0.18.45 - For schema definitions and code generation
  - llm4s - Core LLM client abstraction (local dependency)
  - workflows4s - Workflow orchestration (local dependency)
- **Testing**:
  - MUnit 1.0.3 - Test framework
  - MUnit Cats Effect 2.0.0 - IO testing support
- **Code Quality**:
  - Scalafmt 2.5.6 - Code formatting
  - Scalafix 0.14.3 - Linting and code quality
  - Scoverage 2.2.2 - Code coverage

### Planned (Future Modules)
Additional technologies will be evaluated as new agent capabilities are developed, including:
- Vector databases (for memory storage)
- Message queues (for agent coordination)
- Observability stacks (for monitoring and tracing)
- State persistence layers

## Project Conventions

### Code Style
- **Indentation**: 2 spaces (Scalafmt default)
- **Naming**:
  - Types: CamelCase (e.g., `StructuredLLM`, `Schema`)
  - Values/methods: camelCase (e.g., `complete`, `parseResponse`)
  - Packages: lowercase with dots (e.g., `org.adk4s.structured.core`)
  - Constants: UPPER_SNAKE_CASE (rare, prefer vals)
- **Braces**: Use significant indentation (Scala 3 style) where appropriate
- **Imports**:
  - Organize alphabetically
  - Group stdlib, third-party, and local imports
  - Use wildcard imports for package objects (`import org.adk4s.structured.*`)
- **Comments**: Use Scaladoc style for public APIs with `/** */` format
- **Compiler Options**:
  - `-deprecation` - Warn on deprecated features
  - `-feature` - Warn on feature usage requiring import
  - `-unchecked` - Warn on unchecked type operations
  - `-Xkind-projector:underscores` - Enable kind projector syntax
  - `-source:future` - Enable future language features
  - `-Wconf:src=target/.*:s` - Silence warnings in generated code

### Architecture Patterns
- **Opaque Types**: Use opaque types for type-safe abstractions (e.g., `opaque type Schema[A]`)
- **Typeclasses**: Implement typeclasses for extensibility (e.g., `Schema[A]`)
- **Functional Programming**: Pure functions with IO effects via Cats Effect
- **Separation of Concerns** (current modules):
  - `structured-llm/core/` - LLM abstractions (Schema, Prompt, StructuredLLM)
  - `structured-llm/template/` - Prompt DSL and string interpolators
  - `structured-llm/sap/` - Schema-Aligned Parser for lenient JSON parsing
  - `structured-llm/smithy/` - Smithy schema derivation (planned)
- **Modular Growth** (planned modules):
  - `agent-core/` - Agent lifecycle and state management
  - `memory/` - Memory abstractions (vector stores, conversation history)
  - `tools/` - Function calling with type-safe tool definitions
  - `orchestration/` - Multi-agent coordination and workflows
  - `observability/` - Logging, metrics, and tracing
- **Dependency Injection**: Constructor injection with typeclass parameters
- **Error Handling**: Sealed trait hierarchies for ADT errors (`ParseResult`, `StructuredLLMError`)

### Testing Strategy
- **Framework**: MUnit with Cats Effect integration
- **Test Structure**:
  - Mock LLM clients for unit testing
  - Use `CatsEffectSuite` for IO-based tests
  - Test naming: descriptive, e.g., `"extract resume using smithy4s decoder"`
- **Coverage**: Aim for high coverage on core parsing logic
- **Test Data**: Use structured-llm-test-models for Smithy schema test cases
- **Run tests**: `sbt test`

### Git Workflow
- **Branching**: Main development on main branch (currently no specific branch strategy defined)
- **Commit Messages**: Conventional commits recommended (subject to local conventions)
- **Spec-Driven Development**: Use OpenSpec for proposals and specs:
  - Create change proposals in `openspec/changes/[change-id]/`
  - Implement after approval
  - Archive completed changes

## Domain Context

### Current (LLM Integration)
Key concepts for the structured-llm module:

- **Schema[A]**: Typeclass providing Smithy IDL definition and JSON decoder
- **StructuredLLM[F]**: Main wrapper providing `Prompt => F[A]` abstraction
- **Prompt**: Immutable conversation representation with system/user messages
- **PromptTemplate[I]**: Reusable template taking input I to produce a Prompt
- **SAP (Schema-Aligned Parser)**: Lenient JSON parser recovering from:
  - Markdown code fences
  - Trailing commas
  - Single quotes vs double quotes
  - Unquoted keys
  - JSON comments
  - Truncated responses

### Smithy Integration
- Output types defined in Smithy (`.smithy` files in `structured-llm-test-models/src/main/smithy`)
- Smithy4s generates Scala code from Smithy definitions
- Smithy IDL is ~80% more compact than JSON Schema for LLM prompts
- Rich metadata via `@documentation` and `@required` traits

### Planned (Agent Framework)
Future agent capabilities will introduce additional abstractions:
- **Agent**: Autonomous entities with tools, memory, and goals
- **Tool**: Type-safe function definitions with input/output schemas
- **Memory**: Abstractions for short-term (context), long-term (vector store), and persistent storage
- **Orchestration**: Multi-agent patterns, DAG workflows, event-driven coordination
- **State**: Agent state management with persistence and checkpointing

## Important Constraints
- **Early Development**: Currently only LLM integration is implemented; other agent components are planned
- **Scala 3 Only**: Targeting Scala 3.7.3 with modern language features
- **Type Safety**: All LLM outputs must be parsed into typed case classes
- **Effect System**: All LLM calls must return IO[A] for referential transparency
- **Local Dependencies**: llm4s and workflows4s are referenced as local projects in parent directories
- **No Secrets**: Never commit API keys or credentials (use environment variables)
- **Modular Design**: New agent capabilities should be added as separate modules (e.g., `agent-core`, `memory`, `tools`, `orchestration`)

## External Dependencies
- **llm4s**: LLM client library (local: `../../llm4s/llm4s`)
- **workflows4s**: Workflow orchestration library (local: `../../business4s/workflows4s`)
- **LLM Providers**: Via llm4s (OpenAI, Anthropic, Azure, etc.)
- **Smithy4s**: Schema definition and code generation (Maven dependency)
- **Alloy**: Smithy extensions for enhanced metadata
