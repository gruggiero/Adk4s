package org.sinemenda.probatio.core

import hedgehog.*

/**
 * Tests for ChainState computation.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: Chain-state computation is referentially transparent
 * spec: port-scanner-to-probatio/probatio-core — Requirement: Undetermined is never collapsed into a finding
 */
final class ChainStateSpec extends ProbatioSuite:

  private def emptyLint: LintReport =
    SpecLintFixtures.report(verdicts = List.empty, warnings = List.empty, applicability = Map.empty, lintSuccess = true)

  private def failedLint: LintReport =
    SpecLintFixtures.report(
      verdicts = List.empty,
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = false
    )

  private def emptyLedger: Ledger.LedgerData = Ledger.fromRecords(List.empty)

  // ── Step-1 contract adapters: the requirements channel is an extracted
  // RequirementSet; lint outcomes are per-spec; forgiveness is off unless a
  // test supplies an oracle.
  private def reqSet(rs: List[ChainState.Requirement]): RequirementSet =
    RequirementSet(rs.map(_.spec).distinct, rs, List.empty, FactSource.Degraded)

  private def reqSetWith(
    rs: List[ChainState.Requirement],
    obls: List[ExtractedObligation]
  ): RequirementSet =
    RequirementSet(rs.map(_.spec).distinct, rs, obls, FactSource.Degraded)

  /**
   * An obligation row claiming `Requirement: <title>` — the mapped row a
   *  ledger record discharges against (obligation text = requirement title,
   *  matching the fixture ledger records).
   */
  private def oblFor(r: ChainState.Requirement): ExtractedObligation =
    ExtractedObligation(
      spec = r.spec,
      line = 10,
      obligation = r.requirement,
      artifact = "a.scala",
      artifacts = List("a.scala"),
      requirementClaims = List(r.requirement),
      unmappable = false
    )

  // A spec that was READ and produced no requirements — its lint outcome
  // is still consulted (distinct from RequirementSet.empty, where no spec
  // was read at all and no lint is needed).
  private def noReqs: RequirementSet =
    RequirementSet(List("s"), List.empty, List.empty, FactSource.Degraded)

  private def okLints(lint: LintReport): Map[String, Outcome[LintReport]] =
    Map("s" -> Outcome.Ran(lint))

  private val failedLints: Map[String, Outcome[LintReport]] =
    Map("s" -> Outcome.Undetermined("spec-lint did not complete successfully"))

  private val noForgive: (String, String) => Boolean = (_, _) => false

  // ── Scenario: same inputs produce same output
  // spec: port-scanner-to-probatio/probatio-core — Scenario: same inputs produce same output
  test("same inputs produce same output"):
    val result1: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(emptyLint), emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    val result2: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(emptyLint), emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    assertEquals(result1, result2)

  // ── Scenario: an unreadable ledger yields undetermined, not zero
  // spec: port-scanner-to-probatio/probatio-core — Scenario: an unreadable ledger yields undetermined, not zero
  test("a failed lint yields undetermined, not zero"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(failedLints, emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    assert(result.isLeft, "Expected Left (undetermined) for a failed lint, got Right")
    result match
      case Left(u)  => assert(u.reason.nonEmpty, "undetermined reason must be non-empty")
      case Right(r) => fail(s"Expected undetermined, got report with discharged=${r.discharged}")

  // ── Scenario: a failed lint yields undetermined, not zero
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a failed lint yields undetermined, not zero
  test("a failed lint yields undetermined with a reason naming the lint failure"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(failedLints, emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Left(u) =>
        assert(
          u.reason.contains("lint") || u.reason.contains("Lint"),
          s"reason should name the lint failure, got: ${u.reason}"
        )
      case Right(_) => fail("Expected undetermined")

  // ── Scenario: a genuinely empty ledger is reported as zero discharged
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a genuinely empty ledger is reported as zero discharged
  test("a genuinely empty ledger with a successful lint is reported as zero discharged"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(emptyLint), emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Right(report) =>
        assertEquals(report.discharged, 0)
        assertEquals(report.total, 0)
      case Left(u) => fail(s"Expected a report (zero discharged), got undetermined: ${u.reason}")

  // ── Scenario: a corrupt ledger yields undetermined, not clean
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a corrupt ledger yields undetermined, not clean
  test("a corrupt ledger yields undetermined, not clean"):
    // A "corrupt" ledger is modeled as one where the read fails. For the
    // typed contract, we model this as a failed lint (the computation
    // cannot proceed without a successful lint). The undetermined result
    // is never collapsed into a clean (Right with discharged=0).
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(failedLints, emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    assert(result.isLeft, "corrupt input must yield Left (undetermined), never Right (clean)")

  // ── Property: Chain-state computation is referentially transparent
  // spec: port-scanner-to-probatio/probatio-core — Property: Chain-state computation is referentially transparent
  property("chain-state computation is referentially transparent"):
    for
      nReqs  <- Gen.int(Range.linear(0, 10)).forAll
      lintOk <- Gen.boolean.forAll
      baseline <- Gen
        .string(
          Gen.element1('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'),
          Range.linear(7, 40)
        )
        .forAll
    yield
      val lint: LintReport =
        if lintOk then emptyLint else failedLint
      val reqs: List[ChainState.Requirement] =
        (1 to nReqs).toList.map(i => ChainState.Requirement(spec = "s", requirement = s"R$i"))
      val result1: Either[ChainStateUndetermined, ChainStateReport] =
        ChainState.compute(okLints(lint), emptyLedger, reqSet(reqs), Map.empty, baseline, baseline, "c", noForgive)
      val result2: Either[ChainStateUndetermined, ChainStateReport] =
        ChainState.compute(okLints(lint), emptyLedger, reqSet(reqs), Map.empty, baseline, baseline, "c", noForgive)
      Result.assert(result1 == result2)

  // ── Mutation-killing: a successful lint with reqs produces a Right report
  test("a successful lint with requirements produces a Right report with correct counts"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1"),
      ChainState.Requirement("s", "R2"),
      ChainState.Requirement("s", "R3")
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(emptyLint), emptyLedger, reqSet(reqs), Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Right(report) =>
        assertEquals(report.total, 3)
        assertEquals(report.discharged, 0)
        assertEquals(report.unresolved.length, 3)
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: discharged count reflects ledger records
  test("discharged count reflects matching ledger records"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1"),
      ChainState.Requirement("s", "R2")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(
        RequirementVerdict("R1", Verdict.Resolved, CheckId.F1),
        RequirementVerdict("R2", Verdict.Resolved, CheckId.F1)
      ),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val record: LedgerRecord = LedgerRecord(
      v = 1,
      ts = "2026-08-08T12:34:56Z",
      change = "c",
      spec = "s",
      ring = Ring.R3,
      obligation = "R1",
      artifact = "a.scala",
      command = "sbt test",
      exit = 0,
      baseline = "abc1234"
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        ledger,
        reqSetWith(reqs, reqs.map(oblFor)),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.discharged, 1)
        assertEquals(report.unresolved.length, 1)
        assertEquals(report.unresolved.headOption.map(_.requirement), Some("R2"))
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Manual ring rows count as evidence — the predecessor's ledger.sh
  // read does not filter by ring (spec 5 corrected the old exclusion).
  test("Manual ring records count as discharged"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Resolved, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val record: LedgerRecord = LedgerRecord(
      v = 1,
      ts = "2026-08-08T12:34:56Z",
      change = "c",
      spec = "s",
      ring = Ring.Manual,
      obligation = "R1",
      artifact = "a.scala",
      command = "sbt test",
      exit = 0,
      baseline = "abc1234"
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        ledger,
        reqSetWith(reqs, reqs.map(oblFor)),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.discharged, 1)
        assertEquals(report.unresolved.length, 0)
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: records with different baseline do NOT count
  test("records with a different baseline do not count as discharged"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val record: LedgerRecord = LedgerRecord(
      v = 1,
      ts = "2026-08-08T12:34:56Z",
      change = "c",
      spec = "s",
      ring = Ring.R3,
      obligation = "R1",
      artifact = "a.scala",
      command = "sbt test",
      exit = 0,
      baseline = "deadbeef"
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Resolved, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        ledger,
        reqSetWith(reqs, reqs.map(oblFor)),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.discharged, 0)
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: records with different change do NOT count
  test("records with a different change do not count as discharged"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val record: LedgerRecord = LedgerRecord(
      v = 1,
      ts = "2026-08-08T12:34:56Z",
      change = "other",
      spec = "s",
      ring = Ring.R3,
      obligation = "R1",
      artifact = "a.scala",
      command = "sbt test",
      exit = 0,
      baseline = "abc1234"
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Resolved, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        ledger,
        reqSetWith(reqs, reqs.map(oblFor)),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.discharged, 0)
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: bound count reflects lint verdicts
  test("bound count reflects Bound and Resolved verdicts from lint"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1"),
      ChainState.Requirement("s", "R2"),
      ChainState.Requirement("s", "R3")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(
        RequirementVerdict("R1", Verdict.Bound, CheckId.F1),
        RequirementVerdict("R2", Verdict.Resolved, CheckId.F2),
        RequirementVerdict("R3", Verdict.Unbound, CheckId.F3)
      ),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    // R2's obligation makes it resolvable (no rows → undischarged, which
    // counts as resolved); R1 is bound but has no attributable row —
    // unattributable, which does not.
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        emptyLedger,
        reqSetWith(reqs, List(oblFor(reqs(1)))),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.bound, 2)
        assertEquals(report.resolved, 1)
        assertEquals(report.discharged, 0)
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: failed lint reason contains "lint"
  test("failed lint reason is non-empty and contains the word lint"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(failedLints, emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Left(u) =>
        assert(u.reason.nonEmpty, "reason must be non-empty")
        assert(u.reason.contains("lint"), s"reason must contain 'lint', got: ${u.reason}")
      case Right(r) => fail(s"Expected Left, got Right with discharged=${r.discharged}")

  // ── Mutation-killing: unresolved reason is Unbound when verdict is empty
  test("unresolved reason is Unbound when no lint verdict exists"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(emptyLint), emptyLedger, reqSet(reqs), Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Right(report) =>
        assertEquals(report.unresolved.length, 1)
        assertEquals(report.unresolved.headOption.map(_.reasons), Some(List(UnresolvedReason.Unbound)))
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: unresolved reason is Unbound when verdict is Unbound
  test("unresolved reason is Unbound when verdict is Unbound"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Unbound, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(lint), emptyLedger, reqSet(reqs), Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Right(report) =>
        assertEquals(report.unresolved.length, 1)
        assertEquals(report.unresolved.headOption.map(_.reasons), Some(List(UnresolvedReason.Unbound)))
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Bound with no attributable obligation row is Unattributable — the
  // degraded-mode F1 fix: spec-lint's loose binding says bound, but no
  // exact-title row exists to attribute evidence to.
  test("unresolved reason is Unattributable when verdict is Bound with no attributable row"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Bound, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(lint), emptyLedger, reqSet(reqs), Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Right(report) =>
        assertEquals(report.unresolved.length, 1)
        assertEquals(report.unresolved.headOption.map(_.reasons), Some(List(UnresolvedReason.Unattributable)))
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: unresolved reason is Undischarged when verdict is Resolved but no ledger record
  test("unresolved reason is Undischarged when verdict is Resolved but not discharged"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Resolved, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        emptyLedger,
        reqSetWith(reqs, reqs.map(oblFor)),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.unresolved.length, 1)
        assertEquals(report.unresolved.headOption.map(_.reasons), Some(List(UnresolvedReason.Undischarged)))
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: resolved requirement with ledger record is discharged (no unresolved)
  test("resolved requirement with matching ledger record is discharged and not unresolved"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Resolved, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val record: LedgerRecord = LedgerRecord(
      v = 1,
      ts = "2026-08-08T12:34:56Z",
      change = "c",
      spec = "s",
      ring = Ring.R3,
      obligation = "R1",
      artifact = "a.scala",
      command = "sbt test",
      exit = 0,
      baseline = "abc1234"
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        ledger,
        reqSetWith(reqs, reqs.map(oblFor)),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.discharged, 1)
        assertEquals(report.resolved, 1)
        assertEquals(report.bound, 1)
        assertEquals(report.unresolved.length, 0)
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: bound count distinguishes Bound from Resolved
  test("bound count includes both Bound and Resolved, resolved count includes only Resolved"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1"),
      ChainState.Requirement("s", "R2"),
      ChainState.Requirement("s", "R3")
    )
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(
        RequirementVerdict("R1", Verdict.Bound, CheckId.F1),
        RequirementVerdict("R2", Verdict.Resolved, CheckId.F2),
        RequirementVerdict("R3", Verdict.Unbound, CheckId.F3)
      ),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        okLints(lint),
        emptyLedger,
        reqSetWith(reqs, List(oblFor(reqs(1)))),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.bound, 2, "bound should count Bound + Resolved = 2")
        assertEquals(report.resolved, 1, "resolved should count only Resolved = 1")
      case Left(u) => fail(s"Expected Right, got Left: ${u.reason}")

  // ── Mutation-killing: failed lint reason contains the exact failure text
  test("failed lint reason contains 'spec-lint did not complete successfully'"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(failedLints, emptyLedger, noReqs, Map.empty, "abc1234", "abc1234", "c", noForgive)
    result match
      case Left(u) =>
        assert(
          u.reason.contains("spec-lint did not complete successfully"),
          s"reason must contain exact text, got: ${u.reason}"
        )
      case Right(r) => fail(s"Expected Left, got Right with discharged=${r.discharged}")

  // ── Mutation-killing: successful lint produces Right, not Left
  test("successful lint with empty ledger and reqs produces Right, not Left"):
    val reqs: List[ChainState.Requirement] = List(ChainState.Requirement("s", "R1"))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(okLints(emptyLint), emptyLedger, reqSet(reqs), Map.empty, "abc1234", "abc1234", "c", noForgive)
    assert(result.isRight, "successful lint must produce Right, not Left")
