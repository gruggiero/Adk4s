package org.adk4s.core.streaming

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.Stream
import org.llm4s.llmconnect.model.{StreamedChunk, ToolCall, Completion, AssistantMessage, TokenUsage}
import ujson.Obj

class ChunkAccumulatorTest extends CatsEffectSuite:

  test("Accumulate content from multiple chunks") {
    val acc = ChunkAccumulator.empty
    val chunk1 = StreamedChunk("id1", Some("Hello"), None, None)
    val chunk2 = StreamedChunk("id2", Some(" World"), None, None)
    val result = ChunkAccumulator.accumulate(ChunkAccumulator.accumulate(acc, chunk1), chunk2)
    assertEquals(result.content, "Hello World")
    assertEquals(result.finishReason, None)
    assertEquals(result.toolCalls, Nil)
  }

  test("Accumulate tool calls from chunks") {
    val acc = ChunkAccumulator.empty
    val toolA = ToolCall("call1", "funcA", Obj("arg" -> "val"))
    val toolB = ToolCall("call2", "funcB", Obj("arg2" -> "val2"))
    val chunk1 = StreamedChunk("id1", Some("text"), Some(toolA), None)
    val chunk2 = StreamedChunk("id2", Some(" more"), Some(toolB), None)
    val result = ChunkAccumulator.accumulate(ChunkAccumulator.accumulate(acc, chunk1), chunk2)
    assertEquals(result.toolCalls, List(toolA, toolB))
  }

  test("Handle finish reason from final chunk") {
    val acc = ChunkAccumulator.empty
    val chunk = StreamedChunk("id1", Some("Hello"), None, Some("stop"))
    val result = ChunkAccumulator.accumulate(acc, chunk)
    assertEquals(result.finishReason, Some("stop"))
  }

  test("Acculate with multiple chunks including finish reason") {
    val acc = ChunkAccumulator.empty
    val chunk1 = StreamedChunk("id1", Some("Hello"), None, None)
    val chunk2 = StreamedChunk("id2", Some(" World"), None, Some("stop"))
    val result = ChunkAccumulator.accumulate(ChunkAccumulator.accumulate(acc, chunk1), chunk2)
    assertEquals(result.content, "Hello World")
    assertEquals(result.finishReason, Some("stop"))
  }

  test("Accumulate with tool call and finish reason") {
    val tool = ToolCall("call1", "func", Obj("arg" -> "val"))
    val acc = ChunkAccumulator.empty
    val chunk = StreamedChunk("id1", Some("text"), Some(tool), Some("stop"))
    val result = ChunkAccumulator.accumulate(acc, chunk)
    assertEquals(result.content, "text")
    assertEquals(result.toolCalls, List(tool))
    assertEquals(result.finishReason, Some("stop"))
  }

  test("Accumulate with empty content chunk") {
    val acc = ChunkAccumulator.empty
    val chunk1 = StreamedChunk("id1", Some("Hello"), None, None)
    val chunk2 = StreamedChunk("id2", None, None, None)
    val chunk3 = StreamedChunk("id3", Some("World"), None, None)
    val result = ChunkAccumulator.accumulate(
      ChunkAccumulator.accumulate(ChunkAccumulator.accumulate(acc, chunk1), chunk2),
      chunk3
    )
    assertEquals(result.content, "HelloWorld")
  }

  test("Accumulate with thinking delta") {
    val acc = ChunkAccumulator.empty
    val chunk = StreamedChunk("id1", Some("Hello"), None, None, Some("thinking"))
    val result = ChunkAccumulator.accumulate(acc, chunk)
    assertEquals(result.content, "Hello")
    assertEquals(result.thinking, None)
  }

  test("Accumulate preserves id and created from first chunk") {
    val acc = ChunkAccumulator.empty
    val chunk1 = StreamedChunk("id-first", Some("Hello"), None, None)
    val chunk2 = StreamedChunk("id-second", Some("World"), None, None)
    val result = ChunkAccumulator.accumulate(ChunkAccumulator.accumulate(acc, chunk1), chunk2)
    assertEquals(result.id, Some("id-first"))
  }
