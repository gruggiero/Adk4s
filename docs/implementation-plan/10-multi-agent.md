# Feature 10: Multi-Agent Coordination

## Overview

This document details the implementation of multi-agent coordination patterns for ADK4S, enabling multiple agents to work together on complex tasks.

## Prerequisites

- **Feature 09**: ReAct Agent

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| Workflows4s | WIO composition | Local |
| Cats Effect | Concurrent execution | 3.6.3 |

## Design Philosophy

ADK4S multi-agent patterns:
1. **Agent Handoff** - Sequential delegation between agents
2. **Supervisor/Worker** - Central agent coordinating workers
3. **Parallel Execution** - Multiple agents working concurrently
4. **Conversation Routing** - Dynamic routing to specialized agents

## Implementation Tasks

### Task 1: Create Agent Registry

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/multi/AgentRegistry.scala`

**API Design**:
```scala
package org.adk4s.agent.multi

import cats.effect.IO
import org.adk4s.agent.react.ReActAgent

/**
 * Agent metadata for registration.
 */
case class AgentInfo(
  name: String,
  description: String,
  capabilities: Set[String] = Set.empty
)

/**
 * Registry of available agents.
 */
case class AgentRegistry(
  agents: Map[String, (AgentInfo, ReActAgent)]
):
  def register(info: AgentInfo, agent: ReActAgent): AgentRegistry =
    copy(agents = agents + (info.name -> (info, agent)))

  def get(name: String): Option[ReActAgent] =
    agents.get(name).map(_._2)

  def getInfo(name: String): Option[AgentInfo] =
    agents.get(name).map(_._1)

  def listAgents: List[AgentInfo] =
    agents.values.map(_._1).toList

  def findByCapability(capability: String): List[(AgentInfo, ReActAgent)] =
    agents.values.filter(_._1.capabilities.contains(capability)).toList

object AgentRegistry:
  def empty: AgentRegistry = AgentRegistry(Map.empty)

  def of(entries: (AgentInfo, ReActAgent)*): AgentRegistry =
    entries.foldLeft(empty) { case (reg, (info, agent)) =>
      reg.register(info, agent)
    }
```

---

### Task 2: Create Agent Handoff Pattern

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/multi/AgentHandoff.scala`

**API Design**:
```scala
package org.adk4s.agent.multi

import cats.effect.IO
import cats.syntax.all.*
import org.adk4s.agent.react.{ReActAgent, AgentState}

/**
 * Handoff request from one agent to another.
 */
case class HandoffRequest(
  targetAgent: String,
  context: String,
  originalQuery: String
)

/**
 * Agent handoff pattern - sequential delegation.
 */
object AgentHandoff:
  /**
   * Create a handoff tool that delegates to another agent.
   */
  def handoffTool(registry: AgentRegistry): org.llm4s.tools.ToolFunction =
    org.llm4s.tools.ToolBuilder("handoff_to_agent")
      .description("Delegate the conversation to a specialized agent")
      .parameter("agent_name", org.llm4s.tools.StringSchema("Name of target agent"))
      .parameter("context", org.llm4s.tools.StringSchema("Context for the handoff"))
      .handler { params =>
        val name = params.getOrElse("agent_name", "").toString
        val context = params.getOrElse("context", "").toString
        registry.get(name) match
          case Some(_) => Right(s"Handoff to $name initiated with context: $context")
          case None => Left(s"Agent '$name' not found. Available: ${registry.listAgents.map(_.name).mkString(", ")}")
      }
      .build

  /**
   * Execute handoff chain.
   */
  def executeHandoffChain(
    registry: AgentRegistry,
    initialAgent: String,
    query: String,
    maxHandoffs: Int = 3
  ): IO[String] =
    def loop(currentAgent: String, currentQuery: String, handoffs: Int): IO[String] =
      if handoffs >= maxHandoffs then
        IO.pure(s"Max handoffs reached ($maxHandoffs)")
      else
        registry.get(currentAgent) match
          case Some(agent) =>
            agent.run(currentQuery).flatMap { result =>
              // Check if result contains handoff instruction
              parseHandoff(result) match
                case Some(handoff) =>
                  loop(handoff.targetAgent, handoff.context, handoffs + 1)
                case None =>
                  IO.pure(result)
            }
          case None =>
            IO.raiseError(new RuntimeException(s"Agent '$currentAgent' not found"))

    loop(initialAgent, query, 0)

  private def parseHandoff(result: String): Option[HandoffRequest] =
    // Simple parsing - in production, use structured output
    None
```

---

### Task 3: Create Supervisor/Worker Pattern

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/multi/SupervisorAgent.scala`

**API Design**:
```scala
package org.adk4s.agent.multi

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import org.adk4s.agent.react.{ReActAgent, AgentConfig}
import org.llm4s.llmconnect.LLMClient

/**
 * Supervisor agent that coordinates worker agents.
 */
class SupervisorAgent(
  client: LLMClient,
  workers: AgentRegistry,
  systemPrompt: String
):
  /**
   * Run supervisor with automatic worker delegation.
   */
  def run(query: String): IO[String] =
    // 1. Supervisor analyzes query
    // 2. Decides which workers to invoke
    // 3. Collects and synthesizes results
    ???

  /**
   * Get supervisor's analysis of which workers to use.
   */
  def planExecution(query: String): IO[ExecutionPlan] =
    ???

/**
 * Execution plan from supervisor.
 */
case class ExecutionPlan(
  steps: List[ExecutionStep],
  synthesisStrategy: String
)

sealed trait ExecutionStep
case class DelegateToWorker(worker: String, subQuery: String) extends ExecutionStep
case class ParallelWorkers(workers: List[(String, String)]) extends ExecutionStep
case class SynthesizeResults(workerOutputs: List[String]) extends ExecutionStep

object SupervisorAgent:
  def apply(
    client: LLMClient,
    workers: AgentRegistry,
    systemPrompt: String = "You are a supervisor agent coordinating specialized workers."
  ): SupervisorAgent =
    new SupervisorAgent(client, workers, systemPrompt)
```

---

### Task 4: Create Parallel Agent Execution

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/multi/ParallelAgents.scala`

**API Design**:
```scala
package org.adk4s.agent.multi

import cats.effect.IO
import cats.syntax.all.*
import org.adk4s.agent.react.ReActAgent

/**
 * Execute multiple agents in parallel.
 */
object ParallelAgents:
  /**
   * Run agents in parallel with same query.
   */
  def runAll(
    agents: List[ReActAgent],
    query: String
  ): IO[List[String]] =
    agents.parTraverse(_.run(query))

  /**
   * Run agents in parallel with different queries.
   */
  def runWithQueries(
    agentsAndQueries: List[(ReActAgent, String)]
  ): IO[List[String]] =
    agentsAndQueries.parTraverse { case (agent, query) =>
      agent.run(query)
    }

  /**
   * Run agents and collect first successful result.
   */
  def raceAgents(
    agents: List[ReActAgent],
    query: String
  ): IO[String] =
    IO.race(agents.map(_.run(query))*).map {
      case results => results.head
    }

  /**
   * Run agents with consensus (majority voting).
   */
  def consensus(
    agents: List[ReActAgent],
    query: String,
    threshold: Double = 0.5
  ): IO[Option[String]] =
    runAll(agents, query).map { results =>
      val grouped = results.groupBy(identity).view.mapValues(_.size).toMap
      val majority = (agents.size * threshold).ceil.toInt
      grouped.find(_._2 >= majority).map(_._1)
    }
```

---

### Task 5: Create Conversation Router

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/multi/ConversationRouter.scala`

**API Design**:
```scala
package org.adk4s.agent.multi

import cats.effect.IO
import org.llm4s.llmconnect.LLMClient
import org.adk4s.agent.react.ReActAgent

/**
 * Route conversations to specialized agents.
 */
class ConversationRouter(
  classifier: LLMClient,
  registry: AgentRegistry
):
  /**
   * Route query to appropriate agent.
   */
  def route(query: String): IO[String] =
    for
      agentName <- classify(query)
      agent <- IO.fromOption(registry.get(agentName))(
        new RuntimeException(s"Agent '$agentName' not found")
      )
      result <- agent.run(query)
    yield result

  /**
   * Classify query to determine target agent.
   */
  def classify(query: String): IO[String] =
    // Use classifier LLM to determine routing
    ???

object ConversationRouter:
  def apply(classifier: LLMClient, registry: AgentRegistry): ConversationRouter =
    new ConversationRouter(classifier, registry)

  /**
   * Create router with keyword-based classification.
   */
  def keywordBased(
    registry: AgentRegistry,
    keywords: Map[String, Set[String]]
  ): ConversationRouter =
    ???
```

---

## File Structure

```
adk4s-agent/
└── src/main/scala/org/adk4s/agent/multi/
    ├── package.scala
    ├── AgentRegistry.scala
    ├── AgentHandoff.scala
    ├── SupervisorAgent.scala
    ├── ParallelAgents.scala
    └── ConversationRouter.scala
```

## Testing Plan

1. Test agent registry operations
2. Test handoff chain execution
3. Test parallel agent execution
4. Test consensus mechanism
5. Test conversation routing

## Completion Criteria

- [ ] AgentRegistry complete
- [ ] Handoff pattern implemented
- [ ] Supervisor/Worker pattern
- [ ] Parallel execution
- [ ] Conversation router
- [ ] Unit tests passing
