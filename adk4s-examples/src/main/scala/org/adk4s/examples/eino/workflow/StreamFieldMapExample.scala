package org.adk4s.examples.eino.workflow

import cats.effect.IO
import cats.effect.IOApp
import fs2.Stream
import org.adk4s.core.streaming.StreamFieldMerger
import org.adk4s.core.streaming.StreamFieldSplitter
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Eino equivalent: compose/workflow/6_stream_field_map
 *
 * Demonstrates stream-level field mapping. A stream of messages is split
 * into content and reasoning-content sub-streams, each processed by a
 * word counter, then merged into a combined output stream.
 *
 * Eino uses MapFields("Content","FullStr"), SetStaticValue(["SubStr"],"o"),
 * and TransformableLambda for per-chunk processing. In adk4s, we use
 * StreamFieldSplitter, StreamFieldSplitter.withStaticValue, and
 * StreamFieldMerger for the same pattern.
 */
object StreamFieldMapExample extends IOApp.Simple:

  // --- Domain types ---

  final case class MessageChunk(
    content: String,
    reasoningContent: String
  )

  final case class CounterInput(
    fullStr: String,
    subStr: String
  )

  final case class ChunkResult(
    contentCount: Int,
    reasoningContentCount: Int
  )

  // --- Word counter (per-chunk, same as Eino's wordCounter) ---

  private def countOccurrences(fullStr: String, subStr: String): Int =
    import scala.annotation.tailrec
    if subStr.isEmpty then 0
    else
      @tailrec
      def loop(idx: Int, count: Int): Int =
        if idx > fullStr.length - subStr.length then count
        else if fullStr.substring(idx, idx + subStr.length) == subStr then
          loop(idx + subStr.length, count + 1)
        else
          loop(idx + 1, count)
      loop(0, 0)

  // --- Main ---

  def run: IO[Unit] =
    // Eino input: stream of messages with content and reasoning content
    val messageStream: Stream[IO, MessageChunk] = Stream.emits(List(
      MessageChunk(content = "", reasoningContent = "I need to say something meaningful"),
      MessageChunk(content = "Hello world!", reasoningContent = "")
    ))

    val subStr: String = "o"

    for
      _ <- ExampleUtils.printSection("StreamFieldMap Example (Eino: workflow/6_stream_field_map)")

      _ <- ExampleUtils.printSubSection("Input stream")
      _ <- IO.println(s"   Searching for: \"$subStr\"")
      _ <- IO.println(s"   Chunk 1: content=\"\", reasoningContent=\"I need to say something meaningful\"")
      _ <- IO.println(s"   Chunk 2: content=\"Hello world!\", reasoningContent=\"\"")

      // Step 1: Split stream into content and reasoning streams
      // Eino: MapFields("Content", "FullStr") + SetStaticValue(["SubStr"], "o")
      (contentStream, reasoningStream) <- StreamFieldSplitter.split2[MessageChunk, CounterInput, CounterInput](
        messageStream,
        (msg: MessageChunk) => CounterInput(fullStr = msg.content, subStr = subStr),
        (msg: MessageChunk) => CounterInput(fullStr = msg.reasoningContent, subStr = subStr)
      )

      // Step 2: Process each stream through word counter
      // Eino: TransformableLambda(wordCounter) — per-chunk counting
      contentCounts = contentStream.map((ci: CounterInput) => countOccurrences(ci.fullStr, ci.subStr))
      reasoningCounts = reasoningStream.map((ci: CounterInput) => countOccurrences(ci.fullStr, ci.subStr))

      // Step 3: Merge results
      // Eino: END.AddInput("c1", ToField("content_count")).AddInput("c2", ToField("reasoning_content_count"))
      mergedStream = StreamFieldMerger.merge2[Int, Int, ChunkResult](
        contentCounts, reasoningCounts,
        (cc: Int, rc: Int) => ChunkResult(contentCount = cc, reasoningContentCount = rc)
      )

      _ <- ExampleUtils.printSubSection("Per-chunk results")
      results <- mergedStream.evalMap { (chunk: ChunkResult) =>
        IO.println(s"   content_count=${chunk.contentCount}, reasoning_content_count=${chunk.reasoningContentCount}")
          .as(chunk)
      }.compile.toList

      // Accumulate totals (Eino example does this in the consumer loop)
      totalContent = results.map(_.contentCount).sum
      totalReasoning = results.map(_.reasoningContentCount).sum

      _ <- ExampleUtils.printSubSection("Totals")
      _ <- IO.println(s"   content count:   $totalContent")
      _ <- IO.println(s"   reasoning count: $totalReasoning")

      _ <- IO.println("\n=== StreamFieldMap Example Completed ===")
    yield ()
