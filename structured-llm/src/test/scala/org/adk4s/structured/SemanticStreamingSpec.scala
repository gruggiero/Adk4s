package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.sap.*
import smithy4s.schema.Schema as Smithy4sSchema

// spec: semantic-streaming — Test oracle

class SemanticStreamingSpec extends HedgehogSuite:

  given s4sString: Smithy4sSchema[String] = smithy4s.Schema.string
  given schemaString: Schema[String] = Schema.instance("string String")(using s4sString)

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: StreamState.complete has Complete state
  // ════════════════════════════════════════════════════════════════════════

  property("StreamState.complete has Complete state and Some value") {
    val stringGen: Gen[String] = Gen.string(Gen.char('a', 'z'), Range.linear(1, 10))
    stringGen.forAll.map { (s: String) =>
      val state: StreamState[String] = StreamState.complete(s)
      (state.state ==== CompletionState.Complete)
        .and(state.value ==== Some(s))
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: StreamState.pending has Pending state and None value
  // ════════════════════════════════════════════════════════════════════════

  property("StreamState.pending has Pending state and None value") {
    Gen.constant(()).forAll.map { (_: Unit) =>
      val pending: StreamState[String] = StreamState.pending[String]
      (pending.state ==== CompletionState.Pending)
        .and(pending.value ==== None)
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: StreamingBehavior defaults
  // ════════════════════════════════════════════════════════════════════════

  test("StreamingBehavior.default has all flags false") {
    val behavior: StreamingBehavior = StreamingBehavior.default
    assert(!behavior.done)
    assert(!behavior.needed)
    assert(!behavior.withState)
  }

  test("StreamingBehavior.done has done=true") {
    val behavior: StreamingBehavior = StreamingBehavior.done
    assert(behavior.done)
  }

  test("StreamingBehavior.needed has needed=true") {
    val behavior: StreamingBehavior = StreamingBehavior.needed
    assert(behavior.needed)
  }

  test("StreamState.incomplete has Incomplete state") {
    val state: StreamState[String] = StreamState.incomplete(Some("partial"))
    assertEquals(state.state, CompletionState.Incomplete)
  }
