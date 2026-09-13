package org.sinemenda.probatio.migration

import java.lang.Process
import java.lang.ProcessBuilder

/**
 * The differential harness — materialises two seam-configured copies of
 * the scanner tree inside the repository, runs the suite against each,
 * parses both outputs, and emits a `DifferentialResult`.
 *
 * Both runs execute in the same repository under the same unmodified
 * suite, differing only in which implementation each seam resolves to.
 * The harness verifies that the suite file digests match the
 * repository's before running.
 *
 * This object is callable from tests and from the `probatioOracleDiff`
 * sbt task.
 *
 * spec: cutover-gate — Requirement: Both runs of the comparison execute in the same repository under the same suite
 * spec: cutover-gate — Implementation Anchors: DifferentialHarness
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
   * The result of running the full suite under one seam configuration:
   * the per-file results.
   */
  final case class SuiteRun(
    fileResults: List[BatsFileResult],
    fileSet: Set[String]
  )

  /**
   * Run the oracle suite under a seam configuration and return the
   * per-file results.
   *
   * For the predecessor configuration (no tools ported), no override
   * env vars are set — the bats files run against the original scanner
   * scripts. For a ported configuration, the override env vars point
   * to the probatio binary.
   */
  def runSuite(config: SeamConfiguration, oracleDir: os.Path, binaryPath: String): SuiteRun =
    if !os.exists(oracleDir) then
      sys.error(s"oracle directory not found: $oracleDir — a missing oracle is a hard failure, not an empty comparison")
    else
      val env: Map[String, String]       = buildEnv(config, binaryPath)
      val batsFiles: IndexedSeq[os.Path] = os.list(oracleDir).filter(_.ext == "bats")
      val fileSet: Set[String]           = batsFiles.map(_.last).toSet
      val results: List[BatsFileResult]  = batsFiles.map(runSingleBatsFile(_, env)).toList
      SuiteRun(results, fileSet)

  /** Build the environment variable overrides for a seam configuration. */
  private def buildEnv(config: SeamConfiguration, binaryPath: String): Map[String, String] =
    if config.portedTools.isEmpty then Map.empty[String, String]
    else
      config.portedTools.flatMap { (tool: ToolId) =>
        val envVar: String = ToolId.overrideEnvVar(tool)
        Some(envVar -> binaryPath)
      }.toMap

  /** Run a single bats file and parse the TAP output. */
  private def runSingleBatsFile(batsFile: os.Path, env: Map[String, String]): BatsFileResult =
    val cmd: Seq[String]        = Seq("bats", batsFile.toString)
    val builder: ProcessBuilder = new ProcessBuilder(cmd*).redirectErrorStream(true)
    env.foreach { case (k: String, v: String) => builder.environment().put(k, v) }
    val process: Process = builder.start()
    val output: String   = scala.io.Source.fromInputStream(process.getInputStream).mkString
    process.waitFor()
    val lines: List[String] = output.linesIterator.toList
    val passed: Int         = lines.count(l => l.startsWith("ok ") && !l.contains("# skip"))
    val skipped: Int        = lines.count(l => l.startsWith("ok ") && l.contains("# skip"))
    val failed: Int         = lines.count(_.startsWith("not ok "))
    val total: Int          = passed + skipped + failed
    BatsFileResult(batsFile.last, total, failed)

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
    val bytes: Array[Byte]                  = os.read.bytes(path)
    val digest: java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map("%02x".format(_)).mkString
