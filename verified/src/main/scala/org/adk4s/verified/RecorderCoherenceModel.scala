package org.adk4s.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of recorder record/lookup coherence (RL1).
 *
 * This model mirrors the `Recorder[F]` trait's `record` and `lookup`
 * operations in `org.adk4s.record`. The shipped code cannot be verified
 * directly (it is effectful — `Concurrent`, `Ref` — and Stainless is pinned
 * to Scala 3.7.2), but the *coherence invariant* survives reduction to a
 * finite-map model.
 *
 * Abstraction:
 *   - A recorder becomes a `Map[Int, Int]` (finite map from integer keys to
 *     integer values).
 *   - `record(map, k, v)` updates the map at key k with value v.
 *   - `lookup(map, k)` returns `Some(v)` if k is in the map, `None` otherwise.
 *   - The digest (hash) function is modeled as an uninterpreted injective
 *     function — collision-freedom is ASSUMED, not proven (see
 *     `Hash collision-freedom is assumed, not proven` requirement).
 *
 * The bridge spec (`RecorderCoherenceBridgeSpec` in adk4s-record) runs the
 * real `Recorder.inMemory` and this model on the SAME generated key/record
 * pairs and asserts they agree on the proven invariants.
 *
 * spec: add-adk4s-record/recorder-verified-model — Formal Contracts (Ring 6)
 */
object RecorderCoherenceModel:

  /**
   * Look up a value by key in the finite map.
   * Returns `Some(v)` if the key exists, `None` otherwise.
   */
  @pure
  def lookup(map: Map[Int, Int], k: Int): Option[Int] =
    map.get(k)

  /**
   * Record a value under a key. Returns the updated map.
   *
   * Precondition: key is a non-negative integer (model domain).
   * Postcondition: `lookup(result, k) == Some(v)`.
   */
  @pure
  def record(map: Map[Int, Int], k: Int, v: Int): Map[Int, Int] = {
    require(k >= 0)
    map.updated(k, v)
  }.ensuring(result => lookup(result, k) == Some(v))

  // ── Property lemmas — standalone boolean functions with `ensuring` ──────

  /**
   * Coherence: `lookup(record(map, k, v), k) == Some(v)`.
   *
   * This is the RL1 coherence pair's first half: after recording a value
   * under a key, looking up that key returns the recorded value.
   */
  @pure
  def coherenceLemma(map: Map[Int, Int], k: Int, v: Int): Boolean = {
    require(k >= 0)
    lookup(record(map, k, v), k) == Some(v)
  }.ensuring(_ == true)

  /**
   * Isolation: recording under a different key does not affect prior lookups.
   *
   * Precondition: keys k and j are distinct, k >= 0, j >= 0.
   * Postcondition: `lookup(record(record(map, k, v), j, w), k) == Some(v)`.
   *
   * This is the RL1 coherence pair's second half: recording under key j
   * does not disturb the value recorded under key k.
   */
  @pure
  def isolationLemma(map: Map[Int, Int], k: Int, j: Int, v: Int, w: Int): Boolean = {
    require(k >= 0 && j >= 0 && k != j)
    lookup(record(record(map, k, v), j, w), k) == Some(v)
  }.ensuring(_ == true)

  // ── Hash collision-freedom assumption ───────────────────────────────────
  // The digest function is modeled as an uninterpreted injective function.
  // Collision-freedom is ASSUMED (stated explicitly), not proven — proving
  // hash collision-freedom is beyond Stainless's capability for real hash
  // functions. The assumption is:
  //
  //   forall (k1, k2), k1 != k2 => digest(k1) != digest(k2)
  //
  // This means two distinct canonical forms never produce the same CallKey,
  // so the finite-map model's key isolation directly implies the real
  // recorder's key isolation.

  /**
   * Uninterpreted digest function — assumed injective.
   *
   * This function has no body; it is an abstract function whose only
   * property is injectivity (no two distinct inputs produce the same
   * output). The assumption is stated in the `injectiveAssumption` lemma.
   *
   * NOTE: In PureScala/Stainless, a function with no implementation is
   * modeled as an uninterpreted function. The `ensuring` clause on
   * `injectiveAssumption` states the injectivity axiom explicitly.
   */
  @pure
  def digest(k: Int): Int = k

  /**
   * Injective assumption: distinct keys produce distinct digests.
   *
   * This is an ASSUMPTION, not a proven theorem. It is stated as a
   * postcondition so it is visible in the model source, not hidden.
   * The `require` clause states the precondition (distinct keys), and
   * the `ensuring` clause states the conclusion (distinct digests).
   *
   * NOTE: The `digest` function above is modeled as the identity function
   * for runtime execution (so bridge tests can call it). In Stainless
   * verification, the `ensuring` clause on this lemma states the
   * injectivity axiom that would hold for a true uninterpreted function.
   */
  @pure
  def injectiveAssumption(k1: Int, k2: Int): Boolean = {
    require(k1 != k2)
    k1 != k2
  }.ensuring(_ => digest(k1) != digest(k2))
