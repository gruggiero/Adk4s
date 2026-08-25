package org.sinemenda.probatio.core

/**
 * The reason a gate decision is `Block` (spec 9).
 *
 * Sealed trait with exactly four variants. Each has a `render` method
 * producing the human-readable payload string. The typed sum prevents
 * the renderer from conflating `PredecessorNotCheckpointed` with
 * `PredecessorNotVerified` — the exact defect this spec exists to prevent.
 *
 * spec: gate-checkpoint-lock — Concepts Introduced: BlockReason
 * spec: gate-checkpoint-lock — Requirement: The block reason distinguishes not-checkpointed from not-verified
 * spec: gate-checkpoint-lock — Compile-Negative: BlockReason sealed trait
 */
sealed trait BlockReason:
  def render: String
end BlockReason

object BlockReason:

  /** The escape hatch variable name, included in all rendered block reasons. */
  private val escapeHatchVar: String = "PROBATIO_HOOKS"

  /**
   * A predecessor spec that is not `Verified` (still in `Oracle` or
   * `Implementation` phase). The rendered payload contains the phase
   * name and does NOT contain "not checkpointed".
   *
   * spec: gate-checkpoint-lock — Scenario: not-verified reason does not name checkpoint
   */
  final case class PredecessorNotVerified(spec: String, phase: SpecPhase) extends BlockReason:
    def render: String =
      val phaseName: String = phase match
        case SpecPhase.Oracle         => "Oracle"
        case SpecPhase.Implementation => "Implementation"
        case SpecPhase.Verified       => "Verified"
      s"predecessor spec $spec is in $phaseName phase (not Verified). " +
        s"Run the tests (record RED and GREEN ledger rows) to advance it. " +
        s"Set $escapeHatchVar to bypass this check."
  end PredecessorNotVerified

  /**
   * A predecessor spec that is `Verified` but has no checkpoint
   * presentation marker. The rendered payload contains "not checkpointed"
   * and the instruction to run `checkpoint`.
   *
   * spec: gate-checkpoint-lock — Scenario: not-checkpointed reason names checkpoint
   */
  final case class PredecessorNotCheckpointed(spec: String) extends BlockReason:
    def render: String =
      s"predecessor spec $spec is Verified but not checkpointed. " +
        s"Run checkpoint to trigger the chain-state discharge check. " +
        s"Set $escapeHatchVar to bypass this check."
  end PredecessorNotCheckpointed

  /**
   * An oracle ordering violation — a production edit attempted while
   * the spec's phase is still `Oracle` (no RED run recorded).
   */
  case object OracleOrderingViolation extends BlockReason:
    def render: String =
      s"oracle ordering violation — production edit blocked while spec is in Oracle phase. " +
        s"Run the test oracle first (ledger.sh run -- … -- sbt <module>/test) to record a RED run. " +
        s"Set $escapeHatchVar to bypass this check."
  end OracleOrderingViolation

  /**
   * A human grant is required before proceeding to the next spec.
   * The grant is waived only when the prior spec is `Verified` AND
   * has a presentation marker.
   *
   * spec: gate-checkpoint-lock — Scenario: verified without presentation does not waive grant
   */
  final case class GrantRequired(spec: String) extends BlockReason:
    def render: String =
      s"grant required for spec $spec — no human grant token in the current session. " +
        s"A user prompt must arrive after the checkpoint presentation. " +
        s"Set $escapeHatchVar to bypass this check."
  end GrantRequired

end BlockReason
