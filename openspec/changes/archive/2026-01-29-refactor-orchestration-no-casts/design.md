## Context
The orchestration module includes multiple runtime casts that violate project style guidelines. These casts appear in Chain compilation, Graph execution placeholders, Workflow compilation, and branching tests. The refactor must eliminate runtime casts and rely on pattern matching or typed helpers while keeping APIs intact.

## Goals / Non-Goals
- Goals:
  - Remove all `asInstanceOf` and `isInstanceOf` usage from adk4s-orchestration source and tests.
  - Preserve existing public APIs and behavior for Chain, Graph, Workflow, and Branch/WIOBranch.
  - Use pattern matching and typed helpers to keep the implementation explicit and safe.
- Non-Goals:
  - Redesign the orchestration APIs.
  - Introduce new runtime dependencies.

## Decisions
- Decision: Use typed helpers and pattern matching to replace runtime casts.
  - Chain compilation should use a typed fold/recursion to compose `Runnable` values without casting.
  - Workflow compilation should use typed identity runnables instead of casting input.
  - Graph execution placeholders should be refactored to avoid casting by delegating to typed executors.
- Decision: Replace `isInstanceOf` assertions with pattern matching in tests.
  - Tests should confirm types through `match` and fail explicitly on unexpected cases.

## Risks / Trade-offs
- Removing casts may require additional helper data structures or recursive compilation logic.
- Some changes could expose existing gaps in type tracking that need focused refactors.

## Migration Plan
1. Introduce typed composition helpers for Chain and Workflow compilation.
2. Refactor Graph execution stubs to return typed results without casting.
3. Update tests to use pattern matching for type assertions.
4. Verify that all uses of `isInstanceOf` and `asInstanceOf` are removed in the orchestration module.

## Open Questions
- If a type-safe alternative cannot be found for a specific cast, should we introduce a new typed wrapper API or split the code path?
