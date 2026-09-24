package org.sinemenda.probatio.migration

/**
 * The cutover gate — decides whether to proceed with a cutover by
 * comparing the ported implementation's per-file failure counts
 * against the predecessor's under the same repository and the same
 * suite.
 *
 * The gate proceeds if and only if:
 *   1. The comparison is complete (both runs produced a result for
 *      every file in the suite, and the file sets match), AND
 *   2. No file fails more tests under the ported implementation than
 *      under the predecessor.
 *
 * An incomplete comparison never proceeds. A per-file regression
 * blocks even when the overall total improves.
 *
 * This is a pure decision function: it takes a `DifferentialResult`
 * and returns a `CutoverVerdict`. The caller is responsible for
 * recording the decision and executing the swap or revert.
 *
 * spec: cutover-gate — Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold
 * spec: cutover-gate — Property: proceed-iff-no-file-worse
 * spec: cutover-gate — Property: total-improvement-does-not-excuse-a-regression
 * spec: cutover-gate — Property: incomplete-comparison-never-proceeds
 */
object CutoverGate:

  /**
   * Decide whether to proceed with the cutover based on the differential
   * result.
   *
   * Returns `CutoverVerdict.Proceed` if and only if the comparison is
   * complete and no file is worse under the ported implementation.
   * Returns `CutoverVerdict.Revert(evidence)` otherwise, carrying the
   * `DifferentialResult` that produced the refusal.
   *
   * spec: cutover-gate — Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold
   */
  def decide(d: DifferentialResult): CutoverVerdict =
    if !d.isComplete then CutoverVerdict.Revert(d)
    else if d.hasRegression then CutoverVerdict.Revert(d)
    else CutoverVerdict.Proceed

  /**
   * Record a gate decision with its comparison before the swap or revert
   * proceeds.
   *
   * Returns a `GateRecord` carrying the verdict and the differential
   * result. The recording precedes the action — the caller must not
   * execute the swap or revert until the record exists.
   *
   * spec: cutover-gate — Requirement: The gate's decision and its evidence are recorded before the swap proceeds
   */
  def record(d: DifferentialResult): GateRecord =
    GateRecord(decide(d), d)

  /**
   * Authorise a single seam's swap (spec 8): scope the comparison to
   * the oracle files that exercise the seam, then decide and record.
   *
   * `exercising` maps an oracle file name to the set of tool paths it
   * exercises (as produced by
   * `DifferentialHarness.exercisedToolPaths`); a file exercises the
   * seam iff its tool-path set contains `ToolId.seamPath(seam)`. The
   * returned record's evidence is the seam-scoped comparison —
   * `record.authorisesSwap` is true iff every exercising file produced
   * a result in both arms and none is worse. A seam with no exercising
   * file produces a record that cannot authorise (`hasEvidence` is
   * false): a swap MUST NOT proceed on an unmeasured seam.
   *
   * spec: ledger-checkpoint-cutover — Requirement: Each seam is swapped only after its oracle files reach control parity
   * spec: ledger-checkpoint-cutover — Scenario: A swap on an unmeasured file is refused
   */
  def authoriseSwap(
    seam: SeamTypes.ToolId,
    comparison: DifferentialResult,
    exercising: Map[String, Set[String]]
  ): GateRecord =
    val seamPath: String = SeamTypes.ToolId.seamPath(seam)
    // The expected scope is derived from the exercising map's domain,
    // not from the comparison's rows: an exercising file that produced
    // no comparison row at all is an UNMEASURED file, and the refusal
    // must be able to name it — it cannot be silently scoped out.
    val expected: Set[String] =
      exercising.collect { case (file: String, paths: Set[String]) if paths.contains(seamPath) => file }.toSet
    val measured: List[FileComparison] =
      comparison.files.filter { (f: FileComparison) =>
        exercising.getOrElse(f.fileName, Set.empty[String]).contains(seamPath)
      }
    val absent: List[FileComparison] =
      expected.toList.sorted.collect {
        case name if !measured.exists((f: FileComparison) => f.fileName == name) =>
          FileComparison(
            fileName = name,
            total = 0,
            predecessorFailures = 0,
            portedFailures = 0,
            predecessorPresent = false,
            portedPresent = false
          )
      }
    val scopedFiles: List[FileComparison] = measured ++ absent
    // The scoped comparison IS the evidence: its file set is exactly the
    // seam's exercising files, so `isComplete`/`hasRegression` decide
    // the seam alone — a worse file outside the scope cannot gate it,
    // and an exercising file absent from either arm refuses it.
    val scoped: DifferentialResult =
      comparison.copy(files = scopedFiles, suiteFileSet = expected ++ scopedFiles.map(_.fileName).toSet)
    // A seam with no exercising file has nothing measured: the shipped
    // driver refuses even though an empty comparison is vacuously
    // complete — the record's verdict is a refusal and `hasEvidence` is
    // false, so it can never authorise.
    if scopedFiles.isEmpty then GateRecord(CutoverVerdict.Revert(scoped), scoped)
    else record(scoped)

/**
 * A recorded gate decision with its evidence.
 *
 * spec: cutover-gate — Requirement: The gate's decision and its evidence are recorded before the swap proceeds
 */
final case class GateRecord(verdict: CutoverVerdict, evidence: DifferentialResult):
  /**
   * True iff this record authorises a swap (the verdict is Proceed AND
   * the record carries per-file evidence — a decision without a
   * recorded comparison is not actionable).
   */
  def authorisesSwap: Boolean = hasEvidence && (verdict match
    case CutoverVerdict.Proceed   => true
    case CutoverVerdict.Revert(_) => false
  )

  /**
   * True iff this record carries its comparison evidence. A decision
   * without a recorded comparison is not actionable.
   */
  def hasEvidence: Boolean = evidence.files.nonEmpty
