package org.adk4s.examples.structured.toolcall

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.*
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.tools.{StructuredToolCall, ToolSchema, TypedTool}
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Demonstrates dynamic tool creation and management with StructuredToolCall.
 *
 * Shows how to:
 * - Create typed tools dynamically using StructuredToolCall.createTool
 * - Collect multiple tools in a list for runtime management
 * - Convert TypedTool to InvokableTool for LLM compatibility
 * - Execute tools by name with type safety
 * - Build extensible tool systems with compile-time guarantees
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object DynamicToolRegistryStructuredExample extends IOApp.Simple:

  // Tool input/output case classes for various domains
  case class TranslateInput(text: String, targetLanguage: String)
  case class TranslateResult(translatedText: String, sourceLanguage: String)

  case class SummarizeInput(text: String, maxWords: Int)
  case class SummarizeResult(summary: String, wordCount: Int)

  case class SentimentInput(text: String)
  case class SentimentResult(sentiment: String, score: Double)

  // Derive ToolSchema instances
  given ToolSchema[TranslateInput] = ToolSchema.derive[TranslateInput]
  given ToolSchema[TranslateResult] = ToolSchema.derive[TranslateResult]
  given ToolSchema[SummarizeInput] = ToolSchema.derive[SummarizeInput]
  given ToolSchema[SummarizeResult] = ToolSchema.derive[SummarizeResult]
  given ToolSchema[SentimentInput] = ToolSchema.derive[SentimentInput]
  given ToolSchema[SentimentResult] = ToolSchema.derive[SentimentResult]

  // Factory methods for creating typed tools
  private def createTranslateTool: TypedTool[IO, TranslateInput, TranslateResult] =
    StructuredToolCall.createTool[IO, TranslateInput, TranslateResult](
      toolName = "translate",
      toolDescription = "Translates text from one language to another"
    ) { input =>
      // Mock translation based on target language
      val translated: String = (input.text, input.targetLanguage.toLowerCase) match
        case (text, "spanish") => s"[ES] $text"
        case (text, "french") => s"[FR] $text"
        case (text, "german") => s"[DE] $text"
        case (text, "japanese") => s"[JA] $text"
        case (text, _) => s"[??] $text"

      IO.pure(TranslateResult(translated, "english"))
    }

  private def createSummarizeTool: TypedTool[IO, SummarizeInput, SummarizeResult] =
    StructuredToolCall.createTool[IO, SummarizeInput, SummarizeResult](
      toolName = "summarize",
      toolDescription = "Summarizes text to a target word count"
    ) { input =>
      // Mock summarization by truncating and adding ellipsis
      val words: Array[String] = input.text.split("\\s+")
      val summaryWords: Array[String] = words.take(input.maxWords)
      val summary: String = if summaryWords.length < words.length then
        summaryWords.mkString(" ") + "..."
      else
        summaryWords.mkString(" ")

      IO.pure(SummarizeResult(summary, summaryWords.length))
    }

  private def createSentimentTool: TypedTool[IO, SentimentInput, SentimentResult] =
    StructuredToolCall.createTool[IO, SentimentInput, SentimentResult](
      toolName = "sentiment",
      toolDescription = "Analyzes the sentiment of text"
    ) { input =>
      // Mock sentiment analysis based on keywords
      val text: String = input.text.toLowerCase
      val (sentiment: String, score: Double) =
        if text.contains("love") || text.contains("amazing") || text.contains("excellent") then
          ("positive", 0.9)
        else if text.contains("hate") || text.contains("terrible") || text.contains("awful") then
          ("negative", 0.85)
        else if text.contains("okay") || text.contains("fine") then
          ("neutral", 0.5)
        else
          ("neutral", 0.6)

      IO.pure(SentimentResult(sentiment, score))
    }

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Dynamic Tool Registry (Structured)")
      llmClient <- createLLMClient

      // Create typed tools dynamically
      _ <- ExampleUtils.printSubSection("1. Creating Typed Tools")
      translateTool = createTranslateTool
      summarizeTool = createSummarizeTool
      sentimentTool = createSentimentTool
      _ <- IO.println(s"   Created tools: ${translateTool.name}, ${summarizeTool.name}, ${sentimentTool.name}")

      // Convert to InvokableTool and collect in list (simple registry)
      _ <- ExampleUtils.printSubSection("2. Tool Collection")
      toolList: List[InvokableTool[IO]] = List(
        translateTool.asInvokableTool,
        summarizeTool.asInvokableTool,
        sentimentTool.asInvokableTool
      )
      _ <- IO.println(s"   Collected ${toolList.size} tools")
      _ <- toolList.traverse_ { tool =>
        IO.println(s"     - ${tool.info.name}: ${tool.info.description}")
      }

      // Execute tools via registry (demonstrating typed execution)
      _ <- ExampleUtils.printSubSection("3. Executing Translate Tool")
      translateInput = TranslateInput("Hello, world!", "spanish")
      translateResult <- translateTool.execute(translateInput)
      _ <- IO.println(s"   Input: '${translateInput.text}' → ${translateInput.targetLanguage}")
      _ <- IO.println(s"   Result: '${translateResult.translatedText}'")
      _ <- IO.println(s"   Source: ${translateResult.sourceLanguage}")

      _ <- ExampleUtils.printSubSection("4. Executing Summarize Tool")
      summarizeInput = SummarizeInput(
        "Artificial intelligence and machine learning are transforming how we build software. " +
        "These technologies enable systems to learn from data and improve over time. " +
        "The future of software development will be heavily influenced by AI.",
        maxWords = 10
      )
      summarizeResult <- summarizeTool.execute(summarizeInput)
      _ <- IO.println(s"   Max words: ${summarizeInput.maxWords}")
      _ <- IO.println(s"   Summary: ${summarizeResult.summary}")
      _ <- IO.println(s"   Word count: ${summarizeResult.wordCount}")

      _ <- ExampleUtils.printSubSection("5. Executing Sentiment Tool")
      sentimentInput = SentimentInput("I love this new feature! It's absolutely amazing!")
      sentimentResult <- sentimentTool.execute(sentimentInput)
      _ <- IO.println(s"   Text: ${sentimentInput.text}")
      _ <- IO.println(s"   Sentiment: ${sentimentResult.sentiment}")
      _ <- IO.println(s"   Score: ${sentimentResult.score}")

      // Demonstrate dynamic tool lookup
      _ <- ExampleUtils.printSubSection("6. Dynamic Tool Lookup")
      _ <- IO.println(s"   Looking up 'translate' tool...")
      maybeTool = toolList.find(_.info.name == "translate")
      _ <- maybeTool match
        case Some(tool) =>
          IO.println(s"   Found: ${tool.info.name} - ${tool.info.description}")
        case None =>
          IO.println("   Tool not found")

      _ <- IO.println(s"\n   Tool collection contains ${toolList.size} tools available for LLM use")

      _ <- IO.println("\nDynamic tool registry example completed.")
    yield ()
