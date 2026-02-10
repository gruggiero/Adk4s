package org.adk4s.orchestration.agent

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.ChatModelConfig
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.AdkToolInfo
import org.adk4s.core.component.Tool
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.toolapi.ToolFunction

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ReactAgentTest extends CatsEffectSuite:

  // --- Test helpers ---

  private def makeCompletion(content: String, toolCalls: Seq[ToolCall] = Seq.empty): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = toolCalls)
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = msg
    )

  private def makeToolCallCompletion(toolName: String, args: String): Completion =
    val callId: String = UUID.randomUUID().toString
    val tc: ToolCall = ToolCall(id = callId, name = toolName, arguments = ujson.read(args))
    val msg: AssistantMessage = AssistantMessage(contentOpt = None, toolCalls = Seq(tc))
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = "",
      model = "test-model",
      message = msg
    )

  /** A mock ChatModel that returns responses from a sequence. */
  private def mockChatModel(responses: List[Completion]): ChatModel[IO] =
    val counter: AtomicInteger = new AtomicInteger(0)
    new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val idx: Int = counter.getAndIncrement()
          if idx < responses.length then responses(idx)
          else makeCompletion("fallback response")
        }

      def stream(conversation: Conversation): Stream[IO, StreamedChunk] =
        Stream.eval(generate(conversation)).flatMap { (completion: Completion) =>
          val words: List[String] = completion.content.split(" ").toList
          Stream.emits(words.map { (word: String) =>
            StreamedChunk(
              id = completion.id,
              content = Some(word + " "),
              toolCall = None,
              finishReason = None,
              thinkingDelta = None
            )
          })
        }

      def streamContent(conversation: Conversation): Stream[IO, String] =
        stream(conversation).map(_.content.getOrElse("")).filter(_.nonEmpty)

      def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  private val echoTool: InvokableTool[IO] = Tool.invokable[IO](
    "echo",
    "Echoes the input",
    (args: ujson.Value) => Right(ujson.Str(s"echo: ${args.toString}"))
  )

  private val addTool: InvokableTool[IO] = Tool.invokable[IO](
    "add",
    "Adds two numbers",
    (args: ujson.Value) => {
      val a: Int = args("a").num.toInt
      val b: Int = args("b").num.toInt
      Right(ujson.Num(a + b))
    }
  )

  // --- Tests ---

  test("generate returns direct response when no tool calls") {
    val model: ChatModel[IO] = mockChatModel(List(makeCompletion("Hello!")))
    val agent: ReactAgent = ReactAgent.create(model, List.empty)
    val result: IO[AssistantMessage] = agent.generate(List(UserMessage("Hi")), 5)
    result.map { (msg: AssistantMessage) =>
      assertEquals(msg.content, "Hello!")
      assert(msg.toolCalls.isEmpty)
    }
  }

  test("generate executes tool calls and loops") {
    val responses: List[Completion] = List(
      makeToolCallCompletion("echo", """{"text": "hello"}"""),
      makeCompletion("The echo result was received.")
    )
    val model: ChatModel[IO] = mockChatModel(responses)
    val agent: ReactAgent = ReactAgent.create(model, List(echoTool))
    val result: IO[AssistantMessage] = agent.generate(List(UserMessage("echo hello")), 5)
    result.map { (msg: AssistantMessage) =>
      assertEquals(msg.content, "The echo result was received.")
    }
  }

  test("generate handles multiple tool call rounds") {
    val responses: List[Completion] = List(
      makeToolCallCompletion("echo", """{"text": "first"}"""),
      makeToolCallCompletion("echo", """{"text": "second"}"""),
      makeCompletion("Done after two tool rounds.")
    )
    val model: ChatModel[IO] = mockChatModel(responses)
    val agent: ReactAgent = ReactAgent.create(model, List(echoTool))
    val result: IO[AssistantMessage] = agent.generate(List(UserMessage("do two rounds")), 5)
    result.map { (msg: AssistantMessage) =>
      assertEquals(msg.content, "Done after two tool rounds.")
    }
  }

  test("generate raises error when max steps exceeded") {
    val responses: List[Completion] = List(
      makeToolCallCompletion("echo", """{"text": "loop"}"""),
      makeToolCallCompletion("echo", """{"text": "loop"}"""),
      makeToolCallCompletion("echo", """{"text": "loop"}""")
    )
    val model: ChatModel[IO] = mockChatModel(responses)
    val agent: ReactAgent = ReactAgent.create(model, List(echoTool), maxSteps = 2)
    val result: IO[AssistantMessage] = agent.generate(List(UserMessage("loop")), 2)
    interceptIO[RuntimeException](result).map { (e: RuntimeException) =>
      assert(e.getMessage.contains("max steps exceeded"))
    }
  }

  test("generate prepends system prompt") {
    val capturedConversations: java.util.concurrent.ConcurrentLinkedQueue[Conversation] =
      new java.util.concurrent.ConcurrentLinkedQueue[Conversation]()
    val model: ChatModel[IO] =
      val inner: ChatModel[IO] = mockChatModel(List(makeCompletion("response")))
      new ChatModel[IO]:
        def generate(conversation: Conversation): IO[Completion] =
          IO.delay(capturedConversations.add(conversation)) *> inner.generate(conversation)
        def stream(conversation: Conversation): Stream[IO, StreamedChunk] = inner.stream(conversation)
        def streamContent(conversation: Conversation): Stream[IO, String] = inner.streamContent(conversation)
        def withConfig(config: ChatModelConfig): ChatModel[IO] = this

    val agent: ReactAgent = ReactAgent.create(model, List.empty, systemPrompt = Some("You are helpful."))
    agent.generate(List(UserMessage("Hi")), 5).map { (_: AssistantMessage) =>
      val captured: Conversation = capturedConversations.poll()
      assert(captured != null)
      val firstMsg: Message = captured.messages.head
      firstMsg match
        case sm: org.llm4s.llmconnect.model.SystemMessage =>
          assertEquals(sm.content, "You are helpful.")
        case other =>
          fail(s"Expected SystemMessage, got $other")
    }
  }

  test("stream returns chunks for direct response") {
    // resolveToolLoops calls generate once (no tool calls → returns conversation),
    // then model.stream calls generate again for the actual streaming
    val model: ChatModel[IO] = mockChatModel(List(
      makeCompletion("Hello world"),
      makeCompletion("Hello world")
    ))
    val agent: ReactAgent = ReactAgent.create(model, List.empty)
    val result: IO[List[StreamedChunk]] =
      agent.stream(List(UserMessage("Hi")), 5).compile.toList
    result.map { (chunks: List[StreamedChunk]) =>
      assert(chunks.nonEmpty)
      val content: String = chunks.flatMap(_.content).mkString
      assert(content.contains("Hello"))
    }
  }

  test("stream resolves tool loops then streams final response") {
    val responses: List[Completion] = List(
      makeToolCallCompletion("echo", """{"text": "hello"}"""),
      makeCompletion("Final streamed answer."),
      // Third call is for the stream() after tool resolution
      makeCompletion("Final streamed answer.")
    )
    val model: ChatModel[IO] = mockChatModel(responses)
    val agent: ReactAgent = ReactAgent.create(model, List(echoTool))
    val result: IO[List[StreamedChunk]] =
      agent.stream(List(UserMessage("echo")), 5).compile.toList
    result.map { (chunks: List[StreamedChunk]) =>
      assert(chunks.nonEmpty)
      val content: String = chunks.flatMap(_.content).mkString
      assert(content.contains("Final"))
    }
  }

  test("createWithToolProvider queries tools on each invocation") {
    val callCount: AtomicInteger = new AtomicInteger(0)
    val provider: IO[List[InvokableTool[IO]]] = IO.delay {
      callCount.incrementAndGet()
      List(echoTool)
    }
    val model: ChatModel[IO] = mockChatModel(List(
      makeCompletion("first"),
      makeCompletion("second")
    ))
    val agent: ReactAgent = ReactAgent.createWithToolProvider(model, provider)
    for
      _ <- agent.generate(List(UserMessage("a")), 5)
      _ <- agent.generate(List(UserMessage("b")), 5)
    yield assertEquals(callCount.get(), 2)
  }
