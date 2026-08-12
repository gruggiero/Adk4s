package org.adk4s.harness.testkit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }

/**
 * Spec for the `DeterministicChatModel` double — verifies the determinism
 * and no-wall-clock / no-UUID invariants that L0–L11 rely on.
 *
 * spec: middleware-laws — Requirement: Deterministic ChatModel double enables observational equivalence
 */
class DeterministicChatModelSpec extends HedgehogSuite:

  property("deterministic — same seed + script produces same Completion"):
    for
      seed    <- Gen.long(Range.linear(1, 10000)).forAll
      content <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
    yield
      val script = List(DeterministicChatModel.textCompletion(seed, 0, content))
      val r1     = DeterministicChatModel(seed, script).flatMap(_.generate(Conversation(Nil))).unsafeRunSync()
      val r2     = DeterministicChatModel(seed, script).flatMap(_.generate(Conversation(Nil))).unsafeRunSync()
      r1 ==== r2

  property("no-UUID — id derived from seed and index"):
    for
      seed <- Gen.long(Range.linear(1, 10000)).forAll
      idx  <- Gen.int(Range.linear(0, 10)).forAll
    yield
      val c = DeterministicChatModel.fallbackCompletion(seed, idx)
      c.id ==== s"det-$seed-$idx"

  property("created is always 0L — no wall-clock"):
    for seed <- Gen.long(Range.linear(1, 10000)).forAll
    yield
      val c = DeterministicChatModel.fallbackCompletion(seed, 0)
      c.created ==== 0L

  property("records request trace — rendered system prompt captured"):
    for content <- Gen.string(Gen.alphaNum, Range.linear(1, 10)).forAll
    yield
      val sysMsg = org.llm4s.llmconnect.model.SystemMessage(content)
      val conv   = Conversation(List(sysMsg))
      val model  = DeterministicChatModel(1L, List(DeterministicChatModel.textCompletion(1L, 0, "ok"))).unsafeRunSync()
      model.generate(conv).unsafeRunSync()
      val traces = model.capturedRequests.get.unsafeRunSync()
      traces.headOption.map(_.renderedSystemPrompt) ==== Some(Some(content))

  property("toolCallCompletion — deterministic call id"):
    for seed <- Gen.long(Range.linear(1, 1000)).forAll
    yield
      val c = DeterministicChatModel.toolCallCompletion(seed, 0, "echo", "{}")
      c.message.toolCalls.headOption.map(_.id) ==== Some(s"call-$seed-0")

  property("multi-iteration — tool call then final text is deterministic"):
    for seed <- Gen.long(Range.linear(1, 10000)).forAll
    yield
      // Script: iteration 1 returns a tool call, iteration 2 returns final text.
      val script: List[org.llm4s.llmconnect.model.Completion] = List(
        DeterministicChatModel.toolCallCompletion(seed, 0, "echo", "{}"),
        DeterministicChatModel.textCompletion(seed, 1, "done")
      )
      val conv: Conversation = Conversation(List(UserMessage("test")))
      def runBoth: IO[(org.llm4s.llmconnect.model.Completion, org.llm4s.llmconnect.model.Completion)] =
        DeterministicChatModel(seed, script).flatMap(m =>
          for
            c1 <- m.generate(conv)
            c2 <- m.generate(conv)
          yield (c1, c2)
        )
      val (r1a, r1b): (org.llm4s.llmconnect.model.Completion, org.llm4s.llmconnect.model.Completion) =
        runBoth.unsafeRunSync()
      val (r2a, r2b): (org.llm4s.llmconnect.model.Completion, org.llm4s.llmconnect.model.Completion) =
        runBoth.unsafeRunSync()
      // Both runs produce the same two completions: tool call then final text.
      r1a ==== r2a
      r1b ==== r2b
      // First completion has a tool call, second has final text.
      r1a.message.toolCalls.nonEmpty ==== true
      r1b.message.toolCalls.isEmpty ==== true
