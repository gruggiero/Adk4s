# Concept: Middleware Stack

**Kind**: final case class + enum
**Package**: `org.adk4s.harness`
**Module**: `adk4s-harness-api`
**Spec**: `add-harness-api-phase0/specs/middleware-stack/spec.md`

## Purpose

`MiddlewareStack[F[_]]` is the composition algebra for `AgentMiddleware[F]` values.
It provides stack-order semantics for each hook, validated construction (rejecting
duplicate cell ids and tool names), and the sub-agent boundary operations
(`HarnessState.project` / `mergeBack` with three visibility levels).

## State

- `middlewares: List[AgentMiddleware[F]]` — the middleware list in stack order
  (m1 first = outermost). Private constructor; construction via `validated` or `empty`.

## Actions

- `allCells: List[StateCell[?]]` — concatenation of all middlewares' `stateCells` in stack order
- `allTools: List[InvokableTool[F]]` — concatenation of all middlewares' `tools` in stack order
- `allSections(state: HarnessState): List[PromptSection]` — per-request fold of `promptSections(state)` in stack order
- `beforeAgent(state: HarnessState): F[HarnessState]` — left-to-right fold (m1 → m2 → m3)
- `afterAgent(state: HarnessState): F[HarnessState]` — reverse fold (m3 → m2 → m1, teardown mirrors setup)
- `wrapModelCall(base: ModelStep[F]): ModelStep[F]` — right fold, m1 outermost: `m1(m2(m3(base)))`
- `wrapToolCall(base: ToolStep[F]): ToolStep[F]` — right fold, m1 outermost
- `++(that: MiddlewareStack[F]): MiddlewareStack[F]` — list concatenation (monoid operation)
- `MiddlewareStack.empty[F]` — identity element (empty middleware list)
- `MiddlewareStack.validated[F](ms): Either[NonEmptyList[StackError], MiddlewareStack[F]]` — construction with duplicate detection

## StackError

Sealed enum with two variants:
- `DuplicateCellId(id: StateCell.CellId, owners: List[MiddlewareName])`
- `DuplicateToolName(name: String, owners: List[MiddlewareName])`

All errors are accumulated (not short-circuited). Exhaustive pattern matching enforced by `-Wconf:name=PatternMatchExhaustivity:e`.

## Operational Principle

1. **Construction**: `validated` checks for duplicate cell ids across all `allCells` and duplicate tool names across all `allTools`. All errors accumulated into `NonEmptyList[StackError]`.
2. **Hook distribution**: `beforeAgent` runs forward (m1 first), `afterAgent` runs reverse (m3 first), `wrapModelCall`/`wrapToolCall` compose m1 outermost via `foldRight`.
3. **Per-request sections**: `allSections(state)` folds `promptSections(state)` at call time, not construction time — state-aware sections reflect the current `HarnessState`.
4. **Sub-agent boundary**: `project` maps parent state to child initial state by visibility (Private → initial, Inherited/Shared → parent value). `mergeBack` folds child final states into parent (Shared folds via `merge`, Private/Inherited discarded).

## Laws

- **L1 (monoid identity)**: Inserting `AgentMiddleware.id` at any position produces an observationally equivalent stack.
- **L2 (monoid associativity)**: `(a ++ b) ++ c` is observationally equivalent to `a ++ (b ++ c)`.
- **L3 (hook distribution)**: `stack.wrapModelCall(base)` == `m1(m2(base))` (trace equality).
- **L6 (disjoint commutativity)**: Disjoint pure middlewares commute (preconditions: disjoint cells, no overlapping tools/sections, no request rewriting, pure before/after).
- **L9 (privacy)**: Private cells read as `initial` in child, parent's Private value unchanged by mergeBack.
- **L10 (merge-back neutrality)**: For idempotent merges, an untouched child merges back as a no-op.
- **L11 (order-independence)**: For semilattice merges, `mergeBack` is order-independent over permutations of children.

## Synchronizations

- Depends on `agent-middleware` concept (`AgentMiddleware[F]`, `ModelStep`, `ToolStep`, `PromptSection`, `MiddlewareName`)
- Depends on `harness-state` concept (`HarnessState`, `StateCell[A]`, `CellVisibility`)
- Depends on `tool` concept (`InvokableTool[F]`)
- Ring 6 mirror: `StackKernel.scala` (project/mergeBack invariants)
