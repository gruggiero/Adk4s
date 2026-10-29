package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of the traceability reachability kernel
 * (graph-tool-port).
 *
 * The kernel decides one question: does a requirement reach an
 * enforcement artifact? Node ids are `BigInt` (the shipped adapter maps
 * `GraphNode.id` strings onto them); edges are the verified-by /
 * enforced-by relation the audit follows.
 *
 * Termination is by explicit fuel bounded by edge count — the
 * `fuel >= edges.size` precondition means a cyclic graph cannot make
 * the kernel diverge, and `fuel >= 0` rules out the degenerate negative
 * case. Internally the search runs at exactly `edges.size` fuel — a
 * simple path never exceeds the edge count, so the surplus is only a
 * precondition — which keeps the contract's `pathExists` (defined at
 * `edges.size` fuel) aligned with what the implementation proves.
 *
 * The two postconditions on `reaches` are the contract the audit is
 * trusted for:
 *
 *   1. **Grounded** — a `true` result means some artifact is actually
 *      reachable; the kernel cannot claim enforcement without a target.
 *   2. **Complete** — when some artifact is reachable, the result is
 *      `true`; the kernel cannot silently miss a path.
 *
 * `audit` conserves requirements: every in-scope requirement lands in
 * exactly one of the two result lists, so no requirement can be dropped
 * between the requirement set and the verdict.
 *
 * Proof discipline: a lambda literal never crosses a function boundary.
 * Two alpha-equivalent literals at different source sites are different
 * terms to the solver, so a lemma's postcondition mentioning
 * `(x) => !p(x)` cannot connect to a caller's `(r) => !p(r)` — the fact
 * is unusable and the VC degenerates into an unbounded unfolding. Every
 * predicate application therefore lives behind a named `def`
 * (`pathToAny`, `disjoint`) or is passed as one `val`-bound term.
 *
 * spec: graph-tool-port — Ring 6 Contract: reachability is grounded and complete
 * spec: graph-tool-port — Ring 6 Contract: the audit conserves requirements
 */
object ReachabilityKernel:

  // ── structural list primitives ───────────────────────────────────────
  // `exists`/`forall`/`filter` on lists generate VCs Z3 cannot discharge
  // (see docs/ring6-stainless-verification-experience.md §4) — these are
  // the same computations written as structural recursion.

  private def anyOf[A](l: List[A], p: A => Boolean): Boolean = {
    decreases(l.size)
    l match
      case Nil()      => false
      case Cons(h, t) => p(h) || anyOf(t, p)
  }

  private def allOf[A](l: List[A], p: A => Boolean): Boolean = {
    decreases(l.size)
    l match
      case Nil()      => true
      case Cons(h, t) => p(h) && allOf(t, p)
  }

  // ── the fuelled search ───────────────────────────────────────────────

  /**
   * Depth-first reachability to `artifacts` with `fuel` edge-steps —
   * target hit first, then fuel exhaustion, then the outgoing-edge
   * scan. `scan` keeps the WHOLE edge list in scope (`rem` only walks
   * it) so lemmas can name edges by membership in `edges`.
   *
   * Termination measure is the pair `(fuel, rem.size)` encoded as one
   * `BigInt` (tuple measures are not used anywhere in this module — see
   * docs/ring6-stainless-verification-experience.md §4): with
   * `E = edges.size`, `reachF` measures `(fuel + 1) * (E + 1)` and `scan`
   * measures `fuel * (E + 1) + rem.size`. `reachF → scan` drops the
   * fuel component, `scan → scan` shrinks `rem` at equal fuel, and
   * `scan → reachF` at `fuel - 1` measures `fuel * (E + 1)`, below the
   * caller's `fuel * (E + 1) + rem.size` because `rem` is a `Cons`.
   */
  private def reachF(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): Boolean = {
    require(fuel >= 0)
    decreases((fuel + 1) * (edges.size + 1))
    if artifacts.contains(from) then true
    else if fuel == 0 then false
    else scan(edges, edges, from, artifacts, fuel)
  }

  private def scan(
    edges: List[(BigInt, BigInt)],
    rem: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): Boolean = {
    require(fuel >= 1)
    decreases(fuel * (edges.size + 1) + rem.size)
    rem match
      case Nil()         => false
      case Cons(e, rest) =>
        (e._1 == from && reachF(edges, e._2, artifacts, fuel - 1)) ||
          scan(edges, rest, from, artifacts, fuel)
  }

  // ── spec-level reachability ─────────────────────────────────────────

  /**
   * Specification-level reachability: `from` reaches `to` in `edges`
   * — the fuelled search at the edge-count bound. A path longer than
   * `edges.size` repeats an edge and can be shortened, so the bound is
   * exact.
   */
  def pathExists(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    to: BigInt
  ): Boolean =
    reachF(edges, from, Cons(to, Nil()), edges.size)

  /** A satisfying member makes the scan true. */
  @pure
  private def scanIntro(
    edges: List[(BigInt, BigInt)],
    rem: List[(BigInt, BigInt)],
    e: (BigInt, BigInt),
    from: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): Unit = {
    require(fuel >= 1)
    require(rem.contains(e) && e._1 == from && reachF(edges, e._2, artifacts, fuel - 1))
    decreases(rem.size)
    rem match
      case Nil()      => ()
      case Cons(h, t) => if h == e then () else scanIntro(edges, t, e, from, artifacts, fuel)
  }.ensuring { (_: Unit) =>
    scan(edges, rem, from, artifacts, fuel)
  }

  /**
   * One edge extends a reachability fact — fuel-aligned, so the
   * recursive conclusion is exactly what the caller's scan needs.
   */
  @pure
  private def edgeExtends(
    edges: List[(BigInt, BigInt)],
    e: (BigInt, BigInt),
    from: BigInt,
    a: BigInt,
    fuel: BigInt
  ): Unit = {
    require(fuel >= 1)
    require(edges.contains(e) && e._1 == from && reachF(edges, e._2, Cons(a, Nil()), fuel - 1))
    scanIntro(edges, edges, e, from, Cons(a, Nil()), fuel)
  }.ensuring { (_: Unit) =>
    reachF(edges, from, Cons(a, Nil()), fuel)
  }

  // ── artifact-existence predicate ────────────────────────────────────
  // `artifacts.exists(a => pathExists(edges, from, a))` behind a named
  // def so the predicate literal lives at exactly one source site.

  private def pathToAny(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt]
  ): Boolean =
    anyOf(artifacts, (a: BigInt) => pathExists(edges, from, a))

  /** A satisfying member introduces `pathToAny`. Structural induction. */
  @pure
  private def pathToAnyIntro(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt],
    a: BigInt
  ): Unit = {
    require(artifacts.contains(a) && pathExists(edges, from, a))
    decreases(artifacts.size)
    artifacts match
      case Nil()      => ()
      case Cons(h, t) => if h == a then () else pathToAnyIntro(edges, from, t, a)
  }.ensuring { (_: Unit) =>
    pathToAny(edges, from, artifacts)
  }

  /** `pathToAny` yields a member satisfying `pathExists`. */
  @pure
  private def pathToAnyWit(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt]
  ): BigInt = {
    require(pathToAny(edges, from, artifacts))
    decreases(artifacts.size)
    artifacts match
      case Cons(h, t) =>
        if pathExists(edges, from, h) then h else pathToAnyWit(edges, from, t)
      case Nil() => BigInt(0) // danger-scan:allow unreachable — the require rules it out
  }.ensuring { (res: BigInt) =>
    artifacts.contains(res) && pathExists(edges, from, res)
  }

  // ── complete: a singleton hit transfers to the artifact superset ─────

  /**
   * Artifact-set monotonicity, `reachF` half: if the singleton `{a}`
   * search succeeds and `a ∈ artifacts`, the `artifacts` search succeeds
   * at the same fuel. The searches recurse identically; the superset's
   * base check is weaker. Mutually recursive with `monoScan`, mirroring
   * the `reachF`/`scan` recursion and sharing its encoded measure.
   */
  @pure
  private def monoRF(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    a: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): Unit = {
    require(fuel >= 0)
    require(artifacts.contains(a))
    require(reachF(edges, from, Cons(a, Nil()), fuel))
    decreases((fuel + 1) * (edges.size + 1))
    if a == from then ()
    else monoScan(edges, edges, from, a, artifacts, fuel)
  }.ensuring { (_: Unit) =>
    reachF(edges, from, artifacts, fuel)
  }

  /** Artifact-set monotonicity, `scan` half. */
  @pure
  private def monoScan(
    edges: List[(BigInt, BigInt)],
    rem: List[(BigInt, BigInt)],
    from: BigInt,
    a: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): Unit = {
    require(fuel >= 1)
    require(artifacts.contains(a))
    require(scan(edges, rem, from, Cons(a, Nil()), fuel))
    decreases(fuel * (edges.size + 1) + rem.size)
    rem match
      case Nil()         => ()
      case Cons(e, rest) =>
        if e._1 == from && reachF(edges, e._2, Cons(a, Nil()), fuel - 1) then
          monoRF(edges, e._2, a, artifacts, fuel - 1)
        else
          monoScan(edges, rest, from, a, artifacts, fuel)
  }.ensuring { (_: Unit) =>
    scan(edges, rem, from, artifacts, fuel)
  }

  // ── grounded: a hit yields an artifact witness with a real path ─────

  /**
   * Groundedness witness, `reachF` half: a successful search exhibits
   * the artifact it reached, and the singleton search for that artifact
   * succeeds at the same fuel — i.e. an actual path exists. Mutually
   * recursive with `gwScan`, mirroring `reachF`/`scan`.
   */
  @pure
  private def gwRF(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): BigInt = {
    require(fuel >= 0)
    require(reachF(edges, from, artifacts, fuel))
    decreases((fuel + 1) * (edges.size + 1))
    if artifacts.contains(from) then from
    else
      val ea: ((BigInt, BigInt), BigInt) = gwScan(edges, edges, from, artifacts, fuel)
      edgeExtends(edges, ea._1, from, ea._2, fuel)
      ea._2
  }.ensuring { (res: BigInt) =>
    artifacts.contains(res) && reachF(edges, from, Cons(res, Nil()), fuel)
  }

  /**
   * Groundedness witness, `scan` half: returns the edge the successful
   * scan consumed together with the artifact its target reaches. The
   * edge is guaranteed a member of `rem` — at the `gwRF` call site
   * `rem == edges`, so the caller gets `edges.contains`.
   */
  @pure
  private def gwScan(
    edges: List[(BigInt, BigInt)],
    rem: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): ((BigInt, BigInt), BigInt) = {
    require(fuel >= 1)
    require(scan(edges, rem, from, artifacts, fuel))
    decreases(fuel * (edges.size + 1) + rem.size)
    rem match
      case Cons(e, rest) =>
        if e._1 == from && reachF(edges, e._2, artifacts, fuel - 1) then
          val a: BigInt = gwRF(edges, e._2, artifacts, fuel - 1)
          (e, a)
        else
          gwScan(edges, rest, from, artifacts, fuel)
      case Nil() => ((BigInt(0), BigInt(0)), BigInt(0)) // danger-scan:allow unreachable — the require rules it out
  }.ensuring { (res: ((BigInt, BigInt), BigInt)) =>
    rem.contains(res._1) && res._1._1 == from &&
      artifacts.contains(res._2) &&
      reachF(edges, res._1._2, Cons(res._2, Nil()), fuel - 1)
  }

  // ── the contracted entry points ──────────────────────────────────────

  /**
   * Fuel-bounded reachability to the artifact set — the shipped
   * `GraphAudit.reaches` mirrors this signature.
   *
   * Postcondition (the spec's `reaches` contract):
   *  - grounded: `true` means some artifact is reachable;
   *  - complete: a reachable artifact means `true`.
   */
  @pure
  def reaches(
    edges: List[(BigInt, BigInt)],
    from: BigInt,
    artifacts: List[BigInt],
    fuel: BigInt
  ): Boolean = {
    require(fuel >= 0)
    require(fuel >= edges.length)
    val res: Boolean = reachF(edges, from, artifacts, edges.size)
    if res then
      val a: BigInt = gwRF(edges, from, artifacts, edges.size)
      pathToAnyIntro(edges, from, artifacts, a)
    else if pathToAny(edges, from, artifacts) then
      val a: BigInt = pathToAnyWit(edges, from, artifacts)
      monoRF(edges, from, a, artifacts, edges.size)
    else ()
    res
  }.ensuring { (res: Boolean) =>
    (res ==> pathToAny(edges, from, artifacts)) &&
      (pathToAny(edges, from, artifacts) ==> res)
  }

  /** The two result lists share no element. */
  def disjoint(xs: List[BigInt], ys: List[BigInt]): Boolean =
    allOf(xs, (x: BigInt) => !ys.contains(x))

  // ── audit conservation ──────────────────────────────────────────────

  /**
   * Single-pass partition: `(members satisfying p, members failing p)`.
   * The postcondition is self-verifying — the recursive call is the
   * induction hypothesis, so no cross-function predicate matching is
   * needed.
   */
  @pure
  private def partitionOf[A](l: List[A], p: A => Boolean): (List[A], List[A]) = {
    decreases(l.size)
    l match
      case Nil() => (Nil(), Nil())
      case Cons(h, t) =>
        val pt: (List[A], List[A]) = partitionOf(t, p)
        if p(h) then (Cons(h, pt._1), pt._2) else (pt._1, Cons(h, pt._2))
  }.ensuring { (res: (List[A], List[A])) =>
    res._1.size + res._2.size == l.size
  }

  /** A member of the first partition satisfies `p`. */
  @pure
  private def memFirst(l: List[BigInt], p: BigInt => Boolean, x: BigInt): Unit = {
    require(partitionOf(l, p)._1.contains(x))
    decreases(l.size)
    l match
      case Nil()      => ()
      case Cons(h, t) =>
        if p(h) then
          if h == x then () else memFirst(t, p, x)
        else memFirst(t, p, x)
  }.ensuring { (_: Unit) => p(x) }

  /** A member of the second partition fails `p`. */
  @pure
  private def memSecond(l: List[BigInt], p: BigInt => Boolean, x: BigInt): Unit = {
    require(partitionOf(l, p)._2.contains(x))
    decreases(l.size)
    l match
      case Nil()      => ()
      case Cons(h, t) =>
        if p(h) then memSecond(t, p, x)
        else
          if h == x then () else memSecond(t, p, x)
  }.ensuring { (_: Unit) => !p(x) }

  /** Disjointness is preserved when the second list grows by a non-member. */
  @pure
  private def disjointExtend(f1: List[BigInt], f2: List[BigInt], x: BigInt): Unit = {
    require(disjoint(f1, f2) && !f1.contains(x))
    decreases(f1.size)
    f1 match
      case Nil()      => ()
      case Cons(_, t) => disjointExtend(t, f2, x)
  }.ensuring { (_: Unit) => disjoint(f1, Cons(x, f2)) }

  /**
   * The two halves of a partition are disjoint: a shared member would
   * both satisfy and fail `p`. The `if … then memX` calls render the
   * membership branches infeasible, which is what proves the
   * `!contains` conjuncts `disjoint` unfolds to.
   */
  @pure
  private def partitionDisjoint(l: List[BigInt], p: BigInt => Boolean): Unit = {
    decreases(l.size)
    l match
      case Nil()      => ()
      case Cons(h, t) =>
        partitionDisjoint(t, p)
        val pt: (List[BigInt], List[BigInt]) = partitionOf(t, p)
        if p(h) then
          if pt._2.contains(h) then memSecond(t, p, h) else ()
        else
          if pt._1.contains(h) then memFirst(t, p, h) else ()
          disjointExtend(pt._1, pt._2, h)
  }.ensuring { (_: Unit) =>
    disjoint(partitionOf(l, p)._1, partitionOf(l, p)._2)
  }

  /**
   * The obligations audit on node ids: `(reaching, unenforced)` —
   * artifactless obligations are excluded upstream (they never produce
   * an artifact edge), so they cannot count as enforcement here.
   *
   * Postcondition (the spec's `auditConservation` contract): the two
   * lists together account for every requirement, and they share no
   * element.
   */
  @pure
  def audit(
    requirements: List[BigInt],
    edges: List[(BigInt, BigInt)],
    artifacts: List[BigInt]
  ): (List[BigInt], List[BigInt]) = {
    val p: BigInt => Boolean = (r: BigInt) => reachF(edges, r, artifacts, edges.size)
    partitionDisjoint(requirements, p)
    partitionOf(requirements, p)
  }.ensuring { (res: (List[BigInt], List[BigInt])) =>
    res._1.size + res._2.size == requirements.size &&
      disjoint(res._1, res._2)
  }

end ReachabilityKernel
