package org.adk4s.examples.record

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Ref
import fs2.io.file.Path
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.ChatModelConfig
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.adk4s.harness.testkit.DeterministicChatModel
import org.adk4s.orchestration.agent.ReactAgent
import org.adk4s.record.RecordedChatModel
import org.adk4s.record.Recorder
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.UserMessage

// ── RecordReplayExample — zero-provider-call replay against a warm file recorder
// spec: add-adk4s-record/record-replay-example — Requirement: Example runs same agent twice with zero-provider-call second run
// spec: add-adk4s-record/record-replay-example — Requirement: Multi-turn tool-calling conversation achieves full cache hit on replay
// spec: add-adk4s-record/record-replay-example — Requirement: Example runs without an API key
//
// Demonstrates the end-to-end value of the recording + caching pipeline:
// run an agent once against a deterministic model double wrapped by
// RecordedChatModel with a file-backed recorder, then run the same agent
// again and observe zero underlying model calls with an identical final
// AssistantMessage. The conversation is multi-turn with tool calls in
// turns 1 and 2, exercising tool-call id normalization (REC-4).
object RecordReplayExample extends IOApp.Simple:

  // ── Result types for testable assertions ─────────────────────────────

  /** Result of the zero-call replay scenario. */
  final case class ZeroCallResult(
    firstRunCalls: Int,
    secondRunCalls: Int,
    firstOutput: String,
    secondOutput: String
  )

  /** Result of the multi-turn replay scenario. */
  final case class MultiTurnResult(
    firstRunCalls: Int,
    secondRunCalls: Int,
    firstOutput: String,
    secondOutput: String
  )

  // ── Counting wrapper — tracks underlying model calls ─────────────────
  // Wraps a ChatModel[IO] with an atomic call counter so the test can
  // verify that the second run makes zero underlying calls.

  private final class CallCountingModel(
    underlying: ChatModel[IO],
    counter: Ref[IO, Int]
  ) extends ChatModel[IO]:
    def generate(conversation: Conversation): IO[Completion] =
      counter.update(_ + 1) *> underlying.generate(conversation)

    override def generate(
      conversation: Conversation,
      options: CompletionOptions
    ): IO[Completion] =
      counter.update(_ + 1) *> underlying.generate(conversation, options)

    def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] =
      underlying.stream(conversation)

    def streamContent(conversation: Conversation): fs2.Stream[IO, String] =
      underlying.streamContent(conversation)

    def withConfig(config: ChatModelConfig): ChatModel[IO] =
      new CallCountingModel(underlying.withConfig(config), counter)

  // ── Deterministic multi-turn script ──────────────────────────────────
  // A 3-turn conversation where the agent calls tools in turns 1 and 2,
  // then produces a final text response in turn 3. The call ids are
  // provider-generated (random-looking) to exercise REC-4 normalization.

  private val seed: Long = 42L

  private def buildScript: List[Completion] =
    // Turn 1: assistant requests a tool call (weather lookup)
    val turn1: Completion = Completion(
      id = s"det-$seed-0",
      created = 0L,
      content = "",
      model = "deterministic-test-model",
      message = AssistantMessage(
        None,
        Seq(ToolCall(id = "call_abc123", name = "get_weather", arguments = ujson.Obj("city" -> "Boston")))
      )
    )
    // Turn 2: assistant requests another tool call (restaurant lookup)
    val turn2: Completion = Completion(
      id = s"det-$seed-1",
      created = 0L,
      content = "",
      model = "deterministic-test-model",
      message = AssistantMessage(
        None,
        Seq(ToolCall(id = "call_def456", name = "get_restaurant", arguments = ujson.Obj("cuisine" -> "Italian")))
      )
    )
    // Turn 3: assistant produces final text (no tool calls)
    val turn3: Completion = Completion(
      id = s"det-$seed-2",
      created = 0L,
      content = "Based on the weather in Boston and the Italian restaurant I found, I recommend visiting Giuseppe's on a sunny day. Enjoy your meal!",
      model = "deterministic-test-model",
      message = AssistantMessage(Some("Based on the weather in Boston and the Italian restaurant I found, I recommend visiting Giuseppe's on a sunny day. Enjoy your meal!"))
    )
    List(turn1, turn2, turn3)

  // ── Tools for the multi-turn conversation ────────────────────────────

  private def buildTools: List[InvokableTool[IO]] =
    val weatherTool: InvokableTool[IO] = Tool.invokable[IO](
      "get_weather",
      "Get the current weather for a city.",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "city" -> ujson.Obj("type" -> "string", "description" -> "The city to get weather for")
        ),
        "required" -> ujson.Arr("city")
      ),
      (args: ujson.Value) =>
        val city: String = args("city").str
        Right(ujson.Obj("city" -> city, "temperature" -> 72, "condition" -> "sunny"))
    )
    val restaurantTool: InvokableTool[IO] = Tool.invokable[IO](
      "get_restaurant",
      "Get a restaurant recommendation for a given cuisine.",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "cuisine" -> ujson.Obj("type" -> "string", "description" -> "The cuisine type to search for")
        ),
        "required" -> ujson.Arr("cuisine")
      ),
      (args: ujson.Value) =>
        val cuisine: String = args("cuisine").str
        Right(ujson.Obj("name" -> "Giuseppe's", "cuisine" -> cuisine, "rating" -> 4.7))
    )
    List(weatherTool, restaurantTool)

  // ── Conversation input ───────────────────────────────────────────────
  // A single user message that triggers the multi-turn tool-calling flow.
  // The ReactAgent loop will call the model, execute tool calls, and
  // feed results back, producing 3 model calls (turns 1, 2, 3).

  private def conversationInput: List[Message] =
    List(UserMessage("I'm in Boston and want Italian food. What's the weather and a good restaurant?"))

  // ── Scenario 1: Zero-call replay (simple single-turn) ────────────────
  // A simpler scenario: single-turn conversation, run twice, verify
  // the second run makes zero underlying calls.

  def runZeroCallReplay: IO[ZeroCallResult] =
    val script: List[Completion] = List(
      Completion(
        id = s"det-$seed-simple",
        created = 0L,
        content = "Hello! I'm a deterministic response.",
        model = "deterministic-test-model",
        message = AssistantMessage(Some("Hello! I'm a deterministic response."))
      )
    )
    val messages: List[Message] = List(UserMessage("Hello"))
    val tempDir: IO[String] = IO.blocking(java.nio.file.Files.createTempDirectory("rr-zero").toString)
    tempDir.flatMap { dir =>
      val recorderPath: Path = Path(dir) / "records.jsonl"
      Recorder.file[IO](recorderPath).use { recorder =>
        for
          counter <- Ref.of[IO, Int](0)
          detModel <- DeterministicChatModel(seed, script)
          countingModel = new CallCountingModel(detModel, counter)
          recordedModel = RecordedChatModel[IO](countingModel, recorder)
          agent = ReactAgent.create(
            model = recordedModel,
            tools = Nil,
            systemPrompt = Some("You are a helpful assistant."),
            maxSteps = 5
          )
          // First run — populates the recorder
          firstOutput <- agent.generate(messages, maxSteps = 5).map(_.content)
          firstCalls <- counter.get
          // Reset counter for second run
          _ <- counter.set(0)
          // Second run — should hit cache, zero underlying calls
          secondOutput <- agent.generate(messages, maxSteps = 5).map(_.content)
          secondCalls <- counter.get
        yield ZeroCallResult(firstCalls, secondCalls, firstOutput, secondOutput)
      }
    }

  // ── Scenario 2: Multi-turn full cache hit on replay ──────────────────
  // A 3-turn conversation with tool calls in turns 1 and 2. Run twice,
  // verify the second run makes zero underlying calls (full cache hit).

  def runMultiTurnReplay: IO[MultiTurnResult] =
    val tempDir: IO[String] = IO.blocking(java.nio.file.Files.createTempDirectory("rr-multi").toString)
    tempDir.flatMap { dir =>
      val recorderPath: Path = Path(dir) / "records.jsonl"
      Recorder.file[IO](recorderPath).use { recorder =>
        for
          counter <- Ref.of[IO, Int](0)
          detModel <- DeterministicChatModel(seed, buildScript)
          countingModel = new CallCountingModel(detModel, counter)
          recordedModel = RecordedChatModel[IO](countingModel, recorder)
          agent = ReactAgent.create(
            model = recordedModel,
            tools = buildTools,
            systemPrompt = Some("You are a helpful assistant that uses tools to answer questions."),
            maxSteps = 10
          )
          // First run — populates the recorder (3 model calls, one per turn)
          firstMsg <- agent.generate(conversationInput, maxSteps = 10)
          firstOutput = firstMsg.content
          firstCalls <- counter.get
          // Reset counter for second run
          _ <- counter.set(0)
          // Second run — should hit cache on every turn, zero underlying calls
          secondMsg <- agent.generate(conversationInput, maxSteps = 10)
          secondOutput = secondMsg.content
          secondCalls <- counter.get
        yield MultiTurnResult(firstCalls, secondCalls, firstOutput, secondOutput)
      }
    }

  // ── Entry point — runs the example and prints results ────────────────

  def run: IO[Unit] =
    for
      _ <- IO.println("=== RecordReplay Example ===")
      _ <- IO.println("")
      _ <- IO.println("Scenario 1: Zero-call replay (single-turn)")
      zeroResult <- runZeroCallReplay
      _ <- IO.println(s"  First run:  ${zeroResult.firstRunCalls} underlying call(s), output: \"${zeroResult.firstOutput}\"")
      _ <- IO.println(s"  Second run: ${zeroResult.secondRunCalls} underlying call(s), output: \"${zeroResult.secondOutput}\"")
      _ <- IO.println(s"  Zero-call replay: ${if zeroResult.secondRunCalls == 0 then "PASS" else "FAIL"}")
      _ <- IO.println(s"  Output match: ${if zeroResult.firstOutput == zeroResult.secondOutput then "PASS" else "FAIL"}")
      _ <- IO.println("")
      _ <- IO.println("Scenario 2: Multi-turn full cache hit (3 turns, tool calls in turns 1 and 2)")
      multiResult <- runMultiTurnReplay
      _ <- IO.println(s"  First run:  ${multiResult.firstRunCalls} underlying call(s), output: \"${multiResult.firstOutput}\"")
      _ <- IO.println(s"  Second run: ${multiResult.secondRunCalls} underlying call(s), output: \"${multiResult.secondOutput}\"")
      _ <- IO.println(s"  Full cache hit: ${if multiResult.secondRunCalls == 0 then "PASS" else "FAIL"}")
      _ <- IO.println(s"  Output match: ${if multiResult.firstOutput == multiResult.secondOutput then "PASS" else "FAIL"}")
      _ <- IO.println("")
      _ <- IO.println("Scenario 3: Runs without API key")
      _ <- IO.println("  (Example completed using deterministic model double — no API key needed)")
      _ <- IO.println("")
      _ <- IO.println("=== RecordReplay Example Completed ===")
    yield ()
