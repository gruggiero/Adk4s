package org.adk4s.orchestration.agent

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.json.JsonValueCodec
import org.adk4s.harness.{ CellVisibility, HarnessState, MiddlewareName, StateCell }
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, ToolCall, ToolMessage, UserMessage }
import smithy4s.Document
import upickle.default.*

/**
 * Test oracle for spec:checkpoint-store-fpoly — `CheckpointStateV2` scenarios
 * and the V1-read-compat / V2-round-trip / full-fidelity properties.
 *
 * Tests written from the spec + approved typed contract ONLY.
 * Every test cites its source: `// spec: checkpoint-store-fpoly — Scenario: <heading>`
 */
class CheckpointStateV2Spec extends HedgehogSuite:

  // ── Scenarios (munit) ─────────────────────────────────────────────────────

  test("version field is 2") {
    // spec: checkpoint-store-fpoly — Scenario: version field is 2
    val cp: CheckpointStateV2 = CheckpointStateV2()
    assertEquals(cp.version, 2)
  }

  test("empty-stack harnessState is an empty object") {
    // spec: checkpoint-store-fpoly — Scenario: empty-stack harnessState is an empty object
    val cp: CheckpointStateV2 = CheckpointStateV2(harnessState = HarnessState.empty.snapshot)
    cp.harnessState match
      case Document.DObject(fields) => assert(fields.isEmpty, s"expected empty DObject, got $fields")
      case other                    => fail(s"expected DObject, got ${other.getClass.getSimpleName}")
  }

  test("harnessState carries HarnessState.snapshot") {
    // spec: checkpoint-store-fpoly — Scenario: harnessState carries HarnessState.snapshot
    val cell: StateCell[Int]  = StateCell[Int](MiddlewareName("m"), "counter", 0)
    val state: HarnessState   = HarnessState.initial(List(cell)).set(cell)(42)
    val cp: CheckpointStateV2 = CheckpointStateV2(harnessState = state.snapshot)
    cp.harnessState match
      case Document.DObject(fields) =>
        assert(fields.contains("m/counter"), s"expected 'm/counter' field, got ${fields.keys}")
        fields("m/counter") match
          case Document.DNumber(n) => assertEquals(n.toInt, 42)
          case other               => fail(s"expected DNumber, got $other")
      case other => fail(s"expected DObject, got ${other.getClass.getSimpleName}")
  }

  test("AssistantMessage with tool calls is serialized with full fidelity") {
    // spec: checkpoint-store-fpoly — Scenario: AssistantMessage with tool calls is serialized with full fidelity
    val msg: AssistantMessage = AssistantMessage(
      contentOpt = Some("Let me check"),
      toolCalls = Seq(ToolCall("call_42", "query", ujson.Obj("sql" -> ujson.Str("SELECT 1"))))
    )
    val cm: CheckpointMessage = CheckpointMessageConverter.toCheckpoint(msg)
    val json: String          = upickle.default.write(cm)
    assert(json.contains("call_42"), s"expected 'call_42' in json: $json")
    assert(json.contains("query"), s"expected 'query' in json: $json")
    assert(json.contains("sql"), s"expected 'sql' in json: $json")
  }

  test("ToolMessage is serialized with toolCallId") {
    // spec: checkpoint-store-fpoly — Scenario: ToolMessage is serialized with toolCallId
    val msg: ToolMessage      = ToolMessage("42", "call_42")
    val cm: CheckpointMessage = CheckpointMessageConverter.toCheckpoint(msg)
    val json: String          = upickle.default.write(cm)
    assert(json.contains("call_42"), s"expected 'call_42' in json: $json")
  }

  test("Plain user message has no tool fields") {
    // spec: checkpoint-store-fpoly — Scenario: Plain user message has no tool fields
    val msg: UserMessage      = UserMessage("hello")
    val cm: CheckpointMessage = CheckpointMessageConverter.toCheckpoint(msg)
    assertEquals(cm.toolCalls, Nil)
    assertEquals(cm.toolCallId, None)
  }

  test("Unknown role is a hard error, not a silent UserMessage") {
    // Adversarial review fix: fromCheckpoint must not silently map an unknown
    // role to a valid UserMessage — that masks checkpoint corruption.
    val corrupted: CheckpointMessage = CheckpointMessage(role = "bogus", content = "x")
    val result: Either[String, Message] = CheckpointMessageConverter.fromCheckpoint(corrupted)
    result match
      case Left(err) => assert(err.contains("bogus"), s"expected error mentioning 'bogus', got: $err")
      case Right(msg) => fail(s"expected Left for unknown role, got Right($msg)")
  }

  test("Resumed conversation passes tool-call/tool-result pairing validation") {
    // spec: checkpoint-store-fpoly — Scenario: Resumed conversation passes tool-call/tool-result pairing validation
    val assistantCm: CheckpointMessage = CheckpointMessage(
      role = "assistant",
      content = "Let me check",
      toolCalls = List(CheckpointToolCall("call_1", "search", """{"q":"scala"}"""))
    )
    val toolCm: CheckpointMessage = CheckpointMessage(
      role = "tool",
      content = "result",
      toolCalls = Nil,
      toolCallId = Some("call_1")
    )
    val reconstructed: List[Message] = List(assistantCm, toolCm).map { (cm: CheckpointMessage) =>
      CheckpointMessageConverter.fromCheckpoint(cm)
        .getOrElse(sys.error(s"expected valid message for role ${cm.role}"))
    }
    val validation: org.llm4s.types.Result[Unit] = Message.validateConversation(reconstructed)
    validation match
      case Right(_)  => () // pass
      case Left(err) => fail(s"validation failed: ${err.formatted}")
  }

  test("v1 payload with tool messages decodes successfully") {
    // spec: checkpoint-store-fpoly — Scenario: v1 payload with tool messages decodes successfully
    val v1Json: String =
      """{"messages":[{"role":"tool","content":"result"}],"interruptSignalJson":"","agentName":"test"}"""
    val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](v1Json)(using CheckpointStateV2.readWriter)
    assertEquals(decoded.messages.length, 1)
    val firstMsg: CheckpointMessage = decoded.messages.headOption.getOrElse(sys.error("expected at least one message"))
    assertEquals(firstMsg.toolCallId, None)
    assertEquals(firstMsg.toolCalls, Nil)
  }

  test("v1 payload with assistant tool calls loses fidelity (known limitation)") {
    // spec: checkpoint-store-fpoly — Scenario: v1 payload with assistant tool calls loses fidelity (known limitation, not a failure)
    val v1Json: String =
      """{"messages":[{"role":"assistant","content":""}],"interruptSignalJson":"","agentName":"test"}"""
    val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](v1Json)(using CheckpointStateV2.readWriter)
    assertEquals(decoded.messages.length, 1)
    val firstMsg: CheckpointMessage = decoded.messages.headOption.getOrElse(sys.error("expected at least one message"))
    assertEquals(firstMsg.toolCalls, Nil)
  }

  test("v2 payload decodes with full fidelity") {
    // spec: checkpoint-store-fpoly — Scenario: v2 payload decodes with full fidelity
    val v2Json: String =
      """{"version":2,"messages":[{"role":"assistant","content":"check","toolCalls":[{"id":"call_1","name":"search","arguments":"{\"q\":\"scala\"}"}],"toolCallId":null},{"role":"tool","content":"result","toolCalls":[],"toolCallId":"call_1"}],"harnessState":{"counter":3},"interruptSignalJson":"","agentName":"test"}"""
    val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](v2Json)(using CheckpointStateV2.readWriter)
    assertEquals(decoded.version, 2)
    decoded.harnessState match
      case Document.DObject(fields) =>
        fields.get("counter") match
          case Some(Document.DNumber(n)) => assertEquals(n.toInt, 3)
          case other                     => fail(s"expected DNumber(3), got $other")
      case other => fail(s"expected DObject, got ${other.getClass.getSimpleName}")
    val assistantMsg: CheckpointMessage = decoded.messages
      .find(_.role == "assistant")
      .getOrElse(fail("expected assistant message"))
    assertEquals(assistantMsg.toolCalls.length, 1)
    assertEquals(assistantMsg.toolCalls.headOption.getOrElse(sys.error("expected tool call")).id, "call_1")
    val toolMsg: CheckpointMessage = decoded.messages
      .find(_.role == "tool")
      .getOrElse(fail("expected tool message"))
    assertEquals(toolMsg.toolCallId, Some("call_1"))
  }

  test("corrupted payload fails to decode") {
    // spec: checkpoint-store-fpoly — Scenario: corrupted payload fails to decode
    val corrupted: String = "{not json"
    intercept[Exception] {
      upickle.default.read[CheckpointStateV2](corrupted)(using CheckpointStateV2.readWriter)
    }
  }

  // ── Generators (defined before properties to avoid init-order NPE) ────────

  val genRole: Gen[String] =
    Gen.element1("user", "assistant", "system", "tool")

  val genContent: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(0, 100))

  val genAgentName: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 20))

  val genInterruptSignalJson: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(0, 50))

  val genCheckpointToolCall: Gen[CheckpointToolCall] =
    for
      id   <- Gen.string(Gen.alphaNum, Range.linear(1, 15))
      name <- Gen.string(Gen.alphaNum, Range.linear(1, 15))
      args <- Gen.string(Gen.alphaNum, Range.linear(0, 50))
    yield CheckpointToolCall(id, name, args)

  val genAssistantMessage: Gen[CheckpointMessage] =
    for
      content   <- genContent
      toolCalls <- genCheckpointToolCall.list(Range.linear(0, 3))
    yield CheckpointMessage("assistant", content, toolCalls, None)

  val genToolMessage: Gen[CheckpointMessage] =
    for
      content    <- Gen.string(Gen.alphaNum, Range.linear(1, 50))
      toolCallId <- Gen.string(Gen.alphaNum, Range.linear(1, 10))
    yield CheckpointMessage("tool", content, Nil, Some(toolCallId))

  val genUserMessage: Gen[CheckpointMessage] =
    genContent.map(CheckpointMessage("user", _, Nil, None))

  val genSystemMessage: Gen[CheckpointMessage] =
    genContent.map(CheckpointMessage("system", _, Nil, None))

  val genCheckpointMessage: Gen[CheckpointMessage] =
    Gen.choice1(genAssistantMessage, genToolMessage, genUserMessage, genSystemMessage)

  val genJsonValueDObject: Gen[Document.DObject] =
    Gen
      .string(Gen.alpha, Range.linear(1, 5))
      .list(Range.linear(0, 5))
      .map { (keys: List[String]) =>
        val entries: Map[String, Document] = keys.zipWithIndex.map { (k, i) =>
          k -> Document.DNumber(BigDecimal(i))
        }.toMap
        Document.DObject(entries)
      }

  val genCheckpointStateV2: Gen[CheckpointStateV2] =
    for
      messages            <- genCheckpointMessage.list(Range.linear(0, 10))
      harnessState        <- genJsonValueDObject
      interruptSignalJson <- genInterruptSignalJson
      agentName           <- genAgentName
    yield CheckpointStateV2(
      version = 2,
      messages = messages,
      harnessState = harnessState,
      interruptSignalJson = interruptSignalJson,
      agentName = agentName
    )

  /** Generates a CheckpointStateV2 with exactly two messages: an assistant with toolCalls and a tool with toolCallId. */
  val genFidelityCheckpoint: Gen[CheckpointStateV2] =
    for
      toolCalls <- genCheckpointToolCall.list(Range.linear(1, 3))
      firstId = toolCalls.headOption.getOrElse(sys.error("expected at least one tool call")).id
      assistantContent <- Gen.string(Gen.alphaNum, Range.linear(0, 50))
      toolContent      <- Gen.string(Gen.alphaNum, Range.linear(1, 50))
      agentName        <- genAgentName
    yield CheckpointStateV2(
      version = 2,
      messages = List(
        CheckpointMessage("assistant", assistantContent, toolCalls, None),
        CheckpointMessage("tool", toolContent, Nil, Some(firstId))
      ),
      harnessState = Document.DObject(Map.empty),
      interruptSignalJson = "",
      agentName = agentName
    )

  // ── V1 generators ─────────────────────────────────────────────────────────

  val genV1Message: Gen[SerializableCheckpointMessage] =
    for
      role    <- genRole
      content <- genContent
    yield SerializableCheckpointMessage(role, content)

  val genV1CheckpointState: Gen[CheckpointState] =
    for
      messages            <- genV1Message.list(Range.linear(0, 10))
      interruptSignalJson <- genInterruptSignalJson
      agentName           <- genAgentName
    yield CheckpointState(messages, interruptSignalJson, agentName)

  // ── HarnessState generators (for snapshot round-trip) ─────────────────────

  val genIntCell: Gen[StateCell[Int]] =
    for
      owner   <- Gen.string(Gen.alphaNum, Range.linear(1, 5))
      name    <- Gen.string(Gen.alphaNum, Range.linear(1, 5))
      initial <- Gen.int(Range.linear(-100, 100))
    yield StateCell[Int](MiddlewareName(owner), name, initial)

  val genIntCellWithValue: Gen[(StateCell[Int], Int)] =
    for
      cell  <- genIntCell
      value <- Gen.int(Range.linear(-1000, 1000))
    yield (cell, value)

  // ── Properties (Ring 3) ───────────────────────────────────────────────────

  property("V1 read compatibility — v1 payload decodes to CheckpointStateV2") {
    // spec: checkpoint-store-fpoly — Property: V1 read compatibility — v1 payload decodes to CheckpointStateV2
    for v1 <- genV1CheckpointState.forAll
    yield
      val v1Json: String = upickle.default.write(v1)
      val decoded: CheckpointStateV2 =
        upickle.default.read[CheckpointStateV2](v1Json)(using CheckpointStateV2.readWriter)
      val harnessOk: Boolean = decoded.harnessState match
        case Document.DObject(fields) => fields.isEmpty
        case _                        => false
      val messagesOk: Boolean =
        decoded.messages.length == v1.messages.length &&
          decoded.messages.forall(m => m.toolCalls.isEmpty && m.toolCallId.isEmpty)
      (harnessOk && messagesOk) ==== true
  }

  property("V2 round-trip — write then read is identity") {
    // spec: checkpoint-store-fpoly — Property: V2 round-trip — write then read is identity
    for cpv2 <- genCheckpointStateV2.forAll
    yield
      val json: String               = upickle.default.write(cpv2)(using CheckpointStateV2.readWriter)
      val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](json)(using CheckpointStateV2.readWriter)
      decoded ==== cpv2
  }

  property("Full-fidelity preservation — toolCalls and toolCallId survive round-trip") {
    // spec: checkpoint-store-fpoly — Property: Full-fidelity preservation — toolCalls and toolCallId survive round-trip
    for cp <- genFidelityCheckpoint.forAll
    yield
      val json: String               = upickle.default.write(cp)(using CheckpointStateV2.readWriter)
      val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](json)(using CheckpointStateV2.readWriter)
      val origAssistant: CheckpointMessage = cp.messages
        .find(_.role == "assistant")
        .getOrElse(fail("expected assistant message"))
      val decodedAssistant: CheckpointMessage = decoded.messages
        .find(_.role == "assistant")
        .getOrElse(fail("expected assistant message"))
      val toolCallsOk: Boolean = decodedAssistant.toolCalls == origAssistant.toolCalls
      val toolCallIdOk: Boolean =
        decoded.messages.find(_.role == "tool").flatMap(_.toolCallId) ==
          cp.messages.find(_.role == "tool").flatMap(_.toolCallId)
      (toolCallsOk && toolCallIdOk) ==== true
  }

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
      val restored: Either[org.adk4s.core.error.StateDecodeError, HarnessState] =
        HarnessState.restore(List(cell), cp.harnessState)
      val ok: Boolean = restored match
        case Right(restoredState) => restoredState.get(cell) == value
        case Left(_)              => false
      ok ==== true
  }
