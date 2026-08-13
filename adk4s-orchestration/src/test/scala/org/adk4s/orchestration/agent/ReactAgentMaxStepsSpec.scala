package org.adk4s.orchestration.agent

// spec: add-iron-refined-types/react-agent — Test oracle (Step 2)
// Scenario tests and Hedgehog properties for maxSteps refinement.

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.CatsEffectSuite
import org.adk4s.core.component.{ ChatModel, ChatModelConfig }
import org.adk4s.core.error.ConfigError
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  Conversation,
  Message,
  StreamedChunk,
  UserMessage
}

import java.util.UUID

object ReactAgentMaxStepsSpec:
  // ── Shared test helpers ──────────────────────────────────────────────────

  def makeCompletion(content: String): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty)
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = msg
    )

  def mockChatModel(response: String): ChatModel[IO] = new ChatModel[IO]:
    def generate(conversation: Conversation): IO[Completion] =
      IO.pure(makeCompletion(response))
    def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] =
      fs2.Stream.empty
    def streamContent(conversation: Conversation): fs2.Stream[IO, String] =
      fs2.Stream.empty
    def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  def makeAgent: ReactAgent =
    ReactAgent.create(
      name = "test-agent",
      description = "Test agent",
      model = mockChatModel("Done"),
      tools = Nil,
      systemPrompt = Some("You are a test agent"),
      maxSteps = 10
    )

  def makeMessages: List[Message] = List(UserMessage("Hello"))

class ReactAgentMaxStepsSpec extends CatsEffectSuite:
  import ReactAgentMaxStepsSpec.*

  // ── Scenario tests ───────────────────────────────────────────────────────

  test("Zero maxSteps is rejected with ConfigError"):
    // spec: add-iron-refined-types/react-agent — Scenario: Zero maxSteps is rejected
    val agent: ReactAgent = makeAgent
    agent.generate(makeMessages, 0).attempt.assertEquals(Left(
      ConfigError("maxSteps", "0", "Positive")
    ))

  test("Negative maxSteps is rejected with ConfigError"):
    // spec: add-iron-refined-types/react-agent — Scenario: Negative maxSteps is rejected
    val agent: ReactAgent = makeAgent
    agent.generate(makeMessages, -3).attempt.map {
      case Left(_: ConfigError) => true
      case _ => false
    }.assert

  test("Positive maxSteps enters the loop and completes"):
    // spec: add-iron-refined-types/react-agent — Scenario: Positive maxSteps enters the loop
    val agent: ReactAgent = makeAgent
    agent.generate(makeMessages, 5).map(_.content).assertEquals("Done")

  test("Default maxSteps 10 remains valid"):
    // spec: add-iron-refined-types/react-agent — Scenario: Default maxSteps 10 remains valid
    val agent: ReactAgent = makeAgent
    agent.generate(makeMessages, 10).map(_.content).assertEquals("Done")

class ReactAgentMaxStepsProps extends HedgehogSuite:
  import ReactAgentMaxStepsSpec.*

  // ── Properties (Ring 3) ──────────────────────────────────────────────────

  property("maxSteps rejects zero and negatives"):
    // spec: add-iron-refined-types/react-agent — Property: maxSteps rejects zero and negatives
    val gen: Gen[Int] = Gen.int(Range.linear(-10, 0))
    for n <- gen.forAll
      yield
        val agent: ReactAgent = makeAgent
        val messages: List[Message] = List(UserMessage("Hello"))
        val result: Either[Throwable, AssistantMessage] =
          agent.generate(messages, n).attempt.unsafeRunSync()
        result.isLeft ==== true

  property("maxSteps preserves valid-input behavior"):
    // spec: add-iron-refined-types/react-agent — Property: maxSteps refinement preserves valid-input behavior
    val gen: Gen[Int] = Gen.int(Range.linear(1, 20))
    for n <- gen.forAll
      yield
        val agent: ReactAgent = makeAgent
        val messages: List[Message] = List(UserMessage("Hello"))
        val result: Either[Throwable, String] =
          agent.generate(messages, n).map(_.content).attempt.unsafeRunSync()
        result ==== Right("Done")
