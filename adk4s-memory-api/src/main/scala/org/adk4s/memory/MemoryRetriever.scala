package org.adk4s.memory

import cats.effect.kernel.Sync
import cats.syntax.functor.toFunctorOps
import fs2.Stream
import org.adk4s.core.component.{ Document, Retriever, RetrieverConfig }
import java.security.MessageDigest

/**
 * Adapts any `AgentMemory[F]` into the existing `Retriever[F]` interface so
 * current agent wiring consumes memory with no new plumbing.
 *
 * Requires `Sync[F]` because `Retriever.retrieveStream` returns `fs2.Stream`
 * and `Retriever.fromFunction` requires `Sync`.
 *
 * Each `MemoryHit` is mapped to a `Document` via [[toDocument]]:
 *   - `content` = `hit.text`
 *   - `metadata("score")` = `ujson.Num(hit.score)`
 *   - `metadata("provenance")` = `ujson.Str(...)` when present (omitted when `None`)
 *   - `metadata` entries from `hit.payload` as `ujson.Str`
 *   - `id` is a deterministic hash of the hit's fields (pure function)
 */
object MemoryRetriever:

  /**
   * Build a `Retriever[F]` backed by an `AgentMemory[F]`.
   *
   * @param memory the memory backend to read from
   * @param k factory-supplied upper bound on hits returned (default 8)
   * @param scope optional temporal scope forwarded to `memory.recall`
   */
  def apply[F[_]: Sync](
    memory: AgentMemory[F],
    k: Int = 8,
    scope: Option[TemporalScope] = None
  ): Retriever[F] =
    new Retriever[F]:
      def retrieve(query: String, config: RetrieverConfig): F[List[Document]] =
        val effectiveK: Int = math.min(k, config.topK)
        memory.recall(query, effectiveK, scope).map { hits =>
          hits
            .filter(_.score >= config.minScore)
            .map(toDocument)
        }

      def retrieveStream(query: String, config: RetrieverConfig): Stream[F, Document] =
        Stream.eval(retrieve(query, config)).flatMap(docs => Stream.emits(docs).covary[F])

  /**
   * Pure mapping from `MemoryHit` to `Document`.
   *
   * Synthesizes a stable `id` from the hit's fields, packs `score` /
   * `provenance` / `payload` into `metadata` as ujson values.
   */
  def toDocument(hit: MemoryHit): Document =
    val payloadMeta: Map[String, ujson.Value] = hit.payload.view.mapValues(ujson.Str(_)).toMap
    val metadata: Map[String, ujson.Value] =
      payloadMeta
        .updated("score", ujson.Num(hit.score))
        ++ hit.provenance.map(p => Map("provenance" -> ujson.Str(p)))
    Document(id = synthesizeId(hit), content = hit.text, metadata = metadata)

  /** Deterministic id from hit fields (SHA-256 hex of text|score|provenance|payload). */
  private def synthesizeId(hit: MemoryHit): String =
    val provenanceStr: String = hit.provenance.getOrElse("")
    val payloadStr: String    = hit.payload.toSeq.sortBy(_._1).map((k, v) => s"$k=$v").mkString(";")
    val input: String         = s"${hit.text}|${hit.score}|$provenanceStr|$payloadStr"
    val digest: Array[Byte]   = MessageDigest.getInstance("SHA-256").digest(input.getBytes("UTF-8"))
    digest.map(b => f"$b%02x").mkString
