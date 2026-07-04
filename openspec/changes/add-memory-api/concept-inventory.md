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

     SCAN METHOD: semantic scanner `openspec/schemas/verified-scala3/scanner/scan.sh`
     run per-module (adk4s-core, adk4s-orchestration, structured-llm,
     structured-llm-test-models, adk4s-examples) on 2026-07-03. The scanner
     scans `./src/main/scala/` relative to its arg, so per-module invocation is
     required (the repo has no top-level `src/`). Results were consolidated and
     de-duplicated; obvious scanner mis-parses (scaladoc comments read as field
     types, anonymous enum bodies labelled `values`/`value`/`definitions`) were
     corrected against the source. -->

## Opaque Types (Iron Refined)

<!-- No Iron/refined library is present in the stack (see capability-profile.md).
     The opaque types below are plain `opaque type` newtypes WITHOUT Iron
     constraints. Recorded here because the template groups them under this
     heading. -->

| Type | Underlying | Iron Constraint | Package | Introduced By |
|------|-----------|-----------------|---------|---------------|
| `RunPath` | `List[RunStep]` | (none — plain opaque type, no Iron) | `org.adk4s.core.interrupt` | pre-existing |
| `NodeKey` | `String` | (none — plain opaque type, no Iron) | `org.adk4s.core.types` | pre-existing |
| `FieldPath` | `Vector[String]` | (none — plain opaque type, no Iron) | `org.adk4s.core.types` | pre-existing |

## Sealed Traits and Enums

<!-- Closed type hierarchies that enable exhaustive pattern matching.
     Variants listed where extractable from the scan + source cross-check. -->

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `AdkError` | sealed trait | `LlmCallError`, `StructuredOutputError`, `TypeMismatchError`, `MissingFieldError`, `NodeNotFoundError`, `EdgeValidationError`, `MaxStepsExceededError`, `GraphCompiledError`, `GraphEntryMissingError`, `GraphEndNodesMissingError`, `ToolNotFoundError`, `ToolExecutionError`, `StateTypeMismatchError`, `NodeAlreadyExistsError`, `SourceNodeNotFoundError`, `NodeDoesNotExistError`, `FanInError`, `BranchTargetError`, `AgentInterruptedException`, `CheckpointNotFoundError`, `GenericError`, `NodeKeyError` | `org.adk4s.core.error` | pre-existing |
| `ToolSchemaError` | sealed trait | `MissingRequiredField`, `TypeMismatch` | `org.adk4s.core.tools` | pre-existing |
| `StructuredToolCallError` | sealed trait | `UnknownTool`, `InvalidArguments`, `ExecutionFailed`, `ResultParsingFailed` | `org.adk4s.core.tools` | pre-existing |
| `InterruptSignal` | sealed trait | `Simple`, `Stateful`, `Composite` | `org.adk4s.core.interrupt` | pre-existing |
| `AgentEvent` | sealed trait | `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `Interrupted`, `ErrorOccurred`, `TokenDelta` | `org.adk4s.core.interrupt` | pre-existing |
| `AddressSegment` | sealed trait | `Agent`, `Tool` | `org.adk4s.core.interrupt` | pre-existing |
| `FallbackSemantic` | enum | `Resume`, `Atomic`, `BeforeFirstElement` | `org.adk4s.core.runnable` | pre-existing |
| `RunResult` | sealed trait | `Completed`, `Interrupted`, `Failed` | `org.adk4s.orchestration.agent` | pre-existing |
| `WIONode` | sealed trait | (multiple node variants — see `WIONode.scala`) | `org.adk4s.orchestration.wiograph` | pre-existing |
| `WIONodeModifier` | sealed trait | `CheckpointModifier`, `RetryModifier`, `InterruptionModifier` | `org.adk4s.orchestration.wiograph` | pre-existing |
| `WIOGraphError` | sealed trait | `CycleDetected`, `UnreachableEnd`, `NodeAlreadyExists`, `EntryNodeNotFound`, `EndNodeNotFound`, `SourceNodeNotFound`, `TargetNodeNotFound`, `NodeNotFoundInGraph`, `MultipleOutgoingEdges`, `UnsupportedNodeType`, `SubGraphCompilationFailed`, `ForkEdgeBranchMismatch` | `org.adk4s.orchestration.wiograph` | pre-existing |
| `ChainBranch` | sealed trait | (see `ChainBranch.scala`) | `org.adk4s.orchestration.chain` | pre-existing |
| `ChainStep` | sealed trait | (see `Chain.scala`) | `org.adk4s.orchestration.chain` | pre-existing |
| `GraphNode` | sealed trait | `ChatModelNode`, `ToolsNode`, (others — see `GraphNode.scala`) | `org.adk4s.orchestration.graph` | pre-existing |
| `Branch` | sealed trait | (see `Branch.scala`) | `org.adk4s.orchestration.branch` | pre-existing |
| `WorkflowNode` | sealed trait | `ChatModel`, (others — see `WorkflowNode.scala`) | `org.adk4s.orchestration.workflow` | pre-existing |
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
| `SectionType` | enum | `System`, `User`, `Assistant`, `Raw` | `org.adk4s.structured.template` | pre-existing |
| `CoercionFlag` | enum | `StringToInt`, `StringToBool`, `StringToFloat`, `IntToFloat`, `FloatToInt`, `SingleToArray`, `ObjectToString`, `StrippedNonAlphaNumeric`, `DefaultFromNoValue`, `CaseInsensitive`, `PunctuationStripped`, `AnyOfResolved` | `org.adk4s.structured.sap` | pre-existing |
| `CompletionState` | enum | `Pending`, `Incomplete`, `Complete` | `org.adk4s.structured.sap` | pre-existing |
| `JsonishValue` | enum | `Null`, `Bool`, `Num`, `Str`, `Arr`, `Obj`, `Markdown`, `AnyOf` | `org.adk4s.structured.sap` | pre-existing |

> Note: `adk4s-examples` defines many per-example `sealed trait` state/event
> types (e.g. `GraphState`, `GraphEvent`, `AgentState`, `LoopState`,
> `SvState`, `BranchState`, `WfState`). These are application-edge code, not
> reusable library concepts, and are omitted from the table above. Full list
> available in the scan output.

## Case Classes (Domain Value Objects)

<!-- Immutable data carriers in domain packages. Only library modules
     (adk4s-core, adk4s-orchestration, structured-llm) are listed —
     adk4s-examples case classes are application-edge and omitted.
     Scanner mis-parses (scaladoc read as field type) have been corrected. -->

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| `Document` | `id: String, content: String, metadata: Map[String, ujson.Value] = Map.empty` | `org.adk4s.core.component` | pre-existing — **REUSED by this change** (`MemoryRetriever` target) |
| `RetrieverConfig` | `topK: Int = 5, minScore: Double = 0.0` | `org.adk4s.core.component` | pre-existing — **REUSED by this change** (`MemoryRetriever` maps `recall(k)` onto `topK`) |
| `EmbeddingUsage` | `promptTokens: Int, totalTokens: Int` | `org.adk4s.core.component` | pre-existing |
| `EmbeddingResult` | `embeddings: List[Embedding], usage: Option[EmbeddingUsage]` | `org.adk4s.core.component` | pre-existing |
| `AdkToolInfo` | `name: String, description: String, parameters: ujson.Value` | `org.adk4s.core.component` | pre-existing |
| `AgentToolState` | `messages: List[SerializableMessage], iterationCount: Int` | `org.adk4s.core.component` | pre-existing |
| `SerializableMessage` | `role: String, content: String` | `org.adk4s.core.component` | pre-existing |
| `ChatModelConfig` | `temperature: Option[Double] = None, maxTokens: Option[Int] = None, topP: Option[Double] = None, stopSequences: Option[List[String]] = None` | `org.adk4s.core.component` | pre-existing |
| `RunInfo` | `nodeKey: NodeKey, componentType: String, nodeName: Option[String] = None, startTime: Option[Instant] = None, parentPath: List[NodeKey] = Nil` | `org.adk4s.core.types` | pre-existing |
| `AccumulatedResponse` | `content: String, finishReason: Option[String], toolCalls: List[ToolCall], id: Option[String] = None, created: Option[Long] = None, model: Option[String] = None, usage: Option[TokenUsage] = None, thinking: Option[String] = None` | `org.adk4s.core.streaming` | pre-existing |
| `ToolInput` | `name: String, arguments: String, callId: String` | `org.adk4s.core.tools` | pre-existing |
| `ToolOutput` | `name: String, result: String, callId: String, isError: Boolean = false` | `org.adk4s.core.tools` | pre-existing |
| `ToolExecutionResult` | `outputs: List[ToolOutput], failedTools: List[ToolExecutionFailure] = Nil, interruptSignal: Option[InterruptSignal] = None` | `org.adk4s.core.tools` | pre-existing |
| `ToolExecutionFailure` | `input: ToolInput, error: Throwable` | `org.adk4s.core.tools` | pre-existing |
| `ToolsNodeConfig` | `tools: List[Either[ToolWrapper, InvokableTool[IO]]] = Nil, unknownToolHandler: Option[...], ...` | `org.adk4s.core.tools` | pre-existing |
| `ToolWrapper` | `toolFunction: ToolFunction[?, ?]` (+ `executable` field per CLAUDE.md) | `org.adk4s.core.tools` | pre-existing |
| `LambdaConfig` | `name: Option[String] = None, description: Option[String] = None` | `org.adk4s.core.runnable` | pre-existing |
| `Simple` | `address: List[AddressSegment], info: String` | `org.adk4s.core.interrupt` | pre-existing (InterruptSignal variant) |
| `Stateful` | `address: List[AddressSegment], info: String, state: ujson.Value` | `org.adk4s.core.interrupt` | pre-existing (InterruptSignal variant) |
| `Composite` | `address: List[AddressSegment], info: String, state: ujson.Value, children: List[InterruptSignal]` | `org.adk4s.core.interrupt` | pre-existing (InterruptSignal variant) |
| `RunStep` | `name: String` | `org.adk4s.core.interrupt` | pre-existing |
| `MessageOutput` | `runPath: RunPath, message: String, role: String` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `ToolCallRequested` | `runPath: RunPath, toolName: String, arguments: String, callId: String` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `ToolCallCompleted` | `runPath: RunPath, toolName: String, result: String, callId: String, isError: Boolean` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `IterationCompleted` | `runPath: RunPath, iteration: Int, remainingSteps: Int` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `Interrupted` | `runPath: RunPath, signal: InterruptSignal` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `ErrorOccurred` | `runPath: RunPath, error: AdkError` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `TokenDelta` | `runPath: RunPath, delta: String` | `org.adk4s.core.interrupt` | pre-existing (AgentEvent variant) |
| `InterruptResult` | `address: List[AddressSegment], data: ujson.Value` | `org.adk4s.core.interrupt` | pre-existing |
| `Completed` | `output: String, messages: List[Message]` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) |
| `Interrupted` | `checkpointId: String, signal: InterruptSignal` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) |
| `Failed` | `error: AdkError` | `org.adk4s.orchestration.agent` | pre-existing (RunResult variant) |
| `CheckpointState` | `messages: List[SerializableCheckpointMessage], interruptSignalJson: String, agentName: String` | `org.adk4s.orchestration.agent` | pre-existing |
| `FieldMapping` | `from: FieldPath, to: FieldPath, fromNode: Option[NodeKey] = None` | `org.adk4s.orchestration.workflow` | pre-existing |
| `GraphConfig` | `maxRunSteps: Int = 100, graphName: Option[String] = None, maxParallelism: Int = 10` | `org.adk4s.orchestration.graph` | pre-existing |
| `Prompt` | `conversation: Conversation` | `org.adk4s.structured.core` | pre-existing |
| `OutputFormatOptions` | `prefix: Option[String] = None, unionSeparator: String = " \| ", hoistClasses: HoistStrategy = HoistStrategy.Auto, quoteClassFields: Boolean = false, enumValuePrefix: Option[String] = None, alwaysHoistEnums: Boolean = false, hoistedClassPrefix: String = "", mapStyle: MapStyle = MapStyle.Inline` | `org.adk4s.structured.core` | pre-existing |
| `Candidate` | `json: String, warnings: List[String]` | `org.adk4s.structured.sap` | pre-existing |
| `ParserConfig` | `maxRecoveryAttempts: Int = 3, allowPartialResults: Boolean = false, strictMode: Boolean = false` | `org.adk4s.structured.sap` | pre-existing |

> Note: several case classes appear in the scan as duplicates
> (`BookingArgs`, `BookingResult`, `AddRequest`, `AddResult`,
> `WeatherRequest`) — these are inline examples/test fixtures inside
> `ToolSchema.scala`, `StructuredToolCall.scala`, `StructuredToolFunction.scala`,
> `ToolInfer.scala`, NOT reusable domain types. Omitted from the table.

## Service Traits

<!-- Tagless final service interfaces parameterised on F[_]. -->

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `ChatModel[F[_]]` | `F` (no constraint on trait) | `generate`, `generate` (overloaded), `stream`, `stream` (overloaded), `streamContent`, `withConfig` | `org.adk4s.core.component` | pre-existing |
| `Tool[F[_]]` | `F` | `info`, `asToolFunction` | `org.adk4s.core.component` | pre-existing |
| `InvokableTool[F[_]]` | `F` | `run` | `org.adk4s.core.component` | pre-existing |
| `StreamableTool[F[_]]` | `F` | `runStream` | `org.adk4s.core.component` | pre-existing |
| `ToolCallingChatModel[F[_]]` | `F` | `tools`, `withTools`, `addTools`, `generateWithTools`, `streamWithTools` | `org.adk4s.core.component` | pre-existing |
| `ChatTemplate[F[_]]` | `F` | `format`, `formatConversation` | `org.adk4s.core.component` | pre-existing |
| `Retriever[F[_]]` | `F` (no constraint on trait; `Sync[F]` on `fromFunction`) | `retrieve(query, config: RetrieverConfig): F[List[Document]]`, `retrieveStream(query, config: RetrieverConfig): Stream[F, Document]` | `org.adk4s.core.component` | pre-existing — **REUSED by this change** (`MemoryRetriever` implements it) |
| `Embedder[F[_]]` | `F` | `embed`, `embedBatch`, `dimension` | `org.adk4s.core.component` | pre-existing |
| `StreamingLLMClient[F[_]]` | `F` | `stream`, `streamContent`, `complete` | `org.adk4s.core.streaming` | pre-existing |
| `StructuredToolCall[F[_]]` | `F` | `execute`, `executeRaw`, `function`, `extractor` | `org.adk4s.core.tools` | pre-existing |
| `TypedTool[F[_]]` | `F` | `name`, `description`, `execute`, `asInvokableTool` | `org.adk4s.core.tools` | pre-existing |
| `StateRef[F[_], S]` | `F` | `get`, `set`, `update`, `modify`, `getAndUpdate`, `updateAndGet` | `org.adk4s.orchestration.state` | pre-existing |
| `StructuredLLM[F[_]]` | `F` | `complete`, `completeRaw`, `completeTemplate`, `function`, `extractor`, `streamWithResult`, `streamWithResultRaw`, `completeValidated`, `streamPartial` | `org.adk4s.structured.core` | pre-existing |

## Smithy Models

<!-- Smithy IDL structures driving smithy4s codegen. All live in
     structured-llm-test-models/src/main/smithy/. These are TEST fixtures,
     not production domain types — recorded for completeness. -->

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|
| `Resume` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/resume.smithy` | pre-existing |
| `MarketingCampaign` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/marketing_campaign.smithy` | pre-existing |
| `Product` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/product_catalog.smithy` | pre-existing |
| `Traveler`, `TravelBooking` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/travel_booking.smithy` | pre-existing |
| `Invoice` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/invoice.smithy` | pre-existing |
| `Attendee`, `EventRegistration` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/event_registration.smithy` | pre-existing |
| `Address`, `Shipment` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/shipment.smithy` | pre-existing |
| `SupportTicket` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/support_ticket.smithy` | pre-existing |
| `Order` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/order.smithy` | pre-existing |
| `LoyaltyProgram` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/loyalty_program.smithy` | pre-existing |
| `Patient`, `HealthcareAppointment` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/healthcare_appointment.smithy` | pre-existing |
| `ProjectTask` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/project_task.smithy` | pre-existing |
| `VehicleInspection` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/vehicle_inspection.smithy` | pre-existing |
| `Payment` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/payment.smithy` | pre-existing |
| `CustomerProfile` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/customer_profile.smithy` | pre-existing |
| `InventoryItem` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/inventory_item.smithy` | pre-existing |
| `HRCandidate` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/hr_candidate.smithy` | pre-existing |
| `BankTransaction` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/bank_transaction.smithy` | pre-existing |
| `SubscriptionPlan` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/subscription_plan.smithy` | pre-existing |
| `InsuranceClaim` | structure | (smithy4s-generated) | `structured-llm-test-models/src/main/smithy/insurance_claim.smithy` | pre-existing |
| `examples.smithy` structures | structure | `CategoryClassification`, `RoleDetection`, `QueryClassification`, `ChainRoute`, `PlanExtraction`, `PlanStep`, `StepList`, `StepItem`, `ListParsing`, `ListItem`, `SchemaExtraction`, `ExtractionMetadata`, `SpecialistDelegation`, `TypedIntermediate`, `GraphCompletion` | `structured-llm-test-models/src/main/smithy/examples.smithy` | pre-existing |
| `common.smithy` shapes | structure | `countryCodeFormat`, `emailFormat`, `hexColorCodeFormat`, `languageCodeFormat`, `languageTagFormat` (Smithy trait definitions) | `structured-llm-test-models/src/main/smithy/common.smithy` | pre-existing |

> This change introduces NO new Smithy models. `AgentMemory` is a Scala trait,
> not an IDL service — memory backends are out of scope.

## ScalaCheck Generators

<!-- Reusable generators for property-based tests. The project uses Hedgehog
     (NOT ScalaCheck); the scanner labels them "ScalaCheck Generators" by
     template convention but they are Hedgehog `Gen[_]` values. -->

| Generator | Generates | Location | Introduced By |
|-----------|----------|----------|---------------|
| `genRoleString` | `Gen[String]` | `adk4s-core/src/test/.../MessageTypeDedupSerializationSpec.scala` | pre-existing |
| `genContent` | `Gen[String]` | `adk4s-core/src/test/.../MessageTypeDedupSerializationSpec.scala` | pre-existing |
| `genSerializableMessage` | `Gen[SerializableMessage]` | `adk4s-core/src/test/.../MessageTypeDedupSerializationSpec.scala` | pre-existing |
| `genMaxAttempts` | `Gen[Int]` | `structured-llm/src/test/.../MiddlewareAdoptionSpec.scala` | pre-existing |
| `genSystemMsg` | `Gen[SystemMessage]` | `structured-llm/src/test/.../MessageTypeDedupSpec.scala` | pre-existing |
| `genUserMsg` | `Gen[UserMessage]` | `structured-llm/src/test/.../MessageTypeDedupSpec.scala` | pre-existing |
| `genAsstMsg` | `Gen[AssistantMessage]` | `structured-llm/src/test/.../MessageTypeDedupSpec.scala` | pre-existing |
| `genToolMsg` | `Gen[ToolMessage]` | `structured-llm/src/test/.../MessageTypeDedupSpec.scala` | pre-existing |
| `genMessage` | `Gen[Llm4sMessage]` | `structured-llm/src/test/.../MessageTypeDedupSpec.scala` | pre-existing |
| `genConversation` | `Gen[Conversation]` | `structured-llm/src/test/.../MessageTypeDedupSpec.scala` | pre-existing |
| `genNonEmptyPrompt` | `Gen[Prompt]` | `structured-llm/src/test/.../MessageTypeDedupSpec.scala` | pre-existing |

> This change will add new Hedgehog generators for `Episode` and `MemoryHit`
> in `adk4s-memory-api/src/test/scala/org/adk4s/memory/Generators.scala`.
> They will be appended here during the apply phase.

## Cats Effect Resources and Middleware

<!-- Shared resources and middleware that specs may depend on. -->

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
| `AgentEventEmitter` | `fs2.concurrent.Topic`-backed emitter | Hierarchical event scoping via `scoped(RunStep)` | `org.adk4s.core.interrupt` | pre-existing (not reused — no `AgentEvent` variants added this change) |
| `CheckpointStore` | trait (`InMemoryCheckpointStore` for dev) | Persist interrupt/resume checkpoint state | `org.adk4s.orchestration.interrupt` | pre-existing (not reused — orchestration hook deferred) |
| `Ref[F, Vector[Episode]]` | cats-effect `Ref` | Backing store for `InMemoryAgentMemory` (NEW) | `org.adk4s.memory` | **spec:agent-memory** (this change) |

## Concepts This Change Will Introduce

<!-- Preview of NEW concepts (not yet in the codebase). Recorded here so the
     apply phase does NOT re-create them and so later specs can reuse them.
     These entries are marked "spec:<name>" and become "pre-existing" once
     implemented. -->

| Type | Kind | Package | Introduced By |
|------|------|---------|---------------|
| `AgentMemory[F[_]]` | service trait | `org.adk4s.memory` | spec:agent-memory |
| `Episode` | case class | `org.adk4s.memory` | spec:agent-memory |
| `SourceType` | enum | `org.adk4s.memory` | spec:agent-memory |
| `EpisodeOutcome` | case class | `org.adk4s.memory` | spec:agent-memory |
| `MemoryHit` | case class | `org.adk4s.memory` | spec:agent-memory |
| `TemporalScope` | case class | `org.adk4s.memory` | spec:agent-memory |
| `InMemoryAgentMemory[F[_]]` | class (main scope) | `org.adk4s.memory` | spec:agent-memory |
| `MemoryRetriever` | object/factory | `org.adk4s.memory` | spec:memory-retriever-bridge |
| `AgentMemoryLaws` | case class | `org.adk4s.memory.testkit` | spec:memory-testkit |

## Consistency Check

<!-- Per the schema instruction: verify recorded paths are importable,
     constraints match source, generators compile. -->

- **Package paths**: all recorded package paths (`org.adk4s.core.component`,
  `org.adk4s.core.interrupt`, `org.adk4s.core.tools`, `org.adk4s.core.error`,
  `org.adk4s.core.types`, `org.adk4s.core.runnable`, `org.adk4s.core.streaming`,
  `org.adk4s.orchestration.agent`, `org.adk4s.orchestration.wiograph`,
  `org.adk4s.orchestration.chain`, `org.adk4s.orchestration.graph`,
  `org.adk4s.orchestration.branch`, `org.adk4s.orchestration.workflow`,
  `org.adk4s.orchestration.state`, `org.adk4s.orchestration.interrupt`,
  `org.adk4s.structured.core`, `org.adk4s.structured.sap`,
  `org.adk4s.structured.template`) match real `package` clauses in the
  scanned sources.
- **Opaque type constraints**: confirmed there is NO Iron/refined library in
  the stack; the three opaque types (`RunPath`, `NodeKey`, `FieldPath`) are
  plain newtypes without constraints. The template's "Iron Constraint" column
  is retained for schema compatibility but marked "(none — plain opaque type)".
- **Generators compile**: the recorded `Gen[_]` values live in test sources
  that are part of the sbt test graph (`sbt <module>/Test/compile`). They are
  Hedgehog `Gen` (the scanner labels them "ScalaCheck" by template convention).
- **Scanner mis-parses corrected**:
  - `AgentToolConfig` field list was a scaladoc comment — corrected by reading
    `AgentTool.scala` (actual fields not recorded here; not relevant to this
    change).
  - `ToolsNodeConfig` / `ToolsNodeConfigBuilder` field lists were truncated by
    the scanner — recorded as `...` above; not relevant to this change.
  - Anonymous enum bodies labelled `values`/`value`/`definitions`/`prefixing`
    by the scanner are Smithy IDL renderings inside `OutputFormat.scala`, not
    Scala domain enums — omitted.
  - Duplicate `BookingArgs`/`BookingResult`/`AddRequest`/`AddResult`/
    `WeatherRequest` entries are inline examples in tool-schema docs/tests —
    omitted as non-reusable.
- **Discrepancies**: none blocking. The scanner's per-module requirement
  (no top-level `src/`) is a tooling note, not a codebase issue.
