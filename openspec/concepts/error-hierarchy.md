# Concept: ErrorHierarchy

## Concept specification

```
concept ErrorHierarchy
purpose
    Provide a single sealed error hierarchy (AdkError extends Throwable) for
    all toolkit errors, enabling pattern matching and typed error handling
    across modules. Variants cover LLM call failures, structured output
    errors, graph validation, tool execution, interrupts, and configuration
    refinement failures.
state
    AdkError : sealed trait extends Throwable
    variants
        LlmCallError, StructuredOutputError, TypeMismatchError,
        MissingFieldError, NodeNotFoundError, EdgeValidationError,
        MaxStepsExceededError, GraphCompiledError, GraphEntryMissingError,
        GraphEndNodesMissingError, ToolNotFoundError, ToolExecutionError,
        StateTypeMismatchError, NodeAlreadyExistsError, SourceNodeNotFoundError,
        NodeDoesNotExistError, FanInError, BranchTargetError,
        AgentInterruptedException, CheckpointNotFoundError, GenericError,
        NodeKeyError, StateDecodeError,
        ConfigError(field, invalidValue, constraint),
        GraphCompilationError(errors: List[AdkError])
actions
    show [ error: AdkError ]
        => [ message: String ]
```

## Implementation map

| Symbol | Kind | File |
|--------|------|------|
| `AdkError` | sealed trait | `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala` |
| `ConfigError` | case class | `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala` |
| `GraphCompilationError` | case class | `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala` |
