# Project Context

## Purpose
adk4s (Agent Development Kit for Scala 3) is a functional, type-safe agent toolkit. It builds on **llm4s** (LLM client), **workflows4s** (workflow engine), and **smithy4s** (schema codegen) to provide a complete stack for LLM-powered agents: structured outputs, tool execution, streaming, multi-agent orchestration, interrupt/resume, and real-time observability.

**Core idea**: compose LLM calls, tools, and workflows as pure functions using Cats Effect and fs2.

## Module Dependency Graph

```
adk4s-examples → adk4s-core, adk4s-orchestration, structured-llm, structured-llm-test-models
adk4s-orchestration → adk4s-core, structured-llm, workflows4s-core
adk4s-core → structured-llm, llm4s/core
structured-llm → llm4s/core, workflows4s-core
structured-llm-test-models → structured-llm (compile only, Smithy codegen)
verified → (leaf module, Scala 3.7.2, Stainless, not aggregated by root)
```

### Modules

- **structured-llm** — Type-safe LLM outputs. `StructuredLLM[F[_]]`, `Schema[A]` typeclass, `Prompt`, `SchemaAlignedParser` (lenient JSON recovery), `PromptSyntax`.
- **structured-llm-test-models** — Smithy schemas (`.smithy` files) compiled via smithy4s. Generated types used in tests. Keeps test-only schemas isolated from production code.
- **adk4s-core** — Components, tools, runnables, events, interrupts. `ChatModel`, `Tool`/`InvokableTool`/`StreamableTool`, `Agent`, `AgentTool`, `ToolsNode`, `ToolMiddleware`, `Runnable`/`Lambda`, `AgentEvent`/`AgentEventEmitter`, `InterruptSignal`, streaming utilities, `AdkError` hierarchy.
- **adk4s-orchestration** — Agents, graphs, workflows. `ReactAgent`, `AgentRunner` (interrupt/resume), `WIOGraph`/`WIONode` (type-safe DAG), `Graph`/`GraphExecutor`, `Branch`/`Router`, `Chain`, `Workflow`, `StatefulNode`, `EventSourcedState`, `CheckpointStore`.
- **adk4s-examples** — 55+ runnable examples (all extend `IOApp.Simple`). Run via `./adk4s-examples/run-example.sh <name>` or `sbt "adk4s-examples/runMain <FQCN>"`.
- **verified** — Leaf module pinned to Scala 3.7.2 for Stainless formal verification (Ring 6). Not aggregated by root. Run with `sbt -J-Xmx6g ring6`.

## Tech Stack

### Language & Build
- **Language**: Scala 3.8.4 (verified module: Scala 3.7.2 for Stainless)
- **Build Tool**: sbt 1.12.12
- **Compiler Options**: `-deprecation`, `-feature`, `-unchecked`, `-Xkind-projector:underscores`, `-source:future`, `-Wconf:src=target/.*:s`

### Core Libraries
- llm4s 0.3.4 (Maven Central) — `LLMClient`, `Conversation`, `Message`, `ToolFunction`, `ToolRegistry`, `CompletionOptions`
- Cats Effect 3.7.0 — IO monad and effect management
- fs2 3.13.0 — functional streams
- smithy4s 0.18.55 — schema definitions and code generation
- workflows4s 0.6.2 (local sibling repo via ProjectRef) — WIO monad, WorkflowContext, event sourcing, signal routing
- upickle — serialization (core/orchestration)
- logback 1.5.34 (examples only)

### Testing
- MUnit 1.3.3 / munit-cats-effect 2.2.0 — test framework with IO support
- Hedgehog 0.13.1 — property testing with integrated shrinking (`hedgehog-munit` % Test)
- ~568 tests total across all modules

### Code Quality
- WartRemover 3.5.8 (sbt-wartremover, Ring 1 static analysis) — `Warts.unsafe` enabled with permanent exclusions for `TripleQuestionMark`, `Any` (Scala 3 string interpolation false positive), `DefaultArguments` (valid Scala API design feature)
- Scalafmt 2.6.1 — code formatting
- Scalafix 0.14.7 — linting and code quality
- Scoverage 2.4.4 — code coverage
- Stryker4s 0.21.0 (sbt-stryker4s, Ring 5 mutation testing)
- Stainless (bundled jar, Ring 6 formal verification)

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
  - Import only the symbols needed — do NOT use wildcard imports (`*` / `_`)
- **Comments**: Use Scaladoc style for public APIs with `/** */` format
- **Types**: Write all types explicitly — do NOT rely on type inference
- **Functional Programming**: Pure functions with IO effects via Cats Effect. Use immutable data structures. Do NOT use mutable variables.
- **No `isInstanceOf` / `asInstanceOf`**: Use pattern matching instead. Suppress with `@SuppressWarnings` only when unavoidable (Scala 3 type erasure).
- **No `Any` type**: If `Any` is needed, ask for help.
- **Error Handling**: Sealed trait hierarchies for ADT errors (`AdkError`, `WIOGraphError`, `StructuredLLMError`). Use `Either` for error propagation in builders. Use `IO.raiseError` for runtime errors in effectful code.

### Architecture Patterns
- **Opaque Types**: Use opaque types for type-safe abstractions (e.g., `opaque type Schema[A]`, `NodeKey`)
- **Typeclasses**: Implement typeclasses for extensibility (e.g., `Schema[A]`)
- **Effect Polymorphism**: Components parameterized by `F[_]` effect type, instantiated with `IO` for production
- **Sealed ADTs**: Sealed trait hierarchies for errors, events, signals, node types
- **Companion Object Factories**: Use companion `apply` / `create` methods for construction with validation
- **Middleware Pattern**: `Kleisli`-based composable middleware (e.g., `ToolMiddleware`)
- **Dependency Injection**: Constructor injection with typeclass parameters

### Testing Strategy
- **Framework**: MUnit with Cats Effect integration + Hedgehog property testing
- **Test Structure**:
  - Mock LLM clients for unit testing
  - Use `CatsEffectSuite` for IO-based tests
  - Use `HedgehogSuite` for property-based tests
  - Test naming: descriptive, e.g., `"extract resume using smithy4s decoder"`
- **Coverage**: Aim for high coverage on core parsing and orchestration logic
- **Test Data**: Use `structured-llm-test-models` for Smithy schema test cases
- **Run tests**: `sbt test` (~568 tests)

### Verification Rings
The project uses a multi-ring verification strategy:
- **Ring 0**: `sbt compile` — basic compilation
- **Ring 1**: WartRemover — static analysis (Warts.unsafe with permanent exclusions)
- **Ring 3**: Hedgehog — property-based testing
- **Ring 5**: Stryker4s — mutation testing (retarget `stryker4s.conf` first)
- **Ring 6**: Stainless — formal verification (`sbt -J-Xmx6g ring6`)
- **Ring 8**: Adversarial code review

### Git Workflow
- **Branching**: Main development on main branch
- **Commit Messages**: Conventional commits recommended
- **Spec-Driven Development**: Use OpenSpec for proposals and specs:
  - Create change proposals in `openspec/changes/[change-id]/`
  - Implement after approval
  - Archive completed changes to `openspec/changes/archive/`

## Domain Context

### Structured LLM Outputs (structured-llm)
- **Schema[A]**: Typeclass providing Smithy IDL definition and smithy4s JSON decoder
- **StructuredLLM[F]**: Main wrapper providing `Prompt => F[A]` abstraction
- **Prompt**: Immutable conversation representation with system/user messages
- **SAP (Schema-Aligned Parser)**: Lenient JSON parser recovering from markdown fences, trailing commas, single quotes, unquoted keys, JSON comments, truncated responses

### Agent Components (adk4s-core)
- **ChatModel[F[_]]**: Effect-polymorphic LLM interface wrapping llm4s
- **Tool / InvokableTool / StreamableTool**: Three-tier tool abstraction
- **Agent**: Minimal interface for LLM-powered agents
- **AgentTool**: Wraps an Agent as InvokableTool for hierarchical delegation
- **ToolsNode**: Executes LLM tool calls with middleware, parallel/sequential strategies
- **Runnable[I, O]**: Universal computation with 4 modes (invoke, stream, collect, transform)
- **AgentEvent / AgentEventEmitter**: Event system with hierarchical scoping via fs2 Topic
- **InterruptSignal**: Sealed trait for interrupt routing with address targeting

### Orchestration (adk4s-orchestration)
- **ReactAgent**: ReAct (Reasoning + Acting) loop with tool execution
- **AgentRunner**: Manages interrupt/resume lifecycle with CheckpointStore
- **WIOGraph**: Type-safe DAG built from WIONodes, compiles to WIO or Runnable
- **Graph / GraphExecutor**: Generic graph structure and parallel execution
- **Branch / Router / Chain**: Conditional branching and linear chain execution
- **StatefulNode / EventSourcedState**: State management with event sourcing

### Smithy Integration
- Output types defined in Smithy (`.smithy` files in `structured-llm-test-models/src/main/smithy`)
- Smithy4s generates Scala code from Smithy definitions
- Smithy IDL is ~80% more compact than JSON Schema for LLM prompts
- Rich metadata via `@documentation` and `@required` traits
- Never use Smithy `list Foo { member: Bar }` in IDL strings shown to LLMs — use `Type[]` notation instead

## Important Constraints
- **Scala 3 Only**: Targeting Scala 3.8.4 with modern language features (verified module: 3.7.2 for Stainless)
- **Type Safety**: All LLM outputs must be parsed into typed case classes
- **Effect System**: All LLM calls must return `F[A]` (typically `IO[A]`) for referential transparency
- **Local Dependencies**: llm4s (Maven Central 0.3.4) and workflows4s (local sibling repo `../../business4s/workflows4s`) — workflows4s must exist on disk for compilation
- **No Secrets**: Never commit API keys or credentials (use environment variables like `OPENAI_API_KEY`)
- **No `throw`**: Use `Either` for error propagation or `IO.raiseError` for effectful errors
- **No `asInstanceOf` / `isInstanceOf`**: Use pattern matching; suppress only when unavoidable (type erasure)
- **No mutable variables**: Use immutable data structures and pure functions
- **No wildcard imports**: Import only needed symbols

## External Dependencies
- **llm4s**: LLM client library (Maven Central: `org.llm4s %% core % 0.3.4`)
- **workflows4s**: Workflow orchestration library (local: `../../business4s/workflows4s`)
- **LLM Providers**: Via llm4s (OpenAI, Anthropic, Azure, etc.)
- **Smithy4s**: Schema definition and code generation (Maven Central)
- **Cats Effect**: Effect system library (Maven Central)
- **fs2**: Functional streams (Maven Central)
