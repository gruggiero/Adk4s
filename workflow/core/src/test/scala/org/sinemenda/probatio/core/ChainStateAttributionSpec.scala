package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

/**
 * Tests for chain-state requirement extraction and attribution (spec 5).
 *
 * The oracle is written from the spec and the Step-1 contract ONLY, before
 * implementation: `RequirementExtractor.extract` and `ChainState.compute`
 * bodies are `???`, so every scenario and property here is expected RED at
 * the Step-2 polarity check.
 *
 * Kernel semantics pinned from the predecessor
 * (`scanner/chain-state.sh.predecessor.bak`):
 *   - per requirement, in order: `unbound` (spec-lint F7) → `unattributable`
 *     (degraded path: reachable but no row names it by exact title) →
 *     `unresolved` (a mapped obligation carries an F9 finding) →
 *     `failed`/`undischarged`/`ok` on the ledger read (a row is negative
 *     evidence, missing rows are absence of evidence — never collapsed).
 *   - `Ring.Manual` rows discharge: `ledger.sh read` does not filter by
 *     ring, and the bats oracle discharges on `--ring manual` rows.
 *   - `unattributable` is a DEGRADED-mode reason only: the graph path
 *     resolves sources uniformly, so a bound title with no mapped
 *     obligations reports `unresolved` there (the D5 fix).
 *   - `unmapped_obligations` carries every obligation row that maps to no
 *     requirement and carries a finding — never dropped, never
 *     misattributed.
 *
 * spec: chain-state-attribution
 */
final class ChainStateAttributionSpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  // ── helpers ─────────────────────────────────────────────────────────

  private def req(title: String, spec: String = "s"): ChainState.Requirement =
    ChainState.Requirement(spec, title)

  private def obl(
    line: Int,
    text: String,
    claims: List[String],
    unmappable: Boolean = false,
    artifact: String = "",
    spec: String = "s"
  ): ExtractedObligation =
    ExtractedObligation(
      spec,
      line,
      text,
      artifact,
      if artifact.isEmpty then Nil else List(artifact),
      claims,
      unmappable
    )

  private def reqSet(
    reqs: List[ChainState.Requirement],
    obligations: List[ExtractedObligation],
    source: FactSource
  ): RequirementSet =
    RequirementSet(reqs.map(_.spec).distinct, reqs, obligations, source)

  /** A minimal document whose requirements are exactly `titles`. */
  private def doc(titles: List[String], name: String): SpecDocument =
    SpecDocument(
      name = name,
      lines = Vector.empty,
      requirements = titles.zipWithIndex.map { case (t, i) =>
        RequirementBlock(
          t,
          i * 10 + 1,
          i * 10 + 9,
          hasNormative = true,
          negative = false,
          scenarioCount = 1,
          normativeText = ""
        )
      },
      properties = Nil,
      temporals = Nil,
      scenarios = Nil,
      obligationRows = Nil,
      dataRowCount = 0,
      bridgeRowCount = 0,
      hasProofObligations = true,
      formalContractsContentLines = 0,
      hasBehavioralConcepts = false,
      artifactRows = Nil
    )

  private def row(line: Int, source: String): ObligationRow =
    ObligationRow(line, fieldCount = 5, source, enforcement = "", artifact = "", raw = s"| | $source | | |")

  /**
   * A lint outcome over `titles` whose per-title row bindings and findings
   * are given explicitly — the `requirementRows` map is the loose spec-lint
   * binding (ByTitle OR ByOrdinal), exactly what the engine emits.
   * `artifactUnresolved = Some(Set())` means the artifact check ran and
   * failed nothing; `Some(Set(t))` marks title `t` artifact-unresolved.
   */
  private def lintRan(
    titles: List[String],
    requirementRows: Map[String, List[ObligationRow]],
    unresolvableRows: List[ObligationRow] = Nil,
    findings: List[CheckOutcome] = Nil,
    artifactUnresolved: Option[Set[String]] = Some(Set.empty),
    name: String = "s"
  ): Outcome[LintReport] =
    val document: SpecDocument = doc(titles, name)
    Outcome.Ran(
      LintReport.fromRun(
        document,
        findings,
        Map.empty,
        resolvedRows = requirementRows.values.flatten.toList.distinct,
        unresolvableRows = unresolvableRows,
        requirementRows = requirementRows,
        artifactUnresolved = artifactUnresolved
      )
    )

  private def lintUndetermined: Outcome[LintReport] =
    Outcome.Undetermined("spec-lint did not complete successfully")

  private def ledgerRow(
    spec: String,
    obligation: String,
    baseline: String = "base0",
    exit: Int = 0,
    ring: Ring = Ring.R3,
    artifact: String = "a/b.scala",
    change: String = "c"
  ): LedgerRecord =
    LedgerRecord(
      v = 1,
      ts = "2026-09-17T00:00:00Z",
      change = change,
      spec = spec,
      ring = ring,
      obligation = obligation,
      artifact = artifact,
      command = "sbt test",
      exit = exit,
      baseline = baseline
    )

  private def ledgerOf(rs: List[LedgerRecord]): Ledger.LedgerData =
    Ledger.fromRecords(rs)

  private val noForgive: (String, String) => Boolean = (_, _) => false

  private def reasonsOf(report: ChainStateReport, title: String): List[UnresolvedReason] =
    report.unresolved.filter(_.requirement == title).flatMap(_.reasons)

  // ════════════════════════════════════════════════════════════════════
  // Compile-Negative obligations (Step 1 — already green)
  // ════════════════════════════════════════════════════════════════════

  // ── Compile-Negative: ChainState.compute with a literal Nil requirement list ──
  // spec: chain-state-attribution — Compile-Negative: ChainState.compute with a literal Nil requirement list
  test("ChainState.compute does not accept a literal Nil requirement list"):
    val err: String = compileErrors(
      "ChainState.compute(Map.empty, ???, Nil, Map.empty, \"b\", \"b\", \"c\", (a: String, b: String) => false)"
    )
    assert(
      err.nonEmpty,
      "compute(..., Nil, ...) must not compile — the requirement argument is " +
        "a RequirementSet, so an empty-requirements placeholder is structurally dead"
    )

  test("ChainState.compute does not accept a bare List[Requirement]"):
    val err: String = compileErrors(
      "ChainState.compute(Map.empty, ???, List.empty[ChainState.Requirement], Map.empty, \"b\", \"b\", \"c\", (a: String, b: String) => false)"
    )
    assert(
      err.nonEmpty,
      "compute(..., List.empty[Requirement], ...) must not compile — " +
        "requirements enter only through RequirementExtractor"
    )

  test("the old five-argument ChainState.compute no longer compiles"):
    val err: String = compileErrors(
      "ChainState.compute(???, ???, ???, \"b\", \"b\", \"c\")"
    )
    assert(
      err.nonEmpty,
      "compute(lint, ledger, reqs, baseline, change) must not compile — " +
        "per-spec lints, per-spec baselines, and the forgiveness predicate are required"
    )

  // ── Compile-Negative: UnresolvedEntry with an empty reasons list ────
  // spec: chain-state-attribution — Compile-Negative: UnresolvedEntry with an empty reasons list
  test("UnresolvedEntry cannot be constructed directly"):
    val err: String = compileErrors(
      "UnresolvedEntry(\"s\", \"r\", List(UnresolvedReason.Failed))"
    )
    assert(
      err.nonEmpty,
      "UnresolvedEntry(...) must not compile — the primary constructor is private; " +
        "entries are built by UnresolvedEntry.of"
    )

  test("UnresolvedEntry.copy cannot weaken an entry to no reasons"):
    val err: String = compileErrors(
      "UnresolvedEntry.of(\"s\", \"r\", List(UnresolvedReason.Failed)) match " +
        "{ case Some(e) => e.copy(reasons = Nil); case None => () }"
    )
    assert(
      err.nonEmpty,
      "entry.copy(reasons = Nil) must not compile — copy is sealed shut so " +
        "a valid entry cannot be mutated into a reasonless one"
    )

  // ── Compile-Negative: ChainStateReport with discharged exceeding total ──
  // spec: chain-state-attribution — Compile-Negative: ChainStateReport with discharged exceeding total
  test("ChainStateReport cannot be constructed directly"):
    val err: String = compileErrors(
      "ChainStateReport(\"c\", \"b\", 1, 1, 1, 2, Nil, Nil)"
    )
    assert(
      err.nonEmpty,
      "ChainStateReport(...) must not compile — the primary constructor is private; " +
        "reports are built by ChainStateReport.fromCounts"
    )

  test("ChainStateReport.copy cannot inflate discharged past resolved"):
    val err: String = compileErrors(
      "ChainStateReport.fromCounts(\"c\", \"b\", 1, 1, 1, 1, Nil, Nil) match " +
        "{ case Right(r) => r.copy(discharged = 2); case Left(_) => () }"
    )
    assert(
      err.nonEmpty,
      "report.copy(discharged = 2) must not compile — copy is sealed shut so " +
        "a valid report cannot be mutated into an impossible one"
    )

  // ════════════════════════════════════════════════════════════════════
  // Scenarios (Step 2 oracle — expected RED until Step 3)
  // ════════════════════════════════════════════════════════════════════

  // ── Scenario: Happy path — a change with declared requirements reports a nonzero total ──
  // spec: chain-state-attribution — Scenario: Happy path — a change with declared requirements reports a nonzero total
  test("a change with declared requirements reports a nonzero total"):
    // Two spec documents declaring three requirements between them.
    val docA: SpecDocument = doc(List("Alpha", "Beta"), name = "spec-a")
    val docB: SpecDocument = doc(List("Gamma"), name = "spec-b")
    val extracted: RequirementSet = RequirementExtractor.extract(
      List(
        RequirementExtractor.NamedSpec("spec-a", docA),
        RequirementExtractor.NamedSpec("spec-b", docB)
      ),
      None
    )
    assertEquals(extracted.requirements.length, 3, "extraction must see all three requirements")
    assertEquals(extracted.specNames, List("spec-a", "spec-b"))
    val lints: Map[String, Outcome[LintReport]] = Map(
      "spec-a" -> lintRan(
        List("Alpha", "Beta"),
        Map("Alpha" -> List(row(12, "Requirement: Alpha")), "Beta" -> List(row(13, "Requirement: Beta"))),
        name = "spec-a"
      ),
      "spec-b" -> lintRan(List("Gamma"), Map("Gamma" -> List(row(12, "Requirement: Gamma"))), name = "spec-b")
    )
    val ledger: Ledger.LedgerData = ledgerOf(
      List(
        ledgerRow("spec-a", "obl alpha", artifact = "x/a.scala"),
        ledgerRow("spec-a", "obl beta", artifact = "x/b.scala"),
        ledgerRow("spec-b", "obl gamma", artifact = "x/g.scala")
      )
    )
    val obligations: List[ExtractedObligation] = List(
      obl(12, "obl alpha", List("Alpha"), spec = "spec-a"),
      obl(13, "obl beta", List("Beta"), spec = "spec-a"),
      obl(12, "obl gamma", List("Gamma"), spec = "spec-b")
    )
    val set: RequirementSet = extracted.copy(obligations = obligations)
    ChainState.compute(lints, ledger, set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(report.total, 3, "total equals the number of declared requirements")
        assertEquals(report.discharged, 3)
        assertEquals(report.unresolved, Nil)
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: Adversarial — a genuinely empty requirement set is distinguishable ──
  // spec: chain-state-attribution — Scenario: Adversarial — a genuinely empty requirement set is distinguishable from an unread one
  test("a genuinely empty requirement set produces a clean measured report, not undetermined"):
    val docEmpty: SpecDocument = doc(Nil, name = "only")
    val extracted: RequirementSet = RequirementExtractor.extract(
      List(RequirementExtractor.NamedSpec("only", docEmpty)),
      None
    )
    assert(extracted.isEmpty, "a spec with no requirement headings extracts empty")
    assertEquals(extracted.specNames, List("only"), "the spec was read — emptiness is a fact")
    ChainState.compute(
      Map("only" -> lintRan(Nil, Map.empty, name = "only")),
      ledgerOf(Nil),
      extracted,
      Map.empty,
      "base0",
      "base0",
      "c",
      noForgive
    ) match
      case Right(report) =>
        assertEquals(report.total, 0)
        assertEquals(report.discharged, 0)
        assertEquals(report.unresolved, Nil)
      case Left(u) => fail(s"a readable-but-empty change must not be undetermined: ${u.reason}")

  // ── Scenario: Adversarial — a reachable-but-unattributable requirement is not discharged ──
  // spec: chain-state-attribution — Scenario: Adversarial — a reachable-but-unattributable requirement is not discharged
  test("a reachable-but-unattributable requirement is unresolved with the unattributable reason (degraded)"):
    // spec-lint's loose binding marks Beta bound via an ordinal source; no
    // row names it by exact title. Degraded mode → unattributable.
    val reqs: List[ChainState.Requirement] = List(req("Alpha"), req("Beta"))
    val lint: Outcome[LintReport] = lintRan(
      List("Alpha", "Beta"),
      Map(
        "Alpha" -> List(row(12, "Requirement: Alpha")),
        "Beta"  -> List(row(13, "Requirement 2"))
      )
    )
    val obligations: List[ExtractedObligation] = List(
      obl(12, "obl alpha", List("Alpha")),
      // ordinal-sourced: claims no title, so it is unmappable — but it
      // carries no finding, so it does not land in unmapped_obligations.
      obl(13, "obl beta", Nil, unmappable = true)
    )
    val set: RequirementSet       = reqSet(reqs, obligations, FactSource.Degraded)
    val ledger: Ledger.LedgerData = ledgerOf(List(ledgerRow("s", "obl alpha")))
    ChainState.compute(Map("s" -> lint), ledger, set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(
          reasonsOf(report, "Beta"),
          List(UnresolvedReason.Unattributable),
          "reachable with no exact-title row must be unattributable, never discharged"
        )
        assert(report.discharged < report.total, "unattributable must not count as discharged")
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: Happy path — exact title + recorded run discharges ────
  // spec: chain-state-attribution — Scenario: Happy path — a requirement named by exact title and backed by a recorded run is discharged
  test("a requirement named by exact title and backed by a recorded run is discharged"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val lint: Outcome[LintReport] =
      lintRan(List("Alpha"), Map("Alpha" -> List(row(12, "Requirement: Alpha"))))
    val set: RequirementSet =
      reqSet(reqs, List(obl(12, "obl alpha", List("Alpha"))), FactSource.Degraded)
    val ledger: Ledger.LedgerData = ledgerOf(List(ledgerRow("s", "obl alpha")))
    ChainState.compute(Map("s" -> lint), ledger, set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(report.discharged, 1)
        assertEquals(report.unresolved, Nil)
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: Edge case — a ledger row at a superseded baseline does not discharge ──
  // spec: chain-state-attribution — Scenario: Edge case — a ledger row at a superseded baseline does not discharge
  test("a ledger row at a superseded baseline does not discharge"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val lint: Outcome[LintReport] =
      lintRan(List("Alpha"), Map("Alpha" -> List(row(12, "Requirement: Alpha"))))
    val set: RequirementSet =
      reqSet(reqs, List(obl(12, "obl alpha", List("Alpha"))), FactSource.Degraded)
    // The only evidence is at "oldsha", and nothing forgives it.
    val ledger: Ledger.LedgerData =
      ledgerOf(List(ledgerRow("s", "obl alpha", baseline = "oldsha")))
    ChainState.compute(Map("s" -> lint), ledger, set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(
          reasonsOf(report, "Alpha"),
          List(UnresolvedReason.Undischarged),
          "a stale-baseline row is absent evidence, not a discharge"
        )
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: --forgive-unchanged — a stale row on an unchanged artifact discharges ──
  // spec: chain-state-attribution — Requirement: Stale-baseline rows do not discharge unless the artifact is unchanged
  test("a stale-baseline row discharges when the forgiveness oracle says the artifact is unchanged"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val lint: Outcome[LintReport] =
      lintRan(List("Alpha"), Map("Alpha" -> List(row(12, "Requirement: Alpha"))))
    val set: RequirementSet =
      reqSet(reqs, List(obl(12, "obl alpha", List("Alpha"))), FactSource.Degraded)
    val ledger: Ledger.LedgerData = ledgerOf(
      List(
        ledgerRow("s", "obl alpha", baseline = "oldsha", artifact = "x/a.scala")
      )
    )
    val forgiveAll: (String, String) => Boolean = (_, _) => true
    ChainState.compute(Map("s" -> lint), ledger, set, Map.empty, "base0", "base0", "c", forgiveAll) match
      case Right(report) =>
        assertEquals(report.discharged, 1, "forgiven stale rows discharge")
        assertEquals(report.unresolved, Nil)
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: manual-ring rows discharge (predecessor does not filter rings) ──
  // spec: chain-state-attribution — Requirement: Stale-baseline rows do not discharge unless the artifact is unchanged
  test("a manual-ring ledger row discharges — the predecessor does not filter by ring"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val lint: Outcome[LintReport] =
      lintRan(List("Alpha"), Map("Alpha" -> List(row(12, "Requirement: Alpha"))))
    val set: RequirementSet =
      reqSet(reqs, List(obl(12, "obl alpha", List("Alpha"))), FactSource.Degraded)
    val ledger: Ledger.LedgerData =
      ledgerOf(List(ledgerRow("s", "obl alpha", ring = Ring.Manual)))
    ChainState.compute(Map("s" -> lint), ledger, set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(report.discharged, 1, "manual rows are evidence — ledger.sh read does not filter by ring")
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: a recorded red run is negative evidence — failed, not undischarged ──
  // spec: chain-state-attribution — Requirement: Stale-baseline rows do not discharge unless the artifact is unchanged
  test("an obligation with rows but no green row reports failed, not undischarged"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val lint: Outcome[LintReport] =
      lintRan(List("Alpha"), Map("Alpha" -> List(row(12, "Requirement: Alpha"))))
    val set: RequirementSet =
      reqSet(reqs, List(obl(12, "obl alpha", List("Alpha"))), FactSource.Degraded)
    val ledger: Ledger.LedgerData =
      ledgerOf(List(ledgerRow("s", "obl alpha", exit = 1)))
    ChainState.compute(Map("s" -> lint), ledger, set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(
          reasonsOf(report, "Alpha"),
          List(UnresolvedReason.Failed),
          "a red run is negative evidence — failed, distinct from undischarged"
        )
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: Adversarial — an ordinal-sourced finding is not attributed to the requirement at that ordinal ──
  // spec: chain-state-attribution — Scenario: Adversarial — an ordinal-sourced finding is not attributed to the requirement at that ordinal
  test("an ordinal-sourced finding is not attributed to the requirement at that ordinal"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"), req("Beta"))
    // spec-lint's loose binding covers Beta through the ordinal row; the row
    // at line 13 carries an F9 finding. Its source claims no title.
    val f9: CheckOutcome = CheckOutcome.Fail(
      CheckId.F9,
      Some(13),
      "artifact 'tests/fake.scala' does not resolve"
    )
    val lint: Outcome[LintReport] = lintRan(
      List("Alpha", "Beta"),
      Map(
        "Alpha" -> List(row(12, "Requirement: Alpha")),
        "Beta"  -> List(row(13, "Requirement 2"))
      ),
      unresolvableRows = List(row(13, "Requirement 2")),
      findings = List(f9),
      artifactUnresolved = Some(Set("Beta"))
    )
    val obligations: List[ExtractedObligation] = List(
      obl(12, "obl alpha", List("Alpha")),
      obl(13, "obl beta", Nil, unmappable = true)
    )
    val set: RequirementSet       = reqSet(reqs, obligations, FactSource.Degraded)
    val ledger: Ledger.LedgerData = ledgerOf(List(ledgerRow("s", "obl alpha")))
    ChainState.compute(Map("s" -> lint), ledger, set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assert(
          report.unmappedObligations.exists(u => u.spec == "s" && u.line == 13),
          "the ordinal-sourced finding row must land in unmapped_obligations"
        )
        assert(
          !reasonsOf(report, "Beta").contains(UnresolvedReason.Unresolved),
          "the ordinal row's finding must NOT be attributed to Beta"
        )
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: Happy path — a mappable finding is attributed to its requirement ──
  // spec: chain-state-attribution — Scenario: Happy path — a mappable finding is attributed to its requirement
  test("a mappable finding is attributed to its requirement, unmapped list empty"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val f9: CheckOutcome = CheckOutcome.Fail(
      CheckId.F9,
      Some(12),
      "artifact 'tests/fake.scala' does not resolve"
    )
    val lint: Outcome[LintReport] = lintRan(
      List("Alpha"),
      Map("Alpha" -> List(row(12, "Requirement: Alpha"))),
      findings = List(f9),
      artifactUnresolved = Some(Set("Alpha"))
    )
    val set: RequirementSet =
      reqSet(reqs, List(obl(12, "obl alpha", List("Alpha"))), FactSource.Degraded)
    ChainState.compute(Map("s" -> lint), ledgerOf(Nil), set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(
          reasonsOf(report, "Alpha"),
          List(UnresolvedReason.Unresolved),
          "an F9 finding on a title-mapped row makes the requirement unresolved"
        )
        assertEquals(report.unmappedObligations, Nil, "nothing unmappable → empty list")
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: graph mode — a bound-but-unmapped requirement reports unresolved, not unattributable ──
  // spec: chain-state-attribution — Requirement: An obligation that maps to no known requirement is reported separately, never dropped and never misattributed
  test("graph mode: a bound requirement with no mapped obligations reports unresolved, not unattributable"):
    // The D5 fix: `unattributable` exists ONLY in degraded mode. Under
    // FactSource.Graph the same shape reports `unresolved`.
    val reqs: List[ChainState.Requirement] = List(req("Beta"))
    val lint: Outcome[LintReport] = lintRan(
      List("Beta"),
      Map("Beta" -> List(row(13, "Requirement 1")))
    )
    val set: RequirementSet =
      reqSet(reqs, List(obl(13, "obl beta", Nil, unmappable = true)), FactSource.Graph)
    ChainState.compute(Map("s" -> lint), ledgerOf(Nil), set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(
          reasonsOf(report, "Beta"),
          List(UnresolvedReason.Unresolved),
          "graph mode reports bound-but-unmapped as unresolved, never unattributable"
        )
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ── Scenario: a missing or failed lint outcome is undetermined, never clean ──
  // spec: chain-state-attribution — Requirement: The correctness verdict is computed over the change's actual requirements
  test("a spec with no lint outcome makes the computation undetermined"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha", spec = "x"))
    val set: RequirementSet                = reqSet(reqs, Nil, FactSource.Degraded)
    // lints has no entry for spec "x" — the requirement's lint state is unknown.
    ChainState.compute(Map.empty, ledgerOf(Nil), set, Map.empty, "base0", "base0", "c", noForgive) match
      case Left(_) => () // undetermined — correct
      case Right(r) =>
        fail(s"a requirement whose spec was never linted must not produce a report, got $r")

  test("a failed lint outcome makes the computation undetermined, not clean"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val set: RequirementSet                = reqSet(reqs, Nil, FactSource.Degraded)
    ChainState.compute(
      Map("s" -> lintUndetermined),
      ledgerOf(Nil),
      set,
      Map.empty,
      "base0",
      "base0",
      "c",
      noForgive
    ) match
      case Left(_) => () // undetermined — correct
      case Right(r) =>
        fail(s"a failed lint must not produce a measured report, got $r")

  // ── Scenario: the extraction path is carried on the requirement set ──
  // spec: chain-state-attribution — Requirement: The extraction path used for the requirement set is reported
  test("extract tags the requirement set with the path that produced it"):
    val named: List[RequirementExtractor.NamedSpec] =
      List(RequirementExtractor.NamedSpec("only", doc(List("Alpha"), name = "only")))
    val degraded: RequirementSet = RequirementExtractor.extract(named, None)
    assertEquals(degraded.source, FactSource.Degraded, "no export → Degraded")
    val graph: RequirementSet = RequirementExtractor.extract(
      named,
      Some(ujson.Obj("obligations" -> ujson.Arr(), "specs" -> ujson.Arr("only")))
    )
    assertEquals(graph.source, FactSource.Graph, "a usable export → Graph")
    val malformed: RequirementSet = RequirementExtractor.extract(
      named,
      Some(ujson.Obj("not-obligations" -> ujson.Num(1)))
    )
    assertEquals(malformed.source, FactSource.Degraded, "a malformed export falls back to Degraded")

  // ── Scenario: a requirement with no obligations at all is unbound ────
  // spec: chain-state-attribution — Requirement: A requirement whose obligations cannot be attributed is never counted as discharged
  test("a requirement spec-lint never bound is unbound, not unattributable"):
    val reqs: List[ChainState.Requirement] = List(req("Alpha"))
    val lint: Outcome[LintReport]          = lintRan(List("Alpha"), Map.empty)
    val set: RequirementSet                = reqSet(reqs, Nil, FactSource.Degraded)
    ChainState.compute(Map("s" -> lint), ledgerOf(Nil), set, Map.empty, "base0", "base0", "c", noForgive) match
      case Right(report) =>
        assertEquals(
          reasonsOf(report, "Alpha"),
          List(UnresolvedReason.Unbound),
          "no proof obligation anywhere → unbound (F7), not unattributable"
        )
      case Left(u) => fail(s"expected Right, got undetermined: ${u.reason}")

  // ════════════════════════════════════════════════════════════════════
  // Properties (Ring 3 oracle — expected RED until Step 3)
  // ════════════════════════════════════════════════════════════════════

  // ── Property: counts-are-consistent ─────────────────────────────────
  // spec: chain-state-attribution — Property: counts-are-consistent
  private def genComputeInputs: Gen[
    (
      Map[String, Outcome[LintReport]],
      Ledger.LedgerData,
      RequirementSet,
      Map[String, String],
      String,
      String
    )
  ] =
    for
      nReqs <- Gen.int(Range.linear(0, 10))
      // per-requirement verdict: unbound (no rows), bound-unresolved (F9),
      // or resolved-candidate (rows, no F9)
      verdicts <- Gen.element(0, List(1, 2)).list(Range.singleton(nReqs))
      // ledger rows: for each resolved-candidate requirement, maybe a green
      // row, maybe a red row, maybe a stale row, maybe none
      ledgerPlans <- Gen
        .element(0, List(1, 2, 3)) // 0=none 1=green 2=red 3=stale
        .list(Range.singleton(nReqs))
    yield
      val titles: List[String] = (1 to nReqs).map(i => s"Req $i").toList
      val requirementRows: Map[String, List[ObligationRow]] =
        titles
          .zip(verdicts)
          .zipWithIndex
          .collect {
            case ((t, v), i) if v != 0 => t -> List(row(20 + i, s"Requirement: $t"))
          }
          .toMap
      val f9Findings: List[CheckOutcome] =
        titles.zip(verdicts).zipWithIndex.collect {
          case ((_, v), i) if v == 1 =>
            CheckOutcome.Fail(CheckId.F9, Some(20 + i), s"artifact 'x/f$i.scala' does not resolve")
        }
      val artifactUnresolved: Set[String] =
        titles.zip(verdicts).collect { case (t, v) if v == 1 => t }.toSet
      val lint: Outcome[LintReport] = lintRan(
        titles,
        requirementRows,
        findings = f9Findings,
        artifactUnresolved = Some(artifactUnresolved)
      )
      val obligations: List[ExtractedObligation] =
        titles.zip(verdicts).zipWithIndex.collect {
          case ((t, v), i) if v != 0 => obl(20 + i, s"obl $t", List(t))
        }
      val reqs: List[ChainState.Requirement] = titles.map(t => req(t))
      val ledger: Ledger.LedgerData = ledgerOf(
        titles.zip(verdicts).zip(ledgerPlans).zipWithIndex.collect {
          case (((t, v), plan), _) if v == 2 && plan == 1 =>
            ledgerRow("s", s"obl $t", baseline = "base0", exit = 0)
          case (((t, v), plan), _) if v == 2 && plan == 2 =>
            ledgerRow("s", s"obl $t", baseline = "base0", exit = 1)
          case (((t, v), plan), _) if v == 2 && plan == 3 =>
            ledgerRow("s", s"obl $t", baseline = "stale", exit = 0)
        }
      )
      (
        Map("s" -> lint),
        ledger,
        reqSet(reqs, obligations, FactSource.Degraded),
        Map.empty,
        "base0",
        "c"
      )

  property("counts-are-consistent", coverConfig):
    for in <- genComputeInputs.forAll
        .cover(
          10,
          "all-discharged",
          (in: (
            Map[String, Outcome[LintReport]],
            Ledger.LedgerData,
            RequirementSet,
            Map[String, String],
            String,
            String
          )) => in._3.requirements.nonEmpty && in._2.records.nonEmpty
        )
        .cover(
          15,
          "none-discharged",
          (in: (
            Map[String, Outcome[LintReport]],
            Ledger.LedgerData,
            RequirementSet,
            Map[String, String],
            String,
            String
          )) => in._2.records.isEmpty && in._3.requirements.nonEmpty
        )
        .cover(
          20,
          "requirement-with-no-verdict",
          (in: (
            Map[String, Outcome[LintReport]],
            Ledger.LedgerData,
            RequirementSet,
            Map[String, String],
            String,
            String
          )) => in._3.requirements.nonEmpty
        )
    yield
      val (lints, ledger, reqs, baselines, baseline, change) = in
      ChainState.compute(lints, ledger, reqs, baselines, baseline, baseline, change, noForgive) match
        case Right(r) =>
          Result
            .assert(r.discharged <= r.resolved && r.resolved <= r.bound && r.bound <= r.total)
            .and(Result.assert(r.unresolved.length == r.total - r.discharged))
        case Left(_) => Result.success

  // ── Property: unattributable-is-reachable-and-never-discharged ──────
  // spec: chain-state-attribution — Property: unattributable-is-reachable-and-never-discharged
  private def genUnattributableInputs: Gen[
    (
      Map[String, Outcome[LintReport]],
      Ledger.LedgerData,
      RequirementSet,
      List[String]
    )
  ] =
    for
      nReqs       <- Gen.int(Range.linear(1, 6))
      nUnattr     <- Gen.int(Range.linear(1, nReqs))
      dischargeOk <- Gen.boolean
    yield
      val titles: List[String]       = (1 to nReqs).map(i => s"Req $i").toList
      val unattrTitles: List[String] = titles.take(nUnattr)
      val okTitles: List[String]     = titles.drop(nUnattr)
      // Unattributable: covered by spec-lint's loose binding via an ordinal
      // row, but no row names the title exactly.
      val unattrRows: Map[String, List[ObligationRow]] =
        unattrTitles.zipWithIndex.map { case (t, i) =>
          t -> List(row(30 + i, s"Requirement ${i + 1}"))
        }.toMap
      val okRows: Map[String, List[ObligationRow]] =
        okTitles.zipWithIndex.map { case (t, i) =>
          t -> List(row(50 + i, s"Requirement: $t"))
        }.toMap
      val obligations: List[ExtractedObligation] =
        unattrTitles.zipWithIndex.map { case (_, i) =>
          obl(30 + i, s"obl unattr $i", Nil, unmappable = true)
        } ++ okTitles.zipWithIndex.map { case (t, i) =>
          obl(50 + i, s"obl $t", List(t))
        }
      val lint: Outcome[LintReport] = lintRan(titles, unattrRows ++ okRows)
      val ledger: Ledger.LedgerData =
        if dischargeOk then ledgerOf(okTitles.map(t => ledgerRow("s", s"obl $t")))
        else ledgerOf(Nil)
      (
        Map("s" -> lint),
        ledger,
        reqSet(titles.map(t => req(t)), obligations, FactSource.Degraded),
        unattrTitles
      )

  property("unattributable-is-reachable-and-never-discharged", coverConfig):
    for in <- genUnattributableInputs.forAll
        .cover(
          60,
          "has-unattributable",
          (in: (Map[String, Outcome[LintReport]], Ledger.LedgerData, RequirementSet, List[String])) => in._4.nonEmpty
        )
    yield
      val (lints, ledger, reqs, unattrTitles) = in
      ChainState.compute(lints, ledger, reqs, Map.empty, "base0", "base0", "c", noForgive) match
        case Right(r) =>
          unattrTitles.foldLeft(Result.success) { (res, title) =>
            res.and(
              Result
                .assert(
                  r.unresolved.exists(u =>
                    u.requirement == title && u.reasons.contains(UnresolvedReason.Unattributable)
                  )
                )
                .log(s"expected $title unresolved with unattributable, got ${r.unresolved}")
            )
          }
        case Left(u) => Result.failure.log(s"expected Right, got undetermined: ${u.reason}")

  // ── Property: obligation-rows-are-conserved ─────────────────────────
  // spec: chain-state-attribution — Property: obligation-rows-are-conserved
  private def genObligationRows: Gen[
    (
      Map[String, Outcome[LintReport]],
      Ledger.LedgerData,
      RequirementSet,
      List[(String, Int)] // (spec, line) of unmappable finding-carrying rows
    )
  ] =
    for
      nReqs <- Gen.int(Range.linear(1, 4))
      // per row: 0 = exact-title mapped, 1 = ordinal, 2 = typed-dangling, 3 = bare
      kinds <- Gen.element(0, List(1, 2, 3)).list(Range.linear(0, 5))
      hasF9 <- Gen.boolean.list(Range.singleton(kinds.length))
    yield
      val titles: List[String] = (1 to nReqs).map(i => s"Req $i").toList
      val rows: List[(Int, String)] = kinds.zipWithIndex.map { case (k, i) =>
        val src: String = k match
          case 0 => s"Requirement: ${titles(i % titles.length)}"
          case 1 => s"Requirement ${(i % nReqs) + 1}"
          case 2 => "Property: dangling-prop"
          case _ => "n/a"
        (60 + i, src)
      }
      val requirementRows: Map[String, List[ObligationRow]] =
        rows
          .zip(kinds)
          .flatMap { case ((ln, src), k) =>
            k match
              case 0 => List(titles(0) -> List(row(ln, src))) // will be regrouped below
              case 1 => List(titles((ln - 60) % nReqs) -> List(row(ln, src)))
              case _ => Nil
          }
          .groupMap(_._1)(_._2)
          .view
          .mapValues(_.flatten)
          .toMap
      // unmappable = no exact-title claim: kinds 1,2,3 are unmappable in degraded mode
      val obligations: List[ExtractedObligation] = rows.zip(kinds).map { case ((ln, src), k) =>
        k match
          case 0 => obl(ln, s"obl $ln", List(src.stripPrefix("Requirement: ")))
          case _ => obl(ln, s"obl $ln", Nil, unmappable = true)
      }
      val f9s: List[CheckOutcome] = rows.zip(hasF9).collect { case ((ln, _), true) =>
        CheckOutcome.Fail(CheckId.F9, Some(ln), s"artifact 'x/f$ln.scala' does not resolve")
      }
      val f9Lines: Set[Int] = f9s.collect { case CheckOutcome.Fail(_, Some(l), _) => l }.toSet
      val f9Titles: Set[String] = requirementRows.collect {
        case (t, rs) if rs.exists(r => f9Lines.contains(r.line)) => t
      }.toSet
      val unresolvable: List[ObligationRow] =
        rows.zip(kinds).collect { case ((ln, src), k) if k != 0 => row(ln, src) }
      val lint: Outcome[LintReport] = lintRan(
        titles,
        requirementRows,
        unresolvableRows = unresolvable,
        findings = f9s,
        artifactUnresolved = Some(f9Titles)
      )
      val expectedUnmapped: List[(String, Int)] =
        obligations.zip(rows).collect {
          case (o, (ln, _)) if o.unmappable && f9Lines.contains(ln) =>
            (o.spec, ln)
        }
      (
        Map("s" -> lint),
        ledgerOf(Nil),
        reqSet(titles.map(t => req(t)), obligations, FactSource.Degraded),
        expectedUnmapped
      )

  property("obligation-rows-are-conserved", coverConfig):
    for in <- genObligationRows.forAll
        .cover(
          12,
          "has-unmapped-rows",
          (in: (Map[String, Outcome[LintReport]], Ledger.LedgerData, RequirementSet, List[(String, Int)])) =>
            in._4.nonEmpty
        )
    yield
      val (lints, ledger, reqs, expectedUnmapped) = in
      ChainState.compute(lints, ledger, reqs, Map.empty, "base0", "base0", "c", noForgive) match
        case Right(r) =>
          Result
            .assert(r.unmappedObligations.map(u => (u.spec, u.line)).toSet == expectedUnmapped.toSet)
            .log(s"expected unmapped $expectedUnmapped, got ${r.unmappedObligations}")
            .and(
              Result
                .assert(r.unmappedObligations.distinct.length == r.unmappedObligations.length)
                .log("no unmapped row may be reported twice")
            )
        case Left(u) => Result.failure.log(s"expected Right, got undetermined: ${u.reason}")
