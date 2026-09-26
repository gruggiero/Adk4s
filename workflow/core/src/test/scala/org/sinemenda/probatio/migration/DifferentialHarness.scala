package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.Outcome

/**
 * The differential harness — runs the acceptance suite against two
 * MATERIALISED arms and compares the per-file results.
 *
 * Each arm is an [[ArmTree]]: a copy of the tool tree in which every seam
 * has been resolved to a named implementation and digested by content.
 * The comparison refuses to run when the two arms resolve every seam to
 * identical content — a comparison of a run with itself cannot show a
 * regression, so the absence of one carries no evidence.
 *
 * Both arms are materialised from the same repository at the same
 * baseline — each a `git worktree` of that commit — so the suite inside
 * each arm is the same committed suite by construction.
 *
 * This object is callable from tests and from the `probatioOracleDiff`
 * sbt task.
 *
 * spec: cutover-gate — Requirement: Both runs of the comparison execute in the same repository under the same suite
 * spec: cutover-gate — Implementation Anchors: DifferentialHarness
 * spec: differential-harness-integrity — Requirement: A comparison whose arms resolve identically is refused
 */
object DifferentialHarness:

  import SeamTypes.*

  /**
   * The result of running a single bats file: the file name, total
   * test count, and failure count.
   */
  final case class BatsFileResult(
    fileName: String,
    total: Int,
    failures: Int
  )

  /**
   * The result of running the full suite under one arm: the per-file
   * results.
   */
  final case class SuiteRun(
    fileResults: List[BatsFileResult],
    fileSet: Set[String]
  )

  /**
   * The divergence verdict for two arms' resolved seams — the pre-suite
   * guard. Identical iff every paired seam holds the same content digest.
   *
   * spec: differential-harness-integrity — Contract: divergence
   */
  def divergence(left: List[SeamResolution], right: List[SeamResolution]): ArmDivergence =
    require(
      left.length == right.length && left.map(_.seam) == right.map(_.seam),
      "divergence requires both arms to resolve the same seams in the same order"
    )
    val differing: List[(SeamResolution, SeamResolution)] =
      left.zip(right).filter((l, r) => l.implementationDigest != r.implementationDigest)
    if differing.isEmpty
    then ArmDivergence.Identical(left)
    else ArmDivergence.Diverged(differing)

  /**
   * Run the oracle suite inside a materialised arm and return the
   * per-file results.
   *
   * The suite is the arm's own copy (`arm.root / "tests"`): the bats files
   * resolve the arm's `scanner/`, `hooks/`, and `bin/` relative to their
   * own location, so each run measures the arm's materialised seam
   * contents — not the live tree's.
   *
   * spec: differential-harness-integrity — Requirement: The comparison resolves each arm to a materialised tree
   */
  def runSuite(arm: ArmTree): SuiteRun =
    // os.RelPath keeps the segment out of os.Path's literal macro: a
    // mutated literal segment would be a compile-time error stryker
    // cannot roll back; here it is a runtime throw the tests kill.
    val testsDir: os.Path = arm.root / os.RelPath("tests")
    val batsFiles: IndexedSeq[os.Path] =
      os.list(testsDir).filter(_.ext == "bats").sortBy(_.last)
    val results: List[BatsFileResult] = batsFiles.toList.flatMap { (f: os.Path) =>
      // stdin must be CLOSED: hooks/gate.sh does `PAYLOAD="$(cat)"` — with
      // an inherited open stdin (e.g. sbt's pipe) that read blocks forever.
      val res = os
        .proc("bats", f.last)
        .call(cwd = testsDir, check = false, stderr = os.Pipe, stdin = Array.empty[Byte])
      val lines: List[String] = res.out.text().linesIterator.toList
      val failures: Int       = lines.count(_.startsWith("not ok "))
      val total: Int          = lines.count(l => l.startsWith("ok ") || l.startsWith("not ok "))
      // A file that produced no TAP results (bats error, load failure)
      // yields no result row — the diff then marks it absent from the
      // run, making the comparison incomplete rather than a silent pass.
      if total == 0 then None else Some(BatsFileResult(f.last, total, failures))
    }
    SuiteRun(results, batsFiles.map(_.last).toSet)

  /**
   * The comparison entry point: refuse identical arms, otherwise run the
   * suite against both arms and return the per-file differential.
   *
   * `Left(Identical)` is a REFUSAL — distinguishable from every
   * `CutoverVerdict`; it is never a passing verdict and carries the seam
   * resolutions that proved the arms identical.
   *
   * spec: differential-harness-integrity — Requirement: A comparison whose arms resolve identically is refused
   * spec: differential-harness-integrity — Scenario: Adversarial — identical arms yield a refusal, not Proceed
   */
  def compare(predecessor: ArmTree, ported: ArmTree): Either[ArmDivergence.Identical, DifferentialResult] =
    require(
      predecessor.origin == ported.origin && predecessor.baseline == ported.baseline,
      "both arms must be materialised from the same repository at the same baseline"
    )
    divergence(predecessor.resolutions, ported.resolutions) match
      case identical: ArmDivergence.Identical => Left(identical)
      case _: ArmDivergence.Diverged =>
        Right(diff(runSuite(predecessor), runSuite(ported), predecessor.origin.toString))

  /**
   * The tree-relative tool paths each suite file exercises, derived by
   * scanning the file's source for `scanner/`, `hooks/`, and `bin/` tool
   * references. Used to mark files that exercise a tool with no seam —
   * such a file is reported as not-compared, never as at-parity.
   *
   * spec: differential-harness-integrity — Scenario: Adversarial — a suite file exercising an unrepresented tool is not claimed as compared
   */
  def exercisedToolPaths(arm: ArmTree): Map[String, Set[String]] =
    val testsDir: os.Path = arm.root / os.RelPath("tests")
    // A file exercises a tool iff its text names a file that actually
    // exists under the arm's scanner/, hooks/, or bin/ — a path-shaped
    // substring like `bin/env` (from `#!/usr/bin/env`) is not a tool.
    val toolPaths: Set[String] =
      List("scanner", "hooks", "bin").flatMap { (d: String) =>
        val dir: os.Path = arm.root / d
        if os.exists(dir) then os.list(dir).filter(os.isFile).map((f: os.Path) => s"$d/${f.last}")
        else List.empty[String]
      }.toSet
    os.list(testsDir)
      .filter(_.ext == "bats")
      .map((f: os.Path) => f.last -> toolPaths.filter(os.read(f).contains))
      .toMap
      .filter((_: String, paths: Set[String]) => paths.nonEmpty)

  /**
   * The exercised tool paths that are NOT in the seam set, per suite file.
   * Files with a non-empty entry are not-compared: they exercise a tool
   * whose live invocation path no seam covers.
   *
   * spec: differential-harness-integrity — Requirement: Every swapped seam is represented in the comparison
   */
  def unseamedToolPaths(arm: ArmTree): Map[String, Set[String]] =
    val seamPaths: Set[String] = ToolId.swapOrder.map(ToolId.seamPath).toSet
    exercisedToolPaths(arm)
      .map((file, paths) => file -> paths.diff(seamPaths))
      .filter((_, paths) => paths.nonEmpty)

  /**
   * Check a predecessor-arm suite run against the recorded control.
   *
   * The control is a measured fact at a named baseline: if `arm.baseline`
   * differs from the control's recorded baseline the check is
   * `Undetermined` — never a pass or a fail. At the recorded baseline the
   * check is `Ran(())` iff every suite file's predecessor failure count
   * equals the control's, and `Finding` naming the differing files
   * otherwise.
   *
   * spec: differential-harness-integrity — Requirement: The comparison reproduces the recorded predecessor control
   * spec: differential-harness-integrity — Scenario: Error path — a comparison at a different baseline is not checked against the control
   */
  def checkPredecessorControl(arm: ArmTree, run: SuiteRun, controlPath: os.Path): Outcome[Unit] =
    if !os.exists(controlPath) then Outcome.Undetermined(s"recorded predecessor control not found: $controlPath")
    else
      // The control is an external fixture: unreadable or malformed JSON,
      // or mistyped fields, are could-not-determine — never an exception
      // escaping the check, and never a pass.
      scala.util
        .Try(checkAgainstControl(arm, run, controlPath))
        .fold(
          (_: Throwable) =>
            Outcome.Undetermined(s"recorded predecessor control is unreadable or malformed: $controlPath"),
          (outcome: Outcome[Unit]) => outcome
        )

  private def checkAgainstControl(arm: ArmTree, run: SuiteRun, controlPath: os.Path): Outcome[Unit] =
    val control: ujson.Value    = ujson.read(os.read(controlPath))
    val controlBaseline: String = control("measuredAtBaseline").str
    if arm.baseline != controlBaseline then
      Outcome.Undetermined(
        s"baseline mismatch — arm materialised at ${arm.baseline}, control recorded at $controlBaseline"
      )
    else
      val perFile = control("perFile").obj
      val runByFile: Map[String, BatsFileResult] =
        run.fileResults.map((r: BatsFileResult) => r.fileName -> r).toMap
      val differing: List[String] = perFile.keys.toList.sorted.flatMap { (file: String) =>
        val expected = perFile(file).obj
        runByFile.get(file) match
          case None => Some(s"$file (absent from run)")
          case Some(result) =>
            val expectedTotal: Int    = expected("total").num.toInt
            val expectedFailures: Int = expected("failures").num.toInt
            if result.failures == expectedFailures && result.total == expectedTotal then None
            else
              Some(
                s"$file (control ${expectedTotal}t/${expectedFailures}f, run ${result.total}t/${result.failures}f)"
              )
      }
      val extras: List[String] =
        (runByFile.keySet -- perFile.keySet).toList.sorted.map((f: String) => s"$f (not in control)")
      val mismatches: List[String] = differing ++ extras
      if mismatches.isEmpty then Outcome.Ran(())
      else
        Outcome.Finding(
          s"predecessor run differs from the recorded control: ${mismatches.mkString(", ")}"
        )

  /**
   * Locate the recorded predecessor control fixture of `changeName`
   * through the archive-aware resolver and return its path.
   *
   * `Ran(path)` when the change resolves and `fixtures/predecessor-control.json`
   * exists under the resolved directory; `Undetermined` naming every
   * searched location when the change is absent, or naming the resolved
   * directory when the fixture file is missing — a missing control is
   * never a pass.
   *
   * spec: archive-safe-fixtures — Requirement: The recorded predecessor control is read after archiving
   * spec: archive-safe-fixtures — Scenario: Adversarial — a missing control fails naming every searched location
   */
  def predecessorControl(changeName: String, openspecDir: os.Path): Outcome[os.Path] = ???

  /**
   * Compute the differential result from two suite runs.
   *
   * Both runs must have used the same repository and the same suite
   * file set. The comparison is complete when both runs produced a
   * result for every file in the suite and the file sets match.
   *
   * spec: cutover-gate — Requirement: Both runs of the comparison execute in the same repository under the same suite
   */
  def diff(
    predecessor: SuiteRun,
    ported: SuiteRun,
    repository: String
  ): DifferentialResult =
    val suiteFileSet: Set[String] = predecessor.fileSet.union(ported.fileSet)
    val files: List[FileComparison] = suiteFileSet.toList.sorted.map { (fileName: String) =>
      val predResult: Option[BatsFileResult] = predecessor.fileResults.find(_.fileName == fileName)
      val portResult: Option[BatsFileResult] = ported.fileResults.find(_.fileName == fileName)
      val total: Int                         = predResult.orElse(portResult).map(_.total).getOrElse(0)
      val predFailures: Int                  = predResult.map(_.failures).getOrElse(0)
      val portFailures: Int                  = portResult.map(_.failures).getOrElse(0)
      FileComparison(
        fileName = fileName,
        total = total,
        predecessorFailures = predFailures,
        portedFailures = portFailures,
        predecessorPresent = predResult.isDefined,
        portedPresent = portResult.isDefined
      )
    }
    DifferentialResult(files, repository, suiteFileSet)

  /**
   * Verify that the suite file digests match the repository's.
   *
   * Returns `Left(fileName)` naming the first modified suite file, or
   * `Right(())` if all suite files are byte-identical to the
   * repository's.
   *
   * spec: cutover-gate — Scenario: Adversarial — a comparison against a modified suite is refused
   */
  def verifySuiteDigests(oracleDir: os.Path, expectedDigests: Map[String, String]): Either[String, Unit] =
    val batsFiles: IndexedSeq[os.Path] = os.list(oracleDir).filter(_.ext == "bats")
    val mismatch: Option[String] = batsFiles.flatMap { (f: os.Path) =>
      val fileName: String     = f.last
      val actualDigest: String = computeDigest(f)
      expectedDigests.get(fileName) match
        case Some(expected) if expected != actualDigest => Some(fileName)
        case _                                          => None
    }.headOption
    mismatch match
      case Some(fileName) => Left(fileName)
      case None           => Right(())

  /** Compute a SHA-256 digest of a file. */
  private def computeDigest(path: os.Path): String =
    ContentDigest.hex(ContentDigest.ofFile(path))
