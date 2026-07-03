package org.adk4s.core.component

import cats.effect.*
import cats.syntax.all.*
import org.adk4s.core.types.{Prompt, PromptTemplate}
import org.llm4s.llmconnect.model.{Conversation, Message as Llm4sMessage, SystemMessage, UserMessage, AssistantMessage, ToolMessage}

trait ChatTemplate[F[_], V]:
  def format(variables: V): F[Prompt]

  def formatConversation(variables: V): F[Conversation]

object ChatTemplate:
  def fromPromptTemplate[F[_], V](template: PromptTemplate[V])(using F: Sync[F]): ChatTemplate[F, V] =
    new ChatTemplate[F, V]:
      def format(variables: V): F[Prompt] = F.delay(template.render(variables))

      def formatConversation(variables: V): F[Conversation] =
        F.delay(template.render(variables).conversation)

  def fromMessages[F[_]](messages: List[Llm4sMessage], substitution: (String, Map[String, String]) => String)(using F: Sync[F]): ChatTemplate[F, Map[String, String]] =
    new ChatTemplate[F, Map[String, String]]:
      def format(variables: Map[String, String]): F[Prompt] =
        F.delay {
          val substitutedMessages: Seq[Llm4sMessage] = messages.map { msg =>
            substituteMessageContent(msg, variables, substitution)
          }
          org.adk4s.structured.core.Prompt(substitutedMessages*)
        }

      def formatConversation(variables: Map[String, String]): F[Conversation] =
        format(variables).map(prompt => prompt.conversation)

  def simple[F[_]](messages: List[Llm4sMessage])(using F: Sync[F]): ChatTemplate[F, Map[String, String]] =
    fromMessages(messages, (content, vars) =>
      vars.foldLeft(content) { case (acc, (key, value)) =>
        acc.replace(s"{$key}", value)
      }
    )

  private def substituteMessageContent(
    msg: Llm4sMessage,
    variables: Map[String, String],
    substitution: (String, Map[String, String]) => String
  ): Llm4sMessage =
    msg match
      case sm: SystemMessage =>
        SystemMessage(substitution(sm.content, variables))
      case um: UserMessage =>
        UserMessage(substitution(um.content, variables))
      case am: AssistantMessage =>
        AssistantMessage(
          contentOpt = am.contentOpt.map(c => substitution(c, variables)),
          toolCalls = am.toolCalls
        )
      case tm: ToolMessage =>
        ToolMessage(substitution(tm.content, variables), tm.toolCallId)
