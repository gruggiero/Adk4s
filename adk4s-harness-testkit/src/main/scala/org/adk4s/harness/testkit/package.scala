package org.adk4s.harness

/**
 * Downstream-consumable law testkit for the agent middleware stack.
 *
 * Publishes `AgentMiddlewareLaws` (L0–L10 observational-equivalence
 * properties), `SemilatticeLaws` (L11 commutativity / associativity /
 * idempotence + `mergeBack` order-independence), `DeterministicChatModel`
 * (a deterministic `ChatModel` double that records request traces), and
 * Hedgehog `Generators` — all in MAIN scope, in the `AgentMemoryLaws`
 * style.
 *
 * A downstream middleware author adds
 * `libraryDependencies += "org.adk4s" %% "adk4s-harness-testkit" % version`
 * and imports `org.adk4s.harness.testkit.*` to run the laws against their
 * own stack and deterministic model double.
 *
 * spec: middleware-laws — Requirement: Testkit module publication in main scope
 */
package object testkit
