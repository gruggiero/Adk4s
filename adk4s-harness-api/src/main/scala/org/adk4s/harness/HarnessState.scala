package org.adk4s.harness

import upickle.default.*
import org.adk4s.core.json.{ JsonValue, JsonValueCodec }
import org.adk4s.core.error.StateDecodeError
import smithy4s.Document

/**
 * Typed, enumerable, serializable heterogeneous map.
 *
 * Carries middleware-declared state through the loop, across checkpoints,
 * and across sub-agent boundaries. `get` is total (absent cells read as
 * their declared `initial` value); `set`/`update` are immutable (produce
 * new states); `snapshot`/`restore` serialize to/from `JsonValue`.
 *
 * The single `asInstanceOf` in `get` is the classic typed-map argument:
 * the only path that writes the entry keyed by `cell.id` is `set(cell)(
 * value: A)` for a cell equal (by id) to this one, and id uniqueness
 * within a stack is validated at construction. The stored value is always
 * the `A` of the declaring cell.
 *
 * spec: harness-state — Requirement: HarnessState is a typed heterogeneous map
 */
final class HarnessState private (
  private val cells: Map[StateCell.CellId, (StateCell[?], Any)]
):
  /** Total: absent cells read as their declared initial value. */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def get[A](cell: StateCell[A]): A =
    cells.get(cell.id) match
      case Some((_, value)) => value.asInstanceOf[A]
      case None             => cell.initial

  /** Immutable set: produces a new `HarnessState` with the cell's value updated. */
  def set[A](cell: StateCell[A])(value: A): HarnessState =
    new HarnessState(cells.updated(cell.id, (cell, value)))

  /** Immutable update: `set(cell)(f(get(cell)))`. */
  def update[A](cell: StateCell[A])(f: A => A): HarnessState =
    set(cell)(f(get(cell)))

  /**
   * Snapshot the state as a `JsonValue` (DObject) for checkpoint persistence.
   *
   * Each cell is serialized via its `ReadWriter` codec to `ujson.Value`,
   * then bridged to `JsonValue` via `JsonValueCodec.fromUjson`.
   */
  def snapshot: JsonValue =
    val entries: Map[String, Document] = cells.values.map { case (cell, value) =>
      @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
      val rwAny: ReadWriter[Any] = cell.rw.asInstanceOf[ReadWriter[Any]]
      val uv: ujson.Value        = writeJs(value)(using rwAny)
      cell.id.value -> JsonValueCodec.fromUjson(uv)
    }.toMap
    Document.DObject(entries)

  /** Enumerable entries (for `MiddlewareStack.allCells`-driven operations). */
  private[harness] def entries: Iterable[(StateCell[?], Any)] = cells.values

  /** Cell ids present in this state (for debugging/testing). */
  private[harness] def cellIds: Iterable[StateCell.CellId] = cells.keys

  /** Internal set that accepts `Any` — used by `initial` and `restore`. */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private[harness] def setUnsafe(cell: StateCell[?], value: Any): HarnessState =
    new HarnessState(cells.updated(cell.id, (cell, value)))

object HarnessState:
  /** The empty state (no cells). */
  val empty: HarnessState = new HarnessState(Map.empty)

  /**
   * Construct an initial state from a declared cell list — each cell starts
   * at its `initial` value.
   */
  def initial(declared: List[StateCell[?]]): HarnessState =
    declared.foldLeft(empty)((s, c) => s.setUnsafe(c, c.initial))

  /**
   * Parent → child initial state (projection by visibility).
   *
   * - `Private`: child sees `initial` (parent value unobservable)
   * - `Inherited`: child sees parent's current value (read-only inheritance)
   * - `Shared`: child sees parent's current value (merges back on completion)
   */
  def project(parent: HarnessState, declared: List[StateCell[?]]): HarnessState =
    declared.foldLeft(HarnessState.initial(declared)) { (child, cell) =>
      cell.visibility match
        case CellVisibility.Private   => child
        case CellVisibility.Inherited => copyCell(parent, child, cell)
        case CellVisibility.Shared    => copyCell(parent, child, cell)
    }

  /**
   * Child final states → parent (merge-back by visibility).
   *
   * - `Shared`: fold `cell.merge` over children's values
   * - `Private`/`Inherited`: child writes dropped (parent unchanged)
   *
   * For semilattice `merge` functions, the fold is order-independent.
   */
  def mergeBack(
    parent: HarnessState,
    children: List[HarnessState],
    declared: List[StateCell[?]]
  ): HarnessState =
    declared.foldLeft(parent) { (acc, cell) =>
      cell.visibility match
        case CellVisibility.Private   => acc
        case CellVisibility.Inherited => acc
        case CellVisibility.Shared    => mergeShared(acc, children, cell)
    }

  /**
   * Restore state from a `JsonValue` snapshot (lenient by construction).
   *
   * - Unknown ids in `json` are silently ignored.
   * - Declared-but-absent cells read as `initial`.
   * - A cell that fails to decode is a hard `Left(StateDecodeError)`.
   * - A non-`DObject` `json` is a hard `Left(StateDecodeError)`.
   */
  def restore(
    declared: List[StateCell[?]],
    json: JsonValue
  ): Either[StateDecodeError, HarnessState] =
    json match
      case Document.DObject(fields) =>
        declared.foldLeft[Either[StateDecodeError, HarnessState]](Right(HarnessState.initial(declared))) {
          (accE, cell) =>
            accE.flatMap { acc =>
              fields.get(cell.id.value) match
                case None => Right(acc)
                case Some(docValue) =>
                  try
                    val uv: ujson.Value = JsonValueCodec.toUjson(docValue)
                    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
                    val rwAny: ReadWriter[Any] = cell.rw.asInstanceOf[ReadWriter[Any]]
                    val decoded: Any           = read[Any](uv)(using rwAny)
                    Right(acc.setUnsafe(cell, decoded))
                  catch
                    case e: Exception =>
                      Left(StateDecodeError(cell.id.value, e))
            }
        }
      case _ =>
        Left(StateDecodeError("<root>", new Exception(s"Expected DObject, got ${json.getClass.getSimpleName}")))

  // ── Internal helpers ──────────────────────────────────────────────────

  /** Copy a cell's value from parent to child (for `project`). */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def copyCell(parent: HarnessState, child: HarnessState, cell: StateCell[?]): HarnessState =
    parent.cells.get(cell.id) match
      case Some((_, value)) => child.setUnsafe(cell, value)
      case None             => child

  /** Fold `cell.merge` over children for a `Shared` cell (for `mergeBack`). */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def mergeShared[A](parent: HarnessState, children: List[HarnessState], cell: StateCell[A]): HarnessState =
    val parentVal: A = parent.get(cell)
    val merged: A    = children.foldLeft(parentVal)((acc, child) => cell.merge(acc, child.get(cell)))
    parent.set(cell)(merged)
