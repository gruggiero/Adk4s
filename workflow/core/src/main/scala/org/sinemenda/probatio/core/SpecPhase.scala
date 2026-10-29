package org.sinemenda.probatio.core

/**
 * The phase of a spec in implementation order (spec 9).
 *
 * Derived from ledger rows by the CLI layer:
 * - RED+GREEN at an ancestor baseline → `Verified`
 * - GREEN only → `Implementation`
 * - RED only or none → `Oracle`
 *
 * The enum is sealed with exactly three cases — no fourth case is
 * constructible.
 *
 * spec: gate-checkpoint-lock — Concepts Introduced: SpecPhase
 * spec: gate-checkpoint-lock — Compile-Negative: SpecPhase sealed enum
 */
enum SpecPhase:
  case Oracle
  case Implementation
  case Verified

object SpecPhase:

  /**
   * Total parse of a phase state-file token. The predecessor reads
   * `phase-<change>-<spec>` with unrecognised content falling through
   * to `oracle` — a file the reader cannot classify is the starting
   * phase, never an error and never a fabricated later phase.
   *
   * spec: gate-event-completeness — Requirement: The blocking tiers consult repository state and fail open when it is unavailable
   */
  def fromStateFile(content: String): SpecPhase = content.trim match
    case "implementation" => SpecPhase.Implementation
    case "verified"       => SpecPhase.Verified
    case _ => SpecPhase.Oracle // danger-scan:allow predecessor-default — unrecognised phase content reads as oracle

  /** The state-file token for a phase. */
  def asToken(phase: SpecPhase): String = phase match
    case SpecPhase.Oracle         => "oracle"
    case SpecPhase.Implementation => "implementation"
    case SpecPhase.Verified       => "verified"

end SpecPhase
