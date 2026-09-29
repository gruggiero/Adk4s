package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range

/**
 * Test fixtures for spec-lint reports.
 *
 * `LintReport`'s constructor is private — reports are built by
 * `LintReport.fromRun` against a parsed `SpecDocument`. Tests that
 * consume verdicts as data (chain-state, bridge, renderer tests) call
 * `report` with the verdict list they want attributed; the fixture
 * synthesizes the document and resolution data that `fromRun` derives
 * those verdicts from, so no test bypasses the smart constructor.
 *
 * spec: spec-lint-engine — Requirement: Every obligation row is either resolved to a requirement or reported as unresolvable
 */
object SpecLintFixtures:

  /**
   * Build a report attributing `verdicts` — one per requirement of a
   * synthetic document. `warnings` enter the finding stream as `Warn`
   * outcomes; `lintSuccess = false` adds a synthetic `Fail` finding
   * (success is derived from findings, never stored).
   *
   * Verdict `check` fields in the input are ignored — `fromRun` derives
   * them (`F7` for unbound or artifact-unchecked, `F9` for
   * artifact-verdicts). A `Resolved` verdict requests the artifact-check
   * path; `Bound` verdicts land in the unresolved-artifact set only when
   * at least one verdict is `Resolved` (otherwise the artifact check is
   * modelled as not run and `Bound` carries `F7`).
   */
  def report(
    verdicts: List[RequirementVerdict],
    warnings: List[LintWarning],
    applicability: Map[String, String],
    lintSuccess: Boolean
  ): LintReport =
    val requirements: List[RequirementBlock] = verdicts.map { v =>
      RequirementBlock(
        title = v.requirement,
        line = 1,
        endLine = 2,
        hasNormative = true,
        negative = true,
        scenarioCount = 1,
        normativeText = ""
      )
    }
    val coveredTitles: List[String] =
      verdicts.collect { case v if v.verdict != Verdict.Unbound => v.requirement }
    val rows: List[ObligationRow] = coveredTitles.distinct.map { t =>
      ObligationRow(
        line = 0,
        fieldCount = 5,
        source = s"Requirement: $t",
        enforcement = "fixture check",
        artifact = "",
        raw = s"| obligation | Requirement: $t | fixture check | |"
      )
    }
    val hasResolved: Boolean = verdicts.exists(_.verdict == Verdict.Resolved)
    val artifactUnresolved: Option[Set[String]] =
      if hasResolved then Some(verdicts.collect { case v if v.verdict == Verdict.Bound => v.requirement }.toSet)
      else None
    val requirementRows: Map[String, List[ObligationRow]] =
      rows.groupBy(_.source.stripPrefix("Requirement: "))
    val document: SpecDocument = SpecDocument(
      name = "fixture",
      lines = Vector.empty,
      requirements = requirements,
      properties = Nil,
      temporals = Nil,
      scenarios = Nil,
      obligationRows = rows,
      dataRowCount = rows.size,
      bridgeRowCount = 0,
      hasProofObligations = true,
      formalContractsContentLines = 0,
      hasBehavioralConcepts = false,
      artifactRows = rows,
      chainRows = rows
    )
    val findings: List[CheckOutcome] =
      warnings.map(CheckOutcome.Warn(_)) ++
        (if lintSuccess then Nil
         else List(CheckOutcome.Fail(CheckId.F7, None, "synthetic failure")))
    LintReport.fromRun(
      document,
      findings,
      applicability,
      resolvedRows = rows,
      unresolvableRows = Nil,
      requirementRows = requirementRows,
      artifactUnresolved = artifactUnresolved
    )

  // ════════════════════════════════════════════════════════════════════
  // LintContext fixtures — applicability facts as data
  // ════════════════════════════════════════════════════════════════════

  /**
   * Every applicability fact absent — the minimal honest context: no
   * registry (F10/W7 off), no inventory, no profile, no install roots.
   */
  val absentContext: LintContext =
    LintContext(
      schemaVersion = FactRead.Absent,
      registry = FactRead.Absent,
      registryConcepts = FactRead.Absent,
      inventoryTypes = FactRead.Absent,
      profile = FactRead.Absent,
      installRoots = Nil
    )

  /**
   * A context with the behavioural registry present — F10 applies and the
   * W7 identifier scan runs against `codeIdentifiers` = `inventory` ∖
   * `concepts`.
   */
  def registryContext(
    conceptCount: Int,
    concepts: List[String],
    inventory: List[String]
  ): LintContext =
    LintContext(
      schemaVersion = FactRead.Present(14),
      registry = FactRead.Present(conceptCount),
      registryConcepts = FactRead.Present(concepts),
      inventoryTypes = FactRead.Present(inventory),
      profile = FactRead.Absent,
      installRoots = Nil
    )

  // ════════════════════════════════════════════════════════════════════
  // Spec-document text builders — the bats fixture shapes
  // ════════════════════════════════════════════════════════════════════

  /** The bats `spec_header`: Concepts Used sections + ADDED Requirements. */
  val specHeader: String =
    """# Spec: Fixture
      |
      |## Concepts Used (behavioral)
      |
      || Concept | Role here | File |
      ||---------|-----------|------|
      || (none) | fixture spec | — |
      |
      |## Concepts Used (from inventory)
      |
      || Concept | Kind | Package |
      ||---------|------|---------|
      |
      |## ADDED Requirements
      |
      |""".stripMargin

  /** The bats `req_block`: SHALL + a Given/When/Then scenario. */
  def reqBlock(title: String): String =
    s"""### Requirement: $title
       |
       |The system SHALL do the thing named $title.
       |
       |**Given** a precondition
       |**When** an action
       |**Then** an observable outcome
       |
       |#### Scenario: happy path
       |
       |**Given** a specific setup
       |**When** a specific action
       |**Then** a specific assertion
       |
       |""".stripMargin

  /** The bats `po_header`. */
  val poHeader: String =
    "## Proof Obligations\n\n| Obligation | Source | Enforcement | Artifact |\n|---|---|---|---|\n"

  // ════════════════════════════════════════════════════════════════════
  // genSpecDocument — constructive generator for the reachability
  // properties (spec: spec-lint-engine — Properties (Ring 3))
  // ════════════════════════════════════════════════════════════════════

  private val genTitle: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(4, 14)).map(s => s"requirement-$s")

  private def uniqTitles(ts: List[String]): List[String] =
    ts.zipWithIndex.map { case (t, i) => s"$t-$i" }

  /** One obligation row's Source cell, drawn from the predecessor grammar. */
  private def genSourceCell(
    reqTitles: List[String],
    propTitles: List[String],
    scenTitles: List[String]
  ): Gen[String] =
    val byTitle: Gen[String] =
      Gen.element("requirement-0", reqTitles).map(t => s"Requirement: $t")
    val ordinal: Gen[String] =
      Gen
        .int(Range.linear(1, reqTitles.size + 2))
        .flatMap(n => Gen.element(s"R$n", List(s"Requirement $n")))
    val typedExisting: Gen[String] =
      Gen
        .element("Property", List("Scenario"))
        .flatMap { kind =>
          val pool: List[String] =
            if kind == "Property" then propTitles else scenTitles
          if pool.isEmpty then Gen.constant(s"$kind: orphan")
          else Gen.element("orphan", pool).map(n => s"$kind: $n")
        }
    val danglingTyped: Gen[String] =
      Gen
        .string(Gen.alpha, Range.linear(5, 12))
        .map(n => s"Property: absent-$n")
    val bare: Gen[String] =
      Gen.element("Requirement", List("see above", "the spec", "TODO"))
    Gen.frequency1(
      38 -> byTitle,
      20 -> ordinal,
      10 -> typedExisting,
      18 -> danglingTyped,
      14 -> bare
    )

  private val genEnforcement: Gen[String] =
    Gen.element(
      "property test",
      List("type system", "smart constructor", "tier-justified: perf", "manual")
    )

  /**
   * genSpecDocument — constructive: builds a `SpecDocument` value from a
   * generated requirement-title list (0–8), a generated obligation-row
   * list whose Source cells cover the predecessor's resolution grammar,
   * and generated property/scenario heading sets. No filtering.
   *
   * The Proof Obligations section is present in ~85% of documents; when
   * absent the row list is empty (the parser could never produce rows
   * without the section).
   *
   * spec: spec-lint-engine — Property: reachability-is-total (generator strategy)
   * spec: spec-lint-engine — Property: unmatched-rows-are-reported-never-dropped (generator strategy)
   */
  val genSpecDocument: Gen[SpecDocument] =
    for
      nReqs <- Gen.frequency1(
        10 -> Gen.constant(0),
        90 -> Gen.int(Range.linear(1, 8))
      )
      reqTitles  <- genTitle.list(Range.singleton(nReqs)).map(uniqTitles)
      propTitles <- genTitle.list(Range.linear(0, 3)).map(uniqTitles)
      scenTitles <- genTitle.list(Range.linear(0, 3)).map(uniqTitles)
      hasPo      <- Gen.frequency1(85 -> Gen.constant(true), 15 -> Gen.constant(false))
      nRows      <- if hasPo then Gen.int(Range.constant(0, nReqs + 3)) else Gen.constant(0)
      cells      <- genSourceCell(reqTitles, propTitles, scenTitles).list(Range.singleton(nRows))
      enfs       <- genEnforcement.list(Range.singleton(nRows))
    yield
      val requirements: List[RequirementBlock] = reqTitles.zipWithIndex.map { case (t, i) =>
        RequirementBlock(
          title = t,
          line = 10 + i * 8,
          endLine = 10 + i * 8 + 7,
          hasNormative = true,
          negative = false,
          scenarioCount = 1,
          normativeText = s"the system shall do $t"
        )
      }
      val properties: List[PropertyBlock] = propTitles.zipWithIndex.map { case (t, i) =>
        PropertyBlock(t, 200 + i * 5, 200 + i * 5 + 4, hasGeneratorStrategy = true)
      }
      val scenarios: List[ScenarioHeading] = scenTitles.zipWithIndex.map { case (t, i) =>
        ScenarioHeading(t, 300 + i)
      }
      val rows: List[ObligationRow] = cells.zip(enfs).zipWithIndex.map { case ((src, enf), i) =>
        ObligationRow(
          line = 400 + i,
          fieldCount = 6,
          source = src,
          enforcement = enf,
          artifact = "—",
          raw = s"| ob$i | $src | $enf | — |"
        )
      }
      SpecDocument(
        name = "gen",
        lines = Vector.empty,
        requirements = requirements,
        properties = properties,
        temporals = Nil,
        scenarios = scenarios,
        obligationRows = rows,
        dataRowCount = rows.size,
        bridgeRowCount = 0,
        hasProofObligations = hasPo,
        formalContractsContentLines = 0,
        hasBehavioralConcepts = true,
        artifactRows = rows,
        chainRows = rows
      )
