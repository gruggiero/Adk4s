package org.sinemenda.probatio.guard

import hedgehog.Gen
import hedgehog.Range

/**
 * Generators and materialisation helpers for the feature-freeze guard's
 * test oracle (spec 6, Step 2 — derived from the spec, not from any
 * implementation).
 *
 * Everything here is constructive: generated corpora are assembled from a
 * closed alphabet of specification clause shapes, materialised into
 * temporary `openspec/` trees, and resolved through the real filesystem —
 * the same probes the shipped `FixtureCorpus.resolve` performs.
 *
 * spec: feature-freeze-guard-integrity — Property: corpus-resolution-is-location-independent (generator strategy)
 * spec: feature-freeze-guard-integrity — Property: empty-corpus-never-passes (generator strategy)
 * spec: feature-freeze-guard-integrity — Property: verdict-stability-across-the-port (generator strategy)
 */
object GuardCorpusFixtures:

  // ════════════════════════════════════════════════════════════════════
  // Corpus materialisation — (relative path, content) fixtures written
  // under an `openspec/` tree's active or archived change area.
  // ════════════════════════════════════════════════════════════════════

  /** Write `fixtures` (relative path → content) under `specsDir`. */
  private def writeFixtures(specsDir: os.Path, fixtures: List[(String, String)]): Unit =
    fixtures.foreach { (rel: String, content: String) =>
      val target: os.Path = specsDir / os.RelPath(rel)
      os.makeDir.all(target / os.up)
      os.write.over(target, content)
    }

  /** Materialise a corpus into the active area: `openspecDir/changes/<name>/specs`. */
  def placeActive(
    openspecDir: os.Path,
    changeName: String,
    fixtures: List[(String, String)]
  ): os.Path =
    val specsDir: os.Path = openspecDir / "changes" / changeName / "specs"
    writeFixtures(specsDir, fixtures)
    specsDir

  /**
   * Materialise a corpus into the archived area:
   * `openspecDir/changes/archive/<prefix><name>/specs` — the archive's
   * date-prefix convention (`2026-09-20-complete-probatio-cutover`).
   */
  def placeArchived(
    openspecDir: os.Path,
    changeName: String,
    archivePrefix: String,
    fixtures: List[(String, String)]
  ): os.Path =
    val specsDir: os.Path =
      openspecDir / "changes" / "archive" / s"$archivePrefix$changeName" / "specs"
    writeFixtures(specsDir, fixtures)
    specsDir

  /** `f` runs with a fresh temporary `openspec/` root, removed afterwards. */
  def withTempOpenspec[A](f: os.Path => A): A =
    val tmp: os.Path = os.temp.dir(prefix = "guard-corpus")
    try f(tmp / "openspec")
    finally os.remove.all(tmp) // scalafix:ok DisableSyntax.NoKeywordFinally

  // ════════════════════════════════════════════════════════════════════
  // genCorpus — fixture lists of size 1–8 from a closed alphabet of
  // specification clause shapes (location-independence property).
  // ════════════════════════════════════════════════════════════════════

  /** Capability-directory names — a small closed alphabet. */
  private val genCapDir: Gen[String] =
    Gen.element("alpha", List("beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta"))

  private def uniqDirs(dirs: List[String]): List[String] =
    dirs.zipWithIndex.map { case (d, i) => s"$d-$i" }

  /**
   * A generated corpus: 1–8 fixtures, each `<cap>/spec.md` whose content is
   * a generated specification document. Edge cases fall out of the
   * alphabet: a single fixture, the maximum size, identical `spec.md`
   * names in different subdirectories.
   */
  val genCorpus: Gen[List[(String, String)]] =
    for
      n        <- Gen.int(Range.linear(1, 8))
      dirs     <- genCapDir.list(Range.singleton(n)).map(uniqDirs)
      contents <- genFixture.list(Range.singleton(n))
    yield dirs.zip(contents).map { case (d, c) => s"$d/spec.md" -> c }

  // ════════════════════════════════════════════════════════════════════
  // genEmptyCorpusCondition — the closed set of ways a corpus can be
  // absent (empty-corpus-never-passes property). No filtering.
  // ════════════════════════════════════════════════════════════════════

  enum EmptyCorpusCondition:
    /** The change name exists in neither the active nor the archived area. */
    case ChangeAbsent(changeName: String)
    /** A spec directory exists but contains no fixtures. */
    case LocationEmpty(changeName: String)
    /** A spec directory exists but cannot be read. */
    case LocationUnreadable(changeName: String)

  private val genChangeName: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(4, 12)).map(n => s"change-$n")

  val genEmptyCorpusCondition: Gen[EmptyCorpusCondition] =
    import EmptyCorpusCondition.*
    for
      name <- genChangeName
      cond <- Gen.element(
        ChangeAbsent(name),
        List(LocationEmpty(name), LocationUnreadable(name))
      )
    yield cond

  /**
   * Materialise an absence condition inside `openspecDir`. Returns the
   * directory the condition made unreadable, when it made one, so the
   * caller can restore permissions before cleanup.
   */
  def placeAbsence(
    openspecDir: os.Path,
    condition: EmptyCorpusCondition
  ): Option[os.Path] =
    import EmptyCorpusCondition.*
    condition match
      case ChangeAbsent(_) =>
        // `changes/` exists with an unrelated change — the name is probed
        // and genuinely absent, not merely unlooked-for.
        os.makeDir.all(openspecDir / "changes" / "some-other-change" / "specs")
        os.makeDir.all(openspecDir / "changes" / "archive")
        None
      case LocationEmpty(name) =>
        os.makeDir.all(openspecDir / "changes" / name / "specs")
        None
      case LocationUnreadable(name) =>
        val specsDir: os.Path = openspecDir / "changes" / name / "specs"
        os.makeDir.all(specsDir)
        os.perms.set(specsDir, "---------")
        Some(specsDir)

  // ════════════════════════════════════════════════════════════════════
  // genFixture — specification documents built from a closed alphabet of
  // clause shapes covering each check the closed identifier set names,
  // both satisfying and violating (verdict-stability property).
  //
  // Check coverage: F1 SHALL/MUST-before-Given, F2 negative-needs-scenario
  // (W3 fires when satisfied), F3 property generator-strategy, F4 proof-
  // obligations presence, F5 temporal trigger/response, F6 unresolvable
  // source, F7 uncovered requirement, F8 dangling typed source, F10 missing
  // behavioural-concepts section (only when the doc has ≥1 requirement and
  // the registry exists — the arms run with cwd=repoRoot where it does),
  // W1 vague words, W2 rows < requirements, W4 ordinal sources. F9 is
  // opt-in (`--artifacts`, post-implementation) and the gate never passes
  // it, so it is deliberately out of the generated alphabet.
  // ════════════════════════════════════════════════════════════════════

  private def weighted(percentTrue: Int): Gen[Boolean] =
    Gen.frequency1(percentTrue -> Gen.constant(true), (100 - percentTrue) -> Gen.constant(false))

  private val genTitle: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(4, 10)).map(t => s"requirement-$t")

  /** The clause-shape flags of one requirement block. */
  private final case class ReqShape(
    hasShall: Boolean,    // F1
    negative: Boolean,    // F2 trigger (W3 fires when a scenario is present)
    hasScenario: Boolean, // F2 needs one when negative
    vague: Boolean        // W1
  )

  private val genReqShape: Gen[ReqShape] =
    for
      hasShall    <- weighted(80)
      negative    <- weighted(30)
      hasScenario <- weighted(80)
      vague       <- weighted(15)
    yield ReqShape(hasShall, negative, hasScenario, vague)

  private def renderReqBlock(title: String, shape: ReqShape): String =
    val body: String =
      List(
        if shape.hasShall then "The system SHALL satisfy this requirement."
        else "The system does the thing.",
        if shape.negative then "The system MUST NOT regress the ported surface." else "",
        if shape.vague then "The outcome must be valid." else ""
      ).filter(_.nonEmpty).mkString("\n\n")
    val scenario: String =
      if shape.hasScenario then
        s"""#### Scenario: a path for $title
           |
           |**Given** a precondition
           |**When** an action
           |**Then** an observable outcome
           |""".stripMargin
      else ""
    s"""### Requirement: $title
       |
       |$body
       |
       |$scenario
       |""".stripMargin

  /** The clause-shape flag of one property block — toggles F3. */
  private final case class PropShape(hasStrategy: Boolean)
  private val genPropShape: Gen[PropShape] = weighted(80).map(PropShape(_))

  private def renderPropBlock(title: String, shape: PropShape): String =
    val strategy: String =
      if shape.hasStrategy then "**Generator strategy**: constructive over the closed alphabet." else ""
    s"""### Property: $title
       |
       |**Invariant**: the generated inputs satisfy the stated relation.
       |
       |$strategy
       |""".stripMargin

  /** The clause-shape flags of one temporal block — toggles F5's two lines. */
  private final case class TempShape(trigger: Boolean, response: Boolean)
  private val genTempShape: Gen[TempShape] =
    for
      trigger  <- weighted(80)
      response <- weighted(80)
    yield TempShape(trigger, response)

  private def renderTempBlock(title: String, shape: TempShape): String =
    val trig: String = if shape.trigger then "**Trigger event**: a signal arrives." else ""
    val resp: String = if shape.response then "**Response event**: the system acts." else ""
    s"""### Temporal: $title
       |
       |$trig
       |$resp
       |""".stripMargin

  /**
   * One obligation-row Source cell — the predecessor's resolution grammar:
   * a requirement title, an ordinal (in or out of range — F6), a typed
   * source naming an existing or absent heading (F8), or a bare word that
   * names nothing (F6).
   */
  private def genSourceCell(
    reqTitles: List[String],
    propTitles: List[String],
    scenTitles: List[String]
  ): Gen[String] =
    val byTitle: Gen[String] =
      if reqTitles.isEmpty then Gen.constant("Requirement")
      else Gen.element("Requirement", reqTitles).map(t => s"Requirement: $t")
    val ordinal: Gen[String] =
      Gen.int(Range.linear(1, reqTitles.size + 3)).map(n => s"Requirement $n")
    val typedExisting: Gen[String] =
      Gen.element("Property", List("Scenario")).flatMap { kind =>
        val pool: List[String] = if kind == "Property" then propTitles else scenTitles
        if pool.isEmpty then Gen.constant(s"$kind: absent-heading")
        else Gen.element("absent-heading", pool).map(n => s"$kind: $n")
      }
    val dangling: Gen[String] =
      Gen.string(Gen.alpha, Range.linear(4, 10)).map(n => s"Property: absent-$n")
    val bare: Gen[String] =
      Gen.element("Requirement", List("see above", "the spec"))
    Gen.frequency1(
      40 -> byTitle,
      15 -> ordinal,
      15 -> typedExisting,
      15 -> dangling,
      15 -> bare
    )

  /**
   * A generated specification document: the bats fixture shapes
   * (spec header, requirement blocks, property blocks, temporal blocks,
   * standalone scenario headings so F8 can be satisfied as well as
   * violated, and a proof-obligations table whose rows are drawn from the
   * predecessor's source grammar). `hasPo` toggles F4; row sources cover
   * F6/F7/F8/W4.
   */
  val genFixture: Gen[String] =
    for
      nReqs      <- Gen.int(Range.linear(0, 4))
      reqTitles  <- genTitle.list(Range.singleton(nReqs)).map(_.zipWithIndex.map((t, i) => s"$t-$i"))
      nProps     <- Gen.int(Range.linear(0, 2))
      propTitles <- genTitle.list(Range.singleton(nProps)).map(_.zipWithIndex.map((t, i) => s"property-$i-$t"))
      nScens     <- Gen.int(Range.linear(0, 2))
      scenTitles <- genTitle.list(Range.singleton(nScens)).map(_.zipWithIndex.map((t, i) => s"scenario-$i-$t"))
      nTemps     <- Gen.int(Range.linear(0, 2))
      reqShapes  <- genReqShape.list(Range.singleton(nReqs))
      propShapes <- genPropShape.list(Range.singleton(nProps))
      tempShapes <- genTempShape.list(Range.singleton(nTemps))
      hasPo      <- weighted(80)
      nRows      <- if hasPo then Gen.int(Range.linear(0, nReqs + 3)) else Gen.constant(0)
      sources    <- genSourceCell(reqTitles, propTitles, scenTitles).list(Range.singleton(nRows))
      // F10 fires only on has_registry && n_reqs > 0 && !has_concepts —
      // omitting the section on requirement-free docs would never exercise it.
      hasConcepts <- if nReqs > 0 then weighted(80) else Gen.constant(true)
    yield
      val poSection: String =
        if !hasPo then ""
        else
          val rows: String = sources.zipWithIndex
            .map { case (src, i) => s"| obligation-$i | $src | scenario test | — |" }
            .mkString("\n")
          s"""## Proof Obligations
             |
             || Obligation | Source | Enforcement | Artifact |
             ||------------|--------|-------------|----------|
             |$rows
             |""".stripMargin
      val reqSection: String =
        reqTitles.zip(reqShapes).map((t, s) => renderReqBlock(t, s)).mkString("\n")
      val propSection: String =
        if propTitles.isEmpty then ""
        else s"## Properties\n\n${propTitles.zip(propShapes).map((t, s) => renderPropBlock(t, s)).mkString("\n")}"
      val scenSection: String =
        if scenTitles.isEmpty then ""
        else
          scenTitles
            .map { t =>
              s"""#### Scenario: $t
                 |
                 |**Given** a precondition
                 |**When** an action
                 |**Then** an observable outcome
                 |""".stripMargin
            }
            .mkString("\n")
      val tempSection: String =
        if tempShapes.isEmpty then ""
        else s"## Temporals\n\n${tempShapes.zipWithIndex.map((s, i) => renderTempBlock(s"temporal-$i", s)).mkString("\n")}"
      List(
        specLintHeader(hasConcepts),
        "## ADDED Requirements\n\n",
        reqSection,
        propSection,
        scenSection,
        tempSection,
        poSection
      ).mkString("\n")

  // The header shape the bats fixtures use — Concepts Used tables plus the
  // spec title. Kept here rather than imported so the guard's fixtures are
  // self-contained (SpecLintFixtures is a different package's internals).
  // `withBehavioralConcepts=false` drops the behavioural section so F10
  // (missing Concepts-Used on a doc with requirements) is generated too.
  private def specLintHeader(withBehavioralConcepts: Boolean): String =
    val behavioral: String =
      if withBehavioralConcepts then
        """## Concepts Used (behavioral)
          |
          || Concept | Role here | File |
          ||---------|-----------|------|
          || (none) | generated fixture | — |
          |
          |""".stripMargin
      else ""
    s"""# Spec: Generated Fixture
       |
       |${behavioral}## Concepts Used (from inventory)
       |
       || Concept | Kind | Package |
       ||---------|------|---------|
       |
       |""".stripMargin

  // ════════════════════════════════════════════════════════════════════
  // Output parsing — the emitted check identifiers a spec-lint arm's
  // report carries (the closed-set comparison inputs).
  // ════════════════════════════════════════════════════════════════════

  // Both arms emit `FAIL F<id>[ line <n>]: <msg>` — line-numbered findings
  // carry ` line N`, summary findings (F4, F10) do not. The `:` anchor is
  // required: a space-only pattern misses the summary shape entirely.
  private val failIdRe: scala.util.matching.Regex = "^FAIL (F[0-9]+)( line [0-9]+)?:".r

  /** The `FAIL F#` identifiers emitted in a spec-lint arm's text output. */
  def emittedCheckIds(output: String): Set[String] =
    output.linesIterator
      .map(_.trim)
      .flatMap(line => failIdRe.findFirstMatchIn(line).map(_.group(1)))
      .toSet
