package org.sinemenda.probatio.core

/**
 * The six hook events the gate handles (spec 9 + spec 8).
 *
 * Each has a distinct blocking policy (the blocking asymmetry, §4.5).
 * The enum is sealed with exactly six cases — `PostBash` is the ambient
 * evidence writer, the post-tool observation event the adapters emit on
 * every Bash PostToolUse. Adding the sixth case is exhaustiveness-
 * escalated: every existing match over `GateEvent` fails Ring 0 until it
 * names the new case.
 *
 * spec: gate-checkpoint-lock — Concepts Introduced: GateEvent
 * spec: gate-checkpoint-lock — Compile-Negative: GateEvent sealed enum
 * spec: gate-event-completeness — Requirement: The gate handles every event its installed adapters emit
 * spec: gate-event-completeness — Compile-Negative: GateEvent pattern match omitting the post-tool observation case
 */
enum GateEvent:
  case SessionStart
  case PromptSubmit
  case PostEdit
  case ToolCall
  case PostBash
  case Completion

object GateEvent:

  /**
   * The harness's own name for the event — what the structured envelope's
   * `hookEventName` field carries. Total: every event maps to the name the
   * adapter registration uses for it, never the internal enum name.
   *
   * spec: gate-event-completeness — Requirement: The emitted envelope names the harness's own event name
   * spec: gate-event-completeness — Scenario: Adversarial — the internal name never appears in the envelope
   */
  def harnessName(event: GateEvent): String = event match
    case GateEvent.SessionStart => "SessionStart"
    case GateEvent.PromptSubmit => "UserPromptSubmit"
    case GateEvent.ToolCall     => "PreToolUse"
    case GateEvent.PostEdit     => "PostToolUse"
    case GateEvent.PostBash     => "PostToolUse"
    case GateEvent.Completion   => "Stop"

end GateEvent
