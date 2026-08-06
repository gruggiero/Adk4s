package org.adk4s.memory

import cats.effect.kernel.Sync
import cats.syntax.functor.toFunctorOps
import fs2.Stream
import org.adk4s.core.component.{ Document, Retriever, RetrieverConfig }
import org.adk4s.core.json.*
import smithy4s.Document as S4sDocument
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
 *   - `metadata("score")` = `DNumber(hit.score)`
 *   - `metadata("provenance")` = `DString(...)` when present (omitted when `None`)
 *   - `metadata` entries from `hit.payload` as `DString`
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
   * `provenance` / `payload` into `metadata` as JsonValue (smithy4s.Document) values.
   */
  private val reservedKeys: Set[String] = Set("score", "provenance")

  def toDocument(hit: MemoryHit): Document =
    val payloadMeta: Map[String, JsonValue] =
      hit.payload.view.filterKeys(k => !reservedKeys.contains(k)).mapValues(v => S4sDocument.DString(v)).toMap
    val withScore: Map[String, JsonValue]   = payloadMeta.updated("score", S4sDocument.DNumber(BigDecimal(hit.score)))
    val metadata: Map[String, JsonValue]    = hit.provenance match
      case Some(p) => withScore.updated("provenance", S4sDocument.DString(p))
      case None    => withScore
    Document(id = synthesizeId(hit), content = hit.text, metadata = metadata)

  /** Deterministic id from hit fields (SHA-256 hex of text|score|provenance|validFrom|validTo|payload). */
  private def synthesizeId(hit: MemoryHit): String =
    val provenanceStr: String = hit.provenance.getOrElse("")
    val validFromStr: String  = hit.validFrom.map(_.toString).getOrElse("")
    val validToStr: String    = hit.validTo.map(_.toString).getOrElse("")
    val payloadStr: String    = hit.payload.toSeq.sortBy(_._1).map((k, v) => s"$k=$v").mkString(";")
    val input: String         = s"${hit.text}|${hit.score}|$provenanceStr|$validFromStr|$validToStr|$payloadStr"
    val digest: Array[Byte]   = MessageDigest.getInstance("SHA-256").digest(input.getBytes("UTF-8"))
    digest.map(b => f"$b%02x").mkString
