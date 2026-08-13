package org.adk4s.harness

import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.upickle.given
import org.adk4s.core.types.NonEmpty
import org.adk4s.core.error.ConfigError

/**
 * Middleware identity for cell-id namespacing and error attributions.
 *
 * An opaque type backed by `String :| NonEmpty`. Constructed via
 * `MiddlewareName("literal")` (compile-time refinement for inline literals)
 * or `MiddlewareName.refineEither(s)` (runtime refinement). The underlying
 * value is accessed via the `.value` extension inherited from `Refined`.
 *
 * spec: harness-state — Requirement: MiddlewareName rejects empty strings
 */
type MiddlewareName = MiddlewareName.T

object MiddlewareName extends RefinedType[String, NonEmpty]:

  /** Refinement returning a structured ConfigError on failure.
    * This is the spec-required API: `MiddlewareName.refineEither(s)` returns
    * `Either[ConfigError, MiddlewareName]` with the field name, invalid
    * value, and constraint name. */
  def refineEither(s: String): Either[ConfigError, MiddlewareName] =
    either(s) match
      case Right(mn) => Right(mn)
      case Left(_)   => Left(ConfigError("MiddlewareName", s, "NonEmpty"))
