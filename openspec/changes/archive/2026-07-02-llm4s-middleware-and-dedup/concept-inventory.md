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

     SCAN METHOD: Manual grep-based scan (semantic scanner found 0 concepts due
     to a path resolution issue; manual scan performed 2026-07-01). All package
     paths verified against source `package` clauses. -->

## Opaque Types

<!-- No Iron refined types in this project (Iron is not a dependency).
     Opaque types below are unrefined newtypes. -->

| Type | Underlying | Constraint | Package | Introduced By |
|------|-----------|-----------------|---------|---------------|
| `Schema[A]` | `Schema.SchemaData[A]` | none | `org.adk4s.structured.core` | existing — spec:structured-llm-examples |
| `ToolSchema[A]` | `ToolSchema.SchemaData[A]` | none | `org.adk4s.core.tools` | existing |
| `ToolFunctionAdapter[T, R]` | `ToolFunction[T, R]` | none | `org.adk4s.core.tools` | existing — makes `ToolFunction` behave like `SafeToolExecutable` |
| `NodeKey` | `String` | none | `org.adk4s.core.types` | existing |
| `FieldPath` | `Vector[String]` | none | `org.adk4s.core.types` | existing |
| `RunPath` | `List[RunStep]` | none | `org.adk4s.core.interrupt` | existing |

## Sealed Traits and Enums

<!-- Closed type hierarchies that enable exhaustive pattern matching. -->

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `StructuredLLMError` | sealed trait (extends Throwable) | `LLMCallFailed(underlying: LLMError, prompt: Prompt)` — cause wired via `initCause(LLMErrorCause(underlying))`, `ParseFailed(errors: List[ParseError], rawResponse: String)`, `EmptyResponse(prompt: Prompt)`, `ValidationFailed(failedAsserts: Vector[String])`, `Enriched(underlying: StructuredLLMError, attempts: Vector[AttemptRecord])` | `org.adk4s.structured.core` | existing — **LLMCallFailed cause wired (spec:error-hierarchy-dedup)** |
| `ParseError` | sealed trait (extends Throwable) | `JsonSyntaxError`, `SchemaViolation`, `MissingRequiredField`, `UnexpectedEnumValue`, `NoJsonFound` | `org.adk4s.structured.core` | existing |
| `ParseResult[+A]` | enum | `Success(value: A, warnings: List[String])`, `Failure(errors: List[ParseError])` | `org.adk4s.structured.core` | existing |
| `Role` | REMOVED — deprecated type alias `type Role = org.llm4s.llmconnect.model.MessageRole` | `org.adk4s.structured.core` | **removed (spec:message-type-dedup)** — use llm4s `MessageRole` directly |
| `RetryTrigger` | enum | `LLMError`, `ParseFailure`, `ValidationFailure`, `All`; `shouldRetry(error: Throwable): Boolean` (widened from `StructuredLLMError`) | `org.adk4s.structured.core` | existing — **shouldRetry widened (spec:error-hierarchy-dedup)** |
| `ParseRetryTrigger` | enum | `ParseFailed`, `ValidationFailed`, `All`; `shouldRetry(error: Throwable): Boolean` | `org.adk4s.structured.core` | **introduced (spec:llm4s-middleware-adoption)** — controls parse-failure retry, distinct from `RetryTrigger` |
| `ClientStrategy` | enum | `Fallback(clients: Vector[LLMClient])`, `RoundRobin(clients: Vector[LLMClient])` | `org.adk4s.structured.core` | existing — spec:fallback-round-robin |
| `ConstraintLevel` | enum | `Check`, `Assert` | `org.adk4s.structured.core` | existing — spec:constraint-validation |
| `CheckStatus` | enum | `Succeeded`, `Failed` | `org.adk4s.structured.core` | existing — spec:constraint-validation |
| `HoistStrategy` | enum | `Auto`, `All`, `None`, `Subset` | `org.adk4s.structured.core` | existing — spec:output-format-rendering |
| `MapStyle` | enum | `Inline`, `Verbose` | `org.adk4s.structured.core` | existing — spec:output-format-rendering |
| `CompletionState` | enum | `Pending`, `Incomplete`, `Complete` | `org.adk4s.structured.sap` | existing — spec:semantic-streaming |
| `JsonishValue` | enum | `Null`, `Bool`, `Int`, `Float`, `Str`, `List`, `Object`, `AnyOf` | `org.adk4s.structured.sap` | existing — spec:type-aware-sap-coercion |
| `CoercionFlag` | enum | `ObjectToString`, `StrippedNonAlphaNumeric`, `StringToInt`, `StringToBool`, `SingleToArray`, `EnumFuzzyMatch`, `UnionVariantSelect` (and others) | `org.adk4s.structured.sap` | existing — spec:type-aware-sap-coercion |
| `AdkError` | sealed trait (extends Throwable) | `LlmCallError(underlying: LLMError)` — cause wired via `initCause(LLMErrorCause(underlying))`, `StructuredOutputError`, `TypeMismatchError`, `MissingFieldError`, `NodeNotFoundError`, `EdgeValidationError`, `MaxStepsExceededError`, `GraphCompiledError`, `GraphEntryMissingError`, `GraphEndNodesMissingError`, `ToolNotFoundError`, `ToolExecutionError`, `StateTypeMismatchError`, `NodeAlreadyExistsError`, `SourceNodeNotFoundError`, `NodeDoesNotExistError`, `FanInError`, `BranchTargetError`, `AgentInterruptedException`, `CheckpointNotFoundError`, `GenericError`, `NodeKeyError` | `org.adk4s.core.error` | existing — **LlmCallError cause wired (spec:error-hierarchy-dedup)** |
| `LLMErrorCause` | final class (extends RuntimeException) | `error: LLMError` | `org.adk4s.structured.core` | **new (spec:error-hierarchy-dedup)** |
| `ToolSchemaError` | sealed trait (extends Throwable) | `MissingRequiredField(fieldName, path)`, `TypeMismatch(expectedType, actualValue, path)`, `InvalidEnumValue(value, allowedValues, path)`, `DecodingFailed(msg, underlying)` | `org.adk4s.core.tools` | existing |
| `StructuredToolCallError` | sealed trait (extends Throwable) | `UnknownTool(toolName)`, `InvalidArguments(errors: List[ToolSchemaError])`, `ExecutionFailed(cause: Throwable)`, `ResultParsingFailed(message, rawJson: Value)` | `org.adk4s.core.tools` | existing |
| `InterruptSignal` | sealed trait (derives ReadWriter) | `Simple`, `Stateful(state: ujson.Value)`, `Composite(children)` | `org.adk4s.core.interrupt` | existing |
| `AddressSegment` | sealed trait (derives ReadWriter) | `Agent(name)`, `Tool(name)` | `org.adk4s.core.interrupt` | existing |
| `AgentEvent` | sealed trait | `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted`, `Interrupted`, `ErrorOccurred`, `TokenDelta` | `org.adk4s.core.interrupt` | existing |
| `LLMError` (llm4s) | sealed trait | (llm4s-internal variants) | `org.llm4s.error` | existing (llm4s 0.3.4) |
| `Message` (llm4s) | sealed trait | `SystemMessage`, `UserMessage`, `AssistantMessage`, `ToolMessage` | `org.llm4s.llmconnect.model` | existing (llm4s 0.3.4) — **adopted (spec:message-type-dedup)** |
| `RetryPolicy` (llm4s) | sealed trait | `ExponentialBackoff`, `LinearBackoff`, `FixedDelay`, `NoRetry`, `CustomRetryPolicy` | `org.llm4s.reliability` | existing (llm4s 0.3.4) — **available for adoption (spec:llm4s-middleware-adoption)** — used via `ReliableClient` middleware |

## Case Classes (Domain Value Objects)

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| `Message` (adk4s) | REMOVED — deprecated type alias `type Message = org.llm4s.llmconnect.model.Message` | `org.adk4s.structured.core` | **removed (spec:message-type-dedup)** — use llm4s `Message` directly |
| `Prompt` | `conversation: Conversation` (was `messages: Vector[Message]`) | `org.adk4s.structured.core` | existing — **refactored to wrap Conversation (spec:message-type-dedup)** |
| `SchemaData[A]` | `smithyDefinition: String`, `description: Option[String]`, `smithySchema: SmithySchema[A]`, `constraints: Vector[Constraint[A]]` | `org.adk4s.structured.core` | existing |
| `ParserConfig` | `maxRecoveryAttempts: Int`, `allowPartialResults: Boolean`, `strictMode: Boolean` | `org.adk4s.structured.sap` | existing |
| `Constraint[A]` | `label: String`, `level: ConstraintLevel`, `predicate: A => Boolean` | `org.adk4s.structured.core` | existing — spec:constraint-validation |
| `ResponseCheck` | `name: String`, `expression: String`, `status: CheckStatus` | `org.adk4s.structured.core` | existing — spec:constraint-validation |
| `ValidationResult[A]` | `value: A`, `checks: Vector[ResponseCheck]` | `org.adk4s.structured.core` | existing — spec:constraint-validation |
| `AttemptRecord` | `client: String`, `error: StructuredLLMError`, `rawResponse: String`, `timestamp: Long` | `org.adk4s.structured.core` | existing — spec:error-enrichment |
| `OutputFormatOptions` | `hoistStrategy: HoistStrategy`, `mapStyle: MapStyle`, `quoteFields: Boolean` | `org.adk4s.structured.core` | existing — spec:output-format-rendering |
| `StreamingBehavior` | `done: List[String]`, `needed: List[String]`, `withState: Boolean` | `org.adk4s.structured.sap` | existing — spec:semantic-streaming |
| `StreamState[A]` | `value: A`, `state: CompletionState` | `org.adk4s.structured.sap` | existing — spec:semantic-streaming |
| `CoercionScore` | `value: Int` | `org.adk4s.structured.sap` | existing — spec:type-aware-sap-coercion |
| `BamlValueWithFlags[A]` | `value: A`, `flags: Set[CoercionFlag]`, `score: CoercionScore` | `org.adk4s.structured.sap` | existing — spec:type-aware-sap-coercion |
| `ParsingError` | `message: String`, `path: List[String]` | `org.adk4s.structured.sap` | existing — spec:type-aware-sap-coercion |
| `DynamicValue` | `document: Document` | `org.adk4s.structured.core` | existing — spec:dynamic-type-builder |
| `ToolWrapper` | `toolFunction: ToolFunction[?, ?]` (single field; `name`/`description`/`execute` delegate to it) | `org.adk4s.core.tools` | existing — **refactored (spec:tool-abstraction-dedup)** |
| `ToolsNodeConfig` | `tools: List[Either[ToolWrapper, InvokableTool[IO]]]`, `unknownToolHandler`, `executeSequentially`, `middlewares`, `argumentsHandler`, `maxConcurrency`, `eventEmitter` | `org.adk4s.core.tools` | existing |
| `StructuredToolFunction[I, O]` | `name`, `description`, `inputSchema: ToolSchema[I]`, `outputSchema: ToolSchema[O]`, `handler: I => Either[ToolSchemaError, O]`; extension: `toToolFunction: ToolFunction[ujson.Value, ujson.Value]`, `toSafeExecutable: SafeToolExecutable`, `toToolWrapper: ToolWrapper` | `org.adk4s.core.tools` | existing — **toToolFunction added (spec:tool-abstraction-dedup)** |
| `ToolInput` | `name: String`, `arguments: ujson.Value` | `org.adk4s.core.tools` | existing |
| `ToolOutput` | `result: ujson.Value` | `org.adk4s.core.tools` | existing |
| `ToolExecutionResult` | `outputs: List[ToolOutput]`, `interruptSignal: Option[InterruptSignal]` | `org.adk4s.core.tools` | existing |
| `ToolExecutionFailure` | `toolName: String`, `error: Throwable` | `org.adk4s.core.tools` | existing |
| `AdkToolInfo` | `name: String`, `description: String`, `parameters: ujson.Value` | `org.adk4s.core.component` | existing |
| `ChatModelConfig` | (model config fields) | `org.adk4s.core.component` | existing |
| `AgentToolConfig` | `withFullChatHistory: Boolean`, `inputSchema: Option[ujson.Value]`, `maxSteps: Int` | `org.adk4s.core.component` | existing |
| `AgentToolState` | `messages: List[SerializableMessage]`, `iterationCount: Int` (derives ReadWriter) | `org.adk4s.core.component` | existing — **persistence-affected (spec:message-type-dedup)** |
| `SerializableMessage` | `role: String`, `content: String` (derives ReadWriter) | `org.adk4s.core.component` | existing — already lossy (drops toolCallId/toolCalls); persistence-affected |
| `RunStep` | `name: String` | `org.adk4s.core.interrupt` | existing |
| `InterruptResult` | (interrupt resumption data) | `org.adk4s.core.interrupt` | existing |
| `Conversation` (llm4s) | `messages: Seq[Message]` | `org.llm4s.llmconnect.model` | existing (llm4s 0.3.4) — **target for adoption as Prompt payload (spec:message-type-dedup)** |

## Service Traits

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `StructuredLLM[F[_]]` | `F[_]` | `complete[A: Schema]`, `completeRaw[A: Schema]`, `completeTemplate[I, A: Schema]`, `function[I, A: Schema]`, `extractor[A: Schema]`, `streamWithResult[A: Schema]`, `streamWithResultRaw[A: Schema]`, `completeValidated[A: Schema]`, `streamPartial[A: Schema]` | `org.adk4s.structured.core` | existing |
| `PromptTemplate[-I]` | contravariant `I` | `render(input: I): Prompt`, `andThen`, `contramap`, `expecting[A: Schema]` | `org.adk4s.structured.core` | existing |
| `Partial[A]` | `A` | (typeclass — partial schema derivation) | `org.adk4s.structured.core` | existing — spec:partial-types-streaming |
| `Tool[F[_]]` | `F[_]` | `info: AdkToolInfo` | `org.adk4s.core.component` | existing |
| `InvokableTool[F[_]]` | extends `Tool[F]` | `run(arguments: ujson.Value): F[ujson.Value]` | `org.adk4s.core.component` | existing |
| `StreamableTool[F[_]]` | extends `Tool[F]` | `runStream(arguments: ujson.Value): Stream[F, String]` | `org.adk4s.core.component` | existing |
| `ChatModel[F[_]]` | `F[_]` | `generate`, `stream`, `streamContent`, `withConfig` | `org.adk4s.core.component` | existing |
| `ToolCallingChatModel[F[_]]` | extends `ChatModel[F]` | (tool-calling loop methods) | `org.adk4s.core.component` | existing |
| `Agent` | none | `name`, `description`, `generate(messages, maxSteps): IO[AssistantMessage]` | `org.adk4s.core.component` | existing |
| `ChatTemplate[F[_], V]` | `F[_]`, `V` | (template methods) | `org.adk4s.core.component` | existing |
| `Retriever[F[_]]` | `F[_]` | (retrieval methods) | `org.adk4s.core.component` | existing |
| `Embedder[F[_]]` | `F[_]` | (embedding methods) | `org.adk4s.core.component` | existing |
| `Runnable[I, O]` | `I`, `O` | `invoke`, `stream`, `collect`, `transform`, `andThen`, `parallel`, `timeout`, `handleError`, `contramap` | `org.adk4s.core.runnable` | existing |
| `Lambda[I, O]` | `I`, `O` | (Runnable with name/description) | `org.adk4s.core.runnable` | existing |
| `ToRunnable[C, I, O]` | `C`, `I`, `O` | (component-to-runnable conversion) | `org.adk4s.core.runnable` | existing |
| `BatchExecutor[I, O]` | `I`, `O` | (batch methods) | `org.adk4s.core.batch` | existing |
| `StreamingLLMClient[F[_]]` | `F[_]` | (streaming methods) | `org.adk4s.core.streaming` | existing |
| `StructuredToolCall[F[_]]` | `F[_]` | `execute[I, O]`, (structured tool call methods) | `org.adk4s.core.tools` | existing |
| `TypedTool[F[_], I, O]` | `F[_]`, `I`, `O` | (typed tool methods) | `org.adk4s.core.tools` | existing |
| `SafeToolExecutable` | none | `execute(args: ujson.Value): Either[ToolCallError, ujson.Value]` | `org.adk4s.core.tools` | existing |
| `LLMClient` (llm4s) | none | `complete`, `streamComplete` | `org.llm4s.llmconnect` | existing (llm4s 0.3.4) |
| `LLMMiddleware` (llm4s) | none | `name: String`, `wrap(next: LLMClient): LLMClient` | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) — **target for adoption (spec:llm4s-middleware-adoption)** |
| `ToolFunction[T, R]` (llm4s) | `T`, `R` | `name`, `description`, `parameters`, `execute` | `org.llm4s.toolapi` | existing (llm4s 0.3.4) |

## Smithy Models

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|
| `resume.smithy` | structure | `Resume` (name, email, skills, experience) | `structured-llm-test-models/src/main/smithy/resume.smithy` | existing |
| `bank_transaction.smithy` | structure | `BankTransaction` (transactionId, accountId, type, amount, currency, timestamp, description) | `structured-llm-test-models/src/main/smithy/bank_transaction.smithy` | existing |
| `customer_profile.smithy` | structure | `CustomerProfile` | `structured-llm-test-models/src/main/smithy/` | existing |
| `event_registration.smithy` | structure | `EventRegistration` | `structured-llm-test-models/src/main/smithy/` | existing |
| `healthcare_appointment.smithy` | structure | `HealthcareAppointment` | `structured-llm-test-models/src/main/smithy/` | existing |
| `hr_candidate.smithy` | structure | `HrCandidate` | `structured-llm-test-models/src/main/smithy/` | existing |
| `insurance_claim.smithy` | structure | `InsuranceClaim` | `structured-llm-test-models/src/main/smithy/` | existing |
| `inventory_item.smithy` | structure | `InventoryItem` | `structured-llm-test-models/src/main/smithy/` | existing |
| `invoice.smithy` | structure | `Invoice` | `structured-llm-test-models/src/main/smithy/` | existing |
| `loyalty_program.smithy` | structure | `LoyaltyProgram` | `structured-llm-test-models/src/main/smithy/` | existing |
| `marketing_campaign.smithy` | structure | `MarketingCampaign` | `structured-llm-test-models/src/main/smithy/` | existing |
| `order.smithy` | structure | `Order` | `structured-llm-test-models/src/main/smithy/` | existing |
| `payment.smithy` | structure | `Payment` | `structured-llm-test-models/src/main/smithy/` | existing |
| `product_catalog.smithy` | structure | `ProductCatalog` | `structured-llm-test-models/src/main/smithy/` | existing |
| `project_task.smithy` | structure | `ProjectTask` | `structured-llm-test-models/src/main/smithy/` | existing |
| `shipment.smithy` | structure | `Shipment` | `structured-llm-test-models/src/main/smithy/` | existing |
| `subscription_plan.smithy` | structure | `SubscriptionPlan` | `structured-llm-test-models/src/main/smithy/` | existing |
| `support_ticket.smithy` | structure | `SupportTicket` | `structured-llm-test-models/src/main/smithy/` | existing |
| `travel_booking.smithy` | structure | `TravelBooking` | `structured-llm-test-models/src/main/smithy/` | existing |
| `vehicle_inspection.smithy` | structure | `VehicleInspection` | `structured-llm-test-models/src/main/smithy/` | existing |
| `examples.smithy` | (mixed) | (example schemas) | `structured-llm-test-models/src/main/smithy/examples.smithy` | existing |

## Hedgehog Generators

<!-- Reusable generators for property-based tests. This project uses Hedgehog
     (NOT ScalaCheck). Generators are `Gen[A]` from `hedgehog`. -->

| Generator | Generates | Location | Introduced By |
|-----------|----------|----------|---------------|
| `genClientName` | `Gen[String]` | `structured-llm/src/test/scala/org/adk4s/structured/FallbackRoundRobinSpec.scala:50` | existing — spec:fallback-round-robin |
| `genTestInput` | `Gen[TestInput]` | `structured-llm/src/test/scala/org/adk4s/structured/StructuredTestFrameworkSpec.scala:36-38` | existing — spec:structured-test-framework |
| `genFieldName` / `genFieldType` / `genDynamicRecord` | `Gen[String]` / `Gen[String]` / `Gen[DynamicRecord]` | `structured-llm/src/test/scala/org/adk4s/structured/DynamicTypeBuilderSpec.scala:21-22` | existing — spec:dynamic-type-builder |
| `genAttemptRecord` | `Gen[AttemptRecord]` | `structured-llm/src/test/scala/org/adk4s/structured/ErrorEnrichmentSpec.scala:20` | existing — spec:error-enrichment |
| `genStreamingBehavior` | `Gen[StreamingBehavior]` | `structured-llm/src/test/scala/org/adk4s/structured/SemanticStreamingSpec.scala:24` | existing — spec:semantic-streaming |
| `genOutputFormatOptions` | `Gen[OutputFormatOptions]` | `structured-llm/src/test/scala/org/adk4s/structured/OutputFormatRenderingSpec.scala:34` | existing — spec:output-format-rendering |
| `genJsonishValue` / `genCoercionScore` | `Gen[JsonishValue]` / `Gen[CoercionScore]` | `structured-llm/src/test/scala/org/adk4s/structured/TypeAwareSapCoercionSpec.scala:49,64` | existing — spec:type-aware-sap-coercion |
| `genRetryTrigger` / `genMaxAttempts` | `Gen[RetryTrigger]` / `Gen[Int]` | `structured-llm/src/test/scala/org/adk4s/structured/RetryPoliciesSpec.scala:28,47` | existing — spec:retry-policies |
| `genConstraint` / `genValue` | `Gen[Constraint[Int]]` / `Gen[Int]` | `structured-llm/src/test/scala/org/adk4s/structured/ConstraintValidationSpec.scala:38,56` | existing — spec:constraint-validation |
| `genSmartQuote` / `genAsciiQuote` | `Gen[Char]` | `structured-llm/src/test/scala/org/adk4s/structured/UnicodeQuoteNormalizationSpec.scala:43-44,58-59` | existing — spec:unicode-quote-normalization |

## Cats Effect Resources and Middleware

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
| `ReliableClient` (llm4s) | `final class extends LLMClient` | Wraps `LLMClient` with `RetryPolicy` + deadline; retries on `LLMError` only | `org.llm4s.reliability` | existing (llm4s 0.3.4) — **available for adoption (spec:llm4s-middleware-adoption)** — can be passed as a middleware |
| `MiddlewareClient` (llm4s) | `class` | Composes a list of `LLMMiddleware` over an `LLMClient` | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) — **target for adoption** |
| `LoggingMiddleware` (llm4s) | `class` | Logging with `ContentRedactor` redaction | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) — **target for opt-in wiring** |
| `RateLimitingMiddleware` (llm4s) | `class` | Token-bucket rate limiting | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) — **target for opt-in wiring** |
| `CachingMiddleware` (llm4s) | `class` | Semantic response caching | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) |
| `InputSanitizationMiddleware` (llm4s) | `class` | Input sanitization | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) |
| `MetricsMiddleware` (llm4s) | `class` | Metrics collection | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) |
| `RequestIdMiddleware` (llm4s) | `class` | Request ID propagation | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) |
| `LLMClientPipeline` (llm4s) | `class` | Pipeline composition of middlewares | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) |
| `RetryStructuredLLM` (adk4s) | `private class extends StructuredLLM[F]` | Wraps `StructuredLLM` with hand-rolled `Retry.withRetry` loop around `complete*` calls | `org.adk4s.structured.core` | existing — spec:retry-policies — **deprecated (spec:llm4s-middleware-adoption)** — `fromClientWithRetry` now delegates to `fromClientWithMiddlewares` with `ParseRetryTrigger` |
| `StreamingAccumulator` (llm4s) | `class` | Accumulates streaming chunks into `Completion` | `org.llm4s.llmconnect.streaming` | existing (llm4s 0.3.4) |

## Consistency Check

- All recorded package paths verified against source `package` clauses (manual scan, 2026-07-01).
- All llm4s types verified against `core_3-0.3.4.jar` class listing.
- `AgentToolState`/`SerializableMessage` already use llm4s `Message` in `SerializableMessage.toMessage` but `fromMessage` takes llm4s `Message` too (line 49-58 of `AgentTool.scala`) — the lossy conversion is in `MessageConverter` (adk4s-core) and `StructuredLLMImpl.toConversation` (structured-llm), not in `AgentTool`.
- `ToolWrapper` now stores a single `toolFunction: ToolFunction[?, ?]` field (refactored in spec:tool-abstraction-dedup). The old `originalToolFunction: Option[ToolFunction[?, ?]]` and `executable: SafeToolExecutable` fields are removed. `StructuredToolFunction.toToolFunction` synthesizes a `ToolFunction[ujson.Value, ujson.Value]` so all tools are visible in `toToolRegistry`.
- No discrepancies found between recorded concepts and source.
