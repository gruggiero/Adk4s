## Context

The current orchestration layer provides `Graph[Ctx, In, Err, Out]` with `GraphWorkflowContext` where `State = Any`. This approach:
- Loses type safety since `Out <: WCState[Ctx]` is meaningless when State = Any
- Requires `asInstanceOf` casts in WIO compilation (`WIOExecutor.compileFromNodeUnsafe`)
- Includes node types (ChatModelNode, ToolsNode, etc.) that bypass event sourcing

Workflows4s is designed around user-defined `WorkflowContext` objects that declare specific `State` and `Event` types, enabling a 3-parameter WIO type alias: `WIO[-In, +Err, +Out <: State]`. This pattern provides full type safety and event sourcing guarantees.

**Stakeholders**: Users building agents that require event-sourced execution (replay, persistence, audit trails).

## Goals / Non-Goals

**Goals:**
- Provide WIOGraph that mirrors workflows4s patterns with user-defined WorkflowContext
- Include only node types that are WIO-compatible (event-sourced)
- Achieve cast-free WIO compilation using Scala 3 type system features
- Enforce event and state type constraints at compile time
- Enable direct `graph.toWIO` without intermediate representations

**Non-Goals:**
- Replace existing Graph/GraphNode (they serve different use cases)
- Support ChatModelNode, ToolsNode, etc. directly (users wrap in WIORunIONode)
- Provide runtime execution beyond WIO compilation (use workflows4s runtime)
- Backwards compatibility with existing Graph API

## Decisions

### Decision 1: Separate Type Hierarchy (WIONode vs GraphNode)

**Choice**: Create new `WIONode` sealed trait hierarchy separate from existing `GraphNode`.

**Alternatives Considered**:
- A) Modify existing GraphNode to support both modes → Breaks existing code, complex generics
- B) Add adapter layer to convert GraphNode to WIONode → Still requires casts, doesn't solve root problem
- C) **Create parallel hierarchy** → Clean separation, no breaking changes

**Rationale**: Clean separation allows WIONode to enforce constraints (event types, state types) that GraphNode cannot. Users choose the right abstraction for their needs.

### Decision 2: Type-Safe Edge System Using Phantom Types

**Choice**: Use `WIONodeRef[Ctx, I, O]` with phantom types to track node input/output types through graph construction.

```scala
case class WIONodeRef[Ctx <: WorkflowContext, I, O](key: NodeKey)

// Edge addition is type-safe at compile time:
def addEdge[A, B, C](
  from: WIONodeRef[Ctx, A, B],
  to: WIONodeRef[Ctx, B, C]  // B must match!
): WIOGraph[Ctx, In, Err, Out]
```

**Alternatives Considered**:
- A) Runtime type checks → Defeats purpose, still needs casts
- B) Dependent types → Too complex, Scala 3 support limited
- C) **Phantom types on references** → Simple, effective, compiler enforces

**Rationale**: Phantom types let the compiler verify edge compatibility without runtime overhead. The key insight is that type information flows through `WIONodeRef` during graph construction.

### Decision 3: Direct WIO Construction in Nodes

**Choice**: Each `WIONode` variant provides `def toWIO: WIO[I, Err, O, Ctx]` that directly constructs the appropriate WIO case class.

```scala
sealed trait WIONode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]]:
  def toWIO: WIO[I, Err, O, Ctx]

case class WIOPureNode[...](f: I => Either[Err, O]) extends WIONode[...]:
  def toWIO: WIO[I, Err, O, Ctx] = WIO.Pure(f, WIO.Pure.Meta(...))
```

**Alternatives Considered**:
- A) Use WIO builders (WIO.pure.makeFrom...) → Adds indirection, harder to type
- B) Separate compiler phase → Unnecessary complexity
- C) **Direct WIO case class construction** → Most direct, fully typed

**Rationale**: WIO case classes are public in workflows4s. Direct construction avoids builder indirection and keeps types explicit.

### Decision 4: Graph Compilation via Type-Preserving Fold

**Choice**: Compile graph using a type-preserving fold that maintains input/output type relationships.

```scala
def toWIO: WIO[In, Err, Out, Ctx] =
  // Start from entry node, fold through edges
  // Each step: WIO.AndThen(current, next) preserving types
  compileFromEntry(entryRef)

private def compileFromEntry[A](ref: WIONodeRef[Ctx, In, A]): WIO[In, Err, Out, Ctx] =
  // Type A is known at each step, no casts needed
```

**Alternatives Considered**:
- A) Store nodes in Map[NodeKey, WIONode[?, ?, ?, ?]] and cast → Current approach, unsafe
- B) Use HList/HMap for heterogeneous storage → Complex, poor ergonomics
- C) **Track types through references, compile on-demand** → Clean, type-safe

**Rationale**: By keeping type information in `WIONodeRef` during construction and compiling lazily, we avoid the need for heterogeneous collections and casts.

### Decision 5: Event Type Enforcement via Context Bound

**Choice**: Require event types in `WIORunIONode` to extend the context's Event type using a type bound.

```scala
case class WIORunIONode[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
  runIO: I => IO[Evt],
  handleEvent: (I, Evt) => Either[Err, O]
) extends WIONode[Ctx, I, Err, O]
```

**Alternatives Considered**:
- A) Runtime check that Evt is correct type → Defeats type safety
- B) Typeclass evidence → Overcomplicated
- C) **Upper bound on Evt** → Simple, compiler enforces

**Rationale**: `Evt <: WCEvent[Ctx]` is enforced at the type level. Users define their event ADT in their WorkflowContext; any event used must be part of that ADT.

### Decision 6: Package Structure

**Choice**: New package `org.adk4s.orchestration.wiograph` containing:
- `WIOGraph.scala` - Graph builder and compilation
- `WIONode.scala` - Node type hierarchy
- `WIONodeRef.scala` - Type-safe node references

**Rationale**: Parallel to existing `org.adk4s.orchestration.graph` but clearly separate. Users can import one or both depending on needs.

## Risks / Trade-offs

### Risk 1: Type Complexity for Users
**Risk**: WIOGraph requires users to define WorkflowContext with State/Event types, adding boilerplate.
**Mitigation**: Provide clear documentation and example contexts. This complexity is inherent to event sourcing and matches workflows4s patterns users must learn anyway.

### Risk 2: Limited Node Types
**Risk**: Excluding ChatModelNode, ToolsNode forces users to write more verbose WIORunIONode wrappers.
**Mitigation**: Provide factory methods like `WIONode.chatModel(model)` that create properly event-sourced wrappers with appropriate event types. Document the pattern clearly.

### Risk 3: Graph Construction Ergonomics
**Risk**: Type-safe edge validation may make graph construction verbose or error-prone.
**Mitigation**: Design fluent builder API. Use extension methods and type inference to reduce boilerplate:
```scala
graph
  .addPureNode("transform", input => output)
  .addEdge(entryRef, transformRef)  // Types inferred
```

### Risk 4: Parallel Abstractions Confusion
**Risk**: Having both Graph and WIOGraph may confuse users about which to use.
**Mitigation**: Clear documentation: Graph for simple IO-based execution, WIOGraph for event-sourced workflows requiring replay/persistence. Consider deprecation path if WIOGraph proves superior.

## Open Questions

1. **Helper factories for common patterns**: Should we provide `WIONode.chatModel(model, eventType)` helpers that generate proper WIORunIONode wrappers? This would ease migration but adds API surface.

2. **Interop with existing Graph**: Should there be any conversion utilities between Graph and WIOGraph, or keep them completely separate?

## Resolved Questions

### Validation Timing: Hybrid Approach

**Question**: Should graph validation (cycles, missing edges) happen eagerly during construction or lazily at `toWIO` time?

**Decision**: Use a hybrid approach - validate what's possible eagerly, defer global checks to `toWIO`.

#### Analysis

**Option A: Eager Validation (during construction)**

*Pros:*
- Fail-fast: errors detected immediately at the point of mistake
- Better error messages: can pinpoint exactly which operation caused the issue
- No invalid intermediate states
- IDE-friendly: errors appear immediately

*Cons:*
- Incremental construction harder
- Some validations impossible during construction (e.g., "all paths reach end node")
- Verbose API: every method returns `ValidatedNec`, requiring monadic chaining

**Option B: Lazy Validation (at `toWIO` time)**

*Pros:*
- Simpler construction API without `ValidatedNec` wrappers
- Flexible construction order
- All validations in one place
- Matches workflows4s pattern

*Cons:*
- Delayed error detection, harder debugging
- Invalid intermediate states possible
- Potential for wasted work building invalid graphs

**Option C: Hybrid Approach (Selected)**

Validate what's possible eagerly, defer global checks to `toWIO`:

| Check | When | Rationale |
|-------|------|-----------|
| Node key uniqueness | Eager (throw) | Can detect immediately |
| Edge source/target exist | Eager (throw) | Can detect immediately |
| Edge type compatibility | Compile-time | Phantom types enforce this |
| Cycle detection | Lazy | Requires full graph |
| All paths reach end node | Lazy | Requires full graph |
| Entry/end nodes set | Lazy | Order-independent construction |

**Implementation Pattern:**
```scala
case class WIOGraph[...] private (...):
  // Eager: throws on duplicate
  def addNode[...](key: String, node: WIONode[...]): WIOGraph[...] =
    if nodes.contains(NodeKey(key)) then
      throw new IllegalArgumentException(s"Node '$key' already exists")
    else copy(nodes = nodes + (NodeKey(key) -> node))

  // Compile-time: phantom types enforce compatibility
  def addEdge[A, B, C](
    from: WIONodeRef[Ctx, A, B],
    to: WIONodeRef[Ctx, B, C]
  ): WIOGraph[...] = copy(edges = edges :+ Edge(from, to))

  // Lazy: comprehensive validation
  def toWIO: Either[NonEmptyChain[WIOGraphError], WIO[In, Err, Out, Ctx]] =
    for
      _ <- validateNoCycles
      _ <- validateEntrySet
      _ <- validateEndsReachable
    yield compile()
```

**Rationale:**
1. Type-safe edges already enforce the hardest constraint (type compatibility) at compile time
2. Simple errors (duplicates) should fail fast
3. Global graph properties require the full graph and must be lazy
4. Clean construction API - most operations don't need `ValidatedNec`
5. Single validation point at `toWIO` provides clear boundary
