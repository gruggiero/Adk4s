# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-12
**Consistency check**: CLEAN for the rows this change touches. The 8 opaque-type
rows and the `AdkError` sealed-trait row were cross-checked against source this
session (see evidence below). No stale rows fixed.

## Verification performed (2026-08-12)

The rows this change depends on were cross-checked against source:

| Inventory row | Inventory value | Source evidence (read this session) | Match? |
|---------------|-----------------|--------------------------------------|--------|
| `NodeKey` | opaque `String`, no constraint, `org.adk4s.core.types` | `adk4s-core/.../types/NodeKey.scala:6` `opaque type NodeKey = String` | yes |
| `FieldPath` | opaque `Vector[String]`, no constraint, `org.adk4s.core.types` | `adk4s-core/.../types/FieldPath.scala:5` | yes |
| `RunPath` | opaque `List[RunStep]`, no constraint, `org.adk4s.core.interrupt` | `adk4s-core/.../interrupt/RunPath.scala:7` | yes |
| `MiddlewareName` | opaque `String`, no constraint, `org.adk4s.harness` | `adk4s-harness-api/.../MiddlewareName.scala:11` | yes |
| `StateCell.CellId` | opaque `String`, no constraint, `org.adk4s.harness` | `adk4s-harness-api/.../StateCell.scala:32` | yes |
| `CheckpointStore.CheckpointId` | transparent alias `String`, `org.adk4s.orchestration.interrupt` | `adk4s-orchestration/.../interrupt/CheckpointStore.scala:31` `type CheckpointId = String` | yes |
| `AdkError` variants | 23 variants listed | `adk4s-core/.../error/AdkError.scala:9-93` — all 23 present, no extra | yes |
| `MemoryPolicy` | case class, `recallK: Int`, `org.adk4s.orchestration.memory` | `adk4s-orchestration/.../memory/MemoryPolicy.scala:18-24` | yes |
| `ToolsNodeConfig` | case class, `maxConcurrency: Int = 10`, `org.adk4s.core.tools` | `adk4s-core/.../tools/ToolsNodeConfig.scala:13-21` | yes |

The inventory's `AdkError` row lists `GraphCompiledError`, `GraphEntryMissingError`,
`GraphEndNodesMissingError` — all confirmed present in source. This change adds
**two new variants** (`ConfigError`, `GraphCompilationError`); see below.

## Stale rows fixed

none — all rows this change touches match the source as of 2026-08-12.

## Behavioral Concepts (registry pass)

**registry-check.sh**: `OK (788 implementation-map tokens verified, 0 spec
concept references checked, 4 weak binding(s) to tighten)` — exit 0.

The 4 weak bindings are all in `tools-node.md` (citing `executeToolCalls`/
`executeFromToolCalls`/`toolCalls`/`toolsNode` against `ReactAgent.scala` when
they live elsewhere). These are **pre-existing** and unrelated to this change
(they predate the Iron work). They are noted here for traceability; fixing
them is out of scope for this change.

**Stale implementation-map rows**: none (the 4 weak bindings are weak, not stale).
**Unregistered actions / syncs / state components**: none flagged by this change.

## Concepts relevant to THIS change

Orientation for spec authors: the inventory entries this change will **reuse**
(feeding the specs' "Concepts Used" tables) and a preview of what it
**introduces** (feeding "Concepts Introduced"). The specs' tables remain the
commitments; this is a working excerpt.

### Reused (migrate in place — constraint column changes, provenance kept)

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `NodeKey` | opaque type (String) | `org.adk4s.core.types` | reuse — migrate to `String :| (NonEmpty & Not[Reserved])` |
| `MiddlewareName` | opaque type (String) | `org.adk4s.harness` | reuse — migrate to `String :| NonEmpty` |
| `StateCell.CellId` | opaque type (String) | `org.adk4s.harness` | reuse — migrate to `String :| (NonEmpty & Match[…])` |
| `CheckpointStore.CheckpointId` | transparent alias (String) | `org.adk4s.orchestration.interrupt` | reuse — promote to opaque `String :| NonEmpty` |
| `AdkError` | sealed trait | `org.adk4s.core.error` | reuse — extend with 2 new variants |
| `NodeKeyError` | case class (AdkError) | `org.adk4s.core.error` | reuse — align with new `ConfigError` |
| `MemoryPolicy` | case class | `org.adk4s.orchestration.memory` | reuse — `recallK` field refined; `require` → `refineEither` |
| `ToolsNodeConfig` | case class | `org.adk4s.core.tools` | reuse — `maxConcurrency` refined |
| `Graph` | case class | `org.adk4s.orchestration.graph` | reuse — `compile` feeds `ValidatedGraph` (validation errors are `AdkError`) |
| `GraphExecutor` | object | `org.adk4s.orchestration.execution` | reuse — error path typed; dead commented blocks deleted |

### Introduced (new — will be added to the project inventory at apply Step 12)

| Concept | Kind | Package | Purpose |
|---------|------|---------|---------|
| `ReservedNodeKey` | opaque type / enum | `org.adk4s.core.types` | The `START`/`END` reserved values, distinct from the general `NodeKey` constraint. |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` | Typed error for invalid configuration/identifier refinement failures (replaces `require` throws). |
| `GraphCompilationError` | case class (AdkError) | `org.adk4s.core.error` | Carries `List[AdkError]` (validation errors from `graph.compile`); replaces generic `Exception` in `GraphExecutor`. |
| `ValidatedGraph` | refined type alias `Graph :| GraphValidated` | `org.adk4s.orchestration.graph` (or `.execution`) | Proof-carrying graph that has passed `graph.compile`; consumed by `executeGraph`. |
| `Positive` / `NonNegative` constraint aliases | type aliases | `org.adk4s.core.types` (or a new `org.adk4s.core.refined` package) | Iron numeric constraints reused across `maxSteps`/`recallK`/`maxConcurrency`/`maxParseAttempts`. |

### Inventory rows updated at apply Step 12 (provenance preserved)

The project inventory's "Refined / Opaque Types" table constraint column will
be updated for the 4 migrated types, and the header comment ("No Iron/refined
library is present") will be corrected. Provenance columns are kept:

| Type | Constraint (now) | Constraint (after this change) | Provenance |
|------|------------------|--------------------------------|------------|
| `NodeKey` | (none — plain opaque type) | `NonEmpty & Not[Reserved]` | pre-existing (kept) |
| `MiddlewareName` | (none — plain opaque type) | `NonEmpty` | spec:add-harness-api-phase0/harness-state (kept) |
| `StateCell.CellId` | (none — plain opaque type) | `NonEmpty & Match["[^/]+/[^/]+"]` | spec:add-harness-api-phase0/harness-state (kept) |
| `CheckpointStore.CheckpointId` | (transparent alias — not opaque) | `NonEmpty` (opaque) | spec:add-harness-api-phase0/checkpoint-store-fpoly (kept) |

The `AdkError` sealed-trait row's variants column gains `ConfigError`,
`GraphCompilationError`.
