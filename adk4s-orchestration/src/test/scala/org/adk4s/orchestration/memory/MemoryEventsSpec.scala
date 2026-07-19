package org.adk4s.orchestration.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.error.GenericError
import org.adk4s.core.interrupt.{AgentEvent, InterruptSignal, RunPath}
import org.adk4s.memory.{Episode, InMemoryAgentMemory, SourceType}
import org.adk4s.orchestration.agent.RunResult
import org.llm4s.llmconnect.model.{Message, UserMessage}

import java.time.Instant

/**
 * Test oracle for spec:memory-orchestration-events — `MemoryRecalled` and
 * `MemoryWritten` event emission scenarios and properties.
 *
 * Tests written from the spec + approved typed contract ONLY, before
 * implementation. They compile against the main sources (which have the two
 * new `AgentEvent` variants but do NOT yet emit them) and are EXPECTED TO
 * FAIL at runtime until Step 3.
 *
 * Every test cites its source:
 *   `// spec: memory-orchestration-events — Scenario: <heading>`
 */
class MemoryEventsSpec extends HedgehogSuite with MunitAssertHelpers:
  import Generators.*
  import TestHelpers.*

  private val now: Instant = Instant.parse("2025-01-01T00:00:00Z")

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: MemoryRecalled event
  // ════════════════════════════════════════════════════════════════════════

  test("Non-empty recall emits hitCount > 0") {
    // spec: memory-orchestration-events — Scenario: Non-empty recall emits hitCount > 0
    val policy: MemoryPolicy = MemoryPolicy(recallK = 3)
    val query: String = "Alice"
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      _   <- mem.remember(Episode("Alice works at Acme", SourceType.Conversation, now))
      _   <- mem.remember(Episode("Alice is an engineer", SourceType.Conversation, now))
      triple <- stubRunnerCompletedWithEmitter("answer", agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage(query)))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val recalled: List[AgentEvent.MemoryRecalled] =
      eventList.collect { case e: AgentEvent.MemoryRecalled => e }
    assertM(recalled.length == 1, s"expected exactly 1 MemoryRecalled, got ${recalled.length}")
    val ev: AgentEvent.MemoryRecalled =
      recalled.headOption.getOrElse(failM("expected non-empty MemoryRecalled"))
    assertM(ev.query == query, s"expected query '$query', got '${ev.query}'")
    assertM(ev.hitCount == 2, s"expected hitCount 2, got ${ev.hitCount}")
  }

  test("Empty recall emits hitCount = 0") {
    // spec: memory-orchestration-events — Scenario: Empty recall emits hitCount = 0
    val policy: MemoryPolicy = MemoryPolicy(recallK = 3)
    val query: String = "nothing-matches-this"
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      triple <- stubRunnerCompletedWithEmitter("answer", agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage(query)))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val recalled: List[AgentEvent.MemoryRecalled] =
      eventList.collect { case e: AgentEvent.MemoryRecalled => e }
    assertM(recalled.length == 1, s"expected exactly 1 MemoryRecalled, got ${recalled.length}")
    val ev: AgentEvent.MemoryRecalled =
      recalled.headOption.getOrElse(failM("expected non-empty MemoryRecalled"))
    assertM(ev.query == query, s"expected query '$query', got '${ev.query}'")
    assertM(ev.hitCount == 0, s"expected hitCount 0, got ${ev.hitCount}")
  }

  test("recallK = 0 emits hitCount = 0") {
    // spec: memory-orchestration-events — Scenario: recallK = 0 emits hitCount = 0
    val policy: MemoryPolicy = MemoryPolicy(recallK = 0)
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      triple <- stubRunnerCompletedWithEmitter("answer", agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage("query")))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val recalled: List[AgentEvent.MemoryRecalled] =
      eventList.collect { case e: AgentEvent.MemoryRecalled => e }
    assertM(recalled.length == 1, s"expected exactly 1 MemoryRecalled, got ${recalled.length}")
    val ev: AgentEvent.MemoryRecalled =
      recalled.headOption.getOrElse(failM("expected non-empty MemoryRecalled"))
    assertM(ev.hitCount == 0, s"expected hitCount 0, got ${ev.hitCount}")
  }

  test("No memory emits no MemoryRecalled (adversarial)") {
    // spec: memory-orchestration-events — Scenario: No memory emits no MemoryRecalled (adversarial)
    val policy: MemoryPolicy = MemoryPolicy(recallK = 3)
    val result = for
      triple <- stubRunnerCompletedWithEmitter("answer", agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, None, policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage("query")))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val recalled: List[AgentEvent.MemoryRecalled] =
      eventList.collect { case e: AgentEvent.MemoryRecalled => e }
    assertM(recalled.isEmpty, s"expected 0 MemoryRecalled, got ${recalled.length}")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: MemoryWritten event
  // ════════════════════════════════════════════════════════════════════════

  test("Completed with both write flags emits episodes = 2") {
    // spec: memory-orchestration-events — Scenario: Completed with both write flags emits episodes = 2
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      triple <- stubRunnerCompletedWithEmitter("answer", agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage("query")))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val written: List[AgentEvent.MemoryWritten] =
      eventList.collect { case e: AgentEvent.MemoryWritten => e }
    assertM(written.length == 1, s"expected exactly 1 MemoryWritten, got ${written.length}")
    val ev: AgentEvent.MemoryWritten =
      written.headOption.getOrElse(failM("expected non-empty MemoryWritten"))
    assertM(ev.episodes == 2, s"expected episodes 2, got ${ev.episodes}")
  }

  test("Completed with both write flags false emits episodes = 0") {
    // spec: memory-orchestration-events — Scenario: Completed with both write flags false emits episodes = 0
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = false,
      writeAssistantOutput = false
    )
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      triple <- stubRunnerCompletedWithEmitter("answer", agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage("query")))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val written: List[AgentEvent.MemoryWritten] =
      eventList.collect { case e: AgentEvent.MemoryWritten => e }
    assertM(written.length == 1, s"expected exactly 1 MemoryWritten, got ${written.length}")
    val ev: AgentEvent.MemoryWritten =
      written.headOption.getOrElse(failM("expected non-empty MemoryWritten"))
    assertM(ev.episodes == 0, s"expected episodes 0, got ${ev.episodes}")
  }

  test("Interrupted emits no MemoryWritten (adversarial)") {
    // spec: memory-orchestration-events — Scenario: Interrupted emits no MemoryWritten (adversarial)
    val signal: InterruptSignal = InterruptSignal.simple("need approval")
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      triple <- stubRunnerInterruptedWithEmitter(signal, agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage("query")))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val written: List[AgentEvent.MemoryWritten] =
      eventList.collect { case e: AgentEvent.MemoryWritten => e }
    assertM(written.isEmpty, s"expected 0 MemoryWritten on Interrupted, got ${written.length}")
  }

  test("Failed emits no MemoryWritten (adversarial)") {
    // spec: memory-orchestration-events — Scenario: Failed emits no MemoryWritten (adversarial)
    val error: GenericError = GenericError("boom")
    val policy: MemoryPolicy = MemoryPolicy(
      recallK = 0,
      writeUserInput = true,
      writeAssistantOutput = true
    )
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      triple <- stubRunnerFailedWithEmitter(error, agentName = "alice")
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage("query")))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    val written: List[AgentEvent.MemoryWritten] =
      eventList.collect { case e: AgentEvent.MemoryWritten => e }
    assertM(written.isEmpty, s"expected 0 MemoryWritten on Failed, got ${written.length}")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: New variants carry the run's RunPath
  // ════════════════════════════════════════════════════════════════════════

  test("RunPath matches the runner's other events") {
    // spec: memory-orchestration-events — Scenario: RunPath matches the runner's other events
    val policy: MemoryPolicy = MemoryPolicy(recallK = 0, writeUserInput = true, writeAssistantOutput = true)
    val agentName: String = "alice"
    val result = for
      mem <- InMemoryAgentMemory.create[IO]
      triple <- stubRunnerCompletedWithEmitter("answer", agentName = agentName)
      (runner, em, name) = triple
      decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
      (io, events) = decorator.runWithEvents(List(UserMessage("query")))
      fiber <- io.start
      eventList <- events.compile.toList
      _ <- fiber.joinWithNever
    yield eventList
    val eventList: List[AgentEvent] = result.unsafeRunSync()
    // The runner's MessageOutput event has runPath = RunPath.of(agentName) (after scoping)
    val memoryEvents: List[AgentEvent] = eventList.collect {
      case e: AgentEvent.MemoryRecalled => e: AgentEvent
      case e: AgentEvent.MemoryWritten  => e: AgentEvent
    }
    val runnerEvents: List[AgentEvent] = eventList.filterNot { (e: AgentEvent) =>
      e match
        case _: AgentEvent.MemoryRecalled => true
        case _: AgentEvent.MemoryWritten  => true
        case _                            => false
    }
    assertM(runnerEvents.nonEmpty, "expected at least one runner event (MessageOutput)")
    assertM(memoryEvents.nonEmpty, "expected at least one memory event")
    val runnerPaths: Set[RunPath] = runnerEvents.map(_.runPath).toSet
    assertM(
      memoryEvents.forall(e => runnerPaths.contains(e.runPath)),
      s"memory event runPaths ${memoryEvents.map(_.runPath.show)} must match runner paths ${runnerPaths.map(_.show)}"
    )
  }

  // ════════════════════════════════════════════════════════════════════════
  // Properties (Ring 3)
  // ════════════════════════════════════════════════════════════════════════

  property("MemoryRecalled fires iff memory is present") {
    // spec: memory-orchestration-events — Property: MemoryRecalled fires iff memory is present
    // Invariant: exactly one MemoryRecalled event is emitted per runWithEvents
    // invocation if and only if memory is non-None; when memory = None, zero
    // MemoryRecalled events are emitted.
    // Generator: genOptMemory (frequency 1 None / 3 Some), genPolicy, genRunResult
    // cover: memory.isDefined >= 60%, memory.isEmpty >= 30%
    for
      hasMemory <- Gen.frequency1(1 -> Gen.constant(false), 3 -> Gen.constant(true)).forAll
      policy    <- genPolicy.forAll
      outcome   <- genRunResult.forAll
    yield
      val recalledCount: Int = (for
        memOpt <- if hasMemory then InMemoryAgentMemory.create[IO].map(Some(_)) else IO.pure(None)
        triple <- stubRunnerForWithEmitter(outcome, agentName = "alice")
        (runner, em, name) = triple
        decorator = MemoryAwareRunner(runner, memOpt, policy, Some(em), Some(name))
        (io, events) = decorator.runWithEvents(List(UserMessage("query")))
        fiber <- io.start
        eventList <- events.compile.toList
        _ <- fiber.joinWithNever
      yield
        eventList.collect { case e: AgentEvent.MemoryRecalled => e }.length
      ).unsafeRunSync()
      val result: Boolean =
        if hasMemory then recalledCount == 1 else recalledCount == 0
      result ==== true
  }

  property("MemoryWritten fires iff Completed and memory present") {
    // spec: memory-orchestration-events — Property: MemoryWritten fires iff Completed and memory present
    // Invariant: exactly one MemoryWritten event is emitted if and only if
    // memory is non-None AND the RunResult is Completed; otherwise zero.
    // The episodes field equals the number of remember calls that succeeded.
    // Generator: genOptMemory, genPolicy (write flags booleans), genRunResult
    // cover: Completed >= 33%, Interrupted >= 33%, Failed >= 33%, memory.isEmpty >= 20%
    for
      hasMemory <- Gen.frequency1(1 -> Gen.constant(false), 3 -> Gen.constant(true)).forAll
      policy    <- genPolicy.forAll
      outcome   <- genRunResult.forAll
    yield
      val isCompleted: Boolean = outcome match
        case _: RunResult.Completed => true
        case _                      => false
      val expectedEpisodes: Int =
        if hasMemory && isCompleted then
          val p: MemoryPolicy = policy
          (if p.writeUserInput then 1 else 0) + (if p.writeAssistantOutput then 1 else 0)
        else 0
      val (writtenCount: Int, writtenEpisodes: Option[Int]) = (for
        memOpt <- if hasMemory then InMemoryAgentMemory.create[IO].map(Some(_)) else IO.pure(None)
        triple <- stubRunnerForWithEmitter(outcome, agentName = "alice")
        (runner, em, name) = triple
        decorator = MemoryAwareRunner(runner, memOpt, policy, Some(em), Some(name))
        (io, events) = decorator.runWithEvents(List(UserMessage("query")))
        fiber <- io.start
        eventList <- events.compile.toList
        _ <- fiber.joinWithNever
      yield
        val written: List[AgentEvent.MemoryWritten] =
          eventList.collect { case e: AgentEvent.MemoryWritten => e }
        (written.length, written.headOption.map(_.episodes))
      ).unsafeRunSync()
      val shouldFire: Boolean = hasMemory && isCompleted
      val result: Boolean =
        if shouldFire then
          writtenCount == 1 && writtenEpisodes == Some(expectedEpisodes)
        else
          writtenCount == 0
      result ==== true
  }

  property("New variants' RunPath equals the runner's other events") {
    // spec: memory-orchestration-events — Property: New variants' RunPath equals the runner's other events
    // Invariant: the runPath of every MemoryRecalled and MemoryWritten event
    // equals the runPath of the MessageOutput (or Interrupted/ErrorOccurred)
    // event the underlying runner emits for the same run.
    // Generator: genAgentName (1..12 chars), genRunResult
    for
      agentName <- Gen.string(Gen.alphaNum, Range.linear(1, 12)).forAll
      outcome   <- genRunResult.forAll
    yield
      val (allPathsMatch: Boolean) = (for
        mem <- InMemoryAgentMemory.create[IO]
        triple <- stubRunnerForWithEmitter(outcome, agentName = agentName)
        (runner, em, name) = triple
        policy = MemoryPolicy(recallK = 0, writeUserInput = true, writeAssistantOutput = true)
        decorator = MemoryAwareRunner(runner, Some(mem), policy, Some(em), Some(name))
        (io, events) = decorator.runWithEvents(List(UserMessage("query")))
        fiber <- io.start
        eventList <- events.compile.toList
        _ <- fiber.joinWithNever
      yield
        val memoryEvents: List[AgentEvent] = eventList.collect {
          case e: AgentEvent.MemoryRecalled => e: AgentEvent
          case e: AgentEvent.MemoryWritten  => e: AgentEvent
        }
        val runnerEvents: List[AgentEvent] = eventList.filterNot { (e: AgentEvent) =>
          e match
            case _: AgentEvent.MemoryRecalled => true
            case _: AgentEvent.MemoryWritten  => true
            case _                            => false
        }
        if memoryEvents.isEmpty then true
        else
          val runnerPaths: Set[RunPath] = runnerEvents.map(_.runPath).toSet
          memoryEvents.forall(e => runnerPaths.contains(e.runPath))
      ).unsafeRunSync()
      allPathsMatch ==== true
  }
