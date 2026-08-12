# Spec: Middleware Stack

<!-- This is a DELTA spec for the `add-harness-api-phase0` change. It
     introduces `MiddlewareStack[F]` — a monoid of `AgentMiddleware[F]`
     values with stack-order semantics per hook — plus `StackError`
     (construction-time validation) and the sub-agent boundary operation
     (`HarnessState.project` / `mergeBack` with three visibility levels).

     This spec depends on the `agent-middleware` spec's types
     (`AgentMiddleware[F]`, `ModelStep[F]`, `ToolStep[F]`, `PromptSection`,
     `MiddlewareName`) and the `harness-state` spec's types
     (`HarnessState`, `StateCell[A]`, `CellVisibility`).

     Grounded in `docs/deepagents4s-phase0-agent-middleware-DESIGN.md` §5
     (Composition) and §7 (Laws L1–L6, L9–L11). -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| agent-middleware (NEW — created by agent-middleware spec) | The middleware values that the stack composes; stack-order semantics govern how each hook is distributed | `openspec/concepts/agent-middleware.md` (created at apply Step 12) |
| harness-state (NEW — created by harness-state spec) | The state that `project`/`mergeBack` operate on across sub-agent boundaries | `openspec/concepts/harness-state.md` (created at apply Step 12) |
| Tool | Tool contributions are concatenated in stack order; duplicate tool names are a construction-time error | [tool.md](../../../concepts/tool.md) |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `InvokableTool[F[_]]` | trait | `org.adk4s.core.component` |
| `HarnessState` | final class (introduced by spec:harness-state) | `org.adk4s.harness` |
| `StateCell[A]` | final class (introduced by spec:harness-state) | `org.adk4s.harness` |
| `CellVisibility` | enum (introduced by spec:harness-state) | `org.adk4s.harness` |
| `AgentMiddleware[F[_]]` | trait (introduced by spec:agent-middleware) | `org.adk4s.harness` |
| `ModelStep[F]` | type alias (introduced by spec:agent-middleware) | `org.adk4s.harness` |
| `ToolStep[F]` | type alias (introduced by spec:agent-middleware) | `org.adk4s.harness` |
| `PromptSection` | case class (introduced by spec:agent-middleware) | `org.adk4s.harness` |
| `MiddlewareName` | opaque type (introduced by spec:agent-middleware) | `org.adk4s.harness` |
| `cats.data.Kleisli` | type class | `cats.data` |
| `cats.data.NonEmptyList` | data structure | `cats.data` |
| `cats.Monad` | type class | `cats` |
| `cats.Applicative` | type class | `cats` |
| Hedgehog `property` / `Range` / `Gen` | property test kit | `hedgehog` / `hedgehog.munit` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `MiddlewareStack[F[_]]` | final case class | Monoid of middlewares with stack-order semantics per hook. Carries `middlewares: List[AgentMiddleware[F]]` (private constructor). Provides `allCells`, `allTools`, `allSections(state)`, `beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`. Identity is `MiddlewareStack.empty`. |
| `StackError` | enum | `DuplicateCellId(id: StateCell.CellId, owners: List[MiddlewareName])` / `DuplicateToolName(name: String, owners: List[MiddlewareName])` — construction-time validation failures. |

> **COMMITMENT**: These concepts are introduced by this spec and MUST be
> added to `openspec/concept-inventory.md` during apply Step 12. The
> package is `org.adk4s.harness` (new module `adk4s-harness-api`).

## ADDED Requirements

### Requirement: MiddlewareStack is a monoid under list concatenation with stack-order semantics

The system SHALL provide `MiddlewareStack[F[_]]` as a final case class wrapping `List[AgentMiddleware[F]]` with a private constructor. A stack `[m1, m2, m3]` SHALL compose hooks with the following stack-order semantics: `beforeAgent` sequences m1 → m2 → m3 (Kleisli left-to-right fold); `afterAgent` sequences m3 → m2 → m1 (reverse — teardown mirrors setup); `wrapModelCall` and `wrapToolCall` compose m1 outermost — `m1(m2(m3(base)))` — via a right fold; `tools` and `stateCells` concatenate in stack order; `promptSections(state)` concatenates in stack order, folded per-request from the current `HarnessState`. `MiddlewareStack.empty` SHALL be the identity element (empty middleware list).

**Given** a stack `MiddlewareStack(List(m1, m2, m3))` and a base `ModelStep[F]`
**When** `stack.wrapModelCall(base)` is invoked
**Then** the result is observationally equal to `m1.wrapModelCall(m2.wrapModelCall(m3.wrapModelCall(base)))` — m1 sees the request first and the response last

**Rationale**: deepagents orders hooks by stack position; the composition must be observationally real (not just syntactic) so that the §7 monoid laws hold. The reverse `afterAgent` mirrors setup/teardown pairing. Per-request `promptSections` folding (§4.4) ensures state-aware sections reflect the current `HarnessState`, not a snapshot taken at construction.

#### Scenario: beforeAgent runs in stack order

**Given** a stack `[m1, m2, m3]` where each `beforeAgent` appends its name to a trace list
**When** `stack.beforeAgent(state)` is run
**Then** the trace is `["m1", "m2", "m3"]` — left-to-right stack order

#### Scenario: afterAgent runs in reverse stack order

**Given** a stack `[m1, m2, m3]` where each `afterAgent` appends its name to a trace list
**When** `stack.afterAgent(state)` is run
**Then** the trace is `["m3", "m2", "m1"]` — reverse stack order (teardown mirrors setup)

#### Scenario: wrapModelCall nests m1 outermost

**Given** a stack `[m1, m2, m3]` and a base step that records a trace on invocation
**When** `stack.wrapModelCall(base)` is run with a request
**Then** the request trace shows m1's rewrite first, then m2's, then m3's, then the base; the response trace shows base first, then m3, then m2, then m1

#### Scenario: empty stack is the identity

**Given** `MiddlewareStack.empty[F]`
**When** `beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`, `allCells`, `allTools`, `allSections(state)` are each invoked
**Then** `beforeAgent(s)` returns `s` unchanged, `afterAgent(s)` returns `s` unchanged, `wrapModelCall(base)` returns `base` unchanged, `wrapToolCall(base)` returns `base` unchanged, `allCells` is `Nil`, `allTools` is `Nil`, `allSections(state)` is `Nil`

#### Scenario: allSections folds per-request from current state

**Given** a stack `[m1, m2]` where `m1.promptSections(state)` returns `[PromptSection("m1", state.get(cellA))]` and `m2.promptSections(state)` returns `[PromptSection("m2", "static")]`
**When** `stack.allSections(stateA)` is called with `stateA` having `cellA = "hello"`, then `stack.allSections(stateB)` is called with `stateB` having `cellA = "world"`
**Then** the first call returns `[PromptSection("m1", "hello"), PromptSection("m2", "static")]` and the second returns `[PromptSection("m1", "world"), PromptSection("m2", "static")]` — the section text reflects the state passed at call time, not a cached snapshot

### Requirement: Aggregation methods concatenate in stack order

The system SHALL provide `allCells: List[StateCell[?]]`, `allTools: List[InvokableTool[F]]`, and `allSections(state: HarnessState): List[PromptSection]` on `MiddlewareStack[F]`. Each SHALL concatenate its per-middleware contributions in stack order (m1's contributions first, then m2's, etc.) via `flatMap`. `allCells` and `allTools` are stack-order concatenations of `stateCells` and `tools` respectively. `allSections(state)` SHALL be a per-request fold over `middlewares.flatMap(_.promptSections(state))`.

**Given** a stack `[m1, m2]` where `m1.stateCells = [cA]`, `m2.stateCells = [cB]`, `m1.tools = [t1]`, `m2.tools = [t2]`, `m1.promptSections(s) = [sec1]`, `m2.promptSections(s) = [sec2]`
**When** `allCells`, `allTools`, and `allSections(anyState)` are invoked
**Then** `allCells == List(cA, cB)`, `allTools == List(t1, t2)`, `allSections(anyState) == List(sec1, sec2)` — all in stack order

**Rationale**: The loop consumes `allCells` for initial state and restore, `allTools` for the per-request tool list, and `allSections(state)` for prompt assembly. Stack-order concatenation makes the prompt deterministic and inspectable section-by-section.

#### Scenario: empty stack aggregates to empty

**Given** `MiddlewareStack.empty[F]`
**When** `allCells`, `allTools`, `allSections(HarnessState.empty)` are invoked
**Then** all three return `Nil`

#### Scenario: middleware with default empty contributions

**Given** a stack `[m1]` where `m1` overrides nothing (all defaults: `stateCells = Nil`, `tools = Nil`, `promptSections(_) = Nil`)
**When** `allCells`, `allTools`, `allSections(state)` are invoked
**Then** all three return `Nil` — defaults contribute nothing

### Requirement: Validated construction rejects duplicate cell ids and duplicate tool names

The system SHALL provide `MiddlewareStack.validated[F[_]](ms: List[AgentMiddleware[F]]): Either[NonEmptyList[StackError], MiddlewareStack[F]]` that checks for duplicate `StateCell.CellId`s across all `allCells` and duplicate tool names across all `allTools`. Duplicate cell ids SHALL produce `StackError.DuplicateCellId(id, owners)` listing every `MiddlewareName` that declares the colliding id. Duplicate tool names SHALL produce `StackError.DuplicateToolName(name, owners)` listing every `MiddlewareName` that contributes the colliding tool. All detected errors SHALL be accumulated into a `NonEmptyList[StackError]` (not short-circuited to the first). A valid stack SHALL return `Right(MiddlewareStack(ms))`.

**Given** two middlewares `m1` and `m2` where both declare a `StateCell` with `CellId("owner/cell")`
**When** `MiddlewareStack.validated(List(m1, m2))` is called
**Then** the result is `Left(NonEmptyList.of(StackError.DuplicateCellId("owner/cell", List(m1.name, m2.name))))` — construction fails at `Either`, not at runtime

**Rationale**: This is the guarantee that discharges the `HarnessState.get` cast argument (§3.3 of design doc): id uniqueness within a stack is validated at construction time, so the stored value is always the `A` of the declaring cell. Accumulating all errors (not short-circuiting) gives the stack author the full picture of collisions in one pass. Matching deepagents' fail-fast-on-collisions behavior but at `Either` rather than at exception.

#### Scenario: duplicate cell id detected

**Given** `m1` declaring `StateCell(MiddlewareName("m1"), "count", 0)` and `m2` declaring `StateCell(MiddlewareName("m2"), "count", 0)` — both produce `CellId` with the same `owner/name` if owners match, or the same `CellId` if the id strings collide
**When** `MiddlewareStack.validated(List(m1, m2))` is called
**Then** the result is a `Left` containing `StackError.DuplicateCellId` with the colliding id and both owner names

#### Scenario: duplicate tool name detected

**Given** `m1` contributing a tool named `"search"` and `m2` contributing a tool named `"search"`
**When** `MiddlewareStack.validated(List(m1, m2))` is called
**Then** the result is a `Left` containing `StackError.DuplicateToolName("search", List(m1.name, m2.name))`

#### Scenario: multiple errors accumulated

**Given** `m1` and `m2` with both a duplicate cell id AND a duplicate tool name
**When** `MiddlewareStack.validated(List(m1, m2))` is called
**Then** the result is a `Left` whose `NonEmptyList` contains both `DuplicateCellId` and `DuplicateToolName` — not just the first error

#### Scenario: valid stack returns Right

**Given** `m1` and `m2` with disjoint cell ids and disjoint tool names
**When** `MiddlewareStack.validated(List(m1, m2))` is called
**Then** the result is `Right(MiddlewareStack(List(m1, m2)))` — a usable stack

#### Scenario: empty list validates to empty stack

**Given** an empty list `Nil`
**When** `MiddlewareStack.validated(Nil)` is called
**Then** the result is `Right(MiddlewareStack.empty)` — the identity element

#### Scenario: private constructor prevents unvalidated construction

**Given** code that attempts `MiddlewareStack(List(m1, m2))` directly (bypassing `validated`)
**When** compilation is attempted
**Then** compilation fails — the constructor is private; only `validated` and `empty` can produce a `MiddlewareStack`

### Requirement: StackError is a sealed enum with two variants

The system SHALL define `StackError` as a sealed enum with exactly two variants: `DuplicateCellId(id: StateCell.CellId, owners: List[MiddlewareName])` and `DuplicateToolName(name: String, owners: List[MiddlewareName])`. Every match over `StackError` SHALL be exhaustive — no catch-all arm is permitted.

**Given** a `StackError` value
**When** it is pattern-matched
**Then** the match must handle both `DuplicateCellId` and `DuplicateToolName` or compilation fails (no catch-all allowed)

**Rationale**: A closed enum enables exhaustive pattern matching so that adding a variant in a later phase is a compile error, not a silent fall-through. The `owners` list on each variant attributes the collision to every responsible middleware, aiding debugging.

#### Scenario: DuplicateCellId carries the colliding id and owners

**Given** `StackError.DuplicateCellId(CellId("m/count"), List(MiddlewareName("m1"), MiddlewareName("m2")))`
**When** the `id` and `owners` fields are read
**Then** `id` is `CellId("m/count")` and `owners` is `List(MiddlewareName("m1"), MiddlewareName("m2"))`

#### Scenario: DuplicateToolName carries the colliding name and owners

**Given** `StackError.DuplicateToolName("search", List(MiddlewareName("m1"), MiddlewareName("m2")))`
**When** the `name` and `owners` fields are read
**Then** `name` is `"search"` and `owners` is `List(MiddlewareName("m1"), MiddlewareName("m2"))`

#### Scenario: non-exhaustive match fails to compile

**Given** a `StackError` value
**When** a pattern match handles only `DuplicateCellId` with no `DuplicateToolName` arm and no catch-all
**Then** compilation fails — exhaustiveness escalation makes this a compile error

### Requirement: Sub-agent boundary project maps parent state to child initial state by visibility

The system SHALL provide `HarnessState.project(parent: HarnessState, declared: List[StateCell[?]]): HarnessState` that produces the child's initial state from the parent's state. For each declared cell: `Private` cells SHALL be set to the cell's `initial` value (the child sees `initial`, never the parent's value); `Inherited` cells SHALL copy the parent's current value into the child; `Shared` cells SHALL copy the parent's current value into the child. The child state SHALL be initialized from `HarnessState.initial(declared)` and then updated per visibility.

**Given** a parent state where a `Private` cell `p` has value `"secret"`, an `Inherited` cell `i` has value `"config"`, and a `Shared` cell `s` has value `42`
**When** `HarnessState.project(parent, List(p, i, s))` is called
**Then** the child state has `p` at `p.initial` (NOT `"secret"`), `i` at `"config"` (copied from parent), and `s` at `42` (copied from parent)

**Rationale**: Privacy is structural — a `Private` cell's parent value is unobservable by the child by construction (deepagents enforces only the write-back half via name filtering; here both directions hold). `Inherited` is read-only inheritance (configuration flowing down without a write-back channel). `Shared` copies down and merges back (§5.3 of design doc).

#### Scenario: Private cell reads as initial in child

**Given** a parent with `Private` cell `p` (initial = 0) set to value 99
**When** `project(parent, List(p))` is called and the child reads `p`
**Then** `child.get(p) == 0` (the initial), NOT 99 — the parent's value is invisible

#### Scenario: Inherited cell copies parent value

**Given** a parent with `Inherited` cell `i` (initial = "default") set to value "production"
**When** `project(parent, List(i))` is called and the child reads `i`
**Then** `child.get(i) == "production"` — the parent's value is copied down

#### Scenario: Shared cell copies parent value

**Given** a parent with `Shared` cell `s` (initial = 0) set to value 42
**When** `project(parent, List(s))` is called and the child reads `s`
**Then** `child.get(s) == 42` — the parent's value is copied down

#### Scenario: cells unknown to the child are untouched in the parent

**Given** a parent with cells `{p (Private), s (Shared)}` and a child whose declared cells are only `{s}`
**When** `project(parent, List(s))` is called
**Then** the child state contains only `s`; the parent's `p` is not present in the child and remains unchanged in the parent

### Requirement: Sub-agent boundary mergeBack folds child final states into parent by visibility

The system SHALL provide `HarnessState.mergeBack(parent: HarnessState, children: List[HarnessState], declared: List[StateCell[?]]): HarnessState` that merges child final states back into the parent. For each declared cell: `Shared` cells SHALL fold `cell.merge` over the children's values (starting from the parent's value); `Private` and `Inherited` cells SHALL be dropped (child writes are discarded — the parent value is unchanged). The fold order for `Shared` cells over multiple children SHALL be deterministic given the children list order.

**Given** a parent with `Shared` cell `s` at value 10, and two children with `s` at values 30 and 50, where `merge = (a, b) => a + b`
**When** `mergeBack(parent, List(child1, child2), List(s))` is called
**Then** the parent's `s` is `10 + 30 + 50 = 90` — folded via `cell.merge` starting from the parent value

**Rationale**: `Private`/`Inherited` child writes are dropped by construction (privacy is bidirectional). `Shared` folds `cell.merge`, which is where the semilattice requirement (§3.5) earns its keep: for semilattice merges the fold is order-independent, so parallel `task` results commute. The parent value is the seed of the fold, so a child that did not touch the cell contributes the projected value (which was the parent's value), making merge-back neutral for untouched children (L10).

#### Scenario: Shared cell merges back

**Given** a parent with `Shared` cell `s` at value 10, and one child with `s` at value 30, `merge = (parent, child) => child` (last-write-wins)
**When** `mergeBack(parent, List(child), List(s))` is called
**Then** the parent's `s` is 30 — the child's value replaces the parent's via the merge function

#### Scenario: Private cell is unchanged after mergeBack

**Given** a parent with `Private` cell `p` at value 99, and one child with `p` at value 0 (the initial, since the child saw `initial`)
**When** `mergeBack(parent, List(child), List(p))` is called
**Then** the parent's `p` is still 99 — the child's write is discarded

#### Scenario: Inherited cell is unchanged after mergeBack

**Given** a parent with `Inherited` cell `i` at value "production", and one child with `i` at value "child-override"
**When** `mergeBack(parent, List(child), List(i))` is called
**Then** the parent's `i` is still "production" — the child's write is discarded (read-only inheritance)

#### Scenario: mergeBack with no children is identity

**Given** a parent state and an empty children list `Nil`
**When** `mergeBack(parent, Nil, declared)` is called
**Then** the result equals the parent — no children means no merge

#### Scenario: mergeBack with multiple children folds in list order

**Given** a parent with `Shared` cell `s` at value 0, children `[c1, c2, c3]` with `s` at values `[1, 2, 3]`, and `merge = (a, b) => a + b`
**When** `mergeBack(parent, List(c1, c2, c3), List(s))` is called
**Then** the parent's `s` is `0 + 1 + 2 + 3 = 6` — folded in children list order

### Requirement: mergeBack is order-independent for semilattice merges

When every `Shared` cell's `merge` function is a join semilattice (commutative, associative, idempotent), `mergeBack` SHALL produce the same parent state for any permutation of the children list. This is the parallel-delegation correctness property: concurrently produced child states merge deterministically regardless of completion order.

**Given** a parent with a `Shared` cell `s` whose `merge` is set union (a semilattice), and three children with `s` values `Set(1, 2)`, `Set(2, 3)`, `Set(3, 4)`
**When** `mergeBack(parent, List(c1, c2, c3), List(s))` and `mergeBack(parent, List(c3, c1, c2), List(s))` are both called
**Then** both produce the same parent `s` value: `Set(1, 2, 3, 4)` — order-independent

**Rationale**: deepagents explicitly encourages parallel `task` calls; LangGraph resolves concurrent writes with channel reducers that may be order-sensitive. This port is deliberately stronger: the semilattice discipline (§3.5) guarantees order-independence, making parallel delegation safe. The `SemilatticeLaws` testkit property checks commutativity, associativity, and idempotence per cell.

#### Scenario: union-merge is order-independent

**Given** a parent with `Shared` cell `s` (initial = `Set.empty`) whose `merge = (a, b) => a.union(b)`, and children `[c1, c2]` with `s` values `Set(1, 2)` and `Set(3, 4)`
**When** `mergeBack(parent, List(c1, c2), List(s))` and `mergeBack(parent, List(c2, c1), List(s))` are both called
**Then** both results have `s == Set(1, 2, 3, 4)` — identical regardless of child order

#### Scenario: max-merge is order-independent

**Given** a parent with `Shared` cell `counter` (initial = 0) whose `merge = (a, b) => Math.max(a, b)`, and children with `counter` values `[5, 3, 8]`
**When** `mergeBack` is called with children in orders `[c1, c2, c3]` and `[c3, c2, c1]`
**Then** both results have `counter == 8` — identical regardless of child order

#### Scenario: last-write-wins is NOT order-independent (known limitation)

**Given** a parent with `Shared` cell `s` whose `merge = (parent, child) => child` (last-write-wins, NOT a semilattice), and children `[c1, c2]` with `s` values `"a"` and `"b"`
**When** `mergeBack(parent, List(c1, c2), List(s))` and `mergeBack(parent, List(c2, c1), List(s))` are both called
**Then** the first yields `"b"` and the second yields `"a"` — order-dependent; this is why the semilattice discipline is required for parallel-shared cells, not for sequential-only shared cells

### Requirement: Monoid identity law holds for any insertion position

For any stack `S` and any position (head, middle, or tail), inserting `AgentMiddleware.id` into `S` SHALL produce a stack observationally equivalent to `S`. Observational equivalence means: equal final `AssistantMessage`, equal final `HarnessState.snapshot`, and equal request traces at the base step, when driven by the testkit's deterministic `ChatModel` double with a fixed tool set and input.

**Given** a stack `S` and `AgentMiddleware.id`
**When** `insert(S, AgentMiddleware.id, position)` is run for each position ∈ {head, middle, tail} alongside `S`
**Then** each inserted stack is observationally equivalent to `S` — the identity middleware contributes nothing

**Rationale**: This is law L1. The monoid identity must hold observationally so that inserting a no-op middleware (e.g. a logging middleware that overrides nothing) cannot change agent behavior. The property pins it observationally so future stack-implementation changes cannot break it.

#### Scenario: identity at head

**Given** a stack `[m1, m2]` and `AgentMiddleware.id`
**When** `[id, m1, m2]` and `[m1, m2]` are run on the same input
**Then** both produce equal final messages, equal state snapshots, and equal request traces

#### Scenario: identity at tail

**Given** a stack `[m1, m2]` and `AgentMiddleware.id`
**When** `[m1, m2, id]` and `[m1, m2]` are run on the same input
**Then** both produce equal final messages, equal state snapshots, and equal request traces

#### Scenario: identity in middle

**Given** a stack `[m1, m2]` and `AgentMiddleware.id`
**When** `[m1, id, m2]` and `[m1, m2]` are run on the same input
**Then** both produce equal final messages, equal state snapshots, and equal request traces

### Requirement: Monoid associativity law holds observationally

For any three stacks `a`, `b`, `c`, the stack `(a ++ b) ++ c` SHALL be observationally equivalent to `a ++ (b ++ c)`. Stack concatenation `++` SHALL be defined as `MiddlewareStack(a.middlewares ++ b.middlewares)`.

**Given** three stacks `a`, `b`, `c`
**When** `(a ++ b) ++ c` and `a ++ (b ++ c)` are run on the same input
**Then** both produce equal final messages, equal state snapshots, and equal request traces

**Rationale**: This is law L2. Associativity is true syntactically for the fold-based combinators (list concatenation is associative); the property pins it observationally so future stack-implementation changes cannot break it. This allows stack authors to freely regroup sub-stacks without behavioral concern.

#### Scenario: three single-middleware stacks

**Given** three stacks `a = [m1]`, `b = [m2]`, `c = [m3]`
**When** `(a ++ b) ++ c` (yielding `[m1, m2, m3]`) and `a ++ (b ++ c)` (yielding `[m1, m2, m3]`) are run on the same input
**Then** both produce equal final messages, equal state snapshots, and equal request traces

#### Scenario: regrouping multi-element sub-stacks

**Given** `a = [m1, m2]`, `b = [m3]`, `c = [m4, m5]`
**When** `(a ++ b) ++ c` and `a ++ (b ++ c)` are run on the same input
**Then** both produce equal results — regrouping does not change behavior

### Requirement: Hook distribution law holds for wrapModelCall and wrapToolCall

For a two-element stack `[m1, m2]`, `stack.wrapModelCall(base)` SHALL be observationally equal to `m1.wrapModelCall(m2.wrapModelCall(base))`, and `stack.wrapToolCall(base)` SHALL be observationally equal to `m1.wrapToolCall(m2.wrapToolCall(base))`. Equality is tested by trace equality at the base step.

**Given** a stack `[m1, m2]` and a base `ModelStep[F]`
**When** `stack.wrapModelCall(base)` and `m1.wrapModelCall(m2.wrapModelCall(base))` are both run
**Then** both produce equal request traces at the base step and equal responses

**Rationale**: This is law L3. It confirms that the fold-based composition distributes hooks correctly — m1 is outermost, m2 is inner, base is innermost. The same structure applies to `wrapToolCall`.

#### Scenario: wrapModelCall distribution

**Given** a stack `[m1, m2]` where `m1.wrapModelCall` prepends "m1:" to the system prompt and `m2.wrapModelCall` prepends "m2:" to the system prompt
**When** `stack.wrapModelCall(base)` is run
**Then** the base step receives a request whose system prompt has "m1:" followed by "m2:" — m1 outermost

#### Scenario: wrapToolCall distribution

**Given** a stack `[m1, m2]` where `m1.wrapToolCall` records "m1" in the trace and `m2.wrapToolCall` records "m2" in the trace
**When** `stack.wrapToolCall(base)` is run
**Then** the trace shows "m1" before "m2" before the base — m1 outermost

### Requirement: Disjoint commutativity holds under preconditions

If `m1` and `m2` have disjoint `stateCells`, contribute no overlapping tools, contribute no overlapping `promptSections` (for any tested `state`), do not rewrite requests in `wrapModelCall`/`wrapToolCall`, and have pure `beforeAgent`/`afterAgent` (state transitions lifted into `F`), then `[m1, m2]` SHALL be observationally equivalent to `[m2, m1]`.

**Given** two middlewares `m1` and `m2` satisfying all preconditions (disjoint cells, no overlapping tools/sections, no request rewriting, pure before/after)
**When** `[m1, m2]` and `[m2, m1]` are run on the same input
**Then** both produce equal final messages, equal state snapshots, and equal request traces

**Rationale**: This is law L6. The preconditions are honest: arbitrary `F`-effects (logging order) and request rewriting are inherently order-sensitive, and the law's value is precisely that it delimits *when* reordering a stack is safe. Section overlap is checked per-`state` because `promptSections` is state-aware (§4.4); two middlewares whose sections are disjoint for the tested states but would overlap for some untested state are out of scope of this property, and flagged as a known limitation of input-space coverage rather than a soundness claim.

#### Scenario: two disjoint pure middlewares commute

**Given** `m1` with `stateCells = [cellA]` (Private), `tools = [toolA]`, `promptSections(_) = [secA]`, pure before/after, no request rewriting; `m2` with `stateCells = [cellB]` (Private), `tools = [toolB]`, `promptSections(_) = [secB]`, pure before/after, no request rewriting
**When** `[m1, m2]` and `[m2, m1]` are run on the same input
**Then** both produce equal final messages, equal state snapshots, and equal request traces

#### Scenario: overlapping tools break commutativity (forbidden input)

**Given** `m1` and `m2` both contributing a tool named `"search"` (overlapping tools — precondition violated)
**When** `[m1, m2]` and `[m2, m1]` are run
**Then** the two stacks are NOT observationally equivalent in general — the law does not apply; this is a forbidden input, not a counterexample

#### Scenario: request rewriting breaks commutativity (forbidden input)

**Given** `m1` whose `wrapModelCall` rewrites the system prompt to "from-m1" and `m2` whose `wrapModelCall` rewrites the system prompt to "from-m2" (request rewriting — precondition violated)
**When** `[m1, m2]` and `[m2, m1]` are run
**Then** `[m1, m2]` yields "from-m1" (m1 outermost) and `[m2, m1]` yields "from-m2" (m2 outermost) — NOT equivalent; the law does not apply; this is a forbidden input

### Requirement: Privacy law holds for Private cells across project and mergeBack

For a `Private` cell `p`, `project(parent, cells).get(p)` SHALL equal `p.initial` (the child sees the initial, never the parent's value), and `mergeBack(parent, children, cells).get(p)` SHALL equal `parent.get(p)` for all children (the parent's Private value is unchanged by child writes).

**Given** a parent with `Private` cell `p` (initial = 0) set to value 99, and any children list
**When** `project(parent, List(p))` is called, then `mergeBack(parent, children, List(p))` is called
**Then** `project(...).get(p) == 0` (initial) and `mergeBack(...).get(p) == 99` (parent unchanged)

**Rationale**: This is law L9. Privacy is structural and bidirectional: the child cannot observe the parent's Private value (reads `initial`), and the parent cannot observe the child's Private writes (dropped on mergeBack). deepagents enforces only the write-back half via name filtering; here both directions hold by construction.

#### Scenario: child cannot read parent's Private value

**Given** a parent with `Private` cell `p` (initial = "default") set to "parent-secret"
**When** `project(parent, List(p))` is called and the child reads `p`
**Then** `child.get(p) == "default"` — the parent's "parent-secret" is invisible

#### Scenario: parent's Private value survives mergeBack from any children

**Given** a parent with `Private` cell `p` at value 99, and children `[c1, c2]` where each child has `p` at 0 (the initial)
**When** `mergeBack(parent, List(c1, c2), List(p))` is called
**Then** the parent's `p` is still 99 — child writes to Private cells are discarded

### Requirement: Merge-back neutrality holds for untouched children

For a parent and a single child that is the projection of the parent (`project(parent, cells)`), `mergeBack(parent, List(project(parent, cells)), cells)` SHALL be observationally equal to the parent. This holds for `Shared` cells exactly when `merge` is idempotent (`merge(a, a) == a`), because the projected child's `Shared` value equals the parent's, and merging it back via an idempotent merge is a no-op.

**Given** a parent with `Shared` cell `s` (idempotent merge, e.g. set union or max) at value `v`, and a child that is `project(parent, List(s))` (so the child's `s` is also `v`)
**When** `mergeBack(parent, List(child), List(s))` is called
**Then** the parent's `s` is still `v` — merging the projected value back is a no-op for idempotent merges

**Rationale**: This is law L10. It ties merge-back neutrality to the idempotence of `cell.merge`: a sub-agent that was projected from the parent and did not touch its `Shared` cells merges back as a no-op. This is the sequential-delegation correctness property (one `task` call that does nothing to a shared cell leaves the parent unchanged).

#### Scenario: idempotent merge — untouched child is neutral

**Given** a parent with `Shared` cell `s` (initial = `Set(1, 2)`) whose `merge = (a, b) => a.union(b)` (idempotent), and a child = `project(parent, List(s))` (child's `s` = `Set(1, 2)`)
**When** `mergeBack(parent, List(child), List(s))` is called
**Then** the parent's `s` is still `Set(1, 2)` — `union(Set(1,2), Set(1,2)) == Set(1,2)` (idempotent)

#### Scenario: non-idempotent merge — untouched child is NOT neutral (known limitation)

**Given** a parent with `Shared` cell `s` (initial = 0) whose `merge = (a, b) => a + b` (NOT idempotent), and a child = `project(parent, List(s))` (child's `s` = 0)
**When** `mergeBack(parent, List(child), List(s))` is called
**Then** the parent's `s` becomes `0 + 0 = 0` — neutral here only because the value is the initial; but if the parent had `s = 5`, the child would have `s = 5` (copied), and `mergeBack` yields `5 + 5 = 10` — NOT neutral; this is why L10 requires idempotent merge for the general claim

## Properties (Ring 3)

### Property: monoid-identity-any-position

**Invariant**: For any stack `S` and any position ∈ {head, middle, tail}, `insert(S, AgentMiddleware.id, position)` is observationally equivalent to `S` (equal final message, equal state snapshot, equal request trace at base step).

**Generator strategy**: `genStack: Gen[List[AgentMiddleware[IO]]]` — constructive, builds a list of 1–5 trivial reference middlewares (each overriding one hook with a traceable effect), size via `Range.linear 1 5`. `genPosition: Gen[Position]` — `Gen.element1(Head, Middle, Tail)`. Classify by stack size and position.

```
forAll { (ms: List[AgentMiddleware[IO]], pos: Position) =>
  val s = MiddlewareStack.validated(ms).toOption.get
  val withId = insertId(s, pos)
  for {
    r1 <- runStack(s, genRequest)
    r2 <- runStack(withId, genRequest)
  } yield r1 == r2
}
```

### Property: monoid-associativity

**Invariant**: For any three stacks `a`, `b`, `c`, `(a ++ b) ++ c` is observationally equivalent to `a ++ (b ++ c)`.

**Generator strategy**: `genSubStack: Gen[List[AgentMiddleware[IO]]]` — constructive, 0–3 middlewares each, via `Range.linear 0 3`. Three independent draws for `a`, `b`, `c`. Classify by total size.

```
forAll { (aMs: List[AgentMiddleware[IO]], bMs: List[AgentMiddleware[IO]], cMs: List[AgentMiddleware[IO]]) =>
  val left  = MiddlewareStack(aMs ++ bMs) ++ MiddlewareStack(cMs)
  val right = MiddlewareStack(aMs) ++ MiddlewareStack(bMs ++ cMs)
  for {
    r1 <- runStack(left, genRequest)
    r2 <- runStack(right, genRequest)
  } yield r1 == r2
}
```

### Property: hook-distribution-wrapModelCall

**Invariant**: For a two-element stack `[m1, m2]`, `stack.wrapModelCall(base)` produces the same base-step request trace as `m1.wrapModelCall(m2.wrapModelCall(base))`.

**Generator strategy**: `genTracingMiddleware: Gen[AgentMiddleware[IO]]` — constructive, builds a middleware whose `wrapModelCall` prepends a random tag (Gen.string1) to the system prompt and records the tag in a trace. Two independent draws. Classify by tag distinctness.

```
forAll { (tag1: String, tag2: String) =>
  val m1 = tracingMiddleware(tag1)
  val m2 = tracingMiddleware(tag2)
  val stack = MiddlewareStack(List(m1, m2))
  for {
    t1 <- traceBase(stack.wrapModelCall(baseStep), genRequest)
    t2 <- traceBase(m1.wrapModelCall(m2.wrapModelCall(baseStep)), genRequest)
  } yield t1 == t2
}
```

### Property: disjoint-commutativity

**Invariant**: If `m1` and `m2` have disjoint `stateCells`, no overlapping tools, no overlapping `promptSections` (for any tested `state`), do not rewrite requests, and have pure `beforeAgent`/`afterAgent`, then `[m1, m2]` is observationally equivalent to `[m2, m1]`.

**Generator strategy**: `genDisjointPureMiddleware: Gen[AgentMiddleware[IO]]` — constructive, builds a middleware with a unique Private cell (random cell name via Gen.string1, unique owner), a unique tool (random tool name), a unique prompt section (random section name), pure before/after (no-op), no request rewriting. Two independent draws; filter for disjointness (different cell ids, tool names, section names). Classify by disjointness.

```
forAll { (m1: AgentMiddleware[IO], m2: AgentMiddleware[IO]) =>
  disjoint(m1, m2) ==> {
    val s12 = MiddlewareStack(List(m1, m2))
    val s21 = MiddlewareStack(List(m2, m1))
    for {
      r1 <- runStack(s12, genRequest)
      r2 <- runStack(s21, genRequest)
    } yield r1 == r2
  }
}
```

### Property: privacy-project-and-mergeBack

**Invariant**: For any parent state and any `Private` cell `p`, `project(parent, List(p)).get(p) == p.initial` and `mergeBack(parent, children, List(p)).get(p) == parent.get(p)` for any children.

**Generator strategy**: `genPrivateCell: Gen[StateCell[Int]]` — constructive, `Private` visibility, random initial via `Range.linear 0 100`, `merge = (_, child) => child`. `genParentValue: Gen[Int]` via `Range.linear 0 100`. `genChildrenCount: Gen[Int]` via `Range.linear 0 5`; children are `project(parent, cells)` with random mutations to the Private cell (which are discarded). Classify by children count.

```
forAll { (cell: StateCell[Int], parentVal: Int, nChildren: Int) =>
  val parent = HarnessState.empty.set(cell)(parentVal)
  val child = HarnessState.project(parent, List(cell))
  val children = List.fill(nChildren)(child.set(cell)(parentVal + 1))  // child writes, discarded
  val projected = HarnessState.project(parent, List(cell))
  val merged = HarnessState.mergeBack(parent, children, List(cell))
  projected.get(cell) == cell.initial && merged.get(cell) == parentVal
}
```

### Property: mergeBack-neutrality-idempotent

**Invariant**: For a parent with a `Shared` cell whose `merge` is idempotent, `mergeBack(parent, List(project(parent, cells)), cells)` equals the parent.

**Generator strategy**: `genIdempotentSharedCell: Gen[StateCell[Set[Int]]]` — constructive, `Shared` visibility, `merge = (a, b) => a.union(b)` (idempotent), random initial set of size `Range.linear 0 5` with elements `Range.linear 0 10`. `genParentSet: Gen[Set[Int]]` — random set. Classify by set size.

```
forAll { (cell: StateCell[Set[Int]], parentVal: Set[Int]) =>
  val parent = HarnessState.empty.set(cell)(parentVal)
  val child = HarnessState.project(parent, List(cell))
  val merged = HarnessState.mergeBack(parent, List(child), List(cell))
  merged.get(cell) == parentVal
}
```

### Property: mergeBack-order-independence-semilattice

**Invariant**: When every `Shared` cell's `merge` is a join semilattice, `mergeBack` produces the same parent state for any permutation of the children list.

**Generator strategy**: `genSemilatticeSharedCell: Gen[StateCell[Set[Int]]]` — constructive, `Shared` visibility, `merge = (a, b) => a.union(b)` (commutative, associative, idempotent). `genChildren: Gen[List[HarnessState]]` — list of 1–6 children, each with a random set value, size via `Range.linear 1 6`. `genPermutation: Gen[List[Int]` — a permutation of indices. Classify by children count.

```
forAll { (cell: StateCell[Set[Int]], parentVal: Set[Int], childSets: List[Set[Int]]) =>
  val parent = HarnessState.empty.set(cell)(parentVal)
  val children = childSets.map(s => HarnessState.empty.set(cell)(s))
  val merged1 = HarnessState.mergeBack(parent, children, List(cell))
  val permuted = childSets.reverse  // one permutation; full property tests all permutations
  val childrenP = permuted.map(s => HarnessState.empty.set(cell)(s))
  val merged2 = HarnessState.mergeBack(parent, childrenP, List(cell))
  merged1.get(cell) == merged2.get(cell)
}
```

### Property: validated-duplicate-detection

**Invariant**: `MiddlewareStack.validated` returns `Left` containing `DuplicateCellId` for any stack with duplicate cell ids, and `Left` containing `DuplicateToolName` for any stack with duplicate tool names. All errors are accumulated.

**Generator strategy**: `genMiddlewareWithCell: Gen[(AgentMiddleware[IO], StateCell.CellId)]` — constructive, builds a middleware declaring a cell with a given id. `genCollidingIds: Gen[List[AgentMiddleware[IO]]]` — builds 2–4 middlewares where at least two share a cell id. `genCollidingTools: Gen[List[AgentMiddleware[IO]]]` — builds 2–4 middlewares where at least two share a tool name. Classify by collision type (cell, tool, both).

```
forAll { (ms: List[AgentMiddleware[IO]]) =>
  val result = MiddlewareStack.validated(ms)
  hasDuplicateCellIds(ms) ==> result.isLeft &&
    result.toOption.isEmpty &&
    result.left.get.exists(_.isInstanceOf[StackError.DuplicateCellId])
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `MiddlewareStack(List(m1, m2))` (direct constructor call) | The constructor is private; only `validated` and `empty` can produce a `MiddlewareStack`, ensuring all stacks pass duplicate validation | `assertDoesNotCompile("MiddlewareStack(List(m1, m2))")` |
| A catch-all `case _ => ...` in a match over `StackError` | Exhaustiveness escalation makes this a Ring 0 compile error; the spec mandates exhaustive matches | `assertDoesNotCompile("e match { case StackError.DuplicateCellId(_, _) => () }")` — missing `DuplicateToolName` fails to compile |
| `MiddlewareStack` with a mutable `middlewares` field | The spec mandates immutability; `middlewares` is a `List` (immutable), and the case class has a private constructor | WartRemover `Var` wart + manual review (Ring 8) |
| `mergeBack` that mutates the parent state in place | The spec mandates purity; `mergeBack` returns a new `HarnessState` | type system — `HarnessState` is immutable; WartRemover `Var` wart |

## Formal Contracts (Ring 6)

Ring 6 applies via the VERIFIED-MIRROR pattern
(`openspec/schemas/verified-scala3/templates/verified-mirror.md`). The
shipped `MiddlewareStack` and `HarnessState.project`/`mergeBack` cannot
be verified directly — they use `Kleisli`/`F[_]`-effects and
`smithy4s.Document` payloads, and the Stainless frontend is pinned to
Scala 3.7.2 while this build is 3.8.4. The *algorithm* under
`mergeBack` (a fold over children by visibility) and the privacy
projection are pure and survive reduction to observable effect.

### The abstraction

A state becomes a map from `CellId` (modeled as `BigInt`) to `BigInt`
values. Visibility becomes a tag on each cell. `project` and `mergeBack`
become pure functions over this map.

```scala
// verified/src/main/scala/org/adk4s/verified/StackKernel.scala  (Scala 3.7.2)
sealed abstract class Visibility
case class PrivateV()  extends Visibility
case class InheritedV() extends Visibility
case class SharedV(merge: (BigInt, BigInt) => BigInt) extends Visibility

case class Cell(id: BigInt, visibility: Visibility, initial: BigInt)

// project: parent -> child initial state by visibility
def project(parent: Map[BigInt, BigInt], cells: List[Cell]): Map[BigInt, BigInt]

// mergeBack: fold child states into parent by visibility
def mergeBack(parent: Map[BigInt, BigInt], children: List[Map[BigInt, BigInt]], cells: List[Cell]): Map[BigInt, BigInt]
```

### Contract: project — Private reads as initial, Inherited/Shared copy parent

**Precondition** (`require`): none — `project` is total.
**Postcondition** (`ensuring`): for each cell `c` in `cells`:
- if `c.visibility` is `PrivateV`, then `result(c.id) == c.initial`;
- if `c.visibility` is `InheritedV` or `SharedV`, then `result(c.id) == parent(c.id)`.

```scala
def project(parent: Map[BigInt, BigInt], cells: List[Cell]): Map[BigInt, BigInt] = {
  cells.foldLeft(Map[BigInt, BigInt]()) { (child, cell) =>
    cell.visibility match {
      case PrivateV()   => child + (cell.id -> cell.initial)
      case InheritedV() => child + (cell.id -> parent(cell.id))
      case SharedV(_)   => child + (cell.id -> parent(cell.id))
    }
  }
}.ensuring(result => cells.forall { c =>
  c.visibility match {
    case PrivateV()   => result(c.id) == c.initial
    case InheritedV() => result(c.id) == parent(c.id)
    case SharedV(_)   => result(c.id) == parent(c.id)
  }
})
```

### Contract: mergeBack — Shared folds, Private/Inherited unchanged

**Precondition** (`require`): none.
**Postcondition** (`ensuring`): for each cell `c` in `cells`:
- if `c.visibility` is `SharedV(merge)`, then `result(c.id)` equals
  `children.foldLeft(parent(c.id))((acc, child) => merge(acc, child(c.id)))`;
- if `c.visibility` is `PrivateV` or `InheritedV`, then `result(c.id) == parent(c.id)`.

```scala
def mergeBack(parent: Map[BigInt, BigInt], children: List[Map[BigInt, BigInt]], cells: List[Cell]): Map[BigInt, BigInt] = {
  cells.foldLeft(parent) { (acc, cell) =>
    cell.visibility match {
      case SharedV(merge) => acc + (cell.id -> children.foldLeft(parent(cell.id))((a, child) => merge(a, child(cell.id))))
      case _              => acc
    }
  }
}.ensuring(result => cells.forall { c =>
  c.visibility match {
    case SharedV(merge) => result(c.id) == children.foldLeft(parent(c.id))((a, child) => merge(a, child(c.id)))
    case PrivateV()     => result(c.id) == parent(c.id)
    case InheritedV()   => result(c.id) == parent(c.id)
  }
})
```

### Contract: mergeBack order-independence for semilattice merges

**Postcondition** (`ensuring`): if every `SharedV(merge)` in `cells` has
a `merge` that is commutative, associative, and idempotent, then for any
permutation `perm` of `children`, `mergeBack(parent, perm, cells) ==
mergeBack(parent, children, cells)`.

### Bridge — how the shipped code is bound to the model

`StackKernelBridgeSpec` (Hedgehog, in `adk4s-harness-testkit` test
sources) runs the real `HarnessState.project`/`mergeBack` and the model
on the SAME generated cell sets and state values, and asserts they
agree on exactly the proven invariants:

1. the real `project` sets Private cells to `initial` and
   Inherited/Shared cells to the parent value (matching the model);
2. the real `mergeBack` folds Shared cells and leaves Private/Inherited
   unchanged (matching the model);
3. for semilattice merges, the real `mergeBack` is order-independent
   over permutations of children.

Build wiring this spec commits to: `adk4s-harness-testkit dependsOn(verified % Test)`.
TASTy is backward compatible, so the 3.8.4 module may read the 3.7.2
artifact. `stainlessEnabled := false` by default, so the bridge test
pays only a plain compile of the model; verification is the separate
`sbt -J-Xmx6g ring6` step.

### Scope — what is proven, and what is delegated

Target proofs (best-effort): the three contracts above, all
quantifier-free or structurally inductive with `decreases` measures.

DELEGATED to Ring 3, and named here rather than dropped:

| Law | Why not proven here | Covered by |
|-----|---------------------|------------|
| Monoid identity (L1) | requires `F[_]`-effect observation and `ChatModel` double | Property: monoid-identity-any-position |
| Monoid associativity (L2) | requires `F[_]`-effect observation | Property: monoid-associativity |
| Hook distribution (L3) | requires `Kleisli` trace observation | Property: hook-distribution-wrapModelCall |
| Disjoint commutativity (L6) | requires `F[_]`-effect observation and multi-hook interaction | Property: disjoint-commutativity |
| Duplicate detection | requires `AgentMiddleware` construction and `CellId` string comparison | Property: validated-duplicate-detection |

If a target VC diverges in z3, it moves into this table with its Ring 3
property named — it is never silently dropped.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| `beforeAgent` runs in stack order (m1 → m2 → m3) | Requirement: MiddlewareStack is a monoid under list concatenation with stack-order semantics + Scenario: beforeAgent runs in stack order | scenario test (trace order) | `MiddlewareStackSpec.scala` |
| `afterAgent` runs in reverse stack order (m3 → m2 → m1) | Requirement: MiddlewareStack is a monoid under list concatenation with stack-order semantics + Scenario: afterAgent runs in reverse stack order | scenario test (trace order) | `MiddlewareStackSpec.scala` |
| `wrapModelCall` nests m1 outermost | Requirement: MiddlewareStack is a monoid under list concatenation with stack-order semantics + Scenario: wrapModelCall nests m1 outermost | scenario test (request/response trace) | `MiddlewareStackSpec.scala` |
| Empty stack is the identity for all hooks | Requirement: MiddlewareStack is a monoid under list concatenation with stack-order semantics + Scenario: empty stack is the identity | scenario test | `MiddlewareStackSpec.scala` |
| `allSections(state)` folds per-request from current state | Requirement: MiddlewareStack is a monoid under list concatenation with stack-order semantics + Scenario: allSections folds per-request from current state | scenario test (two different states yield different sections) | `MiddlewareStackSpec.scala` |
| `allCells`/`allTools`/`allSections` concatenate in stack order | Requirement: Aggregation methods concatenate in stack order + Scenario: middleware with default empty contributions | scenario test | `MiddlewareStackSpec.scala` |
| Empty stack aggregates to empty | Requirement: Aggregation methods concatenate in stack order + Scenario: empty stack aggregates to empty | scenario test | `MiddlewareStackSpec.scala` |
| `validated` rejects duplicate cell ids | Requirement: Validated construction rejects duplicate cell ids and duplicate tool names + Scenario: duplicate cell id detected | scenario test + Hedgehog property (validated-duplicate-detection) | `MiddlewareStackSpec.scala` |
| `validated` rejects duplicate tool names | Requirement: Validated construction rejects duplicate cell ids and duplicate tool names + Scenario: duplicate tool name detected | scenario test + Hedgehog property (validated-duplicate-detection) | `MiddlewareStackSpec.scala` |
| `validated` accumulates all errors | Requirement: Validated construction rejects duplicate cell ids and duplicate tool names + Scenario: multiple errors accumulated | scenario test (NonEmptyList size > 1) | `MiddlewareStackSpec.scala` |
| `validated` returns Right for valid stacks | Requirement: Validated construction rejects duplicate cell ids and duplicate tool names + Scenario: valid stack returns Right | scenario test | `MiddlewareStackSpec.scala` |
| `validated(Nil)` returns `Right(empty)` | Requirement: Validated construction rejects duplicate cell ids and duplicate tool names + Scenario: empty list validates to empty stack | scenario test | `MiddlewareStackSpec.scala` |
| Private constructor prevents unvalidated construction | Requirement: Validated construction rejects duplicate cell ids and duplicate tool names + Scenario: private constructor prevents unvalidated construction | compile-negative test (assertDoesNotCompile) | `MiddlewareStackSpec.scala` |
| `StackError` is a sealed enum with two variants | Requirement: StackError is a sealed enum with two variants + Scenario: non-exhaustive match fails to compile | compile-negative test (assertDoesNotCompile) | `MiddlewareStackSpec.scala` |
| `DuplicateCellId` carries id and owners | Requirement: StackError is a sealed enum with two variants + Scenario: DuplicateCellId carries the colliding id and owners | scenario test | `MiddlewareStackSpec.scala` |
| `DuplicateToolName` carries name and owners | Requirement: StackError is a sealed enum with two variants + Scenario: DuplicateToolName carries the colliding name and owners | scenario test | `MiddlewareStackSpec.scala` |
| `project` maps Private to initial, Inherited/Shared to parent value | Requirement: Sub-agent boundary project maps parent state to child initial state by visibility + Scenario: Private cell reads as initial in child + Scenario: Inherited cell copies parent value + Scenario: Shared cell copies parent value | scenario test | `HarnessStateBoundarySpec.scala` |
| `project` leaves parent cells unknown to child untouched | Requirement: Sub-agent boundary project maps parent state to child initial state by visibility + Scenario: cells unknown to the child are untouched in the parent | scenario test | `HarnessStateBoundarySpec.scala` |
| `mergeBack` folds Shared cells, drops Private/Inherited | Requirement: Sub-agent boundary mergeBack folds child final states into parent by visibility + Scenario: Shared cell merges back + Scenario: Private cell is unchanged after mergeBack + Scenario: Inherited cell is unchanged after mergeBack | scenario test | `HarnessStateBoundarySpec.scala` |
| `mergeBack` with no children is identity | Requirement: Sub-agent boundary mergeBack folds child final states into parent by visibility + Scenario: mergeBack with no children is identity | scenario test | `HarnessStateBoundarySpec.scala` |
| `mergeBack` folds in children list order | Requirement: Sub-agent boundary mergeBack folds child final states into parent by visibility + Scenario: mergeBack with multiple children folds in list order | scenario test | `HarnessStateBoundarySpec.scala` |
| `mergeBack` is order-independent for semilattice merges | Requirement: mergeBack is order-independent for semilattice merges + Scenario: union-merge is order-independent + Scenario: max-merge is order-independent | Hedgehog property (mergeBack-order-independence-semilattice) | `HarnessStateBoundarySpec.scala` |
| Last-write-wins is NOT order-independent (known limitation) | Requirement: mergeBack is order-independent for semilattice merges + Scenario: last-write-wins is NOT order-independent (known limitation) | scenario test (negative — confirms order-dependence) | `HarnessStateBoundarySpec.scala` |
| Monoid identity holds for any insertion position | Requirement: Monoid identity law holds for any insertion position + Scenario: identity at head + Scenario: identity at tail + Scenario: identity in middle | Hedgehog property (monoid-identity-any-position) | `MiddlewareStackLawsSpec.scala` |
| Monoid associativity holds observationally | Requirement: Monoid associativity law holds observationally + Scenario: three single-middleware stacks + Scenario: regrouping multi-element sub-stacks | Hedgehog property (monoid-associativity) | `MiddlewareStackLawsSpec.scala` |
| Hook distribution holds for wrapModelCall and wrapToolCall | Requirement: Hook distribution law holds for wrapModelCall and wrapToolCall + Scenario: wrapModelCall distribution + Scenario: wrapToolCall distribution | Hedgehog property (hook-distribution-wrapModelCall) | `MiddlewareStackLawsSpec.scala` |
| Disjoint commutativity holds under preconditions | Requirement: Disjoint commutativity holds under preconditions + Scenario: two disjoint pure middlewares commute | Hedgehog property (disjoint-commutativity) | `MiddlewareStackLawsSpec.scala` |
| Overlapping tools break commutativity (forbidden input) | Requirement: Disjoint commutativity holds under preconditions + Scenario: overlapping tools break commutativity (forbidden input) | scenario test (negative — confirms law does not apply) | `MiddlewareStackLawsSpec.scala` |
| Request rewriting breaks commutativity (forbidden input) | Requirement: Disjoint commutativity holds under preconditions + Scenario: request rewriting breaks commutativity (forbidden input) | scenario test (negative — confirms law does not apply) | `MiddlewareStackLawsSpec.scala` |
| Privacy law holds for Private cells | Requirement: Privacy law holds for Private cells across project and mergeBack + Scenario: child cannot read parent's Private value + Scenario: parent's Private value survives mergeBack | Hedgehog property (privacy-project-and-mergeBack) | `HarnessStateBoundarySpec.scala` |
| Merge-back neutrality holds for untouched children | Requirement: Merge-back neutrality holds for untouched children + Scenario: idempotent merge — untouched child is neutral | Hedgehog property (mergeBack-neutrality-idempotent) | `HarnessStateBoundarySpec.scala` |
| Non-idempotent merge breaks neutrality (known limitation) | Requirement: Merge-back neutrality holds for untouched children + Scenario: non-idempotent merge — untouched child is NOT neutral (known limitation) | scenario test (negative — confirms idempotence requirement) | `HarnessStateBoundarySpec.scala` |
| project sets Private to initial, Inherited/Shared to parent (model) | Requirement: Sub-agent boundary project maps parent state to child initial state by visibility + Invariant: project ensuring clause | formal contract (Ring 6) — `project` ensuring clause, verified by Stainless | `StackKernel` |
| mergeBack folds Shared, leaves Private/Inherited unchanged (model) | Requirement: Sub-agent boundary mergeBack folds child final states into parent by visibility + Invariant: mergeBack ensuring clause | formal contract (Ring 6) — `mergeBack` ensuring clause | `StackKernel` |
| mergeBack order-independence for semilattice merges (model) | Requirement: mergeBack is order-independent for semilattice merges + Invariant: mergeBack permutation equality | formal contract (Ring 6) | `StackKernel` |
| Shipped project/mergeBack conform to the verified model | Requirement: Sub-agent boundary project maps parent state to child initial state by visibility + Requirement: Sub-agent boundary mergeBack folds child final states into parent by visibility | bridge property test (Ring 3 + Ring 6) — real and model on the same generated cell sets and states | `StackKernelBridgeSpec` |
| `StackError` exhaustiveness | Compile-Negative: non-exhaustive match over StackError | compile-negative test (assertDoesNotCompile) | `MiddlewareStackSpec.scala` |
| `MiddlewareStack` immutability | Compile-Negative: mutable middlewares field | WartRemover `Var` wart + manual review (Ring 8) | adversarial review |
| `mergeBack` purity (no in-place parent mutation) | Compile-Negative: mergeBack mutates parent | type system + WartRemover `Var` wart + manual review (Ring 8) | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `MiddlewareStack[F[_]]` | final case class | `adk4s-harness-api/src/main/scala/org/adk4s/harness/MiddlewareStack.scala` | private constructor; `middlewares: List[AgentMiddleware[F]]`; `empty`, `validated`, `++`, `allCells`, `allTools`, `allSections(state)`, `beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall` |
| `StackError` | enum | `adk4s-harness-api/src/main/scala/org/adk4s/harness/StackError.scala` | `DuplicateCellId(id: StateCell.CellId, owners: List[MiddlewareName])` / `DuplicateToolName(name: String, owners: List[MiddlewareName])` |
| `HarnessState.project` | method (companion) | `adk4s-harness-api/src/main/scala/org/adk4s/harness/HarnessState.scala` | `project(parent, declared): HarnessState` — visibility-based projection |
| `HarnessState.mergeBack` | method (companion) | `adk4s-harness-api/src/main/scala/org/adk4s/harness/HarnessState.scala` | `mergeBack(parent, children, declared): HarnessState` — visibility-based merge fold |
| `StackKernel.scala` | new file (Ring 6 model) | `verified/src/main/scala/org/adk4s/verified/` | PureScala, Scala 3.7.2. Uses `stainless.collection.List`/`Map`; `decreases` measures on `project`/`mergeBack`. Verified by `sbt -J-Xmx6g ring6`. |
| `StackKernelBridgeSpec.scala` | new file (bridge test) | `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/` | Hedgehog. Maps generated cell sets to `StackKernel` model, compares real vs model. Compiles the model only — `stainlessEnabled := false`. |
| `adk4s-harness-testkit dependsOn(verified % Test)` | build step | `build.sbt` | Ring 6 bridge precondition; add when the module is created. |
| `MiddlewareStackSpec.scala` | test file | `adk4s-harness-api/src/test/scala/org/adk4s/harness/` | scenario tests for stack semantics, aggregation, validated construction, StackError exhaustiveness |
| `HarnessStateBoundarySpec.scala` | test file | `adk4s-harness-api/src/test/scala/org/adk4s/harness/` | scenario tests for project/mergeBack visibility, mergeBack order-independence, privacy, neutrality |
| `MiddlewareStackLawsSpec.scala` | test file | `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/` | Hedgehog properties for L1 (identity), L2 (associativity), L3 (hook distribution), L6 (disjoint commutativity), L9 (privacy), L10 (neutrality), L11 (order-independence) |
