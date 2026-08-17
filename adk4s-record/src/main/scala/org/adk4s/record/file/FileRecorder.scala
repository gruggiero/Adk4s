package org.adk4s.record.file

import cats.effect.Async
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all.*
import fs2.io.file.Files
import fs2.io.file.Flag
import fs2.io.file.Flags
import fs2.io.file.Path
import fs2.text
import org.adk4s.record.CallKey
import org.adk4s.record.CallRecord
import org.adk4s.record.Recorder
import smithy4s.json.Json

// ── FileRecorder — append-only JSONL file recorder ────────────────────
// spec: add-adk4s-record/recorder-sink — Requirement: Append-only recorders do not overwrite or delete
// spec: add-adk4s-record/recorder-sink — Requirement: Three reference implementations
//
// fs2-io is source-scoped to org.adk4s.record.file per Ring 2 (AR-REC-1).
// The canonicalization package imports neither fs2 nor cats.effect.
private[record] class FileRecorder[F[_]: Async](
  path: Path,
  indexRef: Ref[F, Map[String, CallRecord]],
  seqRef: Ref[F, Long]
) extends Recorder[F]:

  /** Look up a record by key. First-written wins (append-only semantics). */
  def lookup(key: CallKey): F[Option[CallRecord]] =
    indexRef.get.map(_.get(key.value))

  /**
   * Append a record as a JSONL line. Does not overwrite existing entries.
   * First-written wins: if the key already exists in the index, the index is not updated.
   */
  def record(key: CallKey, outcome: CallRecord): F[Unit] =
    for
      _ <- writeLine(outcome)
      _ <- indexRef.update(idx => if idx.contains(key.value) then idx else idx.updated(key.value, outcome))
    yield ()

  /** Get the next monotonically increasing sequence number. */
  def nextSeq: F[Long] =
    seqRef.modify(seq => (seq + 1L, seq + 1L))

  private def writeLine(record: CallRecord): F[Unit] =
    val json: String = Json.writeBlob(record)(using CallRecord.schema).toUTF8String
    val line: String = json + "\n"
    fs2.Stream
      .emit(line)
      .through(
        Files.forAsync[F].writeUtf8(path, Flags(Flag.Append, Flag.Create))
      )
      .compile
      .drain

object FileRecorder:

  /** Create a file recorder resource that loads existing entries on acquire. */
  def resource[F[_]: Async](path: Path): Resource[F, Recorder[F]] =
    for
      _            <- Resource.eval(Files.forAsync[F].createFile(path).handleError(_ => ()))
      initialIndex <- Resource.eval(loadExistingIndex[F](path))
      indexRef     <- Resource.eval(Ref.of[F, Map[String, CallRecord]](initialIndex))
      seqRef       <- Resource.eval(Ref.of[F, Long](initialIndex.size.toLong))
    yield new FileRecorder[F](path, indexRef, seqRef)

  /** Load existing JSONL entries from the file. First-written wins. */
  private def loadExistingIndex[F[_]: Async](path: Path): F[Map[String, CallRecord]] =
    Files.forAsync[F].exists(path).flatMap { exists =>
      if !exists then Async[F].pure(Map.empty[String, CallRecord])
      else
        Files
          .forAsync[F]
          .readUtf8(path)
          .through(text.lines)
          .filter(_.nonEmpty)
          .fold(Map.empty[String, CallRecord]) { (acc, line) =>
            Json.read[CallRecord](smithy4s.Blob(line)) match
              case Right(record) =>
                extractKey(record) match
                  case Some(key) if !acc.contains(key) => acc.updated(key, record)
                  case _                               => acc // first-written wins
              case Left(_) => acc // skip malformed lines
          }
          .compile
          .last
          .map(_.getOrElse(Map.empty[String, CallRecord]))
    }

  private def extractKey(record: CallRecord): Option[String] =
    record match
      case s: CallRecord.SucceededCase => Some(s.succeeded.key)
      case f: CallRecord.FailedCase    => Some(f.failed.key)
