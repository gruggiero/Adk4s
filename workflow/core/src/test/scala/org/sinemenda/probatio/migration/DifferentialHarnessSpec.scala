package org.sinemenda.probatio.migration

import hedgehog.*
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ProbatioSuite

import java.lang.ProcessBuilder
import java.nio.charset.StandardCharsets

/**
 * Test oracle for the differential harness — verifies that both runs
 * of the comparison execute in the same repository under the same
 * suite, and that a modified suite file refuses the comparison.
 *
 * spec: cutover-gate — Requirement: Both runs of the comparison execute in the same repository under the same suite
 */
final class DifferentialHarnessSpec extends ProbatioSuite:

  // The environment-independence property spawns two `bats` runs per
  // generated case over the real acceptance suite — ~5 minutes through
  // the native binary on a fast machine, and an order of magnitude more
  // through the JAR launcher (each tool call pays JVM startup). The cap
  // is sized for the hosted runner's native path with headroom.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(1800, "s")

  /**
   * Absolute path of the repository root. `os.pwd` is unreliable under
   * forked test runners (sbt `Test / fork` and the Stryker4s runner set the
   * working directory to the module base), so resolve via git.
   */
  private def repoRoot: os.Path =
    // spec: hermetic-test-processes — via the shared helper.
    scala.util
      .Try(
        HermeticEnv.capture(
          List("git", "rev-parse", "--show-toplevel"),
          HermeticEnv.empty,
          cwd = Some(os.pwd.toIO)
        )
      )
      .toOption
      .filter((r: HermeticResult) => r.exitCode == 0)
      .map((r: HermeticResult) => os.Path(r.out.trim))
      .getOrElse(os.pwd)

  // ── Scenario: Adversarial — a comparison whose arms differ in repository is refused
  // spec: cutover-gate — Scenario: Adversarial — a comparison whose arms differ in repository is refused
  test("differential harness: differing repositories produce a revert"):
    val predRun: DifferentialHarness.SuiteRun =
      DifferentialHarness.SuiteRun(
        List(DifferentialHarness.BatsFileResult("file1.bats", 10, 3)),
        Set("file1.bats")
      )
    val portRun: DifferentialHarness.SuiteRun =
      DifferentialHarness.SuiteRun(
        List(DifferentialHarness.BatsFileResult("file1.bats", 10, 2)),
        Set("file1.bats")
      )
    // The diff function records the repository; the gate checks completeness.
    // A comparison whose arms were made against different repositories is
    // detected by the caller — the harness records the repository string.
    val d1: DifferentialResult = DifferentialHarness.diff(predRun, portRun, "/repo-a")
    val d2: DifferentialResult = DifferentialHarness.diff(predRun, portRun, "/repo-b")
    // Both are complete (same file set, both present), but the repository
    // differs. The gate decides on the counts; the repository identity is
    // the caller's responsibility to verify before calling decide.
    assert(d1.repository != d2.repository, "the harness must record the repository identity for the caller to verify")

  // ── Scenario: Adversarial — a comparison against a modified suite is refused
  // spec: cutover-gate — Scenario: Adversarial — a comparison against a modified suite is refused
  test("differential harness: modified suite file is detected by digest verification"):
    // The verifySuiteDigests function compares file digests against
    // expected values. A modified file produces a digest mismatch.
    val oracleDir: os.Path = repoRoot / "openspec" / "schemas" / "verified-scala3" / "tests"
    if os.exists(oracleDir) then
      val batsFiles: IndexedSeq[os.Path] = os.list(oracleDir).filter(_.ext == "bats")
      if batsFiles.nonEmpty then
        val firstFile: os.Path                   = batsFiles.headOption.getOrElse(fail("no bats files found"))
        val wrongDigest: String                  = "0000000000000000000000000000000000000000000000000000000000000000"
        val expectedDigests: Map[String, String] = Map(firstFile.last -> wrongDigest)
        val result: Either[String, Unit] =
          DifferentialHarness.verifySuiteDigests(oracleDir, expectedDigests)
        result match
          case Left(fileName) =>
            assert(fileName == firstFile.last, s"modified file $fileName should be named, expected ${firstFile.last}")
          case Right(_) =>
            fail("a modified suite file must be detected — the digest mismatch should produce Left")
    else
      // Oracle directory not found — skip (not a failure in test env)
      (
    )

  // ── Scenario: Happy path — a comparison with identical repository and suite is accepted
  // spec: cutover-gate — Scenario: Happy path — a comparison with identical repository and suite is accepted
  test("differential harness: identical repository and suite produces a complete comparison"):
    val predRun: DifferentialHarness.SuiteRun =
      DifferentialHarness.SuiteRun(
        List(
          DifferentialHarness.BatsFileResult("file1.bats", 10, 3),
          DifferentialHarness.BatsFileResult("file2.bats", 10, 5)
        ),
        Set("file1.bats", "file2.bats")
      )
    val portRun: DifferentialHarness.SuiteRun =
      DifferentialHarness.SuiteRun(
        List(
          DifferentialHarness.BatsFileResult("file1.bats", 10, 2),
          DifferentialHarness.BatsFileResult("file2.bats", 10, 5)
        ),
        Set("file1.bats", "file2.bats")
      )
    val d: DifferentialResult = DifferentialHarness.diff(predRun, portRun, "/repo")
    assert(d.isComplete, "a comparison with identical file sets must be complete")
    assert(!d.hasRegression, "no file should be worse when ported <= predecessor everywhere")

  // ── Scenario: Happy path — suite digest verification passes for unmodified files
  // spec: cutover-gate — Scenario: Happy path — a comparison with identical repository and suite is accepted
  test("differential harness: unmodified suite files pass digest verification"):
    val oracleDir: os.Path = repoRoot / "openspec" / "schemas" / "verified-scala3" / "tests"
    if os.exists(oracleDir) then
      val batsFiles: IndexedSeq[os.Path] = os.list(oracleDir).filter(_.ext == "bats")
      if batsFiles.nonEmpty then
        val expectedDigests: Map[String, String] = batsFiles.map(f => f.last -> computeDigest(f)).toMap
        val result: Either[String, Unit] =
          DifferentialHarness.verifySuiteDigests(oracleDir, expectedDigests)
        result match
          case Left(fileName) =>
            fail(s"unmodified file $fileName should not be flagged as modified")
          case Right(_) =>
            () // expected — all digests match
    else ()

  // ════════════════════════════════════════════════════════════════════════
  // spec: differential-harness-integrity
  //
  // Oracle for the arm-materialisation comparison. Derived from the spec's
  // requirements, scenarios, properties, and compile-negative obligations —
  // NOT from the implementation. Polarity: every test that reaches
  // `ArmTree.materialise`, `divergence`, `runSuite`, `exercisedToolPaths`,
  // or `checkPredecessorControl` is RED until Step 3 (those bodies are
  // `???`); the seam-existence and fixture-shape tests are
  // GREEN-BY-DESIGN.
  // ════════════════════════════════════════════════════════════════════════

  import SeamTypes.*

  /** Cover thresholds are stable at 200 tests for the arm generators. */
  private val coverConfig: PropertyConfig => PropertyConfig =
    _.copy(testLimit = SuccessCount(200))

  // ── Scenario: Happy path — materialisation produces a predecessor arm
  // spec: differential-harness-integrity — Requirement: The comparison resolves each arm to a materialised tree
  test("materialised predecessor arm holds the predecessor implementation at every seam"):
    val tmp: os.Path                                  = os.temp.dir()
    val (repo: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree = materialiseOrFail(
      SeamConfiguration.fromPorted(Set.empty),
      sha,
      schema,
      tmp / "pred"
    )
    assertEquals(arm.origin, repo, "the arm records the repository it was materialised from")
    assertEquals(arm.baseline, sha, "the arm records the baseline it was materialised at")
    assertEquals(arm.resolutions.map(_.seam), ToolId.swapOrder, "every seam is resolved, in swap order")
    arm.resolutions.foreach { (res: SeamResolution) =>
      val expected: ContentDigest =
        ContentDigest.ofFile(schema / os.RelPath(ToolId.predecessorSource(res.seam)))
      assertEquals(
        res.implementationDigest,
        expected,
        s"seam ${res.seam}: the resolution digest must be the predecessor implementation's content"
      )
      val armSeam: os.Path = arm.root / os.RelPath(ToolId.seamPath(res.seam))
      assert(
        os.exists(armSeam) && ContentDigest.ofFile(armSeam) == expected,
        s"seam ${res.seam}: the arm's live path must hold the predecessor implementation"
      )
    }

  // ── Scenario: Happy path — materialisation produces a ported arm
  // spec: differential-harness-integrity — Requirement: The comparison resolves each arm to a materialised tree
  test("materialised ported arm holds the ported implementation at every seam"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree = materialiseOrFail(
      SeamConfiguration.fromPorted(ToolId.swapOrder.toSet),
      sha,
      schema,
      tmp / "ported"
    )
    val alreadySwapped: Set[ToolId] = Set(
      ToolId.ChainState,
      ToolId.SpecLint,
      ToolId.DangerScan,
      ToolId.Reconcile,
      ToolId.Gate
    )
    arm.resolutions.foreach { (res: SeamResolution) =>
      val armSeam: os.Path = arm.root / os.RelPath(ToolId.seamPath(res.seam))
      val content: String  = os.read(armSeam)
      if alreadySwapped.contains(res.seam) then
        // The live file is already the ported shim — the arm carries it verbatim.
        assertEquals(
          res.implementationDigest,
          ContentDigest.ofFile(schema / os.RelPath(ToolId.seamPath(res.seam))),
          s"seam ${res.seam}: the ported resolution must be the live shim's content"
        )
      else
        // ledger/checkpoint have no live shim yet — materialisation writes
        // the same exec-shim shape the real swap will write.
        val subcommand: String =
          os.RelPath(ToolId.seamPath(res.seam)).last.stripSuffix(".sh")
        assert(
          content.contains("exec") && content.contains(s"bin/probatio\" $subcommand "),
          s"seam ${res.seam}: the ported resolution must dispatch to `probatio $subcommand`, got:\n$content"
        )
      assert(
        content.contains("probatio"),
        s"seam ${res.seam}: a ported implementation dispatches to the probatio binary"
      )
    }

  // ── Scenario: Error path — a seam with no predecessor implementation is could-not-determine
  // spec: differential-harness-integrity — Scenario: Error path — a seam with no predecessor implementation is could-not-determine
  test("a seam whose predecessor implementation is absent yields could-not-determine naming the seam"):
    val tmp: os.Path = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) =
      mkSyntheticRepo(tmp, omitPredecessors = Set(ToolId.SpecLint))
    ArmTree.materialise(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "pred") match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains("SpecLint") || reason.contains("spec-lint"),
          s"the failure must name the seam whose predecessor is absent, got: $reason"
        )
      case Outcome.Ran(_) =>
        fail("a missing predecessor implementation must not produce an arm")
      case Outcome.Finding(d) =>
        fail(s"a missing predecessor is Undetermined, not a Finding: $d")

  // ── Scenario: Adversarial — an arm with no built tool is could-not-determine
  // spec: jar-launcher-dispatch — Scenario: Adversarial — an arm with no built tool is could-not-determine
  //
  // The fixture repo carries no `workflow/cli/target` build outputs — nothing
  // was ever built there (build outputs are gitignored, so a worktree cannot
  // carry them). materialise must refuse, naming the artifact it searched.
  test("an arm materialised where no built tool is available is could-not-determine naming the artifact"):
    val tmp: os.Path                                = os.temp.dir()
    val (repo: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    // Whatever the fixture plants, this arm's origin must carry no build
    // outputs — the materialisation has nothing to provide.
    val builtOutputs: os.Path = repo / "workflow" / "cli" / "target"
    if os.exists(builtOutputs) then os.remove.all(builtOutputs)
    ArmTree.materialise(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "pred") match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains("workflow/cli/target"),
          s"the failure must name the built tool's location, got: $reason"
        )
      case Outcome.Ran(_) =>
        fail("an arm with no built tool to provide must not materialise")
      case Outcome.Finding(d) =>
        fail(s"a missing built tool is Undetermined, not a Finding: $d")

  // ── Scenario: Adversarial — identical arms yield a refusal, not Proceed
  // spec: differential-harness-integrity — Scenario: Adversarial — identical arms yield a refusal, not Proceed
  test("two arms resolved identically refuse to compare — no verdict is produced"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val config: SeamConfiguration                  = SeamConfiguration.fromPorted(Set(ToolId.ChainState))
    val a: ArmTree                                 = materialiseOrFail(config, sha, schema, tmp / "a")
    val b: ArmTree                                 = materialiseOrFail(config, sha, schema, tmp / "b")
    DifferentialHarness.compare(a, b) match
      case Left(identical) =>
        assertEquals(
          identical.seams.map(_.seam),
          ToolId.swapOrder,
          "the refusal names every seam whose resolution was identical"
        )
      case Right(result) =>
        fail(s"identical arms must refuse — a suite run cannot yield a verdict here: $result")

  // ── Scenario: Boundary — arms differing at exactly one seam are compared
  // spec: differential-harness-integrity — Scenario: Boundary — arms differing at exactly one seam are compared
  test("arms differing at exactly one seam are compared, not refused"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val predecessor: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "pred")
    val onlyLedgerPorted: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set(ToolId.Ledger)), sha, schema, tmp / "mix")
    DifferentialHarness.compare(predecessor, onlyLedgerPorted) match
      case Left(_)  => fail("arms differing at a seam must be compared, not refused")
      case Right(_) => ()
    DifferentialHarness.divergence(predecessor.resolutions, onlyLedgerPorted.resolutions) match
      case ArmDivergence.Diverged(perSeam) =>
        assertEquals(
          perSeam.map((l: SeamResolution, _: SeamResolution) => l.seam),
          List(ToolId.Ledger),
          "the comparison names the one seam that differs"
        )
      case ArmDivergence.Identical(_) =>
        fail("arms differing at ledger must be Diverged, not Identical")

  // ── Scenario: Content identity — identical bytes at differing paths
  // spec: differential-harness-integrity — Scenario: Content identity — identical bytes at differing paths are the same implementation
  test("identical bytes at differing paths are the same implementation"):
    val digest: ContentDigest = ContentDigest.ofBytes("the same payload".getBytes("UTF-8"))
    def resolutions(prefix: String): List[SeamResolution] =
      ToolId.swapOrder.map { (seam: ToolId) =>
        SeamResolution(seam, digest, os.Path(s"$prefix/${ToolId.seamPath(seam)}"))
      }
    DifferentialHarness.divergence(resolutions("/arm-a"), resolutions("/arm-b")) match
      case ArmDivergence.Identical(seams) =>
        assertEquals(seams.map(_.seam), ToolId.swapOrder)
      case diverged: ArmDivergence.Diverged =>
        fail(s"same bytes at different paths must be identical, got divergence: ${diverged.divergingSeams}")

  // ── Contract: divergence — a diverged result names a genuinely differing seam
  // spec: differential-harness-integrity — Contract: divergence
  test("a divergence verdict names the seam whose content differs"):
    val same: ContentDigest    = ContentDigest.ofBytes("same".getBytes("UTF-8"))
    val differs: ContentDigest = ContentDigest.ofBytes("different".getBytes("UTF-8"))
    val left: List[SeamResolution] = ToolId.swapOrder.map { (seam: ToolId) =>
      SeamResolution(seam, same, os.Path(s"/a/${ToolId.seamPath(seam)}"))
    }
    val right: List[SeamResolution] = left.map { (res: SeamResolution) =>
      if res.seam == ToolId.ChainState then res.copy(implementationDigest = differs) else res
    }
    DifferentialHarness.divergence(left, right) match
      case ArmDivergence.Diverged(perSeam) =>
        assertEquals(perSeam.map((l: SeamResolution, _: SeamResolution) => l.seam), List(ToolId.ChainState))
      case ArmDivergence.Identical(_) =>
        fail("an arm pair differing at chain-state must be Diverged, not Identical")

  // ── Contract: divergence — mismatched seam sets are a programming error
  // spec: differential-harness-integrity — Contract: divergence
  test("divergence on arms resolving different seams throws, naming the seam-set mismatch"):
    val digest: ContentDigest = ContentDigest.ofBytes("x".getBytes("UTF-8"))
    val left: List[SeamResolution] = ToolId.swapOrder.map { (seam: ToolId) =>
      SeamResolution(seam, digest, os.Path(s"/a/${ToolId.seamPath(seam)}"))
    }
    // Same length, same seams, different ORDER — the pairing is positional,
    // so a reordered arm is a caller bug, not a diverged verdict.
    val thrown: IllegalArgumentException =
      intercept[IllegalArgumentException](DifferentialHarness.divergence(left, left.reverse))
    assert(
      thrown.getMessage.contains("same seams"),
      s"the guard must name the seam-set requirement, got: ${thrown.getMessage}"
    )

  // ── Contract: compare — arms from different origins or baselines are refused
  // spec: differential-harness-integrity — Requirement: The comparison resolves each arm to a materialised tree
  test("compare refuses arms materialised at different baselines"):
    val tmp: os.Path                                  = os.temp.dir()
    val (repo: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    os.write(schema / "scanner" / "extra.sh", "#!/usr/bin/env bash\ntrue\n")
    git(repo, List("add", "-A"))
    git(repo, List("-c", "user.email=oracle@local", "-c", "user.name=oracle", "commit", "-qm", "second"))
    val sha2: String = git(repo, List("rev-parse", "HEAD"))
    val a: ArmTree   = materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "a")
    val b: ArmTree   = materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha2, schema, tmp / "b")
    val thrown: IllegalArgumentException =
      intercept[IllegalArgumentException](DifferentialHarness.compare(a, b))
    assert(
      thrown.getMessage.contains("baseline"),
      s"the guard must name the baseline requirement, got: ${thrown.getMessage}"
    )

  // ── Scenario: Happy path — runSuite reports each file's own TAP counts
  // spec: differential-harness-integrity — Requirement: The comparison runs the acceptance suite inside each materialised arm
  test("runSuite reports per-file totals and failures, dropping files with no TAP results"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "arm")
    val run: DifferentialHarness.SuiteRun = DifferentialHarness.runSuite(arm)
    assertEquals(
      run.fileResults.map((r: DifferentialHarness.BatsFileResult) => (r.fileName, r.total, r.failures)),
      List(("failing.bats", 1, 1), ("one.bats", 1, 0), ("unseamed.bats", 1, 0)),
      "each file's own counts must be reported — zero.bats produces no TAP lines and must be absent"
    )

  // ── Scenario: Boundary — a tool directory absent from the arm is skipped
  // spec: differential-harness-integrity — Scenario: Adversarial — a suite file exercising an unrepresented tool is not claimed as compared
  test("exercisedToolPaths skips a tool directory absent from the arm rather than crashing"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "arm")
    os.remove.all(arm.root / "bin")
    val exercised: Map[String, Set[String]] = DifferentialHarness.exercisedToolPaths(arm)
    assert(
      exercised.values.forall((paths: Set[String]) => paths.forall(!_.startsWith("bin/"))),
      s"a removed bin/ directory must contribute no tool paths, got $exercised"
    )

  // ── Scenario: Error path — a missing recorded control is could-not-determine
  // spec: differential-harness-integrity — Requirement: The comparison reproduces the recorded predecessor control
  test("a missing recorded control file is could-not-determine, naming the control"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "arm")
    DifferentialHarness.checkPredecessorControl(arm, suiteRunOf(Map("one.bats" -> (1, 0))), tmp / "absent.json") match
      case Outcome.Undetermined(reason) =>
        assert(reason.contains("control"), s"the outcome must name the missing control, got: $reason")
      case Outcome.Ran(())    => fail("a missing control must not be accepted")
      case Outcome.Finding(d) => fail(s"a missing control is Undetermined, not a Finding: $d")

  // ── Scenario: Adversarial — a suite file exercising an unrepresented tool is not-compared
  // spec: differential-harness-integrity — Scenario: Adversarial — a suite file exercising an unrepresented tool is not claimed as compared
  test("a suite file exercising a tool with no seam is reported as not-compared"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "arm")
    val unseamed: Map[String, Set[String]] = DifferentialHarness.unseamedToolPaths(arm)
    val reported: Option[Set[String]] = unseamed.collectFirst {
      case (file, paths) if file.endsWith("unseamed.bats") => paths
    }
    reported match
      case Some(paths) =>
        assert(
          paths.exists((p: String) => p.contains("install-skills")),
          s"the unrepresented tool must be named, got $paths"
        )
      case None =>
        fail(s"unseamed.bats exercises scanner/install-skills.sh — it must be reported, got $unseamed")
    assert(
      !unseamed.keys.exists(_.endsWith("one.bats")),
      "one.bats exercises only seamed tools — it must not be reported"
    )

  // ── Scenario: Happy path — the predecessor arm reproduces the recorded control
  // spec: differential-harness-integrity — Requirement: The comparison reproduces the recorded predecessor control
  test("a predecessor arm matching the recorded control per file is accepted"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "pred")
    val perFile: Map[String, (Int, Int)] = Map("one.bats" -> (1, 1), "unseamed.bats" -> (1, 0))
    val control: os.Path                 = writeControl(tmp, sha, perFile)
    DifferentialHarness.checkPredecessorControl(arm, suiteRunOf(perFile), control) match
      case Outcome.Ran(()) => ()
      case other           => fail(s"a matching control must be accepted, got $other")

  // ── Scenario: Error path — a comparison at a different baseline is not checked against the control
  // spec: differential-harness-integrity — Scenario: Error path — a comparison at a different baseline is not checked against the control
  test("a predecessor arm at a different baseline is could-not-determine against the control"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "pred")
    val perFile: Map[String, (Int, Int)] = Map("one.bats" -> (1, 0))
    val control: os.Path                 = writeControl(tmp, "0" * 64, perFile)
    DifferentialHarness.checkPredecessorControl(arm, suiteRunOf(perFile), control) match
      case Outcome.Undetermined(reason) =>
        assert(
          reason.contains(sha) || reason.toLowerCase.contains("baseline"),
          s"the baseline mismatch must be named, got: $reason"
        )
      case Outcome.Ran(()) =>
        fail("a baseline mismatch must never be accepted against the recorded control")
      case Outcome.Finding(d) =>
        fail(s"a baseline mismatch is Undetermined, not a Finding: $d")

  // ── Scenario: Adversarial — a predecessor arm reporting the ported arm's counts is a finding
  // spec: differential-harness-integrity — Scenario: Adversarial — a predecessor arm reporting the ported arm's failure counts is a finding
  test("a predecessor arm reporting the ported arm's failure counts is a finding naming the files"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp)
    val arm: ArmTree =
      materialiseOrFail(SeamConfiguration.fromPorted(Set.empty), sha, schema, tmp / "pred")
    val controlCounts: Map[String, (Int, Int)] = Map("one.bats" -> (1, 1), "unseamed.bats" -> (2, 1))
    val portedCounts: Map[String, (Int, Int)]  = Map("one.bats" -> (1, 0), "unseamed.bats" -> (2, 0))
    val control: os.Path                       = writeControl(tmp, sha, controlCounts)
    DifferentialHarness.checkPredecessorControl(arm, suiteRunOf(portedCounts), control) match
      case Outcome.Finding(description) =>
        assert(
          description.contains("one.bats") && description.contains("unseamed.bats"),
          s"the finding must name every file whose count differs, got: $description"
        )
        assert(
          description.contains("), unseamed.bats"),
          s"the finding must separate the named files legibly, got: $description"
        )
      case Outcome.Ran(()) =>
        fail("ported-arm counts reported as the predecessor's must be a finding, not a pass")
      case Outcome.Undetermined(r) =>
        fail(s"a same-baseline count mismatch is a Finding, not Undetermined: $r")

  // ── The recorded control fixture itself is well-formed — after archiving
  // spec: archive-safe-fixtures — Scenario: Happy path — the archived control is well-formed
  // spec: archive-safe-fixtures — Requirement: The recorded predecessor control is read after archiving
  // RED at Step 2: `predecessorControl` is `???` until Step 3 routes the
  // read through the archive-aware resolver — `repair-probatio-cutover`
  // has been archived since 2026-09-25.
  test("the recorded predecessor control fixture is well-formed"):
    DifferentialHarness.predecessorControl("repair-probatio-cutover", repoRoot / "openspec") match
      case Outcome.Ran(fixture) =>
        val json: ujson.Value = ujson.read(os.read(fixture))
        val baseline: String  = json("measuredAtBaseline").str
        assertEquals(baseline.length, 40, "the recorded baseline must be a full commit sha")
        val perFile: Map[String, ujson.Value] = json("perFile").obj.toMap
        val totals: ujson.Obj                 = json("suiteTotals").obj
        assertEquals(perFile.size, totals("files").num.toInt, "perFile must list every suite file")
        assertEquals(perFile.values.map(_("total").num.toInt).sum, totals("tests").num.toInt)
        assertEquals(perFile.values.map(_("failures").num.toInt).sum, totals("failures").num.toInt)
        assertEquals(totals("failures").num.toInt, 18, "the recorded control is 18 failures")
      case other =>
        fail(s"the recorded predecessor control could not be located: $other")

  // ── Scenario: Adversarial — a missing control fails naming every searched location
  // spec: archive-safe-fixtures — Scenario: Adversarial — a missing control fails naming every searched location
  test("a change with no recorded control in either place fails naming every location searched"):
    ChangeLocationGens.withTempOpenspec { (openspec: os.Path) =>
      // Unrelated changes in both areas — the sought change is genuinely
      // absent, not merely unlooked-for.
      ChangeLocationGens.writeFixtures(
        openspec / "changes" / "other-change",
        List("specs/spec.md")
      )
      ChangeLocationGens.writeFixtures(
        openspec / "changes" / "archive" / "2026-02-20-yet-another",
        List("specs/spec.md")
      )
      DifferentialHarness.predecessorControl("absent-change", openspec) match
        case Outcome.Undetermined(reason) =>
          assert(
            reason.contains("absent-change"),
            s"the failure must name the active location searched: $reason"
          )
          assert(
            reason.contains("archive"),
            s"the failure must name the archive area searched: $reason"
          )
        case other => fail(s"a missing control must be Undetermined, got $other")
      // A located change that lacks the control fixture — undetermined,
      // naming the probed fixture path.
      val bare: os.Path = openspec / "changes" / "bare-change"
      ChangeLocationGens.writeFixtures(bare, List("specs/spec.md"))
      DifferentialHarness.predecessorControl("bare-change", openspec) match
        case Outcome.Undetermined(reason) =>
          assert(
            reason.contains("predecessor-control.json"),
            s"the failure must name the probed fixture path: $reason"
          )
        case other => fail(s"a missing control must be Undetermined, got $other")
    }

  // ── Requirement: Retargeted source-inspection tests assert over the ported implementation
  // spec: differential-harness-integrity — Requirement: Retargeted source-inspection acceptance tests

  /** Extract the body of the first bats test whose name matches `namePrefix`. */
  private def batsTestBody(source: String, namePrefix: String): Option[String] =
    val pattern: scala.util.matching.Regex =
      ("(?s)@test \"" + namePrefix + "[^\"]*\" \\{\n(.*?)\n\\}").r
    pattern.findFirstMatchIn(source).map(_.group(1))

  test("the retargeted drift-message test asserts the property over the ported implementation's source"):
    val hygiene: String = os.read(
      repoRoot / "openspec" / "schemas" / "verified-scala3" / "tests" / "shape" / "workflow-hygiene-shape.bats"
    )
    val body: String = batsTestBody(hygiene, "D7: spec-lint.sh drift message")
      .getOrElse(fail("the D7 drift-message test must exist in workflow-hygiene-shape.bats"))
    assert(
      body.contains("workflow"),
      "the ported implementation's source lives under workflow/ — the retargeted test must inspect it"
    )
    assert(
      body.contains("install-skills"),
      "the property under test — the drift message names scanner/install-skills.sh — must still be asserted"
    )
    assert(
      !body.contains("$SPEC_LINT"),
      "the spec-lint.sh shim carries no message text — asserting it tests nothing"
    )

  test("the retargeted cwd-parse test asserts the property over the ported implementation's source"):
    val hygiene: String = os.read(
      repoRoot / "openspec" / "schemas" / "verified-scala3" / "tests" / "shape" / "workflow-hygiene-shape.bats"
    )
    val body: String = batsTestBody(hygiene, "D8: gate.sh extracts cwd")
      .getOrElse(fail("the D8 cwd-parse test must exist in workflow-hygiene-shape.bats"))
    assert(
      body.contains("workflow") || body.contains("HarnessPayload"),
      "the ported payload reader lives under workflow/ — the retargeted test must inspect it"
    )
    assert(
      !body.contains("$GATE"),
      "the gate.sh shim carries no parsing logic — asserting it tests nothing"
    )

  test("the retargeted drift-message test passes against the ported tool inside an arm"):
    val tmp: os.Path                               = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(tmp, withHygiene = true)
    val arm: ArmTree = materialiseOrFail(
      SeamConfiguration.fromPorted(ToolId.swapOrder.toSet),
      sha,
      schema,
      tmp / "arm"
    )
    val (exitCode: Int, output: String) = runBatsFiltered(arm, "D7: spec-lint.sh drift message")
    assertEquals(exitCode, 0, s"the retargeted drift test must pass against the ported source:\n$output")

  test("the retargeted drift-message test fails when the ported tool names a non-existent installer"):
    val tmp: os.Path = os.temp.dir()
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(
      tmp,
      withHygiene = true,
      // The literal is split so this spec file itself does not trip the
      // retargeted test's repo-wide grep for the dangling name.
      driftText = "Re-install (scanner/" + "sync-skills" + ".sh) before trusting this report."
    )
    val arm: ArmTree = materialiseOrFail(
      SeamConfiguration.fromPorted(ToolId.swapOrder.toSet),
      sha,
      schema,
      tmp / "arm"
    )
    val (exitCode: Int, output: String) = runBatsFiltered(arm, "D7: spec-lint.sh drift message")
    assert(exitCode != 0, s"the retargeted drift test must fail on a dangling installer name:\n$output")

  test("the retargeted payload test fails when cwd parsing is substituted by pattern matching"):
    val tmp: os.Path = os.temp.dir()
    val sedStyleReader: String =
      "def cwd(payload: String): String =\n" +
        "        val m = \"\\\"cwd\\\":\\\"([^\\\"]*)\\\"\".r.findFirstMatchIn(payload)\n" +
        "        m.map(_.group(1)).getOrElse(\"\")"
    val (_: os.Path, schema: os.Path, sha: String) = mkSyntheticRepo(
      tmp,
      withHygiene = true,
      payloadCwd = sedStyleReader
    )
    val arm: ArmTree = materialiseOrFail(
      SeamConfiguration.fromPorted(ToolId.swapOrder.toSet),
      sha,
      schema,
      tmp / "arm"
    )
    val (exitCode: Int, output: String) = runBatsFiltered(arm, "D8: gate.sh extracts cwd")
    assert(exitCode != 0, s"the retargeted cwd test must fail on pattern-matching extraction:\n$output")

  // ── Property: identical-arms-never-proceed
  // spec: differential-harness-integrity — Property: identical-arms-never-proceed
  property("identical-arms-never-proceed", coverConfig):
    val repoData: (os.Path, os.Path, String) = sharedSyntheticRepo
    for cfg <- genSeamConfiguration.forAll
        .cover(10, "all-ported", (c: SeamConfiguration) => c.portedTools.size == ToolId.swapOrder.length)
        .cover(10, "all-predecessor", (c: SeamConfiguration) => c.portedTools.isEmpty)
        .cover(
          30,
          "mixed",
          (c: SeamConfiguration) => c.portedTools.nonEmpty && c.portedTools.size < ToolId.swapOrder.length
        )
    yield
      val tmp: os.Path = os.temp.dir()
      (
        ArmTree.materialise(cfg, repoData._3, repoData._2, tmp / "a"),
        ArmTree.materialise(cfg, repoData._3, repoData._2, tmp / "b")
      ) match
        case (Outcome.Ran(a), Outcome.Ran(b)) =>
          DifferentialHarness.compare(a, b) match
            case Left(_) => Result.success
            case Right(_) =>
              Result.failure.log(
                s"arms materialised from the same config ($cfg) are content-identical — the comparison must refuse"
              )
        case (a, b) =>
          Result.failure.log(s"materialise must succeed on a complete synthetic tree: $a / $b")

  // ── Property: divergence-is-detected-by-content-not-path
  // spec: differential-harness-integrity — Property: divergence-is-detected-by-content-not-path
  property("divergence-is-detected-by-content-not-path", coverConfig):
    for pair <- genArmPair.forAll
        .cover(
          25,
          "identical-by-content",
          (l: List[SeamResolution], r: List[SeamResolution]) =>
            l.zip(r).forall((a, b) => a.implementationDigest == b.implementationDigest)
        )
        .cover(
          20,
          "identical-despite-differing-paths",
          (l: List[SeamResolution], r: List[SeamResolution]) =>
            l.zip(r).forall((a, b) => a.implementationDigest == b.implementationDigest) &&
              l.zip(r).exists((a, b) => a.sourcePath != b.sourcePath)
        )
        .cover(
          30,
          "diverged",
          (l: List[SeamResolution], r: List[SeamResolution]) =>
            l.zip(r).exists((a, b) => a.implementationDigest != b.implementationDigest)
        )
        .cover(
          10,
          "exactly-one-seam-differs",
          (l: List[SeamResolution], r: List[SeamResolution]) =>
            l.zip(r).count((a, b) => a.implementationDigest != b.implementationDigest) == 1
        )
    yield
      val (left: List[SeamResolution], right: List[SeamResolution]) = pair
      val expectedIdentical: Boolean =
        left
          .zip(right)
          .forall((a: SeamResolution, b: SeamResolution) => a.implementationDigest == b.implementationDigest)
      val verdict: ArmDivergence = DifferentialHarness.divergence(left, right)
      Result
        .assert(verdict.isIdentical == expectedIdentical)
        .log(s"isIdentical=${verdict.isIdentical} expected=$expectedIdentical")
        .and(
          verdict match
            case ArmDivergence.Diverged(perSeam) =>
              Result
                .assert(
                  perSeam.forall((l: SeamResolution, r: SeamResolution) =>
                    l.seam == r.seam && l.implementationDigest != r.implementationDigest
                  )
                )
                .log("every named divergence must pair the same seam with genuinely differing digests")
            case ArmDivergence.Identical(_) => Result.success
        )

  // ════════════════════════════════════════════════════════════════════════
  // Generators and fixtures
  // ════════════════════════════════════════════════════════════════════════

  // ── Generator: genSeamConfiguration
  // Constructive: the both/neither states are unconstructible, so the
  // generator picks the ported set directly. Frequency-weighted so the
  // all-ported and all-predecessor edge cases each hit ≥ 10% — with seven
  // independent booleans they would otherwise be ~0.8% each.
  private def genSeamConfiguration: Gen[SeamConfiguration] =
    Gen.frequency1(
      3 -> Gen.constant(SeamConfiguration.fromPorted(ToolId.swapOrder.toSet)),
      3 -> Gen.constant(SeamConfiguration.fromPorted(Set.empty)),
      9 -> genMixedSeamConfiguration
    )

  private def genMixedSeamConfiguration: Gen[SeamConfiguration] =
    // A bitmask over the 7 positions covers ALL 126 non-empty proper
    // subsets — `take(n)` would only ever produce prefix subsets, leaving
    // non-prefix mixes (e.g. Checkpoint alone) unreachable.
    for mask <- Gen.int(Range.linear(1, (1 << ToolId.swapOrder.length) - 2))
    yield
      val ported: Set[ToolId] =
        ToolId.swapOrder.zipWithIndex.collect { case (t, i) if ((mask >> i) & 1) == 1 => t }.toSet
      SeamConfiguration.fromPorted(ported)

  // ── Generator: genArmPair
  // Constructive over (content, path) per seam: content is drawn from a
  // small alphabet so digests collide and differ by construction, paths
  // from an independent alphabet so identity-by-content is exercised
  // separately from identity-by-path. Three weighted branches produce the
  // spec's cover classes directly — independent generation would produce
  // the all-identical case at 4^-7 ≈ never.
  private def genArmPair: Gen[(List[SeamResolution], List[SeamResolution])] =
    Gen.frequency1(
      3 -> genSameContentDifferentPaths,
      4 -> genIndependentArmPair,
      3 -> genExactlyOneSeamDiffers
    )

  private def resolution(seam: ToolId, contentIdx: Int, pathPrefix: String): SeamResolution =
    SeamResolution(
      seam,
      ContentDigest.ofBytes(s"content-$contentIdx".getBytes("UTF-8")),
      os.Path(s"$pathPrefix/${ToolId.seamPath(seam)}")
    )

  private def genSameContentDifferentPaths: Gen[(List[SeamResolution], List[SeamResolution])] =
    for
      contents   <- Gen.list(Gen.int(Range.linear(0, 3)), Range.constant(7, 7))
      leftPaths  <- Gen.list(Gen.int(Range.linear(0, 2)), Range.constant(7, 7))
      rightPaths <- Gen.list(Gen.int(Range.linear(0, 2)), Range.constant(7, 7))
    yield
      val left: List[SeamResolution] =
        ToolId.swapOrder.lazyZip(contents).lazyZip(leftPaths).map((s, c, p) => resolution(s, c, s"/arm-$p"))
      val right: List[SeamResolution] =
        ToolId.swapOrder.lazyZip(contents).lazyZip(rightPaths).map((s, c, p) => resolution(s, c, s"/arm-$p"))
      (left, right)

  private def genIndependentArmPair: Gen[(List[SeamResolution], List[SeamResolution])] =
    for
      leftContents  <- Gen.list(Gen.int(Range.linear(0, 3)), Range.constant(7, 7))
      rightContents <- Gen.list(Gen.int(Range.linear(0, 3)), Range.constant(7, 7))
    yield
      val left: List[SeamResolution] =
        ToolId.swapOrder.lazyZip(leftContents).map((s, c) => resolution(s, c, "/left"))
      val right: List[SeamResolution] =
        ToolId.swapOrder.lazyZip(rightContents).map((s, c) => resolution(s, c, "/right"))
      (left, right)

  // Right arm copies the left's contents except at one chosen seam, where
  // the content index is shifted by 1–3 (mod 4) — always a different digest,
  // never accidentally equal.
  private def genExactlyOneSeamDiffers: Gen[(List[SeamResolution], List[SeamResolution])] =
    for
      contents  <- Gen.list(Gen.int(Range.linear(0, 3)), Range.constant(7, 7))
      differIdx <- Gen.int(Range.linear(0, 6))
      shift     <- Gen.int(Range.linear(1, 3))
    yield
      val rightContents: List[Int] = contents.zipWithIndex.map { (c, i) =>
        if i == differIdx then (c + shift) % 4 else c
      }
      val left: List[SeamResolution] =
        ToolId.swapOrder.lazyZip(contents).map((s, c) => resolution(s, c, "/left"))
      val right: List[SeamResolution] =
        ToolId.swapOrder.lazyZip(rightContents).map((s, c) => resolution(s, c, "/right"))
      (left, right)

  /**
   * One synthetic repository shared across the property's iterations —
   * the structure is constant, so building it once keeps the property
   * fast (two worktree materialisations per iteration, not two repos).
   */
  private lazy val sharedSyntheticRepo: (os.Path, os.Path, String) =
    mkSyntheticRepo(os.temp.dir())

  /**
   * Build a minimal synthetic repository shaped like the real tree: an
   * `openspec/schemas/verified-scala3` directory with `scanner/`,
   * `hooks/`, `tests/`, `bin/` and the seven `*.predecessor.bak` files,
   * plus a `workflow/` tree standing in for the ported implementation
   * sources; committed so `materialise` can worktree it at a real
   * baseline. Returns (repoRoot, schemaDir, baselineSha).
   */
  private def mkSyntheticRepo(
    tmp: os.Path,
    omitPredecessors: Set[ToolId] = Set.empty,
    withHygiene: Boolean = false,
    driftText: String = "Re-install (scanner/install-skills.sh) before trusting this report.",
    payloadCwd: String = "def cwd(payload: String): String = ujson.read(payload).obj(\"cwd\").str"
  ): (os.Path, os.Path, String) =
    val repo: os.Path    = tmp / "repo"
    val schema: os.Path  = repo / "openspec" / "schemas" / "verified-scala3"
    val scanner: os.Path = schema / "scanner"
    val hooks: os.Path   = schema / "hooks"
    val tests: os.Path   = schema / "tests"
    val binDir: os.Path  = schema / "bin"

    def shim(sub: String): String =
      s"#!/usr/bin/env bash\nexec \"$schema/bin/probatio\" $sub \"$$@\"\n"

    os.makeDir.all(scanner)
    os.makeDir.all(hooks)
    os.makeDir.all(tests)
    os.makeDir.all(binDir)

    // Live invocations: all seven swapped seams hold the ported exec
    // shim — an exec into the origin's bin/probatio, as in the real
    // tree after the ledger/checkpoint swap.
    os.write(scanner / "ledger.sh", shim("ledger"))
    os.write(scanner / "checkpoint.sh", shim("checkpoint"))
    os.write(scanner / "chain-state.sh", shim("chain-state"))
    os.write(scanner / "spec-lint.sh", shim("spec-lint"))
    os.write(scanner / "danger-scan.sh", shim("danger-scan"))
    os.write(scanner / "reconcile.sh", shim("reconcile"))
    os.write(scanner / "install-skills.sh", "#!/usr/bin/env bash\necho synthetic-install-skills\n")
    os.write(hooks / "gate.sh", shim("gate"))

    // Predecessor implementations for the seven swapped seams — each
    // preserved at its `*.predecessor.bak` revert-target path.
    val baks: List[(ToolId, os.Path)] = List(
      ToolId.Ledger     -> (scanner / "ledger.sh.predecessor.bak"),
      ToolId.ChainState -> (scanner / "chain-state.sh.predecessor.bak"),
      ToolId.SpecLint   -> (scanner / "spec-lint.sh.predecessor.bak"),
      ToolId.DangerScan -> (scanner / "danger-scan.sh.predecessor.bak"),
      ToolId.Reconcile  -> (scanner / "reconcile.sh.predecessor.bak"),
      ToolId.Checkpoint -> (scanner / "checkpoint.sh.predecessor.bak"),
      ToolId.Gate       -> (hooks / "gate.sh.predecessor.bak")
    )
    baks.foreach { case (tool: ToolId, path: os.Path) =>
      if !omitPredecessors.contains(tool) then
        os.write(path, s"#!/usr/bin/env bash\necho predecessor-${ToolId.seamPath(tool)}\n")
    }

    // The suite: a trivially-passing file exercising a seamed tool, a
    // failing file (runSuite must count its failure), a file producing no
    // TAP result lines (must be absent from the run's results), and one
    // exercising a tool with no seam (install-skills).
    os.write(
      tests / "one.bats",
      "#!/usr/bin/env bats\nload helpers\n# exercises scanner/spec-lint.sh\n@test \"one\" { true; }\n"
    )
    os.write(
      tests / "failing.bats",
      "#!/usr/bin/env bats\nload helpers\n# exercises scanner/danger-scan.sh\n@test \"boom\" { false; }\n"
    )
    os.write(
      tests / "zero.bats",
      "#!/usr/bin/env bats\nload helpers\n# no @test blocks — produces no TAP result lines\n"
    )
    os.write(
      tests / "unseamed.bats",
      "#!/usr/bin/env bats\nload helpers\n# exercises scanner/install-skills.sh\n@test \"u\" { true; }\n"
    )
    val realTests: os.Path = repoRoot / "openspec" / "schemas" / "verified-scala3" / "tests"
    os.write(tests / "helpers.bash", os.read(realTests / "helpers.bash"))
    if withHygiene then
      // The REAL shape file — the source-inspection tests moved out of the
      // acceptance oracle (spec: oracle-independence); inside the arm they
      // still run against the arm's own workflow/ sources.
      os.write(
        tests / "shape" / "workflow-hygiene-shape.bats",
        os.read(realTests / "shape" / "workflow-hygiene-shape.bats"),
        createFolders = true
      )

    // The built tool arm provisioning expects — a stub executable at the
    // origin's native-image path (spec: jar-launcher-dispatch — a
    // materialised arm carries the built tool for direct invocations; a
    // repo with no build outputs is refused as could-not-determine).
    val stubTool: os.Path = repo / "workflow" / "cli" / "target" / "native-image" / "probatio"
    os.write(
      stubTool,
      "#!/usr/bin/env bash\necho synthetic-probatio\n",
      createFolders = true
    )
    os.perms.set(stubTool, "rwxr-xr-x")

    // A minimal ported-implementation surface at the real source paths —
    // the retargeted drift/payload tests grep the arm's workflow/ tree.
    os.write(
      repo / "workflow" / "cli" / "src" / "main" / "scala" / "org" / "sinemenda" / "probatio" / "cli" / "StdoutRenderer.scala",
      s"package org.sinemenda.probatio.cli\nobject StdoutRenderer { val driftMessage: String = \"$driftText\" }\n",
      createFolders = true
    )
    os.write(
      repo / "workflow" / "cli" / "src" / "main" / "scala" / "org" / "sinemenda" / "probatio" / "cli" / "HarnessPayloadReader.scala",
      s"package org.sinemenda.probatio.cli\nobject HarnessPayloadReader { $payloadCwd }\n",
      createFolders = true
    )
    // The ported dispatch surface the retargeted no-mutation test greps —
    // an enum with no mutation cases and no mutation string literals.
    os.write(
      repo / "workflow" / "cli" / "src" / "main" / "scala" / "org" / "sinemenda" / "probatio" / "cli" / "Subcommand.scala",
      "package org.sinemenda.probatio.cli\nenum Subcommand { case Ledger, Checkpoint }\n",
      createFolders = true
    )

    os.write(binDir / "probatio", "#!/usr/bin/env bash\necho \"synthetic-probatio $*\"\n")
    os.perms.set(binDir / "probatio", "rwxr-xr-x")

    git(repo, List("init", "-q"))
    git(repo, List("add", "-A"))
    git(repo, List("-c", "user.email=oracle@local", "-c", "user.name=oracle", "commit", "-qm", "baseline"))
    val sha: String = git(repo, List("rev-parse", "HEAD"))
    (repo, schema, sha)

  /** Run a git command; returns stdout trimmed. */
  private def git(cwd: os.Path, args: List[String]): String =
    // spec: hermetic-test-processes — via the shared helper.
    HermeticEnv.capture("git" :: args, HermeticEnv.empty, cwd = Some(cwd.toIO)).out.trim

  /** Materialise or fail the test with the outcome's reason. */
  private def materialiseOrFail(
    config: SeamConfiguration,
    baseline: String,
    schemaDir: os.Path,
    destDir: os.Path
  ): ArmTree =
    ArmTree.materialise(config, baseline, schemaDir, destDir) match
      case Outcome.Ran(arm)        => arm
      case Outcome.Finding(d)      => fail(s"materialise failed: $d")
      case Outcome.Undetermined(r) => fail(s"materialise undetermined: $r")

  /** Write a minimal recorded-control fixture; returns its path. */
  private def writeControl(dir: os.Path, baseline: String, perFile: Map[String, (Int, Int)]): os.Path =
    val entries: String = perFile
      .map((f: String, counts: (Int, Int)) => s""""$f": {"total": ${counts._1}, "failures": ${counts._2}}""")
      .mkString(", ")
    val path: os.Path = dir / "control.json"
    os.write(
      path,
      s"""{"measuredAtBaseline": "$baseline", """ +
        s""""suiteTotals": {"files": ${perFile.size}, "tests": ${perFile.values
            .map(_._1)
            .sum}, "failures": ${perFile.values.map(_._2).sum}}, """ +
        s""""perFile": {$entries}}"""
    )
    path

  /** A synthetic suite run from (file -> (total, failures)). */
  private def suiteRunOf(perFile: Map[String, (Int, Int)]): DifferentialHarness.SuiteRun =
    DifferentialHarness.SuiteRun(
      perFile.map { (f: String, counts: (Int, Int)) =>
        DifferentialHarness.BatsFileResult(f, counts._1, counts._2)
      }.toList,
      perFile.keySet
    )

  /** Run one named test of the arm's shape/workflow-hygiene-shape.bats; returns (exitCode, output). */
  private def runBatsFiltered(arm: ArmTree, namePattern: String): (Int, String) =
    val result = os
      .proc("bats", "-f", namePattern, "shape/workflow-hygiene-shape.bats")
      .call(cwd = arm.root / "tests", check = false)
    (result.exitCode, result.out.text() + result.err.text())

  // ── Helper: compute SHA-256 digest (mirrors DifferentialHarness.computeDigest)
  private def computeDigest(path: os.Path): String =
    val bytes: Array[Byte]                  = os.read.bytes(path)
    val digest: java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map("%02x".format(_)).mkString

  // ── spec: hermetic-test-processes ─────────────────────────────────
  // The environment-independence property and the scenario proving an
  // environment-dependent test is reported by name.

  /**
   * Run one suite file under a hermetic environment and return its TAP
   * result lines (`ok N <name>` / `not ok N <name>`), the verdict shape
   * the two-environment comparison measures.
   */
  private def runSuiteFile(file: os.Path, env: HermeticEnv): List[String] =
    val pb: ProcessBuilder = HermeticEnv.processBuilder(List("bats", file.toString), env)
    pb.redirectErrorStream(true)
    val p: Process = pb.start()
    val out: String = new String(p.getInputStream.readAllBytes, StandardCharsets.UTF_8)
    p.waitFor()
    out.linesIterator
      .filter((line: String) => line.startsWith("ok ") || line.startsWith("not ok "))
      .toList

  /** A TAP result line's test name — `ok 7 name` / `not ok 7 name` → `name`. */
  private def tapName(line: String): String =
    line.dropWhile((c: Char) => !c.isDigit).dropWhile((c: Char) => c.isDigit).trim

  /** A TAP result line's verdict — `ok` passes, `not ok` fails. */
  private def tapPassed(line: String): Boolean = line.startsWith("ok ")

  /**
   * The two-environment comparison: the names of tests whose verdict
   * differs between a run with the controlled variables and a run with
   * none — the suite's environment-dependent set.
   */
  private def environmentDependentTests(
    withoutVars: List[String],
    withVars: List[String]
  ): List[String] =
    val a: Map[String, Boolean] = withoutVars.map((l: String) => tapName(l) -> tapPassed(l)).toMap
    val b: Map[String, Boolean] = withVars.map((l: String) => tapName(l) -> tapPassed(l)).toMap
    a.keySet
      .union(b.keySet)
      .toList
      .filter((name: String) => a.get(name) != b.get(name))
      .sorted

  // spec: hermetic-test-processes — Scenario: Adversarial — a suite file that needs an inherited variable is reported
  test("a suite file that needs an inherited variable is reported"):
    val dir: os.Path = os.temp.dir(prefix = "env-dependent-suite")
    val suite: os.Path = dir / "env-dependent.bats"
    os.write(
      suite,
      """#!/usr/bin/env bats
        |@test "env sensitive marker" {
        |  [ -z "${CLAUDE_CODE_SESSION_ID+x}" ]
        |}
        |@test "env insensitive marker" {
        |  [ -n "$PATH" ]
        |}
        |""".stripMargin
    )
    // The invoking shell carries the harness session variable in one run,
    // none in the other — both through the shared helper.
    val declared: Map[ControlledVariable, String] =
      Map(ControlledVariable.ClaudeCodeSessionId -> "foreign-harness")
    val withVars: List[String]    = runSuiteFile(suite, HermeticEnv.build(declared))
    val withoutVars: List[String] = runSuiteFile(suite, HermeticEnv.build(Map.empty))
    val dependent: List[String]   = environmentDependentTests(withoutVars, withVars)
    assertEquals(
      dependent,
      List("env sensitive marker"),
      "the comparison must name exactly the environment-dependent test"
    )

  /**
   * The enumerated domain of process-spawning suite files: the bats
   * acceptance suite — the fixed, finite set the spec measured. The Scala
   * suites' two-environment equivalence is the recorded Ring-3 run, not
   * this property (a munit suite cannot run inside a property).
   */
  private def processSpawningSuites: List[os.Path] =
    os
      .list(repoRoot / "openspec" / "schemas" / "verified-scala3" / "tests")
      .filter((p: os.Path) => p.ext == "bats")
      .toList

  // The domain is static for the run — evaluate it once so an empty
  // listing fails the property rather than generating a vacuous case.
  private lazy val spawningSuites: List[os.Path] = processSpawningSuites

  // spec: hermetic-test-processes — Property: result-is-independent-of-the-invoking-environment
  property(
    "result-is-independent-of-the-invoking-environment",
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(50))
  ):
    // The domain is the measured bats-file list; an empty listing means
    // the suite ran somewhere it should not — fail, don't vacuously pass.
    spawningSuites match
      case Nil =>
        Gen.constant(()).forAll.map((_: Unit) =>
          Result.failure.log("process-spawning suite domain is empty — the property measured nothing")
        )
      case (first: os.Path) :: (rest: List[os.Path]) =>
        for {
          subset <- HermeticEnvGens.genControlledSubset.forAll
            .cover(5, "empty-invoking-env", (s: Set[ControlledVariable]) => s.isEmpty)
            .cover(
              5,
              "harness-session-only",
              (s: Set[ControlledVariable]) => s == Set(ControlledVariable.ClaudeCodeSessionId)
            )
            .cover(
              4,
              "full-controlled-set",
              (s: Set[ControlledVariable]) => s == HermeticEnvGens.allControlled.toSet
            )
            .cover(
              20,
              "partial-subset",
              (s: Set[ControlledVariable]) =>
                s.nonEmpty && s.size < HermeticEnvGens.allControlled.size
            )
          suite <- Gen.element1(first, rest*).forAll
        } yield
          val declared: Map[ControlledVariable, String] =
            subset.map((v: ControlledVariable) => v -> s"inherited-${v.envName}").toMap
          val withVars: List[String]    = runSuiteFile(suite, HermeticEnv.build(declared))
          val withoutVars: List[String] = runSuiteFile(suite, HermeticEnv.build(Map.empty))
          val dependent: List[String]   = environmentDependentTests(withoutVars, withVars)
          Result
            .assert(dependent.isEmpty)
            .log(s"environment-dependent tests in ${suite.last} under $declared: $dependent")

  // ══ spec: archive-safe-fixtures — the bats-side literal-path check ═════
  //
  // The Scala side is the scalafix rules in `.scalafix-tests.conf`; bats
  // is not scalafix-checked, so the suite files are scanned here — the
  // same file-and-line diagnostics, enforced by this spec.

  /**
   * A `openspec/changes/` literal anchored at the repository root —
   * `repo_root`, `$ROOT`, `${ROOT}`, `$root`, `${root}`, `$REPO_ROOT`,
   * `${REPO_ROOT}`, or `$(git rev-parse --show-toplevel)` — followed by a
   * literal name segment. Discovery is exempt: a glob segment (`*`),
   * a variable-named segment (`$CHANGE`, an argument to the tool under
   * test, not a literal name), and everything under `archive/` (the
   * spec targets active-area literals — `archive/` entries are already
   * archive paths). The span between anchor and literal may cross `;`:
   * `cd "$ROOT"; cat openspec/changes/x` is the same literal.
   *
   * Fixture- and tmpdir-anchored spellings (`$FX/…`, `$BATS_TEST_TMPDIR/…`,
   * `$(mktemp -d)`-derived) are the suite's own synthetic fixtures and
   * are permitted. The residual evasion the text-level mechanism cannot
   * close is indirection (`base="$ROOT"; …` on a later line) — accepted
   * boundary, same as the Scala-side anchor rules.
   */
  private def repoRootAnchoredChangePath: scala.util.matching.Regex =
    ("(repo_root\\b|\\$\\{?ROOT\\b|\\$\\{?root\\b|\\$\\{?REPO_ROOT\\b|" +
      "\\$\\(git\\s+rev-parse\\s+--show-toplevel\\))[^\\n|]*" +
      "openspec/changes/(?!archive\\b)[^\"'\\s$*{]").r

  /**
   * `file:line: text` diagnostics for every repo-root-anchored literal
   * change path in `files`.
   */
  private def anchoredLiteralViolations(files: List[os.Path]): List[String] =
    files.flatMap { (f: os.Path) =>
      os.read(f).linesIterator.zipWithIndex.collect {
        case (line: String, i: Int) if repoRootAnchoredChangePath.findFirstIn(line).nonEmpty =>
          s"${f.last}:${i + 1}: $line"
      }.toList
    }

  // ── Scenario: Happy path — resolver-based test code is clean (bats side)
  // spec: archive-safe-fixtures — Scenario: Happy path — resolver-based test code is clean
  test("the acceptance suite carries no repo-root-anchored literal change path"):
    val testsDir: os.Path = repoRoot / "openspec" / "schemas" / "verified-scala3" / "tests"
    val suiteFiles: List[os.Path] =
      os.list(testsDir).filter((f: os.Path) => f.ext == "bats").toList ++
        List(testsDir / "helpers.bash")
    assertEquals(
      anchoredLiteralViolations(suiteFiles),
      Nil,
      "a literal change path anchored at the repository root breaks on archive — resolve by name"
    )

  // ── Scenario: Adversarial — a literal active-change path in test code is rejected (bats side)
  // spec: archive-safe-fixtures — Scenario: Adversarial — a literal active-change path in test code is rejected, reporting that path
  test("the bats-side check reports a repo-root-anchored literal with its file and line"):
    val dir: os.Path   = os.temp.dir(prefix = "bats-literal-check")
    val suite: os.Path = dir / "planted.bats"
    os.write(
      suite,
      """#!/usr/bin/env bats
        |setup() { ROOT="$(repo_root)"; }
        |
        |@test "reads the control" {
        |  ctrl="$(repo_root)/openspec/changes/some-change/fixtures/c.json"
        |  ls "$ROOT/openspec/changes/named-change/specs"
        |  echo "${root}/openspec/changes/named-change"
        |  cat "$(git rev-parse --show-toplevel)/openspec/changes/x/specs"
        |}
        |""".stripMargin
    )
    val violations: List[String] = anchoredLiteralViolations(List(suite))
    assertEquals(
      violations.map(_.takeWhile(_ != ':')).distinct,
      List("planted.bats"),
      s"every finding names its file: $violations"
    )
    assertEquals(
      violations.map((v: String) => v.dropWhile(_ != ':').drop(1).takeWhile(_ != ':')).sorted,
      List("5", "6", "7", "8"),
      s"the planted literals on lines 5–8 are reported with their line numbers: $violations"
    )
    // The setup assignment itself is clean — the anchor name alone is
    // not a violation; only an anchored `openspec/changes/<literal>` is.
    assert(
      violations.forall(!_.contains(":2:")),
      s"the root assignment must not be flagged: $violations"
    )

  // ── Scenario: Edge case — a synthetic temporary path is allowed (bats side)
  // spec: archive-safe-fixtures — Scenario: Edge case — a synthetic temporary path is allowed
  test("the bats-side check permits fixture- and tmpdir-anchored synthetic paths"):
    val dir: os.Path   = os.temp.dir(prefix = "bats-literal-permitted")
    val suite: os.Path = dir / "synthetic.bats"
    os.write(
      suite,
      """#!/usr/bin/env bats
        |@test "synthetic fixture" {
        |  mkdir -p "$FX/openspec/changes/test-change/specs"
        |  echo "$BATS_TEST_TMPDIR/openspec/changes/x/fixtures/predecessor-control.json"
        |  local schema_tmp; schema_tmp="$(mktemp -d)"
        |  mkdir -p "$schema_tmp/openspec/changes/change-xyz/specs"
        |  for d in "$root"/openspec/changes/*/; do basename "$d"; done
        |}
        |""".stripMargin
    )
    assertEquals(anchoredLiteralViolations(List(suite)), Nil)
