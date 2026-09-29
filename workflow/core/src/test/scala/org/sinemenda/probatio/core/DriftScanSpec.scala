package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

/**
 * Tests for the DriftScan pure function.
 *
 * Migrated to the spec-3 contract: `InstallRootScan` carries an
 * `InstallRootState` (Absent / PresentNoStamp / Stamped / Unreadable), and
 * `DriftScan.installRoots` is the fixed-arity six-root set.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: Instruction drift is detected across all install roots
 * spec: live-fact-banner — Requirement: Instruction drift is scanned across every install root the workflow searches
 */
final class DriftScanSpec extends ProbatioSuite:

  // ── Scenario: a root with a matching stamp produces no drift warning
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a root with a matching stamp produces no drift warning
  test("a root with a matching stamp produces no drift warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(14, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.warnings.isEmpty, s"Expected no warnings, got ${result.warnings}")

  // ── Scenario: a root with a mismatched stamp produces a drift warning
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a root with a mismatched stamp produces a drift warning
  test("a root with a mismatched stamp produces a drift warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.warnings.nonEmpty, "Expected a drift warning for mismatched stamp")
    result.warnings.headOption match
      case Some(w: DriftWarning.VersionMismatch) =>
        assertEquals(w.expected, 14)
        assertEquals(w.found, 13)
      case other => fail(s"Expected VersionMismatch, got $other")

  // ── Scenario: no installed skills produces an explicit line
  // spec: port-scanner-to-probatio/probatio-core — Scenario: no installed skills produces an explicit line
  test("no installed skills produces an explicit noSkillInstalled line"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Absent),
      InstallRootScan(".agents/skills", InstallRootState.Absent)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.noSkillInstalled, "Expected noSkillInstalled=true when no root has a stamp")

  // ── Scenario: a pre-rename stamp is treated as drift with a migration message
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a pre-rename stamp is treated as drift with a migration message
  test("a pre-rename stamp produces a PreRenameStamp warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.Legacy))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.warnings.nonEmpty, "Expected a drift warning for pre-rename stamp")
    result.warnings.headOption match
      case Some(w: DriftWarning.PreRenameStamp) =>
        assertEquals(w.expected, Some(14))
        assertEquals(w.found, 13)
        assert(
          w.message.contains("migrate") || w.message.contains("rename"),
          s"Expected migration message, got: ${w.message}"
        )
      case other => fail(s"Expected PreRenameStamp, got $other")

  // ── Scenario: silence about drift is never emitted (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: silence about drift is never emitted (adversarial)
  test("drift is never silently passed over — warnings non-empty when drift exists"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.warnings.nonEmpty, "drift must produce a non-empty warning list")

  // ── Scenario: silence about checking is never emitted (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: silence about checking is never emitted (adversarial)
  test("no skills installed is never silently passed over — noSkillInstalled is true"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Absent)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.noSkillInstalled, "no skills installed must produce noSkillInstalled=true")

  // ── Mutation-killing: a matching stamp does NOT set noSkillInstalled
  test("a root with a matching stamp sets noSkillInstalled=false"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(14, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(!result.noSkillInstalled, "installed skill must set noSkillInstalled=false")

  // ── Mutation-killing: a mismatched stamp sets noSkillInstalled=false
  test("a root with a mismatched stamp sets noSkillInstalled=false"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(!result.noSkillInstalled, "installed (but mismatched) skill must set noSkillInstalled=false")

  // ── Mutation-killing: version mismatch message contains expected and found
  test("version mismatch warning message contains expected and found values"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    result.warnings.headOption match
      case Some(w: DriftWarning.VersionMismatch) =>
        assert(w.message.contains("14"), s"message must contain expected=14, got: ${w.message}")
        assert(w.message.contains("13"), s"message must contain found=13, got: ${w.message}")
        assert(w.message.contains(".claude/skills"), s"message must contain root path, got: ${w.message}")
      case other => fail(s"Expected VersionMismatch, got $other")

  // ── Mutation-killing: pre-rename message contains migration text
  test("pre-rename warning message contains migration text with expected version"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.Legacy))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    result.warnings.headOption match
      case Some(w: DriftWarning.PreRenameStamp) =>
        assert(w.message.contains("14"), s"message must contain expected=14, got: ${w.message}")
        assert(w.message.contains("13"), s"message must contain found=13, got: ${w.message}")
        assert(w.message.contains("probatio-schema"), s"message must contain probatio-schema, got: ${w.message}")
        assert(
          w.message.contains("verified-scala3-schema"),
          s"message must contain verified-scala3-schema, got: ${w.message}"
        )
      case other => fail(s"Expected PreRenameStamp, got $other")

  // ── Mutation-killing: a non-preRename mismatched stamp does NOT produce PreRenameStamp
  test("a non-preRename mismatched stamp produces VersionMismatch, not PreRenameStamp"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    result.warnings.headOption match
      case Some(_: DriftWarning.VersionMismatch) => assert(true)
      case other                                 => fail(s"Expected VersionMismatch for non-preRename, got $other")

  // ── Mutation-killing: a pre-rename stamp with matching version still produces a warning
  test("a pre-rename stamp with matching version still produces a PreRenameStamp warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(14, StampFormat.Legacy))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.warnings.nonEmpty, "pre-rename stamp must produce a warning even when version matches")
    result.warnings.headOption match
      case Some(_: DriftWarning.PreRenameStamp) => assert(true)
      case other                                => fail(s"Expected PreRenameStamp, got $other")

  // ── Mutation-killing: mixed roots — one matching, one mismatched
  test("mixed roots produce warnings only for mismatched ones"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(14, StampFormat.New)),
      InstallRootScan(".agents/skills", InstallRootState.Stamped(13, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assertEquals(result.warnings.length, 1)
    assert(!result.noSkillInstalled, "at least one installed skill must set noSkillInstalled=false")

  // ── Mutation-killing: mixed roots — one stamped, one absent (exists vs forall)
  test("mixed roots with one absent root sets noSkillInstalled=false"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(14, StampFormat.New)),
      InstallRootScan(".agents/skills", InstallRootState.Absent)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(
      !result.noSkillInstalled,
      "at least one installed skill must set noSkillInstalled=false even if another root is absent"
    )

  // ── spec 3: the searched root set is the predecessor's six ─────────────

  // spec: live-fact-banner — Requirement: Instruction drift is scanned across every install root the workflow searches
  test("installRoots is the six searched roots — three repo-local, three user-scoped"):
    val roots: InstallRoots = DriftScan.installRoots
    assertEquals(roots.length, 6)
    assertEquals(roots.all.length, 6)
    val byBase: Map[RootBase, List[InstallRootRef]] = roots.all.groupBy(_.base)
    assertEquals(
      byBase.getOrElse(RootBase.RepoRoot, Nil).map(_.relativePath).toSet,
      Set(".agents/skills", ".claude/skills", ".pi/skills"),
      "repo-local roots must be .agents/.claude/.pi skills"
    )
    assertEquals(
      byBase.getOrElse(RootBase.UserHome, Nil).map(_.relativePath).toSet,
      Set(".agents/skills", ".claude/skills", ".zcode/skills"),
      "user-scoped roots must be .agents/.claude/.zcode skills"
    )

  // spec: live-fact-banner — Scenario: Adversarial — no root is silently skipped
  test("every installed root with a differing version is reported"):
    val roots: List[InstallRootScan] = DriftScan.installRoots.all.map { ref =>
      InstallRootScan(ref.relativePath, InstallRootState.Stamped(13, StampFormat.New))
    }
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assertEquals(
      result.warnings.length,
      DriftScan.installRoots.length,
      "every one of the six roots with drift must be reported"
    )
    assertEquals(
      result.warnings.map(_.rootPath).toSet,
      DriftScan.installRoots.all.map(_.relativePath).toSet
    )

  // spec: live-fact-banner — Scenario: Edge case — a root carrying a pre-rename stamp is reported as pre-rename
  test("a pre-rename stamp is reported distinctly from a version mismatch"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.Legacy)),
      InstallRootScan(".agents/skills", InstallRootState.Stamped(13, StampFormat.New))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(
      result.warnings.exists(w =>
        w match
          case _: DriftWarning.PreRenameStamp => true
          case _                              => false
      ),
      "must contain PreRenameStamp"
    )
    assert(
      result.warnings.exists(w =>
        w match
          case _: DriftWarning.VersionMismatch => true
          case _                               => false
      ),
      "must contain VersionMismatch"
    )

  // spec: live-fact-banner — Scenario: Error path — no instruction document at any root is reported as such
  test("no instruction document at any root yields noSkillInstalled"):
    val roots: List[InstallRootScan] = DriftScan.installRoots.all.map { ref =>
      InstallRootScan(ref.relativePath, InstallRootState.Absent)
    }
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(result.noSkillInstalled, "all-absent roots must yield noSkillInstalled")
    assert(result.warnings.isEmpty, "all-absent roots must yield no warnings")

  // spec: live-fact-banner — Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent
  test("an unreadable root is reported, never treated as absent"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Unreadable("permission denied")),
      InstallRootScan(".agents/skills", InstallRootState.Absent)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    result.warnings.headOption match
      case Some(w: DriftWarning.Unreadable) =>
        assertEquals(w.rootPath, ".claude/skills")
        assert(w.message.contains("could not be read"), s"message must state unreadable: ${w.message}")
      case other => fail(s"Expected Unreadable warning, got $other")
    // An unreadable root means we cannot claim "no document found".
    assert(
      !result.noSkillInstalled,
      "an unreadable root must not be reported as no-document-found"
    )

  // spec: live-fact-banner — predecessor parity: present document, no stamp (pre-v7 install)
  test("a document with no stamp produces a NoStampDeclared warning"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.PresentNoStamp)
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    result.warnings.headOption match
      case Some(w: DriftWarning.NoStampDeclared) =>
        assert(w.message.contains("no schema version"), s"message must name the missing version: ${w.message}")
      case other => fail(s"Expected NoStampDeclared, got $other")
    assert(!result.noSkillInstalled, "a present document is not 'no skill installed'")

  // ════════════════════════════════════════════════════════════════════
  // Property: every-searched-root-is-scanned
  // spec: live-fact-banner — Property: every-searched-root-is-scanned
  //
  // For every generated install-root configuration, the scan reports on
  // every searched root and every root carrying a warnable state appears
  // in the warnings — no root is silently skipped.
  // ════════════════════════════════════════════════════════════════════

  /** A generated scan input: one state per searched root + the baseline. */
  final private case class InstallRootConfig(
    schemaVersion: Option[Int],
    scans: List[InstallRootScan]
  ):
    /**
     * The roots whose state must produce a warning: anything present that
     * is not a matching new-format stamp, and anything unreadable.
     */
    def rootsWithWarnings: Set[String] =
      scans.collect {
        case InstallRootScan(p, InstallRootState.PresentNoStamp)                 => p
        case InstallRootScan(p, InstallRootState.Unreadable(_))                  => p
        case InstallRootScan(p, InstallRootState.Stamped(_, StampFormat.Legacy)) => p
        case InstallRootScan(p, InstallRootState.Stamped(v, StampFormat.New)) if schemaVersion.exists(_ != v) =>
          p
      }.toSet

  private def isUserScoped(ref: InstallRootRef): Boolean =
    ref.base match
      case RootBase.UserHome => true
      case RootBase.RepoRoot => false

  private def warns(cfg: InstallRootConfig, scan: InstallRootScan): Boolean =
    cfg.rootsWithWarnings.contains(scan.rootPath)

  /** Per-root state generator used by the `mixed` arm. */
  private def genMixedState: Gen[InstallRootState] =
    Gen.frequency1(
      40 -> Gen.constant(InstallRootState.Absent),
      30 -> Gen.int(Range.linear(12, 16)).map(v => InstallRootState.Stamped(v, StampFormat.New)),
      15 -> Gen.int(Range.linear(12, 16)).map(v => InstallRootState.Stamped(v, StampFormat.Legacy)),
      10 -> Gen.constant(InstallRootState.PresentNoStamp),
      5  -> Gen.string(Gen.alpha, Range.linear(3, 12)).map(InstallRootState.Unreadable(_))
    )

  /** Per-root state generator used by the `all-present` arm. */
  private def genPresentState: Gen[InstallRootState] =
    Gen.frequency1(
      2 -> Gen.int(Range.linear(12, 16)).map(v => InstallRootState.Stamped(v, StampFormat.New)),
      1 -> Gen.int(Range.linear(12, 16)).map(v => InstallRootState.Stamped(v, StampFormat.Legacy)),
      1 -> Gen.constant(InstallRootState.PresentNoStamp)
    )

  private def genSchemaBaseline: Gen[Option[Int]] =
    Gen.frequency1(
      4 -> Gen.int(Range.linear(12, 16)).map(Some(_)),
      1 -> Gen.constant(Option.empty[Int])
    )

  /**
   * genInstallRootConfig — the spec's constructive generator: all-absent
   * and all-present arms are frequency-weighted so the cover floors
   * (8% each) cannot be missed by uniform sampling.
   *
   * spec: live-fact-banner — Property: every-searched-root-is-scanned (generator)
   */
  private def genInstallRootConfig: Gen[InstallRootConfig] =
    def build(states: List[InstallRootState]): List[InstallRootScan] =
      DriftScan.installRoots.all
        .zip(states)
        .map { case (ref, st) => InstallRootScan(ref.relativePath, st) }
    for
      schemaV <- genSchemaBaseline
      scans <- Gen.frequency1(
        12 -> Gen.constant(
          build(List.fill(DriftScan.installRoots.length)(InstallRootState.Absent))
        ),
        12 -> genPresentState
          .list(Range.singleton(DriftScan.installRoots.length))
          .map(build),
        76 -> genMixedState
          .list(Range.singleton(DriftScan.installRoots.length))
          .map(build)
      )
    yield InstallRootConfig(schemaV, scans)

  private def coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(300))

  property("every-searched-root-is-scanned", coverConfig):
    for cfg <- genInstallRootConfig.forAll
        .cover(8, "all-absent", (c: InstallRootConfig) => c.scans.forall(_.state == InstallRootState.Absent))
        .cover(8, "all-present", (c: InstallRootConfig) => c.scans.forall(_.state != InstallRootState.Absent))
        .cover(
          50,
          "mixed",
          (c: InstallRootConfig) =>
            c.scans.exists(_.state == InstallRootState.Absent) &&
              c.scans.exists(_.state != InstallRootState.Absent)
        )
        .cover(
          25,
          "user-scoped-drift",
          (c: InstallRootConfig) =>
            DriftScan.installRoots.all
              .zip(c.scans)
              .exists { case (ref, scan) => isUserScoped(ref) && warns(c, scan) }
        )
    yield
      val res: DriftScanResult = DriftScan.scan(cfg.schemaVersion, cfg.scans)
      Result
        .assert(cfg.scans.length == DriftScan.installRoots.length)
        .log(s"scan count ${cfg.scans.length} != searched roots ${DriftScan.installRoots.length}")
        .and(
          Result
            .assert(res.warnings.map(_.rootPath).toSet == cfg.rootsWithWarnings)
            .log(
              s"warnings ${res.warnings.map(_.rootPath).toSet} != expected ${cfg.rootsWithWarnings}"
            )
        )
        .and(
          Result
            .assert(res.noSkillInstalled == cfg.scans.forall(_.state == InstallRootState.Absent))
            .log("noSkillInstalled must hold iff every root is absent")
        )

  // ════════════════════════════════════════════════════════════════════
  // spec 10 of repair-probatio-cutover: schema-rename-completion
  // ════════════════════════════════════════════════════════════════════

  // spec: schema-rename-completion — Scenario: Happy path — every installed document carries the current stamp
  test("schema-rename: every searched root stamped at the current name and version yields no warning"):
    val roots: List[InstallRootScan] = DriftScan.installRoots.all.map { ref =>
      InstallRootScan(ref.relativePath, InstallRootState.Stamped(14, StampFormat.New))
    }
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assert(
      result.warnings.isEmpty,
      s"all-current stamps must yield no warnings, got ${result.warnings}"
    )
    assert(!result.noSkillInstalled, "installed documents must not report no-skill-installed")

  // spec: schema-rename-completion — Scenario: Adversarial — a pre-rename stamp is still reported as migration, not drift
  test("schema-rename: a pre-rename stamp is a migration warning naming the root, not a version mismatch"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Stamped(13, StampFormat.Legacy))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    assertEquals(result.warnings.length, 1, "exactly one warning expected")
    result.warnings.headOption match
      case Some(w: DriftWarning.PreRenameStamp) =>
        assertEquals(w.rootPath, ".claude/skills", "the migration warning must name the root")
        assert(w.message.contains(".claude/skills"), s"message must name the root: ${w.message}")
      case Some(w: DriftWarning.VersionMismatch) =>
        fail(s"a pre-rename stamp reported as version drift is the false-alarm defect: $w")
      case other => fail(s"Expected PreRenameStamp, got $other")

  // spec: schema-rename-completion — Scenario: Error path — an unreadable root is could-not-determine, not absent
  test("schema-rename: an unreadable root is reported unreadable, never as carrying no document"):
    val roots: List[InstallRootScan] = List(
      InstallRootScan(".claude/skills", InstallRootState.Unreadable("permission denied"))
    )
    val result: DriftScanResult = DriftScan.scan(schemaVersion = Some(14), roots)
    result.warnings.headOption match
      case Some(w: DriftWarning.Unreadable) =>
        assertEquals(w.rootPath, ".claude/skills")
      case other => fail(s"Expected Unreadable warning, got $other")
    assert(
      !result.noSkillInstalled,
      "an unreadable root must not collapse into no-document-found"
    )

  // ── Property: every-searched-root-is-classified ─────────────────────
  // spec: schema-rename-completion — Property: every-searched-root-is-classified
  //
  // For every assignment of an InstallRootState to the six searched roots,
  // the drift scan classifies each root into exactly one outcome — absent
  // (no warning, counted toward noSkillInstalled), or a warning whose
  // variant matches the state — and an unreadable root is never reported
  // as absent.

  /** The warning variant each state must produce (None = no warning). */
  private def expectedWarningKind(
    state: InstallRootState,
    schemaVersion: Option[Int]
  ): Option[String] =
    state match
      case InstallRootState.Absent         => None
      case InstallRootState.PresentNoStamp => Some("NoStampDeclared")
      case InstallRootState.Unreadable(_)  => Some("Unreadable")
      case InstallRootState.Stamped(_, StampFormat.Legacy) =>
        Some("PreRenameStamp")
      case InstallRootState.Stamped(v, StampFormat.New) =>
        schemaVersion match
          case Some(e) if v != e => Some("VersionMismatch")
          case _                 => None

  private def warningKind(w: DriftWarning): String =
    w match
      case _: DriftWarning.NoStampDeclared => "NoStampDeclared"
      case _: DriftWarning.Unreadable      => "Unreadable"
      case _: DriftWarning.PreRenameStamp  => "PreRenameStamp"
      case _: DriftWarning.VersionMismatch => "VersionMismatch"

  /**
   * genInstallRootStates — constructive over the four install-root states
   * crossed with the six searched roots. Edge arms: all-absent,
   * all-stamped-at-current, exactly-one-unreadable, and stamps drawn at
   * the recorded historical versions (12, 13) plus the current one.
   */
  private def genInstallRootStates: Gen[(Option[Int], List[InstallRootState])] =
    val genState: Gen[InstallRootState] = Gen.frequency1(
      25 -> Gen.constant(InstallRootState.Absent),
      20 -> Gen.constant(InstallRootState.PresentNoStamp),
      35 -> Gen.element1(12, 13, 14).flatMap { v =>
        Gen.element1(StampFormat.Legacy, StampFormat.New).map(f => InstallRootState.Stamped(v, f))
      },
      20 -> Gen.string(Gen.alpha, Range.linear(3, 12)).map(InstallRootState.Unreadable(_))
    )
    for
      schemaV <- genSchemaBaseline
      states <- Gen.frequency1(
        15 -> Gen.constant(List.fill(DriftScan.installRoots.length)(InstallRootState.Absent)),
        10 -> Gen.constant(
          List.fill(DriftScan.installRoots.length)(InstallRootState.Stamped(14, StampFormat.New))
        ),
        10 -> genState
          .list(Range.singleton(DriftScan.installRoots.length))
          .map((states: List[InstallRootState]) => states.updated(0, InstallRootState.Unreadable("forced"))),
        65 -> genState.list(Range.singleton(DriftScan.installRoots.length))
      )
    yield (schemaV, states)

  property("every-searched-root-is-classified", coverConfig):
    for drawn <- genInstallRootStates.forAll
        .cover(
          10,
          "all-absent",
          (d: (Option[Int], List[InstallRootState])) => d._2.forall(_ == InstallRootState.Absent)
        )
        .cover(
          10,
          "has-unreadable",
          (d: (Option[Int], List[InstallRootState])) =>
            d._2.exists { case InstallRootState.Unreadable(_) => true; case _ => false }
        )
        .cover(
          10,
          "has-legacy",
          (d: (Option[Int], List[InstallRootState])) =>
            d._2.exists { case InstallRootState.Stamped(_, StampFormat.Legacy) => true; case _ => false }
        )
    yield
      val (schemaV: Option[Int], states: List[InstallRootState]) = drawn
      // The repo-local and user-scoped roots share relative paths
      // (`.agents/skills` appears under both bases) — key each scan by
      // base-qualified path so per-root classification is observable.
      val scans: List[InstallRootScan] = DriftScan.installRoots.all
        .zip(states)
        .map { case (ref, st) => InstallRootScan(s"${ref.base}/${ref.relativePath}", st) }
      val res: DriftScanResult = DriftScan.scan(schemaV, scans)
      val warningsByRoot: Map[String, List[DriftWarning]] =
        res.warnings.groupBy(_.rootPath)
      // Totality + single classification: each searched root yields at
      // most one warning, of the variant its state requires; an
      // Unreadable root always yields its Unreadable warning and never
      // collapses into absent.
      val perRootOk: Boolean = scans.forall { (scan: InstallRootScan) =>
        val got: List[DriftWarning]  = warningsByRoot.getOrElse(scan.rootPath, Nil)
        val expected: Option[String] = expectedWarningKind(scan.state, schemaV)
        expected match
          case None => got.isEmpty
          case Some(kind) =>
            got match
              case w :: Nil => warningKind(w) == kind
              case _        => false
      }
      val unreadableNotAbsent: Boolean = scans.forall { (scan: InstallRootScan) =>
        scan.state match
          case InstallRootState.Unreadable(_) =>
            warningsByRoot.getOrElse(scan.rootPath, Nil).exists {
              case _: DriftWarning.Unreadable => true
              case _                          => false
            }
          case _ => true
      }
      Result
        .assert(scans.length == DriftScan.installRoots.length)
        .and(Result.assert(perRootOk).log("a root produced zero or duplicate/mismatched warnings"))
        .and(Result.assert(unreadableNotAbsent).log("an unreadable root was reported as absent"))
        .and(
          Result
            .assert(res.noSkillInstalled == states.forall(_ == InstallRootState.Absent))
            .log("noSkillInstalled must hold iff every root is absent")
        )
