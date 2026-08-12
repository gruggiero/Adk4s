package org.adk4s.orchestration.agent

import org.adk4s.core.error.AdkError
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.harness.HarnessState
import org.llm4s.llmconnect.model.{ AssistantMessage, Message }

/**
 * Loop outcome carrying the final `AssistantMessage`, the full message list,
 * and the final `HarnessState`. Variants mirror `RunResult`
 * (`Completed`/`Interrupted`/`Failed`) with state attached.
 *
 * spec: harness-agent — Requirement: HarnessResult carries outcome + state
 */
sealed trait HarnessResult

object HarnessResult:
  final case class Completed(
    finalAssistant: AssistantMessage,
    messages: List[Message],
    state: HarnessState
  ) extends HarnessResult

  final case class Interrupted(
    signal: InterruptSignal,
    messages: List[Message],
    state: HarnessState
  ) extends HarnessResult

  final case class Failed(
    error: AdkError,
    messages: List[Message],
    state: HarnessState
  ) extends HarnessResult
