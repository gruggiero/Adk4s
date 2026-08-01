package org.adk4s.eval

/**
 * Per-example evaluation outcome — either the program succeeded with a
 * value, or it failed with an error.
 *
 * Sealed enum with exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`):
 * all matches must handle both `Succeeded` and `Failed`.
 */
enum EvalOutcome[+O]:
  case Succeeded(value: O)
  case Failed(error: Throwable)

  /** True if this is `Succeeded`. */
  def isSucceeded: Boolean = this match
    case Succeeded(_) => true
    case Failed(_)    => false

  /** True if this is `Failed`. */
  def isFailed: Boolean = !isSucceeded
