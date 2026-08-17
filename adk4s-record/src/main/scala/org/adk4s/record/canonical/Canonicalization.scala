package org.adk4s.record.canonical

import org.adk4s.core.json.JsonValueCodec
import org.adk4s.core.tools.ToolInput
import org.adk4s.record.ModelCallRequest
import org.adk4s.record.keyVersion
import org.llm4s.llmconnect.model.AssistantMessage as LlmAssistantMessage
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage as LlmSystemMessage
import org.llm4s.llmconnect.model.ToolCall as LlmToolCall
import org.llm4s.llmconnect.model.ToolMessage as LlmToolMessage
import org.llm4s.llmconnect.model.UserMessage as LlmUserMessage

// ── Canonicalization — pure canonicalization functions ──────────────────
// Lives in org.adk4s.record.canonical. MUST NOT import effect or stream
// libraries, or the llm4s LLM client. May import llm4s model types
// (Conversation, Message, etc.) and org.adk4s.core types.
//
// AR-REC-1: No ambient nondeterminism (no System.currentTimeMillis,
// Instant.now, java.util.Random, UUID.randomUUID, .hashCode).
// AR-REC-2: No unordered iteration (no Map/Set .iterator without sort).
//
// Uses smithy4s-generated types from canonical_form.smithy (CanonicalForm,
// CanonicalBody, ModelBody, ToolBody, EmbeddingBody, CanonicalMessage, etc.)
// for type-safe canonical form construction and deterministic JSON
// serialization via smithy4s.json.Json.
//
// ujson appears only at the llm4s boundary (ToolCall.arguments: ujson.Value),
// converted to smithy4s.Document via JsonValueCodec.
//
// spec: add-adk4s-record/call-key — Requirement: Canonicalization is a pure total function
object Canonicalization:

  /**
   * Construct a canonical form from a model call request.
   *
   * Includes all output-affecting fields:
   * - provider, model (identity)
   * - systemPrompt
   * - normalized conversation (tool-call ids → positional)
   * - tool definitions (name, description, schema — from req.tools)
   * - completion options: temperature, maxTokens, topP, presencePenalty,
   *   frequencyPenalty, reasoning, budgetTokens, responseFormat
   * - stopSequences (from ModelCallRequest, not CompletionOptions)
   * - outputSchema (if present)
   * - rollout (if present)
   * - keyVersion
   *
   * Excludes non-output-affecting fields:
   * - providerRequestId, latencyMs, tokenUsage, timestamp
   */
  def fromModelCall(req: ModelCallRequest): CanonicalForm =
    val normalizedConv = normalizeToolCallIds(req.conversation)
    val body = ModelBody(
      provider = req.provider,
      model = req.model,
      systemPrompt = req.systemPrompt,
      messages = conversationToCanonical(normalizedConv),
      tools = toolsToCanonical(req.tools),
      temperature = req.options.temperature,
      topP = req.options.topP,
      maxTokens = req.options.maxTokens,
      presencePenalty = req.options.presencePenalty,
      frequencyPenalty = req.options.frequencyPenalty,
      reasoning = req.options.reasoning.map(_.toString),
      budgetTokens = req.options.budgetTokens,
      responseFormat = req.options.responseFormat.map(_.toString),
      stopSequences = req.stopSequences,
      outputSchema = req.outputSchema,
      rollout = req.rollout.map(_.value)
    )
    CanonicalForm(keyVersion, CallKind.MODEL, CanonicalBody.model(body))

  /** Construct a canonical form from a tool call input. */
  def fromToolCall(input: ToolInput): CanonicalForm =
    val body = ToolBody(
      name = input.name,
      arguments = input.arguments,
      callId = input.callId
    )
    CanonicalForm(keyVersion, CallKind.TOOL, CanonicalBody.tool(body))

  /** Construct a canonical form from an embedding request. */
  def fromEmbedding(text: String, model: String): CanonicalForm =
    val body = EmbeddingBody(
      text = text,
      model = model
    )
    CanonicalForm(keyVersion, CallKind.EMBEDDING, CanonicalBody.embedding(body))

  // ── toolsToCanonical — convert tool defs to canonical tool defs ──────
  private def toolsToCanonical(tools: List[org.adk4s.record.ToolDef]): List[CanonicalToolDef] =
    tools.map { td =>
      CanonicalToolDef(
        name = td.name,
        description = td.description,
        schema = JsonValueCodec.fromUjson(ujson.read(td.schemaJson))
      )
    }

  // ── conversationToCanonical — convert llm4s conversation to canonical ─
  // Each message is converted to the corresponding CanonicalMessage variant.
  // Tool-call ids are already normalized (normalizeToolCallIds was applied).
  // ToolCall.arguments (ujson.Value) is converted to Document via
  // JsonValueCodec — the single boundary adapter.
  private def conversationToCanonical(conv: Conversation): List[CanonicalMessage] =
    conv.messages.map {
      case u: LlmUserMessage =>
        CanonicalMessage.user(UserMessage(u.content))
      case s: LlmSystemMessage =>
        CanonicalMessage.system(SystemMessage(s.content))
      case a: LlmAssistantMessage =>
        val toolCalls = a.toolCalls.map { tc =>
          CanonicalToolCall(
            id = tc.id,
            name = tc.name,
            arguments = JsonValueCodec.fromUjson(tc.arguments)
          )
        }.toList
        val assistantMsg = AssistantMessage(
          content = a.contentOpt,
          toolCalls = if toolCalls.isEmpty then None else Some(toolCalls)
        )
        CanonicalMessage.assistant(assistantMsg)
      case t: LlmToolMessage =>
        CanonicalMessage.tool(ToolMessage(t.content, t.toolCallId))
    }.toList

// ── normalizeToolCallIds — positional id normalization (REC-4) ──────────
// Replaces provider-generated tool-call ids with positional identifiers
// (call_0, call_1, ...) in call order, applied consistently to both
// assistant message tool-call ids and matching tool-reply message ids.
//
// The normalization is idempotent: normalizing an already-normalized
// conversation produces the same result (call_0 stays call_0).
//
// spec: add-adk4s-record/call-key — Requirement: Tool-call ids normalized to positional
def normalizeToolCallIds(conversation: Conversation): Conversation =
  val messages = conversation.messages

  // Phase 1: Walk the conversation in order, assigning positional ids
  // to each tool call as it appears in assistant messages.
  // Build a mapping from original id → positional id using a fold.
  val (idMapping, _) = messages.foldLeft(
    (Map.empty[String, String], 0)
  ) { case (acc @ (_, _), msg) =>
    msg match
      case a: LlmAssistantMessage =>
        a.toolCalls.foldLeft(acc) { case (innerAcc @ (innerMap, innerCounter), tc) =>
          if innerMap.contains(tc.id) then innerAcc
          else (innerMap.updated(tc.id, s"call_$innerCounter"), innerCounter + 1)
        }
      case _: LlmUserMessage   => acc
      case _: LlmSystemMessage => acc
      case _: LlmToolMessage   => acc
  }

  // Phase 2: Apply the mapping to all messages.
  // Already-normalized ids (call_N) map to themselves (idempotence).
  val newMessages = messages.map {
    case a: LlmAssistantMessage =>
      val newToolCalls = a.toolCalls.map { tc =>
        val newId = idMapping.getOrElse(tc.id, tc.id)
        LlmToolCall(newId, tc.name, tc.arguments)
      }
      LlmAssistantMessage(a.contentOpt, newToolCalls)
    case t: LlmToolMessage =>
      val newId = idMapping.getOrElse(t.toolCallId, t.toolCallId)
      LlmToolMessage(t.content, newId)
    case u: LlmUserMessage   => u
    case s: LlmSystemMessage => s
  }

  Conversation(newMessages)
