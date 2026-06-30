package org.adk4s.examples.eino.workflow

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Eino equivalent: compose/workflow/2_field_mapping
 *
 * Demonstrates field mapping in a workflow. In Eino, MapFields/MapFieldPaths
 * extract and route fields between nodes. In adk4s, we use explicit pure
 * function transforms (type-safe lambdas) to achieve the same result.
 *
 * The workflow:
 *   - Input: a message with content, reasoning content, and a substring to search for
 *   - c1: counts occurrences of substring in content
 *   - c2: counts occurrences of substring in reasoning content
 *   - Output: map of { "content_count" -> c1 result, "reasoning_content_count" -> c2 result }
 *
 * Eino uses MapFields("SubStr", "SubStr") and MapFieldPaths(["Message","Content"], ["FullStr"])
 * to route fields. In Scala, we use typed lambdas for the same purpose.
 */
object FieldMappingWorkflowExample extends IOApp.Simple:

  // --- Domain types ---

  final case class MessageInput(
    content: String,
    reasoningContent: String,
    subStr: String
  )

  final case class CounterInput(
    fullStr: String,
    subStr: String
  )

  final case class WorkflowResult(
    contentCount: Int,
    reasoningContentCount: Int
  )

  // --- Word counter (shared by both nodes) ---

  private val wordCounter: Runnable[CounterInput, Int] =
    Runnable.fromInvoke[CounterInput, Int] { (input: CounterInput) =>
      IO.pure {
        // Eino equivalent: strings.Count(c.FullStr, c.SubStr)
        val fullStr: String = input.fullStr
        val subStr: String = input.subStr
        if subStr.isEmpty then 0
        else
          import scala.annotation.tailrec
          @tailrec
          def loop(idx: Int, count: Int): Int =
            if idx > fullStr.length - subStr.length then count
            else if fullStr.substring(idx, idx + subStr.length) == subStr then
              loop(idx + subStr.length, count + 1)
            else
              loop(idx + 1, count)
          loop(0, 0)
      }
    }

  // --- Field mapping transforms (Eino: MapFields / MapFieldPaths) ---

  // Eino: MapFieldPaths(["Message","Content"], ["FullStr"]) + MapFields("SubStr","SubStr")
  private val extractForContent: Runnable[MessageInput, CounterInput] =
    Runnable.fromInvoke[MessageInput, CounterInput] { (msg: MessageInput) =>
      IO.pure(CounterInput(fullStr = msg.content, subStr = msg.subStr))
    }

  // Eino: MapFieldPaths(["Message","ReasoningContent"], ["FullStr"]) + MapFields("SubStr","SubStr")
  private val extractForReasoning: Runnable[MessageInput, CounterInput] =
    Runnable.fromInvoke[MessageInput, CounterInput] { (msg: MessageInput) =>
      IO.pure(CounterInput(fullStr = msg.reasoningContent, subStr = msg.subStr))
    }

  // --- Composed pipelines ---

  // c1: extract content fields → count
  private val c1Pipeline: Runnable[MessageInput, Int] =
    Runnable.fromInvoke[MessageInput, Int] { (msg: MessageInput) =>
      extractForContent.invoke(msg).flatMap(wordCounter.invoke)
    }

  // c2: extract reasoning fields → count
  private val c2Pipeline: Runnable[MessageInput, Int] =
    Runnable.fromInvoke[MessageInput, Int] { (msg: MessageInput) =>
      extractForReasoning.invoke(msg).flatMap(wordCounter.invoke)
    }

  // --- Main ---

  def run: IO[Unit] =
    val input: MessageInput = MessageInput(
      content = "Hello world!",
      reasoningContent = "I need to say something meaningful",
      subStr = "o"
    )
    for
      _ <- ExampleUtils.printSection("FieldMapping Workflow Example (Eino: workflow/2_field_mapping)")

      _ <- ExampleUtils.printSubSection("Input")
      _ <- IO.println(s"   content:          \"${input.content}\"")
      _ <- IO.println(s"   reasoningContent: \"${input.reasoningContent}\"")
      _ <- IO.println(s"   subStr:           \"${input.subStr}\"")

      // Run both counters (Eino runs them as parallel workflow nodes)
      contentCount <- c1Pipeline.invoke(input)
      reasoningCount <- c2Pipeline.invoke(input)
      result = WorkflowResult(contentCount = contentCount, reasoningContentCount = reasoningCount)

      _ <- ExampleUtils.printSubSection("Results")
      // Eino: map[string]any{"content_count": ..., "reasoning_content_count": ...}
      _ <- IO.println(s"   content_count:           ${result.contentCount}")
      _ <- IO.println(s"   reasoning_content_count: ${result.reasoningContentCount}")

      _ <- IO.println("\n=== FieldMapping Workflow Example Completed ===")
    yield ()
