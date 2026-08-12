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
     been fixed twice: multi-module discovery (every `src/` root) and true
     Scalameta parsing (2026-07-19 — 469 accurate rows on this repo, zero
     parse failures; nested types qualified as `Outer.Inner`, sealed-trait
     variants enumerated). It is the verification tool of choice: scan to a
     scratch file and diff against this document; never re-create this file
     from a raw scan (that would replace spec provenance with scan
     provenance).

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
| `MiddlewareName` | `String` | (none — plain opaque type) | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state |
| `StateCell.CellId` | `String` | (none — plain opaque type) | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state |
| `CheckpointStore.CheckpointId` | `String` | (transparent alias — not opaque) | `org.adk4s.orchestration.interrupt` | spec:add-harness-api-phase0/checkpoint-store-fpoly |

## Type Aliases

<!-- Type aliases that serve as the public-facing name for an underlying type.
     These are NOT opaque types — they are transparent `type X = Y` aliases.
     Listed here so subsequent specs can reuse them instead of re-creating. -->

| Type | Underlying | Package | Introduced By |
|------|-----------|---------|---------------|
| `JsonValue` | `smithy4s.Document` | `org.adk4s.core.json` | spec:migrate-json-codec/json-value-model |
| `ModelStep[F[_]]` | `Kleisli[F, ModelRequest[F], ModelResponse]` | `org.adk4s.harness` | spec:add-harness-api-phase0/agent-middleware |
| `ToolStep[F[_]]` | `Kleisli[F, ToolCallCtx, ToolCallOut]` | `org.adk4s.harness` | spec:add-harness-api-phase0/agent-middleware |
| `IOHarnessAgent` | `HarnessAgent[IO]` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/harness-agent |

## Sealed Traits and Enums

<!-- Closed type hierarchies that enable exhaustive pattern matching.
     Variants listed where extractable from the scan + source cross-check. -->

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `AdkError` | sealed trait | `LlmCallError`, `StructuredOutputError`, `TypeMismatchError`, `MissingFieldError`, `NodeNotFoundError`, `EdgeValidationError`, `MaxStepsExceededError`, `GraphCompiledError`, `GraphEntryMissingError`, `GraphEndNodesMissingError`, `ToolNotFoundError`, `ToolExecutionError`, `StateTypeMismatchError`, `NodeAlreadyExistsError`, `SourceNodeNotFoundError`, `NodeDoesNotExistError`, `FanInError`, `BranchTargetError`, `AgentInterruptedException`, `CheckpointNotFoundError`, `GenericError`, `NodeKeyError`, `StateDecodeError` | `org.adk4s.core.error` | pre-existing (`StateDecodeError` shipped by `spec:add-harness-api-phase0/harness-state`) |
| `ToolSchemaError` | sealed trait | `MissingRequiredField`, `TypeMismatch`, `InvalidEnumValue`, `DecodingFailed` | `org.adk4s.core.tools` | pre-existing |
| `StructuredToolCallError` | sealed trait | `UnknownTool`, `InvalidArguments`, `ExecutionFailed`, `ResultParsingFailed` | `org.adk4s.core.tools` | pre-existing |
| `InterruptSignal` | sealed trait (derives ReadWriter) | `Simple`, `Stateful`, `Composite` | `org.adk4s.core.interrupt` | pre-existing |
| `AgentEvent` | sealed trait | `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `Interrupted`, `ErrorOccurred`, `TokenDelta`, `MemoryRecalled`, `MemoryWritten` | `org.adk4s.core.interrupt` | pre-existing (`MemoryRecalled`/`MemoryWritten` shipped by archived `2026-07-19-add-memory-orchestration-hook`) |
| `AddressSegment` | sealed trait (derives ReadWriter) | `Agent`, `Tool` | `org.adk4s.core.interrupt` | pre-existing |
| `RunResult` | sealed trait | `Completed`, `Interrupted`, `Failed` | `org.adk4s.orchestration.agent` | pre-existing — **REUSED by this change** (`MemoryAwareRunner` pattern-matches on it) |
| `WIONode` | sealed trait | (multiple node variants — see `WIONode.scala`) | `org.adk4s.orchestration.wiograph` | pre-existing |
| `WIONodeModifier` | sealed trait | `CheckpointModifier`, `RetryModifier`, `InterruptionModifier` | `org.adk4s.orchestration.wiograph` | pre-existing |
| `WIOGraphError` | sealed trait | (see `WIOGraphError.scala`) | `org.adk4s.orchestration.wiograph` | pre-existing |
| `ChainBranch` | sealed trait | (see `ChainBranch.scala`) | `org.adk4s.orchestration.chain` | pre-existing |
| `CellVisibility` | enum | `Private`, `Inherited`, `Shared` | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state |
| `StackError` | sealed enum | `DuplicateCellId`, `DuplicateToolName` | `org.adk4s.harness` | spec:add-harness-api-phase0/middleware-stack |
| `HarnessResult` | sealed trait | `Completed`, `Interrupted`, `Failed` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/harness-agent |
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
| `OptimizeError` | enum | `UnknownPath`, `FrozenPath` | `org.adk4s.optimize` | spec:add-optimizable-surface/optimizable-surface |
| `Prog` (model) | sealed trait | `Pred`, `Plain`, `Sub`, `Coll` | `org.adk4s.verified.PredictorKernel` | spec:add-optimizable-surface/optimizable-surface (Ring 6 PureScala model) |
| `StackKernel.Visibility` (model) | sealed abstract class | `PrivateV`, `InheritedV`, `SharedV(merge)` | `org.adk4s.verified.StackKernel` | spec:add-harness-api-phase0/middleware-stack (Ring 6 PureScala model) |
| `EvalOutcome[+O]` | enum | `Succeeded(value: O)`, `Failed(error: Throwable)` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `EvalError` | sealed trait (extends Throwable) | `TooManyErrors[I, O](count, max, partial)` | `org.adk4s.eval` | spec:add-eval-core/eval-core |

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
| `MemoryRecalled` | `runPath: RunPath, query: String, hitCount: Int` | `org.adk4s.core.interrupt` | spec:memory-orchestration-events (AgentEvent variant) |
| `MemoryWritten` | `runPath: RunPath, episodes: Int` | `org.adk4s.core.interrupt` | spec:memory-orchestration-events (AgentEvent variant) |
| `InterruptResult` | `address: List[AddressSegment], data: ujson.Value` | `org.adk4s.core.interrupt` | pre-existing |
| `Completed` | `output: String, messages: List[Message]` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) — **REUSED by this change** (`MemoryAwareRunner` extracts `output` for `postTurn`) |
| `Interrupted` | `checkpointId: String, signal: InterruptSignal` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) — **REUSED by this change** (`MemoryAwareRunner` skips `postTurn` on this variant) |
| `Failed` | `error: AdkError` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) — **REUSED by this change** (`MemoryAwareRunner` skips `postTurn` on this variant) |
| `CheckpointState` | `messages: List[SerializableCheckpointMessage], interruptSignalJson: String, agentName: String` | `org.adk4s.orchestration.agent` | pre-existing (private[agent]) — **REUSED by spec:add-harness-api-phase0/checkpoint-store-fpoly** (v1 read-compat: `CheckpointStateV2.readWriter` decodes v1 payloads) |
| `CheckpointStateV2` | `version: Int, messages: List[CheckpointMessage], harnessState: Document, interruptSignalJson: String, agentName: String` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/checkpoint-store-fpoly |
| `CheckpointMessage` | `role: String, content: String, toolCalls: List[CheckpointToolCall], toolCallId: Option[String]` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/checkpoint-store-fpoly |
| `CheckpointToolCall` | `id: String, name: String, arguments: String` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/checkpoint-store-fpoly |
| `CheckpointMessageConverter` | (object with `toCheckpoint`/`fromCheckpoint`) | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/checkpoint-store-fpoly |
| `HarnessAgent[F[_]]` | final class with `generate: F[HarnessResult]`, `stream: Stream[F, StreamedChunk]`; `F[_]: Async` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/harness-agent |
| `HarnessAgent.Config[F[_]]` | `name: String, description: String, model: ChatModel[F], stack: MiddlewareStack[F], baseTools: List[InvokableTool[F]], basePrompt: Option[String], maxSteps: Int, emitter: Option[AgentEvent => F[Unit]]` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/harness-agent |
| `HarnessResult.Completed` | `finalAssistant: AssistantMessage, messages: List[Message], state: HarnessState` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/harness-agent |
| `HarnessResult.Interrupted` | `signal: InterruptSignal, messages: List[Message], state: HarnessState` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/harness-agent |
| `HarnessResult.Failed` | `error: AdkError, messages: List[Message], state: HarnessState` | `org.adk4s.orchestration.agent` | spec:add-harness-api-phase0/harness-agent |
| `DeterministicChatModel` | `ChatModel[IO]` double with seed-based script, `RecordedRequest` trace capture, no UUID/wall-clock | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `SimpleHarnessLoop` | minimal deterministic ReAct loop (harness-api + core only), `run`/`runBaseline`, returns `Observation` | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `Observation` | `finalAssistant: Option[AssistantMessage], finalState: HarnessState, requestTraces: List[RecordedRequest], outcome: Outcome` with `≍` observational equivalence | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `Observation.Outcome` | enum: `Completed`, `Interrupted`, `StepBudgetExhausted` | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `RecordedRequest` | `renderedSystemPrompt: Option[String], messages: List[Message], toolNames: List[String]` | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `TypedCell[A]` | sealed trait: `IntCell`, `StringCell`, `BoolCell`, `ListIntCell` — typed cell wrapper for law comparison without `Any` | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `SharedTypedCell[A]` | sealed trait extends `TypedCell[A]`: `MaxCell`, `MinCell`, `UnionCell` — shared cell with semilattice merge | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `AgentMiddlewareLaws` | class with L0–L10 Hedgehog `Property` values + case classes `L0Case`–`L10Case` | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `SemilatticeLaws` | class with L11 Hedgehog `Property` values + case classes `CommutativityCase[A]`, `AssociativityCase[A]`, `IdempotenceCase[A]`, `MergeBackCase` | `org.adk4s.harness.testkit` | spec:add-harness-api-phase0/middleware-laws |
| `SemilatticeKernel` | object: `commutative`, `associative`, `idempotent`, `isSemilattice` with `ensuring` clauses; `intMax`/`intMin` concrete merges with lemmas | `org.adk4s.verified` | spec:add-harness-api-phase0/middleware-laws (Ring 6) |
| `FieldMapping` | `from: FieldPath, to: FieldPath, fromNode: Option[NodeKey]` | `org.adk4s.orchestration.workflow` | pre-existing |
| `GraphConfig` | `maxRunSteps: Int, graphName: Option[String], maxParallelism: Int` | `org.adk4s.orchestration.graph` | pre-existing |
| `Prompt` | `conversation: Conversation` | `org.adk4s.structured.core` | pre-existing |
| `Episode` | `content: String, sourceType: SourceType, timestamp: Instant, groupId: Option[String], metadata: Map[String, String]` | `org.adk4s.memory` | pre-existing (shipped by archived `2026-07-05-add-memory-api`) — **REUSED by this change** (`postTurn` builds `Episode.conversation(...)` / `Episode(..., SourceType.ToolResult, ...)`) |
| `EpisodeOutcome` | `entitiesExtracted: Int, relationshipsCreated: Int, edgesInvalidated: Int, processingTimeMs: Long, errors: List[String], episodeId: Option[String]` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (`postTurn` returns `F[List[EpisodeOutcome]]`) |
| `MemoryHit` | `text: String, score: Double, validFrom: Option[Instant], validTo: Option[Instant], provenance: Option[String], payload: Map[String, String]` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (`preTurn` renders `List[MemoryHit]` into a context string) |
| `TemporalScope` | `asOf: Instant` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (`MemoryPolicy.scope: Option[TemporalScope]`) |
| `MemoryPolicy` | `recallK: Int, scope: Option[TemporalScope], writeUserInput: Boolean, writeAssistantOutput: Boolean, render: List[MemoryHit] => String` (private constructor; smart constructor enforces `recallK >= 0`) | `org.adk4s.orchestration.memory` | pre-existing (shipped by archived `2026-07-19-add-memory-orchestration-hook`) — **REUSED by this change** (`MemoryPolicy.default`, `policy.render`) |
| `PredictorState` | `instructions: String, demos: Vector[Demo], frozen: Boolean` | `org.adk4s.optimize` | spec:add-optimizable-surface/optimizable-surface |
| `Demo` | `input: ujson.Value, output: ujson.Value` | `org.adk4s.optimize` | spec:add-optimizable-surface/optimizable-surface |
| `PredictorPath` | `segments: Vector[String]` | `org.adk4s.optimize` | spec:add-optimizable-surface/optimizable-surface |
| `Predict0` | `state: PredictorState, template: PromptTemplate, schema: Schema, structured: StructuredLLM[F]` | `org.adk4s.optimize` | spec:add-optimizable-surface/optimizable-surface (Phase 0 placeholder) |
| `Example[I, O]` | `input: I, gold: O, id: Option[String], meta: Map[String, String]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Score` | `value: Double, feedback: Option[String]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `TraceEntry` | `path: String, input: ujson.Value, output: ujson.Value` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Trace` | `entries: Vector[TraceEntry]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `EvalConfig` | `parallelism: Int, failureScore: Double, maxErrors: Option[Int], seed: Long` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `EvalRow[I, O]` | `example: Example[I, O], outcome: EvalOutcome[O], score: Score` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `EvaluationResult[I, O]` | `score: Double, rows: Vector[EvalRow[I, O]]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `SemanticF1Judge` | `precision: Double, recall: Double, reasoning: String` | `org.adk4s.eval` | spec:add-eval-core/llm-judges |
| `CompleteAndGroundedJudge` | `completeness: Double, groundedness: Double, reasoning: String` | `org.adk4s.eval` | spec:add-eval-core/llm-judges |
| `PromptSection` | `name: String, body: String` | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state |
| `SystemPrompt` | `base: Option[String], sections: List[PromptSection]` | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state |
| `StateCell[A]` | `id: StateCell.CellId, visibility: CellVisibility, initial: A, merge: (A, A) => A, rw: ReadWriter[A]` (private constructor; factory `StateCell.apply[A](owner, name, initial, visibility?, merge?)` with `ReadWriter` context bound) | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state |
| `StateDecodeError` | `cellId: String, cause: Throwable` (extends `AdkError`, calls `initCause`) | `org.adk4s.core.error` | spec:add-harness-api-phase0/harness-state |
| `HarnessState` | (private constructor; `cells: Map[CellId, (StateCell[?], Any)]`; public methods: `get[A]`, `set[A]`, `update[A]`, `snapshot`; companion: `empty`, `initial`, `project`, `mergeBack`, `restore`) | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state |
| `ModelRequest[F[_]]` | `systemPrompt: Option[SystemPrompt], messages: List[Message], tools: List[InvokableTool[F]], options: CompletionOptions, state: HarnessState` | `org.adk4s.harness` | spec:add-harness-api-phase0/agent-middleware |
| `ModelResponse` | `completion: Completion, state: HarnessState` | `org.adk4s.harness` | spec:add-harness-api-phase0/agent-middleware |
| `ToolCallCtx` | `input: ToolInput, state: HarnessState` | `org.adk4s.harness` | spec:add-harness-api-phase0/agent-middleware |
| `ToolCallOut` | `output: ToolOutput, state: HarnessState` | `org.adk4s.harness` | spec:add-harness-api-phase0/agent-middleware |
| `MiddlewareStack[F[_]]` | (private constructor; `middlewares: List[AgentMiddleware[F]]`; public methods: `allCells`, `allTools`, `allSections(state)`, `beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`, `++`; companion: `empty[F]`, `validated[F]`) | `org.adk4s.harness` | spec:add-harness-api-phase0/middleware-stack |

## Service Traits

<!-- Tagless final service interfaces parameterised on F[_]. -->

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `ChatModel[F[_]]` | `F` | `generate`, `generate` (overloaded), `stream`, `stream` (overloaded), `streamContent`, `withConfig` | `org.adk4s.core.component` | pre-existing |
| `AgentMiddleware[F[_]]` | `F` (context bound `Applicative[F]`) | `name`, `stateCells`, `tools`, `promptSections(state)`, `beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`; companion: `id[F]` | `org.adk4s.harness` | spec:add-harness-api-phase0/agent-middleware |
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
| `CheckpointStore[F[_]]` | `F` (Sync constraint on `inMemory` factory) | `set(checkpointId: CheckpointId, data: Array[Byte]): F[Unit]`, `get(checkpointId: CheckpointId): F[Option[Array[Byte]]]`, `delete(checkpointId: CheckpointId): F[Unit]`, `keys: F[List[CheckpointId]]`, `inMemory[F[_]: Sync]: F[CheckpointStore[F]]` | `org.adk4s.orchestration.interrupt` | pre-existing — **GENERALIZED by spec:add-harness-api-phase0/checkpoint-store-fpoly** (was concrete `CheckpointStore`, now `CheckpointStore[F[_]]` with `CheckpointId` transparent alias) |
| `Optimizable[P]` | `P` (no F constraint) | `predictors(p: P): Vector[(PredictorPath, PredictorState)]`, `update(p, path, f): P`, `updateEither(p, path, f): Either[OptimizeError, P]`, `updateAll(p, f): P` | `org.adk4s.optimize` | spec:add-optimizable-surface/optimizable-surface |
| `HasPredictorState[Self]` | `Self` (no F constraint) | `state(self: Self): PredictorState`, `withState(self: Self, s: PredictorState): Self` | `org.adk4s.optimize` | spec:add-optimizable-surface/optimizable-surface |
| `Metric[F[_], I, O]` | `F` (Applicative bound) | `apply(gold: Example[I, O], pred: O, trace: Option[Trace]): F[Score]`, `map(f: Score => Score): Metric[F, I, O]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |

## Objects (Factories and Utilities)

<!-- Object-level factories and utility singletons in library modules. -->

| Object | Kind | Methods | Package | Introduced By |
|--------|------|---------|---------|---------------|
| `Evaluate` | object (factory) | `apply[F, I, O](program, devset, metric, config): F[EvaluationResult[I, O]]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Dataset` | object (factory) | `fromCsv[F, I, O](path, parse): F[Vector[Example[I, O]]]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Metrics` | object | `exactMatch[F]: Metric[F, String, String]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Judges` | object (factory) | `semanticF1[F](structured, threshold): Metric[F, String, String]`, `completeAndGrounded[F](structured, threshold): Metric[F, String, String]`, `defaultThreshold: Double` | `org.adk4s.eval` | spec:add-eval-core/llm-judges |
| `JsonValueCodec` | object (boundary adapter) | `toUjson(JsonValue): ujson.Value`, `fromUjson(ujson.Value): JsonValue` | `org.adk4s.core.json` | spec:migrate-json-codec/json-value-model |

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
| `CheckpointStore[F[_]]` | trait (`InMemoryCheckpointStore` for dev) | Persist interrupt/resume checkpoint state (F-polymorphic) | `org.adk4s.orchestration.interrupt` | pre-existing — **GENERALIZED by spec:add-harness-api-phase0/checkpoint-store-fpoly** (was concrete, now `F[_]`-polymorphic with `CheckpointId` alias) |
| `InMemoryAgentMemory[IO]` | `Ref[IO, Vector[Episode]]`-backed | Test double for `AgentMemory[IO]` | `org.adk4s.memory` | pre-existing (shipped) — **REUSED by this change** (hook tests use it as the memory) |
| `MemoryHook` | final class (pure recall/remember wrapper over `Option[AgentMemory[IO]]`) | No-op when memory absent; `preTurn` recalls + renders, `postTurn` remembers per `MemoryPolicy` | `org.adk4s.orchestration.memory` | pre-existing (shipped by archived `2026-07-19-add-memory-orchestration-hook`) — **REUSED by this change** (used internally by `MemoryAwareRunner`) |
| `MemoryAwareRunner` | final class (decorator over `AgentRunner`) | Runs `preTurn` before and `postTurn` after each turn; emits `MemoryRecalled`/`MemoryWritten` events; skips `postTurn` on `Interrupted`/`Failed` | `org.adk4s.orchestration.memory` | pre-existing (shipped by archived `2026-07-19-add-memory-orchestration-hook`) — **REUSED by this change** (`CrossRunMemoryExample` wraps `AgentRunner` with it) |

## Per-Change Provenance (application-edge and shipped concepts)

<!-- RENAMED 2026-08-08 by add-correctness-substratum. This section was headed
     "Concepts This Change Will Introduce" and its body said "the
     `add-cross-run-memory-example` change (this change)" — but that change was
     archived 2026-07-26. A change-scoped section had been stranded inside a
     PROJECT-scoped living document, so "this change" silently pointed at
     whichever change last edited the file. The subsections below are now
     explicitly per-change provenance records, matching the add-eval-core
     pattern already used further down.

     Policy (see the note under "Case Classes"): adk4s-examples types are
     application-edge and are NOT added to the main tables. They are recorded
     here so later specs know they exist and where to find them. -->

### add-cross-run-memory-example change (archived 2026-07-26) — application-edge

`MemoryPolicy` / `MemoryHook` / `MemoryAwareRunner` / `MemoryRecalled` /
`MemoryWritten` were moved to the main tables above as pre-existing (shipped by
the archived `2026-07-19-add-memory-orchestration-hook` change). The remaining
application-edge types:

| Type | Kind | Package | Introduced By |
|------|------|---------|---------------|
| `FileBackedAgentMemory[F[_]]` | final class (`AgentMemory[F]` double, JSON-lines persistence) | `org.adk4s.examples.memory` | spec:add-cross-run-memory-example (application-edge — omitted from main tables per policy) |
| `CrossRunMemoryExample` | `IOApp.Simple` object (CLI teach/recall/reset) | `org.adk4s.examples.memory` | spec:add-cross-run-memory-example (application-edge — omitted from main tables per policy) |
| `MemoryRetrieverExample` | `IOApp.Simple` object (retriever seam demo) | `org.adk4s.examples.memory` | spec:add-cross-run-memory-example (application-edge — omitted from main tables per policy) |
| JSON-lines `Episode` wire format | wire format (upickle `ReadWriter[Episode]`, one episode per line) | `org.adk4s.examples.memory` | spec:add-cross-run-memory-example (application-edge — omitted from main tables per policy) |
| `adk4s-examples % Test → adk4s-memory-testkit` | build wiring (Test scope) | `build.sbt` | spec:add-cross-run-memory-example (recorded in capability-profile.md module graph) |

### add-eval-core change — eval-core spec concepts

The following 14 concepts were introduced by `spec:add-eval-core/eval-core` and are now in the main tables above (Sealed Traits, Case Classes, Service Traits). Recorded here for provenance:

| Type | Kind | Package | Status |
|------|------|---------|--------|
| `Example[I, O]` | case class | `org.adk4s.eval` | shipped |
| `Score` | case class | `org.adk4s.eval` | shipped |
| `TraceEntry` | case class | `org.adk4s.eval` | shipped |
| `Trace` | case class | `org.adk4s.eval` | shipped |
| `EvalConfig` | case class | `org.adk4s.eval` | shipped |
| `EvalOutcome[+O]` | enum | `org.adk4s.eval` | shipped |
| `EvalRow[I, O]` | case class | `org.adk4s.eval` | shipped |
| `EvaluationResult[I, O]` | case class | `org.adk4s.eval` | shipped |
| `EvalError` | sealed trait (extends Throwable) | `org.adk4s.eval` | shipped |
| `Metric[F, I, O]` | trait | `org.adk4s.eval` | shipped |
| `Evaluate` | object (factory) | `org.adk4s.eval` | shipped |
| `Dataset` | object (factory) | `org.adk4s.eval` | shipped |
| `Metrics` | object | `org.adk4s.eval` | shipped |
| `MalformedLineException` | class (extends RuntimeException) | `org.adk4s.eval` | shipped |

Behavioral concept files created: `openspec/concepts/eval-harness.md`, `openspec/concepts/metric-contract.md`.

### add-eval-core change — llm-judges spec concepts

The following 3 concepts were introduced by `spec:add-eval-core/llm-judges` and are now in the main tables above (Case Classes, Objects). Recorded here for provenance:

| Type | Kind | Package | Status |
|------|------|---------|--------|
| `SemanticF1Judge` | case class | `org.adk4s.eval` | shipped |
| `CompleteAndGroundedJudge` | case class | `org.adk4s.eval` | shipped |
| `Judges` | object (factory) | `org.adk4s.eval` | shipped |

## Consistency Check

**Last verified: 2026-08-08** (by `add-correctness-substratum`), using the
SEMANTIC scanner — `scanner/scan.sh` (scala-cli 1.5.0 + Scalameta).

- **Scanner status**: ✅ **WORKS multi-module.** Run result:
  `7 opaque types, 63 sealed types, 294 case classes, 15 service traits,
  45 smithy models, 123 generators`; 0 parse failures reported.
- **Opaque types**: scanner set matches this inventory's table **exactly, 7/7**
  — `ToolSchema`, `RunPath`, `NodeKey`, `FieldPath`, `StateCell.CellId`,
  `MiddlewareName`, `Schema`.
- **Opaque type constraints**: confirmed NO Iron/refined library in the stack;
  all 7 are plain newtypes without constraints.
- **Package paths**: all recorded package paths match real `package` clauses
  in the scanned sources (`org.adk4s.core.*`, `org.adk4s.orchestration.*`,
  `org.adk4s.memory`, `org.adk4s.memory.testkit`, `org.adk4s.structured.*`,
  `org.adk4s.harness`, `org.adk4s.eval`).
- **Memory-api entries**: cross-checked against
  `adk4s-memory-api/src/main/scala/org/adk4s/memory/*.scala` — `AgentMemory`,
  `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`,
  `InMemoryAgentMemory`, `MemoryRetriever` all present and shipped.
- **Generators**: `adk4s-memory-api/src/test/scala/org/adk4s/memory/Generators.scala`
  contains every generator listed above.
- **Discrepancies in the TABLES**: none.

<!-- CORRECTED 2026-08-08. Two prose claims here were stale and one of them was
     actively misleading:

     (a) "the five opaque types" — the table has held SEVEN since
         add-harness-api-phase0 added MiddlewareName and StateCell.CellId. The
         table was current; the sentence counting it was not.

     (b) "The semantic scanner's 0-result run is a known limitation (it expects
         a top-level src/); the manual scan is authoritative."
         FALSE as of schema v6, which fixed the scanner to discover every src/
         root — the changelog names this exact defect ("previously a silent
         empty scan on multi-module builds"). Re-verified today: the scanner
         runs clean and finds 294 case classes. The obsolete note had the
         effect of steering every later verification to a manual scan while a
         working semantic one sat unused. A recorded limitation must be
         re-tested, not inherited. -->

**Counting note**: scanner totals exceed this inventory's row count by design —
the inventory omits `adk4s-examples` application-edge types (policy under
"Case Classes") and test-only helpers. Only the omissions are expected to
differ; a divergence in the main tables is a defect.

## Behavioral Concepts (registry pass)

<!-- The project has a concept registry at openspec/concepts/ (31 concepts).
     This pass runs AGAINST the registry (does not regenerate it).
     REFRESHED 2026-08-08 by add-correctness-substratum — the previous entry
     recorded a run from 2026-07-18 and carried two flags that had already
     been resolved by the shipped code (see below). -->

**registry-check.sh** (run 2026-08-08): `OK (740 implementation-map tokens verified, 15 spec concept references checked, 2 weak binding(s) to tighten)`

*(Previous entry, 2026-07-18: `604 tokens, 0 spec refs`. The token count grew with `add-eval-core`, `add-optimizable-surface`, `migrate-json-codec` and the in-flight `add-harness-api-phase0`; the spec-reference count is nonzero now because `add-harness-api-phase0` has specs on disk.)*

The 2 WEAK rows are pre-existing in `react-agent.md` (`isDefined`, `foreach` cited against `ReactAgent.scala` but they are stdlib `Option` methods, not identifiers declared there). Not blocking; tighten by citing their own file or dropping them from the map.

**Stale implementation-map rows**: none.

**Previously flagged, now RESOLVED** — both were left standing in this document after the code that resolved them shipped:

- ~~NEW candidate concept `MemoryHook` / `MemoryAwareRunner` — "does not yet have a registry file"~~ → **RESOLVED**: `openspec/concepts/memory-aware-runner.md` exists (verified 2026-08-08).
- ~~EXTENDED concept `AgentEventStream` — "updating agent-event-stream.md is PART OF implementing the events spec"~~ → **RESOLVED**: `openspec/concepts/agent-event-stream.md` carries the `MemoryRecalled` / `MemoryWritten` variants (4 references, verified 2026-08-08).

<!-- Both flags were accurate when written and became false when the work
     landed, but nothing re-read them — the same recorded-but-never-checked
     shape the schema v11 changelog describes. A resolved flag must be cleared
     by the change that resolves it; until then this section reports work as
     outstanding that is in fact done. -->

**Unregistered actions / syncs / state components flagged for human review**: none outstanding.

> Registry concept count: **31** (`openspec/concepts/*.md`, excluding
> `README.md`) as of 2026-08-08. The previous note in this section said 25.
