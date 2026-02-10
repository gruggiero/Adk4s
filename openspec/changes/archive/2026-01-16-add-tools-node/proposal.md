# Change: Add Tools Node for Tool Execution

## Why
The framework needs a robust mechanism to execute tool calls from LLM responses for ReAct agents and function-calling workflows. This provides the foundation for autonomous agent behavior where LLMs can invoke external tools to accomplish tasks.

## What Changes
- Add ToolInput/ToolOutput types for tool execution
- Add ToolMiddleware pattern for logging, validation, retry, and timing
- Add ToolsNodeConfig with builder pattern for tool registration and configuration
- Add ToolsNode for executing tool calls with parallel/sequential execution modes
- Add Runnable integration for graph orchestration
- Support both LLM4S ToolFunctions and ADK4S InvokableTools
- Add unknown tool handler configuration
- Add middleware composition support

## Impact
- New capability: `tools-node`
- Affected code: Creates new module `adk4s-agent`
- Dependencies: LLM4S, Cats Effect, fs2, Circe
- Enables: ReAct agents, function-calling workflows, multi-step agent reasoning
