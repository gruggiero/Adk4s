# Agent Orchestration Examples

This directory contains examples demonstrating the **agent orchestration features** that resolve critical gaps in adk4s. These features enable:

1. **Agent-as-Tool (AgentTool)** - Agents can be used as tools, enabling hierarchical agent delegation
2. **Interrupt/Resume** - Agents can interrupt for human approval and resume from checkpoints
3. **Event Streaming** - Real-time event stream showing agent execution with hierarchical RunPath tracking

All examples use mock models by default and don't require API keys.

---

## Quick Start

Run any example using the runner script:

```bash
cd adk4s-examples
./run-example.sh nestedagent          # Multi-level agent delegation
./run-example.sh statefulresume       # Stateful interrupt/resume
./run-example.sh hierarchicalevents   # Event streaming with RunPath
```

Or run directly via sbt:

```bash
sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.NestedAgentDelegationExample"
```

---

## Examples Overview

### 📦 Basic Examples (Foundation)

#### 1. **AgentToolExample** (`agenttool`)
**Gap Resolved**: Agent-as-Tool (AgentTool) - Basic

Demonstrates the fundamental AgentTool pattern:
- Wrapping a sub-agent as an InvokableTool
- Parent agent delegating to sub-agent via tool call
- Tool info extraction from inner agent

**Key Features**:
- ✅ Basic agent wrapping with `AgentTool.fromAgent`
- ✅ Tool invocation with request field
- ✅ Simple delegation pattern

**Run**: `./run-example.sh agenttool`

---

#### 2. **InterruptResumeExample** (`interruptresume`)
**Gap Resolved**: Interrupt/Resume - Basic

Demonstrates the basic interrupt/resume workflow:
- Tool interrupts requesting approval
- State persisted to checkpoint
- Resume with approval data
- Agent continues from interrupt point

**Key Features**:
- ✅ Simple interrupt signal creation
- ✅ Checkpoint save/load via InMemoryCheckpointStore
- ✅ Basic resume with InterruptResult
- ✅ AgentRunner lifecycle management

**Run**: `./run-example.sh interruptresume`

---

#### 3. **EventStreamExample** (`eventstream`)
**Gap Resolved**: Event Streaming - Basic

Demonstrates basic event streaming:
- ReactAgent with optional AgentEventEmitter
- Real-time event consumption via fs2.Stream
- Event types: ToolCallRequested, ToolCallCompleted, MessageOutput, IterationCompleted

**Key Features**:
- ✅ Event emitter creation and subscription
- ✅ Concurrent event consumption with resultIO
- ✅ Event type classification and counting
- ✅ Stream completion handling

**Run**: `./run-example.sh eventstream`

---

### 🚀 Advanced Examples (Full Capabilities)

#### 4. **NestedAgentDelegationExample** (`nestedagent`)
**Gap Resolved**: Agent-as-Tool - Multi-level Hierarchy

Demonstrates **3-level nested agent delegation**:
```
Supervisor Agent
└─ Data Specialist Agent (via AgentTool)
   └─ Query Sub-specialist Agent (via nested AgentTool)
      └─ Executes query and returns results
```

**Key Features**:
- ✅ Multi-level agent hierarchy (3 levels deep)
- ✅ Each level adds value (routing → processing → execution)
- ✅ Address tracking through nested calls
- ✅ Independent agent teams composed together
- ✅ Clean separation of concerns

**Architecture**:
1. **Supervisor**: Routes requests to specialist teams
2. **Data Specialist**: Delegates to sub-specialists based on query type
3. **Query Specialist**: Executes actual database queries

**Use Case**: Large organizations with specialized teams where requests flow through multiple delegation layers.

**Run**: `./run-example.sh nestedagent`

**Output Highlights**:
```
1. Supervisor received request
2. Supervisor → delegated to Data Specialist
3. Data Specialist → delegated to Query Specialist
4. Query Specialist → executed query
5. Results bubbled back through hierarchy
```

---

#### 5. **CompositeInterruptExample** (`compositeinterrupt`)
**Gap Resolved**: Interrupt/Resume - Multiple Simultaneous Interrupts

Demonstrates handling **multiple tool interrupts** in one batch:
- Agent calls payment_tool and email_tool
- Both tools interrupt (payment needs approval, email needs verification)
- Sequential execution stops on first interrupt
- Resume provides multiple InterruptResults

**Key Features**:
- ✅ Sequential execution with interrupt handling
- ✅ Stateful interrupts carrying context data
- ✅ Multiple InterruptResults in resume
- ✅ Address-based interrupt identification
- ✅ Composite interrupt signal structure

**Scenario**:
```
User: Process invoice #12345 - Pay $5000 to ACME Corp and notify CFO
Agent: *calls process_payment tool*
Tool: Interrupts (exceeds $1000 threshold)
[State saved, checkpoint created]
Human: Approves payment ✓
Agent: *resumes and continues to send_email*
```

**Run**: `./run-example.sh compositeinterrupt`

**Note**: True composite interrupts (multiple simultaneous) require parallel execution mode. This example demonstrates sequential execution which stops on first interrupt, preserving safety guarantees.

---

#### 6. **AgentToolAdvancedExample** (`agenttooladvanced`)
**Gap Resolved**: Agent-as-Tool - Advanced Configuration

Demonstrates **all AgentTool factory methods and configuration options**:

**Factory Methods**:
1. **`fromFunction`**: Create agent from simple function `List[Message] => IO[String]`
2. **`fromReactAgent`**: Alias for `fromAgent` with explicit naming
3. **`fromAgent`**: Full control with any Agent implementation

**Configuration Options**:
1. **Default Schema**: Simple `request` field (string)
2. **Custom Schema**: Structured parameters with types and validation
3. **Input Schema Customization**: Via `AgentToolConfig.withInputSchema`

**Key Features**:
- ✅ Four agent creation patterns demonstrated
- ✅ Schema comparison (default vs. custom)
- ✅ Tool info customization
- ✅ Functional agent pattern (stateless transformations)

**Examples**:
- **Text Analyzer**: Using `fromFunction` for sentiment analysis
- **Translator**: Using `fromReactAgent` alias
- **Calculator**: Custom structured schema with operation/operands
- **Summarizer**: Default schema with simple request field

**Run**: `./run-example.sh agenttooladvanced`

**When to Use**:
- `fromFunction`: Simple stateless transformations
- `fromReactAgent`: Explicit naming, works with ReactAgent
- `fromAgent`: Full control, works with any Agent implementation
- Custom Schema: Need structured, validated input parameters

---

#### 7. **HierarchicalEventStreamExample** (`hierarchicalevents`)
**Gap Resolved**: Event Streaming - Hierarchical RunPath Tracking

Demonstrates **event flow through nested agents with RunPath hierarchy**:

**Architecture**:
```
Orchestrator Agent
└─ Research Agent (via AgentTool)
   └─ Search Tool
```

**Event Flow**:
```
[ToolCallRequested] @ (root) (tool=research-agent)
  [ToolCallRequested] @ research-agent (tool=search)
  [ToolCallCompleted] @ research-agent (tool=search)
  [IterationCompleted] @ research-agent
  [MessageOutput] @ research-agent
[ToolCallCompleted] @ (root) (tool=research-agent)
[MessageOutput] @ (root)
```

**Key Features**:
- ✅ Events flow through nested agent boundaries
- ✅ RunPath shows execution context hierarchy
- ✅ Event forwarding from AgentTool to parent emitter
- ✅ Complete visibility: outer + inner agent events
- ✅ Real-time observability into agent internals

**Observability Benefits**:
- Track execution flow across agent hierarchy
- Debug nested agent interactions
- Monitor tool call patterns
- Analyze performance per-agent
- Build execution traces for audit

**Run**: `./run-example.sh hierarchicalevents`

**Output Analysis**:
```
Event Statistics:
  ToolCallRequested: 2
  ToolCallCompleted: 2
  IterationCompleted: 2
  MessageOutput: 2
  TOTAL: 8 events

Hierarchy Analysis:
  (root): 4 events
  research-agent: 4 events
```

---

#### 8. **StatefulResumeExample** (`statefulresume`)
**Gap Resolved**: Interrupt/Resume - State Persistence

Demonstrates **stateful interrupt/resume with complete state preservation**:

**Scenario**: 3-step data migration workflow
```
Step 1: Validate data (completes) ✓
Step 2: Request approval (interrupts) ⏸
        [State saved: messages + iteration count]
        [Human provides approval] 👤
Step 3: Execute migration (resumes) ▶️
```

**State Preserved**:
- ✅ Conversation history (all messages)
- ✅ Iteration count (workflow progress)
- ✅ Inner agent state (AgentToolState)
- ✅ Tool invocation context

**Key Features**:
- ✅ Multi-step workflow with progress tracking
- ✅ Stateful interrupt with context data
- ✅ State persistence at interrupt point
- ✅ State restoration on resume
- ✅ Workflow continues from exact interruption point
- ✅ AgentTool state (messages + iteration) saved/restored

**Interrupt Signal Data**:
```json
{
  "dataset": "customer_profiles",
  "destination": "cloud_warehouse",
  "recordCount": 1000,
  "currentStep": 2,
  "totalSteps": 3,
  "validationPassed": true
}
```

**Run**: `./run-example.sh statefulresume`

**Use Case**: Long-running workflows that require human approval gates at specific steps, with ability to resume exactly where they left off even after process restart (with persistent CheckpointStore).

---

## Gap Resolution Summary

### Before Agent Orchestration Features

**Problem 1: No Agent Delegation**
- ❌ Agents couldn't delegate to other agents
- ❌ Flat agent architecture only
- ❌ No hierarchical agent teams

**Problem 2: No Interrupt/Resume**
- ❌ No human-in-the-loop workflows
- ❌ All-or-nothing execution (complete or fail)
- ❌ No approval gates or checkpoints

**Problem 3: No Observability**
- ❌ Black box agent execution
- ❌ No visibility into nested calls
- ❌ Debugging required code changes

### After Agent Orchestration Features

**Solution 1: AgentTool** ✅
- ✅ Agents can delegate to other agents
- ✅ Multi-level hierarchies supported
- ✅ Address-based tracking through nested calls
- ✅ Clean separation of concerns

**Solution 2: Interrupt/Resume** ✅
- ✅ Workflows can interrupt for approval
- ✅ State persisted to checkpoints
- ✅ Resume from exact interrupt point
- ✅ Stateful interrupts with context data

**Solution 3: Event Streaming** ✅
- ✅ Real-time event stream from agents
- ✅ Hierarchical RunPath tracking
- ✅ Complete observability into nested execution
- ✅ Event types for all agent operations

---

## Architecture Patterns

### 1. Supervisor-Specialist Pattern
**Example**: NestedAgentDelegationExample

```scala
// Top-level coordinator
Supervisor Agent
├─ Routing logic
└─ Delegates to specialists

// Specialist teams
Data Specialist Agent
├─ Domain expertise
└─ Delegates to sub-specialists

// Execution layer
Query Specialist Agent
└─ Actual work execution
```

**When to Use**:
- Large organizations with specialized teams
- Complex workflows requiring domain expertise
- Clear separation of routing vs. execution

---

### 2. Approval Gate Pattern
**Examples**: InterruptResumeExample, StatefulResumeExample

```scala
// Workflow with approval gates
Step 1: Prepare action ✓
Step 2: Request approval ⏸ (interrupt)
        [Checkpoint saved]
        [Human reviews and approves]
Step 3: Execute action ▶️ (resume)
```

**When to Use**:
- Financial transactions (payment approvals)
- Sensitive operations (data deletion)
- Compliance requirements (audit trails)
- Long-running workflows (multi-day processes)

---

### 3. Observable Execution Pattern
**Examples**: EventStreamExample, HierarchicalEventStreamExample

```scala
// Concurrent execution + observation
val (resultIO, eventStream) = runner.runWithEvents(messages)

// Stream events in real-time
eventStream.evalTap(event => logEvent(event)).compile.drain

// Wait for result
result <- resultIO
```

**When to Use**:
- Debugging complex agent interactions
- Performance monitoring and profiling
- Building execution traces for audit
- Real-time dashboards and visualization

---

## Running Examples

### Individual Examples

```bash
# Basic examples
./run-example.sh agenttool
./run-example.sh interruptresume
./run-example.sh eventstream

# Advanced examples
./run-example.sh nestedagent
./run-example.sh compositeinterrupt
./run-example.sh agenttooladvanced
./run-example.sh hierarchicalevents
./run-example.sh statefulresume
```

### Run All Agent Orchestration Examples

```bash
# Run all 8 examples sequentially
for ex in agenttool interruptresume eventstream nestedagent compositeinterrupt agenttooladvanced hierarchicalevents statefulresume; do
  ./run-example.sh $ex
done
```

---

## Implementation Reference

All examples use mock models and don't require API keys. Key implementation details:

### AgentTool Creation
```scala
// Method 1: From Agent
val tool: IO[AgentTool] = AgentTool.fromAgent(myAgent)

// Method 2: From Function
val tool: IO[AgentTool] = AgentTool.fromFunction(
  "tool-name",
  "Tool description",
  (messages: List[Message]) => IO.pure("result")
)

// Method 3: With Custom Config
val config = AgentToolConfig.withInputSchema(customSchema)
val tool: IO[AgentTool] = AgentTool.fromAgent(myAgent, config)
```

### Interrupt/Resume Workflow
```scala
// Phase 1: Run until interrupt
store <- InMemoryCheckpointStore.create
emitter <- AgentEventEmitter.create()
runner = AgentRunner.create(agent, store, emitter)

result1 <- runner.run(messages)
val checkpointId = result1 match
  case RunResult.Interrupted(id, signal) => id

// Phase 2: Resume with approval
result2 <- runner.resume(
  checkpointId,
  List(InterruptResult(address, approvalData))
)
```

### Event Stream Consumption
```scala
// Get result IO + event stream
val (resultIO, eventStream) = runner.runWithEvents(messages)

// Process events concurrently
resultFiber <- resultIO.start
events <- eventStream.evalTap(event =>
  IO.println(s"[${event.getClass.getSimpleName}] @ ${event.runPath.show}")
).compile.toList
result <- resultFiber.joinWithNever
```

---

## Testing

All examples can be compiled and run:

```bash
# Compile all examples
sbt "adk4s-examples/compile"

# Run specific example
sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.NestedAgentDelegationExample"

# Run with custom model (requires API key)
OPENAI_API_KEY=sk-... sbt "adk4s-examples/runMain org.adk4s.examples.eino.agent.StatefulResumeExample"
```

---

## Known Limitations

See `/docs/agent-orchestration-limitations.md` for detailed documentation of known limitations:

1. **Hierarchical Resume Routing Not Implemented**: Nested interrupts don't route resume data hierarchically
2. **withFullChatHistory Not Functional**: AgentTool can't receive full parent conversation history
3. **Exit/TransferToAgent Actions Not Implemented**: Action scoping not available

Despite these limitations, the implementation is **production-ready** for most use cases including:
- ✅ Single-level agent delegation
- ✅ Basic interrupt/resume workflows
- ✅ Event monitoring and observability
- ✅ Checkpoint persistence and recovery

---

## Contributing

To add new agent orchestration examples:

1. Create example in `org.adk4s.examples.eino.agent` package
2. Extend `IOApp.Simple` for runnable main
3. Use `ExampleUtils.printSection` for formatted output
4. Add to `run-example.sh` script
5. Update this README with example documentation

---

## Further Reading

- **Design Document**: `/openspec/changes/agent-orchestration-gaps/design.md`
- **Limitations**: `/docs/agent-orchestration-limitations.md`
- **Gap Analysis**: `/docs/agent-orchestration-gap-analysis.md`
- **Implementation**: Source code in `adk4s-core` and `adk4s-orchestration`

---

**Last Updated**: 2026-02-08
