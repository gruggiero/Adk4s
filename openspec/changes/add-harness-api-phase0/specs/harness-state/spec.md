# Spec: Harness State

<!-- This is a DELTA spec for the `add-harness-api-phase0` change. It
     introduces the typed, enumerable, serializable heterogeneous state map
     that flows through the agent loop, survives checkpoints, and is
     selectively visible to sub-agents.

     Grounded in docs/deepagents4s-phase0-agent-middleware-DESIGN.md §3
     (HarnessState, StateCell, CellVisibility, snapshot/restore, merging).
     The spec covers §3.1–3.5 requirements R1–R5 and laws L7–L11 from §7.

     ALTITUDE: requirements and scenarios use behavioral vocabulary only
     (Concept/action references, domain terms, test vectors). Code
     identifiers — class names, error variants, build commands — live in
     Implementation Anchors and the Concepts Introduced table. The full
     typed contract (Scala signatures) lives in design.md. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| harness-state (NEW — created by this spec) | The typed heterogeneous map that carries middleware-declared state through the loop, across checkpoints, and across sub-agent boundaries | `openspec/concepts/harness-state.md` (created at apply Step 12) |

Creating the `harness-state.md` concept file is PART OF implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `JsonValue` | type alias (`= smithy4s.Document`) | `org.adk4s.core.json` |
| `JsonValueCodec` | object (boundary adapter: `toUjson`/`fromUjson`) | `org.adk4s.core.json` |
| `AdkError` | sealed trait | `org.adk4s.core.error` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `HarnessState` | final class | Typed, enumerable, serializable heterogeneous map; total `get` (absent reads `initial`); immutable `set`/`update` producing new states; `snapshot`/`restore` for checkpoints |
| `StateCell[A]` | final class | Unit of state declaration, ownership, typing, serialization, visibility, and merging; equality by `CellId`; carries mandatory `ReadWriter[A]` codec, `initial` value, `visibility`, and `merge` function |
| `CellVisibility` | enum | `Private` / `Inherited` / `Shared` — three sub-agent visibility levels governing projection and merge-back |
| `StateCell.CellId` | opaque type (`String`) | Stable `"owner/name"` key; uniqueness enforced at stack construction (not in this spec — see middleware-stack spec) |
| `MiddlewareName` | opaque type (`String`) | Middleware identity for cell-id namespacing and error attribution |
| `StateDecodeError` | sealed trait (`AdkError` variant) | Hard failure for a cell that fails to decode on restore; carries the cell id and the codec failure cause |

## ADDED Requirements

### Requirement: StateCell is a typed declaration unit with mandatory codec

The system SHALL provide a `StateCell[A]` as the unit of state declaration, ownership, typing, serialization, visibility, and merging. Every `StateCell[A]` SHALL carry a mandatory `ReadWriter[A]` codec provided at declaration time via a `ReadWriter` context bound. A cell that cannot round-trip through its codec SHALL NOT be constructible. The cell SHALL carry an `initial: A` value, a `visibility: CellVisibility` (defaulting to `Private`), and a `merge: (A, A) => A` function (defaulting to last-write-wins `(_, child) => child`). Cell equality SHALL be by `CellId` (stable string), not by object identity.

**Given** a middleware identity `"todo"` and a cell declared with name `"todos"`, initial value `Set.empty[String]`, visibility `Shared`, and a set-union merge function
**When** the cell is constructed via the `StateCell.apply` factory with a `ReadWriter[Set[String]]` in scope
**Then** the cell's id is `"todo/todos"`, its visibility is `Shared`, its initial is `Set.empty[String]`, its merge is set-union, and its `rw` field is the summoned `ReadWriter[Set[String]]`

**Rationale**: Making the codec mandatory at declaration time means R2 (enumerable/snapshotable) is unconditionally true — there is no "this middleware's state isn't checkpointable" runtime failure mode. The `id` is a stable string (not object identity) so that checkpoints survive process restarts and cell identity is auditable. Equality by `id` allows cell comparison across different cell instances that declare the same owner/name.

#### Scenario: Cell with default visibility and merge

**Given** a `StateCell[Int]` declared with owner `"counter"`, name `"n"`, and initial `0` (no explicit visibility or merge)
**When** the cell's fields are inspected
**Then** visibility is `CellVisibility.Private` and merge is `(_, child: Int) => child` (last-write-wins)

#### Scenario: Cell with Shared visibility and custom merge

**Given** a `StateCell[Set[String]]` declared with owner `"todo"`, name `"items"`, initial `Set.empty`, visibility `Shared`, and merge `(a, b) => a.union(b)`
**When** the cell's fields are inspected
**Then** visibility is `Shared` and merge is set-union

#### Scenario: Cell without a ReadWriter in scope does not compile

**Given** a type `Foo` with no `ReadWriter[Foo]` instance in scope
**When** code attempts to construct `StateCell[Foo](owner, "foo", initialValue)`
**Then** compilation fails — the `ReadWriter` context bound is unsatisfied

#### Scenario: Cell equality is by id, not object identity

**Given** two `StateCell[Int]` instances constructed with the same owner `"m"` and name `"x"` but different initial values (0 and 42)
**When** the two cells are compared with `==`
**Then** they are equal (same `CellId "m/x"`), despite different initial values

### Requirement: CellId is a stable owner-namespaced opaque string

The system SHALL provide `StateCell.CellId` as an opaque type backed by `String`, constructed from a `MiddlewareName` (owner) and a `String` (name) joined by `"/"`. The `CellId` SHALL be stable across process restarts (deterministic from owner and name, not dependent on object identity or memory addresses). The `MiddlewareName` SHALL be an opaque type backed by `String` providing a `value` extension method and an `apply` factory.

**Given** a `MiddlewareName("filesystem")` and a cell name `"files"`
**When** `CellId(owner, name)` is constructed
**Then** the underlying string is `"filesystem/files"`

**Rationale**: Stable string ids are required for checkpoint restore across process restarts (object-identity keys like Vault's `Unique` break this). The `owner/name` namespacing prevents accidental collisions between middlewares and makes cell ownership auditable in serialized form.

#### Scenario: Same owner and name produce equal CellIds

**Given** two `CellId` constructions with owner `"todo"` and name `"items"`
**When** the two ids are compared
**Then** they are equal

#### Scenario: Different owners produce different CellIds for the same name

**Given** `CellId(MiddlewareName("a"), "x")` and `CellId(MiddlewareName("b"), "x")`
**When** the two ids are compared
**Then** they are not equal

#### Scenario: CellId is not constructible from a raw String without a MiddlewareName

**Given** a raw `String` value
**When** code attempts to pass it where a `CellId` is expected without going through `CellId.apply(owner, name)`
**Then** the opaque type prevents direct construction from `String` (the `CellId` companion's `apply` is the only constructor)

### Requirement: CellVisibility enum has exactly three levels

The system SHALL provide a `CellVisibility` enum with exactly three cases: `Private`, `Inherited`, and `Shared`. `Private` means the cell never crosses a sub-agent boundary (the child sees `initial`). `Inherited` means the cell is copied into the child's initial state but child writes are discarded on merge-back. `Shared` means the cell is copied into the child and the child's final value merges back via the cell's `merge` function. The enum SHALL be exhaustive — pattern matching over it SHALL cover all three cases with no catch-all.

**Given** the three `CellVisibility` values `Private`, `Inherited`, `Shared`
**When** a match expression handles each case explicitly
**Then** the compiler verifies exhaustiveness with no warning (no catch-all `case _` needed)

**Rationale**: Three visibility levels (vs deepagents' two) give the structural privacy guarantee (Private: both directions isolated), read-only inheritance (Inherited: config flows down, no write-back), and shared merge (Shared: lattice-based merge for parallel delegation). Exhaustiveness ensures adding a fourth level is a compile-breaking change, not a silent fall-through.

#### Scenario: Private cell is invisible to sub-agent projection

**Given** a parent state with a `Private` cell `p` set to value `42`
**When** the state is projected for a sub-agent that declares `p`
**Then** the child's value for `p` is `p.initial`, not `42`

#### Scenario: Inherited cell copies parent value but discards child writes

**Given** a parent state with an `Inherited` cell `c` set to `"config"` and a child that writes `"modified"` to `c`
**When** the child's state is merged back to the parent
**Then** the parent's value for `c` remains `"config"` (child writes discarded)

#### Scenario: Shared cell copies parent value and merges child writes

**Given** a parent state with a `Shared` cell `s` (merge = set-union) set to `Set("a")` and a child that writes `Set("b")` to `s`
**When** the child's state is merged back to the parent
**Then** the parent's value for `s` is `Set("a", "b")` (merged via the cell's merge function)

### Requirement: HarnessState is a typed heterogeneous map with total get

The system SHALL provide a `HarnessState` as a typed heterogeneous map keyed by `StateCell.CellId`. The `get[A](cell: StateCell[A]): A` operation SHALL be total — reading an absent cell SHALL return the cell's declared `initial` value, never throw, and never return `Option`. The `set[A](cell: StateCell[A])(value: A): HarnessState` operation SHALL produce a new `HarnessState` (immutability — the original is unchanged). The `update[A](cell: StateCell[A])(f: A => A): HarnessState` operation SHALL be defined as `set(cell)(f(get(cell)))`. `HarnessState.empty` SHALL be the empty state, and `HarnessState.initial(declared: List[StateCell[?]])` SHALL produce a state with every declared cell set to its `initial`.

**Given** a `HarnessState.empty` and a cell `c` with `initial = 0`
**When** `state.get(c)` is called
**Then** the result is `0` (the cell's initial), not an exception, not `Option`

**Rationale**: A total `get` removes `Option` noise from every middleware read, makes R5 (forward-compatible restore) trivial (missing cells after restore read as `initial`), and makes the lens laws in §7 clean. Immutability of `set` preserves the functional data-structure discipline and allows safe state threading through the loop.

#### Scenario: Get on empty state returns initial

**Given** `HarnessState.empty` and a cell `c` with `initial = "default"`
**When** `state.get(c)` is called
**Then** the result is `"default"`

#### Scenario: Set then get returns the set value

**Given** a `HarnessState.empty` and a cell `c` with `initial = 0`
**When** `state.set(c)(42).get(c)` is called
**Then** the result is `42`

#### Scenario: Set does not mutate the original state

**Given** a state `s0 = HarnessState.empty` and a cell `c`
**When** `s1 = s0.set(c)(42)` is performed
**Then** `s0.get(c)` is still `c.initial` (unchanged) and `s1.get(c)` is `42`

#### Scenario: Update applies a function to the current value

**Given** a state with cell `c` (initial `0`) set to `5`, and a function `f = (_: Int) + 1`
**When** `state.update(c)(f)` is called
**Then** the resulting state's `get(c)` is `6`

#### Scenario: Initial state sets every declared cell to its initial

**Given** a list of three cells with initials `0`, `"hello"`, `Set.empty[Int]`
**When** `HarnessState.initial(cells)` is constructed
**Then** `get` on each cell returns its respective initial value

#### Scenario: Get on a different cell type than stored is prevented by construction-time uniqueness

**Given** a state where cell `c: StateCell[Int]` has been set to `42`, and a cell `d: StateCell[String]` with a different `CellId`
**When** `state.get(d)` is called
**Then** the result is `d.initial` (the cells are distinct by id; the stored `Int` value for `c` is never returned for `d`)

### Requirement: HarnessState get/set coherence

The `HarnessState` SHALL satisfy the get/set coherence (lens) laws: for any cell `c` and value `v`, `get(c)(set(c)(v)(s)) == v` (set-get); and for any two cells `c`, `d` with `c.id != d.id`, `get(c)(set(d)(v)(s)) == get(c)(s)` (set-disjoint). The single `asInstanceOf` in `get` SHALL be safe under the construction-time `CellId` uniqueness invariant (enforced by `MiddlewareStack.validated` in the middleware-stack spec): the only path that writes the entry keyed by `cell.id` is `set(cell)(value: A)` for a cell equal (by id) to this one, so the stored value is always the `A` of the declaring cell.

**Given** a state `s`, a cell `c: StateCell[Int]`, and a value `v = 42`
**When** `s.set(c)(v).get(c)` is evaluated
**Then** the result is `42` (set-get coherence)

**Rationale**: The single unchecked cast in the design is the classic typed-map argument. Its safety is local and auditable: construction-time id uniqueness (a `MiddlewareStack.validated` concern) guarantees the stored value's type matches the declaring cell's type. The coherence laws are the observable expression of that safety and are a named Stainless target (Ring 6).

#### Scenario: Set-get coherence

**Given** a state `s`, a cell `c` with initial `0`, and a value `v = 99`
**When** `s.set(c)(v).get(c)` is evaluated
**Then** the result is `99`

#### Scenario: Set-disjoint coherence — writing one cell does not affect another

**Given** a state `s` with cells `c` (initial `0`) and `d` (initial `""`), where `c.id != d.id`, and `s` has `c` set to `5`
**When** `s.set(d)("hello").get(c)` is evaluated
**Then** the result is `5` (unchanged by the write to `d`)

#### Scenario: Set-disjoint coherence — reading an unset cell after writing a different cell

**Given** `HarnessState.empty`, cells `c` and `d` with `c.id != d.id`
**When** `state.set(c)(42).get(d)` is evaluated
**Then** the result is `d.initial` (the write to `c` does not populate `d`)

### Requirement: Snapshot produces a JsonValue DObject

The `HarnessState.snapshot` operation SHALL produce a `JsonValue` that is a `smithy4s.Document.DObject` — a map from `CellId` string to the cell's encoded value. Every cell present in the state SHALL appear in the snapshot, encoded via its `ReadWriter` through the `JsonValue` currency. The snapshot SHALL be enumerable (every entry is accessible), satisfying R2.

**Given** a state with two cells `c` (value `42`) and `d` (value `"hello"`) both present
**When** `state.snapshot` is called
**Then** the result is a `DObject` with keys `"owner-c/name-c"` and `"owner-d/name-d"` mapping to the encoded values `DNumber(42)` and `DString("hello")` respectively

**Rationale**: The snapshot is the checkpoint payload — it must be a self-describing, enumerable JSON object so that `restore` can drive decoding from the declared cell list and `CheckpointStateV2` can persist it as a `JsonValue` field. Using `JsonValue` (not `ujson.Value`) keeps the serialization currency on the ADK4S side of the llm4s boundary.

#### Scenario: Snapshot of empty state is an empty DObject

**Given** `HarnessState.empty`
**When** `state.snapshot` is called
**Then** the result is `DObject(Map.empty)`

#### Scenario: Snapshot includes every present cell

**Given** a state with cells `c` (value `42`), `d` (value `"hello"`), and `e` (value `Set(1, 2)`)
**When** `state.snapshot` is called
**Then** the resulting `DObject` has exactly three keys, one per cell id, each mapping to the cell's encoded value

#### Scenario: Snapshot of a state with only initial-valued cells

**Given** `HarnessState.initial(List(c, d))` where `c.initial = 0` and `d.initial = "default"`
**When** `state.snapshot` is called
**Then** the resulting `DObject` contains both cells encoded at their initial values

### Requirement: Restore is lenient with hard decode failure

The `HarnessState.restore(declared: List[StateCell[?]], json: JsonValue): Either[StateDecodeError, HarnessState]` operation SHALL be lenient by construction: unknown ids in `json` SHALL be silently ignored; declared-but-absent cells SHALL read as their `initial` value. A cell that is present in `json` but fails to decode via its `ReadWriter` SHALL produce a hard `Left(StateDecodeError)` carrying the cell id and the codec failure cause — corrupted checkpoints beat silent data loss. A non-`DObject` `json` (e.g. `DArray`, `DString`) SHALL produce a hard `Left(StateDecodeError)`.

**Given** a declared cell list `[c]` and a `json` snapshot `DObject(Map("c/id" -> DNumber(42), "unknown/id" -> DString("stranger")))`
**When** `restore(declared, json)` is called
**Then** the result is `Right(state)` where `state.get(c) == 42` and the unknown id `"unknown/id"` is ignored

**Rationale**: Leniency satisfies R5 (forward compatibility): restoring a checkpoint written by an older stack (fewer cells) or newer stack (more cells) must not fail. But a cell that IS present and decodes incorrectly is corruption, not a version skew — silent data loss would produce a state that looks valid but has wrong values, which is worse than a visible error.

#### Scenario: Restore with unknown fields succeeds

**Given** a declared cell list `[c]` and a snapshot with `c`'s key plus an unknown key `"other/cell"`
**When** `restore([c], snapshot)` is called
**Then** the result is `Right(state)` with `state.get(c)` equal to the decoded value and the unknown key ignored

#### Scenario: Restore with missing cells defaults to initial

**Given** a declared cell list `[c, d]` and a snapshot containing only `c`'s key
**When** `restore([c, d], snapshot)` is called
**Then** the result is `Right(state)` with `state.get(c)` equal to the decoded value and `state.get(d) == d.initial`

#### Scenario: Restore with a cell that fails to decode is a hard Left

**Given** a declared cell list `[c: StateCell[Int]]` and a snapshot where `c`'s key maps to `DString("not-a-number")`
**When** `restore([c], snapshot)` is called
**Then** the result is `Left(StateDecodeError)` carrying `c`'s cell id and the codec failure cause

#### Scenario: Restore with a non-DObject json is a hard Left

**Given** a declared cell list `[c]` and `json = DArray(Vector(DString("x")))`
**When** `restore([c], json)` is called
**Then** the result is `Left(StateDecodeError)` — a non-object payload is a structural decode error

#### Scenario: Restore of an empty snapshot with declared cells

**Given** a declared cell list `[c, d]` and `json = DObject(Map.empty)`
**When** `restore([c, d], json)` is called
**Then** the result is `Right(state)` with both cells at their `initial` values

### Requirement: Snapshot-restore round-trip preserves state

For any `HarnessState` `s` constructed over a declared cell list, `restore(declared, s.snapshot)` SHALL produce `Right(s')` where `s'` is observationally equal to `s` — every cell's value in `s'` equals its value in `s`. Cells absent from `s` (reading as `initial`) SHALL remain at `initial` after round-trip. This is law L7.

**Given** a state `s` over cells `[c, d]` with `c` set to `42` and `d` unset (reading as `d.initial`)
**When** `restore([c, d], s.snapshot)` is called
**Then** the result is `Right(s')` where `s'.get(c) == 42` and `s'.get(d) == d.initial`

**Rationale**: The round-trip property is the correctness guarantee for the checkpoint subsystem: a state persisted via `snapshot` and restored via `restore` must produce the same observable state. Without this, interrupt/resume would silently corrupt middleware state.

#### Scenario: Round-trip of a state with set values

**Given** a state `s` over `[c, d]` with `c = 42` and `d = "hello"`
**When** `restore([c, d], s.snapshot)` is called
**Then** `s'.get(c) == 42` and `s'.get(d) == "hello"`

#### Scenario: Round-trip of a state with only initial values

**Given** `s = HarnessState.initial([c, d])`
**When** `restore([c, d], s.snapshot)` is called
**Then** `s'.get(c) == c.initial` and `s'.get(d) == d.initial`

#### Scenario: Round-trip of empty state

**Given** `s = HarnessState.empty`
**When** `restore([], s.snapshot)` is called
**Then** the result is `Right(HarnessState.empty)` (empty DObject, no declared cells)

### Requirement: Restore leniency for forward and backward compatibility

The restore operation SHALL handle two version-skew scenarios without failure: (1) `restore(cells, snapshot(s) ++ unknownFields)` — a snapshot written by a newer stack with extra cells — SHALL succeed and ignore the unknown fields; (2) `restore(cells ++ newCells, snapshot(s))` — a snapshot written by an older stack missing cells that the current stack declares — SHALL succeed with the new cells at their `initial` values. This is law L8.

**Given** a state `s` over cells `[c]` and a snapshot `s.snapshot` augmented with an unknown key `"future/cell" -> DString("from-the-future")`
**When** `restore([c], augmentedSnapshot)` is called
**Then** the result is `Right(state)` with `state.get(c) == s.get(c)` and the unknown field ignored

**Rationale**: Checkpoints persist across code deploys. A newer version of the stack may add cells (backward compat: old snapshot, new cell list) or remove them (forward compat: new snapshot, old cell list). Both must be non-failing; only actual decode corruption fails.

#### Scenario: Forward compatibility — unknown fields in snapshot are ignored

**Given** a snapshot with cell `c`'s data plus an unknown key `"newMW/field" -> DNumber(99)`
**When** `restore([c], snapshot)` is called
**Then** the result is `Right(state)` with `state.get(c)` correct and no error about the unknown key

#### Scenario: Backward compatibility — new cells default to initial

**Given** a snapshot written by an older stack containing only cell `c`'s data, and a current declared cell list `[c, d]` where `d` is newly added
**When** `restore([c, d], snapshot)` is called
**Then** the result is `Right(state)` with `state.get(c)` correct and `state.get(d) == d.initial`

#### Scenario: Both skews simultaneously

**Given** a snapshot with `c`'s data plus unknown key `"future/x"`, and a declared list `[c, d]` where `d` is new
**When** `restore([c, d], snapshot)` is called
**Then** the result is `Right(state)` with `state.get(c)` correct, `state.get(d) == d.initial`, and `"future/x"` ignored

### Requirement: Privacy is structural for Private cells

For a `Private` cell `p`, projecting a parent state to a child SHALL yield `child.get(p) == p.initial` (the child never sees the parent's value). Merging child states back SHALL yield `parent.get(p) == parent.get(p)` unchanged (the parent's value is unaffected by child writes). This is law L9. Privacy SHALL be structural — enforced by the projection/merge-back operations, not by naming convention or runtime filtering.

**Given** a parent state with a `Private` cell `p` set to `42`, and a child that declares `p` and writes `99` to it
**When** the parent state is projected to the child, the child writes `99`, and the child's state is merged back
**Then** the child's initial `get(p)` is `p.initial` (not `42`), and after merge-back the parent's `get(p)` is still `42`

**Rationale**: deepagents enforces only the write-direction (child writes don't leak up) via name filtering. Here both directions hold by construction: the child reads `initial` (not the parent's value), and the parent's value is untouched by merge-back. This is stronger than the original and eliminates a class of state-leak bugs.

#### Scenario: Private cell projection yields initial

**Given** a parent state with `Private` cell `p` set to `42`
**When** `project(parent, [p])` is called
**Then** the child's `get(p) == p.initial` (not `42`)

#### Scenario: Private cell merge-back preserves parent value

**Given** a parent state with `Private` cell `p` set to `42` and a child state where `p` is set to `99`
**When** `mergeBack(parent, [child], [p])` is called
**Then** the result's `get(p) == 42` (parent's value unchanged)

#### Scenario: Private cell with no parent write

**Given** a parent state where `Private` cell `p` is unset (reads as `p.initial`)
**When** `project(parent, [p])` is called
**Then** the child's `get(p) == p.initial` (consistent whether or not the parent wrote to it)

### Requirement: Shared cell merge is a join semilattice for parallel delegation

A `Shared` cell that will be written under parallel delegation SHALL supply a `merge` function that is a commutative, associative, idempotent binary operation — a join semilattice. The `mergeBack` operation over multiple concurrently produced child states SHALL be order-independent: folding `cell.merge` over any permutation of the children SHALL produce the same result. This is law L11. Sequential-only shared cells MAY keep last-write-wins; the semilattice constraint applies where parallelism does.

**Given** a `Shared` cell `s` with `merge = set-union`, a parent value `Set("a")`, and three children producing `Set("b")`, `Set("c")`, `Set("d")`
**When** `mergeBack` folds `s.merge` over the three children in any permutation
**Then** the result is `Set("a", "b", "c", "d")` regardless of child ordering

**Rationale**: deepagents runs `task` calls in parallel; LangGraph resolves concurrent writes with channel reducers that are order-sensitive unless the author happens to pick a commutative one. This spec deliberately requires the CRDT discipline (commutative, associative, idempotent merge) for parallel-shared cells, making `mergeBack` order-independent by construction. This is a place where the port is stronger than the original.

#### Scenario: Commutativity — merge(a, b) == merge(b, a)

**Given** a `Shared` cell with a semilattice merge (e.g. set-union) and values `a = Set("x")`, `b = Set("y")`
**When** `merge(a, b)` and `merge(b, a)` are evaluated
**Then** both results are equal (`Set("x", "y")`)

#### Scenario: Associativity — merge(a, merge(b, c)) == merge(merge(a, b), c)

**Given** a `Shared` cell with a semilattice merge and values `a`, `b`, `c`
**When** left-associated and right-associated merges are evaluated
**Then** both results are equal

#### Scenario: Idempotence — merge(a, a) == a

**Given** a `Shared` cell with a semilattice merge and value `a = Set("x")`
**When** `merge(a, a)` is evaluated
**Then** the result is `a` (`Set("x")`)

#### Scenario: mergeBack over permutations of children produces the same result

**Given** a `Shared` cell `s` with semilattice merge, a parent value, and children `c1, c2, c3` producing distinct values
**When** `mergeBack(parent, List(c1, c2, c3), [s])` and `mergeBack(parent, List(c3, c1, c2), [s])` are evaluated
**Then** both results have the same value for `s`

#### Scenario: Last-write-wins merge is NOT a semilattice and fails the law

**Given** a `Shared` cell with last-write-wins merge `(_, child) => child` and children producing `1` and `2`
**When** `mergeBack` is evaluated over `List(child1, child2)` vs `List(child2, child1)`
**Then** the results differ (`2` vs `1`) — this demonstrates why last-write-wins is only valid for sequential delegation, not parallel

### Requirement: Merge-back neutrality for untouched children

For a child that was projected from the parent and whose `Shared` cells were not written (the child's value equals the projected value), `mergeBack(parent, List(project(parent, cells)), cells)` SHALL be observationally equal to `parent`. This is law L10. For `Shared` cells, this holds exactly when `merge` is idempotent: `merge(parentValue, parentValue) == parentValue`.

**Given** a parent state with a `Shared` cell `s` (merge = set-union) set to `Set("a")`
**When** a child is projected from the parent (inheriting `Set("a")`), the child does not write to `s`, and `mergeBack(parent, [child], [s])` is called
**Then** the result's `get(s) == Set("a")` (unchanged — `merge(Set("a"), Set("a")) == Set("a")` by idempotence)

**Rationale**: A child that does nothing should not change the parent's state. This ties L10 to L11's idempotence requirement: if merge is not idempotent, an untouched child would corrupt the parent's value, which is a correctness violation.

#### Scenario: Untouched child with idempotent merge preserves parent

**Given** a parent with `Shared` cell `s` (set-union) set to `Set("a", "b")` and a projected child that does not write to `s`
**When** `mergeBack(parent, [child], [s])` is called
**Then** `get(s) == Set("a", "b")` (idempotence: `union(Set("a","b"), Set("a","b")) == Set("a","b")`)

#### Scenario: Untouched child with non-idempotent merge corrupts parent

**Given** a parent with `Shared` cell `s` (merge = concatenation, NOT idempotent) set to `List("a")` and a projected child that does not write to `s`
**When** `mergeBack(parent, [child], [s])` is called
**Then** `get(s) == List("a", "a")` (corrupted — this demonstrates why non-idempotent merge violates L10)

### Requirement: StateDecodeError is a hard AdkError variant

The system SHALL provide `StateDecodeError` as a sealed trait extending `AdkError`, representing a hard failure during `restore` when a cell fails to decode. It SHALL carry the offending `CellId` (or a string description for non-cell structural errors like non-`DObject` input) and the codec failure cause. `StateDecodeError` SHALL be a new variant of the `AdkError` sealed hierarchy, and existing exhaustive pattern matches over `AdkError` SHALL require updating to handle it.

**Given** a `restore` call that fails because cell `"todo/items"` decoded a `DString` where an `Int` was expected
**When** the `Left` value is inspected
**Then** it is a `StateDecodeError` carrying `cellId = "todo/items"` and the codec's failure message

**Rationale**: Corrupted checkpoints must produce a visible, typed error — not a silent default. Extending `AdkError` integrates the failure into the existing error hierarchy so the loop's error handling (`RunResult.Failed`) catches it uniformly. The sealed-trait extension is a compile-breaking change for exhaustive matches, which is intentional (every `AdkError` handler must decide what to do with a decode failure).

#### Scenario: Decode error carries the cell id

**Given** a `restore` failure on cell `c` with id `"m/x"`
**When** the `StateDecodeError` is inspected
**Then** it identifies the cell id `"m/x"` and the codec failure cause

#### Scenario: Structural error for non-DObject input

**Given** a `restore` call with `json = DString("not-an-object")`
**When** the `Left` value is inspected
**Then** it is a `StateDecodeError` describing the structural mismatch (expected `DObject`, received `DString`)

#### Scenario: Existing AdkError exhaustive matches must handle StateDecodeError

**Given** an existing exhaustive `match` over `AdkError` that does not list `StateDecodeError`
**When** the code is compiled after this change
**Then** compilation fails (exhaustiveness escalation — the new variant must be handled)

## Properties (Ring 3)

### Property: Get-set coherence (set-get)

**Invariant**: For any `HarnessState` `s`, cell `c: StateCell[A]`, and value `v: A`, `s.set(c)(v).get(c) == v`.

**Generator strategy**: `genHarnessState` (constructive — builds a state from a generated list of cells with generated values; cells use `Int`, `String`, and `Set[String]` types with known `ReadWriter` instances; cell count ∈ `Range.linear 0 8`; values from `Gen.int(Range.linear -1000 1000)`, `Gen.string(Gen.alpha, Range.linear 0 20)`, `genSetString`; classify by cell type and count). Covers the empty-state edge (0 cells).

```
forAll { (s: HarnessState, c: StateCell[Int], v: Int) =>
  s.set(c)(v).get(c) == v
}
```

### Property: Get-set coherence (set-disjoint)

**Invariant**: For any `HarnessState` `s`, cells `c` and `d` with `c.id != d.id`, and value `v`, `s.set(d)(v).get(c) == s.get(c)`.

**Generator strategy**: `genTwoDistinctCells` (constructive — generates two cells with distinct owners guaranteeing `c.id != d.id`; types from `Int`/`String`; `Int` values from `Gen.int(Range.linear -1000 1000)`, `String` values from `Gen.string(Gen.alpha, Range.linear 0 20)`; state from `genHarnessState` with cell count ∈ `Range.linear 0 8` seeded with both cells; classify by whether `c` is set or unset in `s`). Covers the edge where `c` is absent (reads initial).

```
forAll { (s: HarnessState, c: StateCell[Int], d: StateCell[String], v: String) =>
  s.set(d)(v).get(c) == s.get(c)
}
```

### Property: Codec round-trip per cell (L7a)

**Invariant**: For every declared cell `cell: StateCell[A]` and generated value `a: A`, `read(cell.rw)(write(cell.rw)(a)) == a` (codec round-trip identity).

**Generator strategy**: `genCellValue` (constructive — generates values for cells of types `Int`, `String`, `Set[String]`, `List[Int]` — all with known `ReadWriter` instances; values sized by `Range.linear 0 20` for collections; classify by cell type). Covers empty collections and single-element collections as edge cases.

```
forAll { (cell: StateCell[A], a: A) =>
  read(cell.rw)(write(cell.rw)(a)) == a
}
```

### Property: Snapshot-restore round-trip (L7b)

**Invariant**: For any `HarnessState` `s` over declared cells, `restore(cells, s.snapshot) == Right(s')` where `s'` is observationally equal to `s` (every cell's `get` agrees).

**Generator strategy**: `genStateWithCells` (constructive — generates a declared cell list and a state over those cells with some cells set and some left at initial; cell types `Int`/`String`/`Set[String]`; set fraction ∈ `Range.linear 0 100`%; classify by set-count vs total-count ratio). Covers the all-initial edge (nothing set) and the all-set edge.

```
forAll { (cells: List[StateCell[?]], s: HarnessState) =>
  restore(cells, s.snapshot) == Right(s) // observational equality: get agrees per cell
}
```

### Property: Restore leniency — unknown fields ignored (L8a)

**Invariant**: For any state `s` over cells and a snapshot `s.snapshot` augmented with unknown keys, `restore(cells, augmentedSnapshot)` succeeds and the result agrees with `s` on all declared cells.

**Generator strategy**: `genUnknownFields` (constructive — generates a state, snapshots it, then adds 1-5 unknown keys with arbitrary `JsonValue` values; unknown key names from `Gen.string(Gen.alpha, Range.linear 1 10)` prefixed with `"unknown/"`; values from `genJsonValue`; classify by unknown-field count). Covers the zero-unknown edge (plain round-trip).

```
forAll { (cells: List[StateCell[?]], s: HarnessState, unknowns: Map[String, JsonValue]) =>
  val augmented = DObject(s.snapshot.asInstanceOf[DObject].value ++ unknowns)
  restore(cells, augmented).isRight &&
    restore(cells, augmented).forall(_.get agrees with s.get per cell)
}
```

### Property: Restore leniency — new cells default to initial (L8b)

**Invariant**: For any state `s` over cells `[c]` and additional cells `newCells` not in `s`, `restore(cells ++ newCells, s.snapshot)` succeeds with `newCells` at their `initial` values.

**Generator strategy**: `genExtraCells` (constructive — generates a state over `[c]`, then generates 1-3 extra cells with distinct ids not in `s`; extra-cell count ∈ `Range.linear 1 3`; values from `Gen.int(Range.linear -1000 1000)` / `Gen.string(Gen.alpha, Range.linear 0 20)`; classify by extra-cell count). Covers the zero-extra edge.

```
forAll { (cells: List[StateCell[?]], s: HarnessState, newCells: List[StateCell[?]]) =>
  val result = restore(cells ++ newCells, s.snapshot)
  result.isRight && newCells.forall(nc => result.exists(_.get(nc) == nc.initial))
}
```

### Property: Privacy — projection and merge-back (L9)

**Invariant**: For a `Private` cell `p`, `project(parent, [p]).get(p) == p.initial` and `mergeBack(parent, children, [p]).get(p) == parent.get(p)` for all children.

**Generator strategy**: `genPrivateCellState` (constructive — generates a `Private` cell with a value type (`Int`/`String`), a parent state with `p` set to a non-initial value, and 0-3 child states where `p` is set to arbitrary values; child count ∈ `Range.linear 0 3`; `Int` values from `Gen.int(Range.linear -1000 1000)`, `String` values from `Gen.string(Gen.alpha, Range.linear 0 20)`; classify by child count). Covers the zero-child edge (merge-back with no children is identity).

```
forAll { (p: StateCell[Int] { visibility = Private }, parent: HarnessState, children: List[HarnessState]) =>
  project(parent, List(p)).get(p) == p.initial &&
  mergeBack(parent, children, List(p)).get(p) == parent.get(p)
}
```

### Property: Semilattice laws for Shared cell merge (L11)

**Invariant**: For a `Shared` cell `s` with a semilattice `merge`, commutativity (`merge(a, b) == merge(b, a)`), associativity (`merge(a, merge(b, c)) == merge(merge(a, b), c)`), and idempotence (`merge(a, a) == a`) all hold.

**Generator strategy**: `genSemilatticeCell` (constructive — generates `Shared` cells with known semilattice merges: set-union for `Set[String]`, `max` for `Int`, map-union for `Map[String, Int]`; values from `genSetString` / `Gen.int(Range.linear -100 100)` / `genMapStringInt`; classify by merge type). Covers empty-set/max-at-zero/empty-map edges.

```
forAll { (s: SharedCell[A], a: A, b: A, c: A) =>
  s.merge(a, b) == s.merge(b, a) &&                           // commutativity
  s.merge(a, s.merge(b, c)) == s.merge(s.merge(a, b), c) &&   // associativity
  s.merge(a, a) == a                                          // idempotence
}
```

### Property: mergeBack order-independence over child permutations (L11 consequence)

**Invariant**: For a `Shared` cell `s` with a semilattice merge, `mergeBack(parent, children, [s])` produces the same value for `s` regardless of the permutation of `children`.

**Generator strategy**: `genChildrenForMerge` (constructive — generates a `Shared` cell with a semilattice merge, a parent state, and 1-5 child states with distinct writes; child count ∈ `Range.linear 1 5`; set values from `genSetString` with element count ∈ `Range.linear 0 10`; permutations generated via `Gen.shuffle(children)`; classify by child count). Covers the single-child edge (trivially order-independent).

```
forAll { (s: StateCell[A] { visibility = Shared, merge = semilattice }, parent: HarnessState, children: List[HarnessState]) =>
  val perm = Gen.shuffle(children)
  mergeBack(parent, children, List(s)).get(s) == mergeBack(parent, perm, List(s)).get(s)
}
```

### Property: Merge-back neutrality for untouched children (L10)

**Invariant**: For any parent and `Shared` cell `s` with idempotent merge, `mergeBack(parent, List(project(parent, [s])), [s]).get(s) == parent.get(s)`.

**Generator strategy**: `genUntouchedChild` (constructive — generates a `Shared` cell with a semilattice merge (set-union for `Set[String]`, `max` for `Int`), a parent state with `s` set to a generated value; set element count ∈ `Range.linear 0 10`, `Int` values from `Gen.int(Range.linear -100 100)`; classify by merge type). Covers the initial-valued parent edge.

```
forAll { (s: StateCell[A] { visibility = Shared, merge = semilattice }, parent: HarnessState) =>
  val child = project(parent, List(s))
  mergeBack(parent, List(child), List(s)).get(s) == parent.get(s)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `StateCell[A]` without a `ReadWriter[A]` in scope | The codec is mandatory at declaration — a non-checkpointable cell must not exist | `assertDoesNotCompile("StateCell[NoCodecType](owner, \"x\", init)")` |
| `CellId` constructed from a raw `String` without `MiddlewareName` | The opaque type prevents bypassing the owner-namespacing constructor | `assertDoesNotCompile("val id: CellId = \"raw\"")` — only `CellId.apply(owner, name)` constructs |
| `MiddlewareName` compared or concatenated as a raw `String` | The opaque type forces explicit `.value` access | `assertDoesNotCompile("val s: String = middlewareName")` — must use `.value` |
| A catch-all `case _ => ...` in a match over `CellVisibility` | Exhaustiveness escalation makes this a compile warning/error; all three cases must be explicit | `assertDoesNotCompile("v match { case Private => () }")` — missing `Inherited` and `Shared` |
| A catch-all `case _ => ...` in a match over `AdkError` that does not handle `StateDecodeError` | The new variant must be handled explicitly | `assertDoesNotCompile("e match { case _: LlmCallError => (); ... }")` — missing `StateDecodeError` |
| `HarnessState` constructed directly via `new HarnessState(...)` from outside the `harness` package | The private constructor forces construction through `empty`/`initial`/`set`/`restore` | `assertDoesNotCompile("new HarnessState(Map.empty)")` from outside `org.adk4s.harness` |

## Formal Contracts (Ring 6)

Ring 6 applies via the VERIFIED-MIRROR pattern
(`openspec/schemas/verified-scala3/templates/verified-mirror.md`). The shipped
`HarnessState` cannot be verified directly — it uses `ReadWriter` typeclass
instances, `asInstanceOf`, `JsonValue` (= `smithy4s.Document`), and opaque
types, and the Stainless frontend is pinned to Scala 3.7.2 while this build is
3.8.4. Neither is grounds to skip: the *algorithm* under `HarnessState` get/set
and the semilattice merge laws are pure kernels expressible in PureScala at
some abstraction.

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `HarnessState.get`/`set` coherence | The typed-map lens laws (set-get, set-disjoint) | Yes — `HarnessStateKernel` mirror |
| `cell.merge` semilattice laws | Commutativity, associativity, idempotence for Shared cells | Yes — `SemilatticeKernel` mirror |
| `snapshot`/`restore` round-trip | Codec round-trip + leniency | No — depends on `ReadWriter` codec behavior (external to PureScala); delegated to Ring 3 properties L7/L8 |
| `project`/`mergeBack` privacy | Private cell isolation | No — reduces to get/set coherence + visibility filtering; covered by Ring 3 property L9 |

### The abstraction

A `HarnessState` becomes a `Map[BigInt, BigInt]` (cell id → value identity); a
`StateCell` becomes a `BigInt` identity (index into the distinct cells seen);
`get`/`set` become map lookup/update. The semilattice merge becomes a pure
binary function over `BigInt` identities with the three laws stated as
quantifier-free VCs.

### Contract: HarnessStateKernel — get/set coherence

**Precondition** (`require`): the map's keys are distinct (construction-time uniqueness invariant).
**Postcondition** (`ensuring`): `get(set(m, k, v), k) == v` (set-get) and `get(set(m, k, v), j) == get(m, j)` for `k != j` (set-disjoint).

```scala
object HarnessStateKernel {
  def get(m: Map[BigInt, BigInt], k: BigInt): BigInt = m.getOrElse(k, BigInt(0))
  def set(m: Map[BigInt, BigInt], k: BigInt, v: BigInt): Map[BigInt, BigInt] = m.updated(k, v)

  def setGet(m: Map[BigInt, BigInt], k: BigInt, v: BigInt): Boolean = {
    get(set(m, k, v), k) == v
  }.holds

  def setDisjoint(m: Map[BigInt, BigInt], k: BigInt, j: BigInt, v: BigInt): Boolean = {
    require(k != j)
    get(set(m, j, v), k) == get(m, k)
  }.holds
}
```

### Contract: SemilatticeKernel — merge laws

**Precondition** (`require`): none — the laws hold for all inputs.
**Postcondition** (`ensuring`): commutativity (`merge(a, b) == merge(b, a)`), associativity (`merge(a, merge(b, c)) == merge(merge(a, b), c)`), and idempotence (`merge(a, a) == a`).

```scala
object SemilatticeKernel {
  def merge(a: BigInt, b: BigInt): BigInt = max(a, b)  // model: max is a join semilattice

  def commutative(a: BigInt, b: BigInt): Boolean = {
    merge(a, b) == merge(b, a)
  }.holds

  def associative(a: BigInt, b: BigInt, c: BigInt): Boolean = {
    merge(a, merge(b, c)) == merge(merge(a, b), c)
  }.holds

  def idempotent(a: BigInt): Boolean = {
    merge(a, a) == a
  }.holds
}
```

### Bridge — how the shipped code is bound to the model

`HarnessStateModelBridgeSpec` (Hedgehog, in `adk4s-harness-api` test sources)
runs the real `HarnessState` and the model on the SAME generated inputs and
asserts they agree on exactly the proven invariants:

1. the real `get(c)(set(c)(v)(s))` equals `v` (set-get), matching `HarnessStateKernel.setGet`;
2. the real `get(c)(set(d)(v)(s))` equals `get(c)(s)` for `c.id != d.id` (set-disjoint), matching `HarnessStateKernel.setDisjoint`.

`SemilatticeModelBridgeSpec` (Hedgehog, in `adk4s-harness-api` test sources)
runs real `Shared` cells with semilattice merges and asserts the three laws
hold, matching `SemilatticeKernel`.

Build wiring this spec commits to: `adk4s-harness-api dependsOn(verified % Test)`.
TASTy is backward compatible, so the 3.8.4 module may read the 3.7.2 artifact —
never the reverse, which is why `verified` depends on nothing project-local.
`stainlessEnabled := false` by default, so the bridge test pays only a plain
compile cost; verification is the explicit `ring6` command.

### Scope note

Stainless proves the two load-bearing invariant families:
1. GET/SET COHERENCE — set-get and set-disjoint for the typed-map kernel;
2. SEMILATTICE LAWS — commutativity, associativity, idempotence for the merge kernel.

Snapshot/restore round-trip (L7) and leniency (L8) are DELEGATED to Ring 3
property tests — proving them in Stainless requires modeling `ReadWriter`
codec behavior, which is external to PureScala and adds no assurance the
property tests do not already give. Privacy (L9) is similarly delegated — it
reduces to get/set coherence plus visibility filtering, both covered by Ring 3.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Cell with mandatory codec | Requirement: StateCell is a typed declaration unit with mandatory codec | type system (ReadWriter context bound) + compile-negative test | StateCellSpec, StateCellTypeContract |
| Cell without ReadWriter does not compile | Requirement: StateCell is a typed declaration unit with mandatory codec + Scenario: Cell without a ReadWriter in scope does not compile | compile-negative test (assertDoesNotCompile) | StateCellTypeContract |
| Cell equality by id | Requirement: StateCell is a typed declaration unit with mandatory codec + Scenario: Cell equality is by id, not object identity | Hedgehog property (cell equality by id) + unit test | StateCellSpec |
| CellId is owner-namespaced opaque string | Requirement: CellId is a stable owner-namespaced opaque string | type system (opaque type) + compile-negative test | CellIdTypeContract |
| CellId not constructible from raw String | Requirement: CellId is a stable owner-namespaced opaque string + Scenario: CellId is not constructible from a raw String without a MiddlewareName | compile-negative test (assertDoesNotCompile) | CellIdTypeContract |
| MiddlewareName is opaque | Requirement: CellId is a stable owner-namespaced opaque string | type system (opaque type) + compile-negative test | MiddlewareNameTypeContract |
| CellVisibility has exactly three cases | Requirement: CellVisibility enum has exactly three levels | type system (enum exhaustiveness) + compile-negative test | CellVisibilitySpec |
| No catch-all in CellVisibility match | Requirement: CellVisibility enum has exactly three levels | compile-negative test (assertDoesNotCompile) | CellVisibilityTypeContract |
| Private cell invisible to projection | Requirement: CellVisibility enum has exactly three levels + Scenario: Private cell is invisible to sub-agent projection | Hedgehog property (Privacy — projection and merge-back L9) | HarnessStateBoundarySpec |
| Inherited cell discards child writes | Requirement: CellVisibility enum has exactly three levels + Scenario: Inherited cell copies parent value but discards child writes | unit test (scenario test) | HarnessStateBoundarySpec |
| Shared cell merges child writes | Requirement: CellVisibility enum has exactly three levels + Scenario: Shared cell copies parent value and merges child writes | unit test (scenario test) + Hedgehog property (Semilattice laws L11) | HarnessStateBoundarySpec |
| HarnessState get is total | Requirement: HarnessState is a typed heterogeneous map with total get | Hedgehog property (Get-set coherence set-get) | HarnessStateSpec |
| Get on empty returns initial | Requirement: HarnessState is a typed heterogeneous map with total get + Scenario: Get on empty state returns initial | unit test (scenario test) | HarnessStateSpec |
| Set produces new state (immutability) | Requirement: HarnessState is a typed heterogeneous map with total get + Scenario: Set does not mutate the original state | unit test (scenario test) + WartRemover Var wart | HarnessStateSpec |
| Update is set-get composition | Requirement: HarnessState is a typed heterogeneous map with total get + Scenario: Update applies a function to the current value | unit test (scenario test) | HarnessStateSpec |
| Initial sets every cell to initial | Requirement: HarnessState is a typed heterogeneous map with total get + Scenario: Initial state sets every declared cell to its initial | unit test (scenario test) | HarnessStateSpec |
| Get/set coherence (set-get) | Requirement: HarnessState get/set coherence + Scenario: Set-get coherence | Hedgehog property (Get-set coherence set-get) + formal contract (Ring 6 HarnessStateKernel) | HarnessStateSpec, HarnessStateKernel |
| Get/set coherence (set-disjoint) | Requirement: HarnessState get/set coherence + Scenario: Set-disjoint coherence — writing one cell does not affect another | Hedgehog property (Get-set coherence set-disjoint) + formal contract (Ring 6 HarnessStateKernel) | HarnessStateSpec, HarnessStateKernel |
| Shipped code conforms to verified model (get/set) | Requirement: HarnessState get/set coherence | bridge property (Ring 3 + Ring 6) | HarnessStateModelBridgeSpec |
| Snapshot produces DObject | Requirement: Snapshot produces a JsonValue DObject | unit test (scenario test) + Hedgehog property (Snapshot-restore round-trip L7b) | HarnessStateSnapshotSpec |
| Snapshot of empty is empty DObject | Requirement: Snapshot produces a JsonValue DObject + Scenario: Snapshot of empty state is an empty DObject | unit test (scenario test) | HarnessStateSnapshotSpec |
| Snapshot includes every present cell | Requirement: Snapshot produces a JsonValue DObject + Scenario: Snapshot includes every present cell | unit test (scenario test) | HarnessStateSnapshotSpec |
| Restore ignores unknown fields | Requirement: Restore is lenient with hard decode failure + Scenario: Restore with unknown fields succeeds | Hedgehog property (Restore leniency — unknown fields ignored L8a) | HarnessStateRestoreSpec |
| Restore defaults missing cells to initial | Requirement: Restore is lenient with hard decode failure + Scenario: Restore with missing cells defaults to initial | Hedgehog property (Restore leniency — new cells default to initial L8b) | HarnessStateRestoreSpec |
| Restore hard-fails on decode error | Requirement: Restore is lenient with hard decode failure + Scenario: Restore with a cell that fails to decode is a hard Left | unit test (scenario test) | HarnessStateRestoreSpec |
| Restore hard-fails on non-DObject input | Requirement: Restore is lenient with hard decode failure + Scenario: Restore with a non-DObject json is a hard Left | unit test (scenario test) | HarnessStateRestoreSpec |
| Snapshot-restore round-trip preserves state | Requirement: Snapshot-restore round-trip preserves state | Hedgehog property (Snapshot-restore round-trip L7b) + Hedgehog property (Codec round-trip per cell L7a) | HarnessStateRestoreSpec |
| Forward compatibility — unknown fields ignored | Requirement: Restore leniency for forward and backward compatibility + Scenario: Forward compatibility — unknown fields in snapshot are ignored | Hedgehog property (Restore leniency — unknown fields ignored L8a) | HarnessStateRestoreSpec |
| Backward compatibility — new cells default to initial | Requirement: Restore leniency for forward and backward compatibility + Scenario: Backward compatibility — new cells default to initial | Hedgehog property (Restore leniency — new cells default to initial L8b) | HarnessStateRestoreSpec |
| Privacy — projection yields initial | Requirement: Privacy is structural for Private cells + Scenario: Private cell projection yields initial | Hedgehog property (Privacy — projection and merge-back L9) | HarnessStateBoundarySpec |
| Privacy — merge-back preserves parent | Requirement: Privacy is structural for Private cells + Scenario: Private cell merge-back preserves parent value | Hedgehog property (Privacy — projection and merge-back L9) | HarnessStateBoundarySpec |
| Semilattice commutativity | Requirement: Shared cell merge is a join semilattice for parallel delegation + Scenario: Commutativity — merge(a, b) == merge(b, a) | Hedgehog property (Semilattice laws for Shared cell merge L11) + formal contract (Ring 6 SemilatticeKernel) | SemilatticeSpec, SemilatticeKernel |
| Semilattice associativity | Requirement: Shared cell merge is a join semilattice for parallel delegation + Scenario: Associativity — merge(a, merge(b, c)) == merge(merge(a, b), c) | Hedgehog property (Semilattice laws for Shared cell merge L11) + formal contract (Ring 6 SemilatticeKernel) | SemilatticeSpec, SemilatticeKernel |
| Semilattice idempotence | Requirement: Shared cell merge is a join semilattice for parallel delegation + Scenario: Idempotence — merge(a, a) == a | Hedgehog property (Semilattice laws for Shared cell merge L11) + formal contract (Ring 6 SemilatticeKernel) | SemilatticeSpec, SemilatticeKernel |
| mergeBack order-independence | Requirement: Shared cell merge is a join semilattice for parallel delegation + Scenario: mergeBack over permutations of children produces the same result | Hedgehog property (mergeBack order-independence over child permutations L11 consequence) | HarnessStateBoundarySpec |
| Last-write-wins fails semilattice law | Requirement: Shared cell merge is a join semilattice for parallel delegation + Scenario: Last-write-wins merge is NOT a semilattice and fails the law | unit test (scenario test — counterexample) | SemilatticeSpec |
| Merge-back neutrality for untouched children | Requirement: Merge-back neutrality for untouched children | Hedgehog property (Merge-back neutrality for untouched children L10) | HarnessStateBoundarySpec |
| Non-idempotent merge violates L10 | Requirement: Merge-back neutrality for untouched children + Scenario: Untouched child with non-idempotent merge corrupts parent | unit test (scenario test — counterexample) | HarnessStateBoundarySpec |
| StateDecodeError extends AdkError | Requirement: StateDecodeError is a hard AdkError variant | type system (sealed trait extension) + compile-negative test | StateDecodeErrorTypeContract |
| StateDecodeError carries cell id | Requirement: StateDecodeError is a hard AdkError variant + Scenario: Decode error carries the cell id | unit test (scenario test) | HarnessStateRestoreSpec |
| Structural error for non-DObject input | Requirement: StateDecodeError is a hard AdkError variant + Scenario: Structural error for non-DObject input | unit test (scenario test) | HarnessStateRestoreSpec |
| AdkError exhaustive matches must handle StateDecodeError | Requirement: StateDecodeError is a hard AdkError variant + Scenario: Existing AdkError exhaustive matches must handle StateDecodeError | compile-negative test (assertDoesNotCompile) | StateDecodeErrorTypeContract |
| HarnessState private constructor | Compile-Negative: HarnessState constructed directly via new HarnessState | compile-negative test (assertDoesNotCompile) | HarnessStateTypeContract |
| Shipped code conforms to verified model (semilattice) | Requirement: Shared cell merge is a join semilattice for parallel delegation | bridge property (Ring 3 + Ring 6) | SemilatticeModelBridgeSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `HarnessState` | final class | `adk4s-harness-api/src/main/scala/org/adk4s/harness/HarnessState.scala` | Typed heterogeneous map; `get`/`set`/`update`/`snapshot`/`entries`; companion `empty`/`initial`/`restore`/`project`/`mergeBack` |
| `StateCell` | final class | `adk4s-harness-api/src/main/scala/org/adk4s/harness/StateCell.scala` | Unit of declaration; `CellId` opaque type in companion; `apply` factory with `ReadWriter` context bound |
| `CellVisibility` | enum | `adk4s-harness-api/src/main/scala/org/adk4s/harness/CellVisibility.scala` | `Private` / `Inherited` / `Shared` |
| `MiddlewareName` | opaque type | `adk4s-harness-api/src/main/scala/org/adk4s/harness/MiddlewareName.scala` | Opaque type backed by `String`; `apply` factory + `.value` extension |
| `StateDecodeError` | sealed trait | `adk4s-harness-api/src/main/scala/org/adk4s/harness/StateDecodeError.scala` (or `org.adk4s.core.error`) | Extends `AdkError`; carries cell id + codec failure cause |
| `HarnessStateKernel` | verified mirror | `verified/src/main/scala/org/adk4s/verified/HarnessStateKernel.scala` (Scala 3.7.2) | PureScala model of get/set coherence; Stainless target |
| `SemilatticeKernel` | verified mirror | `verified/src/main/scala/org/adk4s/verified/SemilatticeKernel.scala` (Scala 3.7.2) | PureScala model of semilattice laws; Stainless target |
| `adk4s-harness-api` | sbt module | `build.sbt` | New module; depends on `adk4s-core` (for `JsonValue`/`JsonValueCodec`/`AdkError`), llm4s model types, smithy4s-json; `dependsOn(verified % Test)` for bridge tests |
| `ring6` | sbt alias | `build.sbt` | `addCommandAlias("ring6", "; set verified / stainlessEnabled := true ; verified / compile")` |
| `HarnessStateModelBridgeSpec` | Hedgehog bridge test | `adk4s-harness-api/src/test/scala/org/adk4s/harness/HarnessStateModelBridgeSpec.scala` | Binds shipped `HarnessState` to `HarnessStateKernel` on shared generated inputs |
| `SemilatticeModelBridgeSpec` | Hedgehog bridge test | `adk4s-harness-api/src/test/scala/org/adk4s/harness/SemilatticeModelBridgeSpec.scala` | Binds shipped `Shared` cell merges to `SemilatticeKernel` on shared generated inputs |
