package org.adk4s.orchestration.interrupt

import cats.effect.IO
import cats.effect.Ref

/**
 * A key-value store for persisting workflow checkpoint data.
 *
 * Used with human-in-the-loop patterns to save workflow state when
 * an interrupt occurs, and restore it when the workflow resumes.
 */
trait CheckpointStore:
  def get(checkpointId: String): IO[Option[Array[Byte]]]
  def set(checkpointId: String, data: Array[Byte]): IO[Unit]
  def delete(checkpointId: String): IO[Unit]
  def keys: IO[List[String]]

object InMemoryCheckpointStore:
  def create: IO[CheckpointStore] =
    Ref.of[IO, Map[String, Array[Byte]]](Map.empty).map { (ref: Ref[IO, Map[String, Array[Byte]]]) =>
      new CheckpointStore:
        def get(checkpointId: String): IO[Option[Array[Byte]]] =
          ref.get.map((store: Map[String, Array[Byte]]) => store.get(checkpointId))

        def set(checkpointId: String, data: Array[Byte]): IO[Unit] =
          ref.update((store: Map[String, Array[Byte]]) => store + (checkpointId -> data))

        def delete(checkpointId: String): IO[Unit] =
          ref.update((store: Map[String, Array[Byte]]) => store - checkpointId)

        def keys: IO[List[String]] =
          ref.get.map((store: Map[String, Array[Byte]]) => store.keys.toList)
    }
