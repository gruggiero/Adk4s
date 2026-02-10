## Why

The current `Graph[Ctx, In, Err, Out]` uses `GraphWorkflowContext` with `State = Any`, which defeats WIO type safety and requires `asInstanceOf` casts throughout compilation. Additionally, several node types (ChatModelNode, ToolsNode, StructuredModelNode, StructuredToolNode) bypass event sourcing by performing IO effects that are not captured as events, breaking replay guarantees. A new graph abstraction is needed that follows workflows4s patterns with user-defined WorkflowContext and only includes WIO-compatible node types.

## What Changes

- Add `WIOGraph[Ctx, In, Err, Out]` builder requiring user-defined WorkflowContext with explicit State and Event types
- Add WIO-compatible node types:
  - `WIOPureNode` - pure transformations without IO
  - `WIORunIONode` - IO effects with explicit event handling for replay
  - `WIOForkNode` - branching using WIO.Fork semantics
  - `WIOLoopNode` - iteration with stop conditions
  - `WIOAwaitNode` - timer-based waiting
  - `WIOHandleSignalNode` - external signal handling
  - `WIOParallelNode` - concurrent execution with result collection
  - `WIOSubGraphNode` - nested graph embedding
- Add `WIONodeRef[Ctx, I, O]` for compile-time type-safe edge validation
- Add direct `toWIO` compilation without `asInstanceOf` or `isInstanceOf`
- Enforce event types extend context's Event type at compile time
- Enforce output types extend context's State type at compile time

## Capabilities

### New Capabilities
- `wio-graph`: Type-safe graph abstraction directly interpretable as WIO steps, with user-defined WorkflowContext, WIO-compatible node types, and cast-free compilation

### Modified Capabilities
<!-- No existing specs are being modified - this is a new parallel abstraction -->

## Impact

- **New package**: `org.adk4s.orchestration.wiograph` (or similar)
- **New types**: `WIOGraph`, `WIONode` (sealed trait with variants), `WIONodeRef`
- **Dependencies**: Uses workflows4s WIO types directly (WIO, WorkflowContext, SignalDef, Timer)
- **Existing code**: No breaking changes - current Graph/GraphNode remain unchanged; WIOGraph is an alternative for users needing event-sourced execution
- **Testing**: New test suite for WIOGraph compilation and type safety
