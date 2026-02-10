package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import cats.syntax.traverse.toTraverseOps
import org.adk4s.core.component.{Agent, AgentTool, AgentToolConfig}
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.llmconnect.model.{AssistantMessage, Message, UserMessage}

/**
 * Demonstrates advanced AgentTool features.
 *
 * This example showcases:
 * - AgentTool.fromFunction factory method
 * - AgentTool.fromReactAgent alias
 * - Custom input schemas via AgentToolConfig
 * - Different tool creation patterns
 * - Tool info customization
 *
 * GAP RESOLVED: Agent-as-Tool with Advanced Configuration
 * Before: Only basic agent wrapping supported
 * After: Multiple factory methods, custom schemas, flexible configuration
 */
object AgentToolAdvancedExample extends IOApp.Simple:

  // Example 1: Using fromFunction for simple functional agents
  private def createAnalyzerTool: IO[AgentTool] =
    val analyzerFn: List[Message] => IO[String] = { (messages: List[Message]) =>
      val text: String = messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")

      // Simple sentiment analysis
      val sentiment: String =
        if text.toLowerCase.contains("great") || text.toLowerCase.contains("excellent") then "positive"
        else if text.toLowerCase.contains("bad") || text.toLowerCase.contains("terrible") then "negative"
        else "neutral"

      val wordCount: Int = text.split("\\s+").length

      IO.pure(s"""Analysis Results:
                 |  - Sentiment: $sentiment
                 |  - Word Count: $wordCount
                 |  - Text Length: ${text.length} characters
                 |""".stripMargin)
    }

    AgentTool.fromFunction(
      agentName = "text-analyzer",
      agentDescription = "Analyzes text for sentiment and basic metrics",
      fn = analyzerFn
    )

  // Example 2: Using fromReactAgent alias (equivalent to fromAgent)
  private def createTranslatorTool: IO[AgentTool] =
    val translatorAgent: Agent = new Agent:
      val name: String = "translator"
      val description: String = "Translates text between languages"

      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        val request: String = messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")

        // Mock translation (in reality, would call translation API)
        val translated: String =
          if request.contains("hello") then "hola (Spanish)"
          else if request.contains("goodbye") then "adiós (Spanish)"
          else s"[Translated: $request]"

        IO.pure(AssistantMessage(
          contentOpt = Some(translated),
          toolCalls = Seq.empty
        ))

    AgentTool.fromReactAgent(translatorAgent)

  // Example 3: Custom input schema with structured parameters
  private def createCalculatorTool: IO[AgentTool] =
    val calculatorAgent: Agent = new Agent:
      val name: String = "calculator"
      val description: String = "Performs mathematical calculations"

      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        val request: String = messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")

        // Extract numbers and operation from request
        val result: String =
          if request.contains("add") || request.contains("+") then
            "Result: 42 (addition performed)"
          else if request.contains("multiply") || request.contains("*") then
            "Result: 84 (multiplication performed)"
          else
            "Unable to parse calculation"

        IO.pure(AssistantMessage(
          contentOpt = Some(result),
          toolCalls = Seq.empty
        ))

    // Define custom input schema
    val customSchema: ujson.Value = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "operation" -> ujson.Obj(
          "type" -> "string",
          "description" -> "The mathematical operation to perform",
          "enum" -> ujson.Arr("add", "subtract", "multiply", "divide")
        ),
        "operand1" -> ujson.Obj(
          "type" -> "number",
          "description" -> "First operand"
        ),
        "operand2" -> ujson.Obj(
          "type" -> "number",
          "description" -> "Second operand"
        )
      ),
      "required" -> ujson.Arr("operation", "operand1", "operand2")
    )

    val config: AgentToolConfig = AgentToolConfig.withInputSchema(customSchema)

    AgentTool.fromAgent(calculatorAgent, config)

  // Example 4: Default schema (simple request field)
  private def createSummarizerTool: IO[AgentTool] =
    val summarizerAgent: Agent = new Agent:
      val name: String = "summarizer"
      val description: String = "Summarizes long text into key points"

      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        val text: String = messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")

        val summary: String =
          if text.length > 100 then
            s"Summary: ${text.take(50)}... (${text.split("\\s+").length} words total)"
          else
            s"Summary: $text (already concise)"

        IO.pure(AssistantMessage(
          contentOpt = Some(summary),
          toolCalls = Seq.empty
        ))

    AgentTool.fromAgent(summarizerAgent) // Uses default config

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Advanced AgentTool Features Example")

      // Demonstrate fromFunction
      _ <- ExampleUtils.printSubSection("1. AgentTool.fromFunction (functional agent)")
      analyzerTool <- createAnalyzerTool
      _ <- IO.println(s"   Created: ${analyzerTool.info.name}")
      _ <- IO.println(s"   Description: ${analyzerTool.info.description}")
      _ <- IO.println(s"   Schema: Has 'request' field (default)")
      _ <- IO.println("")
      _ <- IO.println("   Testing analyzer:")
      result1 <- analyzerTool.run(ujson.Obj("request" -> "This product is excellent and works great!"))
      _ <- IO.println(s"   Result:\n${result1.str.split("\n").map("     " + _).mkString("\n")}")

      // Demonstrate fromReactAgent alias
      _ <- ExampleUtils.printSubSection("2. AgentTool.fromReactAgent (alias)")
      translatorTool <- createTranslatorTool
      _ <- IO.println(s"   Created: ${translatorTool.info.name}")
      _ <- IO.println(s"   Description: ${translatorTool.info.description}")
      _ <- IO.println("")
      _ <- IO.println("   Testing translator:")
      result2 <- translatorTool.run(ujson.Obj("request" -> "hello"))
      _ <- IO.println(s"   Result: ${result2.str}")

      // Demonstrate custom input schema
      _ <- ExampleUtils.printSubSection("3. Custom Input Schema (structured parameters)")
      calculatorTool <- createCalculatorTool
      _ <- IO.println(s"   Created: ${calculatorTool.info.name}")
      _ <- IO.println(s"   Description: ${calculatorTool.info.description}")
      _ <- IO.println("   Schema: Custom with operation, operand1, operand2 fields")
      _ <- IO.println("")
      _ <- IO.println("   Schema structure:")
      schemaProps = calculatorTool.info.parameters.obj("properties").obj
      _ <- schemaProps.keys.toList.traverse { (key: String) =>
        val propDesc: String = schemaProps(key).obj.get("description").map(_.str).getOrElse("(no description)")
        IO.println(s"     - $key: $propDesc")
      }
      _ <- IO.println("")
      _ <- IO.println("   Testing calculator:")
      result3 <- calculatorTool.run(ujson.Obj(
        "operation" -> "multiply",
        "operand1" -> 6,
        "operand2" -> 7
      ))
      _ <- IO.println(s"   Result: ${result3.str}")

      // Demonstrate default schema
      _ <- ExampleUtils.printSubSection("4. Default Schema (simple request field)")
      summarizerTool <- createSummarizerTool
      _ <- IO.println(s"   Created: ${summarizerTool.info.name}")
      _ <- IO.println(s"   Description: ${summarizerTool.info.description}")
      _ <- IO.println("   Schema: Default with 'request' field")
      _ <- IO.println("")
      _ <- IO.println("   Default schema structure:")
      defaultProps = summarizerTool.info.parameters.obj("properties").obj
      _ <- defaultProps.keys.toList.traverse { (key: String) =>
        val propDesc: String = defaultProps(key).obj.get("description").map(_.str).getOrElse("(no description)")
        IO.println(s"     - $key: $propDesc")
      }
      _ <- IO.println("")
      _ <- IO.println("   Testing summarizer:")
      result4 <- summarizerTool.run(ujson.Obj("request" ->
        "Artificial intelligence has made tremendous progress in recent years. Machine learning models can now perform complex tasks that were once thought impossible. The field continues to evolve rapidly with new breakthroughs happening regularly."
      ))
      _ <- IO.println(s"   Result: ${result4.str}")

      _ <- IO.println("")
      _ <- IO.println("Factory Method Comparison:")
      _ <- IO.println("  • fromFunction: Best for simple stateless transformations")
      _ <- IO.println("  • fromReactAgent: Alias for fromAgent, explicit naming")
      _ <- IO.println("  • fromAgent: Full control, works with any Agent implementation")
      _ <- IO.println("")
      _ <- IO.println("Schema Options:")
      _ <- IO.println("  • Default: Single 'request' string field (simple)")
      _ <- IO.println("  • Custom: Structured parameters with types and validation")
      _ <- IO.println("  • Future: withFullChatHistory (not yet functional)")

      _ <- IO.println("\nAdvanced AgentTool example complete.")
    yield ()
