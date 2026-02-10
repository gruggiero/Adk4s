package org.adk4s.core.tools

import org.llm4s.llmconnect.model.{ToolCall, ToolMessage}
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.core.runnable.Runnable
import cats.effect.IO
import cats.data.Kleisli

type ToolEndpoint = Kleisli[IO, ToolInput, ToolOutput]

case class ToolInput(
  name: String,
  arguments: String,
  callId: String
)

object ToolInput:
  def fromToolCall(tc: ToolCall): ToolInput =
    ToolInput(tc.name, tc.arguments.toString, tc.id)

  def fromToolCalls(calls: List[ToolCall]): List[ToolInput] =
    calls.map(fromToolCall)

case class ToolOutput(
  name: String,
  result: String,
  callId: String,
  isError: Boolean = false
):
  def toLlm4sMessage: ToolMessage =
    ToolMessage(result, callId)

case class ToolExecutionResult(
  outputs: List[ToolOutput],
  failedTools: List[ToolExecutionFailure] = Nil,
  interruptSignal: Option[InterruptSignal] = None
):
  def toLlm4sMessages(includeErrors: Boolean = true): List[ToolMessage] =
    val success = outputs.map(_.toLlm4sMessage)
    if !includeErrors then success
    else success ++ failedTools.map { ft =>
      ToolMessage(
        content = s"Error executing ${ft.input.name}: ${Option(ft.error.getMessage).getOrElse(ft.error.toString)}",
        toolCallId = ft.input.callId
      )
    }

  def allSucceeded: Boolean =
    failedTools.isEmpty

case class ToolExecutionFailure(
  input: ToolInput,
  error: Throwable
)
