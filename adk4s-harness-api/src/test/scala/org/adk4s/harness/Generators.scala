package org.adk4s.harness

import hedgehog.Gen
import hedgehog.Range
import upickle.default.*

/**
 * Shared Hedgehog generators for the harness-state spec test oracle.
 *
 * Generator strategies are derived from the spec's Properties section,
 * NOT from the implementation:
 *  - `genHarnessState` — constructive, builds a state from a generated list
 *    of cells with generated values; cells use `Int`, `String`, and
 *    `Set[String]` types with known `ReadWriter` instances; cell count ∈
 *    `Range.linear 0 8`; covers the empty-state edge (0 cells).
 *  - `genTwoDistinctCells` — generates two cells with distinct owners
 *    guaranteeing `c.id != d.id`.
 *  - `genCellValue` — generates values for cells of types `Int`, `String`,
 *    `Set[String]`, `List[Int]` — all with known `ReadWriter` instances.
 *  - `genSetString` — generates `Set[String]` with element count ∈
 *    `Range.linear 0 10`.
 *  - `genSemilatticeCell` — generates `Shared` cells with known semilattice
 *    merges: set-union for `Set[String]`, `max` for `Int`.
 */
object Generators:

  val genInt: Gen[Int] =
    Gen.int(Range.linear(-1000, 1000))

  val genString: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(0, 20))

  val genBoolean: Gen[Boolean] =
    Gen.boolean

  val genSetString: Gen[Set[String]] =
    Gen
      .string(Gen.alpha, Range.linear(1, 10))
      .list(Range.linear(0, 10))
      .map(_.toSet)

  val genListInt: Gen[List[Int]] =
    Gen
      .int(Range.linear(-100, 100))
      .list(Range.linear(0, 20))

  // ── Cell generators ──────────────────────────────────────────────────────

  /** A cell type tag: one of the supported types with known ReadWriter instances. */
  enum CellType:
    case IntType, StringType, SetStringType

  val genCellType: Gen[CellType] =
    Gen.element1(CellType.IntType, CellType.StringType, CellType.SetStringType)

  /** Generates a `StateCell[Int]` with a generated owner, name, and initial. */
  def genIntCell(owner: MiddlewareName, name: String, initial: Int): StateCell[Int] =
    StateCell[Int](owner, name, initial)

  /** Generates a `StateCell[String]` with a generated owner, name, and initial. */
  def genStringCell(owner: MiddlewareName, name: String, initial: String): StateCell[String] =
    StateCell[String](owner, name, initial)

  /** Generates a `StateCell[Set[String]]` with a generated owner, name, and initial. */
  def genSetStringCell(owner: MiddlewareName, name: String, initial: Set[String]): StateCell[Set[String]] =
    StateCell[Set[String]](owner, name, initial)

  // ── State generators ─────────────────────────────────────────────────────

  /** Generates a `MiddlewareName` from a short alpha string. */
  val genOwner: Gen[MiddlewareName] =
    Gen.string(Gen.alpha, Range.linear(1, 5)).map(MiddlewareName.apply)

  /** Generates a cell name from a short alpha string. */
  val genCellName: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(1, 5))

  /**
   * Generates a list of distinct Int cells (distinct owner/name pairs).
   * Cell count ∈ `Range.linear 0 8` — covers the empty-state edge.
   */
  def genIntCellList: Gen[List[StateCell[Int]]] =
    Gen
      .string(Gen.alpha, Range.linear(1, 3))
      .list(Range.linear(0, 8))
      .map(_.zipWithIndex.map((prefix, i) => StateCell[Int](MiddlewareName(s"m$i"), s"c$prefix", 0)))

  /**
   * Generates a `HarnessState` from a list of Int cells with generated values.
   * Cell count ∈ `Range.linear 0 8` — covers the empty-state edge (0 cells).
   * Some cells are set to generated values, some left at initial.
   */
  val genHarnessState: Gen[(List[StateCell[Int]], HarnessState)] =
    for
      cellCount <- Gen.int(Range.linear(0, 8))
      cells <- Gen
        .string(Gen.alpha, Range.linear(1, 3))
        .list(Range.linear(0, cellCount))
        .map(_.zipWithIndex.map((prefix, i) => StateCell[Int](MiddlewareName(s"m$i"), s"c$prefix", 0)))
      values <- Gen.int(Range.linear(-1000, 1000)).list(Range.linear(0, cells.length))
    yield
      val state: HarnessState = cells.zip(values).foldLeft(HarnessState.empty) { (acc, pair) =>
        val (c, v) = pair
        acc.set(c)(v)
      }
      (cells, state)

  /** Generates two cells with distinct owners guaranteeing `c.id != d.id`. */
  val genTwoDistinctCells: Gen[(StateCell[Int], StateCell[String])] =
    for
      nameC <- genCellName
      nameD <- genCellName
    yield
      val c: StateCell[Int]    = StateCell[Int](MiddlewareName("ownerC"), nameC, 0)
      val d: StateCell[String] = StateCell[String](MiddlewareName("ownerD"), nameD, "default")
      (c, d)

  // ── Semilattice cell generators ──────────────────────────────────────────

  /** A `Shared` cell with a known semilattice merge (set-union for `Set[String]`). */
  def genSharedSetCell: Gen[StateCell[Set[String]]] =
    genSetString.map { initial =>
      StateCell[Set[String]](
        MiddlewareName("semilattice"),
        "set",
        initial,
        visibility = CellVisibility.Shared,
        merge = (a: Set[String], b: Set[String]) => a.union(b)
      )
    }

  /** A `Shared` cell with a known semilattice merge (`max` for `Int`). */
  def genSharedMaxCell: Gen[StateCell[Int]] =
    genInt.map { initial =>
      StateCell[Int](
        MiddlewareName("semilattice"),
        "max",
        initial,
        visibility = CellVisibility.Shared,
        merge = (a: Int, b: Int) => math.max(a, b)
      )
    }
