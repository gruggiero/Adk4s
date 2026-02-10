package org.adk4s.core

import cats.effect.IO
import org.adk4s.core.error.AdkError

package object types:
  type AdkIO[A]       = IO[A]
  type AdkResult[A]   = Either[AdkError, A]
  type AdkIOResult[A] = IO[Either[AdkError, A]]

  type Message           = org.adk4s.structured.core.Message
  type Role              = org.adk4s.structured.core.Role
  type Prompt            = org.adk4s.structured.core.Prompt
  type Schema[A]         = org.adk4s.structured.core.Schema[A]
  type PromptTemplate[I] = org.adk4s.structured.core.PromptTemplate[I]

  type LlmConversation = org.llm4s.llmconnect.model.Conversation
  type LlmCompletion   = org.llm4s.llmconnect.model.Completion
  type LlmMessage      = org.llm4s.llmconnect.model.Message
  type ToolCall        = org.llm4s.llmconnect.model.ToolCall
