# Change: Refactor Orchestration to Remove Runtime Casts

## Why
The orchestration module currently uses `asInstanceOf` and `isInstanceOf` in core compilation paths and tests. The project style guidelines forbid these casts in favor of pattern matching and explicit types, so we need a refactor to ensure type-safe, cast-free implementations.

## What Changes
- Refactor Chain, Graph, and Workflow compilation/execution paths to avoid `asInstanceOf` and `isInstanceOf`.
- Replace test assertions that rely on `isInstanceOf` with pattern matching.
- Enforce cast-free implementations for orchestration builders and branching routing.

## Impact
- Affected specs: orchestration-builders, branching-routing
- Affected code: adk4s-orchestration Chain, Graph, Workflow, WIOBranch/Branch tests
