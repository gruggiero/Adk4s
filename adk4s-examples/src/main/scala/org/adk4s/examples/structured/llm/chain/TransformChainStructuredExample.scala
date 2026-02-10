package org.adk4s.examples.structured.llm.chain

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.TypedIntermediate

/**
 * Demonstrates staged transformation chains with StructuredLLM.
 *
 * Shows how to:
 * - Build multi-stage transformation pipelines
 * - Transform data through progressive refinement stages
 * - Use TypedIntermediate to track transformation progress
 * - Compose extract → transform → load (ETL) patterns
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object TransformChainStructuredExample extends IOApp.Simple:

  // Schema[A] instance wrapping Smithy-generated schema
  given Schema[TypedIntermediate] = Schema.instance(
    """structure TypedIntermediate {
      |  @required
      |  stage: String
      |  @required
      |  result: String
      |  @required
      |  nextAction: String
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[TypedIntermediate]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Transform Chain (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example: Data transformation pipeline
      _ <- ExampleUtils.printSubSection("Data Transformation Pipeline")
      rawData = """
        Product: Laptop Pro X1
        Price: $1299.99
        Stock: 42 units
        Rating: 4.7/5 (238 reviews)
        Tags: electronics, computers, premium
      """

      _ <- IO.println("   Raw product data:")
      _ <- IO.println(rawData)

      // Stage 1: Extract
      _ <- IO.println("\n   Stage 1: Extraction...")
      extractPrompt = Prompt.simple(
        "You are at the 'extraction' stage. Extract key information from the raw data. IMPORTANT: The 'result' field must be a STRING (not a nested object). Return stage='extraction', result as a string representation of extracted data, and nextAction='transform'.",
        s"Raw data: $rawData"
      )
      extracted <- structured.complete[TypedIntermediate](extractPrompt)
      _ <- IO.println(s"   → Stage: ${extracted.stage}")
      _ <- IO.println(s"   → Extracted: ${extracted.result}")
      _ <- IO.println(s"   → Next: ${extracted.nextAction}")

      // Stage 2: Transform (using extracted data)
      _ <- IO.println("\n   Stage 2: Transformation...")
      transformPrompt = Prompt.simple(
        "You are at the 'transformation' stage. Transform the extracted data into a structured format. IMPORTANT: The 'result' field must be a STRING (not a nested object). Return stage='transformation', result as a string representation of transformed data, and nextAction='normalize'.",
        s"Extracted data: ${extracted.result}"
      )
      transformed <- structured.complete[TypedIntermediate](transformPrompt)
      _ <- IO.println(s"   → Stage: ${transformed.stage}")
      _ <- IO.println(s"   → Transformed: ${transformed.result}")
      _ <- IO.println(s"   → Next: ${transformed.nextAction}")

      // Stage 3: Normalize (using transformed data)
      _ <- IO.println("\n   Stage 3: Normalization...")
      normalizePrompt = Prompt.simple(
        "You are at the 'normalization' stage. Normalize the transformed data. IMPORTANT: The 'result' field must be a STRING (not a nested object). Return stage='normalization', result as a string representation of normalized data, and nextAction='enrich'.",
        s"Transformed data: ${transformed.result}"
      )
      normalized <- structured.complete[TypedIntermediate](normalizePrompt)
      _ <- IO.println(s"   → Stage: ${normalized.stage}")
      _ <- IO.println(s"   → Normalized: ${normalized.result}")
      _ <- IO.println(s"   → Next: ${normalized.nextAction}")

      // Stage 4: Enrich (using normalized data)
      _ <- IO.println("\n   Stage 4: Enrichment...")
      enrichPrompt = Prompt.simple(
        "You are at the 'enrichment' stage. Enrich the data with additional metadata. IMPORTANT: The 'result' field must be a STRING (not a nested object). Return stage='enrichment', result as a string representation of enriched data, and nextAction='complete'.",
        s"Normalized data: ${normalized.result}"
      )
      enriched <- structured.complete[TypedIntermediate](enrichPrompt)
      _ <- IO.println(s"   → Stage: ${enriched.stage}")
      _ <- IO.println(s"   → Enriched: ${enriched.result}")
      _ <- IO.println(s"   → Next: ${enriched.nextAction}")

      _ <- IO.println("\n   Transformation pipeline complete!")
      _ <- IO.println(s"   Final output: ${enriched.result}")

      _ <- IO.println("\nTransform chain example completed.")
    yield ()
