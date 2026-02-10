# Tasks: Implement Convertible Eino Examples

## Phase 0: WIORunnableNode Infrastructure ✅
*Prerequisite for Phase 2 streaming examples (graph/state, graph/tool_call_once, graph/async_node)*

- [x] 0.1 Add `WIORunnableNode` case class to `adk4s-orchestration/wiograph/WIONode.scala`
- [x] 0.2 Add `WIONode.fromRunnable` and `WIONode.fromLambda` factory methods
- [x] 0.3 Add `WIOGraph.addRunnableNode` and `addLambdaNode` convenience methods
- [x] 0.4 Add `WIOGraphStreamExecutor` (new file) + `toRunnable` on `WIOGraph`
- [x] 0.5 Add stream-aware fork variant (`StreamBranch`) to `WIOForkNode`
- [x] 0.6 Write unit tests for `WIORunnableNode` (5 tests)
- [x] 0.7 Write unit tests for `WIOGraphStreamExecutor` and `toRunnable` (11 tests)

## Phase 1: Component Examples ✅
*All 5 examples + shared utilities implemented and compiling*

- [x] 1.1 Create shared utilities (`common/ExampleUtils.scala`)
- [x] 1.2 Implement `components/LambdaExample.scala` (Eino: `components/lambda`)
- [x] 1.3 Implement `components/ChatModelExample.scala` (Eino: `components/model`)
- [x] 1.4 Implement `components/ChatTemplateExample.scala` (Eino: `components/prompt`)
- [x] 1.5 Implement `components/DocumentLoaderExample.scala` (Eino: `components/document`)
- [x] 1.6 Implement `components/RetrieverExample.scala` (Eino: `components/retriever`)

## Phase 2: Graph Examples ✅
*All 6 graph examples implemented — 3 non-streaming + 3 streaming (using WIORunnableNode/toRunnable)*

- [x] 2.1 Implement `graph/SimpleGraphExample.scala` (Eino: `graph/simple`)
- [x] 2.2 Implement `graph/ToolCallAgentExample.scala` (Eino: `graph/tool_call_agent`)
- [x] 2.3 Implement `graph/TwoModelChatExample.scala` (Eino: `graph/two_model_chat`)
- [x] 2.4 Implement `graph/StateGraphExample.scala` (Eino: `graph/state`) — uses WIORunnableNode + toRunnable
- [x] 2.5 Implement `graph/ToolCallOnceExample.scala` (Eino: `graph/tool_call_once`) — uses WIOForkNode branching
- [x] 2.6 Implement `graph/AsyncNodeExample.scala` (Eino: `graph/async_node`) — uses async Runnable nodes

## Phase 3: Workflow Alternative Examples ✅
*All 3 examples implemented and compiling*

- [x] 3.1 Implement `workflow/SimpleWorkflowExample.scala` (Eino: `workflow/1_simple`)
- [x] 3.2 Implement `workflow/BranchWorkflowExample.scala` (Eino: `workflow/4_control_only_branch`)
- [x] 3.3 Implement `workflow/StaticValuesExample.scala` (Eino: `workflow/5_static_values`)

## Phase 4: Agent Pattern Examples ✅
*All 3 examples implemented using ChatModel directly (cats-effect IO based)*

- [x] 4.1 Implement `agent/ReactMemoryExample.scala` (Eino: `flow/agent/react/memory`)
  - Uses ChatModel with ConversationMemory for multi-turn context
- [x] 4.2 Implement `agent/MultiAgentHostExample.scala` (Eino: `flow/agent/multiagent/host`)
  - Host/router pattern with classifier and specialist agents
- [x] 4.3 Implement `agent/PlanExecuteExample.scala` (Eino: `flow/agent/multiagent/plan_execute`)
  - Plan-and-execute pattern with planner and executor agents

## Phase 5: Quickstart Example ✅

- [x] 5.1 Implement `quickstart/ChatExample.scala` (Eino: `quickstart/chat`)

## Documentation & Cleanup ✅

- [x] 6.3 Verify all examples compile: `sbt adk4s-examples/compile` ✅ (25 files)
- [x] 6.1 Update `adk4s-examples/README.md` with all new examples (3 streaming graph examples added)
- [x] 6.2 Update `adk4s-examples/run-example.sh` to support new examples (stategraph, toolcallonce, asyncnode)
- [x] 6.4 Run all 18 Eino examples with MockChatModel — all pass ✅
