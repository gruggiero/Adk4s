package org.adk4s.harness

/**
 * Middleware identity for cell-id namespacing and error attributions.
 *
 * An opaque type backed by `String`. Constructed via `MiddlewareName.apply`;
 * the underlying value is accessed via the `.value` extension.
 *
 * spec: harness-state — Requirement: StateCell is a typed declaration unit with mandatory codec
 */
opaque type MiddlewareName = String

object MiddlewareName:
  def apply(s: String): MiddlewareName            = s
  extension (n: MiddlewareName) def value: String = n
