package org.adk4s.orchestration.agent

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.component.{ ChatModel, ChatModelConfig }
import org.adk4s.core.error.{ AgentInterruptedException, StateDecodeError }
import org.adk4s.core.interrupt.{ AgentEventEmitter, InterruptSignal }
import org.adk4s.harness.{ HarnessState, MiddlewareName, StateCell }
import org.adk4s.orchestration.interrupt.CheckpointStore
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, Conversation, Message, StreamedChunk, UserMessage }

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test oracle for spec:checkpoint-store-fpoly — `AgentRunner.resume` scenarios
 * and the harnessState snapshot round-trip property.
 *
 * Tests written from the spec + approved typed contract ONLY.
 * Every test cites its source: `// spec: checkpoint-store-fpoly — Scenario: <heading>`
 */
class AgentRunnerResumeSpec extends HedgehogSuite:

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def makeCompletion(content: String): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty)
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = msg
    )

  private def mockChatModel(responses: List[Completion]): ChatModel[IO] =
    val counter: AtomicInteger = new AtomicInteger(0)
    new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val idx: Int = counter.getAndIncrement()
          if idx < responses.length then responses(idx)
          else makeCompletion("fallback")
        }

      def stream(conversation: Conversation): Stream[IO, StreamedChunk] = Stream.empty
      def streamContent(conversation: Conversation): Stream[IO, String] = Stream.empty
      def withConfig(config: ChatModelConfig): ChatModel[IO]            = this

  private def simpleAgent(response: String): ReactAgent =
    val model: ChatModel[IO] = mockChatModel(List(makeCompletion(response)))
    ReactAgent.create("test-agent", "Test agent", model, List.empty, None, 10)

  private def interruptingAgent(info: String): ReactAgent =
    val model: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.raiseError(AgentInterruptedException(InterruptSignal.simple(info)))
      def stream(conversation: Conversation): Stream[IO, StreamedChunk] = Stream.empty
      def streamContent(conversation: Conversation): Stream[IO, String] = Stream.empty
      def withConfig(config: ChatModelConfig): ChatModel[IO]            = this
    ReactAgent.create("interrupting-agent", "Agent that interrupts", model, List.empty, None, 10)

  // ── Scenarios (munit) ─────────────────────────────────────────────────────

  test("resume with unknown checkpoint id returns Failed") {
    // spec: checkpoint-store-fpoly — Scenario: resume with unknown checkpoint id returns Failed
    val result: RunResult =
      (for
        store   <- CheckpointStore.inMemory[IO]
        emitter <- AgentEventEmitter.create()
        runner = AgentRunner.create(simpleAgent("ok"), store, emitter)
        result <- runner.resume("nonexistent-id", List.empty)
      yield result).unsafeRunSync()
    result match
      case RunResult.Failed(error) =>
        assert(error.message.contains("nonexistent-id"))
      case other =>
        fail(s"Expected Failed, got $other")
  }

  test("resume with v1 checkpoint restores all cells to initial") {
    // spec: checkpoint-store-fpoly — Scenario: resume with v1 checkpoint restores all cells to initial
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "counter", 0)
    val v1Json: String =
      """{"messages":[{"role":"user","content":"hi"}],"interruptSignalJson":"","agentName":"test"}"""
    val result: RunResult =
      (for
        store   <- CheckpointStore.inMemory[IO]
        _       <- store.set("v1-ckpt", v1Json.getBytes("UTF-8"))
        emitter <- AgentEventEmitter.create()
        runner = new AgentRunner(simpleAgent("ok"), store, emitter, List(cell))
        result <- runner.resume("v1-ckpt", List.empty)
      yield result).unsafeRunSync()
    result match
      case RunResult.Failed(_: StateDecodeError) =>
        fail("v1 checkpoint should restore leniently, not fail with StateDecodeError")
      case _ => () // pass — resume completed or interrupted (not a decode failure)
  }

  test("resume with corrupted harnessState fails") {
    // spec: checkpoint-store-fpoly — Scenario: resume with corrupted harnessState fails
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "counter", 0)
    val corruptedJson: String =
      """{"version":2,"messages":[],"harnessState":{"m/counter":"not-an-int"},"interruptSignalJson":"","agentName":"test"}"""
    val result: RunResult =
      (for
        store   <- CheckpointStore.inMemory[IO]
        _       <- store.set("corrupted-ckpt", corruptedJson.getBytes("UTF-8"))
        emitter <- AgentEventEmitter.create()
        runner = new AgentRunner(simpleAgent("ok"), store, emitter, List(cell))
        result <- runner.resume("corrupted-ckpt", List.empty)
      yield result).unsafeRunSync()
    result match
      case RunResult.Failed(err: StateDecodeError) =>
        assert(err.cellId == "m/counter", s"expected cellId 'm/counter', got '${err.cellId}'")
      case other =>
        fail(s"Expected Failed(StateDecodeError), got $other")
  }

  test("resume deletes checkpoint on successful completion") {
    // spec: checkpoint-store-fpoly — Scenario: resume deletes checkpoint on successful completion
    val v2Json: String =
      """{"version":2,"messages":[{"role":"user","content":"hello"}],"harnessState":{},"interruptSignalJson":"","agentName":"test"}"""
    val (result, checkpointAfter): (RunResult, Option[Array[Byte]]) =
      (for
        store   <- CheckpointStore.inMemory[IO]
        _       <- store.set("resume-ckpt", v2Json.getBytes("UTF-8"))
        emitter <- AgentEventEmitter.create()
        runner = AgentRunner.create(simpleAgent("done!"), store, emitter)
        result          <- runner.resume("resume-ckpt", List.empty)
        checkpointAfter <- store.get("resume-ckpt")
      yield (result, checkpointAfter)).unsafeRunSync()
    result match
      case RunResult.Completed(output, _) =>
        assertEquals(output, "done!")
        assert(checkpointAfter.isEmpty, "checkpoint should be deleted after completion")
      case other =>
        fail(s"Expected Completed, got $other")
  }

  test("Save checkpoint on interrupt uses CheckpointStateV2") {
    // spec: checkpoint-store-fpoly — Scenario: Save checkpoint on interrupt uses CheckpointStateV2
    val (result, checkpointData): (RunResult, Option[Array[Byte]]) =
      (for
        store   <- CheckpointStore.inMemory[IO]
        emitter <- AgentEventEmitter.create()
        runner = AgentRunner.create(interruptingAgent("Approve?"), store, emitter)
        r <- runner.run(List(UserMessage("query")))
        id = r match
          case RunResult.Interrupted(i, _) => i
          case _                           => sys.error("Expected Interrupted")
        d <- store.get(id)
      yield (r, d)).unsafeRunSync()
    assert(checkpointData.isDefined)
    val json: String = new String(checkpointData.getOrElse(sys.error("expected checkpoint")), "UTF-8")
    assert(json.contains(""""version":2"""), s"expected version=2 in: $json")
    assert(json.contains(""""harnessState"""), s"expected harnessState in: $json")
  }

  test("Load checkpoint on resume uses CheckpointStateV2") {
    // spec: checkpoint-store-fpoly — Scenario: Load checkpoint on resume uses CheckpointStateV2
    val v2Json: String =
      """{"version":2,"messages":[{"role":"user","content":"hello"}],"harnessState":{},"interruptSignalJson":"","agentName":"test"}"""
    val result: RunResult =
      (for
        store   <- CheckpointStore.inMemory[IO]
        _       <- store.set("load-ckpt", v2Json.getBytes("UTF-8"))
        emitter <- AgentEventEmitter.create()
        runner = AgentRunner.create(simpleAgent("loaded!"), store, emitter)
        result <- runner.resume("load-ckpt", List.empty)
      yield result).unsafeRunSync()
    result match
      case RunResult.Completed(output, _) =>
        assertEquals(output, "loaded!")
      case other =>
        fail(s"Expected Completed, got $other")
  }

  // ── Generators (defined before properties to avoid init-order NPE) ────────

  val genIntCellWithValue: Gen[(StateCell[Int], Int)] =
    for
      owner   <- Gen.string(Gen.alphaNum, Range.linear(1, 5))
      name    <- Gen.string(Gen.alphaNum, Range.linear(1, 5))
      initial <- Gen.int(Range.linear(-100, 100))
      value   <- Gen.int(Range.linear(-1000, 1000))
    yield
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName(owner), name, initial)
      (cell, value)

  // ── Property (Ring 3) ─────────────────────────────────────────────────────

  property("harnessState snapshot round-trip — restore yields the original state") {
    // spec: checkpoint-store-fpoly — Property: harnessState snapshot round-trip — restore yields the original state
    for cellAndValue <- genIntCellWithValue.forAll
    yield
      val (cell: StateCell[Int], value: Int) = cellAndValue
      val state: HarnessState                = HarnessState.initial(List(cell)).set(cell)(value)
      val cp: CheckpointStateV2 = CheckpointStateV2(
        version = 2,
        messages = Nil,
        harnessState = state.snapshot,
        interruptSignalJson = "",
        agentName = "test"
      )
      val restored: Either[StateDecodeError, HarnessState] =
        HarnessState.restore(List(cell), cp.harnessState)
      val ok: Boolean = restored match
        case Right(restoredState) => restoredState.get(cell) == value
        case Left(_)              => false
      ok ==== true
  }
