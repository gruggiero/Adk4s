## ADDED Requirements

### Requirement: Runnable Trait with Four Streaming Paradigms

The system SHALL provide a `Runnable[I, O]` trait that represents an executable unit with four streaming paradigms:
- `invoke`: non-streaming input to non-streaming output
- `stream`: non-streaming input to streaming output
- `collect`: streaming input to non-streaming output
- `transform`: streaming input to streaming output

The Runnable trait SHALL support automatic derivation of missing paradigms from provided ones.

#### Scenario: Create Runnable from invoke function only
- **GIVEN** a function `I => IO[O]`
- **WHEN** `Runnable.fromInvoke` is called with the function
- **THEN** all four paradigms work correctly
- **AND** `invoke` executes the function
- **AND** `stream` emits the output as a single-element stream
- **AND** `collect` consumes the last element of the input stream and applies the function
- **AND** `transform` applies the function to each element of the input stream

#### Scenario: Create Runnable from stream function only
- **GIVEN** a function `I => Stream[IO, O]`
- **WHEN** `Runnable.fromStream` is called with the function
- **THEN** all four paradigms work correctly
- **AND** `invoke` compiles the stream and returns the last element
- **AND** `stream` returns the stream directly
- **AND** `collect` consumes the last element of the input stream, applies the stream function, and returns the last output element
- **AND** `transform` applies the stream function to each input element and flattens the results

#### Scenario: Create Runnable with all four paradigms explicitly
- **GIVEN** implementations for all four paradigm functions
- **WHEN** `Runnable.full` is called with the implementations
- **THEN** each paradigm uses its explicitly provided implementation
- **AND** no automatic derivation occurs

### Requirement: Lambda ADT for Graph Node Abstraction

The system SHALL provide a `Lambda[I, O]` ADT that wraps user functions as composable graph nodes with the following variants:
- `InvokableLambda[I, O]`: single input to single output
- `StreamableLambda[I, O]`: single input to stream output
- `CollectableLambda[I, O]`: stream input to single output
- `TransformableLambda[I, O]`: stream input to stream output
- `FullLambda[I, O]`: all four paradigms explicitly provided

Each Lambda SHALL support optional configuration metadata (name, description) and conversion to Runnable.

#### Scenario: Create invokable lambda from IO function
- **GIVEN** a function `I => IO[O]`
- **WHEN** `Lambda.apply` is called with the function
- **THEN** an `InvokableLambda` is created
- **AND** `toRunnable` returns a Runnable with correct invoke implementation

#### Scenario: Create lambda from pure function
- **GIVEN** a pure function `I => O`
- **WHEN** `Lambda.pure` is called with the function
- **THEN** an `InvokableLambda` is created that wraps the function in IO.pure

#### Scenario: Create streamable lambda
- **GIVEN** a function `I => Stream[IO, O]`
- **WHEN** `Lambda.stream` is called with the function
- **THEN** a `StreamableLambda` is created
- **AND** `toRunnable` returns a Runnable with correct stream implementation

#### Scenario: Set lambda metadata
- **GIVEN** any Lambda instance
- **WHEN** `named("myLambda")` is called
- **THEN** the Lambda's config has the provided name
- **WHEN** `described("My lambda description")` is called
- **THEN** the Lambda's config has the provided description

#### Scenario: Implicit conversion from function to Lambda
- **GIVEN** a function `I => IO[O]`
- **WHEN** the function is used where a Lambda is expected
- **THEN** the function is implicitly converted to an InvokableLambda

### Requirement: Runnable Combinators

The system SHALL provide combinators for composing and transforming Runnables:
- `andThen`: sequential composition of Runnables
- `map`: transform output with pure function
- `evalMap`: transform output with effectful function
- `contramap`: transform input with pure function
- `timeout`: add timeout to all paradigms
- `handleError`: handle errors with fallback
- `parallel`: run two Runnables in parallel and combine outputs as tuple
- `parallel3`: run three Runnables in parallel and combine outputs as tuple

#### Scenario: Sequential composition with andThen
- **GIVEN** two Runnables `r1: Runnable[I, O]` and `r2: Runnable[O, O2]`
- **WHEN** `r1.andThen(r2)` is called
- **THEN** all four paradigms of the composed Runnable work correctly
- **AND** `invoke` calls r1.invoke then r2.invoke
- **AND** `stream` calls r1.stream then flatMaps with r2.stream
- **AND** `collect` calls r1.collect then flatMaps with r2.invoke
- **AND** `transform` applies r2.transform to r1.transform output

#### Scenario: Output transformation with map
- **GIVEN** a Runnable `r: Runnable[I, O]` and function `f: O => O2`
- **WHEN** `r.map(f)` is called
- **THEN** all four paradigms of the resulting Runnable apply `f` to outputs
- **AND** the transformation is pure (no effects)

#### Scenario: Input transformation with contramap
- **GIVEN** a Runnable `r: Runnable[I, O]` and function `f: I2 => I`
- **WHEN** `r.contramap(f)` is called
- **THEN** all four paradigms of the resulting Runnable apply `f` to inputs before processing
- **AND** the transformation is pure (no effects)

#### Scenario: Parallel execution of two Runnables
- **GIVEN** two Runnables `r1: Runnable[I, O1]` and `r2: Runnable[I, O2]`
- **WHEN** `RunnableOps.parallel(r1, r2)` is called
- **THEN** the invoke paradigm runs both in parallel and returns `(O1, O2)`
- **AND** the stream paradigm zips both streams element-wise
- **AND** execution is concurrent (not sequential)

#### Scenario: Timeout on Runnable
- **GIVEN** a Runnable `r: Runnable[I, O]` and a timeout duration
- **WHEN** `r.timeout(duration)` is called
- **THEN** all four paradigms timeout after the specified duration
- **AND** timeout exceptions are raised if execution exceeds the duration

#### Scenario: Error handling with fallback
- **GIVEN** a Runnable `r: Runnable[I, O]` and a handler function `Throwable => IO[O]`
- **WHEN** `r.handleError(handler)` is called
- **THEN** errors in any paradigm are caught and handled by the handler
- **AND** the handler provides fallback output on error

### Requirement: Component to Runnable Conversion

The system SHALL provide a `ToRunnable[C, I, O]` typeclass for converting components to Runnable with instances for:
- `ChatModel[IO]` as `Runnable[Conversation, Completion]`
- `InvokableTool[IO]` as `Runnable[String, String]`
- `StreamableTool[IO]` as `Runnable[String, String]`
- `Lambda[I, O]` as `Runnable[I, O]`

The conversion SHALL be accessible via an extension method `.asRunnable`.

#### Scenario: Convert ChatModel to Runnable
- **GIVEN** a ChatModel instance
- **WHEN** `model.asRunnable[Conversation, Completion]` is called
- **THEN** a Runnable[Conversation, Completion] is returned
- **AND** invoke calls model.generate
- **AND** stream calls model.stream and accumulates chunks
- **AND** collect consumes the last conversation and generates completion
- **AND** transform evalMaps model.generate on each conversation

#### Scenario: Convert InvokableTool to Runnable
- **GIVEN** an InvokableTool instance
- **WHEN** `tool.asRunnable[String, String]` is called
- **THEN** a Runnable[String, String] is returned
- **AND** all paradigms delegate to the tool's run method

#### Scenario: Convert Lambda to Runnable
- **GIVEN** any Lambda instance
- **WHEN** `lambda.asRunnable[I, O]` is called
- **THEN** the Lambda's toRunnable method is used
- **AND** the resulting Runnable matches the Lambda's paradigm implementation
