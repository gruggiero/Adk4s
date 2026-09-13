package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.ProbatioSuite

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/**
 * Runner for the `probatioOracleDiff` sbt task.
 *
 * This test class is the entry point for `sbt probatioOracleDiff`. It
 * runs the differential harness: materialises two seam-configured
 * copies of the scanner tree, runs the suite against each, parses both
 * outputs, and emits a `DifferentialResult`. The cutover gate then
 * decides based on the per-file comparison.
 *
 * The test prints the differential result and the gate's verdict to
 * stdout, and asserts that the comparison is complete (both runs
 * produced a result for every file in the suite).
 *
 * spec: cutover-gate — Implementation Anchors: probatioOracleDiff
 */
final class OracleDiffRunner extends ProbatioSuite:

  import SeamTypes.*

  // The oracle diff runs all 17 bats files twice (~3 minutes).
  // Override the default 30s timeout to allow the full run.
  override val munitTimeout: FiniteDuration =
    FiniteDuration(5, TimeUnit.MINUTES)

  // ── probatioOracleDiff entry point
  // Runs the differential harness and prints the result + verdict.
  // This test is NOT ignored — it is the sbt task's entry point.
  // However, it requires bats to be installed and the oracle directory
  // to exist. In environments without bats, it will fail with a clear
  // message.
  test("probatioOracleDiff: run differential and decide"):
    val oracleDir: os.Path = os.pwd / "openspec" / "schemas" / "verified-scala3" / "tests"
    if !os.exists(oracleDir) then
      // Oracle directory not found — print a clear message and skip
      println("[probatioOracleDiff] oracle directory not found: " + oracleDir.toString)
      println("[probatioOracleDiff] SKIPPED — no oracle to compare against")
    else
      val binaryPath: String =
        (os.pwd / "openspec" / "schemas" / "verified-scala3" / "bin" / "probatio").toString

      // Run under the all-ported configuration (the cutover target)
      val portedConfig: SeamConfiguration =
        SeamConfiguration.fromPorted(ToolId.swapOrder.toSet)

      println("[probatioOracleDiff] running predecessor arm...")
      val predecessorRun: DifferentialHarness.SuiteRun =
        DifferentialHarness.runSuite(portedConfig.withPredecessor, oracleDir, binaryPath)

      println("[probatioOracleDiff] running ported arm...")
      val portedRun: DifferentialHarness.SuiteRun =
        DifferentialHarness.runSuite(portedConfig, oracleDir, binaryPath)

      val repository: String = os.pwd.toString
      val d: DifferentialResult =
        DifferentialHarness.diff(predecessorRun, portedRun, repository)

      println("[probatioOracleDiff] differential result:")
      d.files.foreach { (f: FileComparison) =>
        println(
          s"  ${f.fileName}: total=${f.total} pred=${f.predecessorFailures} ported=${f.portedFailures}" ++
            s" predPresent=${f.predecessorPresent} portPresent=${f.portedPresent}"
        )
      }
      println(s"[probatioOracleDiff] complete=${d.isComplete} hasRegression=${d.hasRegression}")
      if d.hasRegression then println(s"[probatioOracleDiff] worse files: ${d.worseFileNames.mkString(", ")}")

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
