package org.adk4s.harness.testkit

import hedgehog.core.{ PropertyConfig, SuccessCount }
import hedgehog.munit.HedgehogSuite

/**
 * Spec that runs the `AgentMiddlewareLaws` (L0–L10) against the testkit's
 * own `DeterministicChatModel` double and `SimpleHarnessLoop` runner.
 *
 * This is the self-test for the testkit — it verifies that the laws
 * themselves are correct (the testkit's own middleware and model double
 * satisfy the laws). Downstream middleware authors can import
 * `AgentMiddlewareLaws` and run the same properties against their own
 * stacks.
 *
 * spec: middleware-laws — Requirements L0–L10
 */
class AgentMiddlewareLawsSpec extends HedgehogSuite:

  private val laws: AgentMiddlewareLaws                = AgentMiddlewareLawsSpec.laws
  private val config: PropertyConfig => PropertyConfig = _.copy(testLimit = SuccessCount(2000))

  property("L0 — conservative-refactor-equivalence", config):
    laws.l0ConservativeRefactor

  property("L1 — monoid-identity", config):
    laws.l1MonoidIdentity

  property("L2 — monoid-associativity", config):
    laws.l2MonoidAssociativity

  property("L3 — hook-distribution", config):
    laws.l3HookDistribution

  property("L3 — single-element-identity", config):
    laws.l3SingleElementIdentity

  property("L4 — default-neutrality", config):
    laws.l4DefaultNeutrality

  property("L5 — cell-frame-rule", config):
    laws.l5CellFrameRule

  property("L6 — disjoint-commutativity", config):
    laws.l6DisjointCommutativity

  property("L7 — codec-round-trip", config):
    laws.l7CodecRoundTrip

  property("L8 — restore-leniency", config):
    laws.l8RestoreLeniency

  property("L9 — privacy", config):
    laws.l9Privacy

  property("L10 — merge-back-neutrality", config):
    laws.l10MergeBackNeutrality

object AgentMiddlewareLawsSpec:
  val laws: AgentMiddlewareLaws = new AgentMiddlewareLaws(seed = 42L, basePrompt = Some("base"))
