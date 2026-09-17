package org.sinemenda.probatio.core

/**
 * Typed contract for the spec-lint engine (spec 4, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3
 * implementation must satisfy — compiled under the real
 * probatio-core classpath, `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `LintReport` is a `final class` with a private constructor. The
 *    only construction routes are `LintReport.fromRun` (which derives
 *    `verdicts` from the parsed document — a report claiming success
 *    while omitting a document requirement is unrepresentable) and the
 *    JSON reader. `lintSuccess` is a derived `def`, never a stored
 *    field, and `warnings`/`failures` are projections of the ordered
 *    finding stream.
 *  - `SpecLintEngine.lint` takes the `--artifacts` decision as an
 *    explicit `checkArtifacts: Boolean` plus an injected
 *    `String => Boolean` tracked-file predicate — no argument has a
 *    default, so the flag cannot be silently dropped, and the engine
 *    performs no I/O of its own.
 *  - `lint` returns `Outcome[LintReport]`; an `Unreadable`
 *    applicability fact in the `LintContext` makes the run
 *    `Undetermined` rather than a clean or fabricated report.
 *  - `LintWarning.line` is `Option[Int]` — the predecessor emits W2/W4/
 *    W6 without a `line N:` prefix.
 *  - Verdict `check` attribution: `Unbound` → F7; `Bound` → F9 when the
 *    artifact check ran and held it back, F7 when it did not run;
 *    `Resolved` → F9.
 *
 * spec: spec-lint-engine — Concepts Introduced (new): SpecDocument
 * spec: spec-lint-engine — Concepts Introduced (new): ObligationSource
 * spec: spec-lint-engine — Concepts Introduced (new): CheckOutcome
 * spec: spec-lint-engine — Concepts Introduced (new): LintContext
 */
final class SpecLintEngineTypeContract extends ProbatioSuite:

  // ── SpecDocumentParser — the structural scan ────────────────────────
  val parseSig: (String, String) => SpecDocument =
    SpecDocumentParser.parse

  // ── SpecLintEngine — the pure check fold ────────────────────────────
  // The artifacts decision and the tracked-file predicate are explicit
  // parameters: omitting either does not compile.
  val lintSig: (SpecDocument, LintContext, Boolean, String => Boolean) => Outcome[LintReport] =
    SpecLintEngine.lint

  val obligationSourcesSig: (SpecDocument, ObligationRow) => List[ObligationSource] =
    SpecLintEngine.obligationSources

  // The reachability fold SpecLintKernel mirrors under Stainless.
  val reachabilityFoldSig: (Int, List[Int]) => (List[Int], Int) =
    SpecLintEngine.reachabilityFold

  // ── ObligationSource — the source-cell resolution algebra ───────────
  val sourceByTitleSig: Int => ObligationSource =
    ObligationSource.ByTitle.apply

  val sourceByOrdinalSig: Int => ObligationSource =
    ObligationSource.ByOrdinal.apply

  val sourceTypedSig: (String, String) => ObligationSource =
    ObligationSource.Typed.apply

  val sourceUnresolvableSig: String => ObligationSource =
    ObligationSource.Unresolvable.apply

  // ── CheckOutcome — Pass/Fail/Warn; no pass-by-default ───────────────
  val outcomePassSig: CheckId => CheckOutcome =
    CheckOutcome.Pass.apply

  val outcomeFailSig: (CheckId, Option[Int], String) => CheckOutcome =
    CheckOutcome.Fail.apply

  val outcomeWarnSig: LintWarning => CheckOutcome =
    CheckOutcome.Warn.apply

  // ── SpecDocument tree ───────────────────────────────────────────────
  val documentSig: SpecDocument => List[RequirementBlock] =
    (d: SpecDocument) => d.requirements

  val documentRowsSig: SpecDocument => List[ObligationRow] =
    (d: SpecDocument) => d.obligationRows

  val requirementBlockSig: RequirementBlock => (String, Int, Boolean, Boolean, Int) =
    (b: RequirementBlock) => (b.title, b.line, b.hasNormative, b.negative, b.scenarioCount)

  val propertyBlockSig: PropertyBlock => Boolean =
    (b: PropertyBlock) => b.hasGeneratorStrategy

  val temporalBlockSig: TemporalBlock => (Boolean, Boolean) =
    (b: TemporalBlock) => (b.hasTriggerEvent, b.hasResponseEvent)

  val obligationRowSig: ObligationRow => (Int, String, String) =
    (r: ObligationRow) => (r.line, r.source, r.enforcement)

  // ── LintContext — applicability facts as data ───────────────────────
  val lintContextSig: (
    FactRead[Int],
    FactRead[Int],
    FactRead[List[String]],
    FactRead[List[String]],
    FactRead[Option[String]],
    List[InstallRootScan]
  ) => LintContext = LintContext.apply

  val hasRegistrySig: LintContext => Boolean =
    (c: LintContext) => c.hasRegistry

  val codeIdentifiersSig: LintContext => Set[String] =
    (c: LintContext) => c.codeIdentifiers

  // ── LintReport — smart-constructor surface ──────────────────────────
  val fromRunSig: (
    SpecDocument,
    List[CheckOutcome],
    Map[String, String],
    List[ObligationRow],
    List[ObligationRow],
    Map[String, List[ObligationRow]],
    Option[Set[String]]
  ) => LintReport = LintReport.fromRun

  val verdictsSig: LintReport => List[RequirementVerdict] =
    (r: LintReport) => r.verdicts

  val findingsSig: LintReport => List[CheckOutcome] =
    (r: LintReport) => r.findings

  val lintSuccessSig: LintReport => Boolean =
    (r: LintReport) => r.lintSuccess

  val warningsSig: LintReport => List[LintWarning] =
    (r: LintReport) => r.warnings

  val resolvedRowsSig: LintReport => List[ObligationRow] =
    (r: LintReport) => r.resolvedRows

  val unresolvableRowsSig: LintReport => List[ObligationRow] =
    (r: LintReport) => r.unresolvableRows

  val rowsResolvingToSig: (LintReport, String) => List[ObligationRow] =
    (r: LintReport, title: String) => r.rowsResolvingTo(title)

  val applicabilitySig: LintReport => Map[String, String] =
    (r: LintReport) => r.applicability

  // ── LintWarning — optional source line ──────────────────────────────
  val lintWarningSig: (String, Option[Int], String) => LintWarning =
    LintWarning.apply

  // ── The pinned surface evaluates ────────────────────────────────────
  test("LintContext projections derive from the fact reads"):
    val ctx: LintContext = LintContext(
      schemaVersion = FactRead.Present(14),
      registry = FactRead.Present(2),
      registryConcepts = FactRead.Present(List("Schema", "Registry")),
      inventoryTypes = FactRead.Present(List("Schema", "SpecLintEngine")),
      profile = FactRead.Present(None),
      installRoots = Nil
    )
    assert(ctx.hasRegistry, "registry present must gate F10/W7 on")
    assertEquals(ctx.codeIdentifiers, Set("SpecLintEngine"))

  test("LintReport.fromRun derives verdicts covering every requirement"):
    val document: SpecDocument = SpecDocument(
      name = "t",
      lines = Vector.empty,
      requirements = List(
        RequirementBlock("Alpha", 1, 5, hasNormative = true, negative = false, scenarioCount = 1, normativeText = ""),
        RequirementBlock("Beta", 6, 9, hasNormative = true, negative = false, scenarioCount = 1, normativeText = "")
      ),
      properties = Nil,
      temporals = Nil,
      scenarios = Nil,
      obligationRows = List(
        ObligationRow(12, 6, "Requirement: Alpha", "type system", "", "| o | Requirement: Alpha | type system | |")
      ),
      dataRowCount = 1,
      bridgeRowCount = 0,
      hasProofObligations = true,
      formalContractsContentLines = 0,
      hasBehavioralConcepts = false,
      artifactRows = Nil,
      chainRows = Nil
    )
    val rows: List[ObligationRow] = document.obligationRows
    val report: LintReport = LintReport.fromRun(
      document,
      findings = Nil,
      applicability = Map.empty,
      resolvedRows = rows,
      unresolvableRows = Nil,
      requirementRows = Map("Alpha" -> rows),
      artifactUnresolved = Some(Set.empty)
    )
    assertEquals(report.verdicts.length, 2)
    assertEquals(report.verdicts.map(_.requirement), List("Alpha", "Beta"))
    assertEquals(report.verdicts.map(_.verdict), List(Verdict.Resolved, Verdict.Unbound))
    assert(report.lintSuccess, "no Fail findings → lintSuccess")
    assertEquals(report.rowsResolvingTo("Alpha"), rows)
    assertEquals(report.rowsResolvingTo("Beta"), Nil)

  test("lintSuccess is derived — a Fail finding flips it"):
    val report: LintReport = LintReport.fromRun(
      SpecDocument(
        name = "t",
        lines = Vector.empty,
        requirements = Nil,
        properties = Nil,
        temporals = Nil,
        scenarios = Nil,
        obligationRows = Nil,
        dataRowCount = 0,
        bridgeRowCount = 0,
        hasProofObligations = false,
        formalContractsContentLines = 0,
        hasBehavioralConcepts = false,
        artifactRows = Nil,
        chainRows = Nil
      ),
      findings = List(CheckOutcome.Fail(CheckId.F4, None, "no PO section")),
      applicability = Map.empty,
      resolvedRows = Nil,
      unresolvableRows = Nil,
      requirementRows = Map.empty,
      artifactUnresolved = None
    )
    assert(!report.lintSuccess, "a Fail finding must force lintSuccess=false")
    assertEquals(report.failures.length, 1)
