package org.adk4s.core.component

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, Completion, CompletionOptions, StreamedChunk}
import org.llm4s.toolapi.ToolFunction

trait ToolCallingChatModel[F[_]] extends ChatModel[F]:
  def tools: Seq[ToolFunction[?, ?]]

  def withTools(tools: Seq[ToolFunction[?, ?]]): ToolCallingChatModel[F]

  def addTools(tools: ToolFunction[?, ?]*): ToolCallingChatModel[F]

  def generateWithTools(conversation: Conversation): F[Completion]

  def streamWithTools(conversation: Conversation): Stream[F, StreamedChunk]

object ToolCallingChatModel:
  def fromLlm4s[F[_]](
    client: LLMClient,
    tools: Seq[ToolFunction[?, ?]] = Seq.empty[ToolFunction[?, ?]],
    config: ChatModelConfig = ChatModelConfig.default
  )(using F: Async[F]): ToolCallingChatModel[F] =
    val baseModel: ChatModel[F] = ChatModel.fromLlm4s(client, config)
    val capturedTools: Seq[ToolFunction[?, ?]] = tools
    new ToolCallingChatModel[F]:
      def currentTools: Seq[ToolFunction[?, ?]] = capturedTools

      def tools: Seq[ToolFunction[?, ?]] = capturedTools

      def withTools(newTools: Seq[ToolFunction[?, ?]]): ToolCallingChatModel[F] =
        ToolCallingChatModel.fromLlm4s(client, newTools, config)

      def addTools(additional: ToolFunction[?, ?]*): ToolCallingChatModel[F] =
        withTools(currentTools ++ additional)

      def generate(conversation: Conversation): F[Completion] =
        baseModel.generate(conversation)

      def stream(conversation: Conversation): Stream[F, StreamedChunk] =
        baseModel.stream(conversation)

      def streamContent(conversation: Conversation): Stream[F, String] =
        baseModel.streamContent(conversation)

      def withConfig(newConfig: ChatModelConfig): ChatModel[F] =
        ToolCallingChatModel.fromLlm4s(client, capturedTools, newConfig)

      def generateWithTools(conversation: Conversation): F[Completion] =
        val baseOptions: CompletionOptions = config.toCompletionOptions
        val toolFunctions: Seq[ToolFunction[?, ?]] = capturedTools
        val optionsWithTools: CompletionOptions = baseOptions.copy(tools = toolFunctions)
        F.delay(client.complete(conversation, optionsWithTools)).flatMap {
          case Right(completion) => F.pure(completion)
          case Left(error) => F.raiseError(org.adk4s.core.error.LlmCallError(error))
        }

      def streamWithTools(conversation: Conversation): Stream[F, StreamedChunk] =
        val baseOptions: CompletionOptions = config.toCompletionOptions
        val toolFunctions: Seq[ToolFunction[?, ?]] = capturedTools
        val optionsWithTools: CompletionOptions = baseOptions.copy(tools = toolFunctions)
        Stream.eval(F.delay {
          val chunks = scala.collection.mutable.ListBuffer[StreamedChunk]()
          val result = client.streamComplete(conversation, optionsWithTools, chunk => chunks += chunk)
          (result, chunks.toList)
        }).flatMap {
          case (Right(_), chunks) => Stream.emits(chunks).covary[F]
          case (Left(err), _) => Stream.raiseError[F](org.adk4s.core.error.LlmCallError(err))
        }
