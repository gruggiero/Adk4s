## 1. Runnable policy utilities (port from LLM4S)

- [x] 1.1 Add Cats Effect retry utility (exponential backoff) for `Runnable[I, O]`
- [x] 1.2 Add non-blocking timeout utility for `Runnable[I, O]`
- [x] 1.3 Add fallback utility for `Runnable[I, O]`
- [x] 1.4 Ensure policies work across all Runnable paradigms (invoke/stream/collect/transform)

## 2. TypedAgent bridge (LLM4S → ADK4S)

- [x] 2.1 Add conversion from LLM4S `TypedAgent[I, O]` to ADK4S `Runnable[I, O]`
- [x] 2.2 Bridge Future evaluation to IO via `IO.fromFuture`
- [x] 2.3 Provide a Graph node factory that accepts a `TypedAgent[I, O]` (via the Runnable bridge)

## 3. Parallel DAG execution in Graph executor

- [x] 3.1 Implement DAG layer scheduling using Kahn's algorithm (ported from LLM4S `PlanRunner`)
- [x] 3.2 Execute nodes in the same DAG layer concurrently (bounded parallelism)
- [x] 3.3 Preserve deterministic dependency semantics (a node runs only after all prerequisites complete)
- [x] 3.4 Add configuration for maximum parallelism

## 4. Graph callbacks / aspects

- [x] 4.1 Define `GraphCallback` lifecycle hooks (node start, node success, node failure)
- [x] 4.2 Integrate callbacks into graph execution

## 5. Chain builder

- [x] 5.1 Implement a fluent Chain builder for linear graphs
- [x] 5.2 Compile Chain to `Graph` internally

## 6. Testing

- [x] 6.1 Unit tests for Runnable policy combinators
- [x] 6.2 Unit tests for TypedAgent bridge conversion
- [x] 6.3 Integration tests for parallel DAG execution correctness
- [x] 6.4 Tests for callback invocation order and failure cases
- [x] 6.5 Tests for Chain builder compilation to Graph
