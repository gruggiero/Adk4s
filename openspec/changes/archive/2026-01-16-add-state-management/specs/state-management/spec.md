## ADDED Requirements

### Requirement: StateRef
The system SHALL provide a type-safe wrapper around Cats Effect Ref for graph execution state management.

#### Scenario: Create StateRef from initial state
- **WHEN** `StateRef.of(initialState)` is called
- **THEN** a new StateRef instance is created with the provided initial state

#### Scenario: Create StateRef from existing Ref
- **WHEN** `StateRef.fromRef(ref)` is called
- **THEN** a new StateRef instance wrapping the provided Ref is returned

#### Scenario: Create empty StateRef
- **WHEN** `StateRef.empty(default)` is called
- **THEN** a no-op StateRef is created that always returns the default value

#### Scenario: Get current state
- **WHEN** `stateRef.get` is called
- **THEN** the current state value is returned

#### Scenario: Set state
- **WHEN** `stateRef.set(newValue)` is called
- **THEN** the state is updated to the new value

#### Scenario: Update state
- **WHEN** `stateRef.update(f)` is called
- **THEN** the state is updated using the provided function

#### Scenario: Modify state and return result
- **WHEN** `stateRef.modify(f)` is called
- **THEN** the state is updated and the result value is returned

#### Scenario: Get and update state
- **WHEN** `stateRef.getAndUpdate(f)` is called
- **THEN** the previous state value is returned and the state is updated

#### Scenario: Update and get state
- **WHEN** `stateRef.updateAndGet(f)` is called
- **THEN** the state is updated and the new value is returned

### Requirement: Pre/Post Handlers
The system SHALL provide handler types for modifying state before and after node execution.

#### Scenario: Identity pre-handler
- **WHEN** `StateHandlers.identityPre` is used
- **THEN** the input is passed through unchanged

#### Scenario: Identity post-handler
- **WHEN** `StateHandlers.identityPost` is used
- **THEN** the output is passed through unchanged

#### Scenario: Accumulate handler
- **WHEN** `StateHandlers.accumulate(lens, update)` is used
- **THEN** the input is accumulated into the state and the input is returned

#### Scenario: From state handler
- **WHEN** `StateHandlers.fromState(lens)` is used
- **THEN** the input is replaced with a value derived from the state

#### Scenario: Store output handler
- **WHEN** `StateHandlers.storeOutput(update)` is used
- **THEN** the output is stored in the state and the output is returned

#### Scenario: Combine pre-handlers
- **WHEN** `StateHandlers.combinePre(handlers*)` is used
- **THEN** handlers are applied in sequence, each receiving the output of the previous

#### Scenario: Combine post-handlers
- **WHEN** `StateHandlers.combinePost(handlers*)` is used
- **THEN** handlers are applied in sequence, each receiving the output of the previous

### Requirement: Workflows4s Integration
The system SHALL provide event-sourced state management through Workflows4s integration.

#### Scenario: Create AgentStateContext
- **WHEN** `AgentStateContext` is accessed
- **THEN** it provides AgentState with messages, stepCount, toolCallCount, and metadata

#### Scenario: Emit event
- **WHEN** `EventSourcedOps.emitEvent(event)` is called
- **THEN** a WIO step that emits the provided event is created

#### Scenario: Run IO and emit event
- **WHEN** `EventSourcedOps.runAndEmit(run, toEvent)` is called
- **THEN** the IO is executed and an event is emitted based on the result

#### Scenario: Apply MessageAdded event
- **WHEN** a MessageAdded event is applied to AgentState
- **THEN** the message is appended to the messages list

#### Scenario: Apply StepCompleted event
- **WHEN** a StepCompleted event is applied to AgentState
- **THEN** the stepCount is updated to the provided step number

#### Scenario: Apply ToolCalled event
- **WHEN** a ToolCalled event is applied to AgentState
- **THEN** the toolCallCount is incremented

#### Scenario: Apply MetadataUpdated event
- **WHEN** a MetadataUpdated event is applied to AgentState
- **THEN** the metadata map is updated with the key-value pair

### Requirement: StatefulNode Wrapper
The system SHALL provide a wrapper for Runnable instances that supports state handling through pre/post handlers.

#### Scenario: Wrap with state configuration
- **WHEN** `StatefulNode.wrap(runnable, stateRef, config)` is called
- **THEN** a StatefulNode instance is created that applies pre/post handlers

#### Scenario: Wrap with pre-handler only
- **WHEN** `StatefulNode.withPre(runnable, stateRef, preHandler)` is called
- **THEN** a StatefulNode is created with only the pre-handler configured

#### Scenario: Wrap with post-handler only
- **WHEN** `StatefulNode.withPost(runnable, stateRef, postHandler)` is called
- **THEN** a StatefulNode is created with only the post-handler configured

#### Scenario: Invoke with pre-handler
- **WHEN** `statefulNode.invoke(input)` is called with a pre-handler configured
- **THEN** the pre-handler processes the input before the inner Runnable is invoked

#### Scenario: Invoke with post-handler
- **WHEN** `statefulNode.invoke(input)` is called with a post-handler configured
- **THEN** the post-handler processes the output after the inner Runnable is invoked

#### Scenario: Stream with pre-handler
- **WHEN** `statefulNode.stream(input)` is called with a pre-handler configured
- **THEN** the input stream is processed by the pre-handler before streaming

#### Scenario: Stream with post-handler
- **WHEN** `statefulNode.stream(input)` is called with a post-handler configured
- **THEN** the output stream is processed by the post-handler

#### Scenario: Collect with stream pre-handler
- **WHEN** `statefulNode.collect(inputStream)` is called with a stream pre-handler configured
- **THEN** the input stream is processed by the stream pre-handler

#### Scenario: Transform with stream post-handler
- **WHEN** `statefulNode.transform(inputStream)` is called with a stream post-handler configured
- **THEN** the output stream is processed by the stream post-handler

#### Scenario: Collect with post-handler
- **WHEN** `statefulNode.collect(inputStream)` is called with a post-handler configured
- **THEN** the collected output is processed by the post-handler

#### Scenario: Transform with stream pre-handler
- **WHEN** `statefulNode.transform(inputStream)` is called with a stream pre-handler configured
- **THEN** the input stream is processed by the stream pre-handler

### Requirement: Stream Pre/Post Handlers
The system SHALL provide specialized handlers for streaming operations.

#### Scenario: Stream pre-handler processes input stream
- **WHEN** a StreamPreHandler is applied to an input stream
- **THEN** the handler can transform or filter the stream elements

#### Scenario: Stream post-handler processes output stream
- **WHEN** a StreamPostHandler is applied to an output stream
- **THEN** the handler can transform or filter the stream elements

#### Scenario: Stream pre-handler accesses state
- **WHEN** a StreamPreHandler processes a stream
- **THEN** the handler can read and modify the StateRef

#### Scenario: Stream post-handler accesses state
- **WHEN** a StreamPostHandler processes a stream
- **THEN** the handler can read and modify the StateRef
