package org.adk4s.eval

import smithy4s.Document

/**
 * One predictor invocation record: the predictor's path, its input, and its
 * output (both as `smithy4s.Document`, aliased as `JsonValue` in
 * `adk4s-core`).
 *
 * Data only in Phase 1 — trace capture is Phase 2/3. The `path` is a
 * dotted prefix used by [[Trace.forPredictor]] to slice traces by predictor
 * (GEPA's `pred_trace`).
 *
 * Note: uses `smithy4s.Document` directly rather than the `JsonValue` alias
 * because `adk4s-eval` does not depend on `adk4s-core` (Ring 2 purity rule).
 * `JsonValue` is `type JsonValue = smithy4s.Document`, so the types are
 * identical.
 *
 * @param path   the predictor path (e.g. "program.predictor_0")
 * @param input  the predictor's input as JSON
 * @param output the predictor's output as JSON
 */
final case class TraceEntry(path: String, input: Document, output: Document)
