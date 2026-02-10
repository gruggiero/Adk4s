# ADK4S Orchestration Guide

## Overview

The ADK4S orchestration module provides a flexible, composable approach to building AI agent workflows. It extends and complements Workflows4s while offering unique capabilities specifically designed for AI agent use cases that require dynamic branching, stateful processing, and runtime-determined routing.

## Core Components

### 1. State Management

#### StateRef
A functional reference for managing mutable state in a pure way:

```scala
import org.adk4s.orchestration.state.StateRef
import cats.effect.IO

case class AgentContext(
  conversationHistory: List[String],
  currentTopic: Option[String],
  retryCount: Int
)

// Create a state reference
val stateRef: IO[StateRef[IO, AgentContext]] = 
  StateRef.of(AgentContext(Nil, None, 0))

// Operations
stateRef.flatMap { ref =>
  for {
    current <- ref.get
    _       <- ref.update(_.copy(retryCount = current.retryCount + 1))
    updated <- ref.get
  } yield updated
}
```

#### StatefulNode
Wraps any `Runnable` with state management through pre/post handlers:

```scala
import org.adk4s.orchestration.state.{StatefulNode, StatefulNodeConfig, StateHandlers}

// Pre-handler: Transform input based on state
val enrichWithContext: PreHandler[String, AgentContext] = (input, stateRef) =>
  stateRef.get.map { ctx =>
    s"Previous context: ${ctx.conversationHistory.lastOption.getOrElse("None")}. Query: $input"
  }

// Post-handler: Update state with output
val recordResponse: PostHandler[String, AgentContext] = (output, stateRef) =>
  stateRef.update { ctx =>
    ctx.copy(conversationHistory = ctx.conversationHistory :+ output)
  }.as(output)

// Wrap an LLM node with state management
val statefulLLM = StatefulNode.wrap(
  llmNode,
  stateRef,
  StatefulNodeConfig(
    preHandler = Some(enrichWithContext),
    postHandler = Some(recordResponse)
  )
)
```

### 2. Branching & Routing

#### Branch ADT
A sealed trait hierarchy for defining routing conditions:

```scala
import org.adk4s.orchestration.branch.{Branch, InvokeBranch, StreamBranch}
import org.adk4s.core.types.NodeKey
import cats.effect.IO

// Binary branch based on predicate
val sentimentBranch = Branch.binary(
  predicate = (text: String) => IO.pure(text.contains("urgent")),
  ifTrue = NodeKey.unsafeApply("priority_handler"),
  ifFalse = NodeKey.unsafeApply("standard_handler")
)

// Pure branch (no IO)
val languageBranch = Branch.pure(
  condition = (text: String) => 
    if text.matches(".*[\\u4e00-\\u9fff].*") then NodeKey.unsafeApply("chinese_processor")
    else NodeKey.unsafeApply("english_processor"),
  targets = Set(
    NodeKey.unsafeApply("chinese_processor"),
    NodeKey.unsafeApply("english_processor")
  )
)

// Stream branch for batch processing
val batchBranch = Branch.stream(
  condition = (items: fs2.Stream[IO, Request]) =>
    items.compile.count.map { count =>
      if count > 100 then NodeKey.unsafeApply("batch_processor")
      else NodeKey.unsafeApply("sequential_processor")
    },
  targets = Set(
    NodeKey.unsafeApply("batch_processor"),
    NodeKey.unsafeApply("sequential_processor")
  )
)

// End-if branch (terminate workflow on condition)
val completionBranch = Branch.endIf(
  predicate = (response: Response) => IO.pure(response.isComplete),
  otherwise = NodeKey.unsafeApply("continue_processing")
)
```

#### Router
Manages multiple branches and determines routing decisions at runtime:

```scala
import org.adk4s.orchestration.branch.Router

// Build a router with multiple branches
val router = Router.empty[AgentInput]
  .addBranch(NodeKey.unsafeApply("classifier"), intentBranch)
  .addBranch(NodeKey.unsafeApply("sentiment_analyzer"), sentimentBranch)
  .addBranch(NodeKey.unsafeApply("validator"), completionBranch)

// Route based on input
val nextNode: IO[NodeKey] = router.route(
  NodeKey.unsafeApply("classifier"),
  AgentInput("Help me with my urgent order")
)

// Route with streaming input
val streamRoute: IO[NodeKey] = router.routeStream(
  NodeKey.unsafeApply("batch_entry"),
  requestStream
)
```

#### WIOBranch (Workflows4s Integration)
Bridges ADK4S branching with Workflows4s WIO:

```scala
import org.adk4s.orchestration.branch.WIOBranch
import workflows4s.wio.WIO

// Binary fork
val priorityWorkflow = WIOBranch.fork(
  condition = (order: Order) => order.amount > 10000,
  ifTrue = highValueOrderWorkflow,
  ifFalse = standardOrderWorkflow
)

// Multi-way branch
val categoryWorkflow = WIOBranch.branch(
  selector = (ticket: Ticket) => ticket.category,
  branches = Map(
    "billing" -> billingWorkflow,
    "technical" -> technicalWorkflow,
    "general" -> generalWorkflow
  ),
  default = fallbackWorkflow
)

// End-if pattern
val completionWorkflow = WIOBranch.endIf(
  condition = (state: AgentState) => state.taskCompleted,
  continueWith = nextStepWorkflow,
  endValue = state.copy(status = "completed")
)
```

## Advantages Over Workflows4s

### 1. Runtime-Determined Routing

**Workflows4s**: Branches must be statically defined at compile time. The `WIO.fork` requires pre-defined branches.

**ADK4S Orchestration**: Supports dynamic routing based on runtime values, including routing decisions from LLM outputs:

```scala
// ADK4S: Dynamic routing based on LLM classification
val dynamicRouter = Router.empty[LLMResponse]
  .addBranch(entryNode, Branch(
    condition = (response: LLMResponse) => 
      classifyIntent(response).map(intent => NodeKey.unsafeApply(intent)),
    targets = allPossibleIntents
  ))
```

### 2. Stream-Based Routing

**Workflows4s**: No native support for routing decisions based on stream characteristics.

**ADK4S Orchestration**: `StreamBranch` can analyze entire streams before making routing decisions:

```scala
// Route based on stream content analysis
val contentAwareBranch = Branch.stream(
  condition = (messages: Stream[IO, Message]) =>
    messages.fold(ContentStats.empty)(_ + _).compile.lastOrError.map { stats =>
      if stats.containsSensitiveData then NodeKey.unsafeApply("secure_handler")
      else NodeKey.unsafeApply("standard_handler")
    },
  targets = Set(secureHandler, standardHandler)
)
```

### 3. Composable State Handlers

**Workflows4s**: State is managed through event sourcing with strict patterns.

**ADK4S Orchestration**: Flexible pre/post handlers that compose freely:

```scala
// Compose multiple handlers
val composedPreHandler = StateHandlers.combinePre(
  enrichWithHistory,
  addUserContext,
  validateInput
)

val composedPostHandler = StateHandlers.combinePost(
  recordResponse,
  updateMetrics,
  checkForEscalation
)
```

### 4. Simpler Integration with Existing Code

**Workflows4s**: Requires wrapping everything in WIO monad with specific patterns.

**ADK4S Orchestration**: Works with any `Runnable` and plain Scala functions:

```scala
// Wrap any existing code
val existingService: String => IO[String] = ???
val runnable = Runnable.fromFunction(existingService)
val stateful = StatefulNode.withPre(runnable, stateRef, myPreHandler)
```

## Use Case: Adaptive Multi-Step Agent

This example demonstrates a use case that is **significantly easier** with ADK4S orchestration than Workflows4s.

### Scenario: Customer Support Agent with Dynamic Routing

A customer support agent that:
1. Classifies incoming requests using an LLM
2. Routes to specialized handlers based on classification
3. Can escalate or redirect mid-conversation based on context
4. Maintains conversation state across multiple turns
5. Uses different routing strategies based on stream characteristics

### Why This Is Difficult in Workflows4s

In Workflows4s, you would need to:
1. Pre-define all possible branches at compile time
2. Use complex event sourcing for state management
3. Cannot easily make routing decisions based on LLM outputs
4. Stream-based routing would require custom implementations
5. Dynamic node selection is not natively supported

### ADK4S Implementation

```scala
package org.adk4s.examples

import cats.effect.IO
import fs2.Stream
import org.adk4s.core.types.NodeKey
import org.adk4s.core.runnable.Runnable
import org.adk4s.orchestration.state.{StateRef, StatefulNode, StatefulNodeConfig}
import org.adk4s.orchestration.state.{PreHandler, PostHandler}
import org.adk4s.orchestration.branch.{Branch, Router, WIOBranch}
import workflows4s.wio.{WIO, WorkflowContext}

// Domain Models
case class CustomerRequest(
  customerId: String,
  message: String,
  priority: Priority,
  channel: Channel
)

sealed trait Priority
case object Urgent extends Priority
case object Normal extends Priority
case object Low extends Priority

sealed trait Channel
case object Chat extends Channel
case object Email extends Channel
case object Phone extends Channel

case class AgentState(
  customerId: String,
  conversationHistory: List[String] = Nil,
  currentIntent: Option[String] = None,
  escalationLevel: Int = 0,
  resolvedTopics: Set[String] = Set.empty,
  metadata: Map[String, String] = Map.empty
)

case class AgentResponse(
  message: String,
  suggestedActions: List[String],
  requiresEscalation: Boolean,
  resolved: Boolean
)

// Step 1: Define Specialized Handlers
object Handlers {
  val billingHandler: Runnable[CustomerRequest, AgentResponse] = 
    Runnable.fromFunction { request =>
      IO.pure(AgentResponse(
        message = s"Processing billing inquiry for ${request.customerId}",
        suggestedActions = List("View invoice", "Payment history"),
        requiresEscalation = false,
        resolved = false
      ))
    }

  val technicalHandler: Runnable[CustomerRequest, AgentResponse] = 
    Runnable.fromFunction { request =>
      IO.pure(AgentResponse(
        message = s"Technical support for ${request.customerId}",
        suggestedActions = List("Run diagnostics", "Schedule callback"),
        requiresEscalation = false,
        resolved = false
      ))
    }

  val escalationHandler: Runnable[CustomerRequest, AgentResponse] = 
    Runnable.fromFunction { request =>
      IO.pure(AgentResponse(
        message = s"Escalating to human agent for ${request.customerId}",
        suggestedActions = List("Transfer to supervisor"),
        requiresEscalation = true,
        resolved = false
      ))
    }

  // LLM-based classifier
  val intentClassifier: Runnable[CustomerRequest, String] = 
    Runnable.fromFunction { request =>
      // In real implementation, this would call an LLM
      IO.pure {
        if request.message.toLowerCase.contains("bill") then "billing"
        else if request.message.toLowerCase.contains("error") then "technical"
        else if request.message.toLowerCase.contains("manager") then "escalation"
        else "general"
      }
    }
}

// Step 2: Define State Handlers
object AgentStateHandlers {
  // Enrich request with conversation context
  val enrichWithHistory: PreHandler[CustomerRequest, AgentState] = 
    (request, stateRef) =>
      stateRef.get.map { state =>
        if state.conversationHistory.nonEmpty then
          request.copy(message = 
            s"Previous context: ${state.conversationHistory.takeRight(3).mkString(" | ")}. " +
            s"Current: ${request.message}"
          )
        else request
      }

  // Track conversation and detect patterns
  val updateConversation: PostHandler[AgentResponse, AgentState] = 
    (response, stateRef) =>
      stateRef.update { state =>
        state.copy(
          conversationHistory = state.conversationHistory :+ response.message,
          escalationLevel = 
            if response.requiresEscalation then state.escalationLevel + 1 
            else state.escalationLevel
        )
      }.as(response)

  // Auto-escalate after too many turns
  val checkEscalation: PostHandler[AgentResponse, AgentState] = 
    (response, stateRef) =>
      stateRef.get.flatMap { state =>
        if state.conversationHistory.length > 10 && !response.resolved then
          IO.pure(response.copy(requiresEscalation = true))
        else IO.pure(response)
      }
}

// Step 3: Build the Orchestration
object CustomerSupportOrchestration {
  
  // Node keys for routing
  val classifierNode = NodeKey.unsafeApply("classifier")
  val billingNode = NodeKey.unsafeApply("billing")
  val technicalNode = NodeKey.unsafeApply("technical")
  val escalationNode = NodeKey.unsafeApply("escalation")
  val generalNode = NodeKey.unsafeApply("general")

  // Dynamic routing based on LLM classification
  def createIntentBranch(classifier: Runnable[CustomerRequest, String]): Branch[CustomerRequest] =
    Branch(
      condition = (request: CustomerRequest) =>
        classifier.invoke(request).map { intent =>
          intent match {
            case "billing" => billingNode
            case "technical" => technicalNode
            case "escalation" => escalationNode
            case _ => generalNode
          }
        },
      targets = Set(billingNode, technicalNode, escalationNode, generalNode)
    )

  // Priority-based routing
  val priorityBranch: Branch[CustomerRequest] = Branch.binary(
    predicate = (request: CustomerRequest) => IO.pure(request.priority == Urgent),
    ifTrue = escalationNode,
    ifFalse = classifierNode
  )

  // Stream-based routing for batch processing
  val batchRoutingBranch: Branch[CustomerRequest] = Branch.stream(
    condition = (requests: Stream[IO, CustomerRequest]) =>
      requests.compile.toList.map { list =>
        val urgentCount = list.count(_.priority == Urgent)
        val totalCount = list.length
        if urgentCount.toDouble / totalCount > 0.5 then escalationNode
        else classifierNode
      },
    targets = Set(escalationNode, classifierNode)
  )

  // Build the complete router
  def buildRouter(classifier: Runnable[CustomerRequest, String]): Router[CustomerRequest] =
    Router.empty[CustomerRequest]
      .addBranch(NodeKey.unsafeApply("entry"), priorityBranch)
      .addBranch(classifierNode, createIntentBranch(classifier))

  // Create stateful handlers
  def createStatefulHandler(
    handler: Runnable[CustomerRequest, AgentResponse],
    stateRef: StateRef[IO, AgentState]
  ): StatefulNode[CustomerRequest, AgentResponse, AgentState] =
    StatefulNode.wrap(
      handler,
      stateRef,
      StatefulNodeConfig(
        preHandler = Some(AgentStateHandlers.enrichWithHistory),
        postHandler = Some(
          StateHandlers.combinePost(
            AgentStateHandlers.updateConversation,
            AgentStateHandlers.checkEscalation
          )
        )
      )
    )

  // Main orchestration loop
  def processRequest(
    request: CustomerRequest,
    stateRef: StateRef[IO, AgentState]
  ): IO[AgentResponse] = {
    val router = buildRouter(Handlers.intentClassifier)
    
    for {
      // First, check priority routing
      entryRoute <- router.route(NodeKey.unsafeApply("entry"), request)
      
      // Then get the specific handler route
      handlerRoute <- 
        if entryRoute == escalationNode then IO.pure(escalationNode)
        else router.route(classifierNode, request)
      
      // Execute the appropriate stateful handler
      response <- handlerRoute match {
        case node if node == billingNode =>
          createStatefulHandler(Handlers.billingHandler, stateRef).invoke(request)
        case node if node == technicalNode =>
          createStatefulHandler(Handlers.technicalHandler, stateRef).invoke(request)
        case node if node == escalationNode =>
          createStatefulHandler(Handlers.escalationHandler, stateRef).invoke(request)
        case _ =>
          createStatefulHandler(Handlers.billingHandler, stateRef).invoke(request) // default
      }
    } yield response
  }

  // Process a batch with stream-based routing
  def processBatch(
    requests: Stream[IO, CustomerRequest],
    stateRef: StateRef[IO, AgentState]
  ): Stream[IO, AgentResponse] = {
    // Analyze the batch first to determine processing strategy
    requests.evalMap(request => processRequest(request, stateRef))
  }
}

// Step 4: Workflows4s Integration for Complex Workflows
object WorkflowIntegration {

  object SupportContext extends WorkflowContext {
    override type State = AgentState
    override type Event = Unit // Simplified for example
  }

  // Use WIOBranch for Workflows4s integration
  val supportWorkflow: WIO[CustomerRequest, Nothing, AgentState, SupportContext.type] =
    WIOBranch.branch(
      selector = (request: CustomerRequest) => request.priority match {
        case Urgent => "urgent"
        case Normal => "normal"
        case Low => "low"
      },
      branches = Map(
        "urgent" -> WIO.build[SupportContext.type]
          .pure.makeFrom[CustomerRequest]
          .value(_ => AgentState("", escalationLevel = 1))
          .done,
        "normal" -> WIO.build[SupportContext.type]
          .pure.makeFrom[CustomerRequest]
          .value(req => AgentState(req.customerId))
          .done,
        "low" -> WIO.build[SupportContext.type]
          .pure.makeFrom[CustomerRequest]
          .value(req => AgentState(req.customerId, metadata = Map("queue" -> "low")))
          .done
      ),
      default = WIO.build[SupportContext.type]
        .pure.makeFrom[CustomerRequest]
        .value(req => AgentState(req.customerId))
        .done
    )

  // End-if for completion detection
  val completionCheck: WIO[AgentState, Nothing, AgentState, SupportContext.type] =
    WIOBranch.endIf(
      condition = (state: AgentState) => state.resolvedTopics.nonEmpty,
      continueWith = WIO.build[SupportContext.type]
        .pure.makeFrom[AgentState]
        .value(identity)
        .done,
      endValue = AgentState("completed")
    )
}
```

### Key Differences Highlighted

| Feature | Workflows4s | ADK4S Orchestration |
|---------|-------------|---------------------|
| **Runtime routing** | Static branches only | Dynamic routing via `Branch` with `IO[NodeKey]` |
| **LLM-driven decisions** | Requires complex workarounds | Native support in branch conditions |
| **Stream analysis** | Not supported | `StreamBranch` analyzes before routing |
| **State handlers** | Event sourcing only | Composable pre/post handlers |
| **Integration** | Requires WIO wrapping | Works with any `Runnable` |
| **Complexity** | Higher for dynamic cases | Lower, more intuitive |

## Conclusion

ADK4S orchestration provides a powerful, flexible approach to AI agent workflow management. While Workflows4s excels at static, well-defined workflows with event sourcing requirements, ADK4S orchestration shines when:

- Routing decisions depend on runtime values (especially LLM outputs)
- Stream characteristics determine processing paths
- State management needs to be lightweight and composable
- Integration with existing code should be seamless
- Dynamic, adaptive agent behaviors are required

Use ADK4S orchestration for AI agents that need to adapt their behavior based on context, and Workflows4s when you need strict event sourcing and static workflow definitions.
