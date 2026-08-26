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
  * '''Known limitation''': `OracleGreenCheck.runOracle` (the delegate) was
  * shipped by the archived `port-scanner-to-probatio` change with a
  * predecessor-path fallback for ported tools — when `portedTools` is
  * non-empty, the override env vars point to the predecessor script path,
  * not the probatio binary path (because no binary existed at ship time).
  * This means the gate currently tests the predecessor, not the ported
  * tool. This fallback will be resolved by the `cli-wiring` spec (which
  * creates the binary) and the `hook-cutover` spec (which swaps the
  * shims). Until then, the gate's `true` result means "the oracle is green
  * with the predecessor tools," not "with the ported tools."
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
