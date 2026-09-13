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
 * A recorded gate decision with its evidence.
 *
 * spec: cutover-gate — Requirement: The gate's decision and its evidence are recorded before the swap proceeds
 */
final case class GateRecord(verdict: CutoverVerdict, evidence: DifferentialResult):
  /** True iff this record authorises a swap (the verdict is Proceed AND
    * the record carries per-file evidence — a decision without a
    * recorded comparison is not actionable). */
  def authorisesSwap: Boolean = hasEvidence && (verdict match
    case CutoverVerdict.Proceed   => true
    case CutoverVerdict.Revert(_) => false
  )

  /**
   * True iff this record carries its comparison evidence. A decision
   * without a recorded comparison is not actionable.
   */
  def hasEvidence: Boolean = evidence.files.nonEmpty
