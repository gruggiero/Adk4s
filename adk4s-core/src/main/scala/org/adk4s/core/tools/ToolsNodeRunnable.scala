package org.adk4s.core.tools

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.model.{ToolCall, ToolMessage, AssistantMessage}
import org.adk4s.core.runnable.Runnable

object ToolsNodeRunnable:
  def fromAssistantMessage(node: ToolsNode): Runnable[AssistantMessage, List[ToolMessage]] =
    Runnable.fromInvoke { msg =>
      node.executeFromAssistantMessage(msg).map(_.toLlm4sMessages(includeErrors = true))
    }

  def fromToolCalls(node: ToolsNode): Runnable[List[ToolCall], List[ToolMessage]] =
    Runnable.fromInvoke { calls =>
      node.executeFromToolCalls(calls).map(_.toLlm4sMessages(includeErrors = true))
    }

  def streaming(node: ToolsNode): Runnable[List[ToolCall], ToolMessage] =
    Runnable.fromStream { calls =>
      Stream.emits(ToolInput.fromToolCalls(calls))
        .parEvalMap(node.maxConcurrency)(node.executeTool)
        .map(_.toLlm4sMessage)
    }

  extension (node: ToolsNode)
    def asAssistantMessageRunnable: Runnable[AssistantMessage, List[ToolMessage]] =
      fromAssistantMessage(node)

    def asStreamingRunnable: Runnable[List[ToolCall], ToolMessage] =
      streaming(node)
