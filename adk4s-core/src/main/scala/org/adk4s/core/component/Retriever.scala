package org.adk4s.core.component

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import upickle.default.*

final case class Document(
  id: String,
  content: String,
  metadata: Map[String, ujson.Value] = Map.empty
)

final case class RetrieverConfig(
  topK: Int = 5,
  minScore: Double = 0.0
)

trait Retriever[F[_]]:
  def retrieve(query: String, config: RetrieverConfig = RetrieverConfig()): F[List[Document]]

  def retrieveStream(query: String, config: RetrieverConfig = RetrieverConfig()): Stream[F, Document]

object Retriever:
  val empty: Retriever[IO] = new Retriever[IO]:
    def retrieve(query: String, config: RetrieverConfig): IO[List[Document]] = IO.pure(Nil)

    def retrieveStream(query: String, config: RetrieverConfig): Stream[IO, Document] = Stream.empty

  def fromFunction[F[_]](f: (String, RetrieverConfig) => F[List[Document]])(using F: Sync[F]): Retriever[F] =
    new Retriever[F]:
      def retrieve(query: String, config: RetrieverConfig): F[List[Document]] = f(query, config)

      def retrieveStream(query: String, config: RetrieverConfig): Stream[F, Document] =
        Stream.eval(f(query, config)).flatMap(docs => Stream.emits(docs).covary[F])

  type RetrieverIO = Retriever[IO]
