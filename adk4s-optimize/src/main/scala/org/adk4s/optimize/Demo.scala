package org.adk4s.optimize

/**
 * One input/output pair — the few-shot example unit carried in predictor state.
 *
 * Both `input` and `output` are opaque JSON values (`ujson.Value`), carried
 * without interpretation by the optimizable surface. They are
 * serializable-ready by shape (plain immutable data, no closures, no effect
 * types) for Phase 2 persistence.
 */
final case class Demo(input: ujson.Value, output: ujson.Value)
