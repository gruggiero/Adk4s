package org.adk4s.harness
package typecontract

import munit.FunSuite
import upickle.default.*
import org.adk4s.core.json.JsonValue
import org.adk4s.core.error.{ AdkError, StateDecodeError }
import smithy4s.Document

/**
 * Typed contract for the harness-state spec (Step 1 — HUMAN GATE 1).
 *
 * Verifies that every signature from design.md §3 compiles with exact types:
 *  - `MiddlewareName` opaque type with `apply` + `.value` extension
 *  - `PromptSection` case class with `name: String, body: String`
 *  - `SystemPrompt` case class with `base: Option[String], sections: List[PromptSection]`, `render: String`
 *  - `CellVisibility` enum with `Private` / `Inherited` / `Shared`
 *  - `StateCell[A]` final class with `id`, `visibility`, `initial`, `merge`, `rw`; `CellId` opaque type
 *  - `StateCell.apply` factory with `ReadWriter` context bound
 *  - `StateDecodeError` case class extending `AdkError` with `cellId: String, cause: Throwable`
 *  - `HarnessState` final class with `get[A]`, `set[A]`, `update[A]`, `snapshot`, `entries`
 *  - `HarnessState.empty`, `.initial`, `.project`, `.mergeBack`, `.restore`
 */
class HarnessStateTypeContract extends FunSuite:

  // ── MiddlewareName ──────────────────────────────────────────────────────

  test("MiddlewareName is an opaque type with apply + .value"):
    val name: MiddlewareName = MiddlewareName("todo")
    val underlying: String   = name.value
    assertEquals(underlying, "todo")

  // ── PromptSection ───────────────────────────────────────────────────────

  test("PromptSection has name and body"):
    val section: PromptSection = PromptSection("rules", "be kind")
    val name: String           = section.name
    val body: String           = section.body
    assertEquals(name, "rules")
    assertEquals(body, "be kind")

  // ── SystemPrompt ────────────────────────────────────────────────────────

  test("SystemPrompt has base, sections, and render"):
    val prompt: SystemPrompt          = SystemPrompt(Some("you are an agent"), List(PromptSection("rules", "be kind")))
    val base: Option[String]          = prompt.base
    val sections: List[PromptSection] = prompt.sections
    val rendered: String              = prompt.render
    assert(base.isDefined)
    assertEquals(sections.length, 1)
    assert(rendered.nonEmpty)

  test("SystemPrompt with no base and no sections renders empty"):
    val prompt: SystemPrompt = SystemPrompt(None, Nil)
    val rendered: String     = prompt.render
    assertEquals(rendered, "")

  // ── CellVisibility ──────────────────────────────────────────────────────

  test("CellVisibility has Private, Inherited, Shared — exhaustive match"):
    val v: CellVisibility = CellVisibility.Private
    val label: String = v match
      case CellVisibility.Private   => "private"
      case CellVisibility.Inherited => "inherited"
      case CellVisibility.Shared    => "shared"
    assertEquals(label, "private")

  // ── StateCell ───────────────────────────────────────────────────────────

  test("StateCell has id, visibility, initial, merge, rw — default visibility and merge"):
    given ReadWriter[Int]          = readwriter[Int]
    val owner: MiddlewareName      = MiddlewareName("counter")
    val cell: StateCell[Int]       = StateCell[Int](owner, "n", 0)
    val id: StateCell.CellId       = cell.id
    val idStr: String              = id.value
    val visibility: CellVisibility = cell.visibility
    val initial: Int               = cell.initial
    val merged: Int                = cell.merge(10, 20)
    assertEquals(idStr, "counter/n")
    assertEquals(visibility, CellVisibility.Private)
    assertEquals(initial, 0)
    assertEquals(merged, 20) // last-write-wins default

  test("StateCell with Shared visibility and custom merge"):
    given ReadWriter[Set[String]] = readwriter[Set[String]]
    val owner: MiddlewareName     = MiddlewareName("todo")
    val cell: StateCell[Set[String]] = StateCell[Set[String]](
      owner,
      "items",
      Set.empty[String],
      visibility = CellVisibility.Shared,
      merge = (a: Set[String], b: Set[String]) => a.union(b)
    )
    assertEquals(cell.visibility, CellVisibility.Shared)
    assertEquals(cell.merge(Set("a"), Set("b")), Set("a", "b"))

  test("StateCell equality is by CellId, not object identity"):
    given ReadWriter[Int]     = readwriter[Int]
    val owner: MiddlewareName = MiddlewareName("counter")
    val c1: StateCell[Int]    = StateCell[Int](owner, "n", 0)
    val c2: StateCell[Int]    = StateCell[Int](owner, "n", 99)
    assertEquals(c1, c2) // same id → equal
    assertEquals(c1.hashCode, c2.hashCode)

  test("StateCell with different names are not equal"):
    given ReadWriter[Int]     = readwriter[Int]
    val owner: MiddlewareName = MiddlewareName("counter")
    val c1: StateCell[Int]    = StateCell[Int](owner, "n", 0)
    val c2: StateCell[Int]    = StateCell[Int](owner, "m", 0)
    assertNotEquals(c1, c2)

  // ── StateDecodeError ────────────────────────────────────────────────────

  test("StateDecodeError extends AdkError with cellId and cause"):
    val cause: Throwable      = new Exception("bad json")
    val err: StateDecodeError = StateDecodeError("counter/n", cause)
    val adkErr: AdkError      = err
    val cellId: String        = err.cellId
    val errCause: Throwable   = err.cause
    val msg: String           = err.message
    assertEquals(cellId, "counter/n")
    assert(msg.contains("counter/n"))

  // ── HarnessState — get / set / update ───────────────────────────────────

  test("HarnessState.get is total — absent cells read as initial"):
    given ReadWriter[Int]    = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("c"), "n", 42)
    val state: HarnessState  = HarnessState.empty
    val value: Int           = state.get(cell)
    assertEquals(value, 42) // absent → initial

  test("HarnessState.set updates a cell and preserves others"):
    given ReadWriter[Int]     = readwriter[Int]
    given ReadWriter[String]  = readwriter[String]
    val c1: StateCell[Int]    = StateCell[Int](MiddlewareName("c"), "n1", 0)
    val c2: StateCell[String] = StateCell[String](MiddlewareName("c"), "n2", "default")
    val s0: HarnessState      = HarnessState.initial(List(c1, c2))
    val s1: HarnessState      = s0.set(c1)(10)
    assertEquals(s1.get(c1), 10)
    assertEquals(s1.get(c2), "default") // c2 unchanged
    assertEquals(s0.get(c1), 0)         // s0 unchanged (immutable)

  test("HarnessState.update applies a function"):
    given ReadWriter[Int]    = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("c"), "n", 0)
    val s0: HarnessState     = HarnessState.initial(List(cell))
    val s1: HarnessState     = s0.update(cell)(_ + 5)
    assertEquals(s1.get(cell), 5)

  // ── HarnessState — snapshot / restore ───────────────────────────────────

  test("HarnessState.snapshot returns a DObject"):
    given ReadWriter[Int]    = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("c"), "n", 0)
    val state: HarnessState  = HarnessState.initial(List(cell)).set(cell)(42)
    val snap: JsonValue      = state.snapshot
    snap match
      case Document.DObject(fields) =>
        assert(fields.contains("c/n"))
      case _ => fail("snapshot should be a DObject")

  test("HarnessState.restore is lenient — unknown ids ignored, missing cells default"):
    given ReadWriter[Int]    = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("c"), "n", 0)
    val json: JsonValue = Document.DObject(
      Map(
        "c/n"        -> Document.DNumber(BigDecimal(42)),
        "unknown/id" -> Document.DString("stranger")
      )
    )
    val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
    result match
      case Right(state) =>
        assertEquals(state.get(cell), 42)
      case Left(err) => fail(s"restore should succeed: $err")

  test("HarnessState.restore with non-DObject is Left"):
    given ReadWriter[Int]                              = readwriter[Int]
    val cell: StateCell[Int]                           = StateCell[Int](MiddlewareName("c"), "n", 0)
    val json: JsonValue                                = Document.DArray(Vector(Document.DString("x")))
    val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
    assert(result.isLeft)

  test("HarnessState.snapshot → restore round-trip"):
    given ReadWriter[Int]     = readwriter[Int]
    given ReadWriter[String]  = readwriter[String]
    val c1: StateCell[Int]    = StateCell[Int](MiddlewareName("c"), "n1", 0)
    val c2: StateCell[String] = StateCell[String](MiddlewareName("c"), "n2", "default")
    val state: HarnessState   = HarnessState.initial(List(c1, c2)).set(c1)(42).set(c2)("hello")
    val snap: JsonValue       = state.snapshot
    val restored: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(c1, c2), snap)
    restored match
      case Right(s) =>
        assertEquals(s.get(c1), 42)
        assertEquals(s.get(c2), "hello")
      case Left(err) => fail(s"round-trip should succeed: $err")

  // ── HarnessState — project / mergeBack ──────────────────────────────────

  test("HarnessState.project — Private cells see initial, Inherited/Shared see parent"):
    given ReadWriter[Int]     = readwriter[Int]
    val cPriv: StateCell[Int] = StateCell[Int](MiddlewareName("c"), "priv", 0, visibility = CellVisibility.Private)
    val cInh: StateCell[Int]  = StateCell[Int](MiddlewareName("c"), "inh", 0, visibility = CellVisibility.Inherited)
    val cShar: StateCell[Int] = StateCell[Int](MiddlewareName("c"), "shar", 0, visibility = CellVisibility.Shared)
    val declared: List[StateCell[?]] = List(cPriv, cInh, cShar)
    val parent: HarnessState = HarnessState
      .initial(declared)
      .set(cPriv)(100)
      .set(cInh)(200)
      .set(cShar)(300)
    val child: HarnessState = HarnessState.project(parent, declared)
    assertEquals(child.get(cPriv), 0)   // Private → initial
    assertEquals(child.get(cInh), 200)  // Inherited → parent value
    assertEquals(child.get(cShar), 300) // Shared → parent value

  test("HarnessState.mergeBack — Shared folds, Private/Inherited unchanged"):
    given ReadWriter[Int]     = readwriter[Int]
    val cPriv: StateCell[Int] = StateCell[Int](MiddlewareName("c"), "priv", 0, visibility = CellVisibility.Private)
    val cInh: StateCell[Int]  = StateCell[Int](MiddlewareName("c"), "inh", 0, visibility = CellVisibility.Inherited)
    val cShar: StateCell[Int] = StateCell[Int](
      MiddlewareName("c"),
      "shar",
      0,
      visibility = CellVisibility.Shared,
      merge = (a: Int, b: Int) => a + b
    )
    val declared: List[StateCell[?]] = List(cPriv, cInh, cShar)
    val parent: HarnessState = HarnessState
      .initial(declared)
      .set(cPriv)(10)
      .set(cInh)(20)
      .set(cShar)(100)
    val child1: HarnessState = HarnessState
      .project(parent, declared)
      .set(cPriv)(999) // Private write — should be dropped
      .set(cInh)(999)  // Inherited write — should be dropped
      .set(cShar)(50)  // Shared write — should merge back
    val child2: HarnessState = HarnessState
      .project(parent, declared)
      .set(cShar)(60) // Another Shared write
    val merged: HarnessState = HarnessState.mergeBack(parent, List(child1, child2), declared)
    assertEquals(merged.get(cPriv), 10)  // Private — parent unchanged
    assertEquals(merged.get(cInh), 20)   // Inherited — parent unchanged
    assertEquals(merged.get(cShar), 210) // Shared — 100 + 50 + 60

  // ── Compile-negative obligations ─────────────────────────────────────────
  // The spec's Compile-Negative Obligations table requires assertDoesNotCompile
  // for each forbidden construction. munit's `compileErrors` macro returns the
  // compiler error string for the given snippet; we assert it is non-empty.
  //
  // NOTE: The `compileErrors` macro does NOT apply the project's
  // `-Wconf:name=PatternMatchExhaustivity:e` flag, so exhaustiveness
  // obligations (catch-all in CellVisibility match, catch-all in AdkError
  // match missing StateDecodeError) are enforced at RING 0 (`sbt compile`),
  // where `-Wconf` IS active. The positive side (exhaustive match compiles)
  // is tested above. Here we test the type-level obligations that
  // `compileErrors` CAN catch: opaque type boundaries, context bounds, and
  // private constructors.

  test("StateCell without a ReadWriter in scope does not compile"):
    val errors: String = compileErrors("""
      final class NoCodec
      val owner: org.adk4s.harness.MiddlewareName = org.adk4s.harness.MiddlewareName("m")
      val cell: org.adk4s.harness.StateCell[NoCodec] =
        org.adk4s.harness.StateCell[NoCodec](owner, "x", new NoCodec)
    """)
    assert(errors.nonEmpty, "StateCell[NoCodec] must not compile without a ReadWriter[NoCodec]")

  test("CellId is not constructible from a raw String"):
    val errors: String = compileErrors("""
      val id: org.adk4s.harness.StateCell.CellId = "raw"
    """)
    assert(errors.nonEmpty, "CellId must not be assignable from a raw String")

  test("MiddlewareName is not assignable to a raw String without .value"):
    val errors: String = compileErrors("""
      val name: org.adk4s.harness.MiddlewareName = org.adk4s.harness.MiddlewareName("m")
      val s: String = name
    """)
    assert(errors.nonEmpty, "MiddlewareName must not be assignable to String without .value")

  test("HarnessState constructor is private — cannot be called from outside harness package"):
    val errors: String = compileErrors("""
      val s: org.adk4s.harness.HarnessState =
        new org.adk4s.harness.HarnessState(scala.collection.immutable.Map.empty)
    """)
    assert(errors.nonEmpty, "new HarnessState(...) must not compile from outside the harness package")

  test("StateCell constructor is private — cannot be called directly"):
    val errors: String = compileErrors("""
      val id: org.adk4s.harness.StateCell.CellId =
        org.adk4s.harness.StateCell.CellId(org.adk4s.harness.MiddlewareName("x"), "y")
      val cell: org.adk4s.harness.StateCell[Int] =
        new org.adk4s.harness.StateCell[Int](
          id, org.adk4s.harness.CellVisibility.Private, 0,
          (_: Int, b: Int) => b, summon[upickle.default.ReadWriter[Int]]
        )
    """)
    assert(errors.nonEmpty, "new StateCell(...) must not compile — only StateCell.apply is allowed")

  // ── Compile-negative: Iron constraint enforcement ──────────────────────
  // spec: add-iron-refined-types/harness-state — Compile-Negative Obligations

  test("MiddlewareName empty literal does not compile"):
    val errors: String = compileErrors("""val m: org.adk4s.harness.MiddlewareName = org.adk4s.harness.MiddlewareName("")""")
    assert(errors.nonEmpty, "MiddlewareName(\"\") must not compile — empty violates NonEmpty")

  test("CellId empty literal does not compile"):
    val errors: String = compileErrors("""val c: org.adk4s.harness.StateCell.CellId = org.adk4s.harness.StateCell.CellId("")""")
    assert(errors.nonEmpty, "CellId(\"\") must not compile — empty violates NonEmpty")

  test("CellId missing slash does not compile"):
    val errors: String = compileErrors("""val c: org.adk4s.harness.StateCell.CellId = org.adk4s.harness.StateCell.CellId("no-slash")""")
    assert(errors.nonEmpty, "CellId(\"no-slash\") must not compile — missing / violates Match")
