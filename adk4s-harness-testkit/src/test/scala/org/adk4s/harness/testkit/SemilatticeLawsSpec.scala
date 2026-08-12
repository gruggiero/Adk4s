package org.adk4s.harness.testkit

import hedgehog.core.{ PropertyConfig, SuccessCount }
import hedgehog.munit.HedgehogSuite

/**
 * Spec that runs the `SemilatticeLaws` (L11) against the testkit's own
 * `SharedTypedCell` variants (Max, Min, Union).
 *
 * This is the self-test for the semilattice laws — it verifies that the
 * built-in merge functions (Int max, Int min, Set union) satisfy
 * commutativity, associativity, idempotence, and `mergeBack`
 * order-independence. Downstream middleware authors can import
 * `SemilatticeLaws` and run the same properties against their own shared
 * cells.
 *
 * spec: middleware-laws — Requirement: L11 Semilattice laws for parallel-shared cells
 */
class SemilatticeLawsSpec extends HedgehogSuite:

  private val laws: SemilatticeLaws                    = new SemilatticeLaws
  private val config: PropertyConfig => PropertyConfig = _.copy(testLimit = SuccessCount(2000))

  property("L11 — semilattice-commutativity", config):
    laws.l11Commutativity

  property("L11 — semilattice-associativity", config):
    laws.l11Associativity

  property("L11 — semilattice-idempotence", config):
    laws.l11Idempotence

  property("L11 — mergeBack-order-independence", config):
    laws.l11MergeBackOrderIndependence
