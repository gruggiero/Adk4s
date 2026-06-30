# Concept Inventory

<!-- This is a LIVING DOCUMENT. It is populated by scanning the existing codebase
     and updated after each spec's implementation during the apply phase.

     PURPOSE: Prevent duplicate creation of domain concepts when implementing
     specs sequentially. Before creating any new type, the apply phase MUST
     check this inventory and reuse existing concepts.

     MAINTENANCE RULES:
     - APPEND ONLY during apply (never remove or modify existing entries)
     - Each entry records which spec introduced it (traceability)
     - Package paths must be exact (used for import statements)
     - Constraints must be exact (used for Iron type verification)

     SCAN METHOD: Manual grep-based scan. The semantic scanner
     (openspec/schemas/verified-scala3/scanner/concept-scanner.scala) was
     attempted but returned 0 results because it expects sources under
     `<root>/src/main/scala/` and does not descend into the multi-module
     layout (`adk4s-core/src/main/scala/`, etc.). Fall back to manual scan
     recorded here. Re-run a per-module scan if the scanner is fixed. -->

## Opaque Types (Iron Refined)

<!-- No Iron dependency is present (see capability-profile.md). Opaque types
     below are plain Scala 3 opaque types with no compile-time constraint
     library; constraints are enforced by companion `apply` returning Either. -->

| Type | Underlying | Iron Constraint | Package | Introduced By |
|------|-----------|-----------------|---------|---------------|
| `NodeKey` | `String` | none (validated via `NodeKey.apply: Either[String, NodeKey]`; `unsafeApply` throws) | `org.adk4s.core.types` | existing |
| `FieldPath` | `Vector[String]` | none | `org.adk4s.core.types` | existing |
| `RunPath` | `List[RunStep]` | none | `org.adk4s.core.interrupt` | existing |
| `Schema[A]` | `Schema.SchemaData[A]` | none | `org.adk4s.structured.core` | existing |
| `ToolSchema[A]` | `ToolSchema.SchemaData[A]` | none | `org.adk4s.core.tools` | existing |
| `ToolFunctionAdapter[T, R]` | `ToolFunction[T, R]` | none | `org.adk4s.core.tools` | existing |

## Sealed Traits and Enums

<!-- Closed type hierarchies that enable exhaustive pattern matching.
     Record ALL variants — the compiler enforces completeness. -->

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `AdkError` | sealed trait extends Throwable | `LlmCallError`, `StructuredOutputError`, `TypeMismatchError`, `MissingFieldError`, `NodeNotFoundError`, `EdgeValidationError`, `MaxStepsExceededError`, `GraphCompiledError`, `GraphEntryMissingError`, `GraphEndNodesMissingError`, `ToolNotFoundError`, `ToolExecutionError`, `StateTypeMismatchError`, `NodeAlreadyExistsError`, `SourceNodeNotFoundError`, `NodeDoesNotExistError`, `FanInError`, `BranchTargetError`, `AgentInterruptedException`, `CheckpointNotFoundError`, `GenericError` | `org.adk4s.core.error` | existing |
| `AgentEvent` | sealed trait | `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `Interrupted`, `ErrorOccurred`, `TokenDelta` | `org.adk4s.core.interrupt` | existing |
| `InterruptSignal` | sealed trait derives ReadWriter | `Simple`, `Stateful`, `Composite` | `org.adk4s.core.interrupt` | existing |
| `AddressSegment` | sealed trait derives ReadWriter | `Agent(name)`, `Tool(name)` | `org.adk4s.core.interrupt` | existing |
| `StructuredLLMError` | sealed trait extends Throwable | (variants in `org.adk4s.structured.core`) | `org.adk4s.structured.core` | existing |
| `ParseResult[+A]` | enum | `Success(value)`, `Failure(error: ParseError)` | `org.adk4s.structured.core` | existing |
| `ParseError` | sealed trait | (variants in `org.adk4s.structured.core`) | `org.adk4s.structured.core` | existing |
| `Role` | enum | (Prompt message roles) | `org.adk4s.structured.core` | existing |
| `ToolSchemaError` | sealed trait extends Throwable | (variants in `org.adk4s.core.tools`) | `org.adk4s.core.tools` | existing |
| `StructuredToolCallError` | sealed trait extends Throwable | (variants in `org.adk4s.core.tools`) | `org.adk4s.core.tools` | existing |
| `WIONode[Ctx, I, Err, O]` | sealed trait | `WIOPureNode`, `WIORunIONode`, `WIOForkNode` (with nested `Branch`, `StreamBranch`), `WIOLoopNode`, `WIOAwaitNode`, `WIOSubGraphNode`, `WIOHandleSignalNode`, `WIOParallelNode` (with nested `Element`), `WIORunnableNode`, `WIOForEachNode` | `org.adk4s.orchestration.wiograph` | existing |
| `WIONodeModifier[Ctx, I, Err, O]` | sealed trait | `CheckpointModifier`, `RetryModifier`, `InterruptionModifier` | `org.adk4s.orchestration.wiograph` | existing |
| `WIOGraphError` | sealed trait | (variants in `org.adk4s.orchestration.wiograph`) | `org.adk4s.orchestration.wiograph` | existing |
| `RunResult` | sealed trait | `Completed(output, messages)`, `Interrupted(checkpointId, signal)`, `Failed(error)` | `org.adk4s.orchestration.agent` | existing |
| `InterruptResult[+O]` | sealed trait | (variants in `org.adk4s.orchestration.interrupt`) | `org.adk4s.orchestration.interrupt` | existing |
| `GraphNode[I, O]` | sealed trait | (variants in `org.adk4s.orchestration.graph`) | `org.adk4s.orchestration.graph` | existing |
| `Branch[I]` | sealed trait | (variants in `org.adk4s.orchestration.branch`) | `org.adk4s.orchestration.branch` | existing |
| `ChainStep[I, O]` | sealed trait | (variants in `org.adk4s.orchestration.chain`) | `org.adk4s.orchestration.chain` | existing |
| `ChainBranch[I, O]` | sealed trait | (variants in `org.adk4s.orchestration.chain`) | `org.adk4s.orchestration.chain` | existing |
| `WorkflowNode[I, O]` | sealed trait | (variants in `org.adk4s.orchestration.workflow`) | `org.adk4s.orchestration.workflow` | existing |

## Case Classes (Domain Value Objects)

<!-- Immutable data carriers in domain packages. Only concepts directly
     relevant to this change (tool dispatch, error modeling, graph nodes)
     are recorded here; the full case-class set is large. -->

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| `AdkToolInfo` | `name: String`, `description: String`, `parameters: ujson.Value` | `org.adk4s.core.component` | existing |
| `ToolWrapper` | `originalToolFunction: Option[ToolFunction[?, ?]]`, `executable: SafeToolExecutable`, (+ metadata) | `org.adk4s.core.tools` | existing |
| `AgentInterruptedException` | `signal: InterruptSignal` (extends `AdkError`) | `org.adk4s.core.error` | existing |

## Service Traits

<!-- Tagless final service interfaces parameterised on F[_].
     The tool tier is NOT a single service trait — it is a three-tier
     sealed-style hierarchy (Tool / InvokableTool / StreamableTool). -->

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `Tool[F[_]]` | F | `info: AdkToolInfo`, `asToolFunction: Option[ToolFunction[Any, Any]]` | `org.adk4s.core.component` | existing |
| `InvokableTool[F[_]]` | F | extends `Tool[F]`; `run(arguments: ujson.Value): F[ujson.Value]` | `org.adk4s.core.component` | existing |
| `StreamableTool[F[_]]` | F | extends `Tool[F]`; `runStream(arguments: ujson.Value): Stream[F, String]` | `org.adk4s.core.component` | existing |
| `ChatModel[F[_]]` | F | `generate`, `stream`, `streamContent`, `withConfig` | `org.adk4s.core.component` | existing |
| `ToolCallingChatModel[F[_]]` | F | extends `ChatModel[F]` | `org.adk4s.core.component` | existing |
| `Agent` | — | `name`, `description`, `generate(messages, maxSteps): IO[AssistantMessage]` | `org.adk4s.core.component` | existing |
| `ReactAgent` | — | extends `Agent` | `org.adk4s.orchestration.agent` | existing |
| `StructuredLLM[F[_]]` | F | `complete[A]`, `streamWithResult[A]` | `org.adk4s.structured.core` | existing |
| `StreamingLLMClient[F[_]]` | F | streaming bridge | `org.adk4s.core.streaming` | existing |
| `Embedder[F[_]]` | F | embedding | `org.adk4s.core.component` | existing |
| `Retriever[F[_]]` | F | retrieval | `org.adk4s.core.component` | existing |
| `CheckpointStore` | — | `save`, `load`, `delete` | `org.adk4s.orchestration.interrupt` | existing |
| `StateRef[F[_], S]` | F, S | state access | `org.adk4s.orchestration.state` | existing |
| `Runnable[I, O]` | I, O | `invoke`, `stream`, `collect`, `transform` | `org.adk4s.core.runnable` | existing |
| `SafeToolExecutable` | — | runtime tool executable marker | `org.adk4s.core.tools` | existing |
| `NodeExecutable[I, O]` | I, O | graph node execution | `org.adk4s.orchestration.execution` | existing |

## Smithy Models

<!-- Smithy IDL definitions driving smithy4s codegen. Located in
     structured-llm-test-models/src/main/smithy/. These are TEST models,
     not production IDL. This change does not touch IDL. -->

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|
| (various test schemas: Resume, etc.) | structure | test-only fields | `structured-llm-test-models/src/main/smithy/` | existing |

## ScalaCheck Generators

<!-- Hedgehog is used (NOT ScalaCheck). Record Hedgehog `Gen[_]` values
     in test sources that this change's specs may reuse. -->

| Generator | Generates | Location | Introduced By |
|-----------|----------|----------|---------------|
| (none recorded yet — to be populated during apply if property tests need shared generators) | — | — | — |

## Cats Effect Resources and Middleware

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
| `ToolMiddleware` | Kleisli-based middleware | composable tool middleware: `logging`, `timing`, `jsonFix`, custom | `org.adk4s.core.tools` | existing |
| `AgentEventEmitter` | fs2.concurrent.Topic-backed | hierarchical event emission via `scoped(RunStep)` | `org.adk4s.core.interrupt` | existing |

## Consistency Check

- **Scan method**: manual grep over `*/src/main/scala/**/*.scala` across all
  modules in build.sbt. The semantic scanner was attempted but returned 0
  results due to multi-module layout mismatch (scanner expects
  `<root>/src/main/scala/`).
- **Package paths**: all recorded package paths match real `package` clauses
  in the cited source files (verified via grep of `^package` declarations).
- **`AdkError` variants**: verified against
  `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala` (21 case
  classes extending `AdkError`).
- **`WIONode` variants**: verified against
  `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONode.scala`
  (10 top-level sealed case classes + nested `Branch`/`StreamBranch`/`Element`).
- **`NodeKey` constraint**: no Iron; validation is via `apply: Either[String, NodeKey]`
  with `unsafeApply` throwing `IllegalArgumentException` — this is a `Throw`-spec
  target (see proposal).
- **Discrepancies**: none. The `adk4s-agent/` and `adk4s/` directories contain
  Scala files but are NOT declared in build.sbt and are therefore not compiled
  or linted — they are excluded from this inventory.
