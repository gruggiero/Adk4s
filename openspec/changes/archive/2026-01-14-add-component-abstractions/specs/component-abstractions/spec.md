# component-abstractions Specification

## ADDED Requirements

### Requirement: ChatModel Abstraction
The system SHALL provide a ChatModel[F] trait that wraps LLM4S LLMClient with fs2.Stream support, providing generate and stream methods for chat completion operations.

#### Scenario: Generate completion from conversation
- **GIVEN** a ChatModel created from LLM4S client
- **AND** a Conversation with system and user messages
- **WHEN** generate is called with the conversation
- **THEN** an IO[Completion] is returned with the model response

#### Scenario: Stream completion chunks
- **GIVEN** a ChatModel created from LLM4S client
- **AND** a Conversation with user message
- **WHEN** stream is called with the conversation
- **THEN** a Stream[F, StreamedChunk] is returned with streaming chunks

#### Scenario: Stream content only
- **GIVEN** a ChatModel created from LLM4S client
- **AND** a Conversation with user message
- **WHEN** streamContent is called with the conversation
- **THEN** a Stream[F, String] is returned with content chunks
- **AND** empty chunks are filtered out

#### Scenario: Apply custom configuration
- **GIVEN** a ChatModel with custom ChatModelConfig (temperature=0.7, maxTokens=1000)
- **WHEN** generate is called
- **THEN** the LLM call uses the specified temperature and maxTokens
- **AND** config.toCompletionOptions produces valid CompletionOptions

#### Scenario: Create ChatModel from LLM4S client
- **GIVEN** an LLM4S LLMClient instance
- **WHEN** ChatModel.fromLlm4s is called
- **THEN** a ChatModel[IO] is returned that wraps the client
- **AND** all methods delegate to StreamingLLM.fromClient

#### Scenario: Auto-detect LLM provider
- **GIVEN** environment with LLM provider credentials configured
- **WHEN** ChatModel.auto is called
- **THEN** an IO[ChatModel[IO]] is returned
- **AND** the ChatModel uses the environment-detected provider

### Requirement: ToolCallingChatModel Abstraction
The system SHALL provide a ToolCallingChatModel[F] trait that extends ChatModel[F] with tool calling capabilities, using immutable withTools method to bind tools.

#### Scenario: Get tools list
- **GIVEN** a ToolCallingChatModel with three tools registered
- **WHEN** tools property is accessed
- **THEN** a List[ToolFunction] with three elements is returned

#### Scenario: Replace tools immutably
- **GIVEN** a ToolCallingChatModel with initial tools
- **WHEN** withTools is called with a different tool list
- **THEN** a new ToolCallingChatModel instance is returned
- **AND** the new instance has the provided tools
- **AND** the original instance's tools are unchanged

#### Scenario: Add tools to existing model
- **GIVEN** a ToolCallingChatModel with two tools
- **WHEN** addTools is called with one additional tool
- **THEN** a new ToolCallingChatModel instance is returned
- **AND** the new instance has three tools (original two + one new)
- **AND** the original instance still has two tools

#### Scenario: Generate with tools available
- **GIVEN** a ToolCallingChatModel with tools registered
- **AND** a Conversation requesting tool usage
- **WHEN** generateWithTools is called
- **THEN** the completion includes tool calls from the registered tools
- **AND** the CompletionOptions includes the tools parameter

#### Scenario: Stream with tools available
- **GIVEN** a ToolCallingChatModel with tools registered
- **AND** a Conversation requesting tool usage
- **WHEN** streamWithTools is called
- **THEN** the stream includes chunks with tool calls
- **AND** the CompletionOptions includes the tools parameter

#### Scenario: Generate without tools (base method)
- **GIVEN** a ToolCallingChatModel with tools registered
- **AND** a Conversation that doesn't need tools
- **WHEN** generate is called (not generateWithTools)
- **THEN** the completion proceeds normally without including tools in options

#### Scenario: Create ToolCallingChatModel from LLM4S client
- **GIVEN** an LLM4S LLMClient and a list of ToolFunction
- **WHEN** ToolCallingChatModel.fromLlm4s is called
- **THEN** a ToolCallingChatModel[IO] is returned with the initial tools
- **AND** the implementation delegates to StreamingLLM.fromClient

### Requirement: Tool Abstraction
The system SHALL provide Tool, InvokableTool, and StreamableTool traits for defining executable tools with LLM4S ToolFunction integration.

#### Scenario: Get tool information
- **GIVEN** a Tool wrapping a ToolFunction with name "search" and description "Search the web"
- **WHEN** info is called
- **THEN** AdkToolInfo with name "search", description "Search the web", and parameters is returned

#### Scenario: Get underlying ToolFunction
- **GIVEN** an InvokableTool created from LLM4S ToolFunction
- **WHEN** asToolFunction is called
- **THEN** Some(toolFunction) is returned with the original ToolFunction

#### Scenario: Run invokable tool with valid JSON
- **GIVEN** an InvokableTool created from function expecting "query" parameter
- **AND** JSON arguments '{"query": "test"}'
- **WHEN** run is called with the arguments
- **THEN** IO[String] returns the handler's result

#### Scenario: Run invokable tool with invalid JSON
- **GIVEN** an InvokableTool
- **AND** malformed JSON arguments '{"query": }'
- **WHEN** run is called with the arguments
- **THEN** IO raises ToolExecutionError with tool name and parsing failure

#### Scenario: Run streamable tool
- **GIVEN** a StreamableTool with streaming handler
- **AND** JSON arguments '{"count": 5}'
- **WHEN** runStream is called with the arguments
- **THEN** a Stream[F, String] is returned with streaming results

#### Scenario: Create InvokableTool from LLM4S ToolFunction
- **GIVEN** an LLM4S ToolFunction with name and handler
- **WHEN** Tool.fromLlm4s is called
- **THEN** an InvokableTool[IO] is returned that wraps the ToolFunction
- **AND** asToolFunction returns Some(toolFunction)

#### Scenario: Create InvokableTool from function
- **GIVEN** a handler function taking Map[String, Any] and returning Either[String, String]
- **WHEN** Tool.invokable is called with name, description, and handler
- **THEN** an InvokableTool[IO] is returned
- **AND** asToolFunction returns None
- **AND** run parses JSON arguments and invokes the handler

#### Scenario: Create StreamableTool from function
- **GIVEN** a handler function taking Map[String, Any] and returning Stream[IO, String]
- **WHEN** Tool.streamable is called with name, description, and handler
- **THEN** a StreamableTool[IO] is returned
- **AND** runStream parses JSON arguments and streams handler output

### Requirement: Retriever Abstraction
The system SHALL provide a Retriever[F] trait for document retrieval with configuration support, enabling RAG applications through empty and functional implementations.

#### Scenario: Retrieve documents
- **GIVEN** a Retriever created from function
- **AND** a query "relevant documents"
- **WHEN** retrieve is called with the query
- **THEN** IO[List[Document]] is returned with matching documents

#### Scenario: Retrieve documents as stream
- **GIVEN** a Retriever created from function
- **AND** a query "streaming docs"
- **WHEN** retrieveStream is called with the query
- **THEN** Stream[F, Document] is returned with streaming documents

#### Scenario: Empty retriever returns empty list
- **GIVEN** the empty Retriever
- **AND** any query string
- **WHEN** retrieve is called
- **THEN** IO.pure(Nil) is returned

#### Scenario: Empty retriever returns empty stream
- **GIVEN** the empty Retriever
- **AND** any query string
- **WHEN** retrieveStream is called
- **THEN** Stream.empty is returned

#### Scenario: Apply custom configuration
- **GIVEN** a Retriever with custom RetrieverConfig (topK=5, minScore=0.7)
- **AND** a query
- **WHEN** retrieve is called with the custom config
- **THEN** the function receives the custom config parameters
- **AND** results respect topK and minScore limits

#### Scenario: Create Retriever from function
- **GIVEN** a function (String, RetrieverConfig) => IO[List[Document]]
- **WHEN** Retriever.fromFunction is called
- **THEN** a Retriever[IO] is returned
- **AND** retrieve delegates to the provided function

#### Scenario: Document with metadata
- **GIVEN** a Document with id "doc1", content "text", and metadata Map("category" -> Json.fromString("tech"))
- **WHEN** the Document is created
- **THEN** all fields are stored correctly
- **AND** metadata preserves Json structure

### Requirement: Embedder Abstraction
The system SHALL provide an Embedder[F] trait for text embeddings with batch support and dimension query, including a mock implementation for testing.

#### Scenario: Embed single text
- **GIVEN** an Embedder with dimension 1536
- **AND** a text "sample text"
- **WHEN** embed is called with the text
- **THEN** IO[Embedding] is returned with vector of 1536 doubles

#### Scenario: Embed multiple texts
- **GIVEN** an Embedder with dimension 768
- **AND** a list of three text strings
- **WHEN** embedBatch is called with the texts
- **THEN** IO[EmbeddingResult] is returned
- **AND** embeddings list contains three vectors
- **AND** each vector has 768 dimensions

#### Scenario: Query embedding dimension
- **GIVEN** an Embedder with dimension 384
- **WHEN** dimension is called
- **THEN** IO.pure(384) is returned

#### Scenario: Mock embedder generates random vectors
- **GIVEN** a mock Embedder with dimension 10
- **AND** any text input
- **WHEN** embed is called
- **THEN** a Vector with 10 random doubles is returned
- **AND** values are in range [0.0, 1.0]

#### Scenario: Mock embedder batch generates independent vectors
- **GIVEN** a mock Embedder with dimension 5
- **AND** a list of three texts
- **WHEN** embedBatch is called
- **THEN** three vectors are returned
- **AND** each vector contains different random values

#### Scenario: EmbeddingResult includes usage information
- **GIVEN** an Embedder with usage tracking enabled
- **AND** a batch request
- **WHEN** embedBatch is called
- **THEN** EmbeddingResult.usage is defined
- **AND** usage.promptTokens reflects the total tokens

### Requirement: ChatTemplate Abstraction
The system SHALL provide a ChatTemplate[F, V] trait that wraps structured-llm PromptTemplate for variable substitution and message formatting.

#### Scenario: Format template to Prompt
- **GIVEN** a ChatTemplate from PromptTemplate expecting variable "name"
- **AND** variables Map("name" -> "Alice")
- **WHEN** format is called with the variables
- **THEN** IO[Prompt] is returned with formatted messages

#### Scenario: Format template to Conversation
- **GIVEN** a ChatTemplate from PromptTemplate
- **AND** variables Map("topic" -> "cats")
- **WHEN** formatConversation is called
- **THEN** IO[Conversation] is returned
- **AND** the Conversation can be used with LLM4S clients

#### Scenario: Create ChatTemplate from PromptTemplate
- **GIVEN** a structured-llm PromptTemplate[V]
- **WHEN** ChatTemplate.fromPromptTemplate is called
- **THEN** a ChatTemplate[IO, V] is returned
- **AND** format delegates to template.render

#### Scenario: Create ChatTemplate from messages with substitution
- **GIVEN** a list of Messages with content containing placeholders
- **AND** a substitution function that replaces {name} with variable values
- **WHEN** ChatTemplate.fromMessages is called
- **THEN** a ChatTemplate[IO, Map[String, String]] is returned
- **AND** format applies substitution to each message content

#### Scenario: Simple template with variable substitution
- **GIVEN** a ChatTemplate.simple with messages containing {city} and {country}
- **AND** variables Map("city" -> "Tokyo", "country" -> "Japan")
- **WHEN** format is called
- **THEN** messages have {city} replaced with "Tokyo"
- **AND** messages have {country} replaced with "Japan"

#### Scenario: Multiple variables in same message
- **GIVEN** a ChatTemplate.simple with message "Hello {name}, you are {age} years old"
- **AND** variables Map("name" -> "Bob", "age" -> "30")
- **WHEN** format is called
- **THEN** the message content becomes "Hello Bob, you are 30 years old"

### Requirement: Component Package Object
The system SHALL provide a component package object that exports all component abstractions and provides convenient type aliases for common effect types.

#### Scenario: Export all components
- **GIVEN** the component package object
- **WHEN** importing org.adk4s.core.component.*
- **THEN** ChatModel, ToolCallingChatModel, Tool, Retriever, Embedder, ChatTemplate are available
- **AND** all companion objects (ChatModel, Tool, etc.) are available

#### Scenario: Use ChatModelIO type alias
- **GIVEN** a function returning ChatModel[IO]
- **WHEN** typed as ChatModelIO
- **THEN** the function compiles successfully
- **AND** ChatModelIO is equivalent to ChatModel[cats.effect.IO]

#### Scenario: Use ToolIO type alias
- **GIVEN** a value of type Tool[IO]
- **WHEN** typed as ToolIO
- **THEN** the value compiles successfully
- **AND** ToolIO is equivalent to Tool[cats.effect.IO]

#### Scenario: Use InvokableToolIO type alias
- **GIVEN** a function returning InvokableTool[IO]
- **WHEN** typed as InvokableToolIO
- **THEN** the function compiles successfully

#### Scenario: Use RetrieverIO type alias
- **GIVEN** a value of type Retriever[IO]
- **WHEN** typed as RetrieverIO
- **THEN** the value compiles successfully

#### Scenario: Use EmbedderIO type alias
- **GIVEN** a function returning Embedder[IO]
- **WHEN** typed as EmbedderIO
- **THEN** the function compiles successfully
