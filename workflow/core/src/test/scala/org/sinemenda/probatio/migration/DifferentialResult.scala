package org.sinemenda.probatio.migration

/**
 * The per-file comparison of one oracle run under the predecessor seams
 * against one under the ported seams.
 *
 * For each file in the suite, this records:
 *   - the file name
 *   - the total number of tests in that file
 *   - the predecessor failure count
 *   - the ported failure count
 *   - whether the predecessor run produced a result for this file
 *   - whether the ported run produced a result for this file
 *
 * A comparison is complete when both runs produced a result for every
 * file in the suite. An incomplete comparison (a run that did not
 * produce a result for every file, or whose file set differs between
 * the arms) is never a basis for proceeding.
 *
 * spec: cutover-gate — Concepts Introduced: DifferentialResult
 */
final case class FileComparison(
  fileName: String,
  total: Int,
  predecessorFailures: Int,
  portedFailures: Int,
  predecessorPresent: Boolean,
  portedPresent: Boolean
):
  /**
   * True iff this file is worse under the ported implementation than
   * under the predecessor. A file is worse only when both runs produced
   * a result and the ported failure count exceeds the predecessor
   * failure count.
   */
  def isWorse: Boolean =
    predecessorPresent && portedPresent && portedFailures > predecessorFailures

/**
 * The differential result of running the oracle under two seam
 * configurations against the same repository and the same suite.
 *
 * `files` contains one `FileComparison` per file in the suite. The
 * comparison is complete when both runs produced a result for every
 * file and the file sets match.
 *
 * spec: cutover-gate — Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold
 * spec: cutover-gate — Requirement: Both runs of the comparison execute in the same repository under the same suite
 */
final case class DifferentialResult(
  files: List[FileComparison],
  repository: String,
  suiteFileSet: Set[String]
):
  /**
   * True iff both runs produced a result for every file in the suite
   * and the file sets match. An incomplete comparison is never a basis
   * for proceeding.
   */
  def isComplete: Boolean =
    files.map(_.fileName).toSet == suiteFileSet &&
      files.forall(f => f.predecessorPresent && f.portedPresent)

  /** The files that are worse under the ported implementation. */
  def worseFiles: List[FileComparison] =
    files.filter(_.isWorse)

  /** True iff at least one file is worse under the ported implementation. */
  def hasRegression: Boolean =
    worseFiles.nonEmpty

  /** The names of files that are worse, for evidence reporting. */
  def worseFileNames: List[String] =
    worseFiles.map(_.fileName)
