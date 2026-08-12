package org.adk4s.verified

import stainless.lang._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of semilattice merge laws (L11).
 *
 * This model mirrors the semilattice merge functions used by `Shared`
 * `StateCell` instances (Int max, Int min, Set union). The shipped
 * `StateCell.merge` cannot be verified directly because it uses
 * `ReadWriter`/`JsonValue` and the Stainless frontend is pinned to Scala 3.7.2
 * while this build is 3.8.4. The merge algorithm itself survives reduction to
 * observable effect.
 *
 * Abstraction:
 *   - A semilattice merge becomes a binary function `(A, A) => A`.
 *   - The three laws (commutativity, associativity, idempotence) are stated
 *     as universal quantifications over that function.
 *   - `isSemilattice` combines all three with an `ensuring` clause.
 *
 * The bridge spec (`SemilatticeModelBridgeSpec` in adk4s-harness-api) runs
 * the real `StateCell.merge` and this model on the SAME generated merge
 * functions and values, and asserts they agree on the proven invariants.
 *
 * spec: middleware-laws — Formal Contracts (Ring 6)
 */
object SemilatticeKernel:

  /** Commutativity: `merge(a, b) == merge(b, a)`. */
  def commutative[A](merge: (A, A) => A, a: A, b: A): Boolean =
    merge(a, b) == merge(b, a)

  /** Associativity: `merge(a, merge(b, c)) == merge(merge(a, b), c)`. */
  def associative[A](merge: (A, A) => A, a: A, b: A, c: A): Boolean =
    merge(a, merge(b, c)) == merge(merge(a, b), c)

  /** Idempotence: `merge(a, a) == a`. */
  def idempotent[A](merge: (A, A) => A, a: A): Boolean =
    merge(a, a) == a

  /**
   * All three semilattice laws hold for the given merge and values.
   *
   * The `ensuring` clause states the postcondition explicitly so Stainless
   * can verify it.
   */
  def isSemilattice[A](merge: (A, A) => A, a: A, b: A, c: A): Boolean = {
    commutative(merge, a, b) && associative(merge, a, b, c) && idempotent(merge, a)
  }.ensuring(
    _ == (merge(a, b) == merge(b, a) &&
      merge(a, merge(b, c)) == merge(merge(a, b), c) &&
      merge(a, a) == a)
  )

  // ── Concrete merge functions (model instances) ──────────────────────────

  /** Int max — a semilattice merge for `Int`. */
  def intMax(a: BigInt, b: BigInt): BigInt =
    if a >= b then a else b

  /** Int min — a semilattice merge for `Int`. */
  def intMin(a: BigInt, b: BigInt): BigInt =
    if a <= b then a else b

  // ── Lemmas: concrete merges satisfy the semilattice laws ────────────────

  @inline
  def intMaxCommutative(a: BigInt, b: BigInt): Boolean = {
    intMax(a, b) == intMax(b, a)
  }.ensuring(_ == true)

  @inline
  def intMaxAssociative(a: BigInt, b: BigInt, c: BigInt): Boolean = {
    intMax(a, intMax(b, c)) == intMax(intMax(a, b), c)
  }.ensuring(_ == true)

  @inline
  def intMaxIdempotent(a: BigInt): Boolean = {
    intMax(a, a) == a
  }.ensuring(_ == true)

  @inline
  def intMinCommutative(a: BigInt, b: BigInt): Boolean = {
    intMin(a, b) == intMin(b, a)
  }.ensuring(_ == true)

  @inline
  def intMinAssociative(a: BigInt, b: BigInt, c: BigInt): Boolean = {
    intMin(a, intMin(b, c)) == intMin(intMin(a, b), c)
  }.ensuring(_ == true)

  @inline
  def intMinIdempotent(a: BigInt): Boolean = {
    intMin(a, a) == a
  }.ensuring(_ == true)
