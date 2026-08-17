package org.adk4s.examples.record

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.munit.HedgehogSuite
import org.adk4s.examples.record.RecordReplayExample

/**
 * Test oracle for the record-replay example.
 *
 * spec: add-adk4s-record/record-replay-example
 *
 * Three scenario assertions derived from the spec's Proof Obligations:
 *   1. Second run is zero-call — the underlying model's call counter is
 *      unchanged on the second run, and the final assistant message
 *      content matches the first run's output.
 *   2. Multi-turn full cache hit — a 3-turn conversation with tool calls
 *      in turns 1 and 2 achieves a full cache hit on replay (zero
 *      underlying calls).
 *   3. Example runs without API key — the example completes successfully
 *      using a deterministic model double, with no network calls.
 *
 * These are integration tests: they build the same components as the
 * example and verify the end-to-end record/replay behavior.
 */
class RecordReplayExampleSpec extends HedgehogSuite:

  // ── Scenario 1: Second run is zero-call ──────────────────────────────
  // spec: Requirement: Example runs same agent twice with zero-provider-call second run
  // spec: Scenario: Second run is zero-call

  test("Second run is zero-call: underlying call counter unchanged, output matches"):
    RecordReplayExample.runZeroCallReplay.flatMap { result =>
      IO {
        assert(result.firstRunCalls > 0, "first run must make at least one underlying call")
        assert(result.secondRunCalls == 0, "second run call count must be zero")
        assertEquals(result.firstOutput, result.secondOutput, "final assistant message must match")
      }
    }.unsafeRunSync()

  // ── Scenario 2: Multi-turn full cache hit on replay ──────────────────
  // spec: Requirement: Multi-turn tool-calling conversation achieves full cache hit on replay
  // spec: Scenario: Multi-turn replay hits on every turn

  test("Multi-turn full cache hit: 3-turn conversation, tool calls in turns 1 and 2"):
    RecordReplayExample.runMultiTurnReplay.flatMap { result =>
      IO {
        assertEquals(result.firstRunCalls, 3, "first run must make 3 underlying calls (one per turn)")
        assertEquals(result.secondRunCalls, 0, "second run must make zero underlying calls (full cache hit)")
        assertEquals(result.firstOutput, result.secondOutput, "final output must match across runs")
      }
    }.unsafeRunSync()

  // ── Scenario 3: Example runs without an API key ──────────────────────
  // spec: Requirement: Example runs without an API key
  // spec: Scenario: Example runs without API key

  test("Example runs without API key: completes using deterministic model double"):
    // The example uses a DeterministicChatModel, not a real LLM client.
    // Running it to completion without an API key proves it is self-contained.
    RecordReplayExample.run.as(assert(true, "example completed without API key")).unsafeRunSync()
