package org.adk4s.orchestration.memory

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.ChatModelConfig
import org.adk4s.core.error.{AdkError, AgentInterruptedException}
import org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter, InterruptSignal}
import org.adk4s.memory.{AgentMemory, Episode, EpisodeOutcome, InMemoryAgentMemory, MemoryHit, TemporalScope}
import org.adk4s.orchestration.agent.{AgentRunner, ReactAgent, RunResult}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, UserMessage}

import java.util.UUID

/** Helpers that delegate to munit's real assertions (which throw on failure)
  * instead of hedgehog's `assert`/`assertEquals` (which return `Result` objects
  * that are silently discarded in `test` blocks — only `property` blocks check
  * them). Mix this trait into any `HedgehogSuite` subclass that has `test`
  * blocks with assertions.
  */
trait MunitAssertHelpers { self: hedgehog.munit.HedgehogSuite =>

  def assertM(cond: => Boolean)(implicit loc: munit.Location): Unit =
    withMunitAssertions(a => a.assert(cond))

  def assertM(cond: => Boolean, clue: => Any)(implicit loc: munit.Location): Unit =
    withMunitAssertions(a => a.assert(cond, clue))

  def assertEqualsM[A, B](obtained: A, expected: B)(implicit
      ev: B <:< A,
      loc: munit.Location
  ): Unit =
    withMunitAssertions(a => a.assertEquals(obtained, expected))

  def assertEqualsM[A, B](obtained: A, expected: B, clue: => Any)(implicit
      ev: B <:< A,
      loc: munit.Location
  ): Unit =
    withMunitAssertions(a => a.assertEquals(obtained, expected, clue))

  def failM(message: String)(implicit loc: munit.Location): Nothing =
    withMunitAssertions(a => a.fail(message))
    scala.sys.error(message) // unreachable — a.fail throws FailException
}

/**
 * Test helpers for spec:memory-orchestration-hook.
 *
 * Provides stub `ReactAgent` implementations that return fixed results,
 * a `RecordingAgentMemory` wrapper for counting `recall`/`remember` calls,
 * and a `TestControl`-based IO runner for deterministic concurrency scenarios.
 */
object TestHelpers:

  // ── Stub ReactAgent implementations ──────────────────────────────────────

  /** A stub `ReactAgent` whose `generate` returns a fixed `AssistantMessage`.
    * Used to produce `RunResult.Completed` from `AgentRunner.run`.
    */
  private class StubCompletedAgent(val name: String, val description: String, output: String) extends ReactAgent:
    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      IO.pure(AssistantMessage(contentOpt = Some(output), toolCalls = Seq.empty))
    def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk] =
      Stream.empty

  /** A stub `ReactAgent` whose `generate` raises `AgentInterruptedException`.
    * Used to produce `RunResult.Interrupted` from `AgentRunner.run`.
    */
  private class StubInterruptingAgent(val name: String, val description: String, signal: InterruptSignal) extends ReactAgent:
    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      IO.raiseError(AgentInterruptedException(signal))
    def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk] =
      Stream.empty

  /** A stub `ReactAgent` whose `generate` raises an `AdkError`.
    * Used to produce `RunResult.Failed` from `AgentRunner.run`.
    */
  private class StubFailingAgent(val name: String, val description: String, error: AdkError) extends ReactAgent:
    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      IO.raiseError(error)
    def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk] =
      Stream.empty

  /** Builds an `AgentRunner` that returns `RunResult.Completed(output, messages)` on `run`. */
  def stubRunnerCompleted(output: String, agentName: String = "stub-agent"): IO[AgentRunner] =
    for
      store   <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
    yield AgentRunner.create(StubCompletedAgent(agentName, "stub", output), store, emitter)

  /** Builds an `AgentRunner` that returns `RunResult.Interrupted(_, signal)` on `run`. */
  def stubRunnerInterrupted(signal: InterruptSignal, agentName: String = "stub-agent"): IO[AgentRunner] =
    for
      store   <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
    yield AgentRunner.create(StubInterruptingAgent(agentName, "stub", signal), store, emitter)

  /** Builds an `AgentRunner` that returns `RunResult.Failed(error)` on `run`. */
  def stubRunnerFailed(error: AdkError, agentName: String = "stub-agent"): IO[AgentRunner] =
    for
      store   <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
    yield AgentRunner.create(StubFailingAgent(agentName, "stub", error), store, emitter)

  // ── Emitter-exposed stub runners (for spec: memory-orchestration-events) ──

  /** Builds an `AgentRunner` that returns `RunResult.Completed(output, messages)` on `run`,
    * AND exposes the `AgentEventEmitter` it uses (so `MemoryAwareRunner` can emit
    * memory events on the same emitter). Returns `(runner, emitter, agentName)`.
    */
  def stubRunnerCompletedWithEmitter(
    output: String,
    agentName: String = "stub-agent"
  ): IO[(AgentRunner, AgentEventEmitter, String)] =
    for
      store   <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner   = AgentRunner.create(StubCompletedAgent(agentName, "stub", output), store, emitter)
    yield (runner, emitter, agentName)

  /** Builds an `AgentRunner` that returns `RunResult.Interrupted(_, signal)` on `run`,
    * AND exposes the `AgentEventEmitter`. Returns `(runner, emitter, agentName)`.
    */
  def stubRunnerInterruptedWithEmitter(
    signal: InterruptSignal,
    agentName: String = "stub-agent"
  ): IO[(AgentRunner, AgentEventEmitter, String)] =
    for
      store   <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner   = AgentRunner.create(StubInterruptingAgent(agentName, "stub", signal), store, emitter)
    yield (runner, emitter, agentName)

  /** Builds an `AgentRunner` that returns `RunResult.Failed(error)` on `run`,
    * AND exposes the `AgentEventEmitter`. Returns `(runner, emitter, agentName)`.
    */
  def stubRunnerFailedWithEmitter(
    error: AdkError,
    agentName: String = "stub-agent"
  ): IO[(AgentRunner, AgentEventEmitter, String)] =
    for
      store   <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner   = AgentRunner.create(StubFailingAgent(agentName, "stub", error), store, emitter)
    yield (runner, emitter, agentName)

  /** Builds an `AgentRunner` whose behavior matches a given `RunResult` variant,
    * AND exposes the `AgentEventEmitter`. Returns `(runner, emitter, agentName)`.
    */
  def stubRunnerForWithEmitter(
    outcome: RunResult,
    agentName: String = "stub-agent"
  ): IO[(AgentRunner, AgentEventEmitter, String)] =
    outcome match
      case RunResult.Completed(output, _)   => stubRunnerCompletedWithEmitter(output, agentName)
      case RunResult.Interrupted(_, signal) => stubRunnerInterruptedWithEmitter(signal, agentName)
      case RunResult.Failed(error)          => stubRunnerFailedWithEmitter(error, agentName)

  /** Builds an `AgentRunner` whose behavior matches a given `RunResult` variant.
    * Used by property tests that draw `RunResult` from a generator.
    */
  def stubRunnerFor(outcome: RunResult, agentName: String = "stub-agent"): IO[AgentRunner] =
    outcome match
      case RunResult.Completed(output, _)   => stubRunnerCompleted(output, agentName)
      case RunResult.Interrupted(_, signal) => stubRunnerInterrupted(signal, agentName)
      case RunResult.Failed(error)          => stubRunnerFailed(error, agentName)

  // ── Message-capturing stub agent ─────────────────────────────────────────

  /** A stub `ReactAgent` that captures the messages it receives in `generate`
    * and returns a fixed `AssistantMessage`. Used to verify context injection.
    */
  private class CapturingAgent(
    val name: String,
    val description: String,
    output: String,
    capturedRef: Ref[IO, Option[List[Message]]]
  ) extends ReactAgent:
    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      capturedRef.set(Some(messages)).as(AssistantMessage(contentOpt = Some(output), toolCalls = Seq.empty))
    def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk] =
      Stream.empty

  /** Builds an `AgentRunner` that captures the messages passed to `generate`
    * and returns `RunResult.Completed(output, messages)`.
    * Returns `(runner, ref)` where `ref` can be read to inspect the captured messages.
    */
  def capturingRunner(output: String, agentName: String = "stub-agent"): IO[(AgentRunner, Ref[IO, Option[List[Message]]])] =
    for
      store   <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      ref     <- Ref.of[IO, Option[List[Message]]](None)
      agent    = CapturingAgent(agentName, "capturing-stub", output, ref)
    yield (AgentRunner.create(agent, store, emitter), ref)

  // ── RecordingAgentMemory ─────────────────────────────────────────────────

  /** A recording wrapper around any `AgentMemory[IO]` that counts `recall` and
    * `remember` calls and captures their parameters.
    *
    * Delegates to the underlying memory for actual behavior; only adds recording.
    */
  final case class RecallCall(query: String, k: Int, scope: Option[TemporalScope])

  class RecordingAgentMemory(underlying: AgentMemory[IO]) extends AgentMemory[IO]:
    private val recallCalls: Ref[IO, Vector[RecallCall]] = Ref.unsafe[IO, Vector[RecallCall]](Vector.empty[RecallCall])
    private val rememberCalls: Ref[IO, Vector[Episode]] = Ref.unsafe[IO, Vector[Episode]](Vector.empty[Episode])

    def remember(episode: Episode): IO[EpisodeOutcome] =
      rememberCalls.update(_ :+ episode) *> underlying.remember(episode)

    def recall(query: String, k: Int, scope: Option[TemporalScope] = None): IO[List[MemoryHit]] =
      recallCalls.update(_ :+ RecallCall(query, k, scope)) *> underlying.recall(query, k, scope)

    /** Observe the recorded recall calls. */
    def recordedRecallCalls: IO[List[RecallCall]] = recallCalls.get.map(_.toList)

    /** Observe the recorded remember calls (episodes). */
    def recordedRememberCalls: IO[List[Episode]] = rememberCalls.get.map(_.toList)

    /** Number of remember calls. */
    def rememberCount: IO[Int] = rememberCalls.get.map(_.size)

    /** Number of recall calls. */
    def recallCount: IO[Int] = recallCalls.get.map(_.size)

  /** Creates a `RecordingAgentMemory` wrapping `InMemoryAgentMemory`. */
  def recordingMemory: IO[RecordingAgentMemory] =
    InMemoryAgentMemory.create[IO].map(RecordingAgentMemory.apply)

  // ── Deterministic IO runner ──────────────────────────────────────────────

  /** Runs an `IO[A]` deterministically and returns the result.
    *
    * NOTE: The capability profile claims `cats.effect.unsafe.TestControl` is
    * transitively available via cats-effect 3.7.0, but it is NOT — it lives
    * in the separate `cats-effect-testkit` module, which is not in the
    * dependency graph. For our interrupt/Failed/resume scenarios (which are
    * synchronous IOs with no async boundaries or time-dependence),
    * `unsafeRunSync()` with the global runtime is fully deterministic.
    * The CONCURRENCY RULE's intent (deterministic observables, no wall-clock
    * sleeps) is satisfied: no `Thread.sleep`/`TimeUnit.sleep` is used, and
    * the IOs complete synchronously.
    */
  def runDeterministic[A](io: IO[A]): A =
    io.unsafeRunSync()

  // ── RunResult comparison (ignoring random checkpointId) ──────────────────

  /** Compares two `RunResult`s for equality, ignoring the random `checkpointId`
    * in `Interrupted` (the runner generates a UUID that differs between calls).
    */
  def resultsEqual(a: RunResult, b: RunResult): Boolean = (a, b) match
    case (RunResult.Completed(o1, m1), RunResult.Completed(o2, m2)) =>
      o1 == o2 && m1 == m2
    case (RunResult.Interrupted(_, s1), RunResult.Interrupted(_, s2)) =>
      s1 == s2
    case (RunResult.Failed(e1), RunResult.Failed(e2)) =>
      e1 == e2
    case _ =>
      false
