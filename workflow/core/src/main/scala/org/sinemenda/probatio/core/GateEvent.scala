package org.sinemenda.probatio.core

/**
 * The five hook events the gate handles (spec 9).
 *
 * Each has a distinct blocking policy (the blocking asymmetry, §4.5).
 * The enum is sealed with exactly five cases — no sixth case is
 * constructible.
 *
 * spec: gate-checkpoint-lock — Concepts Introduced: GateEvent
 * spec: gate-checkpoint-lock — Compile-Negative: GateEvent sealed enum
 */
enum GateEvent:
  case SessionStart
  case PromptSubmit
  case PostEdit
  case ToolCall
  case Completion
