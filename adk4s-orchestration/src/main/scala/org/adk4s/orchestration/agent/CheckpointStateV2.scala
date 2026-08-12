package org.adk4s.orchestration.agent

import org.adk4s.core.json.{ JsonValue, JsonValueCodec }
import smithy4s.Document
import upickle.default.*

/**
 * Serializable tool call — full-fidelity replacement for v1's flattened messages.
 *
 * `arguments` is JSON text (not `ujson.Value`) to keep orchestration free of
 * ujson per the `migrate-json-codec` migration.
 *
 * spec: checkpoint-store-fpoly — Requirement: CheckpointStateV2 carries full-fidelity messages
 */
final private[agent] case class CheckpointToolCall(
  id: String,
  name: String,
  arguments: String
) derives ReadWriter

/**
 * Full-fidelity serializable message — replaces v1's lossy `SerializableCheckpointMessage`.
 *
 * `toolCalls` defaults to `Nil` and `toolCallId` defaults to `None` for v1-read
 * compatibility (v1 payloads lack these fields).
 *
 * spec: checkpoint-store-fpoly — Requirement: CheckpointStateV2 carries full-fidelity messages
 */
final private[agent] case class CheckpointMessage(
  role: String,
  content: String,
  toolCalls: List[CheckpointToolCall] = Nil,
  toolCallId: Option[String] = None
) derives ReadWriter

/**
 * Checkpoint state v2 — full-fidelity messages + harness state + version.
 *
 * Replaces v1 `CheckpointState` (which used `SerializableCheckpointMessage(role, content)`
 * and had no `version`/`harnessState` fields). v1 payloads are readable via the
 * custom `ReadWriter` which defaults absent fields.
 *
 * spec: checkpoint-store-fpoly — Requirement: CheckpointStateV2 carries full-fidelity messages
 * spec: checkpoint-store-fpoly — Requirement: CheckpointStateV2 is v1-read compatible
 */
final private[agent] case class CheckpointStateV2(
  version: Int = 2,
  messages: List[CheckpointMessage] = Nil,
  harnessState: JsonValue = Document.DObject(Map.empty),
  interruptSignalJson: String = "",
  agentName: String = ""
):
  /** Update the harness state field. */
  def withHarnessState(hs: JsonValue): CheckpointStateV2 = copy(harnessState = hs)

object CheckpointStateV2:
  /** The current checkpoint format version. */
  val CurrentVersion: Int = 2

  /**
   * Custom ReadWriter for v1-read compatibility.
   *
   * When reading:
   *   - `version` absent ⇒ default to `CurrentVersion` (treated as v1 upgrade)
   *   - `harnessState` absent ⇒ default to `DObject(Map.empty)` (empty harness state)
   *   - `CheckpointMessage.toolCalls` absent ⇒ `Nil` (via CheckpointMessage's default)
   *   - `CheckpointMessage.toolCallId` absent ⇒ `None` (via CheckpointMessage's default)
   *
   * When writing: always writes all fields (v2 format).
   *
   * spec: checkpoint-store-fpoly — Requirement: CheckpointStateV2 is v1-read compatible
   */
  given readWriter: ReadWriter[CheckpointStateV2] =
    readwriter[ujson.Value].bimap[CheckpointStateV2](
      (v: CheckpointStateV2) =>
        ujson.Obj(
          "version"             -> ujson.Num(v.version.toDouble),
          "messages"            -> writeJs(v.messages),
          "harnessState"        -> JsonValueCodec.toUjson(v.harnessState),
          "interruptSignalJson" -> ujson.Str(v.interruptSignalJson),
          "agentName"           -> ujson.Str(v.agentName)
        ),
      (json: ujson.Value) =>
        val obj          = json.obj
        val version: Int = obj.get("version").map(_.num.toInt).getOrElse(CurrentVersion)
        val messages: List[CheckpointMessage] =
          obj.get("messages").map(read[List[CheckpointMessage]](_)).getOrElse(Nil)
        val harnessState: JsonValue =
          obj.get("harnessState").map(JsonValueCodec.fromUjson).getOrElse(Document.DObject(Map.empty))
        val interruptSignalJson: String =
          obj.get("interruptSignalJson").map(_.str).getOrElse("")
        val agentName: String =
          obj.get("agentName").map(_.str).getOrElse("")
        CheckpointStateV2(version, messages, harnessState, interruptSignalJson, agentName)
    )

/**
 * Converts `Message` values to/from `CheckpointMessage` for full-fidelity serialization.
 *
 * spec: checkpoint-store-fpoly — Requirement: CheckpointStateV2 carries full-fidelity messages
 */
object CheckpointMessageConverter:

  import org.llm4s.llmconnect.model.{ AssistantMessage, Message, SystemMessage, ToolMessage, UserMessage, ToolCall }

  /** Convert a `Message` to a full-fidelity `CheckpointMessage`. */
  def toCheckpoint(msg: Message): CheckpointMessage =
    msg match
      case am: AssistantMessage =>
        CheckpointMessage(
          role = "assistant",
          content = am.content,
          toolCalls = am.toolCalls.toList.map { (tc: ToolCall) =>
            CheckpointToolCall(tc.id, tc.name, ujson.write(tc.arguments))
          },
          toolCallId = None
        )
      case tm: ToolMessage =>
        CheckpointMessage(
          role = "tool",
          content = tm.content,
          toolCalls = Nil,
          toolCallId = Some(tm.toolCallId)
        )
      case um: UserMessage =>
        CheckpointMessage(role = "user", content = um.content, toolCalls = Nil, toolCallId = None)
      case sm: SystemMessage =>
        CheckpointMessage(role = "system", content = sm.content, toolCalls = Nil, toolCallId = None)

  /**
   * Convert a `CheckpointMessage` back to a `Message`.
   *
   * Returns `Left(errMsg)` for an unrecognized `role` — the spec defines exactly
   * four roles (user/assistant/system/tool); an unknown role indicates checkpoint
   * corruption and SHALL NOT silently map to a valid `UserMessage`.
   */
  def fromCheckpoint(cm: CheckpointMessage): Either[String, Message] =
    cm.role.toLowerCase match
      case "assistant" =>
        val toolCalls: Seq[ToolCall] = cm.toolCalls.map { (tc: CheckpointToolCall) =>
          ToolCall(tc.id, tc.name, ujson.read(tc.arguments))
        }.toSeq
        val contentOpt: Option[String] =
          if cm.content.isEmpty then None else Some(cm.content)
        Right(AssistantMessage(contentOpt = contentOpt, toolCalls = toolCalls))
      case "tool" =>
        Right(ToolMessage(cm.content, cm.toolCallId.getOrElse("")))
      case "system" =>
        Right(SystemMessage(cm.content))
      case "user" =>
        Right(UserMessage(cm.content))
      case unknown =>
        Left(s"Unknown checkpoint message role: '$unknown'")
