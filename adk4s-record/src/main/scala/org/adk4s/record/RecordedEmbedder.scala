package org.adk4s.record

import cats.MonadThrow
import cats.syntax.all.*
import org.adk4s.core.component.Embedder
import org.adk4s.core.component.Embedding
import org.adk4s.core.component.EmbeddingResult
import org.adk4s.record.canonical.CallKind

// ── RecordedEmbedder — recording + caching Embedder decorator ──────────
// spec: add-adk4s-record/recorded-wrappers — Requirement: RecordedEmbedder wraps Embedder with recording and caching
//
// Wraps an Embedder[F] with recording and caching, following the same
// hit/miss semantics as RecordedChatModel. On a miss, the underlying
// embedder is called and the result is recorded under the computed
// CallKey. On a hit, the recorded embedding is returned with zero
// underlying calls.
object RecordedEmbedder:

  /**
   * Wrap an Embedder[F] with recording and caching.
   *
   * @param under
   *   The underlying Embedder to wrap.
   * @param recorder
   *   The Recorder to store call records in.
   * @param redaction
   *   Optional payload redaction function, applied after key computation.
   * @return
   *   An Embedder[F] that records and caches embedding calls.
   */
  def apply[F[_]](
    under: Embedder[F],
    recorder: Recorder[F],
    redaction: Option[Redaction] = None
  )(using F: MonadThrow[F]): Embedder[F] =
    new Embedder[F]:
      // ── embed ──────────────────────────────────────────────────────
      def embed(text: String): F[Embedding] =
        val form = CanonicalFormOps.fromEmbedding(text, "recorded-embedder")
        val key  = CallKey.fromCanonical(form)
        recorder.lookup(key).flatMap {
          case Some(CallRecord.SucceededCase(succeeded)) =>
            succeeded.payload match
              case RecordPayload.EmbeddingCase(payload) =>
                payload.embedding match
                  case Some(vec) => F.pure(vec.toVector)
                  case None      => callAndRecordEmbed(key, text)
              case _ => // danger-scan:allow payload-kind mismatch means stale/corrupt record; fall through to miss is correct, not a silent mapping to a valid domain value
                callAndRecordEmbed(
                  key,
                  text
                )
          case Some(CallRecord.FailedCase(_)) =>
            callAndRecordEmbed(key, text)
          case None =>
            callAndRecordEmbed(key, text)
        }

      private def callAndRecordEmbed(key: CallKey, text: String): F[Embedding] =
        under.embed(text).flatMap { embedding =>
          val payload = EmbeddingPayload(
            model = "recorded-embedder",
            tokenCount = None,
            embedding = Some(embedding.toList)
          )
          val redacted = redaction.fold(RecordPayload.embedding(payload))(red => red(RecordPayload.embedding(payload)))
          val record = CallRecord.succeeded(
            SucceededRecord(
              key = key.value,
              seq = 0L,
              kind = CallKind.EMBEDDING,
              payload = redacted,
              classification = Classification.PUBLIC
            )
          )
          recorder.record(key, record).attempt.void.as(embedding)
        }

      // ── embedBatch ─────────────────────────────────────────────────
      def embedBatch(texts: List[String]): F[EmbeddingResult] =
        under.embedBatch(texts).flatMap { result =>
          // Record each embedding individually
          val recordActions: List[F[Unit]] = texts.zip(result.embeddings).map { (text, emb) =>
            val form = CanonicalFormOps.fromEmbedding(text, "recorded-embedder")
            val key  = CallKey.fromCanonical(form)
            val payload = EmbeddingPayload(
              model = "recorded-embedder",
              tokenCount = result.usage.map(_.totalTokens),
              embedding = Some(emb.toList)
            )
            val redacted =
              redaction.fold(RecordPayload.embedding(payload))(red => red(RecordPayload.embedding(payload)))
            val record = CallRecord.succeeded(
              SucceededRecord(
                key = key.value,
                seq = 0L,
                kind = CallKind.EMBEDDING,
                payload = redacted,
                classification = Classification.PUBLIC
              )
            )
            recorder.record(key, record).attempt.void
          }
          recordActions.sequence_.as(result)
        }

      // ── dimension ──────────────────────────────────────────────────
      def dimension: F[Int] =
        under.dimension
