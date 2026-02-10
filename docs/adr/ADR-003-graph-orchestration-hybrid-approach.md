# ADR-003: Graph Orchestration Hybrid Approach

## Status

**Accepted**

## Date

2026-02-03

## Context

ADK4S aims to replicate the orchestration features of the Eino framework (ByteDance's Go-based LLM framework). The project currently has two graph implementations:

1. **ADK4S Graph** (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/`)
   - Built on Cats Effect IO and fs2 Streams
   - Provides specialized node types (ChatModel, Tools, StructuredModel, etc.)
   - Uses `ValidatedNec` for error accumulation
   - Compiles to `Runnable[In, Out]` with 4 streaming paradigms

2. **LLM4S Orchestration** (`llm4s/modules/core/src/main/scala/org/llm4s/agent/orchestration/`)
   - Built on Scala Futures
   - Uses generic `TypedAgent[I, O]` abstraction
   - Provides built-in policies (retry, timeout, fallback)
   - Has parallel batch execution via `PlanRunner`

### Eino Requirements (from `docs/eino-analysis/02-core-features.md`)

| Feature | Required | ADK4S | LLM4S |
|---------|----------|-------|-------|
| Linear chains | Yes | Yes | Yes |
| DAG support | Yes | Yes | Yes |
| Type-safe edges | Yes | Yes | Yes |
| 4 streaming paradigms | Yes | Yes | No |
| Retry/Timeout/Fallback | Yes | No | Yes |
| Parallel execution | Yes | Limited | Yes |
| Specialized LLM nodes | Yes | Yes | No |
| Tool integration | Yes | Yes | No |
| State management | Yes | Via WIO | No |
| Callbacks/Aspects | Yes | No | No |

### Key Architectural Differences

**Effect System:**
```
ADK4S:  cats.effect.IO  →  Referential transparency, cancellation, resource safety
LLM4S:  scala.Future    →  Eager execution, no referential transparency
```

**Streaming:**
```
ADK4S:  fs2.Stream[IO, T]  →  Pull-based, backpressure, resource-safe
LLM4S:  None               →  Batch processing only
```

## Decision

We will adopt a **Hybrid Approach** where:

1. **ADK4S Graph remains the primary orchestration layer** because:
   - Cats Effect IO is essential for resource safety and composability
   - fs2 streaming provides all 4 Eino paradigms (invoke, stream, collect, transform)
   - Specialized node types improve ergonomics and error messages
   - `ValidatedNec` error accumulation is superior for user feedback

2. **Port LLM4S Policies to Cats Effect** as utilities on `Runnable[I, O]`:
   - `withRetry` - Exponential backoff retry
   - `withTimeout` - Non-blocking timeout
   - `withFallback` - Fallback on failure

3. **Add TypedAgent bridge** to allow LLM4S agents in ADK4S graphs:
   - `ToRunnable[TypedAgent[I, O], I, O]` given instance
   - Converts Future-based execution to IO via `IO.fromFuture`

4. **Implement parallel batch execution** in GraphExecutor:
   - Port Kahn's algorithm from LLM4S `PlanRunner`
   - Execute independent nodes in parallel via `parTraverse`

5. **Add Callback/Aspect system** for observability:
   - `GraphCallback` trait with lifecycle hooks
   - Integration with GraphExecutor

6. **Implement Chain builder** for simplified linear graphs:
   - Fluent API matching Eino's Chain
   - Compiles to Graph internally

### Alternatives Considered

| Alternative | Pros | Cons | Decision |
|-------------|------|------|----------|
| **Rewrite ADK4S on LLM4S** | Single codebase | Effect system mismatch; loses streaming | **Rejected** |
| **Duplicate LLM4S in ADK4S** | Full control | Maintenance burden; violates DRY | **Rejected** |
| **Use LLM4S only** | Simpler | No streaming; no Cats Effect | **Rejected** |
| **Hybrid (chosen)** | Best of both | Requires bridges | **Accepted** |

## Consequences

### Positive

- ADK4S retains streaming and Cats Effect benefits
- Users get retry/timeout/fallback policies
- Parallel execution improves performance on DAGs
- TypedAgent bridge enables code reuse from LLM4S
- Callbacks enable observability and debugging

### Negative

- Bridge layer adds some complexity
- Two effect systems (IO and Future) coexist in codebase
- Policies must be maintained separately from LLM4S

### Neutral

- Specialized node types remain in ADK4S (ChatModelNode, ToolsNode, etc.)
- LLM4S orchestration remains available for non-Cats Effect use cases

## Implementation Plan

### Phase 1: Immediate Actions

1. **Policies.scala** - Port retry/timeout/fallback to Cats Effect
2. **TypedAgentBridge.scala** - Create ToRunnable instance for TypedAgent
3. **GraphExecutor enhancement** - Add parallel batch execution

### Phase 2: Medium-term Actions

4. **GraphCallback.scala** - Add callback/aspect system
5. **Chain.scala** - Simplified linear graph builder

## References

- `docs/eino-analysis/02-core-features.md` - Eino feature requirements
- `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`
- `llm4s/modules/core/src/main/scala/org/llm4s/agent/orchestration/Policies.scala`
- `llm4s/modules/core/src/main/scala/org/llm4s/agent/orchestration/PlanRunner.scala`
