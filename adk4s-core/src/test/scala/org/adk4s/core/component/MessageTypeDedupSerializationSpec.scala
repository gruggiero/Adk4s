package org.adk4s.core.component

// spec: message-type-dedup — Test oracle (Step 2) — Serialization tests
// Tests for AgentToolState/SerializableMessage serialization compatibility.
// These tests verify the existing serialization format is preserved
// after the message-type change.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import upickle.default.*

class MessageTypeDedupSerializationSpec extends HedgehogSuite:

  // ───────────────────────────────────────────────────────────────
  // Requirement: AgentToolState serialization compatibility
  // Scenario: Round-trip serialization preserves messages
  // ───────────────────────────────────────────────────────────────

  test("AgentToolState round-trip serialization preserves messages") {
    // spec: message-type-dedup — Scenario: Round-trip serialization preserves messages
    val state: AgentToolState = AgentToolState(
      messages = List(
        SerializableMessage("user", "hello"),
        SerializableMessage("assistant", "hi there"),
        SerializableMessage("system", "be helpful")
      ),
      iterationCount = 3
    )
    val json: String = write[AgentToolState](state)
    val decoded: AgentToolState = read[AgentToolState](json)
    assertEquals(decoded.messages.length, 3)
    assertEquals(decoded.messages(0).role, "user")
    assertEquals(decoded.messages(0).content, "hello")
    assertEquals(decoded.messages(1).role, "assistant")
    assertEquals(decoded.messages(1).content, "hi there")
    assertEquals(decoded.messages(2).role, "system")
    assertEquals(decoded.messages(2).content, "be helpful")
    assertEquals(decoded.iterationCount, 3)
  }

  // ───────────────────────────────────────────────────────────────
  // Requirement: AgentToolState serialization compatibility
  // Scenario: Old checkpoint format still readable
  // ───────────────────────────────────────────────────────────────

  test("Old checkpoint JSON format is still readable as AgentToolState") {
    // spec: message-type-dedup — Scenario: Old checkpoint format still readable
    val oldJson: String = """{"messages":[{"role":"user","content":"hello"}],"iterationCount":1}"""
    val decoded: AgentToolState = read[AgentToolState](oldJson)
    assertEquals(decoded.messages.length, 1)
    assertEquals(decoded.messages(0).role, "user")
    assertEquals(decoded.messages(0).content, "hello")
    assertEquals(decoded.iterationCount, 1)
  }

  // ═══════════════════════════════════════════════════════════════
  // Property 3: SerializableMessage round-trip preserves role and content
  // spec: message-type-dedup — Property: SerializableMessage round-trip
  // ═══════════════════════════════════════════════════════════════

  property("SerializableMessage round-trip preserves role and content") {
    // Generator strategy: constructive — genRoleString and genContent.
    // Classify by role.
    val genRoleString: Gen[String] = Gen.element1("user", "assistant", "system", "tool")
    val genContent: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(0, 100))

    val genSerializableMessage: Gen[SerializableMessage] =
      for
        role    <- genRoleString
        content <- genContent
      yield SerializableMessage(role, content)

    genSerializableMessage.forAll.map { (sm: SerializableMessage) =>
      read[SerializableMessage](write[SerializableMessage](sm)) ==== sm
    }
  }
