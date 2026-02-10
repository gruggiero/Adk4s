sequenceDiagram
    participant User
    participant AgentRunner
    participant Supervisor as Supervisor Agent
    participant SupervisorModel as Supervisor ChatModel
    participant DataTool as AgentTool(Data Specialist)
    participant DataSpecialist as Data Specialist Agent
    participant DataModel as Data Specialist ChatModel
    participant QueryTool as AgentTool(Query Specialist)
    participant QueryAgent as Query Specialist Agent
    participant ToolsNode as ToolsNode
    participant EventEmitter as AgentEventEmitter

    Note over User, EventEmitter: Architecture: 3-level nested delegation
    Note over User, EventEmitter: Supervisor → Data Specialist → Query Specialist

    %% === 1. Initial Setup ===
    User->>AgentRunner: run("Find all active users")
    AgentRunner->>AgentRunner: Create scoped emitter
    AgentRunner->>Supervisor: generate(["Find all active users"], maxSteps=10)

    %% === 2. Supervisor First Call ===
    Supervisor->>SupervisorModel: generate(conversation)
    Note over SupervisorModel: counter = 0 (first call)
    SupervisorModel-->>Supervisor: Completion with ToolCall("data-specialist", {"request": "Find all active users"})
    
    Supervisor->>EventEmitter: emit(ToolCallRequested)
    Supervisor->>ToolsNode: executeFromToolCalls([ToolCall])
    
    %% === 3. Data Specialist Delegation ===
    ToolsNode->>DataTool: run({"request": "Find all active users"})
    DataTool->>DataTool: extractRequest("Find all active users")
    DataTool->>DataTool: buildMessages([UserMessage("Find all active users")])
    DataTool->>DataSpecialist: generate([UserMessage], maxSteps=10)
    
    DataSpecialist->>DataModel: generate(conversation)
    Note over DataModel: counter = 0 (first call)
    DataModel-->>DataSpecialist: Completion with ToolCall("query-specialist", {"request": "Find all active users"})
    
    DataSpecialist->>EventEmitter: emit(ToolCallRequested)
    DataSpecialist->>ToolsNode: executeFromToolCalls([ToolCall])
    
    %% === 4. Query Specialist Execution ===
    ToolsNode->>QueryTool: run({"request": "Find all active users"})
    QueryTool->>QueryTool: extractRequest("Find all active users")
    QueryTool->>QueryTool: buildMessages([UserMessage("Find all active users")])
    QueryTool->>QueryAgent: generate([UserMessage], maxSteps=10)
    
    Note over QueryAgent: Leaf agent - no tool calls
    QueryAgent->>QueryAgent: Check if request contains "user"
    QueryAgent-->>QueryTool: AssistantMessage("Query executed successfully: {\"query\": \"SELECT * FROM users\", \"results\": [...]}")
    
    QueryTool-->>ToolsNode: ToolMessage with query results
    ToolsNode-->>DataSpecialist: [ToolMessage]
    
    %% === 5. Data Specialist Second Call ===
    DataSpecialist->>DataModel: generate(conversation with tool result)
    Note over DataModel: counter = 1 (second call)
    DataModel-->>DataSpecialist: Completion with final response
    
    DataSpecialist->>EventEmitter: emit(ToolCallCompleted)
    DataSpecialist->>EventEmitter: emit(IterationCompleted)
    DataTool-->>ToolsNode: ToolMessage with processed results
    ToolsNode-->>Supervisor: [ToolMessage]
    
    %% === 6. Supervisor Final Response ===
    Supervisor->>SupervisorModel: generate(conversation with tool result)
    Note over SupervisorModel: counter = 1 (second call)
    SupervisorModel-->>Supervisor: Completion with final synthesized response
    
    Supervisor->>EventEmitter: emit(ToolCallCompleted)
    Supervisor->>EventEmitter: emit(IterationCompleted)
    Supervisor-->>AgentRunner: AssistantMessage with final output
    
    %% === 7. Completion ===
    AgentRunner->>EventEmitter: emit(MessageOutput)
    AgentRunner-->>User: "Request processed through our specialist teams: ..."
    
    Note over User, EventEmitter: Results bubble up through hierarchy: Query → Data Specialist → Supervisor → User
