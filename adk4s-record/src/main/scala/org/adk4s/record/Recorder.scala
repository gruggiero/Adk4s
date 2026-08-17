package org.adk4s.record

import cats.Applicative
import cats.effect.Async
import cats.effect.Concurrent
import cats.effect.Resource
import fs2.io.file.Path as Fs2Path
import org.adk4s.core.types.Positive
import org.adk4s.record.file.FileRecorder

// ── Recorder — effect-polymorphic sink algebra ────────────────────────
// spec: add-adk4s-record/recorder-sink — Requirement: Recorder is an effect-polymorphic sink algebra
trait Recorder[F[_]]:
  /** Look up a previously recorded call by key. */
  def lookup(key: CallKey): F[Option[CallRecord]]

  /** Record a call outcome. Append-only recorders do not overwrite. */
  def record(key: CallKey, outcome: CallRecord): F[Unit]

  /** Get the next monotonically increasing sequence number. */
  def nextSeq: F[Long]

object Recorder:
  /** A recorder that records nothing and never hits. */
  def noop[F[_]: Applicative]: Recorder[F] =
    RecorderInstances.noop[F]

  /** A bounded, evictable in-memory recorder for tests and optimizer sweeps. */
  def inMemory[F[_]: Concurrent](maxEntries: Positive): F[Recorder[F]] =
    RecorderInstances.inMemory[F](maxEntries)

  /** An append-only JSONL file recorder for reproducible local runs. */
  def file[F[_]: Async](path: Fs2Path): Resource[F, Recorder[F]] =
    FileRecorder.resource[F](path)
