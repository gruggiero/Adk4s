# Change: Add Lambda & Runnable Abstractions

## Why

ADK4S needs core abstractions for wrapping user logic as composable graph nodes, enabling Eino's four streaming paradigms (invoke, stream, collect, transform). This is fundamental for building agent workflows and orchestration.

## What Changes

- Add `Runnable[I, O]` trait with four streaming paradigms
- Add `Lambda[I, O]` ADT with variants for each paradigm
- Add Runnable combinators (andThen, map, contramap, parallel, timeout, error handling)
- Add Component to Runnable conversion typeclass (ChatModel, Tool, Lambda)
- Add comprehensive unit tests for all new functionality

## Impact

- **New capability**: `lambda-runnable`
- **Affected modules**: `adk4s-core` (new module)
- **Dependencies**: fs2 3.9.x, Cats Effect 3.6.3 (already in project)
- **Breaking changes**: None (new module)
