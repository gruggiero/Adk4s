# Concept: HarnessState

## Concept specification

```
concept HarnessState
purpose
    Carry middleware-declared state through the agent loop, across
    checkpoints, and across sub-agent boundaries, as a typed
    heterogeneous map with total reads, immutable updates, and
    visibility-controlled projection/merge-back.
state
    cells: HarnessState -> Map[CellId, (StateCell[?], Any)]
actions
    get [ cell: StateCell[A] ]
        => [ value: A ]
    set [ cell: StateCell[A] ; value: A ]
        => [ state: HarnessState ]
    update [ cell: StateCell[A] ; f: A => A ]
        => [ state: HarnessState ]
    snapshot [ ]
        => [ json: JsonValue ]
    restore [ declared: List[StateCell[?]] ; json: JsonValue ]
        => [ state: HarnessState ]
    restore [ declared: List[StateCell[?]] ; json: JsonValue ]
        => [ error: StateDecodeError("Failed to decode state cell '<cellId>': <cause.message>") ]
    project [ parent: HarnessState ; declared: List[StateCell[?]] ]
        => [ state: HarnessState ]
    mergeBack [ parent: HarnessState ; children: List[HarnessState] ; declared: List[StateCell[?]] ]
        => [ state: HarnessState ]
operational principle
    A caller declares cells (each with an owner middleware name, a local
    name, an initial value, a visibility level, a merge function, and a
    ReadWriter codec). The caller builds an initial state from the
    declared cells. get is total: an absent cell reads as its declared
    initial value. set and update are immutable: they return a new
    HarnessState, leaving the original unchanged. snapshot serializes all
    cell values to a JsonValue (DObject) via each cell's ReadWriter;
    restore is lenient (unknown ids ignored, missing cells default to
    initial) but hard-fails on decode errors or non-DObject input.
    project creates a child state from a parent: Private cells see
    initial, Inherited and Shared cells see the parent's current value.
    mergeBack folds Shared cells' merge functions over children's final
    values; Private and Inherited writes are dropped.
```

## Implementation map

| Element | Code |
|---|---|
| state `cells` | `private val cells: Map[StateCell.CellId, (StateCell[?], Any)]` (`adk4s-harness-api/src/main/scala/org/adk4s/harness/HarnessState.scala`) |
| action `get` | `HarnessState.get[A]` — total read with `asInstanceOf` (safe by invariant construction) (`HarnessState.scala`) |
| action `set` | `HarnessState.set[A]` — immutable `Map.updated` (`HarnessState.scala`) |
| action `update` | `HarnessState.update[A]` — `set(cell)(f(get(cell)))` (`HarnessState.scala`) |
| action `snapshot` | `HarnessState.snapshot` — `writeJs` per cell → `JsonValueCodec.fromUjson` → `Document.DObject` (`HarnessState.scala`) |
| action `restore` | `HarnessState.restore` — `JsonValueCodec.toUjson` → `read[Any]` per cell, lenient on unknown ids, hard-fail on decode error (`HarnessState.scala`) |
| action `project` | `HarnessState.project` — visibility-based parent→child copy (`HarnessState.scala`) |
| action `mergeBack` | `HarnessState.mergeBack` — Shared cells fold `merge` over children (`HarnessState.scala`) |
| error `StateDecodeError` | `StateDecodeError(cellId: String, cause: Throwable)` extends `AdkError` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| cell declaration | `StateCell[A]` with `CellId` refined opaque type (`String :| (NonEmpty & Match["[^/]+/[^/]+"])`), `ReadWriter` context bound, `visibility`, `merge` (`adk4s-harness-api/src/main/scala/org/adk4s/harness/StateCell.scala`) |
| visibility | `CellVisibility` enum: `Private`, `Inherited`, `Shared` (`adk4s-harness-api/src/main/scala/org/adk4s/harness/CellVisibility.scala`) |
| Ring 6 mirror | `HarnessStateKernel` — `Map[BigInt, BigInt]` model with get/set/update + lemma functions (`verified/src/main/scala/org/adk4s/verified/HarnessStateKernel.scala`) |
| runtime host | `org.adk4s.harness` |

## Deviations from the pattern

- `HarnessState.get` uses a single `asInstanceOf` to cast the stored `Any` back to `A`. This is the classic typed-heterogeneous-map argument: the only path that writes the entry keyed by `cell.id` is `set(cell)(value: A)` for a cell equal (by id) to this one. The cast is suppressed via `@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))`. The AGENTS.md rule "NEVER use asInstanceOf" is violated here by design — the typed heterogeneous map pattern fundamentally requires it at the storage layer.
- `snapshot` and `restore` use `ujson.Value` as an intermediate type (via upickle's `writeJs`/`read`), bridged to `JsonValue` via `JsonValueCodec`. This is the serialization boundary, analogous to `JsonValueCodec` in `org.adk4s.core.json`. The Scalafix `NoUjsonInHarnessApi` rule excludes `org.adk4s.harness` from the ujson confinement.
- `StateDecodeError` lives in `adk4s-core` (not `adk4s-harness-api`) because it extends `AdkError`, which is sealed in `adk4s-core`. The cell id is stored as `String` (the underlying opaque type value) because `StateCell.CellId` lives in `adk4s-harness-api` which depends on `adk4s-core`.

## Synchronizations

- **HarnessState ↔ CheckpointStore**: when a checkpoint is saved, `HarnessState.snapshot` produces the `JsonValue` that the checkpoint store persists; when a checkpoint is restored, `CheckpointStore` provides the `JsonValue` that `HarnessState.restore` decodes. (Future: `checkpoint-store-fpoly` spec.)
- **HarnessState ↔ AgentTool**: when a sub-agent is spawned, `HarnessState.project` creates the child's initial state; when the sub-agent completes, `HarnessState.mergeBack` folds the child's final state back into the parent. (Future: `harness-agent` spec.)
- **HarnessState ↔ MiddlewareStack**: the middleware stack declares the cells and their visibilities; `HarnessState.initial` constructs the state from the stack's declared cells. (Future: `middleware-stack` spec.)
