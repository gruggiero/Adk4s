package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.HarnessPayload

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal // danger-scan:allow fail-open — an unreadable payload is "no payload", never a failure

/**
 * The gate's input channel (spec 8, R-P1 adapter). The harness pipes a
 * JSON object on stdin; the gate reads it AT MOST ONCE, at the top
 * level — downstream code receives the parsed `HarnessPayload`, never
 * the channel.
 *
 * Read policy (the predecessor's `read_payload`):
 *   - only when the event consumes a payload (`post-edit`, `tool-call`,
 *     `completion`, `post-bash`) OR no `--repo` flag was supplied and
 *     stdin is not a TTY — the payload's `.cwd` is the repository
 *     fallback;
 *   - an absent, empty, or unparseable payload is `None` — every field
 *     then reads as the jq `// empty` value, and the tiers proceed as
 *     the predecessor does with `PAYLOAD=""`.
 *
 * spec: gate-event-completeness — Concepts Introduced (new): HarnessPayload
 * spec: gate-event-completeness — Requirement: The gate handles every event its installed adapters emit
 */
object HarnessPayloadReader:

  /**
   * Whether the event consumes a payload at all — the predecessor's
   * `payload_events` set plus `post-bash`.
   */
  def consumesPayload(event: org.sinemenda.probatio.core.GateEvent): Boolean =
    event match
      case org.sinemenda.probatio.core.GateEvent.PostEdit     => true
      case org.sinemenda.probatio.core.GateEvent.ToolCall     => true
      case org.sinemenda.probatio.core.GateEvent.PostBash     => true
      case org.sinemenda.probatio.core.GateEvent.Completion   => true
      case org.sinemenda.probatio.core.GateEvent.SessionStart => false
      case org.sinemenda.probatio.core.GateEvent.PromptSubmit => false

  /**
   * Parse the raw payload text. `None` for empty or unparseable input;
   * `Some` for a JSON object whose fields are read with `// empty`
   * semantics. A non-object JSON value parses to the empty-field
   * payload — the predecessor's `payload_field` yields "" for every
   * field of a non-object, which is indistinguishable from absent.
   */
  def parse(text: String): Option[HarnessPayload] =
    if text.isEmpty then None
    else
      try
        ujson.read(text) match
          case obj: ujson.Obj => Some(fromObject(obj))
          case _ => Some(Empty) // danger-scan:allow jq-parity — non-object payloads read as all-fields-absent
      catch case NonFatal(_) => None // danger-scan:allow fail-open — unparseable input is "no payload"

  /** The all-fields-absent payload — `PAYLOAD=""` semantics. */
  val Empty: HarnessPayload =
    HarnessPayload.of("", ujson.Null, ujson.Null, "", ujson.Null)

  private def fromObject(obj: ujson.Obj): HarnessPayload =
    def field(name: String): ujson.Value =
      obj.value.getOrElse(name, ujson.Null)
    def str(name: String): String =
      field(name) match
        case ujson.Str(s) => s
        case _ => "" // danger-scan:allow jq-empty-parity — `// empty` yields "" for absent and non-string alike
    HarnessPayload.of(
      toolName = str("tool_name"),
      toolInput = field("tool_input"),
      toolResponse = field("tool_response"),
      cwd = str("cwd"),
      stopHookActive = field("stop_hook_active")
    )

  /**
   * Read the process input channel at most once. `inputPending` is
   * injected — the non-blocking precondition on the predecessor's
   * `[ ! -t 0 ] && cat` read: a channel with nothing buffered is never
   * read (nothing to consume, and the read would block — an open
   * silent pipe never reaches EOF).
   */
  def readChannel(inputPending: Boolean): Option[String] =
    if !inputPending then None
    else
      try
        val bytes: Array[Byte] = System.in.readAllBytes()
        val text: String       = new String(bytes, StandardCharsets.UTF_8).trim
        if text.isEmpty then None else Some(text)
      catch case NonFatal(_) => None // danger-scan:allow fail-open — an unreadable channel is "no payload"

end HarnessPayloadReader
