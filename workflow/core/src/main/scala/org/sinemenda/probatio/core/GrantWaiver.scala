package org.sinemenda.probatio.core

/**
 * The grant waiver: a pure function over prior spec states (spec 9).
 *
 * Waives the human-grant lock if every prior spec is `Verified` AND has
 * a presentation marker. A current-session grant token satisfies the
 * lock directly. If neither a grant nor a waiver applies, the write is
 * blocked. The escape hatch bypasses both checks.
 *
 * This is a PURE function — it takes booleans as inputs, not file paths.
 * The CLI layer reads state files and passes the results. No file I/O
 * or `System.getenv` is permitted here (compile-negative enforced).
 *
 * spec: gate-checkpoint-lock — Requirement: The grant waiver requires a checkpoint presentation marker
 * spec: gate-checkpoint-lock — Property: grant-waiver-requires-presentation
 * spec: gate-checkpoint-lock — Compile-Negative: No file I/O or System.getenv in GrantWaiver
 */
object GrantWaiver:

  /** A prior spec's state: (name, phase, hasPresentation, hasGrant). */
  type SpecState = (String, SpecPhase, Boolean, Boolean)

  /**
   * Check whether all prior specs pass the grant waiver.
   *
   * - `escapeHatch = true` → `Right(())` (bypasses both checks)
   * - empty list → `Right(())` (fail open)
   * - first failing spec determines the `BlockReason`:
   *   - `GrantRequired(name)` when neither grant nor waiver applies
   *
   * A spec passes the grant waiver if and only if:
   *   (phase == Verified AND hasPresentation) OR hasGrant.
   *
   * spec: gate-checkpoint-lock — Scenario: verified with presentation waives grant
   * spec: gate-checkpoint-lock — Scenario: verified without presentation does not waive grant
   * spec: gate-checkpoint-lock — Scenario: verified with grant from current session is allowed
   */
  def apply(specs: List[SpecState], escapeHatch: Boolean): Either[BlockReason, Unit] =
    if escapeHatch then Right(())
    else checkList(specs)

  private def checkList(specs: List[SpecState]): Either[BlockReason, Unit] =
    specs match
      case Nil => Right(())
      case (name, phase, hasPres, hasGrant) :: rest =>
        val passes: Boolean = (phase == SpecPhase.Verified && hasPres) || hasGrant
        if passes then checkList(rest)
        else Left(BlockReason.GrantRequired(name))

end GrantWaiver
