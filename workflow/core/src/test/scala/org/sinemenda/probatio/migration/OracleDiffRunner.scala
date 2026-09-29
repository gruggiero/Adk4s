package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ProbatioSuite

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.Using

/**
 * Runner for the `probatioOracleDiff` sbt task.
 *
 * This test class is the entry point for `sbt probatioOracleDiff`. It
 * materialises two arms of the tool tree — predecessor and ported — runs
 * the suite against each, parses both outputs, and emits a
 * `DifferentialResult`. The cutover gate then decides based on the
 * per-file comparison.
 *
 * The runner asserts the arms diverged before reporting a verdict: a
 * refusal (`Left(Identical)`) is printed as such and is never mapped to
 * a passing verdict. Files exercising tools outside the seam set are
 * reported as not-compared, never as at-parity.
 *
 * spec: cutover-gate — Implementation Anchors: probatioOracleDiff
 * spec: differential-harness-integrity — Requirement: A comparison whose arms resolve identically is refused
 * spec: differential-harness-integrity — Scenario: Adversarial — a suite file exercising an unrepresented tool is not claimed as compared
 */
final class OracleDiffRunner extends ProbatioSuite:

  import SeamTypes.*

  // The oracle diff runs all 17 bats files twice. ~3 minutes locally via
  // the native binary; the hosted runner has no native image, so the
  // ported arm pays JVM startup per tool call (~3m37s for the suite on a
  // fast dev machine, ~2× on CI). 20 minutes is ~2× that worst case.
  override val munitTimeout: FiniteDuration =
    FiniteDuration(20, TimeUnit.MINUTES)

  /**
   * The repository root, resolved via git — `os.pwd` is unreliable under
   * forked test runners (sbt `Test / fork` and the Stryker4s runner set
   * the working directory to the module base). Unresolvable is an
   * error, not a fallback: a wrong cwd must never read as "the schema
   * is absent" and silently skip the check.
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
      .getOrElse(sys.error("repository root could not be resolved via git rev-parse"))

  // ── probatioOracleDiff entry point
  // Materialises both arms, asserts divergence, runs the differential,
  // and prints the result + verdict.
  // This test is NOT ignored — it is the sbt task's entry point.
  // However, it requires bats to be installed and the schema directory
  // to exist. In environments without bats, it will fail with a clear
  // message.
  test("probatioOracleDiff: run differential and decide"):
    val schemaDir: os.Path = repoRoot / "openspec" / "schemas" / "verified-scala3"
    if !os.exists(schemaDir / "tests") then
      println("[probatioOracleDiff] oracle directory not found: " + (schemaDir / "tests").toString)
      println("[probatioOracleDiff] SKIPPED — no oracle to compare against")
    else
      val portedConfig: SeamConfiguration =
        SeamConfiguration.fromPorted(ToolId.swapOrder.toSet)
      val baseline: String  = "HEAD"
      val workRoot: os.Path = os.temp.dir(prefix = "probatio-arms-", deleteOnExit = true)

      println("[probatioOracleDiff] materialising predecessor arm...")
      val predArm: Outcome[ArmTree] =
        ArmTree.materialise(portedConfig.withPredecessor, baseline, schemaDir, workRoot / "predecessor")

      println("[probatioOracleDiff] materialising ported arm...")
      val portArm: Outcome[ArmTree] =
        ArmTree.materialise(portedConfig, baseline, schemaDir, workRoot / "ported")

      (predArm, portArm) match
        case (Outcome.Undetermined(reason), _) =>
          println(s"[probatioOracleDiff] UNDETERMINED — predecessor arm: $reason")
          fail(s"predecessor arm could not be materialised: $reason")
        case (_, Outcome.Undetermined(reason)) =>
          println(s"[probatioOracleDiff] UNDETERMINED — ported arm: $reason")
          fail(s"ported arm could not be materialised: $reason")
        case (Outcome.Finding(desc), _) =>
          println(s"[probatioOracleDiff] FINDING — predecessor arm: $desc")
          fail(s"predecessor arm materialisation reported a finding: $desc")
        case (_, Outcome.Finding(desc)) =>
          println(s"[probatioOracleDiff] FINDING — ported arm: $desc")
          fail(s"ported arm materialisation reported a finding: $desc")
        case (Outcome.Ran(pred), Outcome.Ran(port)) =>
          // Arms are git worktrees registered in the origin's
          // .git/worktrees — the resource removes them on close so a run
          // leaves no residue, whether the comparison passes or throws.
          Using.resource(WorktreeCleanup(schemaDir, List(workRoot / "predecessor", workRoot / "ported"))) { _ =>
            DifferentialHarness.compare(pred, port) match
              case Left(identical) =>
                val seamNames: String = identical.seams.map(_.seam.toString).mkString(", ")
                println(s"[probatioOracleDiff] REFUSED — arms identical at every seam: $seamNames")
                println("[probatioOracleDiff] REFUSAL is not a verdict — no comparison evidence exists")
                fail("the comparison refused: both arms resolved to identical implementations")
              case Right(d) =>
                println("[probatioOracleDiff] arms diverged — differential result:")
                d.files.foreach { (f: FileComparison) =>
                  println(
                    s"  ${f.fileName}: total=${f.total} pred=${f.predecessorFailures} ported=${f.portedFailures}" ++
                      s" predPresent=${f.predecessorPresent} portPresent=${f.portedPresent}"
                  )
                }
                println(s"[probatioOracleDiff] complete=${d.isComplete} hasRegression=${d.hasRegression}")
                if d.hasRegression then println(s"[probatioOracleDiff] worse files: ${d.worseFileNames.mkString(", ")}")

                val notCompared: Map[String, Set[String]] = DifferentialHarness.unseamedToolPaths(port)
                if notCompared.nonEmpty then
                  println("[probatioOracleDiff] not-compared files (exercise tools with no seam):")
                  notCompared.foreach { (file: String, tools: Set[String]) =>
                    println(s"  $file: ${tools.toList.sorted.mkString(", ")}")
                  }

                val verdict: CutoverVerdict = CutoverGate.decide(d)
                verdict match
                  case CutoverVerdict.Proceed =>
                    println("[probatioOracleDiff] VERDICT: PROCEED — no file is worse")
                  case CutoverVerdict.Revert(evidence) =>
                    println(
                      "[probatioOracleDiff] VERDICT: REVERT — " ++
                        s"${evidence.worseFileNames.length} file(s) worse: " ++
                        evidence.worseFileNames.mkString(", ")
                    )

                // Assert the comparison ran (at least one file was processed)
                assert(d.files.nonEmpty, "the differential result must contain at least one file comparison")
          }

  // ── probatioOracleControl entry point
  // Materialises a PREDECESSOR arm at the control's recorded baseline and
  // asserts the suite run reproduces the recorded per-file counts exactly.
  // This is the evidence that the fixture is a faithful control, and that
  // the materialised arm measures the same thing the hand-run control did.
  //
  // spec: differential-harness-integrity — Requirement: The comparison reproduces the recorded predecessor control
  test("probatioOracleControl: predecessor arm reproduces the recorded control"):
    val schemaDir: os.Path = repoRoot / "openspec" / "schemas" / "verified-scala3"
    if !os.exists(schemaDir / "tests") then println("[probatioOracleControl] SKIPPED — schema tests absent")
    else
      // spec: archive-safe-fixtures — the control is located through
      // the archive-aware resolver, never a literal active-area path:
      // the change is archived and its fixtures live under
      // `changes/archive/`. An unlocatable control is a loud
      // undetermined naming every searched location — never a silent
      // skip.
      val controlPath: os.Path =
        DifferentialHarness.predecessorControl("repair-probatio-cutover", repoRoot / "openspec") match
          case Outcome.Ran(path) => path
          case other =>
            println(s"[probatioOracleControl] UNDETERMINED — $other")
            fail(s"the recorded predecessor control could not be located: $other")
      // spec: archive-safe-fixtures — a present-but-malformed control is
      // a loud undetermined, not a raw parse exception (and never a pass).
      val controlBaseline: String =
        scala.util
          .Try(ujson.read(os.read(controlPath))("measuredAtBaseline").str)
          .fold(
            (e: Throwable) => {
              val msg: String =
                s"the recorded predecessor control at $controlPath is unreadable or malformed: ${e.getMessage}"
              println(s"[probatioOracleControl] UNDETERMINED — $msg")
              fail(msg)
            },
            (s: String) => s
          )
      val allPredecessor: SeamConfiguration =
        SeamConfiguration.fromPorted(ToolId.swapOrder.toSet).withPredecessor
      val workRoot: os.Path = os.temp.dir(prefix = "probatio-control-", deleteOnExit = true)

      println(s"[probatioOracleControl] materialising predecessor arm at $controlBaseline...")
      ArmTree.materialise(allPredecessor, controlBaseline, schemaDir, workRoot / "predecessor") match
        case Outcome.Undetermined(reason) =>
          println(s"[probatioOracleControl] UNDETERMINED — $reason")
          fail(s"predecessor arm could not be materialised: $reason")
        case Outcome.Finding(desc) =>
          println(s"[probatioOracleControl] FINDING — $desc")
          fail(s"predecessor arm materialisation reported a finding: $desc")
        case Outcome.Ran(arm) =>
          Using.resource(WorktreeCleanup(schemaDir, List(workRoot / "predecessor"))) { _ =>
            val run: DifferentialHarness.SuiteRun = DifferentialHarness.runSuite(arm)
            val check: Outcome[Unit]              = DifferentialHarness.checkPredecessorControl(arm, run, controlPath)
            run.fileResults.foreach { (r: DifferentialHarness.BatsFileResult) =>
              println(s"  ${r.fileName}: total=${r.total} failures=${r.failures}")
            }
            check match
              case Outcome.Ran(()) =>
                println(
                  "[probatioOracleControl] CONTROL REPRODUCED — predecessor arm matches the recorded control per file"
                )
              case Outcome.Finding(desc) =>
                println(s"[probatioOracleControl] FINDING — $desc")
                fail(desc)
              case Outcome.Undetermined(reason) =>
                println(s"[probatioOracleControl] UNDETERMINED — $reason")
                fail(reason)
          }

/**
 * Removes materialised-arm git worktrees on close — the arms are
 * registered in the origin's `.git/worktrees`, so a run must deregister
 * them or the repository accumulates stale entries.
 */
final private class WorktreeCleanup(schemaDir: os.Path, worktrees: List[os.Path]) extends AutoCloseable:
  def close(): Unit =
    worktrees.foreach { (wt: os.Path) =>
      // spec: hermetic-test-processes — via the shared helper.
      HermeticEnv.run(
        List("git", "-C", schemaDir.toString, "worktree", "remove", "--force", wt.toString),
        HermeticEnv.empty
      )
    }
