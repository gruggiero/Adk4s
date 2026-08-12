package org.adk4s.harness

import cats.data.Kleisli

/**
 * The model step: a function from `ModelRequest[F]` to an effectful
 * `ModelResponse`. The wrap-model-call hook accepts and returns a model
 * step, composing as a Kleisli endomorphism.
 *
 * spec: agent-middleware — Requirement: ModelStep is a Kleisli from ModelRequest to ModelResponse
 */
type ModelStep[F[_]] = Kleisli[F, ModelRequest[F], ModelResponse]
