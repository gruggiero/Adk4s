package org.adk4s.eval

import ujson.Value

/**
 * One predictor invocation record: the predictor's path, its input, and its
 * output (both as `ujson.Value`).
 *
 * Data only in Phase 1 — trace capture is Phase 2/3. The `path` is a
 * dotted prefix used by [[Trace.forPredictor]] to slice traces by predictor
 * (GEPA's `pred_trace`).
 *
 * @param path   the predictor path (e.g. "program.predictor_0")
 * @param input  the predictor's input as JSON
 * @param output the predictor's output as JSON
 */
final case class TraceEntry(path: String, input: Value, output: Value)
