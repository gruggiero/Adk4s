package org.sinemenda.probatio.guard

import hedgehog.*
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ProbatioSuite
import org.sinemenda.probatio.migration.ChangeLocation
import org.sinemenda.probatio.migration.ChangeLocationGens
import org.sinemenda.probatio.migration.ChangeLocationGens.AbsentFixture
import org.sinemenda.probatio.migration.ChangeLocationGens.ChangePlacement
import org.sinemenda.probatio.migration.ChangeLocationGens.Placement

/**
 * Non-goals guard spec — feature-freeze contract, dependency boundary,
 * oracle immutability (R-X1, R-X2, R-X3, R-ARCH1).
 *
 * This is the GUARD spec: it verifies the port does not extend checks,
 * alter verdicts, add workflow features, change the schema beyond the
 * rename, or introduce forbidden dependencies. The guard is enforced by
 * tests + the dependency-lint build rule + the bats oracle.
 *
 * spec: port-scanner-to-probatio/non-goals-guard — Requirement: The port does not extend checks, alter verdicts, or add workflow features
 * spec: port-scanner-to-probatio/non-goals-guard — Requirement: The port does not change the schema for copy-based consumers beyond the rename
 * spec: port-scanner-to-probatio/non-goals-guard — Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect
 */

// ══ Types (R-X1, R-X2, R-X3) ═════════════════════════════════════════════
//
// The feature-freeze types (`FeatureFreezeViolation`, `FeatureFreezeVerdict`,
// `KnownCheckId`, `FixtureVerdict`) live in `GuardTypes.scala` — they are
// part of the Ring-5 move-to-main set and must not be defined inside a test
// class.

final case class DependencyModule(
  organization: String,
  name: String
)

object AllowedDependencySet:
  val allowed: Set[DependencyModule] = Set(
    DependencyModule("com.lihaoyi", "os-lib"),
    DependencyModule("com.lihaoyi", "upickle"),
    DependencyModule("com.lihaoyi", "upack"),
    DependencyModule("com.lihaoyi", "ujson"),
    DependencyModule("com.lihaoyi", "mainargs"),
    DependencyModule("org.scalameta", "munit"),
    DependencyModule("org.scalameta", "scalameta"),
    DependencyModule("qa.hedgehog", "hedgehog")
  )
  val forbidden: Set[DependencyModule] = Set(
    DependencyModule("org.typelevel", "cats-core"),
    DependencyModule("org.typelevel", "cats-effect"),
    DependencyModule("org.typelevel", "cats-effect-std"),
    DependencyModule("co.fs2", "fs2-core"),
    DependencyModule("co.fs2", "fs2-io"),
    DependencyModule("org.llm4s", "core"),
    DependencyModule("org.business4s", "workflows4s-core"),
    DependencyModule("org.adk4s", "adk4s-core"),
    DependencyModule("org.adk4s", "adk4s-orchestration"),
    DependencyModule("org.adk4s", "structured-llm"),
    DependencyModule("org.scalacheck", "scalacheck")
  )
  def isAllowed(module: DependencyModule): Boolean =
    allowed.contains(module) ||
      allowed.exists(a =>
        a.organization == module.organization &&
          module.name.startsWith(a.name)
      )
  def isForbidden(module: DependencyModule): Boolean =
    forbidden.exists(f =>
      f.organization == module.organization &&
        (f.name == module.name || module.name.startsWith(f.name + "-"))
    ) || isForbiddenOrg(module)
  def isForbiddenOrg(module: DependencyModule): Boolean =
    module.organization == "org.typelevel" ||
      module.organization == "co.fs2" ||
      module.organization == "org.llm4s" ||
      module.organization == "org.business4s" ||
      module.organization == "org.scalacheck" ||
      module.organization == "org.adk4s"

enum WorkflowSubproject:
  case ProbatioCore, ProbatioCli, SbtProbatio, ProbatioVerified

object WorkflowSubproject:
  val all: List[WorkflowSubproject] = values.toList

enum DependencyBoundaryResult:
  case Clean(subproject: WorkflowSubproject)
  case Violation(subproject: WorkflowSubproject, module: DependencyModule)

final case class HookPayload(
  decision: String,
  hookSpecificOutput: Map[String, String]
)

enum PayloadStabilityResult:
  case Stable
  case Unstable(field: String, before: String, after: String)

enum OracleImmutabilityResult:
  case Immutable(commit: String)
  case Modified(commit: String, file: String)

// ══ Spec ══════════════════════════════════════════════════════════════════

final class NonGoalsGuardSpec extends ProbatioSuite:

  import FeatureFreezeViolation.*
  import FeatureFreezeVerdict.*

  // The corpus scenario + verdict-stability property run the predecessor
  // bash and the ported native binary as subprocesses.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  private val stabilityConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(40))

  /**
   * The repository root via `git rev-parse` — NOT `os.pwd`: the Ring-5
   * Stryker sandbox runs the suite from a `target/stryker4s-*` directory,
   * and `rev-parse` still resolves the real worktree root (spec-1's
   * debugging trail; same rule as `SpecLintParitySpec.repoRoot`).
   */
  private val repoRoot: os.Path =
    val result: os.CommandResult = os
      .proc("git", "rev-parse", "--show-toplevel")
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    if result.exitCode == 0 then os.Path(result.out.text().trim)
    else os.pwd

  // ══ Helpers: pattern-match-based type checks (no isInstanceOf) ═══════════

  private def isRejected(v: FeatureFreezeVerdict): Boolean = v match
    case Rejected(_, _) => true
    case Accepted(_)    => false

  private def isClean(r: DependencyBoundaryResult): Boolean = r match
    case DependencyBoundaryResult.Clean(_)        => true
    case DependencyBoundaryResult.Violation(_, _) => false

  private def isImmutable(r: OracleImmutabilityResult): Boolean = r match
    case OracleImmutabilityResult.Immutable(_)   => true
    case OracleImmutabilityResult.Modified(_, _) => false

  // ══ R-X1: Feature-freeze contract scenarios ══════════════════════════════

  // ── Scenario: New lint check rejected as out of scope
  // spec: non-goals-guard — Scenario: New lint check rejected as out of scope
  test("F11 lint check is rejected as a feature-freeze violation"):
    val verdict: FeatureFreezeVerdict = FeatureFreezeGuard.reviewFeatureFreeze(NewLintCheck("F11"))
    verdict match
      case Rejected(NewLintCheck(id), _) => assertEquals(id, "F11")
      case other                         => fail(s"expected Rejected(NewLintCheck), got $other")

  // ── Scenario: Verdict alteration on a fixture rejected
  // spec: non-goals-guard — Scenario: Verdict alteration on a fixture rejected
  test("verdict alteration on a fixture is rejected as a behavior delta"):
    val alteration: FeatureFreezeViolation = VerdictAlteration(
      fixture = "evidence-ledger-v1.jsonl",
      expected = "bound",
      actual = "discharged"
    )
    val verdict: FeatureFreezeVerdict = FeatureFreezeGuard.reviewFeatureFreeze(alteration)
    assert(isRejected(verdict))

  // ── Scenario: New workflow feature rejected
  // spec: non-goals-guard — Scenario: New workflow feature rejected
  test("new gate tier is rejected as a workflow feature"):
    val feature: FeatureFreezeViolation = NewWorkflowFeature("post-completion blocking tier")
    val verdict: FeatureFreezeVerdict   = FeatureFreezeGuard.reviewFeatureFreeze(feature)
    assert(isRejected(verdict))

  // ── Scenario: Pure port accepted (no behavior delta)
  test("a pure port with no behavior delta is accepted"):
    val resolution: CorpusResolution =
      FixtureCorpus.resolve(FeatureFreezeGuard.corpusChangeName, repoRoot / "openspec")
    FeatureFreezeGuard.guardOutcome(resolution, Nil) match
      case Outcome.Ran(Accepted(_)) => ()
      case other                    => fail(s"expected Ran(Accepted(corpus)), got $other")

  // ══ R-X2: Copy-based consumer payload stability scenarios ════════════════

  // ── Scenario: Copy-based consumer payload unchanged after rename
  // spec: non-goals-guard — Scenario: Copy-based consumer payload unchanged after rename
  test("hook payload is byte-stable across the v14 rename"):
    val before: HookPayload = HookPayload(
      decision = "block",
      hookSpecificOutput = Map("additionalContext" -> "spec-lint: 0 FAIL, 38 WARN")
    )
    val result: PayloadStabilityResult = comparePayloads(before, before)
    assertEquals(result, PayloadStabilityResult.Stable)

  // ── Scenario: Schema template or ring definition change rejected
  // spec: non-goals-guard — Scenario: Schema template or ring definition change rejected
  test("schema template change is rejected as out of scope"):
    val violation: FeatureFreezeViolation = NewWorkflowFeature("schema template modification")
    val verdict: FeatureFreezeVerdict     = FeatureFreezeGuard.reviewFeatureFreeze(violation)
    assert(isRejected(verdict))

  // ── Scenario: sbt 1.x to 2.x build migration rejected
  // spec: non-goals-guard — Scenario: sbt 1.x to 2.x build migration rejected
  test("sbt 1.x to 2.x migration is rejected as out of scope"):
    val violation: FeatureFreezeViolation = NewWorkflowFeature("sbt 2.x build migration")
    val verdict: FeatureFreezeVerdict     = FeatureFreezeGuard.reviewFeatureFreeze(violation)
    assert(isRejected(verdict))

  // ══ R-X3: Allowed-dependency set scenarios ═══════════════════════════════

  // ── Scenario: Allowed dependency accepted
  // spec: non-goals-guard — Scenario: Allowed dependency accepted
  test("os-lib dependency is accepted by the allowed set"):
    val module: DependencyModule = DependencyModule("com.lihaoyi", "os-lib")
    assert(AllowedDependencySet.isAllowed(module))
    assert(!AllowedDependencySet.isForbidden(module))

  // ── Scenario: cats dependency rejected (adversarial)
  // spec: non-goals-guard — Scenario: cats dependency rejected (adversarial)
  test("cats-core dependency is rejected by the forbidden set"):
    val module: DependencyModule = DependencyModule("org.typelevel", "cats-core")
    assert(AllowedDependencySet.isForbidden(module))
    assert(!AllowedDependencySet.isAllowed(module))

  // ── Scenario: cats-effect dependency rejected (adversarial)
  // spec: non-goals-guard — Scenario: cats-effect dependency rejected (adversarial)
  test("cats-effect dependency is rejected by the forbidden set"):
    val module: DependencyModule = DependencyModule("org.typelevel", "cats-effect")
    assert(AllowedDependencySet.isForbidden(module))

  // ── Scenario: adk4s module dependency rejected (adversarial)
  // spec: non-goals-guard — Scenario: adk4s module dependency rejected (adversarial)
  test("adk4s-core dependency is rejected by the forbidden set"):
    val module: DependencyModule = DependencyModule("org.adk4s", "adk4s-core")
    assert(AllowedDependencySet.isForbidden(module))

  // ── Scenario: ScalaCheck dependency rejected (adversarial)
  // spec: non-goals-guard — Scenario: ScalaCheck dependency rejected (adversarial)
  test("ScalaCheck dependency is rejected — Hedgehog is the detected framework"):
    val module: DependencyModule = DependencyModule("org.scalacheck", "scalacheck")
    assert(AllowedDependencySet.isForbidden(module))

  // ── Scenario: fs2 dependency rejected (adversarial)
  // spec: non-goals-guard — Scenario: fs2 dependency rejected (adversarial)
  test("fs2-core dependency is rejected — probatio-cli uses blocking I/O"):
    val module: DependencyModule = DependencyModule("co.fs2", "fs2-core")
    assert(AllowedDependencySet.isForbidden(module))

  // ══ Spec 6: feature-freeze-guard-integrity — corpus resolution ═══════════
  //
  // The guard's corpus used to be read from one hardcoded active-area
  // path; archiving the change moved it, the corpus read as empty, and the
  // guard failed outright. These tests pin the repair: resolution across
  // both areas, could-not-determine on every empty or unresolvable
  // corpus, the closed check-identifier set, and verdict stability
  // measured against the predecessor.

  private def isUndetermined(o: Outcome[?]): Boolean = o match
    case Outcome.Undetermined(_) => true
    case _                     => false

  private def isUpheld(o: Outcome[FeatureFreezeVerdict]): Boolean = o match
    case Outcome.Ran(Accepted(_)) => true
    case _                        => false

  // ── Scenario: Happy path — an active change's corpus resolves
  // spec: feature-freeze-guard-integrity — Scenario: Happy path — an active change's corpus resolves
  test("an active change's corpus resolves"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      val fixtures: List[(String, String)] = List(
        "alpha/spec.md" -> "# Spec: A\n",
        "beta/spec.md"  -> "# Spec: B\n"
      )
      GuardCorpusFixtures.placeActive(openspec, "change-x", fixtures)
      FixtureCorpus.resolve("change-x", openspec) match
        case CorpusResolution.Resolved(corpus) =>
          assertEquals(corpus.specs.sorted, fixtures.map(_._1).sorted)
        case other => fail(s"expected Resolved, got $other")
    }

  // ── Scenario: Happy path — an archived change's corpus resolves
  // spec: feature-freeze-guard-integrity — Scenario: Happy path — an archived change's corpus resolves
  test("an archived change's corpus resolves"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      val fixtures: List[(String, String)] = List(
        "alpha/spec.md" -> "# Spec: A\n",
        "beta/spec.md"  -> "# Spec: B\n"
      )
      GuardCorpusFixtures.placeArchived(openspec, "change-x", "2026-09-20-", fixtures)
      FixtureCorpus.resolve("change-x", openspec) match
        case CorpusResolution.Resolved(corpus) =>
          assertEquals(corpus.specs.sorted, fixtures.map(_._1).sorted)
        case other => fail(s"expected Resolved, got $other")
    }

  // ── Scenario: Adversarial — a corpus present in neither location is not silently accepted
  // spec: feature-freeze-guard-integrity — Scenario: Adversarial — a corpus present in neither location is not silently accepted
  test("a corpus present in neither location reports not-found naming every location searched"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      os.makeDir.all(openspec / "changes" / "other-change" / "specs")
      os.makeDir.all(openspec / "changes" / "archive" / "2026-01-01-unrelated" / "specs")
      FixtureCorpus.resolve("absent-change", openspec) match
        case CorpusResolution.NotFound(searched) =>
          assertEquals(searched, FixtureCorpus.searchedLocations("absent-change", openspec))
          assert(searched.nonEmpty, "a not-found must name at least one searched location")
        case other => fail(s"expected NotFound, got $other")
    }

  // ── Surgical kills (Ring 5 survivors): archive matching, spec.md filter
  // A non-matching archived directory must not resolve even when it holds
  // fixtures; a bare-name archived directory does; a specs directory
  // holding files that are not spec.md does not.
  test("an archived change holding fixtures under a non-matching name is not the corpus"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      GuardCorpusFixtures.placeArchived(
        openspec,
        "other-change",
        "2026-01-01-",
        List("cap/spec.md" -> "# Spec: X\n")
      )
      FixtureCorpus.resolve("absent-change", openspec) match
        case CorpusResolution.NotFound(searched) => assert(searched.nonEmpty)
        case other                               => fail(s"expected NotFound, got $other")
    }

  test("an archived change directory named exactly the change resolves"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      GuardCorpusFixtures.placeArchived(
        openspec,
        "change-x",
        "",
        List("cap/spec.md" -> "# Spec: X\n")
      )
      FixtureCorpus.resolve("change-x", openspec) match
        case CorpusResolution.Resolved(corpus) => assertEquals(corpus.specs, List("cap/spec.md"))
        case other                             => fail(s"expected Resolved, got $other")
    }

  // A change name that is a *suffix* of an archived name must not resolve
  // that archive's corpus — `probatio-cutover` is not
  // `complete-probatio-cutover`, and silently taking its corpus is exactly
  // the "present in neither location is not silently accepted" failure.
  test("an archived directory whose name merely ends in the change name is not the corpus"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      GuardCorpusFixtures.placeArchived(
        openspec,
        "complete-probatio-cutover",
        "2026-01-01-",
        List("cap/spec.md" -> "# Spec: X\n")
      )
      FixtureCorpus.resolve("probatio-cutover", openspec) match
        case CorpusResolution.NotFound(searched) => assert(searched.nonEmpty)
        case other                               => fail(s"expected NotFound, got $other")
      FixtureCorpus.resolve("complete-probatio-cutover", openspec) match
        case CorpusResolution.Resolved(corpus) => assertEquals(corpus.specs, List("cap/spec.md"))
        case other                             => fail(s"expected Resolved, got $other")
    }

  test("a specs directory holding no spec.md fixtures does not resolve"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      val specsDir: os.Path = openspec / "changes" / "change-x" / "specs"
      os.makeDir.all(specsDir)
      os.write(specsDir / "readme.txt", "not a fixture")
      FixtureCorpus.resolve("change-x", openspec) match
        case CorpusResolution.NotFound(searched) =>
          assert(searched.contains(specsDir), s"must name the specs dir probed: $searched")
        case other => fail(s"expected NotFound, got $other")
    }

  test("the resolution projections report the located and absent cases"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      GuardCorpusFixtures.placeActive(openspec, "change-x", List("cap/spec.md" -> "# Spec: X\n"))
      val resolved: CorpusResolution = FixtureCorpus.resolve("change-x", openspec)
      assert(resolved.isResolved)
      assert(!resolved.isNotFound)
      resolved match
        case CorpusResolution.Resolved(corpus) =>
          resolved.corpusOption match
            case Some(c) => assertEquals(c.specs, corpus.specs)
            case None    => fail("corpusOption must be Some on a resolved corpus")
        case other => fail(s"expected Resolved, got $other")
      val absent: CorpusResolution = FixtureCorpus.resolve("absent-change", openspec)
      assert(absent.isNotFound)
      assert(!absent.isResolved)
      assertEquals(absent.corpusOption, None)
    }

  // ── Scenario: Adversarial — an unresolvable corpus does not report the freeze upheld
  // spec: feature-freeze-guard-integrity — Scenario: Adversarial — an unresolvable corpus does not report the freeze upheld
  test("an unresolvable corpus does not report the freeze upheld"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      GuardCorpusFixtures.placeAbsence(
        openspec,
        GuardCorpusFixtures.EmptyCorpusCondition.ChangeAbsent("absent-change")
      )
      val resolution: CorpusResolution = FixtureCorpus.resolve("absent-change", openspec)
      val outcome: Outcome[FeatureFreezeVerdict] =
        FeatureFreezeGuard.guardOutcome(resolution, Nil)
      outcome match
        case Outcome.Undetermined(reason) =>
          assert(reason.contains("absent-change"), s"undetermined must name the corpus: $reason")
        case other => fail(s"expected Undetermined, got $other")
      assert(!isUpheld(outcome), "could-not-determine must never be freeze-upheld")
    }

  // ── Scenario: Adversarial — a resolved but empty corpus does not report the freeze upheld
  // spec: feature-freeze-guard-integrity — Scenario: Adversarial — a resolved but empty corpus does not report the freeze upheld
  test("a resolved-but-empty corpus does not report the freeze upheld"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      GuardCorpusFixtures.placeAbsence(
        openspec,
        GuardCorpusFixtures.EmptyCorpusCondition.LocationEmpty("empty-change")
      )
      val resolution: CorpusResolution = FixtureCorpus.resolve("empty-change", openspec)
      resolution match
        case CorpusResolution.NotFound(searched) =>
          assert(
            searched.exists((p: os.Path) => p.toString.contains("empty-change")),
            s"not-found must name the empty corpus location: $searched"
          )
        case other => fail(s"an empty specs dir must not resolve, got $other")
      val outcome: Outcome[FeatureFreezeVerdict] =
        FeatureFreezeGuard.guardOutcome(resolution, Nil)
      assert(isUndetermined(outcome), s"expected Undetermined, got $outcome")
      assert(!isUpheld(outcome), "could-not-determine must never be freeze-upheld")
    }

  // ── Scenario: Happy path — the ported implementation emits only known identifiers
  // spec: feature-freeze-guard-integrity — Scenario: Happy path — the ported implementation emits only known identifiers
  test("the ported implementation emits only known check identifiers over the corpus"):
    val binary: os.Path = repoRoot / "workflow" / "cli" / "target" / "native-image" / "probatio"
    if !os.exists(binary) then
      fail(s"probatio binary not found at $binary — closed-set check FAILS, does not skip")
    val resolution: CorpusResolution =
      FixtureCorpus.resolve(FeatureFreezeGuard.corpusChangeName, repoRoot / "openspec")
    resolution match
      case CorpusResolution.Resolved(corpus) =>
        corpus.specs.foreach { (spec: String) =>
          val (_, emitted) = probatioSpecLint(corpus.fixturePath(spec).toString)
          val unknown: Set[String] = FeatureFreezeGuard.unknownCheckIds(emitted)
          assert(unknown.isEmpty, s"unknown check identifiers emitted on $spec: $unknown")
        }
      case other => fail(s"expected Resolved, got $other")
    // Non-vacuity: the archived corpus is conformant and may emit zero
    // FAIL identifiers — an empty emitted set makes the closed-set check
    // vacuous. A violating document guarantees real inputs, and F4's
    // summary shape (`FAIL F4:` — no line number) exercises the other
    // emitted-id format end-to-end.
    val violatingDoc: String =
      """# Spec: Violating
        |
        |## ADDED Requirements
        |
        |### Requirement: r
        |The system frobs.
        |
        |#### Scenario: s
        |**Given** a precondition
        |**When** an action
        |**Then** an observable outcome
        |""".stripMargin
    val (_, emittedOnViolation) = probatioSpecLintText(violatingDoc)
    assert(emittedOnViolation.nonEmpty, "a violating document must emit FAIL identifiers")
    assert(
      emittedOnViolation.contains("F4"),
      s"the summary-format identifier F4 must be collected, got $emittedOnViolation"
    )
    assert(
      FeatureFreezeGuard.unknownCheckIds(emittedOnViolation).isEmpty,
      s"unknown check identifiers emitted: $emittedOnViolation"
    )

  // The emitted-id parser must collect BOTH output shapes the arms
  // produce: line-numbered (`FAIL F7 line 30: …`) and summary
  // (`FAIL F4: …` — no line number). Missing the summary shape lets a new
  // check escape the closed-set guard entirely.
  test("emitted check identifiers collect the line-numbered and summary FAIL formats"):
    val output: String =
      """  spec-lint: CONTEXT — repository facts.
        |FAIL F1 line 12: requirement 'r' does not state SHALL or MUST
        |FAIL F4: this spec has no "## Proof Obligations" section
        |WARN W2 line 3: proof-obligations rows (0) < requirements (1)
        |FAIL F11: a check the closed set does not know
        |""".stripMargin
    assertEquals(
      GuardCorpusFixtures.emittedCheckIds(output),
      Set("F1", "F4", "F11")
    )

  // ── Scenario: Adversarial — an unknown identifier is a violation
  // spec: feature-freeze-guard-integrity — Scenario: Adversarial — an unknown identifier is a violation
  test("an unknown check identifier is a freeze violation naming that identifier"):
    val emitted: Set[String]      = Set("F1", "F5", "F11")
    val unknown: Set[String]      = FeatureFreezeGuard.unknownCheckIds(emitted)
    assertEquals(unknown, Set("F11"))
    unknown.foreach { (id: String) =>
      FeatureFreezeGuard.reviewFeatureFreeze(NewLintCheck(id)) match
        case Rejected(NewLintCheck(named), reason) =>
          assertEquals(named, "F11")
          assert(reason.contains("F11"), s"violation must name the identifier: $reason")
        case other => fail(s"expected Rejected(NewLintCheck), got $other")
    }

  // ── Scenario: Adversarial — a differing verdict is reported with both values
  // spec: feature-freeze-guard-integrity — Scenario: Adversarial — a differing verdict is reported with both values
  test("a differing verdict is reported with both values"):
    GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
      GuardCorpusFixtures.placeActive(openspec, "change-x", List("cap/spec.md" -> "# Spec: S\n"))
      val resolution: CorpusResolution = FixtureCorpus.resolve("change-x", openspec)
      val outcome: Outcome[FeatureFreezeVerdict] = FeatureFreezeGuard.guardOutcome(
        resolution,
        List(VerdictAlteration("cap/spec.md", "clean", "findings"))
      )
      outcome match
        case Outcome.Finding(description) =>
          assert(description.contains("cap/spec.md"), s"must name the fixture: $description")
          assert(description.contains("clean"), s"must name the predecessor's verdict: $description")
          assert(description.contains("findings"), s"must name the ported verdict: $description")
        case other => fail(s"expected Finding, got $other")
    }

  // ── Compile-Negative: A fixture corpus built from an empty list
  // spec: feature-freeze-guard-integrity — Compile-Negative: A fixture corpus built from an empty list
  test("compile-negative: a fixture corpus built from an empty list does not compile"):
    val err: String = compileErrors("FixtureCorpus(Nil, os.pwd)")
    assert(err.nonEmpty, "FixtureCorpus(Nil, origin) should not compile — the constructor is private")

  // ── Compile-Negative: A not-found resolution without the locations searched
  // spec: feature-freeze-guard-integrity — Compile-Negative: A not-found resolution without the locations searched
  test("compile-negative: a not-found resolution without searched locations does not compile"):
    val err: String = compileErrors("CorpusResolution.NotFound()")
    assert(err.nonEmpty, "NotFound() should not compile — the variant requires the searched list")

  // ── Compile-Negative: A freeze verdict constructed for an unresolved corpus
  // spec: feature-freeze-guard-integrity — Compile-Negative: A freeze verdict constructed for an unresolved corpus
  test("compile-negative: a freeze verdict for an unresolved corpus does not compile"):
    val err: String = compileErrors("FeatureFreezeVerdict.Accepted(CorpusResolution.NotFound(Nil))")
    assert(err.nonEmpty, "Accepted(NotFound) should not compile — the accepted variant takes a resolved corpus")

  // ── Property: corpus-resolution-is-location-independent
  // spec: feature-freeze-guard-integrity — Property: corpus-resolution-is-location-independent
  property("corpus-resolution-is-location-independent"):
    for corpus <- GuardCorpusFixtures.genCorpus.forAll
    yield
      GuardCorpusFixtures.withTempOpenspec { (active: os.Path) =>
        GuardCorpusFixtures.withTempOpenspec { (archived: os.Path) =>
          GuardCorpusFixtures.placeActive(active, "change-x", corpus)
          GuardCorpusFixtures.placeArchived(archived, "change-x", "2026-01-01-", corpus)
          (
            FixtureCorpus.resolve("change-x", active),
            FixtureCorpus.resolve("change-x", archived)
          ) match
            case (CorpusResolution.Resolved(a), CorpusResolution.Resolved(b)) =>
              Result
                .assert(a.specs == b.specs)
                .log(s"active ${a.specs} != archived ${b.specs}")
            case other =>
              Result.failure.log(s"expected both resolved, got $other")
        }
      }

  // ── Property: empty-corpus-never-passes
  // spec: feature-freeze-guard-integrity — Property: empty-corpus-never-passes
  property("empty-corpus-never-passes"):
    for condition <- GuardCorpusFixtures.genEmptyCorpusCondition.forAll
    yield
      GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
        val unreadable: Option[os.Path] = GuardCorpusFixtures.placeAbsence(openspec, condition)
        try
          val name: String = condition match
            case GuardCorpusFixtures.EmptyCorpusCondition.ChangeAbsent(n)        => n
            case GuardCorpusFixtures.EmptyCorpusCondition.LocationEmpty(n)       => n
            case GuardCorpusFixtures.EmptyCorpusCondition.LocationUnreadable(n)  => n
          val outcome: Outcome[FeatureFreezeVerdict] = FeatureFreezeGuard.guardOutcome(
            FixtureCorpus.resolve(name, openspec),
            Nil
          )
          Result
            .assert(isUndetermined(outcome) && !isUpheld(outcome))
            .log(s"expected could-not-determine and not-upheld on $condition, got $outcome")
        finally // scalafix:ok DisableSyntax.NoKeywordFinally
          unreadable.foreach((d: os.Path) => os.perms.set(d, "rwx------"))
      }

  // ══ Compile-Negative obligations ═════════════════════════════════════════

  // ── Compile-Negative: A new F11 check in the ported spec-lint
  // spec: non-goals-guard — Compile-Negative: A new F11 check in the ported spec-lint
  test("compile-negative: F11 is not in the KnownCheckId enum"):
    assert(!KnownCheckId.isKnown("F11"))
    assert(KnownCheckId.isKnown("F1"))
    assert(KnownCheckId.isKnown("F10"))

  // ── Compile-Negative: A new gate tier in the ported gate
  // spec: non-goals-guard — Compile-Negative: A new gate tier in the ported gate
  test("compile-negative: 'post-completion blocking tier' is a NewWorkflowFeature"):
    val violation: FeatureFreezeViolation = NewWorkflowFeature("post-completion blocking tier")
    val verdict: FeatureFreezeVerdict     = FeatureFreezeGuard.reviewFeatureFreeze(violation)
    assert(isRejected(verdict))

  // ══ Properties (Ring 3) ══════════════════════════════════════════════════

  // ── Scenario: Happy path — every corpus fixture's verdict agrees
  // spec: feature-freeze-guard-integrity — Scenario: Happy path — every fixture's verdict agrees
  test("every corpus fixture's verdict agrees with the predecessor"):
    val resolution: CorpusResolution =
      FixtureCorpus.resolve(FeatureFreezeGuard.corpusChangeName, repoRoot / "openspec")
    val disagreements: List[VerdictAlteration] = resolution match
      case CorpusResolution.Resolved(corpus) =>
        corpus.specs.flatMap { (spec: String) =>
          val path: String              = corpus.fixturePath(spec).toString
          val (expected, _)             = bashSpecLint(path)
          val (actual, _)               = probatioSpecLint(path)
          if actual.verdict == expected.verdict && actual.warnings == expected.warnings then None
          else Some(VerdictAlteration(spec, expected.verdict, actual.verdict))
        }
      case CorpusResolution.NotFound(_) => Nil
    FeatureFreezeGuard.guardOutcome(resolution, disagreements) match
      case Outcome.Ran(Accepted(_)) => ()
      case other                    => fail(s"expected Ran(Accepted(corpus)), got $other")

  // ── Property: verdict-stability-across-the-port (spec 6)
  // spec: feature-freeze-guard-integrity — Property: verdict-stability-across-the-port
  // Model-based: the predecessor bash spec-lint runs as the model; the
  // ported native binary is the implementation under test. Only verdicts
  // and warnings are compared — no timing is observed. The limit is kept
  // low: each iteration costs two subprocess invocations.
  property("verdict-stability-across-the-port", stabilityConfig):
    for fixture <- GuardCorpusFixtures.genFixture.forAll
    yield
      val expected: FixtureVerdict          = bashSpecLintText(fixture)
      val (actual, emitted): (FixtureVerdict, Set[String]) = probatioSpecLintText(fixture)
      val unknown: Set[String]              = FeatureFreezeGuard.unknownCheckIds(emitted)
      Result
        .assert(unknown.isEmpty)
        .log(s"unknown check identifiers emitted by the ported arm: $unknown")
        .and(Result.diff(actual, expected)((a, e) => a.verdict == e.verdict && a.warnings == e.warnings))

  // ── Property: dependency boundary is closed
  // spec: non-goals-guard — Property: dependency boundary is closed
  property("dependency boundary is closed"):
    for
      subproject <- Gen.element(WorkflowSubproject.all(0), WorkflowSubproject.all.drop(1)).forAll
      forbiddenList = AllowedDependencySet.forbidden.toList
      forbidden <- Gen.element(forbiddenList(0), forbiddenList.drop(1)).forAll
    yield
      val result: DependencyBoundaryResult = checkClasspath(subproject, forbidden)
      Result.assert(isClean(result))

  // ── Property: oracle immutability at every migration step
  // spec: non-goals-guard — Property: oracle immutability at every migration step
  // The oracle check results are cached so git show is invoked once per
  // commit, not once per Hedgehog iteration.
  property("oracle immutability at every migration step"):
    val commits: List[String] = migrationCommitsOnMain
    val oracleResults: Map[String, OracleImmutabilityResult] =
      commits.map(c => c -> checkOracleImmutability(c)).toMap
    for commit <- Gen.element(commits(0), commits.drop(1)).forAll
    yield
      val result: OracleImmutabilityResult = oracleResults(commit)
      Result.assert(isImmutable(result))

  // ══ spec: archive-safe-fixtures — the shared archive-aware resolver ════

  // ── Scenario: Happy path — an active change resolves
  // spec: archive-safe-fixtures — Scenario: Happy path — an active change resolves
  test("an active change resolves to its active-area directory"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      val dir: os.Path = openspec / "changes" / "new-change"
      ChangeLocationGens.writeFixtures(dir, List("specs/a/spec.md", "fixtures/b.json"))
      ChangeLocation.resolve("new-change", openspec) match
        case ChangeLocation.Active(found) => assertEquals(found, dir)
        case other                        => fail(s"expected Active($dir), got $other")
    }

  // ── Scenario: Happy path — an archived change resolves to the same fixtures
  // spec: archive-safe-fixtures — Scenario: Happy path — an archived change resolves to the same fixtures
  test("an archived change resolves to the same fixtures"):
    val placed: List[String] =
      List("specs/a/spec.md", "fixtures/b/control.json", "fixtures/a/spec.md")
    ChangeLocationGens.withTempOpenspec { (activeOpenspec: os.Path) =>
      val activeDir: os.Path = activeOpenspec / "changes" / "new-change"
      ChangeLocationGens.writeFixtures(activeDir, placed)
      ChangeLocationGens.withTempOpenspec { (archivedOpenspec: os.Path) =>
        val archivedDir: os.Path =
          archivedOpenspec / "changes" / "archive" / "2026-08-01-new-change"
        ChangeLocationGens.writeFixtures(archivedDir, placed)
        val activeFixtures: List[String] =
          os.walk(activeDir)
            .filter((p: os.Path) => os.isFile(p))
            .map((p: os.Path) => p.relativeTo(activeDir).toString)
            .toList
            .sorted
        ChangeLocation.resolve("new-change", archivedOpenspec) match
          case ChangeLocation.Archived(found, date) =>
            assertEquals(date, Some("2026-08-01"))
            assertEquals(found, archivedDir)
            val foundFixtures: List[String] =
              os.walk(found)
                .filter((p: os.Path) => os.isFile(p))
                .map((p: os.Path) => p.relativeTo(found).toString)
                .toList
                .sorted
            assertEquals(foundFixtures, activeFixtures)
          case other => fail(s"expected Archived($archivedDir), got $other")
      }
    }

  // ── Scenario: Adversarial — a change that is in neither place is absent
  // spec: archive-safe-fixtures — Scenario: Adversarial — a change in neither place is not silently accepted
  test("a change in neither place is Absent, naming every location searched"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      ChangeLocationGens.writeFixtures(
        openspec / "changes" / "archive" / "2026-01-15-other-change",
        List("specs/spec.md")
      )
      ChangeLocation.resolve("absent-change", openspec) match
        case ChangeLocation.Absent(searched) =>
          assertEquals(searched, ChangeLocation.searchedLocations("absent-change", openspec))
          assert(
            searched.contains(openspec / "changes" / "absent-change"),
            s"searched must name the active location: $searched"
          )
          assert(
            searched.contains(openspec / "changes" / "archive"),
            s"searched must name the archive root: $searched"
          )
        case other => fail(s"expected Absent, got $other")
    }

  // ── Scenario: Edge case — a change that is archived twice resolves to the most recent
  // spec: archive-safe-fixtures — Scenario: Edge case — a change archived more than once resolves to the latest
  test("a change archived twice resolves to the most recent, naming both locations"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      val older: os.Path = openspec / "changes" / "archive" / "2026-03-10-dup-change"
      val newer: os.Path = openspec / "changes" / "archive" / "2026-09-01-dup-change"
      ChangeLocationGens.writeFixtures(older, List("specs/old.md"))
      ChangeLocationGens.writeFixtures(newer, List("specs/new.md", "fixtures/x.txt"))
      ChangeLocation.resolve("dup-change", openspec) match
        case ChangeLocation.Archived(dir, date) =>
          assertEquals(dir, newer)
          assertEquals(date, Some("2026-09-01"))
          assertEquals(
            os.walk(dir)
              .filter((p: os.Path) => os.isFile(p))
              .map((p: os.Path) => p.relativeTo(dir).toString)
              .toList
              .sorted,
            List("fixtures/x.txt", "specs/new.md")
          )
        case other => fail(s"expected Archived($newer), got $other")
      val searched: List[os.Path] = ChangeLocation.searchedLocations("dup-change", openspec)
      assert(
        searched.contains(older) && searched.contains(newer),
        s"the resolution must name both archive locations: $searched"
      )
    }

  // spec: archive-safe-fixtures — Requirement: Test code locates a change through the archive-aware resolver
  // Ring-5 coverage: a bare `archive/<name>` entry carries no date and
  // resolves with `date = None` (this shape was uncovered).
  test("a bare archive entry resolves with no date"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      val dir: os.Path = openspec / "changes" / "archive" / "old-change"
      ChangeLocationGens.writeFixtures(dir, List("specs/a/spec.md"))
      ChangeLocation.resolve("old-change", openspec) match
        case ChangeLocation.Archived(found, date) =>
          assertEquals(found, dir)
          assertEquals(date, None)
        case other => fail(s"expected Archived($dir, None), got $other")
    }

  // spec: archive-safe-fixtures — Requirement: Test code locates a change through the archive-aware resolver
  // Ring-5 coverage: a dated entry outranks a bare one — the (Some, None)
  // ordering arm is observable only when both shapes exist.
  test("a dated archive entry outranks a bare archive entry"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      val bare: os.Path  = openspec / "changes" / "archive" / "mixed-change"
      val dated: os.Path = openspec / "changes" / "archive" / "2026-05-05-mixed-change"
      ChangeLocationGens.writeFixtures(bare, List("specs/bare.md"))
      ChangeLocationGens.writeFixtures(dated, List("specs/dated.md"))
      ChangeLocation.resolve("mixed-change", openspec) match
        case ChangeLocation.Archived(dir, date) =>
          assertEquals(dir, dated)
          assertEquals(date, Some("2026-05-05"))
        case other => fail(s"expected Archived($dated), got $other")
      val searched: List[os.Path] = ChangeLocation.searchedLocations("mixed-change", openspec)
      assert(
        searched.contains(bare) && searched.contains(dated),
        s"the resolution must name both archive locations: $searched"
      )
    }

  // spec: archive-safe-fixtures — Scenario: Adversarial — a change in neither place is not silently accepted
  // Ring-8 remediation: a name that cannot denote a change — empty,
  // separator-carrying, `.`/`..`, or the reserved `archive` segment —
  // must never resolve to a directory. `resolve("archive")` would
  // otherwise return the archive ROOT as an active change.
  test("an unlocatable change name resolves Absent, probing nothing"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      List("", ".", "archive", "..", "a/b").foreach { (name: String) =>
        ChangeLocation.resolve(name, openspec) match
          case ChangeLocation.Absent(searched) =>
            assertEquals(searched, Nil, s"'$name' is unlocatable — nothing was probed")
          case other =>
            fail(s"unlocatable name '$name' must resolve Absent, got $other")
        assertEquals(
          ChangeLocation.searchedLocations(name, openspec),
          Nil,
          s"'$name' is unlocatable — searchedLocations reports no probes"
        )
      }
    }

  // spec: archive-safe-fixtures — Scenario: Adversarial — a change in neither place is not silently accepted
  // Ring-5 coverage: an archive entry that is a FILE, not a directory,
  // must not resolve (the os.isDir(d) guard inside archiveMatches).
  test("a non-directory archive entry is not resolved"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      val archiveRoot: os.Path = openspec / "changes" / "archive"
      os.makeDir.all(archiveRoot)
      os.write(archiveRoot / "file-change", "a file, not a directory\n")
      ChangeLocation.resolve("file-change", openspec) match
        case ChangeLocation.Absent(searched) =>
          assert(
            !searched.contains(archiveRoot / "file-change"),
            s"a non-directory entry must not be a searched location: $searched"
          )
          assert(
            searched.contains(archiveRoot),
            s"searched must name the archive root: $searched"
          )
        case other => fail(s"expected Absent, got $other")
    }

  // ── Property: resolution-is-location-independent
  // spec: archive-safe-fixtures — Property: resolution-is-location-independent
  property("resolution-is-location-independent"):
    for
      p <- ChangeLocationGens.genChangePlacement.forAll
        .cover(25, "active", (c: ChangePlacement) => c.placement == Placement.InActiveArea)
        .cover(25, "archived once", (c: ChangePlacement) =>
          c.placement match
            case Placement.ArchivedOnce(_) => true
            case _                         => false
        )
        .cover(25, "archived twice", (c: ChangePlacement) =>
          c.placement match
            case Placement.ArchivedTwice(_, _) => true
            case _                             => false
        )
        .cover(10, "single fixture", (c: ChangePlacement) => c.fixtures.length == 1)
        .cover(10, "same-named fixture in different subdirs", (c: ChangePlacement) =>
          c.fixtures.map((f: String) => f.substring(f.lastIndexOf('/'))).distinct.length <
            c.fixtures.length
        )
    yield ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      val expected: List[String] = ChangeLocationGens.place(openspec, p)
      def checkFixtures(dir: os.Path): Result =
        val found: List[String] =
          os.walk(dir)
            .filter((f: os.Path) => os.isFile(f))
            .map((f: os.Path) => f.relativeTo(dir).toString)
            .toList
            .sorted
        Result
          .assert(found == expected.sorted)
          .log(s"resolved $dir; expected ${expected.sorted}, found $found")
      (p.placement, ChangeLocation.resolve(p.changeName, openspec)) match
        case (Placement.InActiveArea, ChangeLocation.Active(dir))           => checkFixtures(dir)
        case (Placement.ArchivedOnce(_), ChangeLocation.Archived(dir, _))   => checkFixtures(dir)
        case (Placement.ArchivedTwice(_, _), ChangeLocation.Archived(dir, _)) => checkFixtures(dir)
        case (_, other) =>
          Result.failure.log(s"placement ${p.placement} resolved as $other")
    }

  // ── Property: absent-names-every-searched-location
  // spec: archive-safe-fixtures — Property: absent-names-every-searched-location
  property("absent-names-every-searched-location"):
    for
      f <- ChangeLocationGens.genAbsentName.forAll
        .cover(40, "suffix-colliding sibling", (a: AbsentFixture) =>
          a.others.exists((other, _) => other.endsWith(s"-${a.name}"))
        )
        .cover(40, "an archived sibling", (a: AbsentFixture) =>
          a.others.exists((_, archived) => archived)
        )
    yield ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      ChangeLocationGens.placeAbsentFixture(openspec, f)
      ChangeLocation.resolve(f.name, openspec) match
        case ChangeLocation.Absent(searched) =>
          Result
            .assert(searched.contains(openspec / "changes" / f.name))
            .log(s"searched must name the active location: $searched")
            .and(
              Result
                .assert(searched.contains(openspec / "changes" / "archive"))
                .log(s"searched must name the archive root: $searched")
            )
        case other => Result.failure.log(s"expected Absent, got $other")
    }

  // ══ Implementations (Step 3 — GREEN run) ═════════════════════════════════

  // `reviewFeatureFreeze` and `guardOutcome` live in `FeatureFreezeGuard`
  // (spec 6): a proposed violation is always rejected — acceptance names
  // the resolved corpus it was earned against and is reachable only
  // through `guardOutcome`, which owns the corpus run.

  /**
   * Compares two hook payloads for byte-stability across the rename (R-X2).
   * Returns `Stable` if all fields match, `Unstable` naming the first
   * divergent field.
   */
  def comparePayloads(before: HookPayload, after: HookPayload): PayloadStabilityResult =
    if before.decision != after.decision then
      PayloadStabilityResult.Unstable("decision", before.decision, after.decision)
    else if before.hookSpecificOutput != after.hookSpecificOutput then
      val diffKey: String = before.hookSpecificOutput.keySet
        .find(k => before.hookSpecificOutput.get(k) != after.hookSpecificOutput.get(k))
        .getOrElse("unknown")
      PayloadStabilityResult.Unstable(
        s"hookSpecificOutput.$diffKey",
        before.hookSpecificOutput.getOrElse(diffKey, ""),
        after.hookSpecificOutput.getOrElse(diffKey, "")
      )
    else PayloadStabilityResult.Stable

  /**
   * Run one spec-lint arm on a specification document. The tools take a
   * change directory, not a spec file — the document is wrapped in a
   * temporary `<dir>/specs/spec.md` shape. Exits 0 (clean), 1 (findings),
   * or 2 (undetermined); any other exit is surfaced as `error-<code>` so a
   * crashed arm can never masquerade as a legitimate undetermined. The
   * verdict string, the warning set and the emitted `FAIL F#` check
   * identifiers are parsed from stdout.
   *
   * Returns the fixture verdict plus the emitted check identifiers —
   * the closed-set comparison's inputs (spec 6).
   */
  private def runSpecLintArmText(
    text: String,
    label: String,
    command: List[String]
  ): (FixtureVerdict, Set[String]) =
    val tmp: os.Path = os.temp.dir(prefix = "non-goals-spec-lint")
    try
      os.makeDir(tmp / "specs")
      os.write(tmp / "specs" / "spec.md", text)
      val result: os.CommandResult = os
        .proc(command ++ List(tmp.toString))
        .call(
          check = false,
          stdout = os.Pipe,
          stderr = os.Pipe,
          cwd = repoRoot
        )
      val verdict: String = result.exitCode match
        case 0     => "clean"
        case 1     => "findings"
        case 2     => "undetermined"
        case other => s"error-$other"
      val output: String        = result.out.text()
      val warnings: Set[String] = output
        .linesIterator
        .map(_.trim)
        .filter(_.startsWith("WARN "))
        .toSet
      (FixtureVerdict(label, verdict, warnings), GuardCorpusFixtures.emittedCheckIds(output))
    finally os.remove.all(tmp) // scalafix:ok DisableSyntax.NoKeywordFinally

  /** Run one spec-lint arm on a fixture file path. */
  private def runSpecLintArm(fixture: String, command: List[String]): (FixtureVerdict, Set[String]) =
    runSpecLintArmText(os.read(os.Path(fixture)), fixture, command)

  /**
   * Runs the predecessor (bash) spec-lint on a fixture and returns the
   * verdict and emitted identifiers. The true predecessor is
   * `spec-lint.sh.predecessor.bak` — `spec-lint.sh` is now a shim execing
   * the probatio binary.
   */
  def bashSpecLint(fixture: String): (FixtureVerdict, Set[String]) =
    val script: os.Path =
      repoRoot / "openspec" / "schemas" / "verified-scala3" / "scanner" / "spec-lint.sh.predecessor.bak"
    if !os.exists(script) then
      (FixtureVerdict(fixture, "undetermined", Set(s"predecessor not found at $script")), Set.empty)
    else runSpecLintArm(fixture, List("bash", script.toString))

  /** The predecessor arm on document text rather than a fixture path. */
  def bashSpecLintText(text: String): FixtureVerdict =
    val script: os.Path =
      repoRoot / "openspec" / "schemas" / "verified-scala3" / "scanner" / "spec-lint.sh.predecessor.bak"
    if !os.exists(script) then FixtureVerdict("<generated>", "undetermined", Set(s"predecessor not found"))
    else runSpecLintArmText(text, "<generated>", List("bash", script.toString))._1

  /**
   * Runs the ported (Scala) spec-lint on a fixture and returns the verdict
   * and emitted identifiers. The spec-lint subcommand is ported — this
   * invokes the probatio native binary.
   */
  def probatioSpecLint(fixture: String): (FixtureVerdict, Set[String]) =
    val binary: os.Path = repoRoot / "workflow" / "cli" / "target" / "native-image" / "probatio"
    if !os.exists(binary) then
      (FixtureVerdict(fixture, "undetermined", Set(s"probatio binary not found at $binary")), Set.empty)
    else runSpecLintArm(fixture, List(binary.toString, "spec-lint"))

  /** The ported arm on document text rather than a fixture path. */
  def probatioSpecLintText(text: String): (FixtureVerdict, Set[String]) =
    val binary: os.Path = repoRoot / "workflow" / "cli" / "target" / "native-image" / "probatio"
    if !os.exists(binary) then
      (FixtureVerdict("<generated>", "undetermined", Set(s"probatio binary not found")), Set.empty)
    else runSpecLintArmText(text, "<generated>", List(binary.toString, "spec-lint"))

  /**
   * Checks a subproject's classpath for a forbidden dependency (R-X3,
   * R-ARCH1). Returns `Clean` if the build.sbt's `isForbiddenDependency`
   * function covers the forbidden module's organization, `Violation` if
   * it does not (meaning the forbidden dep could slip through the build
   * rule).
   *
   * This reads `build.sbt` and verifies that the `isForbiddenDependency`
   * function mentions the forbidden module's organization. If the org is
   * not mentioned in the function, the build rule would NOT reject the
   * forbidden dep — that's a real violation. The build-level
   * `dependency-lint` rule is the primary enforcement; this test-level
   * check verifies the rule's coverage is complete.
   */
  def checkClasspath(
    subproject: WorkflowSubproject,
    module: DependencyModule
  ): DependencyBoundaryResult =
    val buildFile: os.Path = repoRoot / "build.sbt"
    if !os.exists(buildFile) then DependencyBoundaryResult.Clean(subproject)
    else
      val buildText: String = os.read(buildFile)
      // The build.sbt's isForbiddenDependency function checks for forbidden
      // organizations. Verify that the function mentions this module's org.
      // If it doesn't, the build rule has a coverage gap — a real violation.
      val orgCoveredByBuildRule: Boolean = buildText.contains(module.organization)
      if orgCoveredByBuildRule then
        // The build rule covers this organization — the dependency-lint
        // task would reject any dependency from this org on a workflow/*
        // classpath. The classpath is clean.
        DependencyBoundaryResult.Clean(subproject)
      else
        // The build rule does NOT mention this forbidden organization —
        // a forbidden dep from this org could slip through the build rule.
        // This is a real violation of the dependency boundary.
        DependencyBoundaryResult.Violation(subproject, module)

  /**
   * The migration commits on main (for the oracle-immutability property).
   *
   * Enumerates the commits on the current branch that are part of the
   * port-scanner-to-probatio migration, starting from the "created change"
   * commit. Each commit is a known checkpoint where the oracle must be
   * unmodified. Pre-migration commits (before the change was created) are
   * excluded — the oracle immutability invariant applies only during the
   * migration, not to the entire branch history.
   */
  def migrationCommitsOnMain: List[String] =
    // Find the "created change" commit — the first commit of the migration
    val createdResult: os.CommandResult = os
      .proc(
        "git",
        "rev-list",
        "--reverse",
        "--grep=created change",
        "--format=%H",
        "probatio/porting"
      )
      .call(check = false, stdout = os.Pipe)
    val createdCommit: Option[String] = createdResult.out
      .text()
      .linesIterator
      .filter(_.matches("[0-9a-f]{40}"))
      .toList
      .headOption
    createdCommit match
      case Some(base) =>
        // Get all commits from the created-change commit to HEAD
        val result: os.CommandResult = os
          .proc(
            "git",
            "rev-list",
            s"$base^..HEAD"
          )
          .call(check = false, stdout = os.Pipe)
        result.out.text().linesIterator.filter(_.nonEmpty).toList
      case None =>
        // Fallback: if "created change" commit not found, use the last 5 commits
        val result: os.CommandResult = os
          .proc(
            "git",
            "rev-list",
            "-5",
            "HEAD"
          )
          .call(check = false, stdout = os.Pipe)
        result.out.text().linesIterator.filter(_.nonEmpty).toList

  /**
   * Checks that the bats oracle is unmodified at a given commit (R-X1).
   *
   * Compares the oracle files at the given commit against the oracle files
   * at the migration start commit. Returns `Immutable` if they match,
   * `Modified` naming the first divergent file.
   */
  def checkOracleImmutability(commit: String): OracleImmutabilityResult =
    val oracleDir: os.SubPath = os.sub / "openspec" / "schemas" / "verified-scala3" / "tests"
    val oracleDirStr: String  = oracleDir.toString
    // Get the list of bats files at this commit
    val lsResult: os.CommandResult = os
      .proc(
        "git",
        "show",
        s"$commit:$oracleDirStr"
      )
      .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    if lsResult.exitCode != 0 then OracleImmutabilityResult.Immutable(commit)
    else
      // Compare each bats file at this commit against the working tree
      val files: List[String] = lsResult.out
        .text()
        .linesIterator
        .filter(_.endsWith(".bats"))
        .toList
      val modified: Option[String] = files.find { file =>
        val showResult: os.CommandResult = os
          .proc(
            "git",
            "show",
            s"$commit:$oracleDirStr/$file"
          )
          .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        val atCommit: String     = showResult.out.text()
        val workingTree: os.Path = repoRoot / oracleDir / file
        if !os.exists(workingTree) then true // file was deleted — that's a modification
        else atCommit != os.read(workingTree)
      }
      modified match
        case Some(file) => OracleImmutabilityResult.Modified(commit, file)
        case None       => OracleImmutabilityResult.Immutable(commit)
