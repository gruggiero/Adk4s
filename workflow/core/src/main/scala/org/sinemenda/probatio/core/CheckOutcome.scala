package org.sinemenda.probatio.core

import upickle.default.*

/**
 * The outcome of one lint check evaluation.
 *
 * `Pass` exists only as the positive evaluation of a check that ran — a
 * check that did not run (applicability fact unreadable, artifact check
 * not requested) produces no `Pass` value; the run reports
 * `Outcome.Undetermined` instead. `Fail` and `Warn` are the emitted
 * finding stream, in the predecessor's emission order.
 *
 * `line` is `None` for document-level findings (F4, F10, W2, W4, W6) —
 * the predecessor emits those without a `line N:` prefix.
 *
 * spec: spec-lint-engine — Concepts Introduced (new): CheckOutcome
 */
enum CheckOutcome derives ReadWriter:
  case Pass(check: CheckId)
  case Fail(check: CheckId, line: Option[Int], message: String)
  case Warn(warning: LintWarning)
