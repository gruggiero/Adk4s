package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

/**
 * The three-way exit protocol as data (§4.1).
 *
 * Maps to process exit codes:
 *   - `Clean`        → 0 (ran and found nothing wrong)
 *   - `Finding`      → 1 (ran and found something)
 *   - `Undetermined` → 2 (could not determine)
 *
 * The enum is sealed with exactly three cases. No other exit code is
 * producible — the mapping from `Outcome[A]` is a total function via
 * exhaustive match, and `-Wconf:cat=pattern-match-exhaustivity:e` makes
 * non-exhaustive match a compile error. No `case _` default can redirect
 * `Undetermined` to exit 1 (§4.3).
 *
 * spec: cli-protocol — Requirement: Three-way exit protocol for every subcommand
 * spec: cli-protocol — Requirement: Undetermined is never collapsed into a finding
 */
enum ExitCode:
  case Clean, Finding, Undetermined

object ExitCode:

  /** The numeric exit code. */
  def toInt(code: ExitCode): Int = code match
    case Clean        => 0
    case Finding      => 1
    case Undetermined => 2

  /**
   * Maps an `Outcome[A]` to an `ExitCode` via exhaustive match.
   *
   * This is the total function from the outcome enum to the exit-code enum.
   * Every outcome maps to exactly one exit code, and the three outcome
   * cases map to distinct exit codes. No `case _` default — the match is
   * exhaustive by construction on the sealed `Outcome` enum.
   *
   * spec: cli-protocol — Property: exit-code-mapping-is-total-and-disjoint
   * spec: cli-protocol — Property: undetermined-never-collapses
   */
  def from[A](outcome: Outcome[A]): ExitCode = outcome match
    case Outcome.Ran(_)          => Clean
    case Outcome.Finding(_)      => Finding
    case Outcome.Undetermined(_) => Undetermined
