package org.adk4s.structured.core

// spec: message-type-dedup — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.
// These tests reference the NEW API (Prompt wrapping Conversation,
// deprecated Message/Role aliases).
// They will NOT compile until Step 3 implements the changes.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Conversation,
  Message as Llm4sMessage,
  MessageRole,
  SystemMessage,
  ToolMessage,
  UserMessage
}
import smithy4s.schema.Schema as Smithy4sSchema

class MessageTypeDedupSpec extends HedgehogSuite:

  // ───────────────────────────────────────────────────────────────
  // Helpers — a simple Schema instance for testing withOutputFormat
  // ───────────────────────────────────────────────────────────────

  given Schema[String] = Schema.instance[String](
    "structure SimpleString { @required value: String }",
    Some("test string schema")
  )(using Smithy4sSchema.string)

  // ───────────────────────────────────────────────────────────────
  // Requirement: Prompt wraps Conversation directly
  // Scenario: Prompt.simple produces correct Conversation
  // ───────────────────────────────────────────────────────────────

  test("Prompt.simple produces correct Conversation") {
    // spec: message-type-dedup — Scenario: Prompt.simple produces correct Conversation
    val prompt: Prompt = Prompt.simple("You are helpful", "Parse this")
    val messages: Seq[Llm4sMessage] = prompt.conversation.messages
    assertEquals(messages.length, 2)
    messages(0) match
      case sm: SystemMessage => assertEquals(sm.content, "You are helpful")
      case other             => fail(s"Expected SystemMessage, got ${other.getClass.getName}")
    messages(1) match
      case um: UserMessage => assertEquals(um.content, "Parse this")
      case other           => fail(s"Expected UserMessage, got ${other.getClass.getName}")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: Prompt wraps Conversation directly
  // Scenario: Prompt with ToolMessage preserves toolCallId
  // ───────────────────────────────────────────────────────────────

  test("Prompt with ToolMessage preserves toolCallId") {
    // spec: message-type-dedup — Scenario: ToolMessage preserves toolCallId
    val toolMsg: ToolMessage = ToolMessage("result", "call-123")
    val prompt: Prompt = Prompt.single(toolMsg)
    prompt.conversation.messages(0) match
      case tm: ToolMessage =>
        assertEquals(tm.toolCallId, "call-123")
        assertEquals(tm.content, "result")
      case other =>
        fail(s"Expected ToolMessage, got ${other.getClass.getName}")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: Prompt wraps Conversation directly
  // Scenario: Prompt with AssistantMessage preserves toolCalls
  // ───────────────────────────────────────────────────────────────

  test("Prompt with AssistantMessage preserves toolCalls") {
    // spec: message-type-dedup — Scenario: AssistantMessage preserves toolCalls
    val calls: Seq[org.llm4s.llmconnect.model.ToolCall] = Seq(
      org.llm4s.llmconnect.model.ToolCall("call-1", "search", """{"q":"test"}"""),
      org.llm4s.llmconnect.model.ToolCall("call-2", "fetch", """{"url":"x"}""")
    )
    val asstMsg: AssistantMessage = AssistantMessage(contentOpt = Some("thinking"), toolCalls = calls)
    val prompt: Prompt = Prompt.single(asstMsg)
    prompt.conversation.messages(0) match
      case am: AssistantMessage =>
        assertEquals(am.toolCalls.length, 2)
        assertEquals(am.toolCalls(0).id, "call-1")
        assertEquals(am.toolCalls(1).id, "call-2")
      case other =>
        fail(s"Expected AssistantMessage, got ${other.getClass.getName}")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: withOutputFormat appends schema to last user message
  // Scenario: Schema appended to user message
  // ───────────────────────────────────────────────────────────────

  test("withOutputFormat appends schema block to last user message") {
    // spec: message-type-dedup — Scenario: Schema appended to user message
    val prompt: Prompt = Prompt.user("extract data")
    val withSchema: Prompt = prompt.withOutputFormat[String]
    val lastIdx: Int = withSchema.conversation.messages.length - 1
    withSchema.conversation.messages(lastIdx) match
      case um: UserMessage =>
        assert(um.content.startsWith("extract data"))
        assert(um.content.contains("structure SimpleString"))
      case other =>
        fail(s"Expected UserMessage, got ${other.getClass.getName}")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: withOutputFormat appends schema to last user message
  // Scenario: No user message present
  // ───────────────────────────────────────────────────────────────

  test("withOutputFormat appends new UserMessage when no user message exists") {
    // spec: message-type-dedup — Scenario: No user message present
    val prompt: Prompt = Prompt.system("you are helpful")
    val withSchema: Prompt = prompt.withOutputFormat[String]
    assertEquals(withSchema.conversation.messages.length, 2)
    withSchema.conversation.messages(0) match
      case sm: SystemMessage => assertEquals(sm.content, "you are helpful")
      case other             => fail(s"Expected SystemMessage, got ${other.getClass.getName}")
    withSchema.conversation.messages(1) match
      case um: UserMessage =>
        assert(um.content.contains("structure SimpleString"))
      case other =>
        fail(s"Expected UserMessage, got ${other.getClass.getName}")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: Deprecated type aliases for migration
  // Scenario: Deprecated alias resolves to llm4s type
  // ───────────────────────────────────────────────────────────────

  test("Deprecated Message alias resolves to llm4s Message type") {
    // spec: message-type-dedup — Scenario: Deprecated alias resolves to llm4s type
    val msg: Message = UserMessage("hello"): Llm4sMessage
    msg match
      case um: UserMessage => assertEquals(um.content, "hello")
      case other           => fail(s"Expected UserMessage, got ${other.getClass.getName}")
  }

  test("Deprecated Role alias resolves to llm4s MessageRole type") {
    // spec: message-type-dedup — Scenario: Deprecated alias resolves to llm4s type
    val role: Role = MessageRole.User
    assertEquals(role.name, "user")
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: MessageConverter and ConversationConverter removed
  // Scenario: toConversation is identity
  // ───────────────────────────────────────────────────────────────

  test("Prompt.conversation is the wrapped Conversation (identity access)") {
    // spec: message-type-dedup — Scenario: toConversation is identity
    val conv: Conversation = Conversation(Seq(
      SystemMessage("sys"),
      UserMessage("usr"),
      AssistantMessage(contentOpt = Some("asst"), toolCalls = Seq.empty)
    ))
    val prompt: Prompt = Prompt(conv)
    assertEquals(prompt.conversation, conv)
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 1: Conversation round-trip is identity
  // spec: message-type-dedup — Property: Conversation round-trip
  // ═══════════════════════════════════════════════════════════════

  property("Conversation round-trip is identity") {
    // Generator strategy: constructive — genConversation generates Conversation values.
    // Classify by message count.
    val genSystemMsg: Gen[SystemMessage] = Gen.string(Gen.alphaNum, Range.linear(0, 100)).map(SystemMessage.apply)
    val genUserMsg: Gen[UserMessage] = Gen.string(Gen.alphaNum, Range.linear(0, 100)).map(UserMessage.apply)
    val genAsstMsg: Gen[AssistantMessage] =
      Gen.string(Gen.alphaNum, Range.linear(0, 100)).map(c => AssistantMessage(Some(c), Seq.empty))
    val genToolMsg: Gen[ToolMessage] =
      for
        content <- Gen.string(Gen.alphaNum, Range.linear(0, 50))
        callId  <- Gen.string(Gen.alphaNum, Range.linear(1, 20))
      yield ToolMessage(content, callId)

    val genMessage: Gen[Llm4sMessage] = Gen.frequency1(
      (25, genSystemMsg.map(identity[Llm4sMessage])),
      (25, genUserMsg.map(identity[Llm4sMessage])),
      (25, genAsstMsg.map(identity[Llm4sMessage])),
      (25, genToolMsg.map(identity[Llm4sMessage]))
    )

    val genConversation: Gen[Conversation] =
      Gen.list(genMessage, Range.linear(0, 10)).map(msgs => Conversation(msgs.toSeq))

    genConversation.forAll.map { (conv: Conversation) =>
      Prompt(conv).conversation ==== conv
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 2: withOutputFormat preserves non-last messages
  // spec: message-type-dedup — Property: withOutputFormat preserves non-last
  // ═══════════════════════════════════════════════════════════════

  property("withOutputFormat preserves non-last messages") {
    // Generator strategy: constructive — genNonEmptyPrompt generates Prompts with >=1 message
    // where the last message is a UserMessage (so withOutputFormat appends to it in-place).
    // Classify by message count.
    val genSystemMsg: Gen[SystemMessage] = Gen.string(Gen.alphaNum, Range.linear(0, 100)).map(SystemMessage.apply)
    val genUserMsg: Gen[UserMessage] = Gen.string(Gen.alphaNum, Range.linear(0, 100)).map(UserMessage.apply)
    val genAsstMsg: Gen[AssistantMessage] =
      Gen.string(Gen.alphaNum, Range.linear(0, 100)).map(c => AssistantMessage(Some(c), Seq.empty))

    val genMessage: Gen[Llm4sMessage] = Gen.frequency1(
      (33, genSystemMsg.map(identity[Llm4sMessage])),
      (33, genUserMsg.map(identity[Llm4sMessage])),
      (34, genAsstMsg.map(identity[Llm4sMessage]))
    )

    // Generate a prompt where the last message is always a UserMessage
    val genNonEmptyPrompt: Gen[Prompt] =
      for
        prefixMsgs <- Gen.list(genMessage, Range.linear(0, 9))
        lastMsg    <- genUserMsg
      yield Prompt(Conversation(prefixMsgs.toSeq :+ lastMsg))

    genNonEmptyPrompt.forAll.map { (prompt: Prompt) =>
      val withSchema: Prompt = prompt.withOutputFormat[String]
      withSchema.conversation.messages.dropRight(1) ==== prompt.conversation.messages.dropRight(1)
    }
  }
