# Feature 09: ReAct Agent

## Overview

This document details the implementation of the ReAct (Reasoning + Acting) agent pattern for ADK4S. The ReAct agent loops between LLM reasoning and tool execution until the task is complete.

## Prerequisites

- **Feature 01-08**: All previous features, especially Tools Node and Graph

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| LLM4S | Agent class | Local |
| Workflows4s | WIO loops | Local |

## Design Philosophy

ADK4S ReAct agent:
1. **Wraps LLM4S Agent** - Leverage existing implementation
2. **Adds ADK4S integration** - Works with Graph and Workflows4s
3. **Provides observability hooks** - Step-by-step tracing
4. **Supports customization** - Message modifiers, max steps, etc.

## Implementation Tasks

### Task 1: Create AgentConfig

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/react/AgentConfig.scala`

**API Design**:
```scala
package org.adk4s.agent.react

import cats.effect.IO
import org.llm4s.llmconnect.LLMClient
import org.llm4s.tools.{ToolFunction, ToolRegistry}
import org.adk4s.structured.core.Message
import org.adk4s.agent.tools.{ToolsNode, ToolsNodeConfig}

/**
 * Configuration for ReAct agent.
 */
case class AgentConfig(
  /**
   * LLM client to use for reasoning.
   */
  client: LLMClient,

  /**
   * Tools available to the agent.
   */
  tools: List[ToolFunction] = Nil,

  /**
   * System prompt for the agent.
   */
  systemPrompt: Option[String] = None,

  /**
   * Maximum steps before stopping.
   */
  maxSteps: Int = 12,

  /**
   * Message modifier applied before each LLM call.
   * Can be used to add context, summarize history, etc.
   */
  messageModifier: Option[List[Message] => List[Message]] = None,

  /**
   * ToolsNode configuration for tool execution.
   */
  toolsConfig: ToolsNodeConfig = ToolsNodeConfig(),

  /**
   * Handler called after each step for observability.
   */
  onStep: Option[(Int, AgentStepResult) => IO[Unit]] = None,

  /**
   * Temperature for LLM calls.
   */
  temperature: Option[Double] = None
):
  def toolRegistry: ToolRegistry = ToolRegistry(tools)

object AgentConfig:
  def builder(client: LLMClient): AgentConfigBuilder =
    AgentConfigBuilder(client)

case class AgentConfigBuilder(
  client: LLMClient,
  tools: List[ToolFunction] = Nil,
  systemPrompt: Option[String] = None,
  maxSteps: Int = 12
):
  def withTools(tools: List[ToolFunction]): AgentConfigBuilder =
    copy(tools = tools)

  def withTool(tool: ToolFunction): AgentConfigBuilder =
    copy(tools = this.tools :+ tool)

  def withSystemPrompt(prompt: String): AgentConfigBuilder =
    copy(systemPrompt = Some(prompt))

  def withMaxSteps(max: Int): AgentConfigBuilder =
    copy(maxSteps = max)

  def build: AgentConfig =
    AgentConfig(client, tools, systemPrompt, maxSteps)
```

---

### Task 2: Create Agent State and Step Result

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/react/AgentState.scala`

**API Design**:
```scala
package org.adk4s.agent.react

import org.llm4s.llmconnect.model.{ToolCall, Completion}
import org.adk4s.structured.core.Message
import org.adk4s.agent.tools.ToolOutput

/**
 * Agent status.
 */
enum AgentStatus:
  case Pending      // Not started
  case InProgress   // Running
  case ToolCalling  // Waiting for tool execution
  case Complete     // Finished
  case Failed       // Error occurred

/**
 * Agent state for ReAct loop.
 */
case class AgentState(
  messages: List[Message],
  stepCount: Int = 0,
  pendingToolCalls: List[ToolCall] = Nil,
  status: AgentStatus = AgentStatus.Pending,
  lastCompletion: Option[Completion] = None,
  error: Option[Throwable] = None
):
  def isComplete: Boolean = status == AgentStatus.Complete || status == AgentStatus.Failed
  def hasToolCalls: Boolean = pendingToolCalls.nonEmpty
  def lastMessage: Option[Message] = messages.lastOption

  def addMessage(msg: Message): AgentState =
    copy(messages = messages :+ msg)

  def addMessages(msgs: List[Message]): AgentState =
    copy(messages = messages ++ msgs)

  def incrementStep: AgentState =
    copy(stepCount = stepCount + 1)

  def withStatus(s: AgentStatus): AgentState =
    copy(status = s)

  def withToolCalls(calls: List[ToolCall]): AgentState =
    copy(pendingToolCalls = calls, status = AgentStatus.ToolCalling)

  def clearToolCalls: AgentState =
    copy(pendingToolCalls = Nil, status = AgentStatus.InProgress)

object AgentState:
  def initial(systemPrompt: Option[String], userQuery: String): AgentState =
    val messages = systemPrompt.toList.map(Message(org.adk4s.structured.core.Role.System, _)) ++
      List(Message(org.adk4s.structured.core.Role.User, userQuery))
    AgentState(messages, status = AgentStatus.InProgress)

/**
 * Result of a single agent step.
 */
case class AgentStepResult(
  stepNumber: Int,
  input: List[Message],
  output: Option[Completion],
  toolCalls: List[ToolCall],
  toolResults: List[ToolOutput],
  newState: AgentState
)
```

---

### Task 3: Create ReAct Agent

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/react/ReActAgent.scala`

**API Design**:
```scala
package org.adk4s.agent.react

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, Completion, CompletionOptions}
import org.llm4s.agent.{Agent as Llm4sAgent, AgentState as Llm4sState}
import org.adk4s.structured.core.{Message, Role}
import org.adk4s.agent.tools.{ToolsNode, ToolInput, ToolOutput}
import org.adk4s.core.types.ConversationConverter
import org.adk4s.core.error.{AdkError, MaxStepsExceededError}

/**
 * ReAct agent that loops between reasoning (LLM) and acting (tools).
 */
class ReActAgent(config: AgentConfig):
  private val toolsNode = ToolsNode(config.toolsConfig.copy(
    tools = config.tools.map(Left(_)) ++ config.toolsConfig.tools
  ))

  /**
   * Run agent with query and return final response.
   */
  def run(query: String): IO[String] =
    runToState(query).map { state =>
      state.lastMessage.map(_.content).getOrElse("")
    }

  /**
   * Run agent and return final state.
   */
  def runToState(query: String): IO[AgentState] =
    val initial = AgentState.initial(config.systemPrompt, query)
    loop(initial)

  /**
   * Run agent step by step, streaming results.
   */
  def runStreaming(query: String): Stream[IO, AgentStepResult] =
    val initial = AgentState.initial(config.systemPrompt, query)
    Stream.unfoldEval(initial) { state =>
      if state.isComplete || state.stepCount >= config.maxSteps then
        IO.pure(None)
      else
        step(state).map { result =>
          Some((result, result.newState))
        }
    }

  /**
   * Execute a single step.
   */
  def step(state: AgentState): IO[AgentStepResult] =
    val stepNum = state.stepCount + 1
    for
      // Apply message modifier if present
      messages <- IO.pure(config.messageModifier.fold(state.messages)(_(state.messages)))

      // Call LLM
      completion <- callLLM(messages)

      // Check for tool calls
      newState <- if completion.toolCalls.nonEmpty then
        // Execute tools
        executeTools(state, completion)
      else
        // No tools, mark complete
        IO.pure(
          state
            .addMessage(Message(Role.Assistant, completion.content))
            .withStatus(AgentStatus.Complete)
            .incrementStep
            .copy(lastCompletion = Some(completion))
        )

      result = AgentStepResult(
        stepNumber = stepNum,
        input = messages,
        output = Some(completion),
        toolCalls = completion.toolCalls,
        toolResults = Nil, // TODO: capture tool results
        newState = newState
      )

      // Callback
      _ <- config.onStep.traverse_(_(stepNum, result))
    yield result

  private def loop(state: AgentState): IO[AgentState] =
    if state.isComplete then
      IO.pure(state)
    else if state.stepCount >= config.maxSteps then
      IO.raiseError(MaxStepsExceededError(state.stepCount, config.maxSteps))
    else
      step(state).flatMap(result => loop(result.newState))

  private def callLLM(messages: List[Message]): IO[Completion] =
    val prompt = org.adk4s.structured.core.Prompt(messages.toVector)
    val conversation = ConversationConverter.toConversation(prompt)
    val options = CompletionOptions(
      tools = config.tools,
      temperature = config.temperature
    )
    IO(config.client.complete(conversation, options)).flatMap {
      case Right(completion) => IO.pure(completion)
      case Left(error) => IO.raiseError(new RuntimeException(error.formatted))
    }

  private def executeTools(state: AgentState, completion: Completion): IO[AgentState] =
    for
      results <- toolsNode.executeFromToolCalls(completion.toolCalls)
      toolMessages = results.outputs.map(o => Message(Role.Tool, o.result))
      assistantMsg = Message(Role.Assistant, completion.content)
    yield state
      .addMessage(assistantMsg)
      .addMessages(toolMessages)
      .clearToolCalls
      .incrementStep
      .copy(lastCompletion = Some(completion))

object ReActAgent:
  /**
   * Create agent from config.
   */
  def apply(config: AgentConfig): ReActAgent =
    new ReActAgent(config)

  /**
   * Create agent with simple configuration.
   */
  def simple(
    client: LLMClient,
    tools: List[org.llm4s.tools.ToolFunction],
    systemPrompt: Option[String] = None
  ): ReActAgent =
    new ReActAgent(AgentConfig(client, tools, systemPrompt))

  /**
   * Wrap LLM4S Agent for compatibility.
   */
  def fromLlm4s(agent: Llm4sAgent): ReActAgent =
    ??? // TODO: implement adapter
```

---

### Task 4: Create Agent Graph Pattern

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/react/AgentGraph.scala`

**API Design**:
```scala
package org.adk4s.agent.react

import cats.effect.IO
import org.adk4s.orchestration.graph.Graph
import org.adk4s.orchestration.branch.Branch
import org.adk4s.core.types.NodeKey
import org.llm4s.llmconnect.model.{Completion, ToolCall}

/**
 * Build ReAct agent as a Graph for customization.
 */
object AgentGraph:
  /**
   * Create ReAct graph structure.
   *
   * Structure:
   * START -> chat -> [branch: if tools -> tools -> chat, else -> END]
   */
  def create(config: AgentConfig): IO[Graph[String, String, AgentState]] =
    IO {
      val stateGen = IO(org.adk4s.orchestration.state.StateRef.of(AgentState(Nil)))

      Graph.withState[String, String, AgentState](stateGen)
        // Add chat model node with state handler
        // Add tools node
        // Add branching after chat
        // Configure edges
    }

  /**
   * Branch condition: if tool calls present, go to tools; else end.
   */
  def toolCallBranch: Branch[Completion] =
    Branch.binary(
      completion => IO.pure(completion.toolCalls.nonEmpty),
      ifTrue = NodeKey.unsafeApply("tools"),
      ifFalse = NodeKey.END
    )
```

---

## File Structure

```
adk4s-agent/
└── src/main/scala/org/adk4s/agent/react/
    ├── package.scala
    ├── AgentConfig.scala
    ├── AgentState.scala
    ├── ReActAgent.scala
    └── AgentGraph.scala
```

## Testing Plan

1. Test agent with mock LLM (no tool calls)
2. Test agent with tool calls
3. Test max steps limit
4. Test streaming execution
5. Test message modifier
6. Test step callback

## Examples

### Basic Usage

```scala
val client = LLMClient.create()
val weatherTool = ToolBuilder("get_weather")...build

val agent = ReActAgent(AgentConfig(
  client = client,
  tools = List(weatherTool),
  systemPrompt = Some("You are helpful"),
  maxSteps = 10
))

val result: IO[String] = agent.run("What's the weather in Beijing?")
```

### With Step Tracking

```scala
val config = AgentConfig(
  client = client,
  tools = tools,
  onStep = Some { (step, result) =>
    IO.println(s"Step $step: ${result.newState.status}")
  }
)

val agent = ReActAgent(config)
```

## Completion Criteria

- [ ] AgentConfig complete
- [ ] AgentState with status tracking
- [ ] ReActAgent with loop execution
- [ ] Streaming execution support
- [ ] Graph-based customization
- [ ] Unit tests passing
