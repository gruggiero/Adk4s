# Change: Add Component Abstractions

## Why
ADK4S needs core component abstractions to define the building blocks for agent development, aligning with Eino's component model. These abstractions provide thin wrappers around LLM4S and structured-llm, enabling type-safe composable agent components including chat models, tools, retrievers, embedders, and chat templates.

## What Changes
- Add ChatModel trait for LLM chat operations with streaming support
- Add ToolCallingChatModel trait extending ChatModel with tool calling capabilities
- Add Tool, InvokableTool, and StreamableTool traits for function abstractions
- Add Retriever trait for document retrieval (RAG support)
- Add Embedder trait for text embeddings (RAG support)
- Add ChatTemplate trait for prompt templating using structured-llm
- Add component package object with convenient exports and type aliases
- All components use thin wrapper pattern, leveraging LLM4S and structured-llm directly where possible

## Impact
- New capabilities: component-abstractions
- Affected code: adk4s-core module (new component package)
- Affected specs: core-types (extends with component types)
- New dependencies: None (uses existing LLM4S, structured-llm, fs2, Cats Effect)
- Breaking changes: None (new functionality)
