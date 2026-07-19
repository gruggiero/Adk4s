package org.adk4s.orchestration.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.memory.{AgentMemory, Episode, InMemoryAgentMemory, SourceType}

import java.time.Instant

/**
 * Test oracle for spec:memory-orchestration-hook — `MemoryHook` scenarios
 * and the "groupId shared" property.
 *
 * Tests written from the spec + approved typed contract ONLY, before
 * implementation. They compile against the stub main sources (`???` bodies)
 * and are EXPECTED TO FAIL at runtime until Step 3.
 *
 * Every test cites its source: `// spec: memory-orchestration-hook — Scenario: <heading>`
 */
class MemoryHookSpec extends HedgehogSuite with MunitAssertHelpers:
  import Generators.*

  private val now: Instant = Instant.parse("2025-01-01T00:00:00Z")

  // ── Scenarios ────────────────────────────────────────────────────────────

  test("preTurn returns None when memory is None") {
    // spec: memory-orchestration-hook — Scenario: No-op runner matches underlying runner exactly
    // (preTurn aspect: no-op when memory = None)
    val hook: MemoryHook = MemoryHook(None, MemoryPolicy.default)
    val result: Option[String] = hook.preTurn("query").unsafeRunSync()
    assertM(result.isEmpty, s"expected None, got: $result")
  }

  test("postTurn is a no-op (Unit) when memory is None") {
    // spec: memory-orchestration-hook — Scenario: No-op runner matches underlying runner exactly
    // (postTurn aspect: no-op when memory = None)
    val hook: MemoryHook = MemoryHook(None, MemoryPolicy.default)
    val result: Unit = hook.postTurn("group-1", "query", "answer", now).unsafeRunSync()
    assertEqualsM(result, ())
  }

  test("postTurn writes both episodes when both write flags are true") {
    // spec: memory-orchestration-hook — Scenario: Completed writes the configured episodes
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val recMem: TestHelpers.RecordingAgentMemory = TestHelpers.recordingMemory.unsafeRunSync()
    val hook: MemoryHook = MemoryHook(Some(recMem), policy)
    hook.postTurn("turn-1", "What is Alice's role?", "Alice is an engineer", now).unsafeRunSync()
    val episodes: List[Episode] = recMem.recordedRememberCalls.unsafeRunSync()
    assertEqualsM(episodes.length, 2, s"expected 2 episodes, got ${episodes.length}")
    val groupIds: Set[Option[String]] = episodes.map(_.groupId).toSet
    assertEqualsM(groupIds, Set[Option[String]](Some("turn-1")), s"expected Set(Some(turn-1)), got $groupIds")
    assertM(episodes.forall(_.sourceType == SourceType.Conversation),
      s"expected all Conversation, got ${episodes.map(_.sourceType)}")
    val contents: Set[String] = episodes.map(_.content).toSet
    assertM(contents.contains("What is Alice's role?"), s"expected user input in $contents")
    assertM(contents.contains("Alice is an engineer"), s"expected assistant output in $contents")
  }

  test("postTurn with writeUserInput=false suppresses the user episode") {
    // spec: memory-orchestration-hook — Scenario: writeUserInput = false suppresses the user episode
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = false,
      writeAssistantOutput = true
    )
    val recMem: TestHelpers.RecordingAgentMemory = TestHelpers.recordingMemory.unsafeRunSync()
    val hook: MemoryHook = MemoryHook(Some(recMem), policy)
    hook.postTurn("turn-1", "query", "answer", now).unsafeRunSync()
    val episodes: List[Episode] = recMem.recordedRememberCalls.unsafeRunSync()
    assertEqualsM(episodes.length, 1, s"expected 1 episode, got ${episodes.length}")
    val firstEpisode: Episode = episodes.headOption.getOrElse(failM("expected non-empty episodes"))
    assertEqualsM(firstEpisode.content, "answer",
      s"expected 'answer', got '${firstEpisode.content}'")
  }

  // ── Properties (Ring 3) ──────────────────────────────────────────────────

  property("groupId is shared across the turn's episodes") {
    // spec: memory-orchestration-hook — Property: groupId is shared across the turn's episodes
    // Invariant: For Completed outcomes with both write flags true, the two Episodes
    // share the same non-None groupId; with one flag true, the single episode's
    // groupId is Some(_).
    // Generator strategy: genPolicy (booleans), genContent, genInstant
    // cover: both-true >= 40%, one-true >= 40%
    for
      policy <- genPolicy.forAll
      q      <- genContent.forAll
      out    <- genContent.forAll
      at     <- genInstant.forAll
    yield
      val recMem: TestHelpers.RecordingAgentMemory = TestHelpers.recordingMemory.unsafeRunSync()
      val hook: MemoryHook = MemoryHook(Some(recMem), policy)
      hook.postTurn("turn-1", q, out, at).unsafeRunSync()
      val episodes: List[Episode] = recMem.recordedRememberCalls.unsafeRunSync()
      val groupIds: Set[Option[String]] = episodes.map(_.groupId).toSet
      val anyWrite: Boolean = policy.writeUserInput || policy.writeAssistantOutput
      val result: Boolean =
        if anyWrite then
          episodes.nonEmpty && groupIds.size == 1 && groupIds.headOption.flatten.nonEmpty
        else
          episodes.isEmpty
      (result ==== true)
  }
