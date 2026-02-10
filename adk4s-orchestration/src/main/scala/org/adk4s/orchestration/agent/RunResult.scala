package org.adk4s.orchestration.agent

import org.adk4s.core.error.AdkError
import org.adk4s.core.interrupt.InterruptSignal
import org.llm4s.llmconnect.model.Message

/** Represents the outcome of an agent execution via AgentRunner. */
sealed trait RunResult

object RunResult:
  final case class Completed(
    output: String,
    messages: List[Message]
  ) extends RunResult

  final case class Interrupted(
    checkpointId: String,
    signal: InterruptSignal
  ) extends RunResult

  final case class Failed(
    error: AdkError
  ) extends RunResult
