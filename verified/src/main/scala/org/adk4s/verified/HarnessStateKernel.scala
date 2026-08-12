package org.adk4s.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of `HarnessState` get/set coherence.
 *
 * This model mirrors the typed heterogeneous map's core invariant: that `get`
 * after `set` on the same cell returns the set value, and `get` after `set`
 * on a different cell returns the original value. The shipped `HarnessState`
 * uses `Map[StateCell.CellId, (StateCell[?], Any)]` with a single
 * `asInstanceOf` in `get`, and Stainless is pinned to Scala 3.7.2 while the
 * build is 3.8.4 — both are reasons the mirror exists, not reasons to skip.
 *
 * Abstraction:
 *   - A cell id becomes a `BigInt` key.
 *   - A cell value becomes a `BigInt` value.
 *   - The state becomes a `Map[BigInt, BigInt]` (simplified from the real
 *     `Map[CellId, (StateCell[?], Any)]` — the cell reference is not needed
 *     for the get/set coherence proof).
 *   - `get(c, s)` reads `s(c)` with a default of `0` (modeling the "absent
 *     reads initial" semantics — the initial value is abstracted as `0`).
 *   - `set(c, v, s)` returns `s.updated(c, v)`.
 *
 * The bridge spec (`HarnessStateKernelBridgeSpec` in adk4s-harness-api) runs
 * the real `HarnessState` and this model on the SAME generated cell ids and
 * values, and asserts they agree on the proven invariants.
 *
 * Postconditions:
 *   The get/set coherence laws are stated as standalone boolean lemma
 *   functions (separate from the recursive functions), following the
 *   PredictorKernel pattern. This separates termination proofs from
 *   property proofs. With the smt-z3 fallback solver, only termination is
 *   verified; the property lemmas timeout as UNKNOWN.
 */
object HarnessStateKernel:

  /** A state is a map from cell-id (BigInt) to value (BigInt). */
  type State = Map[BigInt, BigInt]

  /** The empty state (no cells). */
  val empty: State = Map.empty[BigInt, BigInt]

  /** Get the value for a cell id; absent cells read as `0` (modeling "initial"). */
  @pure
  def get(c: BigInt, s: State): BigInt =
    s.getOrElse(c, BigInt(0))

  /** Set a cell's value; returns a new state (immutable). */
  @pure
  def set(c: BigInt, v: BigInt, s: State): State =
    s.updated(c, v)

  /** Update a cell's value via a function. */
  @pure
  def update(c: BigInt, f: BigInt => BigInt, s: State): State =
    set(c, f(get(c, s)), s)

  // ---------------------------------------------------------------------------
  // Property lemmas — standalone boolean functions, verified AFTER the
  // functions above. With the native Z3 interface these prove VALID; with the
  // smt-z3 fallback they may timeout as UNKNOWN.
  // ---------------------------------------------------------------------------

  /**
   * Law 1: get-set coherence — `get(c, set(c, v, s)) == v`.
   */
  @pure
  def getSetCoherence(c: BigInt, v: BigInt, s: State): Boolean =
    get(c, set(c, v, s)) == v

  /**
   * Law 2: set preserves other cells — `get(d, set(c, v, s)) == get(d, s)`
   * for `c != d`.
   */
  @pure
  def setPreservesOtherCells(c: BigInt, d: BigInt, v: BigInt, s: State): Boolean =
    c == d || get(d, set(c, v, s)) == get(d, s)

  /**
   * Law 3: get-absent-reads-initial — `get(c, empty) == 0`.
   */
  @pure
  def getAbsentReadsInitial(c: BigInt): Boolean =
    get(c, empty) == BigInt(0)

  /**
   * Law 4: update coherence — `get(c, update(c, f, s)) == f(get(c, s))`.
   */
  @pure
  def updateCoherence(c: BigInt, f: BigInt => BigInt, s: State): Boolean =
    get(c, update(c, f, s)) == f(get(c, s))
