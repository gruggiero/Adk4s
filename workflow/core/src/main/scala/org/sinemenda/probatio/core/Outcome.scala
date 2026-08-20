package org.sinemenda.probatio.core

/** The three-way exit protocol as data (spec §4.1).
  *
  * Maps to exit 0/1/2 at the CLI boundary:
  *   - `Ran`         → exit 0 (ran clean, carries the success value)
  *   - `Finding`     → exit 1 (ran and found something, carries a description)
  *   - `Undetermined`→ exit 2 (could not determine, carries the reason)
  *
  * The enum is sealed with exactly three cases. No fourth case is
  * constructible — the silent fallback (undetermined collapsed into
  * finding or clean) is unrepresentable at the type level.
  *
  * spec: probatio-core — Requirement: The three-way exit protocol is a sealed enum
  */
enum Outcome[+A]:
  case Ran(value: A)
  case Finding(description: String)
  case Undetermined(reason: String)

object Outcome:

  /** Maps an outcome to its exit code (0/1/2).
    *
    * spec: probatio-core — Scenario: undetermined is not collapsed into finding (adversarial)
    * spec: probatio-core — Scenario: undetermined is not collapsed into clean (adversarial)
    */
  def toExitCode[A](outcome: Outcome[A]): Int = outcome match
    case Ran(_)          => 0
    case Finding(_)      => 1
    case Undetermined(_) => 2
