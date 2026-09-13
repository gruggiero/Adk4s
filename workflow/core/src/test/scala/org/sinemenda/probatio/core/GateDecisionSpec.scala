package org.sinemenda.probatio.core

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range

/**
 * Test oracle for the gate-checkpoint-lock spec (spec 9).
 *
 * Properties and scenarios are derived from the SPEC, not from the
 * implementation. The typed contract (GateDecisionContract.scala) provides
 * the shapes; this file exercises them against the behavioural contract.
 *
 * spec: port-scanner-to-probatio/gate-checkpoint-lock — Properties (Ring 3)
 * spec: port-scanner-to-probatio/gate-checkpoint-lock — Compile-Negative Obligations
 */
final class GateDecisionSpec extends ProbatioSuite:

  // ── Generators ──────────────────────────────────────────────────────────

  /** The escape hatch variable name, for render-string assertions. */
  private val escapeHatchVar: String = "PROBATIO_HOOKS"

  /**
   * genSpecPhase — constructive over the three phases.
   */
  private val genSpecPhase: Gen[SpecPhase] =
    Gen.element1(SpecPhase.Oracle, SpecPhase.Implementation, SpecPhase.Verified)

  /**
   * genBoolean — a plain boolean generator.
   */
  private val genBoolean: Gen[Boolean] =
    Gen.element1(true, false)

  /**
   * genPredecessorState — constructive over
   * phase × presentation-exists × escape-hatch.
   * Pairs each state with its expected decision (Right = allow, Left = block).
   */
  private val genPredecessorState: Gen[(SpecPhase, Boolean, Boolean, Boolean)] =
    for
      phase           <- genSpecPhase
      hasPresentation <- genBoolean
      escapeHatch     <- genBoolean
    yield
      val passes: Boolean = escapeHatch || (phase == SpecPhase.Verified && hasPresentation)
      (phase, hasPresentation, escapeHatch, passes)

  /**
   * genGrantState — constructive over
   * phase × presentation-exists × grant-exists × escape-hatch.
   * Pairs each state with its expected decision.
   */
  private val genGrantState: Gen[(SpecPhase, Boolean, Boolean, Boolean, Boolean)] =
    for
      phase           <- genSpecPhase
      hasPresentation <- genBoolean
      hasGrant        <- genBoolean
      escapeHatch     <- genBoolean
    yield
      val passes: Boolean = escapeHatch || hasGrant || (phase == SpecPhase.Verified && hasPresentation)
      (phase, hasPresentation, hasGrant, escapeHatch, passes)

  /**
   * genSpecName — generates a short spec name for block reasons.
   */
  private val genSpecName: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(3, 8)).map("spec-" + _)

  /**
   * genPredecessorSpecList — generates a list of (name, phase, hasPresentation)
   * for the predecessor check, with at least one spec.
   */
  private def genPredecessorSpecList: Gen[List[(String, SpecPhase, Boolean)]] =
    for
      n      <- Gen.int(Range.linear(1, 5))
      names  <- genSpecName.list(Range.singleton(n))
      phases <- genSpecPhase.list(Range.singleton(n))
      pres   <- genBoolean.list(Range.singleton(n))
    yield names.zip(phases).zip(pres).map { case ((nm, ph), pr) => (nm, ph, pr) }

  /**
   * genGrantSpecList — generates a list of (name, phase, hasPresentation, hasGrant).
   */
  private def genGrantSpecList: Gen[List[(String, SpecPhase, Boolean, Boolean)]] =
    for
      n      <- Gen.int(Range.linear(1, 5))
      names  <- genSpecName.list(Range.singleton(n))
      phases <- genSpecPhase.list(Range.singleton(n))
      pres   <- genBoolean.list(Range.singleton(n))
      grants <- genBoolean.list(Range.singleton(n))
    yield names.zip(phases).zip(pres).zip(grants).map { case (((nm, ph), pr), gr) => (nm, ph, pr, gr) }

  /**
   * genBlockReasonState — constructive over phase × presentation-exists,
   * filtered to blocking configurations only (phase != Verified OR no presentation).
   */
  private val genBlockReasonState: Gen[(SpecPhase, Boolean)] =
    for
      phase           <- genSpecPhase
      hasPresentation <- genBoolean
    yield (phase, hasPresentation)

  // ── Property: predecessor-check-requires-presentation ───────────────────

  // spec: gate-checkpoint-lock — Property: predecessor-check-requires-presentation
  property("predecessor-check-requires-presentation"):
    for (phase, hasPresentation, escapeHatch, expectedPass) <- genPredecessorState.forAll
    yield
      val specs: List[PredecessorCheck.SpecState] = List(("specN", phase, hasPresentation))
      val decision: Either[BlockReason, Unit]     = PredecessorCheck(specs, escapeHatch)
      if escapeHatch then Result.assert(decision.isRight)
      else if expectedPass then Result.assert(decision.isRight)
      else Result.assert(decision.isLeft)

  // ── Property: grant-waiver-requires-presentation ────────────────────────

  // spec: gate-checkpoint-lock — Property: grant-waiver-requires-presentation
  property("grant-waiver-requires-presentation"):
    for (phase, hasPresentation, hasGrant, escapeHatch, expectedPass) <- genGrantState.forAll
    yield
      val specs: List[GrantWaiver.SpecState]  = List(("specN", phase, hasPresentation, hasGrant))
      val decision: Either[BlockReason, Unit] = GrantWaiver(specs, escapeHatch)
      if escapeHatch then Result.assert(decision.isRight)
      else if hasGrant then Result.assert(decision.isRight)
      else if expectedPass then Result.assert(decision.isRight)
      else Result.assert(decision.isLeft)

  // ── Property: block-reason-distinguishes-not-checkpointed ───────────────

  // spec: gate-checkpoint-lock — Property: block-reason-distinguishes-not-checkpointed
  property("block-reason-distinguishes-not-checkpointed"):
    for (phase, hasPresentation) <- genBlockReasonState.forAll
    yield
      val specs: List[PredecessorCheck.SpecState] = List(("specN", phase, hasPresentation))
      val decision: Either[BlockReason, Unit]     = PredecessorCheck(specs, escapeHatch = false)
      decision match
        case Right(_) =>
          Result.success
        case Left(reason) =>
          if phase == SpecPhase.Verified && !hasPresentation then
            reason match
              case _: BlockReason.PredecessorNotCheckpointed =>
                Result
                  .assert(reason.render.contains("not checkpointed"))
                  .and(Result.assert(reason.render.contains("checkpoint")))
                  .and(Result.assert(reason.render.contains(escapeHatchVar)))
              case _ =>
                Result.failure
          else if phase != SpecPhase.Verified then
            reason match
              case _: BlockReason.PredecessorNotVerified =>
                Result
                  .assert(!reason.render.contains("not checkpointed"))
                  .and(Result.assert(reason.render.contains(escapeHatchVar)))
              case _ =>
                Result.failure
          else Result.success

  // ── Scenario tests: predecessor check ───────────────────────────────────

  // spec: gate-checkpoint-lock — Scenario: verified predecessor with no presentation is blocked
  test("verified predecessor with no presentation is blocked"):
    val specs: List[PredecessorCheck.SpecState] = List(("specN", SpecPhase.Verified, false))
    val decision: Either[BlockReason, Unit]     = PredecessorCheck(specs, escapeHatch = false)
    assert(decision.isLeft, "must block")
    decision.left.toOption match
      case Some(BlockReason.PredecessorNotCheckpointed(spec)) =>
        assert(spec == "specN", "must name the spec")
      case other => fail(s"expected PredecessorNotCheckpointed, got $other")

  // spec: gate-checkpoint-lock — Scenario: verified predecessor with a presentation is allowed
  test("verified predecessor with a presentation is allowed"):
    val specs: List[PredecessorCheck.SpecState] = List(("specN", SpecPhase.Verified, true))
    val decision: Either[BlockReason, Unit]     = PredecessorCheck(specs, escapeHatch = false)
    assert(decision.isRight, "must allow")

  // spec: gate-checkpoint-lock — Scenario: non-verified predecessor is blocked regardless of presentation
  test("non-verified predecessor is blocked regardless of presentation"):
    val specs: List[PredecessorCheck.SpecState] = List(("specN", SpecPhase.Implementation, true))
    val decision: Either[BlockReason, Unit]     = PredecessorCheck(specs, escapeHatch = false)
    assert(decision.isLeft, "must block")
    decision.left.toOption match
      case Some(BlockReason.PredecessorNotVerified(spec, phase)) =>
        assert(spec == "specN", "must name the spec")
        assert(phase == SpecPhase.Implementation, "must retain the phase")
      case other => fail(s"expected PredecessorNotVerified, got $other")

  // spec: gate-checkpoint-lock — Scenario: escape hatch bypasses both checks
  test("escape hatch bypasses predecessor check"):
    val specs: List[PredecessorCheck.SpecState] = List(("specN", SpecPhase.Verified, false))
    val decision: Either[BlockReason, Unit]     = PredecessorCheck(specs, escapeHatch = true)
    assert(decision.isRight, "escape hatch must allow")

  // spec: gate-checkpoint-lock — Scenario: no state directory means fail open
  test("empty predecessor list fails open (allow)"):
    val specs: List[PredecessorCheck.SpecState] = List.empty
    val decision: Either[BlockReason, Unit]     = PredecessorCheck(specs, escapeHatch = false)
    assert(decision.isRight, "empty list must allow (fail open)")

  // ── Scenario tests: grant waiver ────────────────────────────────────────

  // spec: gate-checkpoint-lock — Scenario: verified with presentation waives grant
  test("verified with presentation waives grant"):
    val specs: List[GrantWaiver.SpecState]  = List(("specN", SpecPhase.Verified, true, false))
    val decision: Either[BlockReason, Unit] = GrantWaiver(specs, escapeHatch = false)
    assert(decision.isRight, "must waive grant")

  // spec: gate-checkpoint-lock — Scenario: verified without presentation does not waive grant
  test("verified without presentation does not waive grant"):
    val specs: List[GrantWaiver.SpecState]  = List(("specN", SpecPhase.Verified, false, false))
    val decision: Either[BlockReason, Unit] = GrantWaiver(specs, escapeHatch = false)
    assert(decision.isLeft, "must not waive grant")
    decision.left.toOption match
      case Some(BlockReason.GrantRequired(spec)) =>
        assert(spec == "specN", "must name the spec")
      case other => fail(s"expected GrantRequired, got $other")

  // spec: gate-checkpoint-lock — Scenario: verified with grant from current session is allowed
  test("verified with grant from current session is allowed"):
    val specs: List[GrantWaiver.SpecState]  = List(("specN", SpecPhase.Verified, false, true))
    val decision: Either[BlockReason, Unit] = GrantWaiver(specs, escapeHatch = false)
    assert(decision.isRight, "grant token must allow directly")

  // ── Scenario tests: block reason rendering ──────────────────────────────

  // spec: gate-checkpoint-lock — Scenario: not-checkpointed reason names checkpoint
  test("not-checkpointed reason names checkpoint in render"):
    val reason: BlockReason = BlockReason.PredecessorNotCheckpointed("specN")
    val rendered: String    = reason.render
    assert(rendered.contains("not checkpointed"), "must contain 'not checkpointed'")
    assert(rendered.contains("checkpoint"), "must contain 'checkpoint'")

  // spec: gate-checkpoint-lock — Scenario: not-verified reason does not name checkpoint
  test("not-verified reason does not name checkpoint in render"):
    val reason: BlockReason = BlockReason.PredecessorNotVerified("specN", SpecPhase.Oracle)
    val rendered: String    = reason.render
    assert(rendered.contains("Oracle"), "must contain the phase name")
    assert(!rendered.contains("not checkpointed"), "must NOT contain 'not checkpointed'")

  // spec: gate-checkpoint-lock — Scenario: both reasons name the escape hatch
  test("not-checkpointed reason names escape hatch in render"):
    val reason: BlockReason = BlockReason.PredecessorNotCheckpointed("specN")
    assert(reason.render.contains(escapeHatchVar), "must contain escape hatch var name")

  test("not-verified reason names escape hatch in render"):
    val reason: BlockReason = BlockReason.PredecessorNotVerified("specN", SpecPhase.Oracle)
    assert(reason.render.contains(escapeHatchVar), "must contain escape hatch var name")

  // ── Compile-Negative: sealed enums and traits ───────────────────────────

  // spec: gate-checkpoint-lock — Compile-Negative: GateEvent sealed enum
  test("GateEvent is sealed — no sixth case constructible"):
    val err: String = compileErrors(
      "val e: GateEvent = new GateEvent { def ordinal = 99 }"
    )
    assert(err.nonEmpty, "GateEvent must be sealed — no new cases constructible")

  // spec: gate-checkpoint-lock — Compile-Negative: BlockReason sealed trait
  test("BlockReason is sealed — no fifth variant constructible"):
    val err: String = compileErrors(
      "val r: BlockReason = new BlockReason { def render = \"\" }"
    )
    assert(err.nonEmpty, "BlockReason must be sealed — no new variants constructible")

  // spec: gate-checkpoint-lock — Compile-Negative: SpecPhase sealed enum
  test("SpecPhase is sealed — no fourth case constructible"):
    val err: String = compileErrors(
      "val p: SpecPhase = new SpecPhase { def ordinal = 99 }"
    )
    assert(err.nonEmpty, "SpecPhase must be sealed — no new cases constructible")

  // spec: gate-checkpoint-lock — Compile-Negative: Block requires a BlockReason
  test("Block requires a BlockReason — cannot construct without reason"):
    val err: String = compileErrors(
      "val d: GateDecision = GateDecision.Block"
    )
    assert(err.nonEmpty, "Block must require a BlockReason — not a case object")

  // ── Property: predecessor check with multi-spec lists ───────────────────

  property("predecessor check with multi-spec list blocks on first failure"):
    for specs <- genPredecessorSpecList.forAll
    yield
      val escapeHatch: Boolean                = false
      val decision: Either[BlockReason, Unit] = PredecessorCheck(specs, escapeHatch)
      val allPass: Boolean = specs.forall { case (_, phase, pres) =>
        phase == SpecPhase.Verified && pres
      }
      if allPass then Result.assert(decision.isRight)
      else Result.assert(decision.isLeft)

  // spec: gate-checkpoint-lock — R8 fix: verify BlockReason is for the FIRST failing spec
  property("predecessor check returns BlockReason for first failing spec"):
    for specs <- genPredecessorSpecList.forAll
    yield
      val escapeHatch: Boolean                = false
      val decision: Either[BlockReason, Unit] = PredecessorCheck(specs, escapeHatch)
      val firstFailing: Option[(String, SpecPhase, Boolean)] =
        specs.find { case (_, phase, pres) => !(phase == SpecPhase.Verified && pres) }
      (firstFailing, decision) match
        case (None, Right(_)) =>
          Result.success
        case (Some((name, phase, pres)), Left(reason)) =>
          val expectedReason: BlockReason =
            if phase == SpecPhase.Verified && !pres then BlockReason.PredecessorNotCheckpointed(name)
            else BlockReason.PredecessorNotVerified(name, phase)
          Result.assert(reason == expectedReason)
        case _ =>
          Result.failure

  // ── Property: grant waiver with multi-spec lists ────────────────────────

  property("grant waiver with multi-spec list blocks on first failure"):
    for specs <- genGrantSpecList.forAll
    yield
      val escapeHatch: Boolean                = false
      val decision: Either[BlockReason, Unit] = GrantWaiver(specs, escapeHatch)
      val allPass: Boolean = specs.forall { case (_, phase, pres, grant) =>
        (phase == SpecPhase.Verified && pres) || grant
      }
      if allPass then Result.assert(decision.isRight)
      else Result.assert(decision.isLeft)

  // spec: gate-checkpoint-lock — R8 fix: verify GrantRequired is for the FIRST failing spec
  property("grant waiver returns GrantRequired for first failing spec"):
    for specs <- genGrantSpecList.forAll
    yield
      val escapeHatch: Boolean                = false
      val decision: Either[BlockReason, Unit] = GrantWaiver(specs, escapeHatch)
      val firstFailing: Option[(String, SpecPhase, Boolean, Boolean)] =
        specs.find { case (_, phase, pres, grant) => !((phase == SpecPhase.Verified && pres) || grant) }
      (firstFailing, decision) match
        case (None, Right(_)) =>
          Result.success
        case (Some((name, _, _, _)), Left(reason)) =>
          Result.assert(reason == BlockReason.GrantRequired(name))
        case _ =>
          Result.failure
