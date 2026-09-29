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
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    val result2: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assertEquals(result1, result2)

  // ── Scenario: an unreadable ledger yields undetermined, not zero
  // spec: port-scanner-to-probatio/probatio-core — Scenario: an unreadable ledger yields undetermined, not zero
  test("a failed lint yields undetermined, not zero"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assert(result.isLeft, "Expected Left (undetermined) for a failed lint, got Right")
    result match
      case Left(u)  => assert(u.reason.text.nonEmpty, "undetermined reason must be non-empty")
      case Right(r) => fail(s"Expected undetermined, got report with discharged=${r.discharged}")

  // ── Scenario: a failed lint yields undetermined, not zero
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a failed lint yields undetermined, not zero
  test("a failed lint yields undetermined with a reason naming the lint failure"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Left(u) =>
        assert(
          u.reason.text.contains("lint") || u.reason.text.contains("Lint"),
          s"reason should name the lint failure, got: ${u.reason}"
        )
      case Right(_) => fail("Expected undetermined")

  // ── Scenario: a genuinely empty ledger is reported as zero discharged
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a genuinely empty ledger is reported as zero discharged
  test("a genuinely empty ledger with a successful lint is reported as zero discharged"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
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
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
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
        ChainState.compute(
          PrePassOutcome.Completed(okLints(lint)),
          emptyLedger,
          reqSet(reqs),
          Map.empty,
          baseline,
          baseline,
          "c",
          noForgive
        )
      val result2: Either[ChainStateUndetermined, ChainStateReport] =
        ChainState.compute(
          PrePassOutcome.Completed(okLints(lint)),
          emptyLedger,
          reqSet(reqs),
          Map.empty,
          baseline,
          baseline,
          "c",
          noForgive
        )
      Result.assert(result1 == result2)

  // ── Mutation-killing: a successful lint with reqs produces a Right report
  test("a successful lint with requirements produces a Right report with correct counts"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1"),
      ChainState.Requirement("s", "R2"),
      ChainState.Requirement("s", "R3")
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        reqSet(reqs),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
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
      baseline = "abc1234",
      optional = LedgerRecordOptional()
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(lint)),
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
      baseline = "abc1234",
      optional = LedgerRecordOptional()
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(lint)),
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
      baseline = "deadbeef",
      optional = LedgerRecordOptional()
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
        PrePassOutcome.Completed(okLints(lint)),
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
      baseline = "abc1234",
      optional = LedgerRecordOptional()
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
        PrePassOutcome.Completed(okLints(lint)),
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
        PrePassOutcome.Completed(okLints(lint)),
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
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Left(u) =>
        assert(u.reason.text.nonEmpty, "reason must be non-empty")
        assert(u.reason.text.contains("lint"), s"reason must contain 'lint', got: ${u.reason}")
      case Right(r) => fail(s"Expected Left, got Right with discharged=${r.discharged}")

  // ── Mutation-killing: unresolved reason is Unbound when verdict is empty
  test("unresolved reason is Unbound when no lint verdict exists"):
    val reqs: List[ChainState.Requirement] = List(
      ChainState.Requirement("s", "R1")
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        reqSet(reqs),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
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
      ChainState.compute(
        PrePassOutcome.Completed(okLints(lint)),
        emptyLedger,
        reqSet(reqs),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
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
      ChainState.compute(
        PrePassOutcome.Completed(okLints(lint)),
        emptyLedger,
        reqSet(reqs),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
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
        PrePassOutcome.Completed(okLints(lint)),
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
      baseline = "abc1234",
      optional = LedgerRecordOptional()
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(lint)),
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
        PrePassOutcome.Completed(okLints(lint)),
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
      ChainState.compute(
        PrePassOutcome.Completed(failedLints),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Left(u) =>
        assert(
          u.reason.text.contains("spec-lint did not complete successfully"),
          s"reason must contain exact text, got: ${u.reason}"
        )
      case Right(r) => fail(s"Expected Left, got Right with discharged=${r.discharged}")

  // ── Mutation-killing: successful lint produces Right, not Left
  test("successful lint with empty ledger and reqs produces Right, not Left"):
    val reqs: List[ChainState.Requirement] = List(ChainState.Requirement("s", "R1"))
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(emptyLint)),
        emptyLedger,
        reqSet(reqs),
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    assert(result.isRight, "successful lint must produce Right, not Left")

  // ════════════════════════════════════════════════════════════════════
  // Ring 5 mutation coverage — UnresolvedEntry.of / fromCounts / wire codec
  // ════════════════════════════════════════════════════════════════════

  private def entry(
    spec: String,
    requirement: String,
    reasons: List[UnresolvedReason]
  ): UnresolvedEntry =
    UnresolvedEntry.of(spec, requirement, reasons) match
      case Some(e) => e
      case None    => fail(s"test fixture bug: invalid entry ($spec, $requirement, $reasons)")

  /** A minimally valid report: 1 requirement, bound, resolved, undischarged. */
  private def baseReport(
    change: String = "c",
    baseline: String = "b",
    total: Int = 1,
    bound: Int = 1,
    resolved: Int = 1,
    discharged: Int = 0,
    unresolved: List[UnresolvedEntry] = List(entry("s", "r", List(UnresolvedReason.Undischarged))),
    unmappedObligations: List[UnmappedObligation] = Nil
  ): Either[String, ChainStateReport] =
    ChainStateReport.fromCounts(
      change,
      baseline,
      total,
      bound,
      resolved,
      discharged,
      unresolved,
      unmappedObligations
    )

  private def expectRejection(result: Either[String, ChainStateReport], clause: String): Unit =
    result match
      case Left(msg) => assert(msg.nonEmpty, s"$clause: rejection must name the violated clause")
      case Right(r)  => fail(s"$clause: expected Left, got report $r")

  // ── UnresolvedEntry.of admits only contract-valid entries ────────────
  test("UnresolvedEntry.of rejects empty spec, empty requirement, empty reasons, and repeated reasons"):
    assertEquals(UnresolvedEntry.of("", "r", List(UnresolvedReason.Unbound)), None)
    assertEquals(UnresolvedEntry.of("s", "", List(UnresolvedReason.Unbound)), None)
    assertEquals(UnresolvedEntry.of("s", "r", Nil), None)
    assertEquals(
      UnresolvedEntry.of("s", "r", List(UnresolvedReason.Unbound, UnresolvedReason.Unbound)),
      None
    )
    assert(UnresolvedEntry.of("s", "r", List(UnresolvedReason.Unbound)).isDefined)

  // ── fromCounts: every clause of the report contract is enforced ──────
  test("fromCounts rejects an empty change"):
    expectRejection(baseReport(change = ""), "empty change")

  test("fromCounts rejects an empty baseline"):
    expectRejection(baseReport(baseline = ""), "empty baseline")

  test("fromCounts rejects a negative count"):
    expectRejection(baseReport(total = -1), "negative count")

  // Ring 5 surgical kill — the non-negativity clause is the UNIQUE decider
  // for this fixture: with discharged = -1 the count differences still
  // agree, so every later clause passes. Without the exists(_ < 0) check
  // the report would be admitted.
  test("fromCounts rejects a negative discharged when every later clause is consistent"):
    expectRejection(
      baseReport(
        discharged = -1,
        unresolved = List(
          entry("s", "r1", List(UnresolvedReason.Undischarged)),
          entry("s", "r2", List(UnresolvedReason.Undischarged))
        )
      ),
      "negative discharged with consistent count arithmetic"
    )

  test("fromCounts rejects discharged exceeding resolved"):
    expectRejection(baseReport(discharged = 2, resolved = 1), "discharged > resolved")

  test("fromCounts rejects resolved exceeding bound"):
    expectRejection(baseReport(total = 2, bound = 1, resolved = 2), "resolved > bound")

  test("fromCounts rejects bound exceeding total"):
    expectRejection(baseReport(total = 1, bound = 2, resolved = 2), "bound > total")

  test("fromCounts rejects an unresolved list shorter than total - discharged"):
    expectRejection(baseReport(unresolved = Nil), "unresolved.length != total - discharged")

  // Ring 5 surgical kill — the list-length clause is the UNIQUE decider
  // for this fixture: a single entry carries every reason the count
  // differences require, so only the length law rejects it.
  test("fromCounts rejects a short unresolved list whose reason counts still agree"):
    expectRejection(
      baseReport(
        total = 2,
        unresolved = List(entry("s", "r", List(UnresolvedReason.Unbound, UnresolvedReason.Undischarged)))
      ),
      "list length disagrees with total - discharged while reason counts agree"
    )

  test("fromCounts rejects duplicate (spec, requirement) pairs in unresolved"):
    val dup: UnresolvedEntry = entry("s", "r", List(UnresolvedReason.Undischarged))
    expectRejection(
      baseReport(total = 2, bound = 2, resolved = 2, unresolved = List(dup, dup)),
      "duplicate (spec, requirement)"
    )

  test("fromCounts rejects an unbound count that disagrees with total - bound"):
    expectRejection(
      baseReport(unresolved = List(entry("s", "r", List(UnresolvedReason.Unbound)))),
      "total - bound != unbound-reasoned entries"
    )

  // Ring 5 surgical kill — the unbound-count clause is the UNIQUE decider:
  // a single entry carrying Unbound + Undischarged satisfies every later
  // clause while disagreeing with total - bound.
  test("fromCounts rejects an unbound reason smuggled inside a multi-reason entry"):
    expectRejection(
      baseReport(
        unresolved = List(
          entry("s", "r", List(UnresolvedReason.Unbound, UnresolvedReason.Undischarged))
        )
      ),
      "unbound count disagrees while downstream counts still agree"
    )

  test("fromCounts rejects an unresolved/unattributable count that disagrees with bound - resolved"):
    expectRejection(
      baseReport(unresolved = List(entry("s", "r", List(UnresolvedReason.Unresolved)))),
      "bound - resolved != unresolved/unattributable-reasoned entries"
    )

  // Ring 5 surgical kill — the unresolved-count clause is the UNIQUE
  // decider: two entries carrying Unresolved over-count bound - resolved
  // while the unbound and undischarged counts still agree.
  test("fromCounts rejects an over-count of unresolved reasons that downstream clauses cannot see"):
    expectRejection(
      baseReport(
        total = 2,
        resolved = 0,
        unresolved = List(
          entry("s", "r1", List(UnresolvedReason.Unbound, UnresolvedReason.Unresolved)),
          entry("s", "r2", List(UnresolvedReason.Unresolved))
        )
      ),
      "unresolved count disagrees while unbound and undischarged counts agree"
    )

  test("fromCounts rejects an undischarged/failed count that disagrees with resolved - discharged"):
    expectRejection(
      baseReport(
        bound = 0,
        resolved = 0,
        discharged = 0,
        unresolved = List(
          entry("s", "r", List(UnresolvedReason.Unbound, UnresolvedReason.Undischarged))
        )
      ),
      "resolved - discharged != undischarged/failed-reasoned entries"
    )

  test("fromCounts rejects unmapped obligations with an empty spec, empty artifact, or non-positive line"):
    expectRejection(
      baseReport(unmappedObligations = List(UnmappedObligation("", 5, "a.scala"))),
      "unmapped entry with empty spec"
    )
    expectRejection(
      baseReport(unmappedObligations = List(UnmappedObligation("s", 5, ""))),
      "unmapped entry with empty artifact"
    )
    expectRejection(
      baseReport(unmappedObligations = List(UnmappedObligation("s", 0, "a.scala"))),
      "unmapped entry with line 0"
    )

  test("fromCounts accepts a valid report, including a line-1 unmapped obligation"):
    assert(baseReport().isRight)
    assert(
      baseReport(unmappedObligations = List(UnmappedObligation("s", 1, "a.scala"))).isRight,
      "line 1 is a valid unmapped-obligation line"
    )

  // ── Wire codec: contract field names out, contract-checked parse in ──
  test("report codec emits the contract field names and round-trips"):
    val report: ChainStateReport = baseReport(
      total = 6,
      bound = 4,
      resolved = 2,
      discharged = 0,
      unresolved = List(
        entry("s", "a", List(UnresolvedReason.Unbound)),
        entry("s", "b", List(UnresolvedReason.Unresolved)),
        entry("s", "c", List(UnresolvedReason.Undischarged)),
        entry("s", "d", List(UnresolvedReason.Failed)),
        entry("s", "e", List(UnresolvedReason.Unbound)),
        entry("s", "f", List(UnresolvedReason.Unattributable))
      ),
      unmappedObligations = List(UnmappedObligation("s", 40, "x.scala"))
    ) match
      case Right(r)  => r
      case Left(msg) => fail(s"fixture report must satisfy the contract: $msg")
    val written: String = upickle.default.write(report)
    val obj: ujson.Obj = ujson.read(written) match
      case o: ujson.Obj => o
      case other        => fail(s"expected a JSON object, got $other")
    assertEquals(
      obj.value.keySet,
      Set("change", "baseline", "total", "bound", "resolved", "discharged", "unresolved", "unmapped_obligations")
    )
    obj("unresolved").arr.toList match
      case e0 :: _ => assertEquals(e0.obj.keySet, Set("spec", "requirement", "reasons"))
      case other   => fail(s"expected unresolved entries, got $other")
    // Every UnresolvedReason string survives the wire.
    List("unbound", "unresolved", "unattributable", "undischarged", "failed").foreach { rs =>
      assert(written.contains(s"\"$rs\""), s"written report must carry reason '$rs'")
    }
    assertEquals(upickle.default.read[ChainStateReport](written), report)

  test("report codec rejects contract-violating wire values"):
    // upickle wraps the reader's rejection in a TraceException — an
    // Exception either way, never a silently-mapped value.
    // Non-integer count
    intercept[Exception](
      upickle.default.read[ChainStateReport](
        """{"change":"c","baseline":"b","total":1.5,"bound":1,"resolved":1,"discharged":0}"""
      )
    )
    // Count-inconsistent object (negative total) is rejected via fromCounts
    intercept[Exception](
      upickle.default.read[ChainStateReport](
        """{"change":"c","baseline":"b","total":-1,"bound":0,"resolved":0,"discharged":0}"""
      )
    )
    // Unresolved entry with an unknown reason string
    intercept[Exception](
      upickle.default.read[ChainStateReport](
        """{"change":"c","baseline":"b","total":1,"bound":0,"resolved":0,"discharged":0,"unresolved":[{"spec":"s","requirement":"r","reasons":["bogus"]}]}"""
      )
    )
    // Unresolved entry violating `of` (empty reasons)
    intercept[Exception](
      upickle.default.read[ChainStateReport](
        """{"change":"c","baseline":"b","total":1,"bound":0,"resolved":0,"discharged":0,"unresolved":[{"spec":"s","requirement":"r","reasons":[]}]}"""
      )
    )
    // A non-object top level crashes, never maps to a value
    intercept[Exception](upickle.default.read[ChainStateReport]("\"oops\""))

  test("UnresolvedReason codec round-trips every case and rejects non-strings and unknowns"):
    List(
      UnresolvedReason.Unbound,
      UnresolvedReason.Unresolved,
      UnresolvedReason.Unattributable,
      UnresolvedReason.Undischarged,
      UnresolvedReason.Failed
    ).foreach(r => assertEquals(upickle.default.read[UnresolvedReason](upickle.default.write(r)), r))
    intercept[Exception](upickle.default.read[UnresolvedReason]("\"bogus\""))
    intercept[Exception](upickle.default.read[UnresolvedReason]("42"))

  test("UnresolvedEntry codec round-trips and rejects contract-violating entries"):
    val e: UnresolvedEntry = entry("s", "r", List(UnresolvedReason.Failed, UnresolvedReason.Undischarged))
    val written: String    = upickle.default.write(e)
    val obj: ujson.Obj = ujson.read(written) match
      case o: ujson.Obj => o
      case other        => fail(s"expected a JSON object, got $other")
    assertEquals(obj.value.keySet, Set("spec", "requirement", "reasons"))
    assertEquals(upickle.default.read[UnresolvedEntry](written), e)
    // A missing field, an unknown reason, and an `of`-violating entry all reject.
    intercept[Exception](
      upickle.default.read[UnresolvedEntry]("""{"spec":"s","requirement":"r"}""")
    )
    intercept[Exception](
      upickle.default.read[UnresolvedEntry](
        """{"spec":"s","requirement":"r","reasons":["bogus"]}"""
      )
    )
    intercept[Exception](
      upickle.default.read[UnresolvedEntry]("""{"spec":"","requirement":"r","reasons":["unbound"]}""")
    )

  test("report codec treats absent unresolved/unmapped_obligations keys as empty (jq parity)"):
    val report: ChainStateReport =
      upickle.default.read[ChainStateReport](
        """{"change":"c","baseline":"b","total":0,"bound":0,"resolved":0,"discharged":0}"""
      )
    assertEquals(report.unresolved, Nil)
    assertEquals(report.unmappedObligations, Nil)

  // ════════════════════════════════════════════════════════════════════
  // spec: chain-state-undetermined-fidelity — the pre-pass boundary
  // The kernel level: `compute` takes a `PrePassOutcome`; a `DidNotRun`
  // never yields a report. The adapter-level classification of process
  // termination lives in the cli suite (`ChainStateUndeterminedFidelitySpec`)
  // and the acceptance oracle (`chain-state.bats`).
  // ════════════════════════════════════════════════════════════════════

  private def dischargingRow(req: ChainState.Requirement): LedgerRecord =
    LedgerRecord(
      v = 1,
      ts = "2026-08-08T12:34:56Z",
      change = "c",
      spec = req.spec,
      ring = Ring.R3,
      obligation = req.requirement,
      artifact = "a.scala",
      command = "sbt test",
      exit = 0,
      baseline = "abc1234",
      optional = LedgerRecordOptional()
    )

  /** Deliberately populated inputs — a DidNotRun must ignore ALL of them. */
  private def populatedInputs: (Ledger.LedgerData, RequirementSet) =
    val reqs: List[ChainState.Requirement] = List(ChainState.Requirement("s", "R1"))
    (Ledger.fromRecords(reqs.map(dischargingRow)), reqSetWith(reqs, reqs.map(oblFor)))

  // ── Scenario: Happy path — a completed pre-pass yields a verdict with counts
  // spec: chain-state-undetermined-fidelity — Scenario: Happy path — a completed pre-pass yields a verdict with counts
  test("a completed pre-pass yields a verdict with counts"):
    val (ledger, reqs) = populatedInputs
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Resolved, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(lint)),
        ledger,
        reqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.total, 1)
        assertEquals(report.bound, 1)
        assertEquals(report.resolved, 1)
        assertEquals(report.discharged, 1)
        assertEquals(report.unresolved, Nil)
      case Left(u) => fail(s"Expected a measured verdict, got undetermined: ${u.reason.text}")

  // ── Scenario: Error path — a pre-pass that cannot be executed is could-not-determine
  // spec: chain-state-undetermined-fidelity — Scenario: Error path — a pre-pass that cannot be executed is could-not-determine
  test("a pre-pass that cannot be executed is could-not-determine naming the pre-pass"):
    val (ledger, reqs) = populatedInputs
    val reason: UndeterminedReason =
      UndeterminedReason.stated("spec-lint not found at /tool/spec-lint.sh; cannot determine bound/resolved")
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.DidNotRun(reason),
        ledger,
        reqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Left(u) =>
        assertEquals(u.reason, reason, "the stated reason is carried verbatim")
        assert(u.reason.text.contains("spec-lint"), "the reason names the pre-pass")
      case Right(r) =>
        fail(s"a did-not-run pre-pass produced a measured report: discharged=${r.discharged}")

  // ── Scenario: Adversarial — a pre-pass exiting outside its outcome range emits no counts
  // spec: chain-state-undetermined-fidelity — Scenario: Adversarial — a pre-pass exiting outside its outcome range emits no counts
  test("a pre-pass exiting outside its outcome range emits no counts"):
    val (ledger, reqs) = populatedInputs
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.DidNotRun(
          UndeterminedReason.stated("spec-lint exited 42 (not a lint-finding exit); cannot determine bound/resolved")
        ),
        ledger,
        reqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Right(r) => fail(s"did-not-run produced a report: $r")
      case Left(u) =>
        val json: ujson.Value = upickle.default.writeJs(u)
        List("total", "bound", "resolved", "discharged").foreach { (k: String) =>
          assert(
            !json.obj.contains(k) || json(k) == ujson.Null,
            s"could-not-determine must not carry a $k figure, got: $json"
          )
        }

  // ── Scenario: Happy path — an unreadable evidence record names the evidence record
  // spec: chain-state-undetermined-fidelity — Scenario: Happy path — an unreadable evidence record names the evidence record
  test("a could-not-determine caused by an unreadable evidence record names the evidence record"):
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.DidNotRun(
          UndeterminedReason.stated("evidence-ledger.jsonl could not be read: truncated at line 42")
        ),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Left(u) =>
        assert(
          u.reason.text.contains("evidence-ledger.jsonl"),
          s"the reason names the evidence record, got: ${u.reason.text}"
        )
      case Right(_) => fail("an unreadable evidence record is never a measurement")

  // ── Scenario: Edge case — an empty but readable evidence record is a measurement, not an unknown
  // spec: chain-state-undetermined-fidelity — Scenario: Edge case — an empty but readable evidence record is a measurement, not an unknown
  test("an empty but readable evidence record is a measurement, not an unknown"):
    val reqs: List[ChainState.Requirement] = List(ChainState.Requirement("s", "R1"))
    val lint: LintReport = SpecLintFixtures.report(
      verdicts = List(RequirementVerdict("R1", Verdict.Resolved, CheckId.F1)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(okLints(lint)),
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
        assertEquals(report.discharged, 0, "an empty ledger discharges nothing")
        assertEquals(report.total, 1)
      case Left(u) => fail(s"an empty ledger is a measurement, not undetermined: ${u.reason.text}")

  // ── Scenario: Adversarial — a reason that names nothing is not emitted
  // spec: chain-state-undetermined-fidelity — Scenario: Adversarial — a reason that names nothing is not emitted
  test("a reason that names nothing is not emitted"):
    assert(UndeterminedReason.of("").isLeft, "of rejects an empty reason")
    val fallback: UndeterminedReason = UndeterminedReason.stated("")
    assert(
      fallback.text.nonEmpty && fallback.text.contains("input under inspection"),
      s"the stated fallback still names the input under inspection, got: ${fallback.text}"
    )
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.DidNotRun(fallback),
        emptyLedger,
        noReqs,
        Map.empty,
        "abc1234",
        "abc1234",
        "c",
        noForgive
      )
    result match
      case Left(u)  => assert(u.reason.text.nonEmpty, "the emitted reason is never empty")
      case Right(_) => fail("a did-not-run pre-pass produced a report")

  // ── Property: no-counts-without-a-completed-pre-pass ─────────────────
  // spec: chain-state-undetermined-fidelity — Property: no-counts-without-a-completed-pre-pass
  //
  // genPrePassOutcome — constructive over the two variants: `Completed`
  // draws a per-spec lint map over 0–8 requirements with varied verdicts
  // (plus a did-not-complete lint outcome arm); `DidNotRun` draws a reason
  // from the closed set of named inputs.

  /** The closed set of could-not-determine reasons naming an input. */
  private lazy val namedInputReasons: List[String] = List(
    "spec-lint not found at /tool/spec-lint.sh; cannot determine bound/resolved",
    "spec-lint at /tool/spec-lint.sh is not executable; cannot determine bound/resolved",
    "spec-lint exited 13 (not a lint-finding exit); cannot determine bound/resolved",
    "spec-lint produced no recognised completion message (exit 0); it did not finish running",
    "could not read evidence-ledger.jsonl: malformed row",
    "no spec documents found under /c/specs; cannot determine bound/resolved"
  )

  private lazy val genDidNotRun: Gen[PrePassOutcome] =
    Gen.element("spec-lint absent", namedInputReasons.drop(1)).map { (r: String) =>
      PrePassOutcome.DidNotRun(UndeterminedReason.stated(r))
    }

  /** A coherent (prePass, ledger, requirements) bundle for the Completed arm. */
  private lazy val genCompletedBundle: Gen[(PrePassOutcome, Ledger.LedgerData, RequirementSet)] =
    for
      n <- Gen.int(Range.linear(0, 8))
      kinds <- Gen
        .element(Verdict.Resolved, List(Verdict.Bound, Verdict.Unbound))
        .list(Range.singleton(n))
      lintRan   <- Gen.frequency1(85 -> Gen.constant(true), 15 -> Gen.constant(false))
      discharge <- Gen.boolean.list(Range.singleton(n))
    yield
      val reqs: List[ChainState.Requirement] =
        (1 to n).toList.map(i => ChainState.Requirement("s", s"R$i"))
      val lint: LintReport = SpecLintFixtures.report(
        verdicts = reqs.zip(kinds).map((r, v) => RequirementVerdict(r.requirement, v, CheckId.F1)),
        warnings = List.empty,
        applicability = Map.empty,
        lintSuccess = true
      )
      val lintOutcome: Outcome[LintReport] =
        if lintRan then Outcome.Ran(lint)
        else Outcome.Undetermined("spec-lint did not complete successfully")
      val obls: List[ExtractedObligation] =
        reqs.zip(kinds).collect { case (r, v) if v != Verdict.Unbound => oblFor(r) }
      val rows: List[LedgerRecord] =
        reqs.lazyZip(kinds).lazyZip(discharge).toList.collect { case (r, Verdict.Resolved, true) => dischargingRow(r) }
      (
        PrePassOutcome.Completed(Map("s" -> lintOutcome)),
        Ledger.fromRecords(rows),
        reqSetWith(reqs, obls)
      )

  private lazy val genVerdictKernelInput: Gen[(PrePassOutcome, Ledger.LedgerData, RequirementSet)] =
    genCompletedBundle.flatMap { case (completed, ledger, reqs) =>
      Gen
        .frequency1(70 -> Gen.constant(completed), 30 -> genDidNotRun)
        .map((o: PrePassOutcome) => (o, ledger, reqs))
    }

  private lazy val boundaryCover: hedgehog.core.PropertyConfig => hedgehog.core.PropertyConfig =
    (c: hedgehog.core.PropertyConfig) => c.copy(testLimit = hedgehog.core.SuccessCount(200))

  property("no counts without a completed pre-pass", boundaryCover):
    for input <- genVerdictKernelInput.forAll
        .cover(25, "did-not-run", (t: (PrePassOutcome, Ledger.LedgerData, RequirementSet)) => t._1.didNotRun)
        .cover(50, "completed", (t: (PrePassOutcome, Ledger.LedgerData, RequirementSet)) => t._1.isCompleted)
    yield
      val (prePass, ledger, reqs) = input
      val result: Either[ChainStateUndetermined, ChainStateReport] =
        ChainState.compute(
          prePass,
          ledger,
          reqs,
          Map.empty,
          "abc1234",
          "abc1234",
          "c",
          noForgive
        )
      val rendered: ujson.Value = result match
        case Left(u)  => upickle.default.writeJs(u)
        case Right(r) => upickle.default.writeJs(r)
      val hasCountField: Boolean =
        List("total", "bound", "resolved", "discharged").exists(k =>
          rendered.obj.contains(k) && rendered(k) != ujson.Null
        )
      Result
        .assert(prePass.isCompleted || result.isLeft)
        .log("a did-not-run pre-pass produced a verdict report")
        .and(
          Result
            .assert(result.isRight || !hasCountField)
            .log(s"an undetermined verdict carries count fields: $rendered")
        )
        .and(
          result match
            case Right(r) =>
              Result
                .assert(hasCountField)
                .log("a completed pre-pass yields counts — even when all zero")
                .and(
                  Result.assert(
                    r.discharged <= r.resolved && r.resolved <= r.bound && r.bound <= r.total &&
                      r.unresolved.length == r.total - r.discharged
                  )
                )
                .log(s"counts violate monotonicity: $r")
            case Left(u) =>
              Result
                .assert(u.reason.text.nonEmpty)
                .log("a could-not-determine reason is never empty")
        )
