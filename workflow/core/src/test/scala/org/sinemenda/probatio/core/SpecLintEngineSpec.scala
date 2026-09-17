package org.sinemenda.probatio.core

import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Test oracle for the spec-lint engine (spec 4).
 *
 * Compile-negative obligations plus the Step-2 oracle: parser behaviour,
 * `obligationSources` resolution, one pinpoint test per predecessor check
 * (F1–F10, W1–W7), the spec's named scenarios, and the two reachability
 * properties.
 *
 * spec: spec-lint-engine — Compile-Negative: LintReport constructed with lintSuccess=true while its verdicts list omits a requirement present in the SpecDocument
 * spec: spec-lint-engine — Compile-Negative: a file read or environment read inside probatio-core's lint engine
 * spec: spec-lint-engine — Compile-Negative: SpecLintCmd.run executed without --artifacts
 * spec: spec-lint-engine — Compile-Negative: calling SpecLintEngine.lint without a LintContext
 * spec: spec-lint-engine — Property: reachability-is-total
 * spec: spec-lint-engine — Property: unmatched-rows-are-reported-never-dropped
 */
final class SpecLintEngineSpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  // ── Compile-Negative: LintReport smart-constructor bypass ───────────
  // spec: spec-lint-engine — Compile-Negative: LintReport constructed with lintSuccess=true while its verdicts list omits a requirement present in the SpecDocument
  test("LintReport cannot be constructed with a bare lintSuccess flag"):
    val err: String = compileErrors(
      "LintReport(verdicts = Nil, warnings = Nil, applicability = Map.empty, lintSuccess = true)"
    )
    assert(
      err.nonEmpty,
      "LintReport(...) must not compile — the primary constructor is private; " +
        "reports are built by LintReport.fromRun against a SpecDocument"
    )

  test("LintReport primary constructor is private"):
    val err: String = compileErrors(
      "new LintReport(Nil, Nil, Map.empty, Nil, Nil, Map.empty)"
    )
    assert(err.nonEmpty, "new LintReport(...) must not compile — private constructor")

  test("LintReport has no lintSuccess setter — success is derived from findings"):
    val err: String = compileErrors(
      "LintReport.empty.lintSuccess = true"
    )
    assert(err.nonEmpty, "assigning lintSuccess must not compile — it is a derived def")

  // ── Compile-Negative: file read or environment read inside the engine
  // spec: spec-lint-engine — Compile-Negative: a file read or environment read inside probatio-core's lint engine
  test("the lint engine accepts a parsed document, not a filesystem path"):
    val err: String = compileErrors(
      "SpecLintEngine.lint(java.nio.file.Paths.get(\"spec.md\"), ???, ???, ???)"
    )
    assert(
      err.nonEmpty,
      "SpecLintEngine.lint(Path, ...) must not compile — the engine takes a SpecDocument"
    )

  test("the lint engine sources perform no file I/O and no environment reads"):
    val moduleDir: Path = lintModuleDir
    val engineSources: List[Path] = List(
      "SpecLintEngine.scala",
      "SpecDocumentParser.scala",
      "SpecDocument.scala",
      "LintContext.scala",
      "CheckOutcome.scala",
      "LintReport.scala"
    ).map((name: String) => moduleDir.resolve(name))
    val forbidden: List[String] = List(
      "java.nio.file",
      "java.io.File",
      "System.getenv", // scalafix:ok DisableSyntax.NoSystemGetenv
      "scala.io.",
      "sys.process",
      "ProcessBuilder"
    )
    engineSources.foreach { path =>
      assert(Files.isRegularFile(path), s"engine source missing: $path")
      val text: String = Files.readString(path)
      forbidden.foreach { token =>
        assert(
          !text.contains(token),
          s"${path.getFileName} contains forbidden I/O token '$token'"
        )
      }
    }

  // ── Compile-Negative: the artifacts decision cannot be dropped ──────
  // spec: spec-lint-engine — Compile-Negative: SpecLintCmd.run executed without --artifacts
  test("SpecLintEngine.lint cannot be called without an explicit artifacts decision"):
    val err: String = compileErrors(
      "SpecLintEngine.lint(???, ???)"
    )
    assert(
      err.nonEmpty,
      "lint(document, context) must not compile — checkArtifacts and the " +
        "tracked-file predicate are required parameters, so --artifacts " +
        "cannot be silently ignored"
    )

  // ── Compile-Negative: the applicability context is required ─────────
  // spec: spec-lint-engine — Compile-Negative: calling SpecLintEngine.lint without a LintContext
  test("SpecLintEngine.lint cannot be called without a LintContext"):
    val err: String = compileErrors(
      "SpecLintEngine.lint(???, ???, ???)"
    )
    assert(
      err.nonEmpty,
      "lint without LintContext must not compile — the engine never " +
        "fabricates applicability"
    )

  // ── helpers ─────────────────────────────────────────────────────────

  /**
   * Locate `probatio-core`'s main source directory — tests run with the
   * repository root or the module dir as working directory depending on
   * the runner.
   */
  private def lintModuleDir: Path =
    val candidates: List[Path] = List(
      Paths.get("workflow/core/src/main/scala/org/sinemenda/probatio/core"),
      Paths.get("src/main/scala/org/sinemenda/probatio/core")
    )
    candidates.find((p: Path) => Files.isDirectory(p)) match
      case Some(dir) => dir
      case None =>
        fail(s"could not locate probatio-core sources from ${Paths.get("").toAbsolutePath}")

  // ════════════════════════════════════════════════════════════════════
  // Step 2 oracle — parser, source resolution, checks, properties
  // ════════════════════════════════════════════════════════════════════

  import SpecLintFixtures.absentContext
  import SpecLintFixtures.genSpecDocument
  import SpecLintFixtures.poHeader
  import SpecLintFixtures.registryContext
  import SpecLintFixtures.reqBlock
  import SpecLintFixtures.specHeader

  /** Parse then lint a spec text; fails the test unless the run is `Ran`. */
  private def lintText(
    source: String,
    ctx: LintContext = absentContext,
    checkArtifacts: Boolean = false,
    artifactTracked: String => Boolean = (_: String) => false
  ): LintReport =
    SpecLintEngine.lint(
      SpecDocumentParser.parse("fixture", source),
      ctx,
      checkArtifacts,
      artifactTracked
    ) match
      case Outcome.Ran(report) => report
      case other               => fail(s"expected Outcome.Ran, got $other")

  /** The 1-based line number of the first line containing `needle`. */
  private def lineOf(source: String, needle: String): Int =
    source.linesIterator.zipWithIndex
      .find { case (l, _) => l.contains(needle) }
      .map { case (_, i) => i + 1 }
      .getOrElse(fail(s"fixture text does not contain '$needle'"))

  /** The failure findings carrying `check`. */
  private def failsWith(report: LintReport, check: CheckId): List[CheckOutcome.Fail] =
    report.failures.filter(_.check == check)

  /** The warnings carrying `code` ("W1" … "W7"). */
  private def warnsWith(report: LintReport, code: String): List[LintWarning] =
    report.warnings.filter(_.code == code)

  /** First element of a list the fixture guarantees nonempty. */
  private def headOr[A](xs: List[A]): A =
    xs.headOption.getOrElse(fail("fixture produced an empty list"))

  // ── Parser: block extraction and line preservation ──────────────────
  // spec: spec-lint-engine — Requirement: The engine is a pure function over a parsed spec document and a repository-facts context
  test("parser records every requirement, property, temporal and scenario heading with its source line"):
    val source: String =
      specHeader +
        reqBlock("Alpha does the thing") +
        "### Property: sorted-ness\n\n**Generator strategy**: genSorted\n\n" +
        "### Temporal: eventual flush\n\n**Trigger event**: write\n**Response event**: read-your-write\n\n" +
        poHeader +
        "| o | Requirement: Alpha does the thing | manual | — |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assertEquals(doc.requirements.map(_.title), List("Alpha does the thing"))
    assertEquals(doc.properties.map(_.title), List("sorted-ness"))
    assertEquals(doc.temporals.map(_.title), List("eventual flush"))
    assertEquals(doc.scenarios.map(_.title), List("happy path"))
    assertEquals(headOr(doc.requirements).line, lineOf(source, "### Requirement:"))
    assertEquals(headOr(doc.properties).line, lineOf(source, "### Property:"))
    assertEquals(headOr(doc.temporals).line, lineOf(source, "### Temporal:"))
    assertEquals(headOr(doc.scenarios).line, lineOf(source, "#### Scenario:"))

  test("parser records per-block facts: normative word, negativity, scenario count, generator and event lines"):
    val source: String =
      specHeader +
        "### Requirement: Beta never leaks state\n\n" +
        "The system SHALL never leak beta state.\n\n" +
        "**Given** a leak\n**When** a write\n**Then** nothing leaks\n\n" +
        "#### Scenario: leak attempt\n\n**Given** x\n**When** y\n**Then** z\n\n" +
        "#### Scenario: second attempt\n\n**Given** x\n**When** y\n**Then** z\n\n" +
        "### Property: no-strategy\n\nbody without a strategy line\n\n" +
        "### Temporal: half-wired\n\n**Trigger event**: tick\n\n" +
        poHeader +
        "| o | Requirement 1 | manual | — |\n"
    val doc: SpecDocument     = SpecDocumentParser.parse("fixture", source)
    val req: RequirementBlock = headOr(doc.requirements)
    assert(req.hasNormative, "SHALL before the first Given must set hasNormative")
    assert(req.negative, "'never' in title and body must mark the requirement negative")
    assertEquals(req.scenarioCount, 2)
    assert(!headOr(doc.properties).hasGeneratorStrategy, "no strategy line parsed")
    assert(headOr(doc.temporals).hasTriggerEvent, "trigger line parsed")
    assert(!headOr(doc.temporals).hasResponseEvent, "no response line parsed")

  test("parser counts every data row but keeps only the rows the source check evaluates"):
    val source: String =
      specHeader +
        reqBlock("Alpha does the thing") +
        poHeader +
        "| good | Requirement: Alpha does the thing | manual | — |\n" +
        "| short |\n" +
        "| empty-src | | manual | — |\n" +
        "| commented | <!-- todo --> | manual | — |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assertEquals(doc.dataRowCount, 4, "all four data rows count toward the W2 denominator")
    assertEquals(
      doc.obligationRows.length,
      1,
      "short (<4 fields), empty-source and comment-source rows never reach the source check"
    )
    assertEquals(headOr(doc.obligationRows).line, lineOf(source, "| good |"))

  test("parser records section flags: proof obligations, behavioural concepts, formal-contracts content, bridge rows"):
    val source: String =
      specHeader +
        reqBlock("Alpha does the thing") +
        "## Formal Contracts (Ring 6)\n\n" +
        "line one\nline two\nline three\n\n" +
        poHeader +
        "| o | Requirement: Alpha does the thing | mirror | `ParityBridgeSpec.scala` |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assert(doc.hasProofObligations, "PO section flag")
    assert(doc.hasBehavioralConcepts, "behavioural concepts flag")
    assertEquals(doc.formalContractsContentLines, 3)
    assertEquals(doc.bridgeRowCount, 1, "the bridge artifact row counts across all data rows")

  // ── obligationSources — the source-cell resolution algebra ──────────
  // spec: spec-lint-engine — Concepts Introduced (new): ObligationSource
  private def sourcesOf(source: String, cell: String): List[ObligationSource] =
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    val row: ObligationRow = doc.obligationRows.find(_.source.contains(cell)) match
      case Some(r) => r
      case None    => fail(s"no obligation row with source cell containing '$cell'")
    SpecLintEngine.obligationSources(doc, row)

  test("obligationSources resolves an exact requirement title to ByTitle"):
    val source: String =
      specHeader + reqBlock("Alpha does the thing") + poHeader +
        "| o | Requirement: Alpha does the thing | manual | — |\n"
    assertEquals(
      sourcesOf(source, "Alpha does the thing"),
      List(ObligationSource.ByTitle(0))
    )

  test("obligationSources resolves R<N> and 'Requirement N' to ByOrdinal"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + reqBlock("Req Beta") + poHeader +
        "| o | R2 | manual | — |\n" +
        "| p | Requirement 1 | manual | — |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assertEquals(
      SpecLintEngine.obligationSources(doc, doc.obligationRows(0)),
      List(ObligationSource.ByOrdinal(1))
    )
    assertEquals(
      SpecLintEngine.obligationSources(doc, doc.obligationRows(1)),
      List(ObligationSource.ByOrdinal(0))
    )

  test("obligationSources resolves a typed source to Typed and a bare cell to Unresolvable"):
    val source: String =
      specHeader + reqBlock("Req Alpha") +
        "### Property: sorted-ness\n\n**Generator strategy**: g\n\n" +
        poHeader +
        "| o | Property: sorted-ness | manual | — |\n" +
        "| p | Requirement: Req Alpha + Scenario: happy path | manual | — |\n" +
        "| q | the spec | manual | — |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assertEquals(
      SpecLintEngine.obligationSources(doc, doc.obligationRows(0)),
      List(ObligationSource.Typed("Property", "sorted-ness"))
    )
    val combined: List[ObligationSource] =
      SpecLintEngine.obligationSources(doc, doc.obligationRows(1))
    assert(combined.contains(ObligationSource.ByTitle(0)), s"missing ByTitle in $combined")
    assert(
      combined.contains(ObligationSource.Typed("Scenario", "happy path")),
      s"missing Typed in $combined"
    )
    assertEquals(
      SpecLintEngine.obligationSources(doc, doc.obligationRows(2)).length,
      1
    )
    SpecLintEngine.obligationSources(doc, doc.obligationRows(2)).headOption match
      case Some(ObligationSource.Unresolvable(_)) => ()
      case other                                  => fail(s"expected Unresolvable, got $other")

  test("obligationSources: an out-of-range ordinal is Unresolvable, never a phantom ByOrdinal"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement 9 | manual | — |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    val sources: List[ObligationSource] =
      SpecLintEngine.obligationSources(doc, headOr(doc.obligationRows))
    assert(
      sources.forall {
        case ObligationSource.Unresolvable(_) => true
        case _                                => false
      },
      s"out-of-range ordinal must not resolve: $sources"
    )

  // ── Scenario: Happy path — a clean document produces no failures ────
  // spec: spec-lint-engine — Scenario: Happy path — a clean document produces no failures
  test("a clean document produces no failures"):
    val report: LintReport = lintText(
      specHeader + reqBlock("Alpha does the thing") + poHeader +
        "| o | Requirement: Alpha does the thing | manual | — |\n"
    )
    assert(report.failures.isEmpty, s"unexpected failures: ${report.failures}")
    assert(report.lintSuccess, "a finding-free run reports success")

  // ── Scenario: Error path — a requirement named by no obligation ─────
  // spec: spec-lint-engine — Scenario: Error path — a requirement named by no obligation is reported unenforced
  test("a requirement named by no obligation is reported unenforced at its own line"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + reqBlock("Req Beta") + poHeader +
        "| o | Requirement: Req Alpha | manual | — |\n"
    val report: LintReport           = lintText(source)
    val f7s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F7)
    assertEquals(f7s.length, 1, s"expected exactly one F7: ${report.failures}")
    assertEquals(headOr(f7s).line, Some(lineOf(source, "### Requirement: Req Beta")))
    assert(
      headOr(f7s).message.contains("""requirement "Req Beta""""),
      s"F7 must name the uncovered requirement: ${headOr(f7s).message}"
    )
    assertEquals(report.rowsResolvingTo("Req Beta"), Nil)

  // ── Scenario: Edge case — an obligation whose source names nothing ──
  // spec: spec-lint-engine — Scenario: Edge case — an obligation whose source names nothing resolvable
  test("an obligation whose source names nothing resolvable fails F6 at the row and covers nothing"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement | manual | — |\n"
    val report: LintReport           = lintText(source)
    val f6s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F6)
    assertEquals(f6s.length, 1, s"expected one F6: ${report.failures}")
    assertEquals(headOr(f6s).line, Some(lineOf(source, "| o | Requirement |")))
    assert(
      headOr(f6s).message.contains("names no resolvable reference"),
      s"the generic F6 form: ${headOr(f6s).message}"
    )
    assertEquals(report.unresolvableRows.length, 1)
    assertEquals(report.resolvedRows.length, 0)
    assertEquals(report.rowsResolvingTo("Req Alpha"), Nil, "an unresolvable row covers nothing")

  // ── Scenario: Adversarial — a typed source naming a missing heading ─
  // spec: spec-lint-engine — Scenario: Adversarial — a typed source naming a heading that does not exist
  test("a typed source naming a heading that does not exist fails F8 and never fuzzy-matches"):
    val source: String =
      specHeader + reqBlock("Req Alpha") +
        "### Property: sorted-ness\n\n**Generator strategy**: g\n\n" +
        poHeader +
        "| o | Requirement: Req Alpha | manual | — |\n" +
        "| p | Property: missing-widget | manual | — |\n"
    val report: LintReport           = lintText(source)
    val f8s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F8)
    assertEquals(f8s.length, 1, s"expected one F8: ${report.failures}")
    assertEquals(headOr(f8s).line, Some(lineOf(source, "| p | Property: missing-widget")))
    assert(
      headOr(f8s).message.contains("missing-widget") && headOr(f8s).message.contains("Property"),
      s"F8 must name the dangling typed title: ${headOr(f8s).message}"
    )

  // ── F1–F10 pinpoints ────────────────────────────────────────────────
  test("F1: a requirement with no SHALL/MUST before its first Given fails at the heading line"):
    val source: String =
      specHeader +
        "### Requirement: Missing norm\n\n" +
        "**Given** a precondition\n**When** an action\n**Then** an outcome\n\n" +
        "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
        poHeader + "| o | Requirement: Missing norm | manual | — |\n"
    val report: LintReport           = lintText(source)
    val f1s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F1)
    assertEquals(f1s.length, 1)
    assertEquals(headOr(f1s).line, Some(lineOf(source, "### Requirement: Missing norm")))

  test("F2: a negative requirement with no scenario fails; one with a scenario warns W3"):
    val sourceNoScen: String =
      specHeader +
        "### Requirement: Alpha never leaks\n\n" +
        "The system SHALL never leak alpha.\n\n" +
        "**Given** x\n**When** y\n**Then** z\n\n" +
        poHeader + "| o | Requirement: Alpha never leaks | manual | — |\n"
    val report: LintReport = lintText(sourceNoScen)
    assertEquals(failsWith(report, CheckId.F2).length, 1, "negative requirement without a scenario must fail F2")

    val sourceWithScen: String =
      specHeader +
        "### Requirement: Alpha never leaks\n\n" +
        "The system SHALL never leak alpha.\n\n" +
        "**Given** x\n**When** y\n**Then** z\n\n" +
        "#### Scenario: leak attempt\n\n**Given** x\n**When** y\n**Then** z\n\n" +
        poHeader + "| o | Requirement: Alpha never leaks | manual | — |\n"
    val report2: LintReport = lintText(sourceWithScen)
    assert(failsWith(report2, CheckId.F2).isEmpty, "a scenario discharges F2")
    val w3s: List[LintWarning] = warnsWith(report2, "W3")
    assertEquals(w3s.length, 1, "a covered negative requirement warns W3")
    assertEquals(headOr(w3s).line, Some(lineOf(sourceWithScen, "### Requirement: Alpha never leaks")))

  test("F3: a property block without a generator strategy fails; one with it passes"):
    val source: String =
      specHeader +
        "### Property: no-strategy\n\nbody\n\n" +
        "### Property: has-strategy\n\n**Generator strategy**: g\n\n"
    val report: LintReport           = lintText(source)
    val f3s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F3)
    assertEquals(f3s.length, 1)
    assertEquals(headOr(f3s).line, Some(lineOf(source, "### Property: no-strategy")))

  test("F4: requirements with no Proof Obligations section fail at document level"):
    val report: LintReport           = lintText(specHeader + reqBlock("Req Alpha"))
    val f4s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F4)
    assertEquals(f4s.length, 1)
    assertEquals(headOr(f4s).line, None, "F4 is a document-level finding with no line")

  test("F5: a temporal block missing trigger or response event fails per missing line"):
    val source: String =
      specHeader +
        "### Temporal: half-wired\n\n**Trigger event**: tick\n\n" +
        "### Temporal: unwired\n\nbody\n\n"
    val report: LintReport           = lintText(source)
    val f5s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F5)
    assertEquals(f5s.length, 3, s"one missing response + two missing on 'unwired': ${report.failures}")

  test("F6: an out-of-range ordinal reports the 'cites Requirement N' form and resolves nothing"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement 9 | manual | — |\n"
    val report: LintReport           = lintText(source)
    val f6s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F6)
    assertEquals(f6s.length, 1)
    assert(
      headOr(f6s).message.contains("cites Requirement 9") && headOr(f6s).message.contains("spec has 1"),
      s"the ordinal F6 form: ${headOr(f6s).message}"
    )
    assertEquals(report.unresolvableRows.length, 1)

  test("F7: a typed source that resolves covers no requirement — F7 still fires"):
    val source: String =
      specHeader + reqBlock("Req Alpha") +
        "### Property: real-prop\n\n**Generator strategy**: g\n\n" +
        poHeader + "| o | Property: real-prop | manual | — |\n"
    val report: LintReport = lintText(source)
    assertEquals(failsWith(report, CheckId.F7).length, 1, "a Property row cannot cover a requirement")

  test("F9: a code-shaped artifact token that resolves to no tracked file fails — only when requested"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement: Req Alpha | manual | `ZzzNoSuchSpec.scala` |\n"
    val off: LintReport = lintText(source, checkArtifacts = false)
    assert(failsWith(off, CheckId.F9).isEmpty, "F9 does not run without the artifacts decision")

    val on: LintReport               = lintText(source, checkArtifacts = true, artifactTracked = _ => false)
    val f9s: List[CheckOutcome.Fail] = failsWith(on, CheckId.F9)
    assertEquals(f9s.length, 1)
    assert(headOr(f9s).message.contains("ZzzNoSuchSpec.scala"), s"F9 names the token: ${headOr(f9s).message}")

    val tracked: LintReport = lintText(source, checkArtifacts = true, artifactTracked = _ => true)
    assert(failsWith(tracked, CheckId.F9).isEmpty, "a resolved token produces no F9")

  test("F9: prose artifacts — README, review text, build commands — are never checked"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement: Req Alpha | manual | `README` |\n" +
        "| p | Requirement: Req Alpha | manual | `adversarial review` |\n" +
        "| q | Requirement: Req Alpha | manual | `sbt core/compile` |\n"
    val report: LintReport = lintText(source, checkArtifacts = true, artifactTracked = _ => false)
    assert(failsWith(report, CheckId.F9).isEmpty, s"prose artifacts must not be checked: ${report.failures}")

  // ── Scenario: Edge case — a present registry makes F10 apply ────────
  // spec: spec-lint-engine — Scenario: Edge case — a present registry makes its dependent check apply
  test("F10: a present registry and no behavioural-concepts section fails; the section discharges it"):
    val ctx: LintContext = registryContext(3, List("Schema", "Registry", "Strangler"), Nil)
    val noConcepts: String =
      "# Spec: F\n\n## ADDED Requirements\n\n" + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement: Req Alpha | manual | — |\n"
    val report: LintReport = lintText(noConcepts, ctx)
    assertEquals(failsWith(report, CheckId.F10).length, 1, "registry present + no concepts section must fail F10")

    val withConcepts: String = specHeader + reqBlock("Req Alpha") + poHeader +
      "| o | Requirement: Req Alpha | manual | — |\n"
    val report2: LintReport = lintText(withConcepts, ctx)
    assert(failsWith(report2, CheckId.F10).isEmpty, "the Concepts Used (behavioral) section discharges F10")

  // ── W1–W7 pinpoints ─────────────────────────────────────────────────
  test("W1: a vague word inside a requirement block warns at that line"):
    val source: String =
      specHeader +
        "### Requirement: Req Alpha\n\n" +
        "The system SHALL return a valid result.\n\n" +
        "**Given** x\n**When** y\n**Then** z\n\n" +
        "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
        poHeader + "| o | Requirement: Req Alpha | manual | — |\n"
    val w1s: List[LintWarning] = warnsWith(lintText(source), "W1")
    assertEquals(w1s.length, 1)
    assertEquals(headOr(w1s).line, Some(lineOf(source, "valid result")))

  test("W2: fewer data rows than requirements warns; equal or more does not"):
    val twoReqsOneRow: String =
      specHeader + reqBlock("Req Alpha") + reqBlock("Req Beta") + poHeader +
        "| o | Requirement: Req Alpha | manual | — |\n" +
        "| p | Requirement: Req Beta | manual | — |\n" +
        "| q | Requirement: Req Alpha | manual | — |\n"
    val report: LintReport = lintText(twoReqsOneRow)
    assert(warnsWith(report, "W2").isEmpty, "3 rows for 2 requirements: no W2")

    val thin: String =
      specHeader + reqBlock("Req Alpha") + reqBlock("Req Beta") + poHeader +
        "| o | Requirement: Req Alpha | manual | — |\n"
    val w2s: List[LintWarning] = warnsWith(lintText(thin), "W2")
    assertEquals(w2s.length, 1)
    assertEquals(headOr(w2s).line, None, "W2 carries no line")

  test("W4: an ordinal source reference warns even when it resolves"):
    val source: String =
      specHeader + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement 1 | manual | — |\n"
    val w4s: List[LintWarning] = warnsWith(lintText(source), "W4")
    assertEquals(w4s.length, 1)
    assert(headOr(w4s).message.contains("BY ORDINAL"), s"W4 wording: ${headOr(w4s).message}")

  test("W5: an impossible-state requirement enforced only by tests warns; strong enforcement silences it"):
    def impossibleDoc(enf: String): String =
      specHeader +
        "### Requirement: Bad states cannot be constructed\n\n" +
        "The system SHALL ensure a bad state cannot be constructed.\n\n" +
        "**Given** x\n**When** y\n**Then** z\n\n" +
        "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
        poHeader + s"| o | Requirement: Bad states cannot be constructed | $enf | — |\n"
    assertEquals(
      warnsWith(lintText(impossibleDoc("property test")), "W5").length,
      1,
      "weak enforcement of an impossibility claim must warn W5"
    )
    assert(
      warnsWith(lintText(impossibleDoc("type system")), "W5").isEmpty,
      "a type-system defence silences W5"
    )
    assert(
      warnsWith(lintText(impossibleDoc("tier-justified: no dependent types")), "W5").isEmpty,
      "tier-justified silences W5"
    )

  test("W6: formal contracts with a proof table but no bridge row warns; a bridge row silences it"):
    def fcDoc(bridge: Boolean): String =
      specHeader + reqBlock("Req Alpha") +
        "## Formal Contracts (Ring 6)\n\nline one\nline two\nline three\n\n" +
        poHeader +
        (if bridge then "| o | Requirement: Req Alpha | mirror | `KernelBridgeSpec.scala` |\n"
         else "| o | Requirement: Req Alpha | manual | — |\n")
    assertEquals(warnsWith(lintText(fcDoc(false)), "W6").length, 1)
    assert(warnsWith(lintText(fcDoc(true)), "W6").isEmpty)

  test("W7: a code-shaped token inside a clause warns only when the registry is present"):
    val source: String =
      specHeader +
        "### Requirement: Req Alpha\n\n" +
        "The system SHALL do it.\n\n" +
        "**Given** `AlphaSpec.scala` exists\n**When** `sbt core/compile` runs\n**Then** z\n\n" +
        "#### Scenario: s\n\n**Given** x\n**When** y\n**Then** z\n\n" +
        poHeader + "| o | Requirement: Req Alpha | manual | — |\n"
    val noRegistry: LintReport = lintText(source, absentContext)
    assert(warnsWith(noRegistry, "W7").isEmpty, "no registry — W7 is inapplicable")

    val ctx: LintContext       = registryContext(1, List("Schema"), List("SpecLintEngine"))
    val report: LintReport     = lintText(source, ctx)
    val w7s: List[LintWarning] = warnsWith(report, "W7")
    assert(w7s.length >= 2, s"expected source-file and build-command W7s, got: ${report.warnings}")
    assert(w7s.forall(_.line.isDefined), "W7 warnings carry a source line")

  // ── Applicability: unreadable facts and the absent-registry path ────
  // spec: spec-lint-engine — Scenario: Adversarial — an unreadable repository fact yields could-not-determine, not clean
  test("an unreadable applicability fact yields could-not-determine, never clean"):
    val source: String = specHeader + reqBlock("Req Alpha") + poHeader +
      "| o | Requirement: Req Alpha | manual | — |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    val unreadableFields: List[LintContext] = List(
      absentContext.copy(registry = FactRead.Unreadable("openspec/concepts: permission denied")),
      absentContext.copy(registryConcepts = FactRead.Unreadable("registry headings unreadable")),
      absentContext.copy(inventoryTypes = FactRead.Unreadable("concept-inventory.md unreadable")),
      absentContext.copy(schemaVersion = FactRead.Unreadable("schema.yaml unreadable")),
      absentContext.copy(profile = FactRead.Unreadable("capability-profile.md unreadable"))
    )
    unreadableFields.foreach { ctx =>
      SpecLintEngine.lint(doc, ctx, checkArtifacts = false, _ => false) match
        case Outcome.Undetermined(reason) =>
          assert(reason.nonEmpty, "the undetermined reason must say what could not be read")
        case other =>
          fail(s"an Unreadable fact must make the run Undetermined, got $other")
    }

  // ── Scenario: Happy path — an absent registry is reported inapplicable ─
  // spec: spec-lint-engine — Scenario: Happy path — an absent registry makes its dependent check inapplicable, and says so
  test("an absent registry makes the registry-dependent check inapplicable, and the report says so"):
    val source: String =
      "# Spec: F\n\n## ADDED Requirements\n\n" + reqBlock("Req Alpha") + poHeader +
        "| o | Requirement: Req Alpha | manual | — |\n"
    val report: LintReport = lintText(source, absentContext)
    assert(
      failsWith(report, CheckId.F10).isEmpty,
      "no registry — F10 does not apply, no failure is emitted"
    )
    assert(
      report.applicability.exists { case (k, v) => k.contains("17") && v.contains("N/A") },
      s"applicability must record the registry check as N/A: ${report.applicability}"
    )

  // ── reachabilityFold — the Ring-6 mirror target ─────────────────────
  // spec: spec-lint-engine — Contract: reachabilityFold
  test("reachabilityFold reports unenforced indices and the unresolvable-row count"):
    assertEquals(
      SpecLintEngine.reachabilityFold(3, List(0, -1, 2)),
      (List(1), 1)
    )
    assertEquals(SpecLintEngine.reachabilityFold(0, Nil), (List.empty[Int], 0))
    assertEquals(
      SpecLintEngine.reachabilityFold(2, List(0, 0, 1, -1, -1)),
      (List.empty[Int], 2)
    )
    assertEquals(
      SpecLintEngine.reachabilityFold(4, List(-1)),
      (List(0, 1, 2, 3), 1)
    )

  /** Does the source cell carry an ordinal reference beyond the requirement count? */
  private def hasOutOfRangeOrdinal(source: String, requirementCount: Int): Boolean =
    val ordRe = "(^|[^A-Za-z0-9])(R([0-9]+)|Requirement ([0-9]+))".r
    ordRe.findAllMatchIn(source).exists { m =>
      val n: Int = Option(m.group(3)).orElse(Option(m.group(4))).map(_.toInt).getOrElse(0)
      n > requirementCount
    }

  // ── Property: reachability-is-total ──────────────────────────────────
  // spec: spec-lint-engine — Property: reachability-is-total
  property("reachability-is-total", coverConfig):
    for doc <- genSpecDocument.forAll
        .cover(5, "zero-requirements", (d: SpecDocument) => d.requirements.isEmpty)
        .cover(
          30,
          "some-unreachable",
          (d: SpecDocument) => d.obligationRows.length < d.requirements.length
        )
        .cover(
          15,
          "ordinal-source",
          (d: SpecDocument) =>
            d.obligationRows.exists(r => r.source.matches(".*(^|[^A-Za-z])(R[0-9]+|Requirement [0-9]+).*"))
        )
        .cover(
          15,
          "dangling-typed-source",
          (d: SpecDocument) => d.obligationRows.exists(_.source.contains("absent-"))
        )
    yield SpecLintEngine.lint(doc, absentContext, checkArtifacts = false, _ => false) match
      case Outcome.Ran(report) =>
        val violations: List[String] = doc.requirements.flatMap { req =>
          val covered: Boolean = report.rowsResolvingTo(req.title).nonEmpty
          val reported: Boolean =
            report.failures.exists { f =>
              f.check == CheckId.F7 && f.message.contains(s"""requirement "${req.title}"""")
            } ||
              (!doc.hasProofObligations && report.failures.exists(_.check == CheckId.F4))
          if covered == reported then Some(s"${req.title}: covered=$covered reported=$reported")
          else None
        }
        Result
          .assert(violations.isEmpty)
          .log(s"reachability violations: ${violations.mkString("; ")}")
      case other =>
        Result.failure.log(s"expected Ran, got $other")

  // ── Property: unmatched-rows-are-reported-never-dropped ─────────────
  // spec: spec-lint-engine — Property: unmatched-rows-are-reported-never-dropped
  property("unmatched-rows-are-reported-never-dropped", coverConfig):
    for doc <- genSpecDocument.forAll
        .cover(
          30,
          "has-unresolvable-row",
          (d: SpecDocument) =>
            d.obligationRows.exists { r =>
              val s: String = r.source
              s == "Requirement" || s == "see above" || s == "the spec" || s == "TODO" ||
              s.contains("absent-") ||
              hasOutOfRangeOrdinal(s, d.requirements.length)
            }
        )
    yield SpecLintEngine.lint(doc, absentContext, checkArtifacts = false, _ => false) match
      case Outcome.Ran(report) =>
        Result
          .assert(
            report.resolvedRows.length + report.unresolvableRows.length ==
              doc.obligationRows.length
          )
          .log(
            s"resolved=${report.resolvedRows.length} + unresolvable=" +
              s"${report.unresolvableRows.length} != rows=${doc.obligationRows.length}"
          )
          .and(
            Result
              .assert(report.unresolvableRows.forall(r => doc.obligationRows.contains(r)))
              .log("every unresolvable row is a parsed row")
          )
      case other =>
        Result.failure.log(s"expected Ran, got $other")

  // ── Ring 5 survivor pinpoints ────────────────────────────────────────
  // Each test exists because a Stryker4s mutant survived the behavioural
  // suite: the assertion pins the exact semantics the mutant blurred.
  // Mutants not covered by a test here are documented as equivalent or
  // unreachable in implementation-progress.md (the `line < row.line`
  // visibility filters — a heading and a row can never share a line —
  // the `Unresolvable` fragment's `t == "Requirement"` spelling, which
  // `ordinalFragment` normalizes before the message is built; the
  // unreachable `else` kind arm of `namedExists`, whose Typed kinds are
  // closed by `typedPartRe`; the `codeIds.nonEmpty` short-circuit, which
  // an empty Set.contains already subsumes; the `hasPo` W5 gate, which
  // is implied by `coveredIdx.contains(i)`; and the `FactRead.Unreadable`
  // arms of `applicability`, which `lint`'s Undetermined early-return
  // makes unreachable).

  test("the applicability record renders every fact state in the predecessor's vocabulary"):
    val report: LintReport =
      lintText(specHeader, registryContext(2, List("tool"), List("Tool", "Extra")))
    assertEquals(
      report.applicability,
      Map(
        "schema"               -> "v14",
        "behavioural registry" -> "PRESENT (2 concepts)",
        "check 17 ALTITUDE"    -> "APPLIES",
        "type inventory"       -> "PRESENT (2 typed rows)",
        "check 6"              -> "APPLIES",
        "capability profile"   -> "ABSENT"
      )
    )

  test("the applicability record's absent and kit states use the predecessor's words"):
    val absent: LintReport = lintText(specHeader, absentContext)
    assertEquals(
      absent.applicability,
      Map(
        "schema"               -> "ABSENT",
        "behavioural registry" -> "ABSENT",
        "check 17 ALTITUDE"    -> "N/A (attested, not assumed)",
        "type inventory"       -> "ABSENT",
        "check 6"              -> "N/A",
        "capability profile"   -> "ABSENT"
      )
    )
    val kitted: LintReport = lintText(
      specHeader,
      absentContext.copy(profile = FactRead.Present(Some("munit")))
    )
    assertEquals(
      kitted.applicability("capability profile"),
      "PRESENT (kit: munit)"
    )
    val kitless: LintReport = lintText(
      specHeader,
      absentContext.copy(profile = FactRead.Present(None))
    )
    assertEquals(
      kitless.applicability("capability profile"),
      "PRESENT (no deterministic test kit)"
    )

  test("an unreadable fact makes the run Undetermined and names every reason"):
    val ctx: LintContext = absentContext.copy(
      registry = FactRead.Unreadable("no perms"),
      profile = FactRead.Unreadable("nfs gone")
    )
    SpecLintEngine.lint(
      SpecDocumentParser.parse("fixture", specHeader),
      ctx,
      checkArtifacts = false,
      (_: String) => false
    ) match
      case Outcome.Undetermined(reason) =>
        assertEquals(
          reason,
          "could not determine applicability — unreadable repository facts: no perms; nfs gone"
        )
      case other => fail(s"expected Undetermined, got $other")

  test("a four-field row is evaluated with empty enforcement and artifact cells"):
    val source: String    = specHeader + poHeader + "| ob | Requirement: x |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    val row: ObligationRow =
      doc.obligationRows.headOption.getOrElse(fail("the NF=4 row must be evaluated"))
    assertEquals(row.fieldCount, 4)
    assertEquals(row.enforcement, "")
    assertEquals(row.artifact, "")

  test("a five-field row reads the enforcement cell and leaves artifact empty"):
    val source: String    = specHeader + poHeader + "| ob | Requirement: x | manual |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    val row: ObligationRow =
      doc.obligationRows.headOption.getOrElse(fail("the NF=5 row must be evaluated"))
    assertEquals(row.fieldCount, 5)
    assertEquals(row.enforcement, "manual")
    assertEquals(row.artifact, "")
    assertEquals(
      doc.artifactRows.length,
      1,
      "the artifact pass tracks every NF>=5 data row"
    )

  test("a block heading inside Proof Obligations ends row evaluation"):
    val source: String = specHeader + poHeader +
      "| a | Requirement: x |\n" +
      "### Property: mid\n\n" +
      "| b | Requirement: x |\n" +
      "### Temporal: mid\n\n" +
      "| c | Requirement: x |\n" +
      "### Requirement: mid\n\n" +
      "| d | Requirement: x |\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assertEquals(
      doc.obligationRows.map(_.line),
      List(lineOf(source, "| a |")),
      "rows below a block heading are outside the Proof Obligations scan"
    )

  test("tab-only lines and indented comment-closes are not Formal Contracts content"):
    val source: String    = specHeader + "## Formal Contracts\n\n\t\n  -->\nbodyone\nbody two\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assertEquals(doc.formalContractsContentLines, 2)

  test("normative text joins the pre-Given body lines with single spaces"):
    val source: String = specHeader +
      "### Requirement: joining\nline one text\nThe system SHALL act.\n\n**Given** a setup\n"
    val doc: SpecDocument = SpecDocumentParser.parse("fixture", source)
    assertEquals(
      headOr(doc.requirements).normativeText,
      "line one text the system shall act. "
    )

  test("the W7 scan skips block headings even when the heading names a code shape"):
    val source: String = specHeader +
      "### Requirement: mentions **Given** and `req.scala`\n\n**Given** a state\n" +
      "### Property: mentions **Given** and `prop.scala`\n\n**Given** still\n" +
      "### Temporal: mentions **Given** and `temp.scala`\n\n**Given** again\n"
    val report: LintReport = lintText(
      source,
      registryContext(1, List("x"), List("X"))
    )
    assert(
      warnsWith(report, "W7").isEmpty,
      s"heading lines are never W7-scanned: ${report.warnings}"
    )

  test("a typed source under four characters is legitimate without a matching heading"):
    val source: String = specHeader + poHeader +
      "| o | Property: abc |\n"
    val report: LintReport = lintText(source)
    assert(
      failsWith(report, CheckId.F8).isEmpty,
      s"a three-character Property name exists by definition: ${report.failures}"
    )

  test("a four-character dangling Property name reports F8"):
    val source: String = specHeader + poHeader +
      "| o | Property: wxyz |\n"
    val report: LintReport = lintText(source)
    assertEquals(failsWith(report, CheckId.F8).length, 1)

  test("a typed Property source resolves against properties, never scenarios"):
    val source: String = specHeader +
      "### Property: real-prop\n\n**Generator strategy**: g\n\n" +
      "#### Scenario: scen-only\n\n" +
      poHeader +
      "| o | Property: real-prop |\n" +
      "| p | Property: scen-only |\n"
    val report: LintReport           = lintText(source)
    val f8s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F8)
    assertEquals(f8s.length, 1, s"only the property-name-in-scenarios row fails: $f8s")
    assertEquals(headOr(f8s).line, Some(lineOf(source, "| p |")))

  test("an ordinal equal to the requirement count resolves; one past it fails citing the count"):
    val source: String = specHeader +
      reqBlock("Alpha") + reqBlock("Beta") +
      poHeader +
      "| ok | Requirement 2 |\n" +
      "| past | R3 |\n" +
      "| past2 | Requirement 4 |\n"
    val report: LintReport           = lintText(source)
    val f6s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F6)
    assertEquals(f6s.length, 2, s"R3 and Requirement 4 are both out of range: $f6s")
    assert(
      f6s.exists(_.message == "Source cites Requirement 3 but the spec has 2"),
      s"R3 must render as 'Requirement 3': ${f6s.map(_.message)}"
    )
    assert(
      f6s.exists(_.message == "Source cites Requirement 4 but the spec has 2"),
      s"Requirement 4 keeps its fragment: ${f6s.map(_.message)}"
    )
    assert(
      failsWith(report, CheckId.F7).forall(_.message.contains("Alpha")),
      s"Requirement 2 covers Beta; only Alpha is unenforced: ${report.failures}"
    )

  test("a repeated backticked token warns once and unquoted prose never warns"):
    val source: String = specHeader +
      "### Requirement: dedup\n\n" +
      "**And** the change lands in foo.scala\n" +
      "**Given** `dup.scala` exists and `dup.scala` is reused\n"
    val report: LintReport = lintText(
      source,
      registryContext(1, List("x"), List("X"))
    )
    val w7s: List[LintWarning] = warnsWith(report, "W7")
    assertEquals(
      w7s.length,
      1,
      s"unquoted 'foo.scala' is prose; quoted 'dup.scala' warns once: $w7s"
    )
    assert(
      headOr(w7s).message.contains("'dup.scala'"),
      s"the warned token is the quoted one: ${w7s.map(_.message)}"
    )

  test("Formal Contracts content without a Proof Obligations section does not warn W6"):
    val source: String = specHeader +
      "### Requirement: solo\n\nThe system SHALL act.\n\n**Given** a setup\n" +
      "## Formal Contracts\n\nbody one\nbody two\nbody three\n"
    val report: LintReport = lintText(source)
    assert(
      warnsWith(report, "W6").isEmpty,
      s"W6 requires a Proof Obligations section: ${report.warnings}"
    )

  test("a test-only-enforced impossibility claim warns W5 with the predecessor's message"):
    val source: String = specHeader +
      "### Requirement: no bad states\n\n" +
      "The state cannot be constructed after close.\n\n**Given** a setup\n" +
      poHeader +
      "| o | Requirement: no bad states | property test | — |\n"
    val report: LintReport     = lintText(source)
    val w5s: List[LintWarning] = warnsWith(report, "W5")
    assertEquals(w5s.length, 1, s"expected exactly one W5: ${report.warnings}")
    assertEquals(
      headOr(w5s).message,
      "requirement \"no bad states\" claims a state is impossible but is " +
        "enforced only by tests — a type or smart constructor (ladder " +
        "tier 1–2) can make it unrepresentable; otherwise write " +
        "\"tier-justified: <why not>\" in the Enforcement cell"
    )

  test("a temporal block missing both event lines reports F5 twice; one present suppresses half"):
    val source: String = specHeader +
      "### Temporal: neither\n\nbody\n\n" +
      "### Temporal: trigger-only\n\n**Trigger event**: go\n\n" +
      "### Temporal: both\n\n**Trigger event**: go\n**Response event**: done\n\n"
    val report: LintReport           = lintText(source)
    val f5s: List[CheckOutcome.Fail] = failsWith(report, CheckId.F5)
    assertEquals(f5s.length, 3, s"two findings for 'neither', one for 'trigger-only': $f5s")
    assert(
      f5s.exists(_.message == "temporal \"neither\" has no **Trigger event** line"),
      s"missing trigger message: ${f5s.map(_.message)}"
    )
    assert(
      f5s.exists(_.message == "temporal \"neither\" has no **Response event** line"),
      s"missing response message: ${f5s.map(_.message)}"
    )
    assert(
      f5s.exists(_.message == "temporal \"trigger-only\" has no **Response event** line"),
      s"present trigger suppresses its half: ${f5s.map(_.message)}"
    )

  test("a row resolving only via a legitimate typed source is resolved, not unresolvable"):
    val source: String = specHeader +
      "### Property: real-prop\n\n**Generator strategy**: g\n\n" +
      poHeader +
      "| o | Property: real-prop |\n"
    val report: LintReport = lintText(source)
    assert(
      report.unresolvableRows.isEmpty,
      s"the typed-legitimate row resolves: ${report.unresolvableRows}"
    )
    assertEquals(report.resolvedRows.length, 1)

  test("checkArtifacts=false emits no F9 and covers requirements under F7"):
    val source: String = specHeader +
      reqBlock("Alpha") +
      poHeader +
      "| o | Requirement: Alpha | manual | `MissingSpec.scala` |\n"
    val report: LintReport = lintText(
      source,
      absentContext,
      checkArtifacts = false,
      (_: String) => false
    )
    assert(
      failsWith(report, CheckId.F9).isEmpty,
      s"the artifact pass is opt-in: ${report.failures}"
    )
    assertEquals(
      headOr(report.verdicts).check,
      CheckId.F7,
      "a covered requirement with no artifact run carries the F7 verdict check"
    )

  test("a requirement whose covering rows mix clean and dangling artifacts stays Bound"):
    val source: String = specHeader +
      reqBlock("Alpha") +
      poHeader +
      "| o1 | Requirement: Alpha | manual | `MissingSpec.scala` |\n" +
      "| o2 | Requirement: Alpha | manual | — |\n"
    val report: LintReport = lintText(
      source,
      absentContext,
      checkArtifacts = true,
      (_: String) => false
    )
    assertEquals(
      failsWith(report, CheckId.F9).length,
      1,
      s"only the dangling artifact row fails: ${report.failures}"
    )
    assertEquals(
      headOr(report.verdicts).verdict,
      Verdict.Bound,
      "one dangling artifact row suffices to leave the requirement Bound"
    )
    assertEquals(
      headOr(report.verdicts).check,
      CheckId.F9,
      "the artifact check ran — the verdict's vetting check is F9"
    )

  test("an out-of-range ordinal inside prose still cites the ordinal, not the whole cell"):
    val source: String = specHeader +
      reqBlock("Alpha") + reqBlock("Beta") +
      poHeader +
      "| o | see Requirement 9 |\n"
    val report: LintReport = lintText(source)
    assert(
      failsWith(report, CheckId.F6)
        .exists(_.message == "Source cites Requirement 9 but the spec has 2"),
      s"the ordinal fragment is extracted from the cell: ${report.failures.map(_.message)}"
    )

  test("a bare-number source is a generic F6, not an ordinal citation"):
    val source: String = specHeader +
      reqBlock("Alpha") +
      poHeader +
      "| o | 9 |\n"
    val report: LintReport = lintText(source)
    assert(
      failsWith(report, CheckId.F6)
        .exists(_.message == "Source names no resolvable reference: 9"),
      s"a bare number is no ordinal form: ${report.failures.map(_.message)}"
    )

  test("Formal Contracts with exactly two content lines is below the W6 threshold"):
    val source: String = specHeader +
      "### Requirement: solo\n\nThe system SHALL act.\n\n**Given** a setup\n" +
      poHeader +
      "| o | Requirement: solo |\n" +
      "## Formal Contracts\n\nbody one\nbody two\n"
    val report: LintReport = lintText(source)
    assert(
      warnsWith(report, "W6").isEmpty,
      s"W6 needs more than two content lines: ${report.warnings}"
    )

  test("a dangling Scenario source reports F8"):
    val source: String = specHeader + poHeader +
      "| o | Scenario: missing-x |\n"
    val report: LintReport = lintText(source)
    assertEquals(failsWith(report, CheckId.F8).length, 1)

  test("a property block without a Generator strategy line reports F3 with the predecessor's message"):
    val source: String = specHeader +
      "### Property: bare\n\nbody\n\n"
    val report: LintReport = lintText(source)
    assert(
      failsWith(report, CheckId.F3)
        .exists(_.message == "property \"bare\" has no **Generator strategy** line"),
      s"F3 message: ${report.failures.map(_.message)}"
    )

  test("a scenario heading declared below the row does not satisfy a Scenario source"):
    val source: String = specHeader +
      poHeader +
      "| o | Scenario: later-one |\n" +
      "#### Scenario: later-one\n\n**Given** a state\n"
    val report: LintReport = lintText(source)
    assertEquals(
      failsWith(report, CheckId.F8).length,
      1,
      s"the row resolves only against headings above it: ${report.failures}"
    )

  test("F1 message names the requirement and the missing normative clause"):
    val source: String =
      specHeader +
        "### Requirement: bare\n\n" +
        "**Given** a precondition\n**When** an action\n**Then** an outcome\n\n"
    val f1s: List[CheckOutcome.Fail] = failsWith(lintText(source), CheckId.F1)
    assertEquals(f1s.length, 1)
    assertEquals(
      headOr(f1s).message,
      "requirement \"bare\" has no SHALL/MUST before its first **Given**"
    )
