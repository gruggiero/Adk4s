package org.adk4s.harness.testkit

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.Ref
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.error.StateDecodeError
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.core.json.JsonValue
import org.adk4s.harness.{
  AgentMiddleware,
  CellVisibility,
  HarnessState,
  MiddlewareName,
  MiddlewareStack,
  ModelRequest,
  ModelResponse,
  ModelStep,
  StateCell,
  SystemPrompt
}
import org.llm4s.llmconnect.model.{ CompletionOptions, Message, UserMessage }
import smithy4s.Document

/**
 * Adversarial / negative scenario tests for the middleware laws.
 *
 * Each test confirms that a law is sensitive to its preconditions —
 * violating a precondition produces an observable failure or
 * non-equivalence, so the law is not trivially true. These are
 * scenario tests (not properties), driven by concrete examples from
 * the spec.
 *
 * spec: middleware-laws — adversarial scenarios
 */
class AdversarialScenariosSpec extends HedgehogSuite:

  // ── L4: non-default middleware is NOT neutral ───────────────────────────────

  test("L4-adversarial — non-default beforeAgent is NOT neutral"):
    val owner: MiddlewareName      = MiddlewareName("adv")
    val targetCell: StateCell[Int] = StateCell[Int](owner, "target", 0)
    // A middleware with NO declared cells but a beforeAgent that writes to
    // another middleware's cell — this violates the default-neutrality
    // precondition.
    val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName = MiddlewareName("adv-mw")
      override def beforeAgent(state: HarnessState): IO[HarnessState] =
        IO.pure(state.set(targetCell)(42))
    val state: HarnessState  = HarnessState.initial(List(targetCell))
    val result: HarnessState = mw.beforeAgent(state).unsafeRunSync()
    assert(result.get(targetCell) == 42)
    assert(result.get(targetCell) != state.get(targetCell))

  // ── L5: cross-cell write is detected ────────────────────────────────────────

  test("L5-adversarial — cross-cell write is detected"):
    val owner: MiddlewareName = MiddlewareName("adv")
    val cellZ: StateCell[Int] = StateCell[Int](owner, "z", 0)
    // A middleware with NO declared cells but a beforeAgent that writes to
    // cell `z` it does not declare — the frame rule is VIOLATED.
    val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName = MiddlewareName("adv-mw")
      override def beforeAgent(state: HarnessState): IO[HarnessState] =
        IO.pure(state.set(cellZ)(99))
    val state: HarnessState  = HarnessState.initial(List(cellZ)).set(cellZ)(5)
    val result: HarnessState = mw.beforeAgent(state).unsafeRunSync()
    // The cross-cell write happened — the frame rule is violated.
    assert(result.get(cellZ) == 99)
    assert(result.get(cellZ) != state.get(cellZ))

  // ── L6: overlapping cells do NOT commute ────────────────────────────────────

  test("L6-adversarial — overlapping cells break commutativity"):
    val owner: MiddlewareName = MiddlewareName("adv")
    val cellA: StateCell[Int] = StateCell[Int](owner, "a", 0)
    val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName                    = MiddlewareName("m1")
      override val stateCells: List[StateCell[?]] = List(cellA)
      override def beforeAgent(state: HarnessState): IO[HarnessState] =
        IO.pure(state.set(cellA)(1))
    val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName                    = MiddlewareName("m2")
      override val stateCells: List[StateCell[?]] = List(cellA)
      override def beforeAgent(state: HarnessState): IO[HarnessState] =
        IO.pure(state.set(cellA)(2))
    val state: HarnessState = HarnessState.initial(List(cellA))
    val r1: HarnessState    = m1.beforeAgent(m2.beforeAgent(state).unsafeRunSync()).unsafeRunSync()
    val r2: HarnessState    = m2.beforeAgent(m1.beforeAgent(state).unsafeRunSync()).unsafeRunSync()
    // m1 then m2 → a=2; m2 then m1 → a=1 — overlapping cells are NOT commutative.
    assert(r1.get(cellA) == 2)
    assert(r2.get(cellA) == 1)
    assert(r1.get(cellA) != r2.get(cellA))

  // ── L6: request rewriters do NOT commute ────────────────────────────────────

  test("L6-adversarial — request rewriters break commutativity"):
    val m1: AgentMiddleware[IO] = new Generators.PromptRewriteMiddleware("m1", "A", true)
    val m2: AgentMiddleware[IO] = new Generators.PromptRewriteMiddleware("m2", "B", true)
    val baseReq: ModelRequest[IO] = ModelRequest[IO](
      systemPrompt = Some(SystemPrompt(Some("base"), Nil)),
      messages = Nil,
      tools = Nil,
      options = CompletionOptions(),
      state = HarnessState.empty
    )
    // Capture the system prompt that reaches the innermost step.
    def captureStep(ref: Ref[IO, Option[String]]): ModelStep[IO] =
      Kleisli((req: ModelRequest[IO]) =>
        ref.set(req.systemPrompt.map(_.render)) *>
          IO.pure(ModelResponse(DeterministicChatModel.fallbackCompletion(0L, 0), req.state))
      )
    val program: IO[(Option[String], Option[String])] =
      for
        ref12 <- Ref.of[IO, Option[String]](None)
        ref21 <- Ref.of[IO, Option[String]](None)
        _     <- m1.wrapModelCall(m2.wrapModelCall(captureStep(ref12))).run(baseReq)
        _     <- m2.wrapModelCall(m1.wrapModelCall(captureStep(ref21))).run(baseReq)
        r12   <- ref12.get
        r21   <- ref21.get
      yield (r12, r21)
    val (r12, r21): (Option[String], Option[String]) = program.unsafeRunSync()
    // [m1, m2] → m1 wraps outermost → m2's prefix applied first → "ABbase"
    // [m2, m1] → m2 wraps outermost → m1's prefix applied first → "BAbase"
    assert(r12.contains("ABbase"))
    assert(r21.contains("BAbase"))
    assert(r12 != r21)

  // ── L8: corrupted cell value is a hard error ────────────────────────────────

  test("L8-adversarial — corrupted cell is a hard Left"):
    val owner: MiddlewareName   = MiddlewareName("adv")
    val intCell: StateCell[Int] = StateCell[Int](owner, "x", 0)
    // A snapshot where "adv/x" contains a JSON string but x is Int.
    val corruptedSnapshot: JsonValue =
      Document.DObject(Map("adv/x" -> Document.DString("not-an-int")))
    val result: Either[StateDecodeError, HarnessState] =
      HarnessState.restore(List(intCell), corruptedSnapshot)
    assert(result.isLeft)

  // ── L9: child writes to Private cell are unobservable ───────────────────────

  test("L9-adversarial — child Private writes are unobservable"):
    val owner: MiddlewareName = MiddlewareName("adv")
    val privateCell: StateCell[Int] =
      StateCell[Int](owner, "p", 0, visibility = CellVisibility.Private)
    val parent: HarnessState = HarnessState.initial(List(privateCell)).set(privateCell)(99)
    // Child aggressively writes p = 999.
    val child: HarnessState  = HarnessState.initial(List(privateCell)).set(privateCell)(999)
    val merged: HarnessState = HarnessState.mergeBack(parent, List(child), List(privateCell))
    // The parent's Private value is untouched.
    assert(merged.get(privateCell) == 99)

  // ── L10: non-idempotent merge breaks neutrality ─────────────────────────────

  test("L10-adversarial — non-idempotent merge breaks neutrality"):
    val owner: MiddlewareName = MiddlewareName("adv")
    // A Shared cell with a NON-idempotent merge: (a, b) => a + b
    val cellS: StateCell[Int] =
      StateCell[Int](owner, "s", 0, visibility = CellVisibility.Shared, merge = (a: Int, b: Int) => a + b)
    val parent: HarnessState = HarnessState.initial(List(cellS)).set(cellS)(5)
    // Child is projected (sees s = 5), modifies nothing.
    val child: HarnessState  = HarnessState.project(parent, List(cellS))
    val merged: HarnessState = HarnessState.mergeBack(parent, List(child), List(cellS))
    // merge(5, 5) = 10 — the parent is NOT unchanged.
    assert(merged.get(cellS) == 10)
    assert(merged.get(cellS) != parent.get(cellS))

  // ── L11: non-commutative merge fails the semilattice laws ───────────────────

  test("L11-adversarial — non-commutative merge fails commutativity"):
    val owner: MiddlewareName = MiddlewareName("adv")
    // A Shared cell with a NON-commutative merge: list concatenation.
    val cellL: StateCell[List[Int]] =
      StateCell[List[Int]](
        owner,
        "l",
        Nil,
        visibility = CellVisibility.Shared,
        merge = (a: List[Int], b: List[Int]) => a ++ b
      )
    val a: List[Int]  = List(1)
    val b: List[Int]  = List(2)
    val ab: List[Int] = cellL.merge(a, b)
    val ba: List[Int] = cellL.merge(b, a)
    // merge(a, b) == List(1, 2) but merge(b, a) == List(2, 1) — NOT commutative.
    assert(ab == List(1, 2))
    assert(ba == List(2, 1))
    assert(ab != ba)

  // ── L0: interrupt equivalence under TestControl (deterministic concurrency) ──

  test("L0-TestControl — interrupt equivalence under deterministic execution"):
    // L0 interrupt scenario: the loop with an empty middleware stack and the
    // baseline loop MUST produce equal observations even under interrupt,
    // driven deterministically via TestControl.
    val laws: AgentMiddlewareLaws = new AgentMiddlewareLaws(seed = 42L, basePrompt = Some("base"))
    val signal: InterruptSignal   = InterruptSignal.simple("sig")
    val (_, loop1, _) = laws
      .loopFor(
        Generators.ToolBehavior.InterruptOnFirst,
        signal
      )
      .unsafeRunSync()
    val (_, loop2, _) = laws
      .loopFor(
        Generators.ToolBehavior.InterruptOnFirst,
        signal
      )
      .unsafeRunSync()
    val conversation: List[Message] =
      List(UserMessage("test"))
    val maxSteps: Int = 5
    val program: IO[(Observation, Observation)] =
      for
        r1 <- loop1.run(MiddlewareStack.empty[IO], conversation, maxSteps)
        r2 <- loop2.runBaseline(conversation, maxSteps)
      yield (r1, r2)
    // TestControl ensures deterministic execution — no real time, no async race.
    val result: (Observation, Observation) =
      TestControl.executeEmbed(program).unsafeRunSync()
    assert(result._1.eqStrict(result._2))

  // ── L11: mergeBack order-independence under TestControl ─────────────────────

  test("L11-TestControl — mergeBack order-independence under deterministic execution"):
    // L11 mergeBack order-independence: merging children in any order produces
    // an equal state, driven deterministically via TestControl.
    val owner: MiddlewareName = MiddlewareName("adv")
    val cellS: StateCell[Set[Int]] =
      StateCell[Set[Int]](
        owner,
        "s",
        Set.empty,
        visibility = CellVisibility.Shared,
        merge = (a: Set[Int], b: Set[Int]) => a.union(b)
      )
    val parent: HarnessState         = HarnessState.initial(List(cellS)).set(cellS)(Set(0))
    val child1: HarnessState         = HarnessState.initial(List(cellS)).set(cellS)(Set(1))
    val child2: HarnessState         = HarnessState.initial(List(cellS)).set(cellS)(Set(2))
    val child3: HarnessState         = HarnessState.initial(List(cellS)).set(cellS)(Set(3))
    val children: List[HarnessState] = List(child1, child2, child3)
    val permuted: List[HarnessState] = List(child3, child1, child2)
    val program: IO[(HarnessState, HarnessState)] =
      IO.pure(
        (
          HarnessState.mergeBack(parent, children, List(cellS)),
          HarnessState.mergeBack(parent, permuted, List(cellS))
        )
      )
    val result: (HarnessState, HarnessState) =
      TestControl.executeEmbed(program).unsafeRunSync()
    assert(result._1.get(cellS) == Set(0, 1, 2, 3))
    assert(result._2.get(cellS) == Set(0, 1, 2, 3))
    assert(result._1.get(cellS) == result._2.get(cellS))
