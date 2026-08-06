package org.adk4s.optimize

import smithy4s.Document

/**
 * One input/output pair — the few-shot example unit carried in predictor state.
 *
 * Both `input` and `output` are opaque JSON values (`smithy4s.Document`,
 * aliased as `JsonValue` in `adk4s-core`), carried without interpretation by
 * the optimizable surface. They are serializable-ready by shape (plain
 * immutable data, no closures, no effect types) for Phase 2 persistence.
 *
 * Note: uses `smithy4s.Document` directly rather than the `JsonValue` alias
 * because `adk4s-optimize` does not depend on `adk4s-core` (Ring 2 purity
 * rule). `JsonValue` is `type JsonValue = smithy4s.Document`, so the types
 * are identical.
 */
final case class Demo(input: Document, output: Document)
