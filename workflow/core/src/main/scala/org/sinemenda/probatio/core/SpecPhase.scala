package org.sinemenda.probatio.core

/**
 * The phase of a spec in implementation order (spec 9).
 *
 * Derived from ledger rows by the CLI layer:
 * - RED+GREEN at an ancestor baseline → `Verified`
 * - GREEN only → `Implementation`
 * - RED only or none → `Oracle`
 *
 * The enum is sealed with exactly three cases — no fourth case is
 * constructible.
 *
 * spec: gate-checkpoint-lock — Concepts Introduced: SpecPhase
 * spec: gate-checkpoint-lock — Compile-Negative: SpecPhase sealed enum
 */
enum SpecPhase:
  case Oracle
  case Implementation
  case Verified
