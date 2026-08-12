package org.adk4s.harness

/**
 * Three sub-agent visibility levels governing projection and merge-back.
 *
 * - `Private`: never crosses a sub-agent boundary; the child sees `initial`.
 * - `Inherited`: copied into the child's initial state; child writes are discarded.
 * - `Shared`: copied into the child; child's final value merges back via `merge`.
 *
 * spec: harness-state — Requirement: CellVisibility governs sub-agent state isolation
 */
enum CellVisibility:
  case Private
  case Inherited
  case Shared
