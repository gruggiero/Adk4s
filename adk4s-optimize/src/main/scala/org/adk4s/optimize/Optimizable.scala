package org.adk4s.optimize

import scala.compiletime.summonFrom
import scala.compiletime.erasedValue
import scala.compiletime.constValue
import scala.deriving.Mirror

/**
 * The optimizer-facing capability of a program type `P`.
 *
 * `Optimizable[P]` treats `P` as an opaque, type-erased product from the
 * optimizer's point of view. It provides three actions:
 *
 *  - `predictors(p)` — enumerate every tunable LM-call site with a stable
 *    path and its current `PredictorState`, in field declaration order.
 *  - `update(p, path, f)` — pure update of one predictor's state by path;
 *    total on paths returned by `predictors`, raises `OptimizeError` otherwise.
 *  - `updateAll(p, f)` — update every non-frozen predictor.
 *
 * Derivation is `Mirror`-based (`inline def derived`): for a case class `P`,
 * each field with a `HasPredictorState` instance contributes a leaf (path =
 * field name); each field with an `Optimizable` instance contributes its
 * subtree with the field name prepended; `Vector` fields of predictors
 * contribute indexed subtrees (segment = index as string); other fields are
 * ignored.
 *
 * The surface API is declared FROZEN by the `add-optimizable-surface` change:
 * any later change to these signatures requires its own proposal.
 */
trait Optimizable[P]:
  /**
   * Enumerate every predictor field of `p` with its path and current state,
   * in field declaration order. Non-predictor fields do not appear.
   */
  def predictors(p: P): Vector[(PredictorPath, PredictorState)]

  /**
   * Pure update of one predictor's state by path. Total on paths returned by
   * `predictors`; raises `OptimizeError.UnknownPath` for paths not enumerated,
   * and `OptimizeError.FrozenPath` when the addressed predictor is frozen.
   * Defined in terms of `updateEither`, raising the error on the left branch.
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def update(p: P, path: PredictorPath, f: PredictorState => PredictorState): P =
    updateEither(p, path, f).fold(e => throw e, identity)

  /**
   * Total variant: returns either the updated program or a typed error,
   * never raising. Returns `Left(OptimizeError.UnknownPath(path))` for a
   * path not enumerated by `predictors`, and
   * `Left(OptimizeError.FrozenPath(path))` when the addressed predictor's
   * state has the frozen flag set.
   */
  def updateEither(p: P, path: PredictorPath, f: PredictorState => PredictorState): Either[OptimizeError, P]

  /**
   * Apply `f` to every predictor whose `PredictorState.frozen` is `false`.
   * Frozen predictors are excluded (bit-identical in the result). A no-op
   * when all predictors are frozen.
   */
  def updateAll(p: P, f: (PredictorPath, PredictorState) => PredictorState): P =
    predictors(p).filter(!_._2.frozen).foldLeft(p) { (acc, entry) =>
      val (path, _) = entry
      update(acc, path, s => f(path, s))
    }

object Optimizable:
  /** Summon the `Optimizable[P]` instance. */
  def apply[P](using o: Optimizable[P]): Optimizable[P] = o

  /**
   * Derive an `Optimizable[P]` for any case class `P` via `Mirror.ProductOf[P]`.
   *
   * For each field (in declaration order):
   *   1. If the field type has an `Optimizable` instance, recurse into the
   *      subtree, prefixing the field name to every nested path.
   *   2. Else if the field type has a `HasPredictorState` instance, contribute
   *      a leaf with path = field name.
   *   3. Else if the field type is `Vector[ElemType]` and `ElemType` has a
   *      `HasPredictorState` instance, contribute indexed leaves (segment =
   *      index as string).
   *   4. Otherwise, skip the field (non-predictor).
   *
   * `Optimizable` is tried before `HasPredictorState` so a sub-program (which
   * has an `Optimizable` instance) is treated as a subtree, not a leaf.
   */
  inline def derived[P <: Product](using m: Mirror.ProductOf[P]): Optimizable[P] =
    new Optimizable[P]:
      def predictors(p: P): Vector[(PredictorPath, PredictorState)] =
        Optimizable.predictorsImpl[P](p)

      def updateEither(
        p: P,
        path: PredictorPath,
        f: PredictorState => PredictorState
      ): Either[OptimizeError, P] =
        Optimizable.updateEitherImpl[P](p, path, f)

  // ── predictors ─────────────────────────────────────────────────────────

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private inline def predictorsImpl[P](p: P)(using m: Mirror.ProductOf[P]): Vector[(PredictorPath, PredictorState)] =
    val product: Product         = p.asInstanceOf[Product]
    val fieldValues: Vector[Any] = product.productIterator.toVector
    val fieldNames: List[String] = fieldLabels[m.MirroredElemLabels]
    predictorsWalk[m.MirroredElemTypes](fieldValues, fieldNames, Vector.empty)

  /** Walk field types + values + names in parallel, accumulating predictor entries. */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.IterableOps"))
  private inline def predictorsWalk[Types <: Tuple](
    values: Vector[Any],
    names: List[String],
    prefix: Vector[String]
  ): Vector[(PredictorPath, PredictorState)] =
    inline erasedValue[Types] match
      case _: EmptyTuple => Vector.empty
      case _: (head *: tail) =>
        val fieldName: String                                    = names.head
        val fieldValue: Any                                      = values.head
        val fieldPath: Vector[String]                            = prefix :+ fieldName
        val contributed: Vector[(PredictorPath, PredictorState)] = predictField[head](fieldValue, fieldPath)
        contributed ++ predictorsWalk[tail](values.tail, names.tail, prefix)

  /**
   * For a single field, try Optimizable (subtree) → HasPredictorState (leaf)
   * → Vector[HasPredictorState] (collection) → skip.
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.IterableOps"))
  private inline def predictField[T](value: Any, fieldPath: Vector[String]): Vector[(PredictorPath, PredictorState)] =
    inline erasedValue[T] match
      case _: Vector[elem] =>
        predictCollection[elem](value, fieldPath)
      case _ =>
        summonFrom {
          case subOpt: Optimizable[T] =>
            val fieldName: String = fieldPath.last
            subOpt.predictors(value.asInstanceOf[T]).map { case (p, s) =>
              PredictorPath(Vector(fieldName) ++ p.segments) -> s
            }
          case leaf: HasPredictorState[T] =>
            Vector(PredictorPath(fieldPath) -> leaf.state(value.asInstanceOf[T]))
          case _ =>
            Vector.empty
        }

  /** Collection: index each element, try HasPredictorState on the element type. */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.IterableOps"))
  private inline def predictCollection[Elem](
    value: Any,
    fieldPath: Vector[String]
  ): Vector[(PredictorPath, PredictorState)] =
    summonFrom {
      case leaf: HasPredictorState[Elem] =>
        value.asInstanceOf[Vector[Any]].zipWithIndex.map { case (e, idx) =>
          PredictorPath(fieldPath :+ idx.toString) -> leaf.state(e.asInstanceOf[Elem])
        }
      case _ =>
        Vector.empty
    }

  // ── updateEither ────────────────────────────────────────────────────────

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private inline def updateEitherImpl[P](
    p: P,
    path: PredictorPath,
    f: PredictorState => PredictorState
  )(using m: Mirror.ProductOf[P]): Either[OptimizeError, P] =
    path.segments match
      case s if s.isEmpty =>
        Left(OptimizeError.UnknownPath(path))
      case _ =>
        val product: Product         = p.asInstanceOf[Product]
        val fieldValues: Vector[Any] = product.productIterator.toVector
        val fieldNames: List[String] = fieldLabels[m.MirroredElemLabels]
        updateWalk[m.MirroredElemTypes](fieldValues, fieldNames, Vector.empty, path, f).map { updatedValues =>
          val tuple: Tuple = updatedValues.foldLeft(EmptyTuple: Tuple)((acc, v) => acc :* v.asInstanceOf[Object])
          m.fromTuple(tuple.asInstanceOf[m.MirroredElemTypes])
        }

  /**
   * Walk fields looking for the one matching path.segments(prefix.size).
   * Returns the updated field-value vector, or Left(error).
   * The `inline erasedValue[Types]` and `inline erasedValue[head]` matches
   * are both in this method to keep the inline depth minimal (matching the
   * predict chain's depth). The vector case delegates to
   * `updateVectorViaPredict` which calls `predictCollectionWithUpdater` at
   * its top level (before runtime `if/else`).
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.IterableOps"))
  private inline def updateWalk[Types <: Tuple](
    values: Vector[Any],
    names: List[String],
    prefix: Vector[String],
    path: PredictorPath,
    f: PredictorState => PredictorState
  ): Either[OptimizeError, Vector[Any]] =
    inline erasedValue[Types] match
      case _: EmptyTuple =>
        Left(OptimizeError.UnknownPath(path))
      case _: (head *: tail) =>
        val fieldName: String       = names.head
        val fieldValue: Any         = values.head
        val restValues: Vector[Any] = values.tail
        val restNames: List[String] = names.tail
        val targetSegment: String   = path.segments(prefix.size)
        inline erasedValue[head] match
          case _: Vector[elem] =>
            updateVectorViaPredict[elem, tail](
              fieldValue,
              restValues,
              restNames,
              prefix,
              fieldName,
              targetSegment,
              path,
              f
            )
          case _ =>
            updateScalarField[head, tail](
              fieldValue,
              restValues,
              restNames,
              prefix,
              fieldName,
              targetSegment,
              path,
              f
            )

  /**
   * Handle a `Vector[Elem]` field. Calls `predictCollectionWithUpdater` at
   * the TOP (before runtime `if/else`) to resolve `HasPredictorState[Elem]`
   * via `summonFrom` — same pattern as `predictCollection` which works.
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.IterableOps"))
  private inline def updateVectorViaPredict[Elem, Tail <: Tuple](
    fieldValue: Any,
    restValues: Vector[Any],
    restNames: List[String],
    prefix: Vector[String],
    fieldName: String,
    targetSegment: String,
    path: PredictorPath,
    f: PredictorState => PredictorState
  ): Either[OptimizeError, Vector[Any]] =
    // Call predictCollectionWithUpdater FIRST — before any runtime if/else.
    // This ensures summonFrom resolves at the same inline depth as predictCollection.
    val entries: Vector[(PredictorPath, PredictorState, PredictorState => Any)] =
      predictCollectionWithUpdater[Elem](fieldValue, prefix :+ fieldName)
    if fieldName != targetSegment then updateWalk[Tail](restValues, restNames, prefix, path, f).map(fieldValue +: _)
    else
      val remaining: Vector[String] = path.segments.drop(prefix.size + 1)
      remaining match
        case r if r.isEmpty =>
          // A Vector field needs an index segment; a leaf path can't address it.
          Left(OptimizeError.UnknownPath(path))
        case _ =>
          updateCollectionFromEntries(entries, fieldValue, restValues, prefix, path, f)

  /**
   * Like `predictCollection`, but also returns an updater closure for each
   * element. The closure captures the `HasPredictorState[Elem]` instance and
   * the element value, so callers can update the element's state without
   * needing to resolve the typeclass themselves.
   *
   * Uses the same `summonFrom` pattern as `predictCollection`, which
   * successfully resolves `HasPredictorState[Elem]` at the inline expansion
   * site. The update path cannot resolve it directly (due to inline expansion
   * depth issues with pattern-bound types), so we reuse the predict path's
   * resolution via this closure-based approach.
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.IterableOps"))
  private inline def predictCollectionWithUpdater[Elem](
    value: Any,
    fieldPath: Vector[String]
  ): Vector[(PredictorPath, PredictorState, PredictorState => Any)] =
    summonFrom {
      case leaf: HasPredictorState[Elem] =>
        value.asInstanceOf[Vector[Any]].zipWithIndex.map { case (e, idx) =>
          val path: PredictorPath            = PredictorPath(fieldPath :+ idx.toString)
          val state: PredictorState          = leaf.state(e.asInstanceOf[Elem])
          val updater: PredictorState => Any = s => leaf.withState(e.asInstanceOf[Elem], s)
          (path, state, updater)
        }
      case _ =>
        Vector.empty
    }

  /**
   * Runtime collection update using entries from `predictCollectionWithUpdater`.
   * Each entry contains (path, state, updater). Finds the entry matching the
   * target path, checks frozen, applies `f`, and uses the updater to produce
   * the new element value.
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def updateCollectionFromEntries(
    entries: Vector[(PredictorPath, PredictorState, PredictorState => Any)],
    fieldValue: Any,
    restValues: Vector[Any],
    prefix: Vector[String],
    path: PredictorPath,
    f: PredictorState => PredictorState
  ): Either[OptimizeError, Vector[Any]] =
    val targetPath: PredictorPath = path
    entries.find(_._1 == targetPath) match
      case None =>
        Left(OptimizeError.UnknownPath(path))
      case Some((_, current, updater)) if current.frozen =>
        Left(OptimizeError.FrozenPath(path))
      case Some((_, current, updater)) =>
        val elems: Vector[Any] = fieldValue.asInstanceOf[Vector[Any]]
        val idxStr: String     = path.segments(prefix.size + 1)
        val idx: Int           = idxStr.toIntOption.getOrElse(-1)
        if idx < 0 || idx >= elems.size then Left(OptimizeError.UnknownPath(path))
        else
          val updatedElem: Any = updater(f(current))
          Right(elems.updated(idx, updatedElem) +: restValues)

  /**
   * Handle a scalar (non-Vector) field: skip if name doesn't match, or update
   * as leaf (HasPredictorState) or subtree (Optimizable).
   * `summonFrom` is at the very top (before runtime `if/else`) so that
   * typeclass instances resolve at the inline expansion site.
   */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.IterableOps"))
  private inline def updateScalarField[Head, Tail <: Tuple](
    fieldValue: Any,
    restValues: Vector[Any],
    restNames: List[String],
    prefix: Vector[String],
    fieldName: String,
    targetSegment: String,
    path: PredictorPath,
    f: PredictorState => PredictorState
  ): Either[OptimizeError, Vector[Any]] =
    summonFrom {
      case subOpt: Optimizable[Head] =>
        if fieldName != targetSegment then updateWalk[Tail](restValues, restNames, prefix, path, f).map(fieldValue +: _)
        else
          val remaining: Vector[String] = path.segments.drop(prefix.size + 1)
          remaining match
            case r if r.isEmpty =>
              // Leaf via Optimizable's HasPredictorState? No — Optimizable is a subtree.
              // A leaf path addressing a subtree field is an error.
              Left(OptimizeError.UnknownPath(path))
            case _ =>
              // Subpath: recurse into subtree.
              val subPath: PredictorPath = PredictorPath(path.segments.drop(prefix.size + 1))
              subOpt.updateEither(fieldValue.asInstanceOf[Head], subPath, f).map(updated => updated +: restValues)
      case leaf: HasPredictorState[Head] =>
        if fieldName != targetSegment then updateWalk[Tail](restValues, restNames, prefix, path, f).map(fieldValue +: _)
        else
          val remaining: Vector[String] = path.segments.drop(prefix.size + 1)
          remaining match
            case r if r.isEmpty =>
              // Leaf: path points directly at this field.
              val current: PredictorState = leaf.state(fieldValue.asInstanceOf[Head])
              if current.frozen then Left(OptimizeError.FrozenPath(path))
              else
                val updated: Head = leaf.withState(fieldValue.asInstanceOf[Head], f(current))
                Right(updated +: restValues)
            case _ =>
              // A leaf (HasPredictorState) can't have a subpath.
              Left(OptimizeError.UnknownPath(path))
      case _ =>
        if fieldName != targetSegment then updateWalk[Tail](restValues, restNames, prefix, path, f).map(fieldValue +: _)
        else Left(OptimizeError.UnknownPath(path))
    }

  // ── Mirror label extraction ─────────────────────────────────────────────

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private inline def fieldLabels[Labels <: Tuple]: List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        constValue[head].asInstanceOf[String] :: fieldLabels[tail]
