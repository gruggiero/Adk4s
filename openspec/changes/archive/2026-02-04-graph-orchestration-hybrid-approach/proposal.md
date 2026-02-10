## Why
 
ADK4S has a strong IO- and streaming-first orchestration layer (`Graph`) and a separate orchestration implementation in LLM4S that provides mature operational policies (retry/timeout/fallback) and parallel DAG execution.
 
ADR-003 adopts a hybrid approach:
- ADK4S `Graph` remains the primary orchestration API because it supports Cats Effect IO and the 4 streaming paradigms.
- LLM4S capabilities are brought into ADK4S via bridges and utilities rather than replacing the ADK4S execution model.
 
Users need:
- LLM4S-style policies (`retry`, `timeout`, `fallback`) on `Runnable[I, O]`.
- A direct way to reuse LLM4S `TypedAgent[I, O]` inside an ADK4S `Graph`.
- Parallel batch execution for DAGs in the ADK4S executor.
- Callbacks/aspects for observability.
- A chain builder for ergonomic linear workflows.
 
## What Changes
 
- Add Cats Effect policy utilities that decorate `Runnable[I, O]`.
- Add a `TypedAgent` bridge that converts `TypedAgent[I, O]` to `Runnable[I, O]`.
- Enhance the ADK4S graph executor with parallel batch execution (ported from LLM4S `PlanRunner`).
- Add a callback/aspect system integrated into graph execution.
- Implement a `Chain` builder that compiles to `Graph`.
 
## Capabilities
 
### New Capabilities
 
- `runnable-policies`: Retry/timeout/fallback on `Runnable[I, O]`.
- `llm4s-typed-agent-bridge`: Use LLM4S `TypedAgent[I, O]` as nodes in ADK4S graphs.
- `graph-parallel-execution`: Execute DAG layers in parallel in the ADK4S graph executor.
- `graph-callbacks`: Lifecycle callbacks/aspects for observability.
- `chain-builder`: Fluent builder for linear workflows that compiles to `Graph`.
 
### Modified Capabilities
 
- `orchestration-builders`: Add Chain builder and convenient node factories (including TypedAgent nodes).
 
## Impact
 
- Affected code: primarily `adk4s-core` (Runnable policies, bridges) and `adk4s-orchestration` (GraphExecutor enhancements, callbacks, chain builder).
- Dependencies: ADK4S remains Cats Effect / fs2 based; LLM4S is used via bridging at the boundary.
- Breaking changes: None (additive APIs).
