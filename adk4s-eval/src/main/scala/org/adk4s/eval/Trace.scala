package org.adk4s.eval

/**
 * A trace of predictor invocations — a vector of [[TraceEntry]] values.
 *
 * Data only in Phase 1 — trace capture is Phase 2/3. The
 * [[forPredictor]] method filters entries by path prefix, implementing
 * GEPA's `pred_trace` slicing.
 *
 * @param entries the predictor invocation records
 */
final case class Trace(entries: Vector[TraceEntry]):

  /** Filter entries whose path starts with the given prefix. */
  def forPredictor(path: String): Trace =
    Trace(entries.filter(entry => entry.path.startsWith(path)))

object Trace:

  /** An empty trace. */
  def empty: Trace = Trace(Vector.empty[TraceEntry])
