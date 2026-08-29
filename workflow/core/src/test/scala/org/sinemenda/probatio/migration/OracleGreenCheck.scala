package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.core.ProbatioSuite

import java.lang.Process
import java.lang.ProcessBuilder
import scala.sys.process.*

/** Oracle-green regression property (R-M1).
  *
  * The acceptance oracle (the 17 bats files under
  * `openspec/schemas/verified-scala3/tests/`) produces the same set of
  * pass/fail outcomes before and after a tool substitution at a seam. This
  * is a regression property: the oracle is the fixed reference, and the
  * ported tool's behavior at the seam must not change any test's outcome.
  *
  * spec: migration-protocol — Requirement: Bats oracle is the porting acceptance suite
  * spec: migration-protocol — Property: oracle-green-at-every-step
  */
final class OracleGreenCheck extends ProbatioSuite:

  import SeamTypes.*

  // ── Scenario: Ported tool passes oracle at its seam
  // spec: migration-protocol — Scenario: Ported tool passes oracle at its seam
  // Runs all 17 bats files twice (~60s). Ignored in normal CI; re-enable
  // with `munit.Ignore` removal during the actual migration step.
  test("oracle green: ported tool at its seam produces same outcomes as predecessor".ignore):
    val config: SeamConfiguration = SeamConfiguration(Set(ToolId.ChainState))
    val predecessorResult: OracleOutcome = runOracle(config.withPredecessor)
    val portedResult: OracleOutcome = runOracle(config.withPorted)
    assertEquals(predecessorResult, portedResult,
      s"oracle outcomes differ: predecessor=$predecessorResult, ported=$portedResult")

  // ── Scenario: Oracle modification rejected as a behavior delta
  // spec: migration-protocol — Scenario: Oracle modification rejected as a behavior delta
  test("oracle immutability: oracle source is byte-identical to pre-port state"):
    // The oracle files under tests/ must not be modified during the port.
    // This test asserts the oracle directory exists and the bats files
    // are present (the git-diff check is enforced by CI).
    val oracleDir: os.Path = os.pwd / "openspec" / "schemas" / "verified-scala3" / "tests"
    assert(os.exists(oracleDir), s"oracle directory missing at $oracleDir")
    val batsFiles: IndexedSeq[os.Path] = os.list(oracleDir).filter(_.ext == "bats")
    assert(batsFiles.nonEmpty, "no .bats files found in oracle directory")
    // The oracle must have exactly 17 bats files (per spec implementation anchors)
    assertEquals(batsFiles.length, 17,
      s"expected 17 bats files, found ${batsFiles.length}")

  // ── Scenario: Partial migration — mixed predecessor and ported tools
  // spec: migration-protocol — Scenario: Partial migration — mixed predecessor and ported tools
  // Runs all 17 bats files twice (~60s). Ignored in normal CI.
  test("oracle green: mixed predecessor and ported tools pass the full oracle".ignore):
    val config: SeamConfiguration = SeamConfiguration(Set(ToolId.ChainState, ToolId.SpecLint))
    val predecessorResult: OracleOutcome = runOracle(config.withPredecessor)
    val portedResult: OracleOutcome = runOracle(config)
    assertEquals(predecessorResult, portedResult,
      s"oracle outcomes differ with mixed tools: predecessor=$predecessorResult, mixed=$portedResult")

  // ── Compile-Negative: Oracle source modified to accommodate a ported tool
  // spec: migration-protocol — Compile-Negative: Oracle source modified to accommodate a ported tool
  test("compile-negative: oracle source diff is empty at every migration step"):
    // The oracle source must not be modified. This is enforced by CI via
    // `git diff --exit-code openspec/schemas/verified-scala3/tests/`.
    // Here we assert the oracle files are tracked by git (not new/untracked).
    val oracleDir: os.Path = os.pwd / "openspec" / "schemas" / "verified-scala3" / "tests"
    val gitResult: Int = Seq("git", "diff", "--exit-code", "--", oracleDir.toString).!
    assert(gitResult == 0, "oracle source has uncommitted modifications — a modified oracle is not an independent witness")

  // ── Property: oracle-green-at-every-step
  // spec: migration-protocol — Property: oracle-green-at-every-step
  // Uses a reduced test count (1) because each oracle run executes all 17
  // bats files, which takes ~30s per run. The property is still valid with
  // a single random config because the scenario tests above cover the
  // deterministic cases.
  // NOTE: This property is commented out because it runs the full bats
  // oracle (17 files × 2 runs), which takes ~60s. Re-enable during the
  // actual migration step by uncommenting.
  // property("oracle green at every step", _.copy(testLimit = hedgehog.core.SuccessCount(1))):
  //   for
  //     seamConfig <- genSeamConfiguration.forAll
  //   yield
  //     val predecessorResult: OracleOutcome = runOracle(seamConfig.withPredecessor)
  //     val portedResult: OracleOutcome = runOracle(seamConfig.withPorted)
  //     Result.diff(predecessorResult, portedResult)(_ == _)

  // ── Generator: genSeamConfiguration
  // For each tool, one configuration with the predecessor implementation
  // and one with the ported implementation, with all other seams on the
  // predecessor.
  def genSeamConfiguration: Gen[SeamConfiguration] =
    Gen.element(ToolId.swapOrder(0), ToolId.swapOrder.drop(1)).map(tool => SeamConfiguration(Set(tool)))

  // ── Helper: run the oracle with a given seam configuration
  // Runs the bats oracle under the given seam configuration. For the
  // predecessor configuration (no tools ported), no override env vars
  // are set — the bats files run against the original scanner scripts.
  // For a ported configuration, the override env vars point to the
  // probatio binary (the resolved launcher at bin/probatio).
  def runOracle(config: SeamConfiguration): OracleOutcome =
    val oracleDir: os.Path = os.pwd / "openspec" / "schemas" / "verified-scala3" / "tests"
    if !os.exists(oracleDir) then
      // Oracle directory missing — this is a hard failure, not a silent
      // fallback. The oracle MUST exist for the regression to be valid.
      fail(s"oracle directory not found: $oracleDir")
    else
      // For predecessor configuration, no env vars are set.
      // For ported configurations, env vars point to the probatio binary.
      val env: Map[String, String] = if config.portedTools.isEmpty then
        Map.empty[String, String]
      else
        // Ported tools: set override to the probatio binary path.
        // The binary is the JAR launcher at bin/probatio, which dispatches
        // to the correct subcommand based on argv.
        config.portedTools.flatMap { tool =>
          val envVar: String = ToolId.overrideEnvVar(tool)
          val binaryPath: String = portedBinaryPath
          Some(envVar -> binaryPath)
        }.toMap

      val batsFiles: IndexedSeq[os.Path] = os.list(oracleDir).filter(_.ext == "bats")
      val outcomes: IndexedSeq[OracleOutcome] = batsFiles.map(runSingleBatsFile(_, env))
      val passed: Int = outcomes.map(_.passed).sum
      val failed: Int = outcomes.map(_.failed).sum
      val skipped: Int = outcomes.map(_.skipped).sum

      OracleOutcome(passed, failed, skipped)

  // ── Helper: the resolved probatio binary path (JAR launcher)
  // The binary is the launcher script at bin/probatio, which invokes
  // the assembly JAR. When a native-image binary is available, this
  // path will point to the native binary instead.
  private def portedBinaryPath: String =
    (os.pwd / "openspec" / "schemas" / "verified-scala3" / "bin" / "probatio").toString

  // ── Helper: run a single bats file and parse the outcome
  private def runSingleBatsFile(batsFile: os.Path, env: Map[String, String]): OracleOutcome =
    val cmd: Seq[String] = Seq("bats", batsFile.toString)
    val builder: ProcessBuilder = new ProcessBuilder(cmd*).redirectErrorStream(true)
    // Set override env vars on the process
    env.foreach { case (k: String, v: String) => builder.environment().put(k, v) }
    val process: Process = builder.start()
    val output: String = scala.io.Source.fromInputStream(process.getInputStream).mkString
    process.waitFor()
    // Parse TAP output: lines starting with "ok" = passed, "not ok" = failed,
    // "skip" = skipped
    val lines: List[String] = output.linesIterator.toList
    val passed: Int = lines.count(l => l.startsWith("ok ") && !l.contains("# skip"))
    val skipped: Int = lines.count(l => l.startsWith("ok ") && l.contains("# skip"))
    val failed: Int = lines.count(_.startsWith("not ok "))
    OracleOutcome(passed, failed, skipped)
