package org.adk4s.eval

/**
 * Eval harness (DSPy port — Phase 1).
 *
 * This package provides the evaluation harness for LLM-powered programs:
 * [[Example]] / [[Score]] / [[Metric]] / [[Trace]] data types, the
 * [[Evaluate]] harness with parallel evaluation + failure-score substitution
 * + maxErrors cancellation, [[EvaluationResult]] with JSON/CSV export,
 * [[Dataset]] JSONL reader, built-in string metrics ([[Metrics]]), and
 * LLM judges ([[Judges]]).
 *
 * The `Metric` signature is FROZEN by this change's design.md: the
 * `Option[Trace]` argument is pinned now so optimizers (Phase 2/3) can
 * depend on it without the signature ever changing.
 */
