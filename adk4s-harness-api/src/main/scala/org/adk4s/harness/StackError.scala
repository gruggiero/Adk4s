package org.adk4s.harness

/**
 * Construction-time validation failure for `MiddlewareStack.validated`.
 *
 * Sealed enum with exactly two variants. Every match over `StackError`
 * SHALL be exhaustive — no catch-all arm is permitted.
 *
 * spec: middleware-stack — Requirement: StackError is a sealed enum with two variants
 */
enum StackError:
  /** Duplicate `StateCell.CellId` detected across middlewares. */
  case DuplicateCellId(id: StateCell.CellId, owners: List[MiddlewareName])

  /** Duplicate tool name detected across middlewares. */
  case DuplicateToolName(name: String, owners: List[MiddlewareName])
