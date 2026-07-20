package org.adk4s.examples.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.foldable.toFoldableOps
import cats.syntax.traverse.toTraverseOps
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.interrupt.{ AgentEvent, AgentEventEmitter }
import org.adk4s.memory.{ AgentMemory, MemoryHit }
import org.adk4s.orchestration.agent.{ AgentRunner, ReactAgent, RunResult }
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.adk4s.orchestration.memory.{ MemoryAwareRunner, MemoryPolicy }
import org.llm4s.llmconnect.model.UserMessage

import java.nio.file.{ Files, Path }

/**
 * Smoke test for the cross-run memory example.
 *
 * Verifies the end-to-end flow that `CrossRunMemoryExample` demonstrates:
 *   - A1: fact taught in Run 1 is recalled in Run 2 (same memory instance)
 *   - A2: fact taught in Run 1 is recalled in Run 3 (fresh FileBackedAgentMemory)
 *   - Observability: MemoryRecalled and MemoryWritten events are emitted
 *   - Adversarial: mock model echoes recalled memory in its response
 */
class CrossRunMemorySmokeSpec extends HedgehogSuite:

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def tempDir: IO[Path] =
    IO.blocking(Files.createTempDirectory("cross-run-smoke"))

  private def cleanDir(dir: Path): IO[Unit] =
    IO.blocking {
      val file: Path = dir.resolve("episodes.jsonl")
      if Files.exists(file) then Files.delete(file)
    }

  private def buildRunner(
    memory: AgentMemory[IO],
    policy: MemoryPolicy,
    emitter: AgentEventEmitter
  ): IO[MemoryAwareRunner] =
    for
      checkpointStore <- InMemoryCheckpointStore.create
      agent = ReactAgent.create(
        name = "smoke-agent",
        description = "Smoke test agent",
        model = new CrossRunMemoryExample.MemoryEchoMockModel(),
        tools = Nil,
        systemPrompt = Some("You are a helpful assistant with cross-run memory."),
        maxSteps = 1,
        emitter = emitter
      )
      baseRunner = AgentRunner.create(agent, checkpointStore, emitter)
    yield MemoryAwareRunner(
      baseRunner,
      Some(memory),
      policy,
      Some(emitter),
      Some("smoke-agent")
    )

  /** Collect all events from a runner's event stream during a single run. */
  private def runWithEvents(
    runner: MemoryAwareRunner,
    emitter: AgentEventEmitter,
    userMessage: String
  ): IO[(RunResult, List[AgentEvent])] =
    val (ioResult: IO[RunResult], stream: fs2.Stream[IO, AgentEvent]) =
      runner.runWithEvents(List(UserMessage(userMessage)), maxSteps = 1)
    for
      eventsRef <- cats.effect.kernel.Ref.of[IO, List[AgentEvent]](Nil)
      _         <- stream.evalTap((e: AgentEvent) => eventsRef.update(_ :+ e)).compile.drain.start.void
      result    <- ioResult
      _         <- emitter.complete
      events    <- eventsRef.get
    yield (result, events)

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Cross-run persistence — same memory instance
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: A1 — fact taught in Run 1 is recalled in Run 2 ─────────────

  test("A1: fact taught in Run 1 is recalled in Run 2 (same memory instance)") {
    // spec: cross-run-memory-example — Scenario: A1
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir  <- tempDir
      _    <- cleanDir(dir)
      mem  <- FileBackedAgentMemory[IO](dir)
      em1  <- AgentEventEmitter.create()
      r1   <- buildRunner(mem, policy, em1)
      _    <- r1.run(List(UserMessage("My favorite color is blue.")), maxSteps = 1)
      em2  <- AgentEventEmitter.create()
      r2   <- buildRunner(mem, policy, em2)
      res2 <- r2.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)
      hits <- mem.recall("favorite color", 5)
    yield
      val completed: Boolean = res2 match
        case RunResult.Completed(output, _) =>
          output.contains("Relevant memory:") && output.contains("blue")
        case _ => false
      completed && hits.nonEmpty
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "Run 2 must recall the fact taught in Run 1 and echo it")
  }

  // ── Scenario: A2 — fact taught in Run 1 is recalled in Run 3 (fresh JVM) ──

  test("A2: fact taught in Run 1 is recalled in Run 3 (fresh FileBackedAgentMemory)") {
    // spec: cross-run-memory-example — Scenario: A2
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir  <- tempDir
      _    <- cleanDir(dir)
      mem1 <- FileBackedAgentMemory[IO](dir)
      em1  <- AgentEventEmitter.create()
      r1   <- buildRunner(mem1, policy, em1)
      _    <- r1.run(List(UserMessage("My favorite color is blue.")), maxSteps = 1)
      // Fresh instance — simulates a new JVM
      mem3 <- FileBackedAgentMemory[IO](dir)
      em3  <- AgentEventEmitter.create()
      r3   <- buildRunner(mem3, policy, em3)
      res3 <- r3.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)
      hits <- mem3.recall("favorite color", 5)
    yield
      val completed: Boolean = res3 match
        case RunResult.Completed(output, _) =>
          output.contains("Relevant memory:") && output.contains("blue")
        case _ => false
      completed && hits.nonEmpty
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "Run 3 (fresh instance) must recall the fact taught in Run 1")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Observability — MemoryRecalled and MemoryWritten events
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: MemoryWritten event fires on Completed ─────────────────────

  test("MemoryWritten event fires when a run completes") {
    // spec: cross-run-memory-example — Scenario: MemoryWritten event
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir           <- tempDir
      _             <- cleanDir(dir)
      mem           <- FileBackedAgentMemory[IO](dir)
      em            <- AgentEventEmitter.create()
      r             <- buildRunner(mem, policy, em)
      (res, events) <- runWithEvents(r, em, "Remember: the sky is green.")
    yield res match
      case _: RunResult.Completed =>
        events.exists {
          case _: AgentEvent.MemoryWritten => true
          case _                           => false
        }
      case _ => false
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "MemoryWritten event must fire when a run completes")
  }

  // ── Scenario: MemoryRecalled event fires on second run ───────────────────

  test("MemoryRecalled event fires with hitCount > 0 on second run") {
    // spec: cross-run-memory-example — Scenario: MemoryRecalled event
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir         <- tempDir
      _           <- cleanDir(dir)
      mem         <- FileBackedAgentMemory[IO](dir)
      em1         <- AgentEventEmitter.create()
      r1          <- buildRunner(mem, policy, em1)
      _           <- r1.run(List(UserMessage("My favorite color is blue.")), maxSteps = 1)
      em2         <- AgentEventEmitter.create()
      r2          <- buildRunner(mem, policy, em2)
      (_, events) <- runWithEvents(r2, em2, "What is my favorite color?")
    yield events.exists {
      case AgentEvent.MemoryRecalled(_, _, hitCount) => hitCount > 0
      case _                                         => false
    }
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "MemoryRecalled event must fire with hitCount > 0 on second run")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Adversarial mock model echoes recalled memory
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Mock model echoes "Relevant memory:" block ─────────────────

  test("adversarial mock model echoes the recalled memory block") {
    // spec: cross-run-memory-example — Scenario: Mock model echoes memory
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir  <- tempDir
      _    <- cleanDir(dir)
      mem  <- FileBackedAgentMemory[IO](dir)
      em1  <- AgentEventEmitter.create()
      r1   <- buildRunner(mem, policy, em1)
      _    <- r1.run(List(UserMessage("My favorite color is blue.")), maxSteps = 1)
      em2  <- AgentEventEmitter.create()
      r2   <- buildRunner(mem, policy, em2)
      res2 <- r2.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)
    yield res2 match
      case RunResult.Completed(output, _) =>
        output.startsWith("[echoed memory]")
      case _ => false
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "Mock model must echo the recalled memory block in its response")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Recall without prior teach — adversarial (no "blue")
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Recall without teach does not produce "blue" ───────────────

  test("recall without prior teach does not produce 'blue' in the response") {
    // spec: cross-run-memory-example — Scenario: Recall without prior teach
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir <- tempDir
      _   <- cleanDir(dir)
      mem <- FileBackedAgentMemory[IO](dir)
      em  <- AgentEventEmitter.create()
      r   <- buildRunner(mem, policy, em)
      res <- r.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)
    yield res match
      case RunResult.Completed(output, _) =>
        !output.toLowerCase.contains("blue")
      case _ => false
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "Recall without teach must NOT produce 'blue' — mock has no canned answers")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Mock does not echo when no context is injected (adversarial)
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Mock does not echo "blue" when no context injected ─────────

  test("mock does not echo 'blue' when no memory context is injected") {
    // spec: cross-run-memory-example — Scenario: Mock does not echo when no context injected
    // Empty storage → no hits → no "Relevant memory:" block → mock must not produce "blue"
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir <- tempDir
      _   <- cleanDir(dir)
      mem <- FileBackedAgentMemory[IO](dir)
      em  <- AgentEventEmitter.create()
      r   <- buildRunner(mem, policy, em)
      res <- r.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)
    yield res match
      case RunResult.Completed(output, _) =>
        !output.contains("[echoed memory]") && !output.toLowerCase.contains("blue")
      case _ => false
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "Mock must not echo 'blue' when no memory context is injected")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Requirement: Reset clears the storage directory
  // ════════════════════════════════════════════════════════════════════════

  // ── Scenario: Reset clears the storage directory ─────────────────────────

  test("reset clears the storage directory and subsequent recall returns empty") {
    // spec: cross-run-memory-example — Scenario: Reset clears storage
    val policy: MemoryPolicy = MemoryPolicy.default
    val result: IO[Boolean] = for
      dir <- tempDir
      _   <- cleanDir(dir)
      mem <- FileBackedAgentMemory[IO](dir)
      em  <- AgentEventEmitter.create()
      r   <- buildRunner(mem, policy, em)
      _   <- r.run(List(UserMessage("My favorite color is blue.")), maxSteps = 1)
      // Verify episodes exist
      hitsBefore <- mem.recall("favorite color", 5)
      // Reset: delete the episodes file
      _ <- IO.blocking {
        val file: Path = dir.resolve("episodes.jsonl")
        if Files.exists(file) then Files.delete(file)
      }
      // Fresh instance after reset
      mem2      <- FileBackedAgentMemory[IO](dir)
      hitsAfter <- mem2.recall("favorite color", 5)
    yield hitsBefore.nonEmpty && hitsAfter.isEmpty
    val passed: Boolean = result.unsafeRunSync()
    assert(passed, "Reset must clear storage and subsequent recall must return empty")
  }
