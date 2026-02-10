package org.adk4s.core.component

import cats.effect.*
import cats.syntax.all.*
import scala.util.Random

type Embedding = Vector[Double]

final case class EmbeddingUsage(
  promptTokens: Int,
  totalTokens: Int
)

final case class EmbeddingResult(
  embeddings: List[Embedding],
  usage: Option[EmbeddingUsage]
)

trait Embedder[F[_]]:
  def embed(text: String): F[Embedding]

  def embedBatch(texts: List[String]): F[EmbeddingResult]

  def dimension: F[Int]

object Embedder:
  def mock[F[_]](dim: Int = 1536)(using F: Sync[F]): Embedder[F] =
    new Embedder[F]:
      def embed(text: String): F[Embedding] =
        F.delay {
          Vector.fill(dim)(Random.nextDouble())
        }

      def embedBatch(texts: List[String]): F[EmbeddingResult] =
        F.delay {
          val embeddings = texts.map(_ => Vector.fill(dim)(Random.nextDouble()))
          val totalTokens = texts.map(_.split("\\s+").length).sum
          EmbeddingResult(
            embeddings = embeddings,
            usage = Some(EmbeddingUsage(promptTokens = totalTokens, totalTokens = totalTokens))
          )
        }

      def dimension: F[Int] = F.pure(dim)

  type EmbedderIO = Embedder[IO]
