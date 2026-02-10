## 1. Core Types
- [x] 1.1 Create ToolInput type with fromToolCall conversions
- [x] 1.2 Create ToolOutput type with toMessage conversions
- [x] 1.3 Create ToolExecutionResult for batch results
- [x] 1.4 Create ToolExecutionFailure for error details
- [x] 1.5 Write tests for type conversions and batch handling

## 2. Middleware System
- [x] 2.1 Define ToolEndpoint and ToolMiddleware types
- [x] 2.2 Implement identity middleware
- [x] 2.3 Implement logging middleware
- [x] 2.4 Implement timing middleware
- [x] 2.5 Implement validation middleware
- [x] 2.6 Implement retry middleware with exponential backoff
- [x] 2.7 Implement middleware composition operators
- [x] 2.8 Write tests for all middleware implementations

## 3. Configuration
- [x] 3.1 Create ToolsNodeConfig case class
- [x] 3.2 Implement fromRegistry factory
- [x] 3.3 Implement fromToolFunctions factory
- [x] 3.4 Implement fromAdkTools factory
- [x] 3.5 Create ToolsNodeConfigBuilder
- [x] 3.6 Add builder methods for tools, handlers, middleware
- [x] 3.7 Add execution mode configuration (sequential/parallel)
- [x] 3.8 Write tests for config creation and builder pattern

## 4. ToolsNode Implementation
- [x] 4.1 Create ToolsNode class with config
- [x] 4.2 Implement executeTool for single execution
- [x] 4.3 Implement executeTools for batch execution
- [x] 4.4 Implement executeFromToolCalls for LLM4S integration
- [x] 4.5 Implement sequential execution mode
- [x] 4.6 Implement parallel execution mode with concurrency limit
- [x] 4.7 Implement LLM4S tool execution
- [x] 4.8 Implement ADK4S tool execution
- [x] 4.9 Implement unknown tool handler
- [x] 4.10 Implement arguments preprocessing handler
- [x] 4.11 Add middleware integration
- [x] 4.12 Write tests for all execution modes and error handling

## 5. Runnable Integration
- [x] 5.1 Create fromAssistantMessage Runnable
- [x] 5.2 Create fromToolCalls Runnable
- [x] 5.3 Create streaming Runnable for real-time results
- [x] 5.4 Add extension methods to ToolsNode
- [x] 5.5 Write tests for Runnable integrations

## 6. Documentation
- [x] 6.1 Add package.scala with exports (omitted - causes compilation issues)
- [x] 6.2 Update module documentation
- [x] 6.3 Add usage examples
