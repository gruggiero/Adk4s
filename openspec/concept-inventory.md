# Concept Inventory

<!-- PROJECT-SCOPED LIVING DOCUMENT — lives at openspec/concept-inventory.md
     (the type-level companion of the behavioral registry at
     openspec/concepts/), NOT in a change directory. Populated by scanning
     the codebase once, then updated after each spec's implementation during
     the apply phase (Step 12). Provenance accumulates ACROSS changes; each
     change's inventory-check artifact verifies this file instead of
     re-creating it.

     PURPOSE: Prevent duplicate creation of domain concepts when implementing
     specs sequentially. Before creating any new type, the apply phase MUST
     check this inventory and reuse existing concepts.

     MAINTENANCE RULES:
     - APPEND ONLY during apply (never remove or modify existing entries,
       except fixing a stale row while PRESERVING its provenance)
     - Each entry records which spec introduced it: `spec:<change>/<spec>`
       (or `scan:<file>` / `pre-existing` for concepts predating the workflow)
     - Package paths must be exact (used for import statements)
     - Constraints must be exact (used for refined-type verification)

     SCAN METHOD: manual/regex scan on 2026-07-18 (cross-checked against the
     archived `2026-07-05-add-memory-api` inventory), performed while the
     semantic scanner still missed multi-module builds. The scanner has since
     been fixed (it now discovers every `src/` root — 383 rows on this repo)
     and is the verification tool of choice: scan to a scratch file and diff
     against this document; never re-create this file from a raw scan (that
     would replace spec provenance with scan provenance).

     Seeded 2026-07-18 from the add-memory-orchestration-hook change's
     inventory (schema v6 migration); "REUSED/EXTENDED by this change"
     annotations refer to that change. -->

## Refined / Opaque Types

<!-- No Iron/refined library is present in the stack (see capability-profile.md).
     The opaque types below are plain `opaque type` newtypes WITHOUT Iron
     constraints. -->

| Type | Underlying | Constraint | Package | Introduced By |
|------|-----------|------------|---------|---------------|
| `RunPath` | `List[RunStep]` | (none — plain opaque type) | `org.adk4s.core.interrupt` | pre-existing |
| `NodeKey` | `String` | (none — plain opaque type) | `org.adk4s.core.types` | pre-existing |
| `FieldPath` | `Vector[String]` | (none — plain opaque type) | `org.adk4s.core.types` | pre-existing |
| `ToolSchema[A]` | `ToolSchema.SchemaData[A]` | (none — plain opaque type) | `org.adk4s.core.tools` | pre-existing |
| `Schema[A]` | `Schema.SchemaData[A]` | (none — plain opaque type) | `org.adk4s.structured.core` | pre-existing |

## Sealed Traits and Enums

<!-- Closed type hierarchies that enable exhaustive pattern matching.
     Variants listed where extractable from the scan + source cross-check. -->

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `AdkError` | sealed trait | `LlmCallError`, `StructuredOutputError`, `TypeMismatchError`, `MissingFieldError`, `NodeNotFoundError`, `EdgeValidationError`, `MaxStepsExceededError`, `GraphCompiledError`, `GraphEntryMissingError`, `GraphEndNodesMissingError`, `ToolNotFoundError`, `ToolExecutionError`, `StateTypeMismatchError`, `NodeAlreadyExistsError`, `SourceNodeNotFoundError`, `NodeDoesNotExistError`, `FanInError`, `BranchTargetError`, `AgentInterruptedException`, `CheckpointNotFoundError`, `GenericError`, `NodeKeyError` | `org.adk4s.core.error` | pre-existing |
| `ToolSchemaError` | sealed trait | `MissingRequiredField`, `TypeMismatch` | `org.adk4s.core.tools` | pre-existing |
| `StructuredToolCallError` | sealed trait | `UnknownTool`, `InvalidArguments`, `ExecutionFailed`, `ResultParsingFailed` | `org.adk4s.core.tools` | pre-existing |
| `InterruptSignal` | sealed trait (derives ReadWriter) | `Simple`, `Stateful`, `Composite` | `org.adk4s.core.interrupt` | pre-existing |
| `AgentEvent` | sealed trait | `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `Interrupted`, `ErrorOccurred`, `TokenDelta` | `org.adk4s.core.interrupt` | pre-existing — **EXTENDED by this change** (events spec adds `MemoryRecalled`, `MemoryWritten`) |
| `AddressSegment` | sealed trait (derives ReadWriter) | `Agent`, `Tool` | `org.adk4s.core.interrupt` | pre-existing |
| `RunResult` | sealed trait | `Completed`, `Interrupted`, `Failed` | `org.adk4s.orchestration.agent` | pre-existing — **REUSED by this change** (`MemoryAwareRunner` pattern-matches on it) |
| `WIONode` | sealed trait | (multiple node variants — see `WIONode.scala`) | `org.adk4s.orchestration.wiograph` | pre-existing |
| `WIONodeModifier` | sealed trait | `CheckpointModifier`, `RetryModifier`, `InterruptionModifier` | `org.adk4s.orchestration.wiograph` | pre-existing |
| `WIOGraphError` | sealed trait | (see `WIOGraphError.scala`) | `org.adk4s.orchestration.wiograph` | pre-existing |
| `ChainBranch` | sealed trait | (see `ChainBranch.scala`) | `org.adk4s.orchestration.chain` | pre-existing |
| `ChainStep` | sealed trait | (see `Chain.scala`) | `org.adk4s.orchestration.chain` | pre-existing |
| `GraphNode` | sealed trait | (see `GraphNode.scala`) | `org.adk4s.orchestration.graph` | pre-existing |
| `Branch` | sealed trait | (see `Branch.scala`) | `org.adk4s.orchestration.branch` | pre-existing |
| `WorkflowNode` | sealed trait | (see `WorkflowNode.scala`) | `org.adk4s.orchestration.workflow` | pre-existing |
| `StructuredLLMError` | sealed trait | `LLMCallFailed`, `ParseFailed`, `EmptyResponse`, `ValidationFailed`, `Enriched` | `org.adk4s.structured.core` | pre-existing |
| `ParseError` | sealed trait | `JsonSyntaxError`, `SchemaViolation`, `MissingRequiredField`, `UnexpectedEnumValue` | `org.adk4s.structured.core` | pre-existing |
| `ParseRetryTrigger` | enum | `ParseFailed`, `ValidationFailed`, `All` | `org.adk4s.structured.core` | pre-existing |
| `HoistStrategy` | enum | `Auto`, `All`, `None`, `Subset` | `org.adk4s.structured.core` | pre-existing |
| `MapStyle` | enum | `Inline`, `Verbose` | `org.adk4s.structured.core` | pre-existing |
| `ClientStrategy` | enum | `Fallback`, `RoundRobin` | `org.adk4s.structured.core` | pre-existing |
| `ParseResult` | enum | `Success`, `Failure` | `org.adk4s.structured.core` | pre-existing |
| `RetryTrigger` | enum | `LLMError`, `ParseFailure`, `ValidationFailure`, `All` | `org.adk4s.structured.core` | pre-existing |
| `ConstraintLevel` | enum | `Check`, `Assert` | `org.adk4s.structured.core` | pre-existing |
| `CheckStatus` | enum | `Succeeded`, `Failed` | `org.adk4s.structured.core` | pre-existing |
| `CoercionFlag` | enum | (see `CoercionScore.scala`) | `org.adk4s.structured.sap` | pre-existing |
| `CompletionState` | enum | `Pending`, `Incomplete`, `Complete` | `org.adk4s.structured.sap` | pre-existing |
| `JsonishValue` | enum | `Null`, `Bool`, `Num`, `Str`, `Arr`, `Obj`, `Markdown`, `AnyOf` | `org.adk4s.structured.sap` | pre-existing |
| `SourceType` | enum | `Conversation`, `Document`, `StructuredData`, `ToolResult`, `ExternalApi` | `org.adk4s.memory` | pre-existing (shipped by archived `2026-07-05-add-memory-api`) — **REUSED by this change** (`postTurn` writes `Conversation`/`ToolResult` episodes) |
| `FallbackSemantic` | enum | `Resume`, `Atomic`, `BeforeFirstElement` | `org.adk4s.core.runnable` | scan:RunnableOps.scala (found by fixed multi-module scanner, v6 migration) |
| `GraphWorkflowContext.Event` | sealed trait (nested) | `NodeResult` | `org.adk4s.orchestration.execution` | scan:GraphWorkflowContext.scala (found by fixed multi-module scanner, v6 migration) |
| `SectionType` | enum | `System`, `User`, `Assistant`, `Raw` | `org.adk4s.structured.template` | scan:PromptSyntax.scala (found by fixed multi-module scanner, v6 migration) |

> Note: `adk4s-examples` defines many per-example `sealed trait` state/event
> types. These are application-edge code, not reusable library concepts, and
> are omitted. The `verified` module's PureScala mirrors are also omitted
> (they are formal-verification models, not library types).

## Case Classes (Domain Value Objects)

<!-- Immutable data carriers in domain packages. Only library modules are
     listed — adk4s-examples case classes are application-edge and omitted.
     Only entries REUSED or INTRODUCED by this change are annotated; the rest
     are recorded for reuse-avoidance. -->

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| `Document` | `id: String, content: String, metadata: Map[String, ujson.Value]` | `org.adk4s.core.component` | pre-existing |
| `RetrieverConfig` | `topK: Int = 5, minScore: Double = 0.0` | `org.adk4s.core.component` | pre-existing |
| `EmbeddingUsage` | `promptTokens: Int, totalTokens: Int` | `org.adk4s.core.component` | pre-existing |
| `EmbeddingResult` | `embeddings: List[Embedding], usage: Option[EmbeddingUsage]` | `org.adk4s.core.component` | pre-existing |
| `AdkToolInfo` | `name: String, description: String, parameters: ujson.Value` | `org.adk4s.core.component` | pre-existing |
| `AgentToolState` | `messages: List[SerializableMessage], iterationCount: Int` | `org.adk4s.core.component` | pre-existing |
| `SerializableMessage` | `role: String, content: String` | `org.adk4s.core.component` | pre-existing |
| `ChatModelConfig` | `temperature: Option[Double], maxTokens: Option[Int], topP: Option[Double], stopSequences: Option[List[String]]` | `org.adk4s.core.component` | pre-existing |
| `RunInfo` | `nodeKey: NodeKey, componentType: String, nodeName: Option[String], startTime: Option[Instant], parentPath: List[NodeKey]` | `org.adk4s.core.types` | pre-existing |
| `AccumulatedResponse` | `content: String, finishReason: Option[String], toolCalls: List[ToolCall], id, created, model, usage, thinking: Option[String]` | `org.adk4s.core.streaming` | pre-existing |
| `ToolInput` | `name: String, arguments: String, callId: String` | `org.adk4s.core.tools` | pre-existing |
| `ToolOutput` | `name: String, result: String, callId: String, isError: Boolean` | `org.adk4s.core.tools` | pre-existing |
| `ToolExecutionResult` | `outputs: List[ToolOutput], failedTools: List[ToolExecutionFailure], interruptSignal: Option[InterruptSignal]` | `org.adk4s.core.tools` | pre-existing |
| `ToolExecutionFailure` | `input: ToolInput, error: Throwable` | `org.adk4s.core.tools` | pre-existing |
| `RunStep` | `name: String` | `org.adk4s.core.interrupt` | pre-existing |
| `MessageOutput` | `runPath: RunPath, message: String, role: String` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `ToolCallRequested` | `runPath: RunPath, toolName: String, arguments: String, callId: String` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `ToolCallCompleted` | `runPath: RunPath, toolName: String, result: String, callId: String, isError: Boolean` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `IterationCompleted` | `runPath: RunPath, iteration: Int, remainingSteps: Int` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `Interrupted` | `runPath: RunPath, signal: InterruptSignal` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `ErrorOccurred` | `runPath: RunPath, error: AdkError` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `TokenDelta` | `runPath: RunPath, delta: String` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `InterruptResult` | `address: List[AddressSegment], data: ujson.Value` | `org.adk4s.core.interrupt` | pre-existing |
| `Completed` | `output: String, messages: List[Message]` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) — **REUSED by this change** (`MemoryAwareRunner` extracts `output` for `postTurn`) |
| `Interrupted` | `checkpointId: String, signal: InterruptSignal` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) — **REUSED by this change** (`MemoryAwareRunner` skips `postTurn` on this variant) |
| `Failed` | `error: AdkError` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) — **REUSED by this change** (`MemoryAwareRunner` skips `postTurn` on this variant) |
| `CheckpointState` | `messages: List[SerializableCheckpointMessage], interruptSignalJson: String, agentName: String` | `org.adk4s.orchestration.agent` | pre-existing (private[agent]) |
| `FieldMapping` | `from: FieldPath, to: FieldPath, fromNode: Option[NodeKey]` | `org.adk4s.orchestration.workflow` | pre-existing |
| `GraphConfig` | `maxRunSteps: Int, graphName: Option[String], maxParallelism: Int` | `org.adk4s.orchestration.graph` | pre-existing |
| `Prompt` | `conversation: Conversation` | `org.adk4s.structured.core` | pre-existing |
| `Episode` | `content: String, sourceType: SourceType, timestamp: Instant, groupId: Option[String], metadata: Map[String, String]` | `org.adk4s.memory` | pre-existing (shipped by archived `2026-07-05-add-memory-api`) — **REUSED by this change** (`postTurn` builds `Episode.conversation(...)` / `Episode(..., SourceType.ToolResult, ...)`) |
| `EpisodeOutcome` | `entitiesExtracted: Int, relationshipsCreated: Int, edgesInvalidated: Int, processingTimeMs: Long, errors: List[String], episodeId: Option[String]` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (`postTurn` returns `F[List[EpisodeOutcome]]`) |
| `MemoryHit` | `text: String, score: Double, validFrom: Option[Instant], validTo: Option[Instant], provenance: Option[String], payload: Map[String, String]` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (`preTurn` renders `List[MemoryHit]` into a context string) |
| `TemporalScope` | `asOf: Instant` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (`MemoryPolicy.scope: Option[TemporalScope]`) |

## Service Traits

<!-- Tagless final service interfaces parameterised on F[_]. -->

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `ChatModel[F[_]]` | `F` | `generate`, `generate` (overloaded), `stream`, `stream` (overloaded), `streamContent`, `withConfig` | `org.adk4s.core.component` | pre-existing |
| `Tool[F[_]]` | `F` | `info`, `asToolFunction` | `org.adk4s.core.component` | pre-existing |
| `InvokableTool[F[_]]` | `F` | `run` | `org.adk4s.core.component` | pre-existing |
| `StreamableTool[F[_]]` | `F` | `runStream` | `org.adk4s.core.component` | pre-existing |
| `ToolCallingChatModel[F[_]]` | `F` | `tools`, `withTools`, `addTools`, `generateWithTools`, `streamWithTools` | `org.adk4s.core.component` | pre-existing |
| `ChatTemplate[F[_]]` | `F` | `format`, `formatConversation` | `org.adk4s.core.component` | pre-existing |
| `Retriever[F[_]]` | `F` | `retrieve(query, config: RetrieverConfig): F[List[Document]]`, `retrieveStream(query, config): Stream[F, Document]` | `org.adk4s.core.component` | pre-existing |
| `Embedder[F[_]]` | `F` | `embed`, `embedBatch`, `dimension` | `org.adk4s.core.component` | pre-existing |
| `StreamingLLMClient[F[_]]` | `F` | `stream`, `streamContent`, `complete` | `org.adk4s.core.streaming` | pre-existing |
| `StructuredToolCall[F[_]]` | `F` | `execute`, `executeRaw`, `function`, `extractor` | `org.adk4s.core.tools` | pre-existing |
| `TypedTool[F[_]]` | `F` | `name`, `description`, `execute`, `asInvokableTool` | `org.adk4s.core.tools` | pre-existing |
| `StateRef[F[_], S]` | `F` | `get`, `set`, `update`, `modify`, `getAndUpdate`, `updateAndGet` | `org.adk4s.orchestration.state` | pre-existing |
| `StructuredLLM[F[_]]` | `F` | `complete`, `completeRaw`, `completeTemplate`, `function`, `extractor`, `streamWithResult`, `streamWithResultRaw`, `completeValidated`, `streamPartial` | `org.adk4s.structured.core` | pre-existing |
| `AgentMemory[F[_]]` | `F` (no constraint on trait; `Monad[F]` on `rememberAll` default) | `remember(episode: Episode): F[EpisodeOutcome]`, `recall(query: String, k: Int, scope: Option[TemporalScope]): F[List[MemoryHit]]`, `rememberAll(episodes: List[Episode]): F[List[EpisodeOutcome]]` | `org.adk4s.memory` | pre-existing (shipped by archived `2026-07-05-add-memory-api`) — **REUSED by this change** (`MemoryHook` calls `recall`/`remember`) |
| `CheckpointStore` | (no type param — concrete trait) | `set(checkpointId: String, data: Array[Byte]): F[Unit]`, `get(checkpointId: String): F[Option[Array[Byte]]]`, `delete(checkpointId: String): F[Unit]` | `org.adk4s.orchestration.interrupt` | pre-existing — **REUSED by this change** (`MemoryAwareRunner` delegates resume to the underlying `AgentRunner` which owns the store) |

## Smithy Models

<!-- Smithy IDL structures driving smithy4s codegen. All live in
     structured-llm-test-models/src/main/smithy/. These are TEST fixtures,
     not production domain types — recorded for completeness. This change
     introduces NO new Smithy models. -->

| Model | Kind | Location | Introduced By |
|-------|------|----------|---------------|
| `Resume`, `MarketingCampaign`, `Product`, `Traveler`, `TravelBooking`, `Invoice`, `Attendee`, `EventRegistration`, `Address`, `Shipment`, `SupportTicket`, `Order`, `LoyaltyProgram`, `Patient`, `HealthcareAppointment`, `ProjectTask`, `VehicleInspection`, `Payment`, `CustomerProfile`, `InventoryItem`, `HRCandidate`, `BankTransaction`, `SubscriptionPlan`, `InsuranceClaim` | structure | `structured-llm-test-models/src/main/smithy/*.smithy` | pre-existing |
| `examples.smithy` shapes | structure | `structured-llm-test-models/src/main/smithy/examples.smithy` | pre-existing |

## Property Generators

<!-- Reusable Hedgehog `Gen[_]` values for property-based tests. -->

| Generator | Generates | Location | Introduced By |
|-----------|----------|----------|---------------|
| `genSourceType` | `Gen[SourceType]` | `adk4s-memory-api/src/test/scala/org/adk4s/memory/Generators.scala` | pre-existing (shipped) — **REUSED by this change** |
| `genContent` | `Gen[String]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genQuery` | `Gen[String]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genInstant` | `Gen[Instant]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genOptionalInstant` | `Gen[Option[Instant]]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genEpisode` | `Gen[Episode]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genEpisodes` | `Gen[List[Episode]]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genK` | `Gen[Int]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genScope` | `Gen[TemporalScope]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genHit` | `Gen[MemoryHit]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genConfig` | `Gen[RetrieverConfig]` | `adk4s-memory-api/src/test/.../Generators.scala` | pre-existing — **REUSED** |
| `genRoleString`, `genSerializableMessage` | `Gen[String]`, `Gen[SerializableMessage]` | `adk4s-core/src/test/.../MessageTypeDedupSerializationSpec.scala` | pre-existing |

> This change will add new Hedgehog generators for `MemoryPolicy` and
> `List[MemoryHit]` rendering in
> `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/Generators.scala`.
> They will be appended here during the apply phase.

## Cats Effect Resources and Middleware

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
| `AgentEventEmitter` | `fs2.concurrent.Queue`-backed emitter | Hierarchical event scoping via `scoped(RunStep)` | `org.adk4s.core.interrupt` | pre-existing — **REUSED by this change** (events spec emits `MemoryRecalled`/`MemoryWritten` through it) |
| `CheckpointStore` | trait (`InMemoryCheckpointStore` for dev) | Persist interrupt/resume checkpoint state | `org.adk4s.orchestration.interrupt` | pre-existing — **REUSED** (decorator forwards; underlying `AgentRunner` owns it) |
| `InMemoryAgentMemory[IO]` | `Ref[IO, Vector[Episode]]`-backed | Test double for `AgentMemory[IO]` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (hook tests use it as the memory) |

## Concepts This Change Will Introduce

<!-- NEW concepts (not yet in the codebase). Recorded here so the apply phase
     does NOT re-create them and so later specs can reuse them. These become
     "pre-existing" once implemented. -->

| Type | Kind | Package | Introduced By |
|------|------|---------|---------------|
| `MemoryPolicy` | final case class | `org.adk4s.orchestration.memory` | spec:memory-orchestration-hook |
| `MemoryHook` | final class | `org.adk4s.orchestration.memory` | spec:memory-orchestration-hook |
| `MemoryAwareRunner` | final class (decorator) | `org.adk4s.orchestration.memory` | spec:memory-orchestration-hook |
| `MemoryRecalled` | AgentEvent variant (final case class) | `org.adk4s.core.interrupt` | spec:memory-orchestration-events |
| `MemoryWritten` | AgentEvent variant (final case class) | `org.adk4s.core.interrupt` | spec:memory-orchestration-events |

## Consistency Check

- **Package paths**: all recorded package paths match real `package` clauses
  in the scanned sources (`org.adk4s.core.*`, `org.adk4s.orchestration.*`,
  `org.adk4s.memory`, `org.adk4s.memory.testkit`, `org.adk4s.structured.*`).
- **Opaque type constraints**: confirmed NO Iron/refined library in the stack;
  the five opaque types are plain newtypes without constraints.
- **Memory-api entries**: cross-checked against
  `adk4s-memory-api/src/main/scala/org/adk4s/memory/*.scala` — `AgentMemory`,
  `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`,
  `InMemoryAgentMemory`, `MemoryRetriever` all present and shipped.
- **Generators**: `adk4s-memory-api/src/test/scala/org/adk4s/memory/Generators.scala`
  compiles and contains every generator listed above (verified by grep).
- **Discrepancies**: none. The semantic scanner's 0-result run is a known
  limitation (it expects a top-level `src/`); the manual scan is authoritative.

## Behavioral Concepts (registry pass)

<!-- The project has a concept registry at openspec/concepts/ (25 concepts).
     This pass runs AGAINST the registry (does not regenerate it). -->

**registry-check.sh**: `OK (604 implementation-map tokens verified, 0 spec concept references checked, 2 weak binding(s) to tighten)` — run on 2026-07-18. The 2 WEAK rows are pre-existing in `react-agent.md` (`isDefined`, `foreach` not in the cited `ReactAgent.scala` but exist elsewhere); they are NOT caused by this change and are not blocking.

**Stale implementation-map rows**: none introduced by this change.

**Unregistered actions / syncs / state components flagged for human review**:

- **NEW candidate concept: `MemoryHook` / `MemoryAwareRunner`** — this change introduces a new behavioral unit (memory-aware agent execution) that does not yet have a registry file. Per the registry README's "Living document" rule, creating `openspec/concepts/memory-aware-runner.md` is PART OF implementing the hook spec (state: `policy`, `hook`, `underlying: AgentRunner`; actions: `run`, `runWithEvents`, `resume`; syncs: `RecallToContext` when `MemoryHook/preTurn` yields `Some(context)`, `WriteEpisode` when `MemoryHook/postTurn` runs on `RunResult.Completed`). The hook spec's "Concepts Used (behavioral)" table cites this as a concept to be created.
- **EXTENDED concept: `AgentEventStream`** — the events spec adds two new `AgentEvent` variants (`MemoryRecalled`, `MemoryWritten`). Updating `openspec/concepts/agent-event-stream.md` (its "AgentEvent variants" list and Implementation map) is PART OF implementing the events spec.
- **CITED existing concepts**: `AgentRunner` (decorated), `AgentEventStream` (extended), `ReactAgent` (the inner agent whose turn is wrapped). No new syncs beyond the two named above.

> The registry-check `0 spec concept references checked` count is correct:
> this change has no `specs/` directory yet (the specs artifact is created
> next). Once specs exist, registry-check's pass 3 will verify every
> `Concept`/`Concept/action` cited in the specs' "Concepts Used (behavioral)"
> tables against the registry — including the new `MemoryAwareRunner` concept
> file, which must be created before or during the events/hook spec
> implementation.
