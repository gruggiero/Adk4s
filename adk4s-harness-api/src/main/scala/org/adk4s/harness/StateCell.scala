package org.adk4s.harness

import upickle.default.ReadWriter
import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.constraint.any.Not
import io.github.iltotore.iron.constraint.string.{Blank, Match}
import io.github.iltotore.iron.upickle.given
import org.adk4s.core.types.NonEmpty
import org.adk4s.core.error.ConfigError

/**
 * Unit of state declaration, ownership, typing, serialization, visibility,
 * and merging.
 *
 * Every `StateCell[A]` carries a mandatory `ReadWriter[A]` codec provided
 * at declaration time via a `ReadWriter` context bound. A cell that cannot
 * round-trip through its codec cannot be constructed.
 *
 * Cell equality is by `CellId` (stable string), not by object identity.
 *
 * spec: harness-state — Requirement: StateCell is a typed declaration unit with mandatory codec
 */
final class StateCell[A] private (
  val id: StateCell.CellId,
  val visibility: CellVisibility,
  val initial: A,
  val merge: (A, A) => A,
  val rw: ReadWriter[A]
):
  override def equals(other: Any): Boolean = other match
    case that: StateCell[?] => this.id == that.id
    case _                  => false
  override def hashCode: Int    = id.hashCode
  override def toString: String = s"StateCell($id, $visibility)"

object StateCell:
  /** Stable `"owner/name"` key for cell identity.
    *
    * An opaque type backed by `String :| (NonEmpty & Match["[^/]+/[^/]+"])`.
    * Constructed via `CellId("owner/name")` (compile-time refinement for
    * inline literals) or `CellId.refineEither(s)` (runtime refinement).
    * The underlying value is accessed via the `.value` extension inherited
    * from `Refined`.
    *
    * spec: harness-state — Requirement: StateCell.CellId rejects empty and malformed values
    */
  type CellId = CellId.T

  object CellId extends RefinedType[String, NonEmpty & Match["[^/]+/[^/]+"]]:

    /** Refinement returning a structured ConfigError on failure. */
    def refineEither(s: String): Either[ConfigError, CellId] =
      either(s) match
        case Right(cid) => Right(cid)
        case Left(_)    => Left(ConfigError("CellId", s, "NonEmpty & Match[\"[^/]+/[^/]+\"]"))

    /** Construct a CellId from an owner MiddlewareName and a name string.
      * Refines the concatenated `owner/name` string at runtime via
      * `refineEither`. Throws `ConfigError` on invalid input (empty name
      * producing a malformed `owner/` string). */
    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    def apply(owner: MiddlewareName, name: String): CellId =
      refineEither(s"${owner.value}/$name").fold(
        err => throw err,
        identity
      )

  def apply[A: ReadWriter](
    owner: MiddlewareName,
    name: String,
    initial: A,
    visibility: CellVisibility = CellVisibility.Private,
    merge: (A, A) => A = (_: A, child: A) => child
  ): StateCell[A] =
    val id: CellId = CellId(owner, name)
    new StateCell(id, visibility, initial, merge, summon[ReadWriter[A]])
