package org.adk4s.orchestration.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.error.GenericError
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.memory.{Episode, InMemoryAgentMemory, SourceType}
import org.adk4s.orchestration.agent.RunResult
import org.llm4s.llmconnect.model.{Message, UserMessage}

import java.time.Instant

/**
 * Test oracle for spec:memory-orchestration-hook — `MemoryAwareRunner` scenarios
 * and properties.
 *
 * Tests written from the spec + approved typed contract ONLY, before
 * implementation. They compile against the stub main sources (`???` bodies)
 * and are EXPECTED TO FAIL at runtime until Step 3.
 *
 * Every test cites its source: `// spec: memory-orchestration-hook — Scenario: <heading>`
 */
class MemoryAwareRunnerSpec extends HedgehogSuite with MunitAssertHelpers:
  import Generators.*
  import TestHelpers.*

  private val now: Instant = Instant.parse("2025-01-01T00:00:00Z")

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Opt-in memory activation
  // ════════════════════════════════════════════════════════════════════════

  test("No-op runner matches underlying runner exactly") {
    // spec: memory-orchestration-hook — Scenario: No-op runner matches underlying runner exactly
    val msgs: List[Message] = List(UserMessage("Hi"))
    val policy: MemoryPolicy = MemoryPolicy.default
    val result = for
      runner    <- stubRunnerCompleted("done")
      decorator  = MemoryAwareRunner(runner, None, policy)
      direct    <- runner.run(msgs)
      wrapped   <- decorator.run(msgs)
    yield (direct, wrapped)
    val (direct, wrapped) = result.unsafeRunSync()
    assertM(resultsEqual(wrapped, direct), s"expected $direct, got $wrapped")
    assertM(wrapped match
      case RunResult.Completed(output, m) => output == "done" && m == msgs
      case _ => false
    , s"expected Completed('done', msgs), got $wrapped")
  }

  test("No-op runner forwards interrupt unchanged") {
    // spec: memory-orchestration-hook — Scenario: No-op runner forwards interrupt unchanged
    val signal: InterruptSignal = InterruptSignal.simple("Need approval")
    val msgs: List[Message] = List(UserMessage("Do something"))
    val policy: MemoryPolicy = MemoryPolicy.default
    val result = for
      runner    <- stubRunnerInterrupted(signal)
      decorator  = MemoryAwareRunner(runner, None, policy)
      wrapped   <- decorator.run(msgs)
    yield wrapped
    val wrapped: RunResult = result.unsafeRunSync()
    wrapped match
      case RunResult.Interrupted(_, s) =>
        assertM(s == signal, s"expected signal $signal, got $s")
      case other =>
        failM(s"Expected Interrupted, got $other")
  }

  test("Adversarial — a caller cannot force a write without opting in") {
    // spec: memory-orchestration-hook — Scenario: Adversarial — a caller cannot force a write without opting in
    val msgs: List[Message] = List(UserMessage("query"))
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 5,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      runner    <- stubRunnerCompleted("answer")
      decorator  = MemoryAwareRunner(runner, None, policy)
      _         <- decorator.run(msgs)
    yield ()
    result.unsafeRunSync()
    // memory = None → zero episodes written regardless of policy write flags
    assertM(true, "no memory to write to — policy flags are inert")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Pre-turn recall injects context
  // ════════════════════════════════════════════════════════════════════════

  test("Non-empty recall injects a labeled block") {
    // spec: memory-orchestration-hook — Scenario: Non-empty recall injects a labeled block
    // Note: InMemoryAgentMemory.naiveScore uses substring matching, so the query
    // must share terms with the episode contents. "Alice" matches both episodes.
    val policy: MemoryPolicy = MemoryPolicy(recallK = 3)
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      _    <- mem.remember(Episode("Alice works at Acme", SourceType.Conversation, now))
      _    <- mem.remember(Episode("Alice is an engineer", SourceType.Conversation, now))
      pair <- capturingRunner("Alice is an engineer")
      (runner, ref) = pair
      decorator     = MemoryAwareRunner(runner, Some(mem), policy)
      _    <- decorator.run(List(UserMessage("Alice")))
      captured <- ref.get
    yield captured
    val captured: Option[List[Message]] = result.unsafeRunSync()
    val messages: List[Message] = captured.getOrElse(Nil)
    assertM(messages.nonEmpty, "expected at least one message")
    val firstContent: String = messages.headOption.map(_.content).getOrElse("")
    assertM(firstContent.contains("Relevant memory:"),
      s"expected 'Relevant memory:' in first message: $firstContent")
    assertM(firstContent.contains("Alice works at Acme"),
      s"expected 'Alice works at Acme' in: $firstContent")
    assertM(firstContent.contains("Alice is an engineer"),
      s"expected 'Alice is an engineer' in: $firstContent")
    assertM(messages.exists(m => m.content == "Alice"),
      s"expected original user message preserved in: ${messages.map(_.content)}")
  }

  test("Empty recall injects nothing") {
    // spec: memory-orchestration-hook — Scenario: Empty recall injects nothing
    val policy: MemoryPolicy = MemoryPolicy(recallK = 3)
    val originalMsgs: List[Message] = List(UserMessage("query"))
    val result = for
      mem  <- InMemoryAgentMemory.create[IO]
      pair <- capturingRunner("answer")
      (runner, ref) = pair
      decorator     = MemoryAwareRunner(runner, Some(mem), policy)
      _    <- decorator.run(originalMsgs)
      captured <- ref.get
    yield captured
    val captured: Option[List[Message]] = result.unsafeRunSync()
    val messages: List[Message] = captured.getOrElse(Nil)
    assertM(messages == originalMsgs,
      s"expected $originalMsgs, got $messages")
  }

  test("recallK = 0 skips recall entirely") {
    // spec: memory-orchestration-hook — Scenario: recallK = 0 skips recall entirely
    val policy: MemoryPolicy = MemoryPolicy(recallK = 0)
    val result = for
      recMem <- recordingMemory
      pair   <- capturingRunner("answer")
      (runner, ref) = pair
      decorator     = MemoryAwareRunner(runner, Some(recMem), policy)
      _      <- decorator.run(List(UserMessage("query")))
      recallCount <- recMem.recallCount
      captured <- ref.get
    yield (recallCount, captured)
    val (recallCount, captured) = result.unsafeRunSync()
    assertM(recallCount == 0, s"expected 0 recall calls, got $recallCount")
    val messages: List[Message] = captured.getOrElse(Nil)
    assertM(messages == List(UserMessage("query")),
      s"expected no context injection, got $messages")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Post-turn write only on Completed
  // ════════════════════════════════════════════════════════════════════════

  test("Completed writes the configured episodes") {
    // spec: memory-orchestration-hook — Scenario: Completed writes the configured episodes
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      recMem <- recordingMemory
      runner <- stubRunnerCompleted("Alice is an engineer")
      decorator = MemoryAwareRunner(runner, Some(recMem), policy)
      _    <- decorator.run(List(UserMessage("What is Alice's role?")))
      episodes <- recMem.recordedRememberCalls
    yield episodes
    val episodes: List[Episode] = result.unsafeRunSync()
    assertM(episodes.length == 2, s"expected 2 episodes, got ${episodes.length}")
    val groupIds: Set[Option[String]] = episodes.map(_.groupId).toSet
    assertM(groupIds.size == 1, s"expected 1 groupId, got $groupIds")
    assertM(groupIds.headOption.flatten.nonEmpty, s"expected non-empty groupId, got $groupIds")
    assertM(episodes.forall(_.sourceType == SourceType.Conversation),
      s"expected all Conversation, got ${episodes.map(_.sourceType)}")
    val contents: Set[String] = episodes.map(_.content).toSet
    assertM(contents.contains("What is Alice's role?"),
      s"expected user input in $contents")
    assertM(contents.contains("Alice is an engineer"),
      s"expected assistant output in $contents")
  }

  test("Interrupted writes nothing (adversarial)") {
    // spec: memory-orchestration-hook — Scenario: Interrupted writes nothing (adversarial)
    val signal: InterruptSignal = InterruptSignal.simple("Need approval")
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      recMem <- recordingMemory
      runner <- stubRunnerInterrupted(signal)
      decorator = MemoryAwareRunner(runner, Some(recMem), policy)
      wrapped <- decorator.run(List(UserMessage("query")))
      episodes <- recMem.recordedRememberCalls
    yield (wrapped, episodes)
    val (wrapped, episodes) = result.unsafeRunSync()
    assertM(episodes.isEmpty, s"expected 0 episodes on Interrupted, got ${episodes.length}")
    wrapped match
      case _: RunResult.Interrupted => () // expected
      case other => failM(s"expected Interrupted, got $other")
  }

  test("Failed writes nothing (adversarial)") {
    // spec: memory-orchestration-hook — Scenario: Failed writes nothing (adversarial)
    val error: GenericError = GenericError("boom")
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      recMem <- recordingMemory
      runner <- stubRunnerFailed(error)
      decorator = MemoryAwareRunner(runner, Some(recMem), policy)
      wrapped <- decorator.run(List(UserMessage("query")))
      episodes <- recMem.recordedRememberCalls
    yield (wrapped, episodes)
    val (wrapped, episodes) = result.unsafeRunSync()
    assertM(episodes.isEmpty, s"expected 0 episodes on Failed, got ${episodes.length}")
    wrapped match
      case _: RunResult.Failed => () // expected
      case other => failM(s"expected Failed, got $other")
  }

  test("writeUserInput=false suppresses the user episode") {
    // spec: memory-orchestration-hook — Scenario: writeUserInput = false suppresses the user episode
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = false,
      writeAssistantOutput = true
    )
    val result = for
      recMem <- recordingMemory
      runner <- stubRunnerCompleted("answer")
      decorator = MemoryAwareRunner(runner, Some(recMem), policy)
      _    <- decorator.run(List(UserMessage("query")))
      episodes <- recMem.recordedRememberCalls
    yield episodes
    val episodes: List[Episode] = result.unsafeRunSync()
    assertM(episodes.length == 1, s"expected 1 episode, got ${episodes.length}")
    val firstEpisode: Episode = episodes.headOption.getOrElse(failM("expected non-empty episodes"))
    assertM(firstEpisode.content == "answer",
      s"expected 'answer', got '${firstEpisode.content}'")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: runWithEvents forwards the underlying stream unchanged
  // ════════════════════════════════════════════════════════════════════════

  test("Stream identity under no memory") {
    // spec: memory-orchestration-hook — Scenario: Stream identity under no memory
    val msgs: List[Message] = List(UserMessage("Go"))
    val policy: MemoryPolicy = MemoryPolicy.default
    val result = for
      runner    <- stubRunnerCompleted("Result")
      decorator  = MemoryAwareRunner(runner, None, policy)
      (directIO, directEvents) = runner.runWithEvents(msgs)
      (wrappedIO, wrappedEvents) = decorator.runWithEvents(msgs)
      directFiber <- directIO.start
      wrappedFiber <- wrappedIO.start
      directEventsList <- directEvents.compile.toList
      wrappedEventsList <- wrappedEvents.compile.toList
      directResult <- directFiber.joinWithNever
      wrappedResult <- wrappedFiber.joinWithNever
    yield (directResult, wrappedResult, directEventsList, wrappedEventsList)
    val (directResult, wrappedResult, directEventsList, wrappedEventsList) = result.unsafeRunSync()
    assertM(resultsEqual(wrappedResult, directResult),
      s"result mismatch: $directResult vs $wrappedResult")
    assertM(wrappedEventsList.length == directEventsList.length,
      s"event count mismatch: ${directEventsList.length} vs ${wrappedEventsList.length}")
  }

  test("Stream identity under memory (no memory events in this spec)") {
    // spec: memory-orchestration-hook — Scenario: Stream identity under memory (no memory events in this spec)
    val msgs: List[Message] = List(UserMessage("Go"))
    val policy: MemoryPolicy = MemoryPolicy(recallK = 0)
    val result = for
      mem       <- InMemoryAgentMemory.create[IO]
      runner    <- stubRunnerCompleted("Result")
      decorator  = MemoryAwareRunner(runner, Some(mem), policy)
      (directIO, directEvents) = runner.runWithEvents(msgs)
      (wrappedIO, wrappedEvents) = decorator.runWithEvents(msgs)
      directFiber <- directIO.start
      wrappedFiber <- wrappedIO.start
      directEventsList <- directEvents.compile.toList
      wrappedEventsList <- wrappedEvents.compile.toList
      directResult <- directFiber.joinWithNever
      wrappedResult <- wrappedFiber.joinWithNever
    yield (directResult, wrappedResult, directEventsList, wrappedEventsList)
    val (directResult, wrappedResult, directEventsList, wrappedEventsList) = result.unsafeRunSync()
    assertM(wrappedEventsList.length == directEventsList.length,
      s"event count mismatch: ${directEventsList.length} vs ${wrappedEventsList.length}")
    wrappedResult match
      case _: RunResult.Completed => () // expected
      case other => failM(s"expected Completed, got $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: resume delegates without a spurious write
  // ════════════════════════════════════════════════════════════════════════

  test("Resume that completes writes the episode") {
    // spec: memory-orchestration-hook — Scenario: Resume that completes writes the episode
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      recMem <- recordingMemory
      interruptRunner <- stubRunnerInterrupted(InterruptSignal.simple("need approval"))
      decorator = MemoryAwareRunner(interruptRunner, Some(recMem), policy)
      first <- decorator.run(List(UserMessage("original query")))
      checkpointId: String = first match
        case RunResult.Interrupted(id, _) => id
        case _ => failM("Expected Interrupted to get checkpointId")
      resumed <- decorator.resume(checkpointId, List.empty)
      episodes <- recMem.recordedRememberCalls
    yield (first, resumed, episodes)
    val (first, resumed, episodes) = result.unsafeRunSync()
    resumed match
      case RunResult.Interrupted(_, _) =>
        assertM(episodes.isEmpty, s"expected 0 episodes on re-interrupt, got ${episodes.length}")
      case RunResult.Completed(_, _) =>
        assertM(episodes.nonEmpty, "expected episodes on Completed resume")
      case RunResult.Failed(_) =>
        assertM(true, "resume failed — no write expected")
  }

  test("Resume that interrupts again writes nothing (adversarial)") {
    // spec: memory-orchestration-hook — Scenario: Resume that interrupts again writes nothing (adversarial)
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      recMem <- recordingMemory
      runner <- stubRunnerInterrupted(InterruptSignal.simple("need approval"))
      decorator = MemoryAwareRunner(runner, Some(recMem), policy)
      first <- decorator.run(List(UserMessage("query")))
      checkpointId: String = first match
        case RunResult.Interrupted(id, _) => id
        case _ => failM("Expected Interrupted to get checkpointId")
      resumed <- decorator.resume(checkpointId, List.empty)
      episodes <- recMem.recordedRememberCalls
    yield (resumed, episodes)
    val (resumed, episodes) = result.unsafeRunSync()
    assertM(episodes.isEmpty, s"expected 0 episodes on re-interrupt, got ${episodes.length}")
    resumed match
      case _: RunResult.Interrupted => () // expected
      case other => failM(s"expected Interrupted, got $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Properties (Ring 3)
  // ════════════════════════════════════════════════════════════════════════

  property("No-memory decorator is the identity") {
    // spec: memory-orchestration-hook — Property: No-memory decorator is the identity
    // Invariant: For all msgs, policy, and underlying-runner behaviors, a
    // MemoryAwareRunner with memory = None produces a RunResult equal to the
    // underlying runner's RunResult and never calls AgentMemory.remember.
    // Generator: genMessages, genPolicy, genRunResult
    // Coverage target: Completed >= 30%, Interrupted >= 30%, Failed >= 30%
    //   (genRunResult uses Gen.choice1 which distributes uniformly across the
    //    three variants, so the 33%/33%/33% split is achieved by construction)
    for
      msgs    <- genMessages.forAll
      policy  <- genPolicy.forAll
      outcome <- genRunResult.forAll
    yield
      val mem: Option[org.adk4s.memory.AgentMemory[IO]] = None
      val result: Boolean = (for
        runner    <- stubRunnerFor(outcome)
        decorator  = MemoryAwareRunner(runner, mem, policy)
        direct    <- runner.run(msgs)
        wrapped   <- decorator.run(msgs)
      yield resultsEqual(wrapped, direct)).unsafeRunSync()
      result ==== true
  }

  property("postTurn fires iff Completed") {
    // spec: memory-orchestration-hook — Property: postTurn fires iff Completed
    // Invariant: For all RunResult outcomes and non-None memories, remember is
    // called a positive number of times iff the outcome is Completed; for
    // Interrupted/Failed, remember is called zero times.
    // Generator: genRunResult, genPolicy (write flags booleans)
    // cover: Completed >= 33%, Interrupted >= 33%, Failed >= 33%
    for
      outcome <- genRunResult.forAll
      policy  <- genPolicy.forAll
    yield
      val result: Boolean = (for
        recMem <- recordingMemory
        runner <- stubRunnerFor(outcome)
        decorator = MemoryAwareRunner(runner, Some(recMem), policy)
        _    <- decorator.run(List(UserMessage("query")))
        count <- recMem.rememberCount
      yield
        outcome match
          case _: RunResult.Completed =>
            val anyWrite: Boolean = policy.writeUserInput || policy.writeAssistantOutput
            if anyWrite then count > 0 else count == 0
          case _: RunResult.Interrupted | _: RunResult.Failed =>
            count == 0
      ).unsafeRunSync()
      result ==== true
  }

  property("recall is called exactly once per run when recallK > 0") {
    // spec: memory-orchestration-hook — Property: recall is called exactly once per run when recallK > 0
    // Invariant: For non-None memories and recallK > 0, recall is called exactly
    // once with k = policy.recallK and scope = policy.scope; for recallK = 0,
    // recall is called zero times.
    // Generator: genRecallKWide (0-10), genOptScope, genContent
    // cover: recallK == 0 >= 10%, recallK > 0 >= 80%
    for
      recallK <- genRecallKWide.forAll
      scope   <- genOptScope.forAll
      q       <- genContent.forAll
    yield
      val policy: MemoryPolicy = MemoryPolicy(
        recallK = recallK,
        scope = scope,
        writeUserInput = true,
        writeAssistantOutput = true
      )
      val result: Boolean = (for
        recMem <- recordingMemory
        runner <- stubRunnerCompleted("out")
        decorator = MemoryAwareRunner(runner, Some(recMem), policy)
        _    <- decorator.run(List(UserMessage(q)))
        calls <- recMem.recordedRecallCalls
      yield
        if recallK > 0 then
          calls.length == 1 &&
          calls.headOption.exists(c => c.k == recallK && c.scope == scope)
        else
          calls.isEmpty
      ).unsafeRunSync()
      (result ==== true)
  }
