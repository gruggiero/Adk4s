package org.sinemenda.probatio.migration

/** The migration stage being gated (R-M3).
  *
  * The oracle-green gate blocks stage transitions: from Stage 2 (wiring)
  * to Stage 3 (cutover), and between swaps within Stage 3. The stage
  * parameter records which transition is being evaluated.
  *
  * spec: migration-protocol — Requirement: The oracle-green gate is a mandatory checkpoint between stages
  */
enum Stage:
  case Wiring   // Stage 2 — CLI subcommand wiring
  case Cutover  // Stage 3 — hook shim cutover

/** The oracle-green gate — blocks a stage transition until the bats oracle
  * is green with the ported tools substituted at their seams (R-M3).
  *
  * `apply(stage, seamConfig)` returns true only when every bats test passes
  * under the given seam configuration. This is the mandatory checkpoint
  * between Stage 2 (wiring) and Stage 3 (cutover), and between each swap
  * within Stage 3.
  *
  * The gate is a pure decision function: it returns whether the oracle is
  * green. The caller (the migration process) is responsible for checking
  * the result and not proceeding if it returns false. The spec says "A
  * stage transition without oracle clearance is an unverified change" —
  * this is a process requirement enforced by the caller, not by the gate
  * function itself.
  *
  * spec: migration-protocol — Requirement: The oracle-green gate is a mandatory checkpoint between stages
  * spec: migration-protocol — Property: oracle-green-at-every-step
  */
object OracleGreenGate:

  import SeamTypes.*

  /** Returns true iff the bats oracle is green (zero failures) under the
    * given seam configuration. The stage parameter records which transition
    * is being gated (Wiring to Cutover, or a Cutover swap).
    *
    * Delegates to `OracleGreenCheck.runOracle` to run the bats oracle under
    * the seam configuration, then checks that no test failed.
    */
  def apply(stage: Stage, seamConfig: SeamConfiguration): Boolean =
    val check: OracleGreenCheck = new OracleGreenCheck()
    val outcome: OracleOutcome = check.runOracle(seamConfig)
    // The stage parameter records which transition is being gated
    // (Wiring→Cutover or a Cutover swap). The gate logic is the same
    // for both stages — it delegates to the oracle. The match ensures
    // exhaustiveness: if a new Stage variant is added, this fails to
    // compile rather than silently falling through.
    stage match
      case Stage.Wiring | Stage.Cutover => outcome.failed == 0

  /** Per-swap gating: returns true iff the bats oracle is green when the
    * given tool is substituted at its seam, on top of the already-ported
    * tools in the seam configuration.
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
      SeamConfiguration(seamConfig.portedTools + tool)
    val check: OracleGreenCheck = new OracleGreenCheck()
    val outcome: OracleOutcome = check.runOracle(configWithTool)
    outcome.failed == 0
