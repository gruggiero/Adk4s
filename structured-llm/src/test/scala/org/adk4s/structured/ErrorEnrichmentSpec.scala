package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.core.StructuredLLMError.*

// spec: error-enrichment — Test oracle

class ErrorEnrichmentSpec extends HedgehogSuite:

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Enriched error preserves underlying message
  // ════════════════════════════════════════════════════════════════════════

  property("Enriched error message contains underlying error message") {
    val msgGen: Gen[String] = Gen.string(Gen.char('a', 'z'), Range.linear(1, 20))
    msgGen.forAll.map { (msg: String) =>
      val underlying: StructuredLLMError = ParseFailed(List.empty, msg)
      val enriched: StructuredLLMError = Enriched(underlying, Vector.empty)
      enriched.message.contains(msg) ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Enriched error includes attempt details
  // ════════════════════════════════════════════════════════════════════════

  property("Enriched error message includes attempt details") {
    val nameGen: Gen[String] = Gen.string(Gen.char('a', 'z'), Range.linear(1, 10))
    nameGen.forAll.map { (clientName: String) =>
      val underlying: StructuredLLMError = ParseFailed(List.empty, "parse fail")
      val attempts: Vector[AttemptRecord] = Vector(
        AttemptRecord(clientName, ParseFailed(List.empty, "err1"), "", 0L)
      )
      val enriched: StructuredLLMError = Enriched(underlying, attempts)
      enriched.message.contains(clientName) ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: AttemptRecord holds client, error, rawResponse, timestamp
  // ════════════════════════════════════════════════════════════════════════

  test("AttemptRecord holds all fields") {
    val error: StructuredLLMError = ParseFailed(List.empty, "test error")
    val record: AttemptRecord = AttemptRecord("client-a", error, "raw json", 12345L)
    assertEquals(record.client, "client-a")
    assertEquals(record.rawResponse, "raw json")
    assertEquals(record.timestamp, 12345L)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Enriched with multiple attempts
  // ════════════════════════════════════════════════════════════════════════

  test("Enriched error with multiple attempts lists all") {
    val dummyError: org.llm4s.error.LLMError = org.llm4s.error.UnknownError("test", new Exception("test"))
    val dummyPrompt: Prompt = Prompt.empty
    val underlying: StructuredLLMError = ParseFailed(List.empty, "final fail")
    val attempts: Vector[AttemptRecord] = Vector(
      AttemptRecord("client-a", ParseFailed(List.empty, "err1"), "raw1", 1L),
      AttemptRecord("client-b", LLMCallFailed(dummyError, dummyPrompt), "raw2", 2L)
    )
    val enriched: StructuredLLMError = Enriched(underlying, attempts)
    val msg: String = enriched.message
    assert(msg.contains("client-a"))
    assert(msg.contains("client-b"))
    assert(msg.contains("Attempt 1"))
    assert(msg.contains("Attempt 2"))
  }
