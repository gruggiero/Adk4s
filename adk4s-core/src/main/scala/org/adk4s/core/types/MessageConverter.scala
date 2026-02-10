package org.adk4s.core.types

import org.adk4s.structured.core.{ Message as AdkMessage, Role as AdkRole }
import org.llm4s.llmconnect.model.{ Message as Llm4sMessage, UserMessage, SystemMessage, AssistantMessage, ToolMessage }

object MessageConverter:
  def toLlm4s(msg: AdkMessage): Llm4sMessage =
    msg.role match
      case AdkRole.System    => SystemMessage(msg.content)
      case AdkRole.User      => UserMessage(msg.content)
      case AdkRole.Assistant => AssistantMessage(contentOpt = Some(msg.content), toolCalls = Seq.empty)
      case AdkRole.Tool      => ToolMessage(msg.content, toolCallId = "unknown")

  def fromLlm4s(msg: Llm4sMessage): AdkMessage =
    msg match
      case SystemMessage(content)     => AdkMessage(AdkRole.System, content)
      case UserMessage(content)       => AdkMessage(AdkRole.User, content)
      case AssistantMessage(_, calls) => AdkMessage(AdkRole.Assistant, msg.content)
      case ToolMessage(content, _)    => AdkMessage(AdkRole.Tool, content)

  extension (msg: AdkMessage) def asLlm4s: Llm4sMessage = toLlm4s(msg)

  extension (msg: Llm4sMessage) def asAdk: AdkMessage = fromLlm4s(msg)
