# Concept Inventory

<!-- Existing domain types, traits, enums, and wire models in the project that
     this change will reuse or extend. APPEND ONLY during apply. -->

## Opaque Types

| Type | Underlying | Package | Introduced By |
|------|-----------|---------|---------------|
| `Schema[A]` | `SchemaData[A]` (smithyDefinition: String, description: Option[String], smithySchema: SmithySchema[A]) | `org.adk4s.structured.core` | existing — spec:structured-llm-examples |

## Sealed Traits and Enums

| Type | Kind | Variants | Package | Introduced By |
|------|------|----------|---------|---------------|
| `ParseError` | sealed trait | `JsonSyntaxError`, `SchemaViolation`, `MissingRequiredField`, `UnexpectedEnumValue`, `NoJsonFound` | `org.adk4s.structured.core` | existing |
| `StructuredLLMError` | sealed trait | `LLMCallFailed(underlying: LLMError, prompt: Prompt)`, `ParseFailed(errors: List[ParseError], rawResponse: String)`, `EmptyResponse(prompt: Prompt)` | `org.adk4s.structured.core` | existing |
| `Role` | enum | `System`, `User`, `Assistant`, `Tool` | `org.adk4s.structured.core` | existing |
| `ParseResult[+A]` | enum | `Success(value: A, warnings: List[String])`, `Failure(errors: List[ParseError])` | `org.adk4s.structured.core` | existing |
| `SectionType` | enum | `System`, `User`, `Assistant`, `Raw` | `org.adk4s.structured.template` | existing |
| `RetryPolicy` (llm4s) | sealed trait | `ExponentialBackoff`, `LinearBackoff`, `FixedDelay`, `NoRetry`, `Custom` | `org.llm4s.reliability` | existing (llm4s 0.3.4) |
| `GuardrailAction` (llm4s) | sealed trait | `Block`, `Fix`, `Warn`, `Allow` | `org.llm4s.agent.guardrails` | existing (llm4s 0.3.4) |
| `GuardrailResult[+A]` (llm4s) | sealed trait | `Pass(value: A)`, `Fail(error: String, action: GuardrailAction)`, `Warn(value: A, warnings: List[String])` | `org.llm4s.agent.guardrails` | existing (llm4s 0.3.4) |

## Case Classes (Domain Value Objects)

| Type | Fields | Package | Introduced By |
|------|--------|---------|---------------|
| `Message` | `role: Role`, `content: String` | `org.adk4s.structured.core` | existing |
| `Prompt` | `messages: Vector[Message]` | `org.adk4s.structured.core` | existing |
| `SchemaData[A]` | `smithyDefinition: String`, `description: Option[String]`, `smithySchema: SmithySchema[A]` | `org.adk4s.structured.core` | existing |
| `ParserConfig` | `maxRecoveryAttempts: Int`, `allowPartialResults: Boolean`, `strictMode: Boolean` | `org.adk4s.structured.sap` | existing |
| `PromptSection` | `sectionType: SectionType`, `content: String` | `org.adk4s.structured.template` | existing |
| `AccumulatorSnapshot` (llm4s) | `content: String`, `thinking: Option[String]`, `toolCalls: Seq[ToolCall]`, `finishReason: Option[String]`, `promptTokens: Int`, `completionTokens: Int`, `thinkingTokens: Int` | `org.llm4s.llmconnect.streaming` | existing (llm4s 0.3.4) |

## Service Traits

| Trait | Type Param | Methods | Package | Introduced By |
|-------|-----------|---------|---------|---------------|
| `StructuredLLM[F[_]]` | `F[_]` | `complete[A: Schema]`, `completeRaw[A: Schema]`, `completeTemplate[I, A: Schema]`, `function[I, A: Schema]`, `extractor[A: Schema]`, `streamWithResult[A: Schema]`, `streamWithResultRaw[A: Schema]` | `org.adk4s.structured.core` | existing |
| `PromptTemplate[-I]` | contravariant `I` | `render(input: I): Prompt`, `andThen`, `contramap`, `expecting[A: Schema]` | `org.adk4s.structured.core` | existing |
| `LLMMiddleware` (llm4s) | none | `name: String`, `wrap(next: LLMClient): LLMClient` | `org.llm4s.llmconnect.middleware` | existing (llm4s 0.3.4) |
| `Guardrail[A]` (llm4s) | `A` | `validate(value: A): Result[A]`, `name: String`, `description: Option[String]`, `compose(other: Guardrail[A]): Guardrail[A]` | `org.llm4s.agent.guardrails` | existing (llm4s 0.3.4) |
| `OutputGuardrail` (llm4s) | extends `Guardrail[String]` | inherits `validate(String): Result[String]` | `org.llm4s.agent.guardrails` | existing (llm4s 0.3.4) |

## Smithy Models

| Model | Kind | Operations/Fields | Location | Introduced By |
|-------|------|-------------------|----------|---------------|
| `resume.smithy` | structure | `Resume` (name, email, skills, experience) | `structured-llm-test-models/src/main/smithy/resume.smithy` | existing |
| `bank_transaction.smithy` | structure | `BankTransaction` | `structured-llm-test-models/src/main/smithy/` | existing |
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

## Cats Effect Resources and Middleware

| Resource | Type | Purpose | Package | Introduced By |
|----------|------|---------|---------|---------------|
| `ReliableClient` (llm4s) | `final class extends LLMClient` | Wraps LLMClient with retry policy + deadline; retries on `LLMError` only | `org.llm4s.reliability` | existing (llm4s 0.3.4) |
| `StreamingAccumulator` (llm4s) | `class` | Accumulates streaming chunks (content, thinking, tool calls, tokens) into `Completion` | `org.llm4s.llmconnect.streaming` | existing (llm4s 0.3.4) |
