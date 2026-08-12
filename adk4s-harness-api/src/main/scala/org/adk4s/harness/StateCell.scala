package org.adk4s.harness

import upickle.default.ReadWriter

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
  /** Stable `"owner/name"` key for cell identity. */
  opaque type CellId = String
  object CellId:
    def apply(owner: MiddlewareName, name: String): CellId =
      s"${owner.value}/$name"
    extension (id: CellId) def value: String = id

  def apply[A: ReadWriter](
    owner: MiddlewareName,
    name: String,
    initial: A,
    visibility: CellVisibility = CellVisibility.Private,
    merge: (A, A) => A = (_: A, child: A) => child
  ): StateCell[A] =
    new StateCell(CellId(owner, name), visibility, initial, merge, summon[ReadWriter[A]])
