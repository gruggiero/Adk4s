package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import org.adk4s.core.component.{Agent, AgentTool, AgentToolConfig, ChatModel, ChatModelConfig, InvokableTool, Tool}
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, UserMessage}

import java.util.UUID

/**
 * Demonstrates AgentTool: wrapping a sub-agent as an InvokableTool so a parent agent
 * can invoke it via tool calls.
 *
 * Pattern: Supervisor agent delegates to a "database-agent" sub-agent.
 */
object AgentToolExample extends IOApp.Simple:

  /** A mock sub-agent that simulates database queries. */
  private val dbAgent: Agent = new Agent:
    val name: String = "database-agent"
    val description: String = "Handles database queries and returns structured results"
    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      val query: String = messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")
      val result: String =
        if query.contains("user") then """{"users": [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]}"""
        else if query.contains("order") then """{"orders": [{"id": 101, "total": 49.99}]}"""
        else s"""{"result": "No data found for query: ${query.take(50)}"}"""
      IO.pure(AssistantMessage(contentOpt = Some(result), toolCalls = Seq.empty))

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("AgentTool Example: Supervisor + Database Agent")

      // Create the AgentTool wrapping the database agent 
      agentTool <- AgentTool.fromAgent(dbAgent)
      _ <- IO.println(s"Created AgentTool: name=${agentTool.info.name}, desc=${agentTool.info.description}")

      // Invoke the agent tool as if the LLM decided to call it
      _ <- ExampleUtils.printSubSection("Invoking agent-tool with user query request")
      result1 <- agentTool.run(ujson.Obj("request" -> "Find all users in the system"))
      _ <- IO.println(s"Agent tool result: $result1")

      _ <- ExampleUtils.printSubSection("Invoking agent-tool with order query")
      result2 <- agentTool.run(ujson.Obj("request" -> "Show recent orders"))
      _ <- IO.println(s"Agent tool result: $result2")

      _ <- IO.println("\nAgentTool example complete.")
    yield ()
