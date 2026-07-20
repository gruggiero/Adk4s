package org.adk4s.examples.memory

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.ExitCode
import cats.syntax.foldable.toFoldableOps
import cats.syntax.traverse.toTraverseOps
import fs2.Stream
import org.adk4s.core.component.{ ChatModel, ChatModelConfig }
import org.adk4s.core.interrupt.{ AgentEvent, AgentEventEmitter }
import org.adk4s.memory.{ AgentMemory, MemoryHit }
import org.adk4s.orchestration.agent.{ AgentRunner, ReactAgent, RunResult }
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.adk4s.orchestration.memory.{ MemoryAwareRunner, MemoryPolicy }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, Conversation, StreamedChunk, UserMessage }

import java.nio.file.{ Files, Path }
import java.util.UUID

/**
 * Cross-run memory example.
 *
 * Demonstrates that `FileBackedAgentMemory` persists episodes across process
 * boundaries. Supports three CLI modes:
 *   - `teach`  — teaches a fact to memory (Run 1)
 *   - `recall` — recalls the fact in a fresh stack (Run 2)
 *   - `reset`  — clears the storage directory
 *   - (no arg) — runs all three in sequence as a self-contained demo
 *
 * The mock model is **adversarial**: it echoes whatever memory context was
 * injected into its prompt, proving the recall path works end-to-end. It
 * does NOT have canned answers — if no memory was recalled, it says so.
 *
 * Observability (recall mode): four labeled sections printed in order:
 *   1. Pre-turn recall — hit details (text, score, provenance) from direct recall
 *   2. Injected context — the rendered memory block
 *   3. Agent answer — the final assistant message
 *   4. Post-turn remember — episode count from MemoryWritten event
 */
object CrossRunMemoryExample extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    val mode: IO[Unit] = args.headOption match
      case Some("teach")  => runTeach()
      case Some("recall") => runRecall()
      case Some("reset")  => runReset()
      case _              => runDemo()
    mode.as(ExitCode.Success)

  /**
   * Adversarial mock model: echoes the "Relevant memory:" block verbatim
   * so we can prove recall happened. If no memory block is present, it
   * responds with a generic ack — NOT a canned answer.
   */
  final class MemoryEchoMockModel extends ChatModel[IO]:
    def generate(conversation: Conversation): IO[Completion] =
      IO.delay {
        val fullPrompt: String =
          conversation.messages.map(_.content).mkString("\n")
        val response: String =
          if fullPrompt.contains("Relevant memory:") then
            val memBlock: String =
              fullPrompt.substring(fullPrompt.indexOf("Relevant memory:"))
            s"[echoed memory] $memBlock"
          else
            val lastUser: String =
              conversation.messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")
            s"[no memory] I received: $lastUser"
        Completion(
          id = UUID.randomUUID().toString,
          created = System.currentTimeMillis(),
          content = response,
          model = "memory-echo-mock",
          message = AssistantMessage(response)
        )
      }

    def stream(conversation: Conversation): Stream[IO, StreamedChunk] =
      Stream.eval(generate(conversation)).flatMap { (c: Completion) =>
        Stream.emit(
          StreamedChunk(
            id = c.id,
            content = Some(c.content),
            toolCall = None,
            finishReason = Some("stop"),
            thinkingDelta = None
          )
        )
      }

    def streamContent(conversation: Conversation): Stream[IO, String] =
      stream(conversation).evalMap((chunk: StreamedChunk) => IO.pure(chunk.content.getOrElse(""))).filter(_.nonEmpty)

    def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  // ── Example configuration ────────────────────────────────────────────────

  private val dataDir: Path =
    Path.of(System.getProperty("java.io.tmpdir"), "adk4s-cross-run-memory-demo")

  private val systemPrompt: String =
    "You are a helpful assistant with cross-run memory."

  // ── Modes ────────────────────────────────────────────────────────────────

  /** Teach mode: teaches a fact to memory. */
  private def runTeach(): IO[Unit] =
    for
      _      <- IO.println("═" * 60)
      _      <- IO.println("  Cross-Run Memory Example — TEACH")
      _      <- IO.println(s"  Data dir: $dataDir")
      _      <- IO.println("═" * 60)
      memory <- FileBackedAgentMemory[IO](dataDir)
      policy = MemoryPolicy.default
      emitter <- AgentEventEmitter.create()
      runner  <- buildRunner(memory, policy, emitter)
      result  <- runner.run(List(UserMessage("My favorite color is blue, and I work at Acme Corp.")), maxSteps = 1)
      _       <- printResult("Teach", result)
      _       <- IO.println("\nTeach complete. Run 'recall' to verify cross-run persistence.")
    yield ()

  /**
   * Recall mode: fresh stack, same data dir. Prints four observability sections.
   *
   * Uses `run` (not `runWithEvents`) because `run` injects the recalled
   * context into the messages — `runWithEvents` does not (documented
   * limitation of `MemoryAwareRunner`). The hit count comes from the direct
   * `recall` call; the write count comes from a before/after episode count.
   */
  private def runRecall(): IO[Unit] =
    for
      _      <- IO.println("═" * 60)
      _      <- IO.println("  Cross-Run Memory Example — RECALL")
      _      <- IO.println(s"  Data dir: $dataDir")
      _      <- IO.println("═" * 60)
      memory <- FileBackedAgentMemory[IO](dataDir)
      policy = MemoryPolicy.default
      emitter <- AgentEventEmitter.create()
      runner  <- buildRunner(memory, policy, emitter)

      // Pre-turn recall: direct recall for full hit details (text, score, provenance)
      hits <- memory.recall("favorite color", policy.recallK)
      _    <- printSection("Pre-turn recall", printHits(hits))

      // Count episodes before the run (for write-count delta)
      countBefore <- memory.recall("", 1000).map(_.length)

      // Run with context injection (run() injects the rendered recall block)
      result <- runner.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)

      // Injected context: render the hits (what was prepended to the messages)
      injectedBlock = if hits.isEmpty then "" else policy.render(hits)
      _ <- printSection("Injected context", IO.println(injectedBlock))

      // Agent answer
      _ <- printSection("Agent answer", printResult("Recall", result))

      // Post-turn remember: episode count from before/after delta
      countAfter <- memory.recall("", 1000).map(_.length)
      writeCount = countAfter - countBefore
      _ <- printSection("Post-turn remember", IO.println(s"  Episodes written: $writeCount"))

      _ <- IO.println("\n═" * 30)
      _ <- IO.println("  Recall complete.")
      _ <- IO.println("═" * 30)
    yield ()

  /** Reset mode: clears the storage directory. */
  private def runReset(): IO[Unit] =
    for
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Cross-Run Memory Example — RESET")
      _ <- IO.println(s"  Data dir: $dataDir")
      _ <- IO.println("═" * 60)
      _ <- IO.blocking {
        val file: Path = dataDir.resolve("episodes.jsonl")
        if Files.exists(file) then Files.delete(file)
      }
      _ <- IO.println("  Storage cleared.")
    yield ()

  /** Demo mode: runs teach → recall → fresh-JVM recall in one invocation. */
  private def runDemo(): IO[Unit] =
    for
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Cross-Run Memory Example (full demo)")
      _ <- IO.println("═" * 60)
      _ <- IO.println(s"  Data dir: $dataDir")
      _ <- IO.println("")

      // Clean slate for a reproducible demo
      _ <- runReset()

      // ── Run 1: Teach a fact ────────────────────────────────────────────
      _      <- IO.println("─" * 60)
      _      <- IO.println("  Run 1: Teach a fact")
      _      <- IO.println("─" * 60)
      memory <- FileBackedAgentMemory[IO](dataDir)
      policy = MemoryPolicy.default
      emitter <- AgentEventEmitter.create()
      runner1 <- buildRunner(memory, policy, emitter)
      result1 <- runner1.run(List(UserMessage("My favorite color is blue.")), maxSteps = 1)
      _       <- printResult("Run 1", result1)
      hits1   <- memory.recall("favorite color", 5)
      _       <- printHitsLabel("Run 1 — direct recall", hits1)

      // ── Run 2: Fresh runner, same data dir ─────────────────────────────
      _        <- IO.println("")
      _        <- IO.println("─" * 60)
      _        <- IO.println("  Run 2: Fresh runner, same data dir")
      _        <- IO.println("─" * 60)
      emitter2 <- AgentEventEmitter.create()
      runner2  <- buildRunner(memory, policy, emitter2)
      result2  <- runner2.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)
      _        <- printResult("Run 2", result2)
      hits2    <- memory.recall("favorite color", 5)
      _        <- printHitsLabel("Run 2 — direct recall", hits2)

      // ── Run 3: Fresh JVM (new FileBackedAgentMemory instance) ──────────
      _        <- IO.println("")
      _        <- IO.println("─" * 60)
      _        <- IO.println("  Run 3: Fresh JVM (new FileBackedAgentMemory instance)")
      _        <- IO.println("─" * 60)
      memory3  <- FileBackedAgentMemory[IO](dataDir)
      emitter3 <- AgentEventEmitter.create()
      runner3  <- buildRunner(memory3, policy, emitter3)
      result3  <- runner3.run(List(UserMessage("What is my favorite color?")), maxSteps = 1)
      _        <- printResult("Run 3", result3)
      hits3    <- memory3.recall("favorite color", 5)
      _        <- printHitsLabel("Run 3 — direct recall (fresh instance)", hits3)

      _ <- IO.println("")
      _ <- IO.println("═" * 60)
      _ <- IO.println("  Cross-run memory example completed.")
      _ <- IO.println("═" * 60)
    yield ()

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def buildRunner(
    memory: AgentMemory[IO],
    policy: MemoryPolicy,
    emitter: AgentEventEmitter
  ): IO[MemoryAwareRunner] =
    for
      checkpointStore <- InMemoryCheckpointStore.create
      agent = ReactAgent.create(
        name = "memory-demo-agent",
        description = "Cross-run memory demo agent",
        model = new MemoryEchoMockModel(),
        tools = Nil,
        systemPrompt = Some(systemPrompt),
        maxSteps = 1,
        emitter = emitter
      )
      baseRunner = AgentRunner.create(agent, checkpointStore, emitter)
    yield MemoryAwareRunner(
      baseRunner,
      Some(memory),
      policy,
      Some(emitter),
      Some("memory-demo-agent")
    )

  /** Run with events and collect all events from the stream. */
  private def runWithEventsAndCollect(
    runner: MemoryAwareRunner,
    emitter: AgentEventEmitter,
    userMessage: String
  ): IO[(RunResult, List[AgentEvent])] =
    val (ioResult: IO[RunResult], stream: Stream[IO, AgentEvent]) =
      runner.runWithEvents(List(UserMessage(userMessage)), maxSteps = 1)
    for
      eventsRef <- cats.effect.kernel.Ref.of[IO, List[AgentEvent]](Nil)
      fib       <- stream.evalTap((e: AgentEvent) => eventsRef.update(_ :+ e)).compile.drain.start
      result    <- ioResult
      _         <- emitter.complete
      _         <- fib.join
      events    <- eventsRef.get
    yield (result, events)

  private def printSection(label: String, body: IO[Unit]): IO[Unit] =
    IO.println(s"── $label ──") *> body *> IO.println("")

  private def printResult(label: String, result: RunResult): IO[Unit] =
    result match
      case RunResult.Completed(output, _) =>
        IO.println(s"  [$label] Agent: $output")
      case RunResult.Interrupted(checkpointId, _) =>
        IO.println(s"  [$label] Interrupted (checkpoint: $checkpointId)")
      case RunResult.Failed(error) =>
        IO.println(s"  [$label] Failed: ${error.message}")

  private def printHits(hits: List[MemoryHit]): IO[Unit] =
    if hits.isEmpty then IO.println("  0 hits.")
    else
      IO.println(s"  ${hits.length} hit(s):") *>
        hits.traverse_((h: MemoryHit) =>
          IO.println(s"    score=${h.score} provenance=${h.provenance.getOrElse("N/A")} text=\"${h.text}\"")
        )

  private def printHitsLabel(label: String, hits: List[MemoryHit]): IO[Unit] =
    if hits.isEmpty then IO.println(s"  [$label] No hits.")
    else
      IO.println(s"  [$label] ${hits.length} hit(s):") *>
        hits.traverse_((h: MemoryHit) => IO.println(s"    score=${h.score} text=\"${h.text}\""))
