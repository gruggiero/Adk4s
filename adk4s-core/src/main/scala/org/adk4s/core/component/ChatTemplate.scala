package org.adk4s.core.component

import cats.effect.*
import cats.syntax.all.*
import org.adk4s.core.types.{Prompt, PromptTemplate}
import org.adk4s.core.types.{Message, Role}
import org.llm4s.llmconnect.model.Conversation
import org.adk4s.core.types.ConversationConverter

trait ChatTemplate[F[_], V]:
  def format(variables: V): F[Prompt]

  def formatConversation(variables: V): F[Conversation]

object ChatTemplate:
  def fromPromptTemplate[F[_], V](template: PromptTemplate[V])(using F: Sync[F]): ChatTemplate[F, V] =
    new ChatTemplate[F, V]:
      def format(variables: V): F[Prompt] = F.delay(template.render(variables))

      def formatConversation(variables: V): F[Conversation] =
        F.delay {
          val prompt = template.render(variables)
          ConversationConverter.toConversation(prompt)
        }

  def fromMessages[F[_]](messages: List[Message], substitution: (String, Map[String, String]) => String)(using F: Sync[F]): ChatTemplate[F, Map[String, String]] =
    new ChatTemplate[F, Map[String, String]]:
      def format(variables: Map[String, String]): F[Prompt] =
        F.delay {
          val substitutedMessages = messages.map { msg =>
            val newContent = substitution(msg.content, variables)
            msg.copy(content = newContent)
          }
          org.adk4s.structured.core.Prompt(substitutedMessages.toVector)
        }

      def formatConversation(variables: Map[String, String]): F[Conversation] =
        format(variables).map(prompt => ConversationConverter.toConversation(prompt))

  def simple[F[_]](messages: List[Message])(using F: Sync[F]): ChatTemplate[F, Map[String, String]] =
    fromMessages(messages, (content, vars) =>
      vars.foldLeft(content) { case (acc, (key, value)) =>
        acc.replace(s"{$key}", value)
      }
    )
