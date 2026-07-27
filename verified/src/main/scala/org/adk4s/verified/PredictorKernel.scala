package org.adk4s.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of the `Optimizable` predictor enumeration and
 * update algorithm.
 *
 * This model mirrors the pre-order traversal that `Optimizable.derived`
 * performs via `Mirror`-based inline derivation in the main module. The
 * shipped code cannot be verified directly (it uses `Mirror`/`inline` and
 * `ujson.Value`, and Stainless is pinned to Scala 3.7.2), but the *algorithm*
 * is a pure pre-order traversal that survives reduction to observable effect.
 *
 * Abstraction:
 *   - A program becomes a tree (`Prog`).
 *   - A predictor-state becomes a `BigInt` identity.
 *   - A path becomes the `List[BigInt]` of child indices that reaches a leaf.
 *   - Field declaration order becomes list order.
 *
 * The bridge spec (`PredictorModelBridgeSpec` in adk4s-optimize) runs the real
 * `Optimizable` and this model on the SAME generated programs and asserts they
 * agree on the proven invariants.
 */
object PredictorKernel:

  /**
   * A program tree: predictor leaf, non-predictor field, nested program, or
   * ordered collection.
   */
  sealed abstract class Prog
  case class Pred(frozen: Boolean, state: BigInt) extends Prog
  case class Plain()                              extends Prog
  case class Sub(fields: List[Prog])              extends Prog
  case class Coll(elems: List[Prog])              extends Prog

  /** Count the number of `Pred` leaves in a tree. */
  @pure
  def countPreds(p: Prog): BigInt = {
    decreases(p)
    p match {
      case Pred(_, _) => BigInt(1)
      case Plain()    => BigInt(0)
      case Sub(fs)    => sumList(fs.map(countPreds))
      case Coll(es)   => sumList(es.map(countPreds))
    }
  }

  /** Sum a list of BigInts. */
  @pure
  def sumList(xs: List[BigInt]): BigInt = {
    decreases(xs)
    xs match {
      case Nil()      => BigInt(0)
      case Cons(h, t) => h + sumList(t)
    }
  }

  /**
   * Enumerate every predictor leaf path in pre-order traversal.
   *
   * Path = list of child indices from the root to the leaf.
   * `Plain` fields are excluded (non-predictor).
   * `Sub` and `Coll` are traversed in list order (declaration order).
   *
   * Postcondition: result length == countPreds(p) (completeness).
   */
  @pure
  def paths(p: Prog, prefix: List[BigInt]): List[List[BigInt]] = {
    decreases(p)
    p match {
      case Pred(_, _) => List(prefix)
      case Plain()    => Nil[List[BigInt]]()
      case Sub(fs)    => indexed(fs, prefix)
      case Coll(es)   => indexed(es, prefix)
    }
  }.ensuring(_.size == countPreds(p))

  /** Index a list of children, prepending each index to the prefix. */
  @pure
  def indexed(children: List[Prog], prefix: List[BigInt]): List[List[BigInt]] = {
    decreases(children)
    children match {
      case Nil() => Nil[List[BigInt]]()
      case Cons(h, t) =>
        val idx: BigInt = BigInt(0)
        paths(h, prefix ++ Cons(idx, Nil())) ++ indexedRest(t, prefix, BigInt(1))
    }
  }

  /** Tail-recursive helper for indexing the rest of the list. */
  @pure
  def indexedRest(children: List[Prog], prefix: List[BigInt], idx: BigInt): List[List[BigInt]] = {
    decreases(children)
    children match {
      case Nil() => Nil[List[BigInt]]()
      case Cons(h, t) =>
        paths(h, prefix ++ Cons(idx, Nil())) ++ indexedRest(t, prefix, idx + BigInt(1))
    }
  }

  /**
   * Update every non-frozen predictor's state using `f`.
   *
   * Frozen predictors are returned bit-identical.
   * The shape (path set) is preserved.
   *
   * Postcondition:
   *   - paths(updateAll(p, f), Nil) == paths(p, Nil)  (shape preserved)
   *   - frozenStates(updateAll(p, f)) == frozenStates(p)  (frozen untouched)
   */
  @pure
  def updateAll(p: Prog, f: BigInt => BigInt): Prog = {
    decreases(p)
    p match {
      case Pred(fr, st) => if (fr) Pred(fr, st) else Pred(fr, f(st))
      case Plain()      => Plain()
      case Sub(fs)      => Sub(fs.map(updateAll(_, f)))
      case Coll(es)     => Coll(es.map(updateAll(_, f)))
    }
  }.ensuring(r => paths(r, Nil()) == paths(p, Nil()) && frozenStates(r) == frozenStates(p))

  /** Extract the states of all frozen predictors, in pre-order. */
  @pure
  def frozenStates(p: Prog): List[BigInt] = {
    decreases(p)
    p match {
      case Pred(true, st) => List(st)
      case Pred(false, _) => Nil[BigInt]()
      case Plain()        => Nil[BigInt]()
      case Sub(fs)        => fs.flatMap(frozenStates)
      case Coll(es)       => es.flatMap(frozenStates)
    }
  }

  /**
   * Round-trip identity: updating with the identity function returns an equal
   * program.
   */
  @pure
  def roundTripIdentity(p: Prog): Boolean = {
    updateAll(p, x => x) == p
  }.ensuring(_ == true)
