package org.sinemenda.probatio.core

/**
 * The predecessor check: a pure function over prior spec states (spec 9).
 *
 * Blocks if any prior spec in implementation order is not both `Verified`
 * (RED+GREEN ledger rows exist at an ancestor baseline) AND checkpointed
 * (a presentation marker exists for that spec). A spec that is `Verified`
 * but has no presentation marker is reported as `PredecessorNotCheckpointed`,
 * distinct from a spec that is `Oracle` or `Implementation` phase
 * (`PredecessorNotVerified`). The escape hatch bypasses both checks.
 *
 * This is a PURE function — it takes booleans as inputs, not file paths.
 * The CLI layer reads state files and passes the results. No file I/O
 * or `System.getenv` is permitted here (compile-negative enforced).
 *
 * spec: gate-checkpoint-lock — Requirement: The predecessor check requires a checkpoint presentation marker in addition to verified phase
 * spec: gate-checkpoint-lock — Property: predecessor-check-requires-presentation
 * spec: gate-checkpoint-lock — Compile-Negative: No file I/O or System.getenv in PredecessorCheck
 */
object PredecessorCheck:

  /** A prior spec's state: (name, phase, hasPresentation). */
  type SpecState = (String, SpecPhase, Boolean)

  /**
   * Check whether all prior specs pass the predecessor check.
   *
   * - `escapeHatch = true` → `Right(())` (bypasses both checks)
   * - empty list → `Right(())` (fail open, matching the no-state-directory discipline)
   * - first failing spec determines the `BlockReason`:
   *   - `Verified` without presentation → `PredecessorNotCheckpointed`
   *   - non-`Verified` → `PredecessorNotVerified`
   *
   * spec: gate-checkpoint-lock — Scenario: verified predecessor with no presentation is blocked
   * spec: gate-checkpoint-lock — Scenario: verified predecessor with a presentation is allowed
   * spec: gate-checkpoint-lock — Scenario: non-verified predecessor is blocked regardless of presentation
   * spec: gate-checkpoint-lock — Scenario: escape hatch bypasses both checks
   * spec: gate-checkpoint-lock — Scenario: no state directory means fail open
   */
  def apply(specs: List[SpecState], escapeHatch: Boolean): Either[BlockReason, Unit] =
    if escapeHatch then Right(())
    else checkList(specs)

  private def checkList(specs: List[SpecState]): Either[BlockReason, Unit] =
    specs match
      case Nil => Right(())
      case (name, phase, hasPres) :: rest =>
        if phase == SpecPhase.Verified && hasPres then checkList(rest)
        else if phase == SpecPhase.Verified && !hasPres then Left(BlockReason.PredecessorNotCheckpointed(name))
        else Left(BlockReason.PredecessorNotVerified(name, phase))

end PredecessorCheck
