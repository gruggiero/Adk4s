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

<!-- Iron IS present in the stack (iron + iron-cats 3.3.2 + iron-upickle; see
     capability-profile.md). The `add-iron-refined-types` change migrated the
     project's newtypes to Iron `RefinedType` with compile-time literal checking
     and `refineEither` runtime construction. Rows below record the constraint
     expression for each. Plain `opaque type` newtypes (no Iron constraint) are
     marked "(none — plain opaque type)". -->

| Type | Underlying | Constraint | Package | Introduced By |
|------|-----------|------------|---------|---------------|
| `RunPath` | `List[RunStep]` | (none — plain opaque type) | `org.adk4s.core.interrupt` | pre-existing |
| `NodeKey` | `String` | `NonEmpty & Not[Reserved]` (Iron RefinedType; compile-time for literals, `refineEither`/`from` at runtime) | `org.adk4s.core.types` | pre-existing; refined by `spec:add-iron-refined-types/core-types` |
| `ReservedNodeKey` | enum (`Start`, `End`) | (none — distinct type from NodeKey) | `org.adk4s.core.types` | added by `spec:add-iron-refined-types/core-types` |
| `Positive` | `Int` | `numeric.Positive` (Iron) | `org.adk4s.core.types` | added by `spec:add-iron-refined-types/core-types` |
| `NonNegative` | `Int` | `numeric.Positive0` (Iron) | `org.adk4s.core.types` | added by `spec:add-iron-refined-types/core-types` |
| `FieldPath` | `Vector[String]` | (none — plain opaque type) | `org.adk4s.core.types` | pre-existing |
| `ToolSchema[A]` | `ToolSchema.SchemaData[A]` | (none — plain opaque type) | `org.adk4s.core.tools` | pre-existing |
| `Schema[A]` | `Schema.SchemaData[A]` | (none — plain opaque type) | `org.adk4s.structured.core` | pre-existing |
| `MiddlewareName` | `String` | `NonEmpty` (Iron RefinedType) | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state; migrated by add-iron-refined-types/harness-state |
| `StateCell.CellId` | `String` | `NonEmpty & Match["[^/]+/[^/]+"]` (Iron RefinedType) | `org.adk4s.harness` | spec:add-harness-api-phase0/harness-state; migrated by add-iron-refined-types/harness-state |
| `CheckpointStore.CheckpointId` | `String` | `NonEmpty` (Iron RefinedType) | `org.adk4s.orchestration.interrupt` | spec:add-harness-api-phase0/checkpoint-store-fpoly; migrated by add-iron-refined-types/checkpoint-store-fpoly |
| `CallKey` | `String` | (none — plain opaque type; well-formedness guaranteed by `CallKey.fromCanonical` digest function) | `org.adk4s.record` | spec:add-adk4s-record/call-key |
| `RolloutId` | `String` | `NonEmpty` (Iron RefinedType; `refineEither` returns `Either[ConfigError, RolloutId]`) | `org.adk4s.record` | spec:add-adk4s-record/call-key |

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
| `AdkError` | sealed trait | `LlmCallError`, `StructuredOutputError`, `TypeMismatchError`, `MissingFieldError`, `NodeNotFoundError`, `EdgeValidationError`, `MaxStepsExceededError`, `GraphCompiledError`, `GraphEntryMissingError`, `GraphEndNodesMissingError`, `ToolNotFoundError`, `ToolExecutionError`, `StateTypeMismatchError`, `NodeAlreadyExistsError`, `SourceNodeNotFoundError`, `NodeDoesNotExistError`, `FanInError`, `BranchTargetError`, `AgentInterruptedException`, `CheckpointNotFoundError`, `GenericError`, `NodeKeyError`, `StateDecodeError`, `ConfigError`, `GraphCompilationError` | `org.adk4s.core.error` | pre-existing (`StateDecodeError` shipped by `spec:add-harness-api-phase0/harness-state`); `ConfigError`, `GraphCompilationError` added by `spec:add-iron-refined-types/error-hierarchy-dedup` |
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
| `RequestMutation` | enum | `ChangeProvider`, `ChangeModel`, `ReorderMessages`, `ChangeTemperature`, `ChangeMaxTokens`, `ChangeTopP`, `ChangeStopSequences`, `AddTool`, `RemoveTool`, `ChangeToolSchema`, `ChangeSystemPrompt`, `ChangeRolloutId` | `org.adk4s.record` | spec:add-adk4s-record/call-key |
| `NonAffectingMutation` | enum | `RegenerateToolCallIds`, `ChangeProviderRequestId`, `ChangeLatency`, `ChangeTokenUsage`, `ChangeTimestamp` | `org.adk4s.record` | spec:add-adk4s-record/call-key |
| `CallKind` | generated enum (Smithy IDL) | `MODEL`, `TOOL`, `EMBEDDING` | `org.adk4s.record.canonical` | spec:add-adk4s-record/call-key (generated from `canonical_form.smithy` via smithy4s codegen) |
| `CanonicalBody` | generated union (Smithy IDL) | `ModelCase(ModelBody)`, `ToolCase(ToolBody)`, `EmbeddingCase(EmbeddingBody)` | `org.adk4s.record.canonical` | spec:add-adk4s-record/call-key (generated from `canonical_form.smithy`) |
| `CanonicalMessage` | generated union (Smithy IDL) | `UserCase(UserMessage)`, `SystemCase(SystemMessage)`, `AssistantCase(AssistantMessage)`, `ToolCase(ToolMessage)` | `org.adk4s.record.canonical` | spec:add-adk4s-record/call-key (generated from `canonical_form.smithy`) |
| `RecorderError` | sealed trait (extends AdkError) | `SinkWriteFailed(cause: Throwable)`, `SinkReadFailed(cause: Throwable)`, `CodecFailed(message: String, input: String)` | `org.adk4s.core.error` | spec:add-adk4s-record/recorder-sink |
| `CallRecord` | generated union (Smithy IDL) | `SucceededCase(SucceededRecord)`, `FailedCase(FailedRecord)` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `RecordPayload` | generated union (Smithy IDL) | `ModelCase(ModelPayload)`, `ToolCase(ToolPayload)`, `EmbeddingCase(EmbeddingPayload)` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `Classification` | generated enum (Smithy IDL) | `PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `RESTRICTED` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |

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
| `MemoryPolicy` | `recallK: NonNegative (Int :| Positive0), scope: Option[TemporalScope], writeUserInput: Boolean, writeAssistantOutput: Boolean, render: List[MemoryHit] => String` (private constructor; `applyEither` returns `Either[ConfigError, MemoryPolicy]`; throwing `apply` delegates to `applyEither`) | `org.adk4s.orchestration.memory` | pre-existing (shipped by archived `2026-07-19-add-memory-orchestration-hook`) — **REUSED by this change** (`MemoryPolicy.default`, `policy.render`); migrated by add-iron-refined-types/memory-orchestration-hook |
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
| `ToolDef` | `name: String, description: String, schemaJson: String` | `org.adk4s.record` | spec:add-adk4s-record/call-key |
| `ModelCallRequest` | `provider: String, model: String, conversation: Conversation, tools: List[ToolDef], systemPrompt: String, options: CompletionOptions, rollout: Option[RolloutId], outputSchema: Option[String], stopSequences: List[String], providerRequestId: Option[String], latencyMs: Option[Long], tokenUsage: Option[Long], timestamp: Option[Long]` | `org.adk4s.record` | spec:add-adk4s-record/call-key |
| `SucceededRecord` | generated case class (Smithy IDL): `key: String, seq: Long, kind: CallKind, payload: RecordPayload, classification: Classification` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `FailedRecord` | generated case class (Smithy IDL): `key: String, seq: Long, kind: CallKind, error: RecordedError, classification: Classification` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `ModelPayload` | generated case class (Smithy IDL): `content: String, finishReason: Option[String], toolCalls: Option[List[ModelToolCall]], promptTokens: Option[Int], completionTokens: Option[Int], totalTokens: Option[Int]` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `ToolPayload` | generated case class (Smithy IDL): `name: String, arguments: Document, callId: String, isError: Boolean` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `EmbeddingPayload` | generated case class (Smithy IDL): `model: String, tokenCount: Option[Int], dimensions: Option[Int]` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `RecordedError` | generated case class (Smithy IDL): `errorType: String, message: String, cause: Option[String]` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |
| `ModelToolCall` | generated case class (Smithy IDL): `id: String, name: String, arguments: Document` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink (generated from `record_form.smithy`) |

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
| `Recorder[F[_]]` | `F` (Applicative on noop, Concurrent on inMemory, Async on file) | `lookup(key: CallKey): F[Option[CallRecord]]`, `record(key: CallKey, outcome: CallRecord): F[Unit]`, `nextSeq: F[Long]`; companion: `noop[F: Applicative]`, `inMemory[F: Concurrent](maxEntries: Positive): F[Recorder[F]]`, `file[F: Async](path: Path): Resource[F, Recorder[F]]` | `org.adk4s.record` | spec:add-adk4s-record/recorder-sink — implementations: `NoopRecorder`, `InMemoryRecorder`, `FileRecorder` |

## Objects (Factories and Utilities)

<!-- Object-level factories and utility singletons in library modules. -->

| Object | Kind | Methods | Package | Introduced By |
|--------|------|---------|---------|---------------|
| `Evaluate` | object (factory) | `apply[F, I, O](program, devset, metric, config): F[EvaluationResult[I, O]]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Dataset` | object (factory) | `fromCsv[F, I, O](path, parse): F[Vector[Example[I, O]]]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Metrics` | object | `exactMatch[F]: Metric[F, String, String]` | `org.adk4s.eval` | spec:add-eval-core/eval-core |
| `Judges` | object (factory) | `semanticF1[F](structured, threshold): Metric[F, String, String]`, `completeAndGrounded[F](structured, threshold): Metric[F, String, String]`, `defaultThreshold: Double` | `org.adk4s.eval` | spec:add-eval-core/llm-judges |
| `JsonValueCodec` | object (boundary adapter) | `toUjson(JsonValue): ujson.Value`, `fromUjson(ujson.Value): JsonValue` | `org.adk4s.core.json` | spec:migrate-json-codec/json-value-model |
| `Canonicalization` | object (pure functions) | `fromModelCall(ModelCallRequest): CanonicalForm`, `fromToolCall(ToolInput): CanonicalForm`, `fromEmbedding(String, String): CanonicalForm` | `org.adk4s.record.canonical` | spec:add-adk4s-record/call-key |
| `CanonicalFormOps` | object (convenience constructors) | `from(ModelCallRequest): CanonicalForm`, `fromToolCall(ToolInput): CanonicalForm`, `fromEmbedding(String, String): CanonicalForm`, `fromJson(String): Either[String, CanonicalForm]` | `org.adk4s.record` | spec:add-adk4s-record/call-key |

## Smithy Models

<!-- Smithy IDL structures driving smithy4s codegen. Production domain types
     live in adk4s-record/src/main/smithy/. Test fixtures live in
     structured-llm-test-models/src/main/smithy/. -->

| Model | Kind | Location | Introduced By |
|-------|------|----------|---------------|
| `Resume`, `MarketingCampaign`, `Product`, `Traveler`, `TravelBooking`, `Invoice`, `Attendee`, `EventRegistration`, `Address`, `Shipment`, `SupportTicket`, `Order`, `LoyaltyProgram`, `Patient`, `HealthcareAppointment`, `ProjectTask`, `VehicleInspection`, `Payment`, `CustomerProfile`, `InventoryItem`, `HRCandidate`, `BankTransaction`, `SubscriptionPlan`, `InsuranceClaim` | structure | `structured-llm-test-models/src/main/smithy/*.smithy` | pre-existing |
| `examples.smithy` shapes | structure | `structured-llm-test-models/src/main/smithy/examples.smithy` | pre-existing |
| `CanonicalForm` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `CallKind` | enum | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `CanonicalBody` | union | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `ModelBody` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `ToolBody` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `EmbeddingBody` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `CanonicalMessage` | union | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `UserMessage` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `SystemMessage` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `AssistantMessage` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `ToolMessage` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `CanonicalToolCall` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `CanonicalToolDef` | structure | `adk4s-record/src/main/smithy/canonical_form.smithy` | spec:add-adk4s-record/call-key |
| `Classification` | enum | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `CallRecord` | union | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `SucceededRecord` | structure | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `FailedRecord` | structure | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `RecordPayload` | union | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `ModelPayload` | structure | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `ToolPayload` | structure | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `EmbeddingPayload` | structure | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `RecordedError` | structure | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |
| `ModelToolCall` | structure | `adk4s-record/src/main/smithy/record_form.smithy` | spec:add-adk4s-record/recorder-sink |

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
| `genToolDef` | `Gen[ToolDef]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genModelRequest` | `Gen[ModelCallRequest]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genRolloutId` | `Gen[RolloutId]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genConversation` | `Gen[Conversation]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genConversationWithToolCalls` | `Gen[Conversation]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genMessage`, `genTurn`, `genUserMessage`, `genSystemMessage`, `genAssistantMessageNoTools`, `genAssistantMessageWithTools`, `genToolCall`, `genToolMessage` | `Gen[Message]`, `Gen[List[Message]]`, etc. | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genRequestMutationPair`, `genRequestMutation`, `removeToolGen`, `changeSchemaGen` | `Gen[(ModelCallRequest, RequestMutation)]`, `Gen[RequestMutation]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genNonAffectingMutationPair`, `genNonAffectingMutation` | `Gen[(ModelCallRequest, NonAffectingMutation)]`, `Gen[NonAffectingMutation]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genRolloutPair` | `Gen[(ModelCallRequest, Option[RolloutId], Option[RolloutId])]` | `adk4s-record/src/test/.../CallKeySpec.scala` | spec:add-adk4s-record/call-key |
| `genCallKey` | `Gen[CallKey]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genClassification` | `Gen[Classification]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genCallKind` | `Gen[CallKind]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genRecordedError` | `Gen[RecordedError]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genModelPayload` | `Gen[ModelPayload]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genToolPayload` | `Gen[ToolPayload]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genEmbeddingPayload` | `Gen[EmbeddingPayload]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genRecordPayload` | `Gen[RecordPayload]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genSucceededRecord` | `Gen[CallRecord]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genFailedRecord` | `Gen[CallRecord]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genCallRecord` | `Gen[CallRecord]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genSeqOps` | `Gen[List[RecorderOp]]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |
| `genBoundedOps` | `Gen[List[(CallKey, CallRecord)]]` | `adk4s-record/src/test/.../RecorderSpec.scala` | spec:add-adk4s-record/recorder-sink |

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

### add-adk4s-record change — call-key spec concepts

The following concepts were introduced by `spec:add-adk4s-record/call-key` and are now in the main tables above. Recorded here for provenance:

| Type | Kind | Package | Status |
|------|------|---------|--------|
| `CallKey` | opaque type | `org.adk4s.record` | shipped |
| `RolloutId` | Iron RefinedType (`String :| NonEmpty`) | `org.adk4s.record` | shipped |
| `CallKind` | generated enum (Smithy IDL) | `org.adk4s.record.canonical` | shipped |
| `CanonicalForm` | generated case class (Smithy IDL) | `org.adk4s.record.canonical` | shipped |
| `CanonicalBody` | generated union (Smithy IDL) | `org.adk4s.record.canonical` | shipped |
| `CanonicalMessage` | generated union (Smithy IDL) | `org.adk4s.record.canonical` | shipped |
| `ModelBody`, `ToolBody`, `EmbeddingBody` | generated structures (Smithy IDL) | `org.adk4s.record.canonical` | shipped |
| `UserMessage`, `SystemMessage`, `AssistantMessage`, `ToolMessage` | generated structures (Smithy IDL) | `org.adk4s.record.canonical` | shipped |
| `CanonicalToolCall`, `CanonicalToolDef` | generated structures (Smithy IDL) | `org.adk4s.record.canonical` | shipped |
| `RequestMutation` | enum | `org.adk4s.record` | shipped |
| `NonAffectingMutation` | enum | `org.adk4s.record` | shipped |
| `ToolDef` | case class | `org.adk4s.record` | shipped |
| `ModelCallRequest` | case class | `org.adk4s.record` | shipped |
| `Canonicalization` | object (pure functions) | `org.adk4s.record.canonical` | shipped |
| `CanonicalFormOps` | object (convenience constructors) | `org.adk4s.record` | shipped |
| `normalizeToolCallIds` | function | `org.adk4s.record.canonical` | shipped |
| `keyVersion` | val (`Int = 1`) | `org.adk4s.record` | shipped |

### add-adk4s-record change — recorder-sink spec concepts

The following concepts were introduced by `spec:add-adk4s-record/recorder-sink` and are now in the main tables above. Recorded here for provenance:

| Type | Kind | Package | Status |
|------|------|---------|--------|
| `RecorderError` | sealed trait (extends AdkError) | `org.adk4s.core.error` | shipped |
| `RecorderError.SinkWriteFailed` | case class | `org.adk4s.core.error` | shipped |
| `RecorderError.SinkReadFailed` | case class | `org.adk4s.core.error` | shipped |
| `RecorderError.CodecFailed` | case class | `org.adk4s.core.error` | shipped |
| `Recorder[F[_]]` | service trait | `org.adk4s.record` | shipped |
| `NoopRecorder[F[_]]` | class (implements Recorder) | `org.adk4s.record` | shipped |
| `InMemoryRecorder[F[_]]` | class (implements Recorder) | `org.adk4s.record` | shipped |
| `FileRecorder[F[_]]` | class (implements Recorder) | `org.adk4s.record.file` | shipped |
| `RecorderInstances` | object (factory) | `org.adk4s.record` | shipped |
| `CallRecord` | generated union (Smithy IDL) | `org.adk4s.record` | shipped |
| `SucceededRecord` | generated case class (Smithy IDL) | `org.adk4s.record` | shipped |
| `FailedRecord` | generated case class (Smithy IDL) | `org.adk4s.record` | shipped |
| `RecordPayload` | generated union (Smithy IDL) | `org.adk4s.record` | shipped |
| `ModelPayload` | generated case class (Smithy IDL) | `org.adk4s.record` | shipped |
| `ToolPayload` | generated case class (Smithy IDL) | `org.adk4s.record` | shipped |
| `EmbeddingPayload` | generated case class (Smithy IDL) | `org.adk4s.record` | shipped |
| `RecordedError` | generated case class (Smithy IDL) | `org.adk4s.record` | shipped |
| `ModelToolCall` | generated case class (Smithy IDL) | `org.adk4s.record` | shipped |
| `Classification` | generated enum (Smithy IDL) | `org.adk4s.record` | shipped |
| `Redaction` | type alias (`RecordPayload => RecordPayload`) | `org.adk4s.record` | spec:add-adk4s-record/recorded-wrappers |
| `RecordedChatModel` | object/factory (`ChatModel[F]` decorator) | `org.adk4s.record` | spec:add-adk4s-record/recorded-wrappers |
| `RecordedEmbedder` | object/factory (`Embedder[F]` decorator) | `org.adk4s.record` | spec:add-adk4s-record/recorded-wrappers |
| `RecordingToolMiddleware` | object/factory (`ToolMiddleware` factory) | `org.adk4s.record` | spec:add-adk4s-record/recorded-wrappers |
| `ModelPayloadOps` | object (convenience constructor) | `org.adk4s.record` | spec:add-adk4s-record/recorded-wrappers |
| `RecorderLaws` | class (Hedgehog property testkit, 13 laws RL0–RL12) | `org.adk4s.record` | spec:add-adk4s-record/recorder-laws |

## Consistency Check

**Last verified: 2026-08-15** (by `add-adk4s-record/recorder-sink`), using the
SEMANTIC scanner — `scanner/scan.sh` (scala-cli 1.5.0 + Scalameta).

- **Scanner status**: ✅ **WORKS multi-module.** Run result:
  `6 opaque types, 76 sealed types, 346 case classes, 18 service traits,
  62 smithy models, 228 generators`; 0 parse failures reported.
  (Note: scanner counts source files only; generated smithy4s types in
  `target/` are not counted — they are tracked via the Smithy Models table.)
- **Delta from call-key (spec 2)**: +1 sealed trait (RecorderError), +1 service trait (Recorder), +7 smithy models (record_form.smithy structures), +13 generators (RecorderSpec.scala).
- **Package paths**: all recorded package paths match real `package` clauses
  in the scanned sources (`org.adk4s.core.*`, `org.adk4s.orchestration.*`,
  `org.adk4s.memory`, `org.adk4s.memory.testkit`, `org.adk4s.structured.*`,
  `org.adk4s.harness`, `org.adk4s.eval`, `org.adk4s.record`, `org.adk4s.record.file`).
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

### port-scanner-to-probatio change — probatio-core spec concepts

The following concepts were introduced by `spec:port-scanner-to-probatio/probatio-core`:

| Type | Kind | Package | Status |
|------|------|---------|--------|
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` | shipped |
| `Ring` | enum (R0–R9, Manual) | `org.sinemenda.probatio.core` | shipped |
| `ContractViolation` | sealed trait (15 clause variants — 12 original + 3 provenance clauses added by spec:port-scanner-to-probatio/provenance-validation) | `org.sinemenda.probatio.core` | shipped |
| `LedgerRecord` | final case class (private[core] constructor); spec 7 joined `optional: LedgerRecordOptional` as a REQUIRED field — a record that cannot state what it observed is unrepresentable | `org.sinemenda.probatio.core` | shipped; field-joined by `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `LedgerRecordOptional` | final case class (sha256, digest, wallTime, source, session — Option types); was orphaned pre-spec-7, now attached as `LedgerRecord.optional`; `extract` enforces the TYPE of each present field | `org.sinemenda.probatio.core` | shipped; attached by `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `Ledger.LedgerData` | final case class (immutable, append-only) | `org.sinemenda.probatio.core` | shipped |
| `UnresolvedReason` | enum (Unbound, Unresolved, Undischarged, Unattributable, Failed) | `org.sinemenda.probatio.core` | shipped |
| `UnresolvedEntry` | final case class | `org.sinemenda.probatio.core` | shipped |
| `UnmappedObligation` | final case class | `org.sinemenda.probatio.core` | shipped |
| `ChainStateReport` | final case class (private constructor; verdict-path factory `from(prePass: PrePassOutcome.Completed, …)` — a report cannot be built from a `DidNotRun`; `fromCounts` `private[probatio]` for wire reconstruction only) | `org.sinemenda.probatio.core` | shipped; construction narrowed by `spec:repair-probatio-cutover/chain-state-undetermined-fidelity` |
| `ChainStateUndetermined` | final case class (change, baseline, reason: `UndeterminedReason` — carries no measurement counts) | `org.sinemenda.probatio.core` | shipped; re-shaped by `spec:repair-probatio-cutover/chain-state-undetermined-fidelity` |
| `ChainState.Requirement` | final case class | `org.sinemenda.probatio.core` | shipped |
| `Verdict` | enum (Bound, Resolved, Unbound) | `org.sinemenda.probatio.core` | shipped |
| `CheckId` | enum (F1–F10) | `org.sinemenda.probatio.core` | shipped |
| `RequirementVerdict` | final case class | `org.sinemenda.probatio.core` | shipped |
| `LintWarning` | final case class | `org.sinemenda.probatio.core` | shipped |
| `LintReport` | final class, private constructor (verdicts, findings: List[CheckOutcome], applicability, resolvedRows, unresolvableRows, requirementRows — `lintSuccess` derived from findings) | `org.sinemenda.probatio.core` | shipped; re-shaped by `spec:complete-probatio-cutover/spec-lint-engine` |
| `HookSpecificOutput` | final case class | `org.sinemenda.probatio.core` | shipped |
| `GatePayload` | final case class | `org.sinemenda.probatio.core` | shipped |
| `InstallRootScan` | final case class (rootPath, state: InstallRootState — four-state root read, was a two-field stamp record) | `org.sinemenda.probatio.core` | shipped; modified by `spec:complete-probatio-cutover/live-fact-banner` |
| `DriftWarning` | sealed trait (VersionMismatch, PreRenameStamp, NoStampDeclared, Unreadable — two variants added) | `org.sinemenda.probatio.core` | shipped; extended by `spec:complete-probatio-cutover/live-fact-banner` |
| `DriftScanResult` | final case class | `org.sinemenda.probatio.core` | shipped |
| `BannerInputs` | final class (private constructor; only `BannerInputs.from(facts: RepositoryFacts)` — hand-constructed literals cannot reach the engine) | `org.sinemenda.probatio.core` | shipped; re-shaped by `spec:complete-probatio-cutover/live-fact-banner` |
| `BannerEngine` | object (`render`: BannerInputs → BannerOutput — pure function; method name corrected from `assembleBanner` 2026-08-29 by spec:complete-probatio-cutover/inventory-check) | `org.sinemenda.probatio.core` | shipped |
| `ActiveChangeWithChainState` | final case class (name, artifacts: FactRead[ArtifactScan], chainState: Either[ChainStateUndetermined, ChainStateReport] — "never attempted" unrepresentable) | `org.sinemenda.probatio.core` | shipped; re-shaped by `spec:complete-probatio-cutover/live-fact-banner` |
| `BannerOutput` | final case class | `org.sinemenda.probatio.core` | shipped |
| `MetalsClient.LspMessage` | final case class | `org.sinemenda.probatio.core` | shipped |
| `MetalsClient.MetalsError` | sealed trait (FramingError, HandshakeFailed, Timeout) | `org.sinemenda.probatio.core` | shipped |
| `MetalsClient.MetalsSession` | final case class | `org.sinemenda.probatio.core` | shipped |
| `Subcommand` | enum (11 cases: Gate, SpecLint, ChainState, Ledger, Checkpoint, Reconcile, DangerScan, Metals, InstallSkills, InstallHooks, Graph — shrunk from 16 by removing 6 unported tools: RegistryCheck, Scan, RemovalAudit, ImpactScan, ConceptScanner, Graph; `Graph` restored 2026-09-23 by `repair-probatio-cutover/graph-tool-port` — the traceability tool is now ported) | `org.sinemenda.probatio.cli` | shipped; shrunk by `spec:complete-probatio-cutover/cli-entrypoint-contract`, widened by `spec:repair-probatio-cutover/graph-tool-port` |
| `ExitCode` | enum (3 cases: Clean, Finding, Undetermined) | `org.sinemenda.probatio.cli` | shipped |
| `CliError` | sealed abstract class (UnknownSubcommand, MissingValue, InvalidEnum, UnknownFlag) | `org.sinemenda.probatio.cli` | shipped |
| `MulticallDispatch` | object (resolveAndSplit: InvocationName + ProgramArgs → Either[CliError, (Subcommand, ProgramArgs)] — replaces old resolve(argv0, argv1)) | `org.sinemenda.probatio.cli` | shipped; updated by `spec:complete-probatio-cutover/cli-entrypoint-contract` |
| `ProgramArgs` | opaque type over List[String] (constructible only via fromRuntime/fromFixture — no public apply) | `org.sinemenda.probatio.cli` | `spec:complete-probatio-cutover/cli-entrypoint-contract` |
| `InvocationName` | opaque type over String (constructible only via fromRuntime: Either[String, InvocationName] — no public apply) | `org.sinemenda.probatio.cli` | `spec:complete-probatio-cutover/cli-entrypoint-contract` |
| `HelpOutput` | final case class (subcommand, flags: List[FlagHelp], exitCodes: List[ExitCodeDoc]) | `org.sinemenda.probatio.cli` | shipped |
| `HelpRegistry` | object (helpFor: Subcommand → HelpOutput) | `org.sinemenda.probatio.cli` | shipped |
| `ProbatioMain` | object (`dispatch`: (InvocationName, ProgramArgs) → Int — multicall entry point; `main`: Array[String] → Unit — JVM/native entry point, extracts invocation name from runtime) | `org.sinemenda.probatio.cli` | shipped; updated by `spec:complete-probatio-cutover/cli-entrypoint-contract` |
| `FlagHelp` | final case class (name, description, default) | `org.sinemenda.probatio.cli` | shipped |
| `ExitCodeDoc` | final case class (code, label, condition) | `org.sinemenda.probatio.cli` | shipped |
| `CliErrorRender` | object (render: CliError → String) | `org.sinemenda.probatio.cli` | shipped |
| `GateCmd` / `SpecLintCmd` / `ChainStateCmd` / `LedgerCmd` / `CheckpointCmd` / `ReconcileCmd` / `DangerScanCmd` / `MetalsCmd` / `InstallSkillsCmd` / `InstallHooksCmd` | objects (run: Array[String] → Outcome[Int] — subcommand entrypoints; 6 unported entrypoints removed: RegistryCheckCmd, ScanCmd, RemovalAuditCmd, ImpactScanCmd, ConceptScannerCmd, GraphCmd; SpecLintCmd implemented by spec-lint-engine: positional change-dir, `--context-only`, `--artifacts`, `--format json`, nested `specs/` discovery) | `org.sinemenda.probatio.cli` | shipped; shrunk by `spec:complete-probatio-cutover/cli-entrypoint-contract`; SpecLintCmd implemented by `spec:complete-probatio-cutover/spec-lint-engine`; LedgerCmd `validate`→`verify` + `run` rework and CheckpointCmd `report`/`regenerate-tasks` implemented by `spec:complete-probatio-cutover/ledger-checkpoint-parity` |

### port-scanner-to-probatio change — sbt-plugin spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `ProbatioPlugin` | sbt AutoPlugin (Scala 2.12, object extends AutoPlugin) | `org.sinemenda.probatio.plugin` | shipped |
| `probatioVersion` | sbt SettingKey[String] | `org.sinemenda.probatio.plugin` | shipped |
| `graalVMHome` | sbt SettingKey[Option[String]] | `org.sinemenda.probatio.plugin` | shipped |
| `probatioInstall` | sbt TaskKey[File] | `org.sinemenda.probatio.plugin` | shipped |
| `probatioSpecLint` | sbt TaskKey[Unit] | `org.sinemenda.probatio.plugin` | shipped |
| `probatioChainState` | sbt TaskKey[Unit] | `org.sinemenda.probatio.plugin` | shipped |
| `probatioCheckpoint` | sbt TaskKey[Unit] | `org.sinemenda.probatio.plugin` | shipped |
| `probatioLedgerAppend` | sbt TaskKey[Unit] | `org.sinemenda.probatio.plugin` | shipped |
| `probatioGateShim` | sbt TaskKey[File] | `org.sinemenda.probatio.plugin` | shipped |
| `probatioUninstall` | sbt TaskKey[Unit] | `org.sinemenda.probatio.plugin` | shipped |
| `ShimGenerator` | object (generateShim: ResolutionResult → Either[String, String] — pure 3-line shim generator bound to the resolution result; blocked resolution or an unquotable target path → Left(reason), no shim) — signature changed by `spec:complete-probatio-cutover/native-gate-delivery` | `org.sinemenda.probatio.plugin` | shipped |
| `ExitCodeMapping` | object (mapExitCode: (String, Int, String) → Either[String, Unit] — three-way exit protocol mapping) | `org.sinemenda.probatio.plugin` | shipped |
| `InstallResolver` | object (resolve: ResolutionScenario → ResolutionResult, resolveForShim: (scenario, subcommand, platformHasNative) → ResolutionResult — pure install resolution model; resolveForShim blocks a launcher resolution for a per-turn subcommand on a native platform) — member added by `spec:complete-probatio-cutover/native-gate-delivery` | `org.sinemenda.probatio.plugin` | shipped |
| `ResolutionScenario` | sealed trait (PrebuiltAvailable, PrebuiltChecksumInvalid, JarFallback, NativeImage) | `org.sinemenda.probatio.plugin` | shipped |
| `ResolutionResult` | final case class (path: Option[String], logLines: List[String]) | `org.sinemenda.probatio.plugin` | shipped |

### port-scanner-to-probatio change — native-packaging spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `Platform` | enum (LinuxX86_64, MacosAarch64, MacosX86_64, WindowsX86_64) | `org.sinemenda.probatio.packaging` | shipped |
| `ReleaseArtifact` | enum (NativeBinary(Platform), AssemblyJar, SourcesJar, Sbom, Checksum(String)) | `org.sinemenda.probatio.packaging` | shipped |
| `ChecksumVerifier` | object (computeSha256, verify, verifyForExecution) | `org.sinemenda.probatio.packaging` | shipped |
| `ChecksumResult` | enum (Proceed, Mismatch(artifactName, expected, actual)) | `org.sinemenda.probatio.packaging` | shipped |
| `Sbom` | final case class (derives ReadWriter — SPDX 2.3 model) | `org.sinemenda.probatio.packaging` | shipped |
| `SbomPackage` | final case class (derives ReadWriter) | `org.sinemenda.probatio.packaging` | shipped |
| `ReleaseManifest` | final case class (derives ReadWriter — version, artifacts, checksums, sbom, builtFromCI) | `org.sinemenda.probatio.packaging` | shipped |
| `ReleaseValidator` | object (validateCompleteness, validatePlatformCoverage, validateSbom, validateChecksums, validateCIProvenance, validateAll) | `org.sinemenda.probatio.packaging` | shipped |
| `BinaryResolution` | object (resolve: subcommand + platform + availability → ResolutionResult) | `org.sinemenda.probatio.packaging` | shipped |
| `ResolutionResult` | enum (NativeBinary(path), JarFallback(path, warning), Blocked(reason)) | `org.sinemenda.probatio.packaging` | shipped |

### port-scanner-to-probatio change — migration-protocol spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `ConformanceModel` | object (Ring 6 PureScala model: modelValidate, modelContract, conformance, conformanceNoFalsePositive, conformanceNoFalseNegative, totality) | `org.sinemenda.probatio.verified` | shipped |
| `RecordModel` | final case class (finite representation of ledger record clauses for Ring 6) | `org.sinemenda.probatio.verified` | shipped |
| `ContractId` | enum (LedgerRecord, ChainStateReport, GateHookJson) | `org.sinemenda.probatio.migration` | test-only |
| `ContractJudgment` | enum (Accept, Reject(clause)) | `org.sinemenda.probatio.migration` | test-only |
| `ValidatorJudgment` | enum (Accept, Reject(reason)) | `org.sinemenda.probatio.migration` | test-only |
| `ContractRecord` | final case class (contractId, json, violatedClause) | `org.sinemenda.probatio.migration` | test-only |
| `ConformanceResult` | final case class (contractJudgment, validatorJudgment — isConformant, isFalsePositive, isFalseNegative) | `org.sinemenda.probatio.migration` | test-only |
| `SeamConfiguration` | final case class (portedTools: Set[ToolId] — withPredecessor, withPorted) | `org.sinemenda.probatio.migration` | test-only |
| `OracleOutcome` | final case class (passed, failed, skipped) | `org.sinemenda.probatio.migration` | test-only |
| `MigrationState` | final case class (portedTools: Set[ToolId] — predecessorTools, isComplete) | `org.sinemenda.probatio.migration` | test-only |
| `ShimTarget` | final case class (tool, resolvedTarget, candidateTargets — isExactlyOne, isMissing, isDual) | `org.sinemenda.probatio.migration` | test-only |
| `ShimResolution` | final case class (targets: List[ShimTarget] — allExactlyOne, missing, dual) | `org.sinemenda.probatio.migration` | test-only |
| `SkillDocReference` | final case class (skillDocPath, referencedPath, line — isPredecessorReference, isPortedReference) | `org.sinemenda.probatio.migration` | test-only |
| `SkillDocLintResult` | final case class (brokenReferences, forwardReferences — isClean) | `org.sinemenda.probatio.migration` | test-only |
| `ToolId` (migration) | enum (Ledger, ChainState, SpecLint, DangerScan, Reconcile, Checkpoint, Gate — swapOrder, seamPath, predecessorSource, `overrideEnvVar: Option[String]` with None for Ledger/Checkpoint). Widened 2026-09-21 by `repair-probatio-cutover/differential-harness-integrity` (+Ledger, +Checkpoint — the two seams the comparison never measured). | `org.sinemenda.probatio.migration` | test-only |
| `OracleGreenCheck` | **final class extends ProbatioSuite** (a munit suite, NOT an object) with instance methods `runOracle: SeamConfiguration → OracleOutcome` and `runDifferential: SeamConfiguration → DifferentialResult`, plus `genSeamConfiguration` — callers must instantiate it (`new OracleGreenCheck()`). Kind corrected 2026-09-20 by `repair-probatio-cutover` inventory-check; previously recorded as `object`. | `org.sinemenda.probatio.migration` | test-only |

### complete-probatio-porting change — migration-protocol spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `OracleGreenGate` | object (apply: (Stage, SeamConfiguration) → Boolean — gates stage transitions on bats oracle green; instantiates `new OracleGreenCheck()` and calls its instance `runOracle`, since `OracleGreenCheck` is a suite class not an object; also apply: (ToolId, SeamConfiguration) → Boolean for per-swap gating) | `org.sinemenda.probatio.migration` | test-only |
| `Stage` | enum (Wiring, Cutover — migration stages for oracle-green gating) | `org.sinemenda.probatio.migration` | test-only |

### complete-probatio-cutover change — cutover-gate spec concepts

<!-- Added 2026-09-20 by `repair-probatio-cutover` inventory-check. These seven
     shipped with the archived `cutover-gate` spec but were never recorded; the
     change's concept-delta check missed them. Provenance is therefore the
     cutover-gate spec, not this change. -->

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `DifferentialHarness` | object (`runSuite`: ArmTree → SuiteRun; `divergence`: (List[SeamResolution], List[SeamResolution]) → ArmDivergence; `compare`: (ArmTree, ArmTree) → Either[ArmDivergence.Identical, DifferentialResult]; `exercisedToolPaths`/`unseamedToolPaths`: ArmTree → Map[String, Set[String]]; `checkPredecessorControl`: (ArmTree, SuiteRun, controlPath) → Outcome[Unit]; `diff`: (SuiteRun, SuiteRun, repository) → DifferentialResult; `verifySuiteDigests`: (oracleDir, Map[String,String]) → Either[String, Unit]; nested `SuiteRun`, `BatsFileResult`). **DEFECT REPAIRED 2026-09-21** by `spec:repair-probatio-cutover/differential-harness-integrity`: `runSuite` now executes the suite inside a materialised `ArmTree` (a `git worktree` at a baseline with per-seam content digests) instead of setting `*_OVERRIDE` env vars on the live tree; identical arms are refused (`Left`), never reported as a verdict. | `org.sinemenda.probatio.migration` | test-only; shipped by `spec:complete-probatio-cutover/cutover-gate`, repaired by `spec:repair-probatio-cutover/differential-harness-integrity` |
| `FileComparison` | final case class (fileName, total, predecessorFailures, portedFailures, predecessorPresent, portedPresent — `isWorse`) | `org.sinemenda.probatio.migration` | test-only; shipped by `spec:complete-probatio-cutover/cutover-gate` |
| `DifferentialResult` | final case class (files: List[FileComparison], repository — `isComplete`, `worseFiles`, `hasRegression`, `worseFileNames`) | `org.sinemenda.probatio.migration` | test-only; shipped by `spec:complete-probatio-cutover/cutover-gate` |
| `CutoverGate` | object (`decide`: DifferentialResult → CutoverVerdict; `record`: DifferentialResult → GateRecord) | `org.sinemenda.probatio.migration` | test-only; shipped by `spec:complete-probatio-cutover/cutover-gate` |
| `CutoverVerdict` | enum (Proceed, Revert(evidence: DifferentialResult)) | `org.sinemenda.probatio.migration` | test-only; shipped by `spec:complete-probatio-cutover/cutover-gate` |
| `GateRecord` | final case class (verdict: CutoverVerdict, evidence: DifferentialResult — `authorisesSwap`, `hasEvidence`) | `org.sinemenda.probatio.migration` | test-only; shipped by `spec:complete-probatio-cutover/cutover-gate` |
| `SkillDocLintCheck` | **final class extends ProbatioCliSuite** (a munit suite, NOT an object) — detects stale skill-doc references after a shim swap | `org.sinemenda.probatio.migration` (cli test sources) | test-only; shipped by `spec:complete-probatio-porting/hook-cutover` |

### repair-probatio-cutover change — differential-harness-integrity spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `ArmTree` | final case class, private constructor (root, origin, baseline, config, resolutions) — constructible only via `ArmTree.materialise` (git worktree at baseline + seam resolution) | `org.sinemenda.probatio.migration` | test-only; introduced by `spec:repair-probatio-cutover/differential-harness-integrity` |
| `ArmDivergence` | enum (Identical(seams: List[SeamResolution]) — a refusal, never a verdict; Diverged(perSeam: List[(SeamResolution, SeamResolution)]) — `isIdentical`, `divergingSeams`) | `org.sinemenda.probatio.migration` | test-only; introduced by `spec:repair-probatio-cutover/differential-harness-integrity` |
| `SeamResolution` | final case class (seam: ToolId, implementationDigest: ContentDigest, sourcePath: os.Path) — identity is the digest; sourcePath is provenance only | `org.sinemenda.probatio.migration` | test-only; introduced by `spec:repair-probatio-cutover/differential-harness-integrity` |
| `ContentDigest` | opaque type over String (64-char lowercase SHA-256 hex — `ofBytes`, `ofFile`, `parse`, `hex`) | `org.sinemenda.probatio.migration` | test-only; introduced by `spec:repair-probatio-cutover/differential-harness-integrity` |

### repair-probatio-cutover change — chain-state-undetermined-fidelity spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `PrePassOutcome` | enum (`Completed(lints: Map[String, Outcome[LintReport]])` — carries the pre-pass's produced data; `DidNotRun(reason: UndeterminedReason)` — carries no lint data at all, so a measurement cannot be read out of a run that never happened) + `isCompleted`/`didNotRun` | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/chain-state-undetermined-fidelity` |
| `UndeterminedReason` | opaque type over String (no public `apply` — an empty reason is unconstructible; `of: String => Either[String, UndeterminedReason]` validating route; `stated` total route — empty → `unclassifiable` = "the input under inspection"; `.text`; `ReadWriter` rejects empty on the wire) | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/chain-state-undetermined-fidelity` |
| `ChainStatePrePass` | object (`runPrePassProbe` — spawns the resolved spec-lint (`SPEC_LINT_OVERRIDE` else `<repo>/openspec/schemas/verified-scala3/scanner/spec-lint.sh`) and classifies termination: absent / non-executable / launch failure / exit ∉ {0,1} / missing or count-mismatched completion marker / malformed graph-mode JSON → named `Left`s; mode-aware marker check — graph mode requires stdout to parse as a JSON array, degraded requires `spec-lint: <n> spec file(s)` with n == enumerated count) | `org.sinemenda.probatio.cli` | `spec:repair-probatio-cutover/chain-state-undetermined-fidelity` |
| `ChainStateKernel.computeOutcome` | Ring 6 contract extension (`PrePassOutcome`{`PrePassCompleted(lintSuccess)`, `PrePassDidNotRun(reason: BigInt)`} + `computeOutcome` — `DidNotRun` → `Left(Undetermined)` without consulting evidence; `Completed` → existing fold) + laws `didNotRunIgnoresPopulatedEvidence`, `didNotRunCarriesStatedReason`, `completedOutcomeMatchesCompute`, `derivedCountsMonotone` | `org.sinemenda.probatio.core` (verified/probatio) | `spec:repair-probatio-cutover/chain-state-undetermined-fidelity` |

Existing rows modified by this spec (annotated in place above):
`ChainStateReport` (verdict-path factory gated on `PrePassOutcome.Completed`;
`fromCounts` narrowed to `private[probatio]`),
`ChainStateUndetermined` (reason re-typed to `UndeterminedReason`; no count
fields), `ChainState` (`compute` takes the typed `PrePassOutcome` — a bare
lint map cannot reach the fold).

### repair-probatio-cutover change — completion-witness-refusal spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `WitnessVerdict` | closed enum (`Witnessed` — every in-scope green claim corroborated; `Unwitnessed(row: ClaimVerdict)` — a refusal warrant carrying the offending row so a refusal names it; `Undeterminable(reason: UndeterminedReason)` — unreadable input, abstains without consuming the refusal budget) + `namedRow` | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/completion-witness-refusal` |
| `CompletionDecision` | closed enum (`Allow` / `AllowUndetermined(reason: UndeterminedReason)` — fail-open, states the reason, never consumes the budget; `Refuse(unwitnessed: WitnessVerdict.Unwitnessed)` — the refusal carries the warrant row) + `isRefusal`/`namedRow` | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/completion-witness-refusal` |
| `GateKernel.decideCompletion` | Ring 6 contract extension (`EvidenceRow(isGreen, baseline: BigInt, corroborated)`, `CompletionDecision`{`CompletionAllow`, `CompletionRefuse(rowIndex)`}, `decideCompletion(rows, baseline, priorRefusals)` — refusal iff `priorRefusals == 0` ∧ an in-range uncorroborated green row exists at `baseline`; the refusal names a justifying in-range row; `AllowUndetermined` unmodelled — the kernel quantifies over already-read rows) + discharge lemmas `fuSound`, `uncAtIndexImpliesExists`, `fuComplete` | `org.sinemenda.probatio.core` (verified/probatio) | `spec:repair-probatio-cutover/completion-witness-refusal` |

Existing rows modified by this spec (annotated in place above):
`GateDecisions` (gained `corroborationVerdict` — current-baseline-scoped
warrant detection — and `decideCompletion` — the budget-bounded decision;
both pure, no I/O),
`ReconcileReport` (gained `uncorroborated: List[ClaimVerdict]` — testimony
∪ contradicted in record order, the verdict's warrant set),
`GateKernel` (gained the `decideCompletion` mirror — see the new row).

### repair-probatio-cutover change — gate-event-compatibility spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `EventDispatch` | closed enum (`Tier(event: GateEvent)` — a recognised `--event` name routes to its own tier; `Injection(suppliedName: String)` — every other supplied name routes to the context-injection tier carrying the name verbatim, so a fallback that discards what it fell back from is unconstructible) + `isTier`/`isInjection`; `EventDispatch.classify: String => EventDispatch` — **total** (no `Option`, no `Either` — a failable parse is unconstructible), single-sourced on `recognisedTable` (the six (token, event) pairs in predecessor dispatch order); `recognisedNames` derived from the same table so the closed set and the classification cannot drift | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/gate-event-compatibility` |
| `DispatchKernel.classifyEvent` | Ring 6 contract extension (`EventDispatchModel`{`EventTier(event: BigInt)`|`EventInjection(supplied: BigInt)`} — supplied modelled by name-code, 0 = unrecognised; `recognisedEventNames` = codes 1–6; `classifyEvent(code)` with the spec's three postcondition clauses — totality, recognised⇔tier, injection-carries-name) + laws `classifyEventInjective`, `recognisedEventNamesDistinct` (`noDup` helper — stainless `List` has no `distinct`) | `org.sinemenda.probatio.core` (verified/probatio) | `spec:repair-probatio-cutover/gate-event-compatibility` |

Existing rows modified by this spec (annotated in place above): none —
`GateContext.event: GateEvent` became `dispatch: EventDispatch` inside
`SubcommandEntrypoints` (cli entrypoint plumbing, not an inventoried
concept), and `DispatchKernel` gained the `classifyEvent` mirror (see the
new row).

### repair-probatio-cutover change — graph-tool-port spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `GraphNode` | sealed trait, nine kinds (`Concept`, `Action`, `Sync`, `TypeEntry`, `Spec`, `Requirement`, `Obligation`, `Artifact`, `Code`) — `id` derived per the predecessor convention (`concept:X`, `req:change/cap#N`, `artifact:path`, …), never stored; no `of("kind", id)` constructor, so a free-string kind is unconstructible | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `GraphEdge` / `Edge` / `ObligationLink` | closed enum of the nine relations (`Declares`, `DefinesSync`, `ImplementedBy`, `Cites`, `Uses`, `Introduces`, `HasRequirement`, `EnforcedBy`, `VerifiedBy`) + `Edge` record (from, rel, to, `planned: Option[Boolean]` — `Cites`-only; `link: Option[ObligationLink]` — `EnforcedBy`-only) + `ObligationLink` (`Explicit`/`Title`/`Inferred` — the obligation-link basis); `wireName` gives the predecessor's lowercase rel/link words | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `UnlinkableRow` / `UnlinkableReason` | final case class (source, line, text, reason) + opaque `UnlinkableReason` over String (no public `apply` — a reasonless unlinkable row is unconstructible; `of` validates) | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `TraceabilityGraph` | final case class (nodes: `Vector[GraphNode]`, edges: `List[Edge]`, `unlinkable: List[UnlinkableRow]`) — the unlinkable set is a **required field**, a graph built without it is unconstructible; `node`/`outgoing`/`incoming`/`requirements`/`obligations`; `TraceabilityGraph.build` is pure — resolver predicates injected, no I/O | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `GraphBuild` | final case class (graph, warnings, rowsRead, rowsBound) — the conservation accounting: rowsRead = rowsBound + unlinkable.size | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `ConceptRegistryDoc` / `InventoryDoc` / `SpecGraphDoc` | objects — the three document parsers (concept registry, concept inventory, spec tables incl. obligation marker rows and typed-source classification); `GraphParse` holds the shared row/section helpers | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `GraphQuery` | closed enum of the five operations (`Export`, `Stats`, `Impact`, `Obligations`, `ConceptCode`) — an unknown operation word is unconstructible | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `GraphAudit` / `ReachabilityResult` | object `GraphAudit` (`audit` — follows `EnforcedBy` (requirement→obligation) then `VerifiedBy` (obligation→artifact); unresolving artifacts are transparent; fuel = edge count) + `ReachabilityResult` (reaching, unenforcedRequirements, artifactlessObligations — **lists, not counts**) | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `GraphWire` / `ExportedGraph` | object (`export`/`readExport`/`writeChangePayload`) + `ExportedGraph` — the predecessor's JSON wire object extended with the `unlinkable` array; malformed reads are `Left`, never partial | `org.sinemenda.probatio.core` | `spec:repair-probatio-cutover/graph-tool-port` |
| `ReachabilityKernel` | Ring 6 mirror (`verified/probatio`): `reachF`/`scan` fuel-bounded DFS on `BigInt` ids (scalar encoded measure `(fuel+1)*(E+1)` / `fuel*(E+1)+rem.size`); `reaches` postcondition = grounded ∧ complete iff over `pathToAny` (`gwRF`/`gwScan` witness recursion + `monoRF`/`monoScan` monotonicity recursion + `pathToAnyIntro`/`pathToAnyWit`); `audit` postcondition = size conservation + `disjoint` (self-verifying `partitionOf` + `memFirst`/`memSecond`/`disjointExtend`/`partitionDisjoint`); 658/658 VCs valid | `org.sinemenda.probatio.core` (verified/probatio) | `spec:repair-probatio-cutover/graph-tool-port` |
| `GraphConformance` / `diffExports` | test-only differential comparator — filters exactly the two sanctioned divergence classes (ported-only `unlinkable` rows; predecessor-only binds explained by a ported unlinkable/warning) | `org.sinemenda.probatio.migration` | test-only; introduced by `spec:repair-probatio-cutover/graph-tool-port` |

Existing rows modified by this spec (annotated in place above):
`Subcommand` (+`Graph` — 10 → 11 cases, the traceability tool is ported).
`SubcommandEntrypoints` gained the `GraphCmd` entrypoint and the
`ChainStateCmd` graph seam now exports in-process via
`GraphCmd.exportObligations` (cli entrypoint plumbing, not inventoried
concepts); the chain-state verdict report states `degraded: true` when
the graph read degraded — a wire-level field, `ChainStateReport` shape
unchanged.

### complete-probatio-porting change — hook-cutover spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `ShimSwap` | final case class (tool: ToolId, predecessorPath, shimPath, binaryPath, oracleGreen, timestamp — immutable audit trail entry for one shim swap) | `org.sinemenda.probatio.migration` | test-only |
| `SwapOrder` | enum (LedgerFirst, ChainState, SpecLint, DangerScan, Reconcile, Checkpoint, GateLast — R-M3 dependency order for shim swaps; gate is always last; swapOrder, isLast, indexOf). Checkpoint position added 2026-09-21 by `repair-probatio-cutover/differential-harness-integrity`, matching the `strangler-migration-protocol` concept's declared order. | `org.sinemenda.probatio.migration` | test-only |

### port-scanner-to-probatio change — non-goals-guard spec concepts

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `FeatureFreezeViolation` | enum (NewLintCheck(checkId), VerdictAlteration(fixture, expected, actual), NewWorkflowFeature(featureDescription)) | `org.sinemenda.probatio.guard` | test-only |
| `FeatureFreezeVerdict` | enum (Accepted, Rejected(violation, reason)) | `org.sinemenda.probatio.guard` | test-only |
| `KnownCheckId` | enum (F1–F10 closed set — allIds, isKnown) | `org.sinemenda.probatio.guard` | test-only |
| `FixtureVerdict` | final case class (fixture, verdict, warnings) | `org.sinemenda.probatio.guard` | test-only |
| `DependencyModule` | final case class (organization, name) | `org.sinemenda.probatio.guard` | test-only |
| `AllowedDependencySet` | object (allowed, forbidden, isAllowed, isForbidden, isForbiddenOrg — closed dependency set) | `org.sinemenda.probatio.guard` | test-only |
| `WorkflowSubproject` | enum (ProbatioCore, ProbatioCli, SbtProbatio, ProbatioVerified — all) | `org.sinemenda.probatio.guard` | test-only |
| `DependencyBoundaryResult` | enum (Clean(subproject), Violation(subproject, module)) | `org.sinemenda.probatio.guard` | test-only |
| `HookPayload` | final case class (decision, hookSpecificOutput) | `org.sinemenda.probatio.guard` | test-only |
| `PayloadStabilityResult` | enum (Stable, Unstable(field, before, after)) | `org.sinemenda.probatio.guard` | test-only |
| `OracleImmutabilityResult` | enum (Immutable(commit), Modified(commit, file)) | `org.sinemenda.probatio.guard` | test-only |

### port-scanner-to-probatio change — provenance-validation spec concepts

The following concepts were introduced by `spec:port-scanner-to-probatio/provenance-validation`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `ProvenanceFields` | final case class (sha256, digest, wallTime, source, session — Option types) | `org.sinemenda.probatio.core` | shipped; REMOVED by `spec:complete-probatio-cutover/ledger-checkpoint-parity` (subsumed by `LedgerRecordOptional`) |
| `ValidatedRecord` | final case class (wraps LedgerRecord after 15-clause validation); spec 7 made `provenance` a DERIVED view of `record.optional` — never a second source of truth | `org.sinemenda.probatio.core` | shipped; provenance derived by `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `ContractViolation.OptionalFieldTypeInvalid` | case object (clause 13 — optional field type invalid) | `org.sinemenda.probatio.core` | shipped |
| `ContractViolation.ObserverProvenanceInvalid` | case object (clause 14 — observer provenance invalid) | `org.sinemenda.probatio.core` | shipped |
| `ContractViolation.SessionProvenanceInvalid` | case object (clause 15 — session provenance invalid) | `org.sinemenda.probatio.core` | shipped |
| `Ledger.LedgerReadError` | sealed trait (MalformedRow(rowIndex, violation), NotAnArray(other)) | `org.sinemenda.probatio.core` | shipped |
| `LedgerValidatorKernel.Violation.OptionalFieldTypeInvalid` | case object (clause 13 — Ring 6 model) | `org.sinemenda.probatio.verified` | shipped |
| `LedgerValidatorKernel.Violation.ObserverProvenanceInvalid` | case object (clause 14 — Ring 6 model) | `org.sinemenda.probatio.verified` | shipped |
| `LedgerValidatorKernel.Violation.SessionProvenanceInvalid` | case object (clause 15 — Ring 6 model) | `org.sinemenda.probatio.verified` | shipped |

### port-scanner-to-probatio change — schema-policy spec concepts

The following concepts were introduced by `spec:port-scanner-to-probatio/schema-policy`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `StampFormat` | enum (Legacy, New) — pre-rename vs post-rename generatedBy stamp format | `org.sinemenda.probatio.core` | shipped |
| `StampClassification` | enum (Matching, DriftWarning(expected, found), PreRename(found), NoSkill) — drift detector classification | `org.sinemenda.probatio.core` | shipped |
| `RootStamp` | final case class (rootPath, stamp: Option[(StampFormat, Int)]) — stamp at one install root | `org.sinemenda.probatio.core` | shipped |
| `StampScan` | final case class (roots: List[RootStamp]) — scan across install roots | `org.sinemenda.probatio.core` | shipped |
| `DriftLine` | enum (NoSkillLine, MigrationMessage(rootPath, found), DriftWarningLine(rootPath, expected, found)) — drift scan output line | `org.sinemenda.probatio.core` | shipped |
| `ResolvedValue` | enum (Default, Value(v)) — resolved hook control env var value | `org.sinemenda.probatio.core` | shipped |
| `DeprecationWarning` | final case class (oldName, newName, majorWindow) — env var deprecation warning | `org.sinemenda.probatio.core` | shipped |
| `EnvVarSetting` | enum (Neither, LegacyOnly(value), NewOnly(value), Both(newVal, legacyVal)) — four env var states | `org.sinemenda.probatio.core` | shipped |
| `EnvResolution` | final case class (resolved: ResolvedValue, warnings: Warnings) — env var resolution result | `org.sinemenda.probatio.core` | shipped |
| `CacheState` | final case class (legacyExists, newExists, legacyContents, newDirContents) — cache dir migration state | `org.sinemenda.probatio.core` | shipped |
| `SchemaPolicy` | object (resolveHookEnv, migrateCache, classifyStamp, classifyDrift — pure migration functions) | `org.sinemenda.probatio.core` | shipped |

### port-scanner-to-probatio change — gate-checkpoint-lock spec concepts

The following concepts were introduced by `spec:port-scanner-to-probatio/gate-checkpoint-lock`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `GateEvent` | enum (SessionStart, PromptSubmit, PostEdit, ToolCall, Completion, PostBash) — six hook events + total `harnessName` (the harness's own event name per case) | `org.sinemenda.probatio.core` | shipped; +`PostBash`/`harnessName` by `spec:complete-probatio-cutover/gate-event-completeness` |
| `GateDecision` | enum (Allow, Block(reason: BlockReason)) — gate decision | `org.sinemenda.probatio.core` | shipped |
| `SpecPhase` | enum (Oracle, Implementation, Verified) — spec phase in implementation order + `fromStateFile` (total: unrecognised → `Oracle`) / `asToken` | `org.sinemenda.probatio.core` | shipped; +`fromStateFile`/`asToken` by `spec:complete-probatio-cutover/gate-event-completeness` |
| `BlockReason` | sealed trait (PredecessorNotVerified(spec, phase), PredecessorNotCheckpointed(spec), OracleOrderingViolation, GrantRequired(spec), CompletionUnresolved(details), ChainStateUndetermined, Uncorroborated(details)) — block reason with render; the last three are the completion tier's refusal texts | `org.sinemenda.probatio.core` | shipped; +3 completion variants by `spec:complete-probatio-cutover/gate-event-completeness` |
| `PredecessorCheck` | object (apply: pure function over List[(name, phase, hasPresentation)] + escapeHatch → Either[BlockReason, Unit]) | `org.sinemenda.probatio.core` | shipped |
| `GrantWaiver` | object (apply: pure function over List[(name, phase, hasPresentation, hasGrant)] + escapeHatch → Either[BlockReason, Unit]) | `org.sinemenda.probatio.core` | shipped |
| `PresentationMarker` | final case class (specName, exists: Boolean) — checkpoint presentation evidence | `org.sinemenda.probatio.core` | shipped |

### complete-probatio-porting change — cli-wiring spec concepts

The following concepts were introduced by `spec:complete-probatio-porting/cli-wiring`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `CliContext` | final case class (repoRoot, changeDir, ledgerFile, gitDir: String; escapeHatch: Boolean) — resolved paths + env-var overrides read once at entrypoint start + `hooksControl(env, schemaVersion): EnvResolution` — the `PROBATIO_HOOKS`/`VERIFIED_SCALA3_HOOKS` alias window resolved through `SchemaPolicy` (`off` under either name skips the gate; the inverted `=1` reader is removed) | `org.sinemenda.probatio.cli` | shipped; +`hooksControl` by `spec:complete-probatio-cutover/gate-event-completeness` |
| `StdoutRenderer[A]` | trait (render(value: A): String) — typeclass for byte-compatible stdout rendering; given instances for ChainStateReport, ChainStateUndetermined, LintReport, GatePayload, BannerOutput, LintContext | `org.sinemenda.probatio.cli` | shipped; +LintContext instance by `spec:complete-probatio-cutover/spec-lint-engine` |
| `SubcommandWiring` | object (parseArgs, readLedgerFile, appendLedgerLine, emitStdout, emitStderr, stampTimestamp, supportedVersion) — I/O adapter layer: reads files, parses args, calls core, renders, maps to Outcome[Int]; spec 7 added `repoContaining`/`gitExit`/`forgivePredicate` (moved from `ChainStateCmd`), `repoRootOf`, `sha256OfFile`/`sha256Hex`, `shellParses`, `replayCommand`, `executeCaptured`, `readTextFile`/`writeTextFile`, `absoluteGitDirOf`, boolean-aware `parseArgs` | `org.sinemenda.probatio.cli` | shipped; extended by `spec:complete-probatio-cutover/ledger-checkpoint-parity` |

### complete-probatio-cutover change — live-fact-banner spec concepts

The following concepts were introduced by `spec:complete-probatio-cutover/live-fact-banner`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `FactRead[+A]` | enum (Present(value), Absent, Unreadable(reason)) — three-state read result; unreadable never collapses to absent | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `ArtifactRef` | final case class (id, generates) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `ArtifactScan` | final case class (present: List[String], next: Option[ArtifactRef]) — the schema.yaml artifact DAG as read | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `RepositoryFacts` | final case class (schemaVersion/registry/inventory: `FactRead[Int]`, profile: `FactRead[Option[String]]`, installRoots: `List[InstallRootScan]`, activeChanges: `FactRead[List[ActiveChangeWithChainState]]`; `fingerprint` — canonical whole-record JSON encoding incl. reasons/baselines) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `RootBase` | enum (RepoRoot, UserHome) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `InstallRootRef` | final case class (base: RootBase, relativePath) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `InstallRoots` | final case class — fixed-arity record of the predecessor's six install roots (`.all`, `.length`); cannot be narrowed without a compile error | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `InstallRootState` | enum (Absent, PresentNoStamp, Stamped(version, StampFormat), Unreadable(reason)) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `SessionId` | opaque type over String (fromRaw, resolve with signal-priority, `.raw`, `.encoded` — lossless base64url, injective) — declared for `gate-event-completeness`, introduced early here | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `HeartbeatRecord` | final case class (ts, event, format) — declared for `gate-event-completeness`, introduced early here | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/live-fact-banner` |
| `RepositoryFactsReader` | object (`read(repoRoot, userHome, env): RepositoryFacts` + `readLintContext(repoRoot, userHome): LintContext` — the single fact-reading seam; total: failures are data, never thrown) | `org.sinemenda.probatio.cli` | `spec:complete-probatio-cutover/live-fact-banner`; +`readLintContext` by `spec:complete-probatio-cutover/spec-lint-engine` |
| `GateStateDir` | final case class (path: Path) — declared for `gate-event-completeness`, introduced early here | `org.sinemenda.probatio.cli` | `spec:complete-probatio-cutover/live-fact-banner` |
| `GateStateDirReader` | object (resolve via `git rev-parse --absolute-git-dir`, fingerprint `fp-<SessionId.encoded>` read/write, heartbeat read/write; all ops fail-open) + spec-8 marker surface: `phaseFile`/`readPhase`/`writePhase`, `presentationFile`/`sessionPresentations` (`presentation-*-*-<sess>` glob parity)/`writePresentation`, `grantFile`/`hasGrant`/`hasAnySessionGrant`/`writeGrant`, `refusalFile`/`hasRefusal`/`writeRefusal` (write failure ⇒ fail open)/`clearRefusals`, `sweepCheckpointOutputs`, `specDirs`, `RefusalKind` + `markerPrefix` | `org.sinemenda.probatio.cli` | `spec:complete-probatio-cutover/live-fact-banner`; extended by `spec:complete-probatio-cutover/gate-event-completeness` |
| `BannerEngineKernel.bannerClaims` | Ring 6 contract (`facts: List[BigInt] => List[BigInt]` — emitted claims equal fact codes; `-1` unreadable, `0` absent, `n>0` present-with-count) + helpers (`allFactCodesValid`, `claimsMatchFacts`, `noUnreadableClaimedAbsent`, `claimFor`) and five fixed-size law lemmas | `org.sinemenda.probatio.verified` | `spec:complete-probatio-cutover/live-fact-banner` |

Existing rows modified by this spec (annotated in place above): `BannerInputs`
(private constructor), `ActiveChangeWithChainState` (FactRead artifacts + Either
chain state), `InstallRootScan` (four-state root read), `DriftWarning`
(+NoStampDeclared, +Unreadable), `DriftScan.installRoots` (three-root list →
six-root `InstallRoots` record).

### complete-probatio-cutover change — spec-lint-engine spec concepts

The following concepts were introduced by `spec:complete-probatio-cutover/spec-lint-engine`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `SpecDocument` | final case class (name, lines: Vector[String], requirements, properties, temporals, scenarios, obligationRows, dataRowCount, bridgeRowCount, hasProofObligations, formalContractsContentLines, hasBehavioralConcepts, artifactRows, chainRows — the chain-state awk's own proof-obligation row set, admitted under `## `-only section flags) — the parsed spec as immutable data | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine`; +`chainRows` by `spec:complete-probatio-cutover/chain-state-attribution` |
| `RequirementBlock` | final case class (title, line, endLine, hasNormative, negative, scenarioCount, normativeText) — `line`/`endLine` bracket the block for body rescans and live-visibility filtering | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `PropertyBlock` | final case class (title, line, endLine, hasGeneratorStrategy) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `TemporalBlock` | final case class (title, line, endLine, hasTriggerEvent, hasResponseEvent) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `ScenarioHeading` | final case class (title, line) — `#### Scenario:` headings wherever they appear; F8 source resolution target | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `ObligationRow` | final case class (line, fieldCount, source, enforcement, artifact, raw) derives ReadWriter — one evaluated proof-obligation table row; skipped rows (empty/comment source, `NF < 4`) never appear here | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `ObligationSource` | enum (ByTitle(requirementIndex), ByOrdinal(requirementIndex), Typed(kind, name), Unresolvable(cell)) — the predecessor `check_source` resolution algebra as a closed type | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `SpecDocumentParser` | object (`parse(name, lines): SpecDocument` — pure port of the predecessor awk scan: heading dispatch, live-state block tracking, empty-title gating, artifact-row tracking under `## `-only section flags) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `SpecLintEngine` | object (`lint(document, context, artifactTracked): LintReport` — pure total function emitting F1–F10/W1–W7 in predecessor order; `obligationSources`, `reachabilityFold` exposed for verification) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `CheckOutcome` | enum derives ReadWriter (Pass(check), Fail(check, line, message), Warn(warning)) — the emitted finding stream, predecessor emission order | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `LintContext` | final case class (schemaVersion/registry/registryConcepts/inventoryTypes/profile: FactRead, installRoots) — injected repository facts; `hasRegistry` gates F10/W7, `codeIdentifiers` is the predecessor `comm -23` | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/spec-lint-engine` |
| `SpecLintKernel` | object — Ring 6 mirror of `reachabilityFold` (`numRequirements, rowTargets: List[BigInt] => (unenforced, unresolvableCount)`): `uncoveredFrom` same-shaped soundness postcondition + `uncoveredComplete` inductive lemma + four fixed-size law lemmas | `org.sinemenda.probatio.core` (verified/probatio) | `spec:complete-probatio-cutover/spec-lint-engine` |

Existing rows modified by this spec (annotated in place above): `LintReport`
(re-shaped — verdicts + finding stream + applicability + resolvedRows/
unresolvableRows/requirementRows; `lintSuccess` derived from findings),
`RepositoryFactsReader` (+`readLintContext`), `StdoutRenderer` (+given
instance for `LintContext`), `SpecLintCmd` (skeleton → real implementation:
positional change-dir, `--context-only`, `--artifacts`, `--format json`,
nested `specs/` discovery).

The following concepts were introduced by `spec:complete-probatio-cutover/chain-state-attribution`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `FactSource` | enum (Graph, Degraded) + `asString` — which fact pipeline produced the requirement set; gates `unattributable` eligibility (degraded only) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/chain-state-attribution` |
| `ExtractedObligation` | final case class (spec, line, obligation, artifact, artifacts, requirementClaims, unmappable) — one normalised obligation row; `unmappable` evaluated per-path at extraction time | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/chain-state-attribution` |
| `RequirementSet` | final case class (specNames, requirements, obligations, source) + `empty` + `isEmpty` — the only way requirements enter `ChainState.compute`; a bare `List[Requirement]` cannot | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/chain-state-attribution` |
| `RequirementExtractor` | object (`NamedSpec(name, document)`; `extract(specs, graphExport: Option[ujson.Value]): RequirementSet`; `usableExport`, `degradedObligations` exposed for verification) — total; degraded/empty inputs surface as data, never thrown | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/chain-state-attribution` |
| `ChainState` | object (`compute(prePass: PrePassOutcome, ledger, reqs: RequirementSet, specBaselines: Map[String, List[String]], baseline, change, artifactUnchanged)` — `DidNotRun` short-circuits to `Left(ChainStateUndetermined)` without consulting evidence; `Completed` delegates to the measured fold) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/chain-state-attribution`; signature re-shaped by `spec:repair-probatio-cutover/chain-state-undetermined-fidelity` |
| `ChainStateKernel.chainStateFold` | Ring 6 contract (`total, verdicts, discharged, unattributable => (bound, resolved, dis, unresolved)` — verdict codes 0/1/2, index ranges in `[0,total)`; postcondition: `dis <= resolved <= bound <= total`, `unresolved.size == total - dis`, unattributable indices never counted discharged and always appear in `unresolved`) + helpers (`filterOut`, `foldFrom`, `rangeClause`, `clauseFrom`, `filteredNotBanned`, `absentIsUnresolved`) + three witness lemmas | `org.sinemenda.probatio.core` (verified/probatio) | `spec:complete-probatio-cutover/chain-state-attribution` |

Existing rows modified by this spec (annotated in place above):
`SpecDocument` (+`chainRows` — the chain-state awk's own row set, populated
under `## `-only section flags parallel to `obligationRows`),
`UnresolvedEntry`/`ChainStateReport` (private constructors + `of`/`fromCounts`
smart constructors; wire reads route through them),
`UnresolvedReason` (`Unattributable` now reachable — degraded-mode-only
reason), `ChainState.Requirement` (unchanged shape; consumed only via
`RequirementSet`).

The following concepts were introduced by `spec:complete-probatio-cutover/danger-reconcile-engines`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `DangerPattern` | enum (8 cases: `UnsafeGet`, `UnsafeHead`, `CatchAll`, `Cast`, `Blocking`, `Swallowed`, `UnreachableClaim`, `LintOff`) + `label` — the predecessor's pattern classes and report tokens | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `DangerHit` | final case class (file, line, pattern, text, justified) — one occurrence | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `DangerReport` | final case class, private ctor + `of` — `hits`/`justifiedExcluded` are a partition of the occurrence list; a summary disagreeing with contents is unrepresentable | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `DangerScanEngine` | object (`isProductionPath` — the `/src/main/` containment rule; `scanLine`; `scan` — pattern-major emission, pure, no I/O) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `Corroboration` | enum (5 cases: `SelfObserved`, `Witnessed(observer, preceding, following)` — the ambient set is `preceding ++ (observer :: following)` so witnessed-without-witness is unconstructible, `Testimony`, `Contradicted(observer, others)`, `Exempt`) + `verdictToken` | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `ClaimVerdict` | final case class (spec, ring, obligation, command, baseline, verdict, observed) — the predecessor's per-claim verdict object | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `ReconcileReport` | final case class, private ctor + `of` — every count/verdict list is a derived view of `classifications`; NO discharge verdict exists; +`uncorroborated: List[ClaimVerdict]` (testimony ∪ contradicted in record order — the completion refusal's warrant set) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines`; extended by `spec:repair-probatio-cutover/completion-witness-refusal` |
| `ReconcileEngine` | object (`Classified(record, corroboration)`, `judgmentRings = Set(R2, R8, Manual)`, `classify(records, change, spec, baseline)` — pure fold; exact-key corroboration on (spec, ring, baseline, command)) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `ChangedFilesReader` | object (`resolveBaseline`, `changedProductionFiles`, `readFiles`) — the git/filesystem adapter for danger-scan; subprocess failure maps to `Left`/undetermined, never a silent clean report | `org.sinemenda.probatio.cli` | `spec:complete-probatio-cutover/danger-reconcile-engines` |
| `ReconcileKernel.corroborationFold` | Ring 6 contract (`require(validRecords)`; `ensuring(cls.length == records.length && postOk(records, records, cls))`) + helpers (`observedAt`, `observedOutcomeAt`, `validRecords`, `classifyRow`, `postOk`, `foldGo`) — all structural recursion; classification codes CLS_SELF_OBSERVED/WITNESSED/TESTIMONY/CONTRADICTED/EXEMPT | `org.sinemenda.probatio.verified` (verified/probatio) | `spec:complete-probatio-cutover/danger-reconcile-engines` |

Existing rows modified by this spec (annotated in place above): none — all
concepts are new; `StdoutRenderer`, `SubcommandEntrypoints`, `HelpRegistry`
were extended with the `reconcile`/`danger-scan` surfaces without changing
existing concept shapes.

### complete-probatio-cutover change — ledger-checkpoint-parity spec concepts

The following concepts were introduced by `spec:complete-probatio-cutover/ledger-checkpoint-parity`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `RingStatus` | enum (5 cases: `Green`, `Failed`, `Unevidenced`, `SameSession`, `UnverifiedSession`) + `token` — the predecessor's per-ring status tokens | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `RingEvidence` | final case class (ring, status, record: Option[LedgerRecord], note: Option[String]) — one ring's classified evidence | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `ReplayVerdict` | enum (3 cases: `Matches`, `Diverges`, `Unreplayable` — NO "skipped" case, a skipped row could pass as verified) + `unreplayableRings = Set(R8, Manual)` + total `classify(ring, replayedExit, recordedExit)` | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `CheckpointReport` | final case class, private ctor + `of(change, spec, baseline, rings, chainState)` — `unresolvedCount` derives from the verdict's own `unresolved` member; `markerWritten` requires every requested ring green AND zero unresolved, so an unevidenced-marker report is unrepresentable; `toJson`/`toText` render predecessor structure | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `CheckpointEngine` | object (`report` — dedup first-occurrence, last-record-per-ring, verdict consumed as opaque `ujson.Value` never recomputed; `classify` — the R8 same-session ladder; `markerDecision`; `unresolvedCountOf` — jq `length` semantics; `specBaseline` — progress-tracker `Commit` cell; `regenerateTasks` — checkbox-only rewrite) — pure, no I/O, no `ChainState` reference | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/ledger-checkpoint-parity` |
| `LedgerValidatorKernel.allEvidenced` / `.markerDecision` | Ring 6 mirror — structural recursion over Stainless lists; `markerDecision` carries `ensuring` equivalence to the `forall` formulation | `org.sinemenda.probatio.verified` (verified/probatio) | `spec:complete-probatio-cutover/ledger-checkpoint-parity` |

Existing rows modified by this spec (annotated in place above):
`LedgerRecord` (joined `optional` as required), `LedgerRecordOptional`
(attached — was orphaned), `ValidatedRecord` (`provenance` derived),
`ProvenanceFields` (removed — subsumed), `LedgerCmd`/`CheckpointCmd`
(predecessor op surface), `SubcommandWiring` (shared I/O adapters +
observation seams), `HelpRegistry` (ledger/checkpoint help surface).

### complete-probatio-cutover change — gate-event-completeness spec concepts

The following concepts were introduced by `spec:complete-probatio-cutover/gate-event-completeness`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `HarnessPayload` | final case class, private ctor + `of(toolName, toolInput, toolResponse, cwd, stopHookActive)` — the structured input a harness supplies on stdin; `interrupted` derived from the response (one fact, stated once) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/gate-event-completeness` |
| `ToolOutcome` | sealed trait, `private[ToolOutcome]` ctors — `classify(response)` is the only construction path (object → `Exit(0)` unless `interrupted:true`; `"Error: Exit code N"` → `Exit(N)` via `toIntOption`, unrepresentable digits → `Skip`; other string → `Skip(not-a-command-outcome)`; other shape → `Skip(unrecognised-response-shape)`); total and conservative | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/gate-event-completeness` |
| `RefusalBudget` | final case class, private ctor (`issued: Int`) — `full`, `fromMarker` (present marker ⇒ spent), `exhausted`, `issue` (`None` when spent — the second refusal is unrepresentable); `apply(blockable)` fold — exactly one refusal at the first blockable, kernel-mirrored | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/gate-event-completeness` |
| `GateDecisions` | object — the pure decision module (no I/O, enforced by the `NoIOInProbatioCore` scalafix rule): `readOnlyTools`, `isProductionEdit`, `isSpecEdit`, `specOrder`, `owningSpec` (Expected-Files table map — consulted on the production branch only), `advancePhase` (implementation→verified requires RED∧GREEN), `AmbientMatch`/`ambientRingMatch` + `ambientVerdict` (Either — the skip reason feeds the trace), `Step0Target`/`step0Target`, `markerTriple` (right-to-left parse quirks), `unresolvedBlock` (≤10 + `+N more`), `Polarity`/`hasRing3Row`/`firstRing3Baseline`/`hasGreenAfterRed`, `specEditChangeName` (sed-equivalent greedy-backtrack extraction), +`corroborationVerdict`/`decideCompletion` (the completion refusal: current-baseline-scoped warrant + budget-bounded decision — still pure, no I/O) | `org.sinemenda.probatio.core` | `spec:complete-probatio-cutover/gate-event-completeness`; extended by `spec:repair-probatio-cutover/completion-witness-refusal` |
| `HarnessPayloadReader` | object — `consumesPayload`, `parse` (jq `// empty` semantics), `Empty`, `readChannel(inputPending)` — the at-most-once stdin read; silent open pipes read as no-payload | `org.sinemenda.probatio.cli` | `spec:complete-probatio-cutover/gate-event-completeness` |
| `GateKernel` | object — Stainless Ring 6 mirror: `refusalBudget(blockable)` (`ensuring` exactly one refusal at the first blockable) and `classifyOutcome(shape, carriedCode)` (`ensuring` `Some` iff shape ∈ {0,2}); +`decideCompletion` (completion-witness-refusal mirror — see the spec-3 row); `GateBridgeSpec` in probatio-cli binds shipped code to the model on generated inputs | `org.sinemenda.probatio.verified` (verified/probatio) | `spec:complete-probatio-cutover/gate-event-completeness`; extended by `spec:repair-probatio-cutover/completion-witness-refusal` |

Existing rows modified by this spec (annotated in place above): `GateEvent`
(+`PostBash` sixth case, total `harnessName`), `SpecPhase`
(+`fromStateFile`/`asToken`), `BlockReason` (+`CompletionUnresolved`,
+`ChainStateUndetermined`, +`Uncorroborated`), `CliContext`
(+`hooksControl` — the `=off`-under-either-name hatch through
`SchemaPolicy`; the inverted `=1` reader removed), `GateStateDir`/
`GateStateDirReader` (phase, presentation, grant, refusal, sweep, specDirs
surface), `GateCmd` (six-event dispatcher — `post-bash` ambient writer,
tool-call grant/oracle locks, marker-driven completion, payload channel).

### complete-probatio-cutover change — native-gate-delivery spec concepts

The following concepts were introduced by `spec:complete-probatio-cutover/native-gate-delivery`:

| Concept | Kind | Package | Status |
|---------|------|---------|--------|
| `LatencyMeasurement` | final case class (sampleCount, medianMillis, maxMillis, artifactKind: ArtifactKind ∈ {NativeImage, JarLauncher}) — an undersized measurement is a recordable observation; sufficiency is the verdict's call | `org.sinemenda.probatio.packaging` | `spec:complete-probatio-cutover/native-gate-delivery` |
| `BudgetVerdict` | enum (Met(m, b), Exceeded(m, b), Undetermined(NoMeasurement \| InsufficientSamples(observed, required))) — `evaluate(Option[LatencyMeasurement], LatencyBudget)`; Met/Exceeded carry the measurement as evidence | `org.sinemenda.probatio.packaging` | `spec:complete-probatio-cutover/native-gate-delivery` |
| `LatencyBudget` | final case class (medianMillis, minSamples) — `perTurn = (150.0, 100)` per native-packaging R-N1 | `org.sinemenda.probatio.packaging` | `spec:complete-probatio-cutover/native-gate-delivery` |
| `ReleaseManifestIO` | object — `fromDirectory(dir, version, builtFromCI): Either[String, ReleaseManifest]` rebuilds a typed manifest from a release-artifact directory (filename → artifact, `X.sha256` first token → `checksums(X)`, SPDX JSON → `Sbom`) | `org.sinemenda.probatio.packaging` | `spec:complete-probatio-cutover/native-gate-delivery` |
| `ReleaseCheck` | object — `main(args)`: the release-step gate; builds the manifest from the downloaded artifact dir and fails the release when `ReleaseValidator.validateAll` is non-empty | `org.sinemenda.probatio.packaging` | `spec:complete-probatio-cutover/native-gate-delivery` |
| `probatioSpecLintArgs` / `probatioChainStateArgs` / `probatioCheckpointArgs` / `probatioLedgerAppendArgs` | sbt SettingKey[Option[Seq[String]]] — the argument list each delegating task passes; `None` = task reports the missing value without invoking | `org.sinemenda.probatio.plugin` | `spec:complete-probatio-cutover/native-gate-delivery` |
| `probatioExpectedSha256` | sbt SettingKey[Option[String]] — the recorded digest a cached prebuilt binary must match; unset = a present binary is checksum-invalid, never assumed valid | `org.sinemenda.probatio.plugin` | `spec:complete-probatio-cutover/native-gate-delivery` |
| `probatioAssemblyJar` | sbt SettingKey[Option[File]] — the concrete JAR the fallback launcher binds to; unset = the task reports rather than writing a launcher referencing an unset env var | `org.sinemenda.probatio.plugin` | `spec:complete-probatio-cutover/native-gate-delivery` |

Existing rows modified by this spec (annotated in place above): `ShimGenerator`
(`generateShim` re-bound from a raw path to `ResolutionResult` — blocked
resolution yields `Left`, no shim), `InstallResolver` (+`resolveForShim` —
per-turn launcher block on native platforms), `ReleaseValidator`
(`validateChecksums` now reconciles `manifest.checksums` keys with `Checksum`
sidecar names), `ProbatioPlugin` (+`writeShim` seam, +`detectScenario`
checksum parameter, +`runDelegatingTask` explicit `args`).
