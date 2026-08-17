package org.adk4s.record

import cats.Applicative
import cats.effect.Concurrent
import cats.effect.Ref
import cats.syntax.all.*
import org.adk4s.core.types.Positive

import scala.collection.immutable.ListMap

// ── Recorder.noop — records nothing, never hits ───────────────────────
// spec: add-adk4s-record/recorder-sink — Requirement: Three reference implementations
private[record] class NoopRecorder[F[_]: Applicative] extends Recorder[F]:
  def lookup(key: CallKey): F[Option[CallRecord]] =
    Applicative[F].pure(None)

  def record(key: CallKey, outcome: CallRecord): F[Unit] =
    Applicative[F].unit

  def nextSeq: F[Long] =
    Applicative[F].pure(0L)

// ── Recorder.inMemory — bounded, evictable, LRU ───────────────────────
// spec: add-adk4s-record/recorder-sink — Requirement: Bounded recorder evicts without failing the call
private[record] class InMemoryRecorder[F[_]](
  maxEntries: Positive,
  storeRef: Ref[F, ListMap[String, CallRecord]],
  seqRef: Ref[F, Long]
) extends Recorder[F]:

  def lookup(key: CallKey): F[Option[CallRecord]] =
    storeRef.modify { store =>
      store.get(key.value) match
        case Some(record) =>
          // Move-to-end for LRU: remove and re-insert at tail
          val updated: ListMap[String, CallRecord] =
            store.removed(key.value).updated(key.value, record)
          (updated, Some(record))
        case None =>
          (store, None)
    }

  def record(key: CallKey, outcome: CallRecord): F[Unit] =
    storeRef.modify { store =>
      val existing: ListMap[String, CallRecord] = store.removed(key.value)
      val withNew: ListMap[String, CallRecord]  = existing.updated(key.value, outcome)
      // Evict oldest (head) if over capacity
      val finalStore: ListMap[String, CallRecord] =
        if withNew.size > maxEntries then
          withNew.headOption match
            case Some((oldestKey, _)) => withNew.removed(oldestKey)
            case None                 => withNew
        else withNew
      (finalStore, ())
    }

  def nextSeq: F[Long] =
    seqRef.modify(seq => (seq + 1L, seq + 1L))

object RecorderInstances:

  /** Create a noop recorder — records nothing, never hits. */
  def noop[F[_]: Applicative]: Recorder[F] =
    new NoopRecorder[F]

  /** Create a bounded in-memory recorder with LRU eviction. */
  def inMemory[F[_]: Concurrent](maxEntries: Positive): F[Recorder[F]] =
    for
      storeRef <- Ref.of[F, ListMap[String, CallRecord]](ListMap.empty)
      seqRef   <- Ref.of[F, Long](0L)
    yield new InMemoryRecorder[F](maxEntries, storeRef, seqRef)
