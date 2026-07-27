package org.adk4s.optimize

/**
 * Stable address of a predictor inside a program.
 *
 * `segments` is a `Vector[String]` of case-class field names, outermost first.
 * For collection-indexed predictors, the segment is the element index as a
 * string (e.g. `"0"`, `"1"`).
 *
 * `render` joins segments with `.` for traces, save files, and logs. The
 * empty path (`Vector.empty`) renders to the empty string and is used as a
 * root marker; it is never returned by `Optimizable.predictors`.
 */
final case class PredictorPath(segments: Vector[String]):
  /** Dot-joined rendering, outermost segment first. */
  def render: String = segments.mkString(".")
