package org.adk4s.record

import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.llmconnect.model.UserMessage

// ── ToolDef — tool definition for canonicalization ─────────────────────
// Captures the fields that enter the canonical form: name, description,
// and schema (as a stable JSON string). Decouples the canonical form from
// the complex ToolFunction type (which requires SchemaDefinition[T] and
// a ReadWriter[R] to construct).
// spec: add-adk4s-record/call-key — Requirement: Canonical form includes output-affecting fields
final case class ToolDef(
  name: String,
  description: String,
  schemaJson: String
)

// ── ModelCallRequest — bundle of model call fields for canonicalization ─
// Local to adk4s.record; does NOT use org.adk4s.harness.ModelRequest (which
// adk4s-record does not depend on). Carries both output-affecting fields
// (included in canonical form) and non-output-affecting fields (excluded,
// used by NonAffectingMutation to verify key insensitivity).
final case class ModelCallRequest(
  provider: String,
  model: String,
  conversation: Conversation,
  tools: List[ToolDef],
  systemPrompt: String,
  options: CompletionOptions,
  rollout: Option[RolloutId] = None,
  outputSchema: Option[String] = None,
  stopSequences: List[String] = Nil,
  // Non-output-affecting fields (excluded from canonical form)
  providerRequestId: Option[String] = None,
  latencyMs: Option[Long] = None,
  tokenUsage: Option[Long] = None,
  timestamp: Option[Long] = None
)

// ── RequestMutation — output-affecting mutations for RL3 (key sensitivity) ─
// spec: add-adk4s-record/call-key — Requirement: Canonical form includes output-affecting fields
// spec: add-adk4s-record/recorder-laws — Requirement: RL3 and RL4 are mutation-generator-driven
enum RequestMutation:
  case ChangeProvider(newProvider: String)
  case ChangeModel(newModel: String)
  case ReorderMessages
  case ChangeTemperature(newTemp: Double)
  case ChangeMaxTokens(newMax: Option[Int])
  case ChangeTopP(newTopP: Double)
  case ChangeStopSequences(newStops: List[String])
  case AddTool(name: String)
  case RemoveTool(name: String)
  case ChangeToolSchema(name: String)
  case ChangeSystemPrompt(newPrompt: String)
  case ChangeRolloutId(newRollout: Option[RolloutId])

  /** Apply this mutation to a base request, producing a new request. */
  def apply(req: ModelCallRequest): ModelCallRequest = this match
    case ChangeProvider(p) => req.copy(provider = p)
    case ChangeModel(m)    => req.copy(model = m)
    case ReorderMessages   =>
      // Swap the first two messages (if there are at least 2).
      // If there's only 1 message, prepend a dummy user message to ensure
      // the conversation changes.
      val msgs = req.conversation.messages
      val reordered =
        if msgs.length >= 2 then
          val first  = msgs(0)
          val second = msgs(1)
          second :: first :: msgs.drop(2).toList
        else UserMessage("reorder-marker") :: msgs.toList
      req.copy(conversation = Conversation(reordered))
    case ChangeTemperature(t) =>
      req.copy(options = req.options.copy(temperature = t))
    case ChangeMaxTokens(m) =>
      req.copy(options = req.options.copy(maxTokens = m))
    case ChangeTopP(p) =>
      req.copy(options = req.options.copy(topP = p))
    case ChangeStopSequences(s) =>
      req.copy(stopSequences = s)
    case AddTool(name) =>
      // Add a new ToolDef with the given name — changes the tool-definition list
      val newTool = ToolDef(name, "added tool", "{}")
      req.copy(tools = req.tools :+ newTool)
    case RemoveTool(name) =>
      // Remove the first tool with the given name (if it exists)
      req.copy(tools = req.tools.filterNot(_.name == name))
    case ChangeToolSchema(name) =>
      // Change the schema of the first tool with the given name
      req.copy(tools = req.tools.map { td =>
        if td.name == name then td.copy(schemaJson = "{\"changed\":true}") else td
      })
    case ChangeSystemPrompt(p) => req.copy(systemPrompt = p)
    case ChangeRolloutId(r)    => req.copy(rollout = r)

// ── NonAffectingMutation — non-output-affecting mutations for RL4 ───────
// spec: add-adk4s-record/call-key — Requirement: Canonical form excludes non-output-affecting fields
// spec: add-adk4s-record/recorder-laws — Requirement: RL3 and RL4 are mutation-generator-driven
enum NonAffectingMutation:
  case RegenerateToolCallIds(newIds: Map[String, String])
  case ChangeProviderRequestId(newId: String)
  case ChangeLatency(newLatency: Long)
  case ChangeTokenUsage(newUsage: Long)
  case ChangeTimestamp(newTimestamp: Long)

  /** Apply this mutation to a base request, producing a new request. */
  def apply(req: ModelCallRequest): ModelCallRequest = this match
    case RegenerateToolCallIds(newIds) =>
      // Replace tool-call ids in the conversation with new ids.
      // The canonical form normalizes these to positional identifiers,
      // so the key should not change.
      val newMessages = req.conversation.messages.map { msg =>
        msg match
          case a: AssistantMessage =>
            val newToolCalls = a.toolCalls.map { tc =>
              newIds.get(tc.id) match
                case Some(newId) => ToolCall(newId, tc.name, tc.arguments)
                case None        => tc
            }
            AssistantMessage(a.contentOpt, newToolCalls)
          case t: ToolMessage =>
            newIds.get(t.toolCallId) match
              case Some(newId) => ToolMessage(t.content, newId)
              case None        => t
          case other => other
      }
      req.copy(conversation = Conversation(newMessages))
    case ChangeProviderRequestId(newId) =>
      req.copy(providerRequestId = Some(newId))
    case ChangeLatency(newLatency) =>
      req.copy(latencyMs = Some(newLatency))
    case ChangeTokenUsage(newUsage) =>
      req.copy(tokenUsage = Some(newUsage))
    case ChangeTimestamp(newTimestamp) =>
      req.copy(timestamp = Some(newTimestamp))
