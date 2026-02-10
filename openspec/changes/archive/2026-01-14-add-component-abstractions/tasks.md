## Implementation Tasks

### 1. Create ChatModel Trait
- [x] 1.1 Create ChatModel.scala with ChatModel[F] trait
- [x] 1.2 Add generate, stream, and streamContent methods
- [x] 1.3 Create ChatModelConfig case class with toCompletionOptions
- [x] 1.4 Create ChatModel companion object with fromLlm4s factory
- [x] 1.5 Write unit tests for ChatModel

### 2. Create ToolCallingChatModel Trait
- [x] 2.1 Create ToolCallingChatModel.scala extending ChatModel[F]
- [x] 2.2 Add tools property and withTools/addTools methods (immutable)
- [x] 2.3 Add generateWithTools and streamWithTools methods
- [x] 2.4 Create ToolCallingChatModel companion with fromLlm4s factory
- [x] 2.5 Write unit tests for ToolCallingChatModel

### 3. Create Tool Traits
- [x] 3.1 Create Tool.scala with AdkToolInfo case class
- [x] 3.2 Create Tool[F] base trait with info and asToolFunction
- [x] 3.3 Create InvokableTool[F] trait extending Tool with run method
- [x] 3.4 Create StreamableTool[F] trait extending Tool with runStream method
- [x] 3.5 Create Tool companion with invokable, and streamable factories
- [x] 3.6 Write unit tests for Tool abstractions

### 4. Create Retriever Trait
- [x] 4.1 Create Retriever.scala with Document and RetrieverConfig case classes
- [x] 4.2 Create Retriever[F] trait with retrieve and retrieveStream methods
- [x] 4.3 Create Retriever companion with empty and fromFunction factories
- [x] 4.4 Write unit tests for Retriever

### 5. Create Embedder Trait
- [x] 5.1 Create Embedder.scala with Embedding type alias
- [x] 5.2 Add EmbeddingResult and EmbeddingUsage case classes
- [x] 5.3 Create Embedder[F] trait with embed, embedBatch, and dimension methods
- [x] 5.4 Create Embedder companion with mock factory
- [x] 5.5 Write unit tests for Embedder

### 6. Create ChatTemplate Trait
- [x] 6.1 Create ChatTemplate.scala with ChatTemplate[F, V] trait
- [x] 6.2 Add format and formatConversation methods
- [x] 6.3 Create ChatTemplate companion with fromPromptTemplate, fromMessages, and simple factories
- [x] 6.4 Write unit tests for ChatTemplate

### 7. Create Component Package Object
- [x] 7.1 Create package.scala with component exports
- [x] 7.2 Add convenient type aliases (ChatModelIO, ToolIO, etc.)
- [x] 7.3 Verify all components are accessible via package object

### 8. Integration and Testing
- [x] 8.1 Run all unit tests and ensure >90% coverage
- [x] 8.2 Test integration with LLM4S client
- [x] 8.3 Test full flow: template -> model -> response
- [x] 8.4 Run lint and typecheck
