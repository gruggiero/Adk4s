package org.sinemenda.probatio.migration

/**
 * The migration stage being gated (R-M3).
 *
 * The oracle-green gate blocks stage transitions: from Stage 2 (wiring)
 * to Stage 3 (cutover), and between swaps within Stage 3. The stage
 * parameter records which transition is being evaluated.
 *
 * spec: migration-protocol — Requirement: The oracle-green gate is a mandatory checkpoint between stages
 */
enum Stage:
  case Wiring  // Stage 2 — CLI subcommand wiring
  case Cutover // Stage 3 — hook shim cutover

/**
 * The oracle-green gate — blocks a stage transition until the bats oracle
 * is green with the ported tools substituted at their seams (R-M3).
 *
 * The gate now delegates to [[CutoverGate]], which decides by comparing
 * the ported implementation's per-file failure counts against the
 * predecessor's under the same repository and the same suite. The gate
 * proceeds only when no file fails more tests under the ported
 * implementation than under the predecessor.
 *
 * The single-run predicate (failed == 0) has been replaced by the
 * comparison-based decision. See [[CutoverGate]] and
 * [[DifferentialHarness]].
 *
 * spec: migration-protocol — Requirement: The oracle-green gate is a mandatory checkpoint between stages
 * spec: migration-protocol — Property: oracle-green-at-every-step
 * spec: cutover-gate — Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold
 */
object OracleGreenGate:

  import SeamTypes.*

  /**
   * Returns true iff the cutover gate decides to proceed under the
   * given seam configuration. The stage parameter records which
   * transition is being gated (Wiring to Cutover, or a Cutover swap).
   *
   * Delegates to [[CutoverGate.decide]] via the differential harness:
   * runs the oracle under both the predecessor and ported seam
   * configurations, computes the differential result, and asks the
   * cutover gate to decide.
   */
  def apply(stage: Stage, seamConfig: SeamConfiguration): Boolean =
    val check: OracleGreenCheck          = new OracleGreenCheck()
    val differential: DifferentialResult = check.runDifferential(seamConfig)
    val verdict: CutoverVerdict          = CutoverGate.decide(differential)
    // The stage parameter records which transition is being gated
    // (Wiring→Cutover or a Cutover swap). The gate logic is the same
    // for both stages — it delegates to the cutover gate. The match
    // ensures exhaustiveness: if a new Stage variant is added, this
    // fails to compile rather than silently falling through.
    stage match
      case Stage.Wiring | Stage.Cutover =>
        verdict match
          case CutoverVerdict.Proceed   => true
          case CutoverVerdict.Revert(_) => false

  /**
   * Per-swap gating: returns true iff the cutover gate decides to
   * proceed when the given tool is substituted at its seam, on top of
   * the already-ported tools in the seam configuration.
   *
   * This is the per-swap checkpoint within Stage 3 (cutover). A swap
   * proceeds only when this returns true. If the oracle regresses, the
   * swap is aborted and the tool remains on the predecessor.
   *
   * The tool is added to the ported set before running the oracle, so
   * the oracle sees the tool substituted at its `*_OVERRIDE` seam.
   *
   * spec: hook-cutover — Requirement: Shims are swapped in dependency order — gate last
   * spec: hook-cutover — Scenario: A regressing swap is aborted
   */
  def apply(tool: SeamTypes.ToolId, seamConfig: SeamConfiguration): Boolean =
    val configWithTool: SeamConfiguration =
      SeamConfiguration.fromPorted(seamConfig.portedTools + tool)
    val check: OracleGreenCheck          = new OracleGreenCheck()
    val differential: DifferentialResult = check.runDifferential(configWithTool)
    val verdict: CutoverVerdict          = CutoverGate.decide(differential)
    verdict match
      case CutoverVerdict.Proceed   => true
      case CutoverVerdict.Revert(_) => false
