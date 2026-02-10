## Context

ADR-003 establishes that ADK4S `Graph` remains the primary orchestration layer because it is built on Cats Effect IO and fs2 streaming and supports the 4 streaming paradigms.

LLM4S already provides mature orchestration utilities that ADK4S lacks today:
- Policy utilities (retry / timeout / fallback)
- Parallel execution for DAGs (via `PlanRunner`)

This change implements a direct hybrid approach:
- Keep ADK4S `Graph` as the user-facing orchestration API.
- Integrate LLM4S at the boundary by bridging `TypedAgent[I, O]` into `Runnable[I, O]`.
- Port LLM4S policies to Cats Effect as utilities on `Runnable`.
- Enhance Graph execution to support parallel DAG execution.
- Add callbacks/aspects for observability.
- Provide a Chain builder that compiles to `Graph`.

## Goals / Non-Goals

**Goals:**
- Provide LLM4S-style policies (`withRetry`, `withTimeout`, `withFallback`) for `Runnable[I, O]`.
- Allow LLM4S `TypedAgent[I, O]` to be used inside ADK4S Graphs without rewriting it.
- Add parallel DAG execution to the ADK4S Graph executor.
- Add callback/aspect hooks for observability in Graph execution.
- Provide a Chain builder for ergonomic linear workflows (compiling down to Graph).
- Maintain backward compatibility (additive changes only).

**Non-Goals:**
- This change does not involve `WIOGraph` or event-sourced workflow composition.
- This change does not replace ADK4S IO/fs2 execution with Future-based execution.
- This change does not attempt to unify ADK4S Graph and LLM4S orchestration into a single data type.

## Decisions

### Decision 1: Port LLM4S policies onto `Runnable`

**Choice**: Implement Cats Effect policy combinators that decorate `Runnable[I, O]`.

**Rationale**:
- `Runnable` is the common abstraction underneath Graph nodes.
- Policies can be applied consistently regardless of node type (ChatModel, Tools, TypedAgent bridge).

### Decision 2: TypedAgent bridge (Future → IO) at the boundary

**Choice**: Provide a `ToRunnable` (or equivalent) bridge for `TypedAgent[I, O]` by converting `Future` into `IO` via `IO.fromFuture`.

**Rationale**:
- Keeps ADK4S effect model consistent.
- Enables reuse of LLM4S agents as Graph nodes.

### Decision 3: Parallel batch execution in GraphExecutor

**Choice**: Enhance Graph execution to perform parallel execution of independent nodes in DAG layers.

**Rationale**:
- Needed for Eino requirements and for parity with LLM4S `PlanRunner`.
- Keeps user API (Graph builder) unchanged while improving runtime behavior.

### Decision 4: Callbacks/aspects integrated with Graph execution

**Choice**: Add a `GraphCallback` API with lifecycle hooks and integrate it into execution.

**Rationale**:
- Enables observability (tracing, logging, metrics) without contaminating node logic.

### Decision 5: Chain builder compiles to Graph

**Choice**: Provide a fluent Chain builder that internally constructs a Graph.

**Rationale**:
- Makes linear workflows ergonomic while preserving the same execution engine.

## Risks / Trade-offs

### Risk 1: Future/IO boundary semantics

**Risk**: Bridging `Future` to `IO` can introduce semantic mismatches (eager evaluation, cancellation semantics).

**Mitigation**:
- Keep the bridge surface area minimal and explicit.
- Document cancellation limitations of bridged `TypedAgent` steps.

### Risk 2: Parallel execution ordering and determinism

**Risk**: Parallel DAG execution can change execution order versus sequential traversal, affecting side-effect ordering.

**Mitigation**:
- Define explicit semantics: nodes in the same DAG layer may execute concurrently.
- Provide configuration controls for maximum parallelism.

### Risk 3: Callback overhead

**Risk**: Callbacks may add overhead and complexity.

**Mitigation**:
- Make callbacks optional with a no-op default.

## Implementation Plan

### Phase 1: Immediate
1. Add Runnable policy combinators (retry/timeout/fallback)
2. Add TypedAgent→Runnable bridge
3. Add parallel DAG execution to GraphExecutor

### Phase 2: Medium-term
4. Add GraphCallback/aspect system
5. Add Chain builder that compiles to Graph
