package org.adk4s.core.component

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, Completion, CompletionOptions, StreamedChunk}
import org.adk4s.core.streaming.StreamingLLMClient
import org.adk4s.core.error.LlmCallError

final case class ChatModelConfig(
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  topP: Option[Double] = None,
  stopSequences: Option[List[String]] = None
):
  def toCompletionOptions: CompletionOptions =
    CompletionOptions(
      temperature = temperature.getOrElse(0.7),
      maxTokens = maxTokens,
      topP = topP.getOrElse(1.0)
    )

object ChatModelConfig:
  val default: ChatModelConfig = ChatModelConfig()

trait ChatModel[F[_]]:
  def generate(conversation: Conversation): F[Completion]

  def generate(conversation: Conversation, options: CompletionOptions): F[Completion] =
    generate(conversation)

  def stream(conversation: Conversation): Stream[F, StreamedChunk]

  def stream(conversation: Conversation, options: CompletionOptions): Stream[F, StreamedChunk] =
    stream(conversation)

  def streamContent(conversation: Conversation): Stream[F, String]

  def withConfig(config: ChatModelConfig): ChatModel[F]

object ChatModel:
  def fromLlm4s[F[_]](client: LLMClient, config: ChatModelConfig = ChatModelConfig.default)(using F: Async[F]): ChatModel[F] =
    val streamingClient = StreamingLLMClient.fromClient(client)
    new ChatModel[F]:
      def generate(conversation: Conversation): F[Completion] =
        generate(conversation, config.toCompletionOptions)

      override def generate(conversation: Conversation, options: CompletionOptions): F[Completion] =
        F.delay(client.complete(conversation, options)).flatMap {
          case Right(completion) => F.pure(completion)
          case Left(error) => F.raiseError(LlmCallError(error))
        }

      def stream(conversation: Conversation): Stream[F, StreamedChunk] =
        stream(conversation, config.toCompletionOptions)

      override def stream(conversation: Conversation, options: CompletionOptions): Stream[F, StreamedChunk] =
        val underlying = Stream.eval(F.delay {
          val chunks = scala.collection.mutable.ListBuffer[StreamedChunk]()
          val result = client.streamComplete(conversation, options, chunk => chunks += chunk)
          (result, chunks.toList)
        }).flatMap {
          case (Right(_), chunks) => Stream.emits(chunks).covary[F]
          case (Left(err), _) => Stream.raiseError[F](LlmCallError(err))
        }
        underlying

      def streamContent(conversation: Conversation): Stream[F, String] =
        stream(conversation)
          .evalMap(chunk => F.pure(chunk.content.getOrElse("")))
          .filter(_.nonEmpty)

      def withConfig(newConfig: ChatModelConfig): ChatModel[F] =
        ChatModel.fromLlm4s(client, newConfig)
