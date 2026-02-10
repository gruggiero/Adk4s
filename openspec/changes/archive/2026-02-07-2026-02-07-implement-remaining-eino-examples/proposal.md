# Change: Implement Remaining Eino Examples in adk4s

## Why
The previous change (`2026-02-06-implement-eino-examples`) implemented 19 of 26 Eino examples. Seven remain: 5 partially convertible and 2 unconvertible. The gap analysis (`docs/remaining-eino-examples-analysis.md`) identified concrete features needed to close each gap. Implementing these will:
- Achieve full Eino example coverage (26/26)
- Add high-value features: `BatchExecutor`, `ReactAgent`, `WIOInterruptNode`, `Tool.infer`
- Demonstrate advanced patterns: human-in-the-loop, streaming agents, batch processing, dynamic tool reconfiguration

## What Changes

### Phase 1: BatchExecutor (unlocks `compose/batch`)
- Add `BatchExecutor[F, I, O]` trait to `adk4s-orchestration` — runs a `Runnable` over multiple inputs with configurable concurrency and per-item error isolation
- Uses fs2 `parEvalMap` for bounded parallelism
- Implement `BatchExample.scala`

### Phase 2: ReactAgent (unlocks `flow/agent/react`)
- Add `ReactAgent[F]` trait to `adk4s-agent` — first-class ReAct loop with `generate` (invoke) and `stream` (token-by-token) methods
- Builds on existing `ChatModel`, `ToolsNode`, `WIOGraph` with `WIOLoopNode` + `WIOForkNode`
- Implement `ReactAgentExample.scala`

### Phase 3: DynamicToolRegistry (unlocks `flow/agent/react/dynamic_option`)
- Add `DynamicToolRegistry[F]` to `adk4s-agent` — thread-safe tool list via cats-effect `Ref`, supports add/remove at runtime
- Extend `ReactAgent` to accept `DynamicToolRegistry`
- Implement `DynamicOptionExample.scala`

### Phase 4: WIOInterruptNode (unlocks `graph/react_with_interrupt`)
- Add `WIOInterruptNode[Ctx, I, O]` to `adk4s-orchestration` — pauses execution before a node, serializes state, waits for external resume signal
- Add `CheckpointStore` trait with `InMemoryCheckpointStore` implementation
- Implement `ReactWithInterruptExample.scala`

### Phase 5: Workflow Examples with Explicit Transforms (unlocks `workflow/2_field_mapping` and `workflow/3_data_only`)
- Implement `FieldMappingWorkflowExample.scala` using `WIOPureNode` transforms (no new infrastructure)
- Implement `DataOnlyWorkflowExample.scala` using `WIOParallelNode` + `WIOPureNode` composition (no new infrastructure)

### Phase 6: StreamFieldSplitter (unlocks `workflow/6_stream_field_map`)
- Add `StreamFieldSplitter` and `StreamFieldMerger` utilities to `adk4s-core` — fan-out/fan-in for struct streams using fs2 `broadcastThrough`
- Implement `StreamFieldMapExample.scala`

### Phase 7: Tool Schema Inference (unlocks `components/tool`)
- Add `Tool.infer[I]` to `adk4s-core` — derives JSON Schema from Scala 3 case class using `Mirror` + inline macros
- Add `JsonFixMiddleware` to `adk4s-core` — repairs malformed JSON from LLMs
- Implement `ToolSchemaExample.scala`

### Phase 8: Documentation & Verification
- Update `adk4s-examples/README.md` and `run-example.sh` with all 7 new examples
- Run all 26 examples with MockChatModel
- Update `docs/eino-examples-convertibility-report.md` final score to 26/26

## Impact
- Affected modules: `adk4s-core`, `adk4s-orchestration`, `adk4s-agent` (new module or package), `adk4s-examples`
- New traits/classes: `BatchExecutor`, `ReactAgent`, `DynamicToolRegistry`, `WIOInterruptNode`, `CheckpointStore`, `StreamFieldSplitter`, `StreamFieldMerger`, `Tool.infer`, `JsonFixMiddleware`
- No breaking changes to existing APIs
