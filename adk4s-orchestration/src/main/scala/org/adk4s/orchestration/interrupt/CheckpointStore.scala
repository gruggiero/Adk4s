package org.adk4s.orchestration.interrupt

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.functor.toFunctorOps

/** A key-value store for persisting workflow checkpoint data.
  *
  * F-polymorphic: generalized from the IO-fixed `CheckpointStore` to
  * `CheckpointStore[F[_]]`. At `F = IO`, all existing call sites compile
  * unchanged (just add `[IO]` type argument where the trait is named).
  *
  * Used with human-in-the-loop patterns to save workflow state when
  * an interrupt occurs, and restore it when the workflow resumes.
  *
  * spec: checkpoint-store-fpoly — Requirement: CheckpointStore is F-polymorphic
  */
trait CheckpointStore[F[_]]:
  def get(checkpointId: CheckpointStore.CheckpointId): F[Option[Array[Byte]]]
  def set(checkpointId: CheckpointStore.CheckpointId, data: Array[Byte]): F[Unit]
  def delete(checkpointId: CheckpointStore.CheckpointId): F[Unit]
  def keys: F[List[CheckpointStore.CheckpointId]]

object CheckpointStore:
  /** Transparent type alias for checkpoint identifiers — preserves source
    * compatibility with existing `String` call sites.
    *
    * spec: checkpoint-store-fpoly — Requirement: CheckpointId type alias
    */
  type CheckpointId = String

  /** Ref-backed in-memory checkpoint store factory.
    *
    * Works with any `Sync[F]` — the trait does NOT bind to `IO` at the API level.
    *
    * spec: checkpoint-store-fpoly — Requirement: CheckpointStore is F-polymorphic
    */
  def inMemory[F[_]](using sync: Sync[F]): F[CheckpointStore[F]] =
    Ref.of[F, Map[CheckpointId, Array[Byte]]](Map.empty).map { (ref: Ref[F, Map[CheckpointId, Array[Byte]]]) =>
      new CheckpointStore[F]:
        def get(checkpointId: CheckpointId): F[Option[Array[Byte]]] =
          ref.get.map((store: Map[CheckpointId, Array[Byte]]) => store.get(checkpointId))

        def set(checkpointId: CheckpointId, data: Array[Byte]): F[Unit] =
          ref.update((store: Map[CheckpointId, Array[Byte]]) => store + (checkpointId -> data))

        def delete(checkpointId: CheckpointId): F[Unit] =
          ref.update((store: Map[CheckpointId, Array[Byte]]) => store - checkpointId)

        def keys: F[List[CheckpointId]] =
          ref.get.map((store: Map[CheckpointId, Array[Byte]]) => store.keys.toList)
    }

/** Backward-compatible alias — delegates to `CheckpointStore.inMemory[IO]`.
  *
  * Existing code referencing `InMemoryCheckpointStore.create` continues to work.
  * New code SHOULD use `CheckpointStore.inMemory[IO]` directly.
  */
object InMemoryCheckpointStore:
  def create: IO[CheckpointStore[IO]] =
    CheckpointStore.inMemory[IO]
