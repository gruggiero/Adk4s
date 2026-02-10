package org.adk4s.core.interrupt

import org.adk4s.core.error.AdkError

/** Observable events emitted during agent execution. */
sealed trait AgentEvent:
  def runPath: RunPath

  /** Returns this event with the RunPath prepended by the given step. */
  def withPrependedStep(step: RunStep): AgentEvent

object AgentEvent:
  final case class MessageOutput(
    runPath: RunPath,
    message: String,
    role: String
  ) extends AgentEvent:
    def withPrependedStep(step: RunStep): MessageOutput =
      copy(runPath = runPath.prepended(step))

  final case class ToolCallRequested(
    runPath: RunPath,
    toolName: String,
    arguments: String,
    callId: String
  ) extends AgentEvent:
    def withPrependedStep(step: RunStep): ToolCallRequested =
      copy(runPath = runPath.prepended(step))

  final case class ToolCallCompleted(
    runPath: RunPath,
    toolName: String,
    result: String,
    callId: String,
    isError: Boolean
  ) extends AgentEvent:
    def withPrependedStep(step: RunStep): ToolCallCompleted =
      copy(runPath = runPath.prepended(step))

  final case class IterationCompleted(
    runPath: RunPath,
    iteration: Int,
    remainingSteps: Int
  ) extends AgentEvent:
    def withPrependedStep(step: RunStep): IterationCompleted =
      copy(runPath = runPath.prepended(step))

  final case class Interrupted(
    runPath: RunPath,
    signal: InterruptSignal
  ) extends AgentEvent:
    def withPrependedStep(step: RunStep): Interrupted =
      copy(runPath = runPath.prepended(step))

  final case class ErrorOccurred(
    runPath: RunPath,
    error: AdkError
  ) extends AgentEvent:
    def withPrependedStep(step: RunStep): ErrorOccurred =
      copy(runPath = runPath.prepended(step))

  final case class TokenDelta(
    runPath: RunPath,
    delta: String
  ) extends AgentEvent:
    def withPrependedStep(step: RunStep): TokenDelta =
      copy(runPath = runPath.prepended(step))
