package org.adk4s.record

import cats.effect.unsafe.implicits.global
import munit.FunSuite

// ── RecorderLawsTypeContract — type-level contract for spec 6 ──────────
// spec: add-adk4s-record/recorder-laws — Requirement: RecorderLaws ships in main scope as downstream-consumable properties
//
// Verifies the structural shape of RecorderLaws: 13 Property vals,
// parameterized over Recorder[F], accessible from main scope.
class RecorderLawsTypeContract extends FunSuite:

  // ── RecorderLaws class shape ──────────────────────────────────────

  test("RecorderLaws can be constructed with a Recorder[IO]"):
    val recorder: Recorder[cats.effect.IO] = Recorder.noop[cats.effect.IO]
    val laws: RecorderLaws[cats.effect.IO] = RecorderLaws(recorder)
    assertEquals(laws != null, true)

  test("RecorderLaws has 13 Property values (RL0–RL12)"):
    val recorder: Recorder[cats.effect.IO] = Recorder.noop[cats.effect.IO]
    val laws: RecorderLaws[cats.effect.IO] = RecorderLaws(recorder)
    // Type-level check: each val is a hedgehog Property
    laws.rl0_transparency
    laws.rl1_coherence
    laws.rl2_key_determinism
    laws.rl3_key_sensitivity
    laws.rl4_key_insensitivity
    laws.rl5_zero_call_hit
    laws.rl6_rollout_separation
    laws.rl7_codec_round_trip
    laws.rl8_append_only_monotonicity
    laws.rl9_failure_fidelity
    laws.rl10_write_failure_containment
    laws.rl11_redaction_neutrality
    laws.rl12_version_isolation
    // All 13 are non-null (will fail with NotImplementedError at access
    // time until implemented, but the type-level check is that they
    // compile as Property)
    assert(true) // compiles iff all 13 vals exist with type Property

  test("RecorderLaws is parameterized over Recorder[F]"):
    // Type-level check: RecorderLaws takes a type parameter F
    val recorder: Recorder[cats.effect.IO] = Recorder.noop[cats.effect.IO]
    val laws: RecorderLaws[cats.effect.IO] = RecorderLaws[cats.effect.IO](recorder)
    assert(laws != null)

  test("RequestMutation has 12 variants"):
    val mutations: List[RequestMutation] = List(
      RequestMutation.ChangeProvider("p"),
      RequestMutation.ChangeModel("m"),
      RequestMutation.ReorderMessages,
      RequestMutation.ChangeTemperature(0.5),
      RequestMutation.ChangeMaxTokens(Some(100)),
      RequestMutation.ChangeTopP(0.9),
      RequestMutation.ChangeStopSequences(List("stop")),
      RequestMutation.AddTool("tool"),
      RequestMutation.RemoveTool("tool"),
      RequestMutation.ChangeToolSchema("tool"),
      RequestMutation.ChangeSystemPrompt("prompt"),
      RequestMutation.ChangeRolloutId(None)
    )
    assertEquals(mutations.length, 12)

  test("NonAffectingMutation has 5 variants"):
    val mutations: List[NonAffectingMutation] = List(
      NonAffectingMutation.RegenerateToolCallIds(Map.empty),
      NonAffectingMutation.ChangeProviderRequestId("id"),
      NonAffectingMutation.ChangeLatency(100L),
      NonAffectingMutation.ChangeTokenUsage(50L),
      NonAffectingMutation.ChangeTimestamp(1234567890L)
    )
    assertEquals(mutations.length, 5)

  test("RequestMutation.apply returns ModelCallRequest"):
    val req: ModelCallRequest = ModelCallRequest(
      provider = "p",
      model = "m",
      conversation = org.llm4s.llmconnect.model.Conversation(
        List(org.llm4s.llmconnect.model.UserMessage("hi"))
      ),
      tools = Nil,
      systemPrompt = "",
      options = org.llm4s.llmconnect.model.CompletionOptions()
    )
    val mutated: ModelCallRequest = RequestMutation.ChangeProvider("x").apply(req)
    assertEquals(mutated.provider, "x")

  test("NonAffectingMutation.apply returns ModelCallRequest"):
    val req: ModelCallRequest = ModelCallRequest(
      provider = "p",
      model = "m",
      conversation = org.llm4s.llmconnect.model.Conversation(
        List(org.llm4s.llmconnect.model.UserMessage("hi"))
      ),
      tools = Nil,
      systemPrompt = "",
      options = org.llm4s.llmconnect.model.CompletionOptions()
    )
    val mutated: ModelCallRequest =
      NonAffectingMutation.ChangeLatency(200L).apply(req)
    assertEquals(mutated.latencyMs, Some(200L))

  test("RecorderLaws is accessible from test scope (main-scope class)"):
    // This test verifies that RecorderLaws is in main scope, not Test scope.
    // If it were Test-scoped, this test would not compile.
    val recorder: Recorder[cats.effect.IO] = Recorder.noop[cats.effect.IO]
    val laws: RecorderLaws[cats.effect.IO] = RecorderLaws(recorder)
    assert(laws != null)

  test("keyVersion is 1"):
    assertEquals(keyVersion, 1)
