# Design: add-iron-refined-types

## Package Structure

### Layers

The project has no HTTP/actor/DB layer — it is a library with a
domain/service/runtime split that maps to the existing module structure.
Layer rules are derived from the detected stack (capability-check.md):
no HTTP, no actor framework, no DB client, no protobuf. The only
infrastructure libraries are cats-effect (F[_]), fs2 (streams), upickle
(JSON), and now Iron (refined types).

| Layer | Package(s) | Depends On | Must NOT Import | Ring 2 Rule |
|-------|-----------|-----------|-----------------|---------------|
| Domain (pure) | `org.adk4s.core.types`, `org.adk4s.core.error` | cats, iron, iron-cats | cats-effect, fs2, llm4s, workflows4s | No effect/stream imports |
| Domain (pure) | `org.adk4s.harness` (types only) | cats, iron, iron-cats, iron-upickle | cats-effect, fs2 | No effect/stream imports |
| Service | `org.adk4s.core.tools`, `org.adk4s.structured.core` | Domain, cats-effect, fs2, llm4s | workflows4s | `allowed: { from: service, to: [domain] }` |
| Runtime | `org.adk4s.orchestration.*` | Domain, Service, cats-effect, fs2, workflows4s | — | May import workflows4s, persistence |
| Test | `org.adk4s.**.test` | Domain, Service, Runtime, munit, hedgehog | — | Test-only |

### New Packages

No new packages. All refined types and constraints land in the existing
`org.adk4s.core.types` package (for `Positive`, `NonNegative`,
`ReservedNodeKey`) and the existing type definition sites (`NodeKey`,
`MiddlewareName`, `CellId`, `CheckpointId` stay in their current packages).

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `NodeKey.refineEither` / `refineUnsafe` | Iron refinement (compile-time + runtime) | No — Iron macros are not Stainless-modelable; the constraint logic is delegated to Iron's macro library (trusted dependency) |
| `Positive` / `NonNegative` constraint aliases | Iron numeric constraints | No — same; Iron's numeric constraints are macro-based |
| `ReservedNodeKey` enum | Reserved value enumeration | No — trivial enum; no algorithm to verify |
| `ConfigError` / `GraphCompilationError` | Error data carriers | No — pure data classes with no logic beyond `message` formatting |
| `ValidatedGraph.from` | Proof-carrying graph construction | No — delegates to `Graph.compile` which uses workflows4s types (WIO) that Stainless cannot model |
| `MemoryPolicy.applyEither` | Smart constructor with `refineEither` | No — delegates to Iron's `refineEither`; the decision is a single `>= 0` check already covered by Iron |

**Ring 6 summary**: No Ring 6 candidates. This change is a type-safety
hardening (refinement boundaries, typed errors, dead-code deletion) with no
new algorithmic logic. All decision logic is delegated to Iron's macro
library (trusted dependency) or is trivial data formatting. The `verified`
module is explicitly excluded from Iron integration (per proposal).

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `ReactAgent.generate` / `stream` | `F[_]: Async` | ReAct loop; internal `refineEither[Positive]` boundary |
| `AgentRunner.run` | `F[_]: Async` | Delegates to ReactAgent; same boundary |
| `HarnessAgent.generate` / `resume` / `stream` | `F[_]: Async` | Same; `effectiveMaxSteps` computation refines |
| `GraphExecutor.execute` / `executeWithError` | `F[_]: Async` | `IO.raiseError(GraphCompilationError(…))` replaces generic `Exception` |
| `StructuredLLM.fromClientWithMiddlewares` | `F[_]: Async` | Factory; `maxParseAttempts` refined at boundary |
| `CheckpointStore[F]` | `F[_]` | API uses refined `CheckpointId` |

## Type Strategy — Invalid-State Prevention

| Invariant | Level | Mechanism | Justification |
|-----------|-------|-----------|---------------|
| NodeKey is non-empty and non-reserved | Best | Iron refined opaque type `String :| (NonEmpty & Not[Reserved])` + compile-negative test | Inline literals are checked at compile time; runtime values via `refineEither`. Unrepresentable as a type. |
| ReservedNodeKey is distinct from NodeKey | Best | Separate enum type; `ReservedNodeKey` is not a subtype of `NodeKey` + compile-negative test | Type system enforces the distinction. |
| MiddlewareName is non-empty | Best | Iron refined opaque type `String :| NonEmpty` + compile-negative test | Same as NodeKey. |
| StateCell.CellId is non-empty and matches `owner/name` format | Best | Iron refined opaque type `String :| (NonEmpty & Match["[^/]+/[^/]+"])` + compile-negative test | Format constraint enforced at type level. |
| CheckpointId is non-empty | Best | Iron refined opaque type `String :| NonEmpty` (promoted from transparent alias) + compile-negative test | Transparent alias accepted any String; opaque refined type closes the hole. |
| Raw String is not assignable to CheckpointId | Best | Opaque type boundary + compile-negative test | The transparent alias let `String` pass as `CheckpointId`; opaque type restores the boundary. |
| maxConcurrency is positive | Good | `refineEither[Positive]` at construction boundary; `parallelEither` returns `Either[ConfigError, …]` | Runtime value (not a literal); smart constructor rejects. Throwing overload preserved for source compat. |
| maxSteps is positive | Good | `refineEither[Positive]` at internal boundary of ReactAgent/AgentRunner/HarnessAgent | Public API stays `Int` (source compat); internal refinement rejects. |
| recallK is non-negative | Good | `refineEither[NonNegative]` returning `Either[ConfigError, MemoryPolicy]`; replaces `require` throw | Runtime value; smart constructor returns typed error. Throwing overload preserved. |
| maxParseAttempts is positive | Good | `refineEither[Positive]` at factory boundary | Runtime value; smart constructor rejects. |
| ValidatedGraph requires successful compile | Best | Refined type `Graph :| GraphValidated`; only constructible via `ValidatedGraph.from(g)` after `g.compile` succeeds + compile-negative test | Proof-carrying type; unconstructible without the proof. |
| GraphCompilationError carries all validation errors | Good | Case class with `errors: List[GraphValidationError]` field; property test verifies list preservation | Data carrier; invariant is structural. |
| ConfigError carries field/value/constraint | Good | Case class with three fields; property test verifies Show round-trip | Data carrier; invariant is structural. |
| No commented-out throw blocks in GraphExecutor | Okay | Static check (grep for `throw new` in comments) | Dead code deletion; verified by absence. |
| Existing AdkError matches handle new variants | Best | Scala 3 exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) | Compiler enforces; non-exhaustive matches fail compilation. |

## Refined Type Strategy

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| `NodeKey` | `String` | `NonEmpty & Not[Reserved]` | API boundary + graph identifier; crosses persistence (graph definitions) |
| `ReservedNodeKey` | enum | (none — enum is inherently constrained) | Replaces `NodeKey.START`/`NodeKey.END` constants |
| `MiddlewareName` | `String` | `NonEmpty` | API boundary; used in `CellId` construction |
| `StateCell.CellId` | `String` | `NonEmpty & Match["[^/]+/[^/]+"]` | Persisted in `HarnessState` snapshots; format is structural |
| `CheckpointStore.CheckpointId` | `String` | `NonEmpty` | Persisted in checkpoint metadata; crosses API boundary |
| `ValidatedGraph` | `Graph` | `GraphValidated` (proof-carrying) | Internal proof type; consumed by `executeGraph` |
| `Positive` | `Int` | `numeric.Positive` | Shared numeric constraint for `maxConcurrency`, `maxSteps`, `maxParseAttempts` |
| `NonNegative` | `Int` | `numeric.NonNegative` | Shared numeric constraint for `recallK` |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `FieldPath` | Already `Vector[String]` opaque type; structural/collection-backed (Tier C — out of scope) |
| `RunPath` | Already `List[RunStep]` opaque type; structural (Tier C) |
| `ToolSchema[A]` | Already opaque type wrapping smithy4s schema; external library type (Tier C) |
| `Schema[A]` | Same as ToolSchema (Tier C) |
| `AddressSegment.Agent` / `AddressSegment.Tool` | Already enum variants; structural (Tier C) |
| `CompletionOptions` | External library type (llm4s); not ours to refine (Tier C) |
| `maxConcurrency` / `maxSteps` / `maxParseAttempts` (public API params) | Stay `Int` for source compatibility; refined at internal boundary only |
| `recallK` (public field) | Becomes `Int :| NonNegative` internally; `applyEither` returns `Either[ConfigError, MemoryPolicy]` |

### Iron Module Dependencies

| Module | Iron Modules Added | Reason |
|--------|-------------------|--------|
| adk4s-core | `iron`, `iron-cats` | Core refined types (`NodeKey`, `Positive`, `NonNegative`, `ReservedNodeKey`) + Cats typeclass instances |
| adk4s-harness-api | `iron`, `iron-cats`, `iron-upickle` | `MiddlewareName`, `CellId` + upickle `ReadWriter` bridge for JSON round-trip |
| adk4s-orchestration | `iron`, `iron-cats`, `iron-upickle` | `CheckpointId` + `ValidatedGraph` + upickle for checkpoint metadata |
| structured-llm | `iron` (transitive via adk4s-core) | `maxParseAttempts` refinement uses `Positive` from adk4s-core |
| verified | (none) | Explicitly excluded — Iron macros conflict with Stainless |

## IDL Model Layout

N/A — this change introduces no API operations or Smithy schema changes.
The refined types are internal type-level constraints; they do not alter
the Smithy IDL or any LLM-facing surface.

## Error Strategy

### Error Enum

The existing `AdkError` sealed hierarchy is extended with two new variants:

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `AdkError` (existing) | + `ConfigError(field, invalidValue, constraint)` | All refinement boundaries (NodeKey, MiddlewareName, CellId, CheckpointId, maxConcurrency, maxSteps, recallK, maxParseAttempts) |
| `AdkError` (existing) | + `GraphCompilationError(errors: List[GraphValidationError])` | `GraphExecutor.execute` / `executeWithError` |

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure (refinement) | `Either[ConfigError, Refined]` | `NodeKey.refineEither(s): Either[ConfigError, NodeKey]` |
| Pure → Effect (agent) | `IO.raiseError(ConfigError(…))` or `IO.fromEither(refineEither(…))` | `ReactAgent.generate` lifts `refineEither[Positive](maxSteps)` into `IO` |
| Pure → Effect (graph) | `IO.raiseError(GraphCompilationError(…))` | `GraphExecutor.execute` replaces `IO.raiseError(new Exception(…))` |
| Smart constructor → Caller | `Either[ConfigError, MemoryPolicy]` | `MemoryPolicy.applyEither(recallK)` |
| Throwing overload (compat) | `refineUnsafe` (throws `IllegalArgumentException`) | `MemoryPolicy.apply(recallK)` — preserved for source compatibility |

**No swallowed errors**: The `ConfigError` path is total — every
refinement failure produces a `ConfigError` with the field name, invalid
value, and constraint. No `case _ =>` default arms in the new code; the
exhaustiveness escalation catches any match that doesn't handle the new
variants.

## Compatibility Story (Ring 4)

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| `HarnessState` snapshots (containing `CellId`) | JSON (upickle) | `iron-upickle` `ReadWriter` bridge — refined types serialize as their underlying `String`; old snapshots decode unchanged | `HarnessStateSpec` — existing snapshot fixtures decode + round-trip property |
| Checkpoint metadata (containing `CheckpointId`) | JSON (upickle) | Same `iron-upickle` bridge | `CheckpointStoreSpec` — round-trip property |
| `NodeKey` in graph definitions | In-memory (not persisted as JSON) | Iron subtype-of-underlying: `NodeKey` is a subtype of `String`, so any code that treats it as `String` still works | `NodeKeySpec` — `.value` access + Cats instances |

**Fixture obligation**: `old fixture bytes/json → decode → expected domain
value` and `new value → encode → decode → same value`. The `iron-upickle`
codec serializes refined types as their underlying base type (Iron's
subtype property), so the wire format is identical before and after
migration. Existing snapshot fixtures must decode without regression.

**Key risk**: `CheckpointId` promotion from transparent alias to opaque
type is a **source-incompatible** change — callers that passed a raw
`String` where a `CheckpointId` is expected will fail compilation. This is
intentional (it restores the type boundary) but requires updating all
call sites. The implementation-order artifact sequences this after the
compile-spike.

## Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| Iron refinement macros | Compile-time constraint checking | No — trusted dependency; macros are not Stainless-modelable |
| `ConfigError.message` | String formatting | No — trivial formatting; no algorithm |
| `GraphCompilationError.message` | String formatting | No — trivial |
| `ValidatedGraph.from` | Delegates to `Graph.compile` | No — `Graph.compile` produces WIO (workflows4s) types that Stainless cannot model |
| `MemoryPolicy.applyEither` | `refineEither[NonNegative]` | No — delegates to Iron; the `>= 0` check is Iron's, not ours |

**Ring 6 verdict**: No candidates. This change contains no new algorithmic
logic — it is purely type-level constraint enforcement (delegated to Iron)
and error-type introduction. The `verified` module is explicitly excluded.

## Verification Map

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| adk4s-core (types, error) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| adk4s-core (tools) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| adk4s-harness-api | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — |
| adk4s-orchestration (memory) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| adk4s-orchestration (graph/executor) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| adk4s-orchestration (interrupt) | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — |
| adk4s-orchestration (agent) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| structured-llm | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| verified | — | — | — | — | — | — | — | — | — | — |

**Ring notes**:
- R0 (compile): exhaustiveness escalation catches unhandled `AdkError` matches; Iron compile-time constraints for inline literals.
- R1 (static analysis): WartRemover `Throw` wart is currently disabled; this change doesn't re-enable it but removes dead throw code. Scalafix `RemoveUnused` catches deleted dead code.
- R2 (architecture): layer rules enforced — `org.adk4s.core.types` must not import cats-effect/fs2.
- R3 (property tests): Hedgehog properties for all refined types (round-trip, rejection, JSON round-trip).
- R4 (compatibility): `iron-upickle` round-trip for `CellId`, `CheckpointId`, `MiddlewareName`; existing snapshot fixtures decode.
- R5 (mutation): stryker4s targets the refinement boundary functions.
- R6 (formal): N/A — no algorithmic candidates.
- R7 (model check): N/A.
- R8 (adversarial): review for silent fallback mappings, `case _` defaults, partial functions.
- R9 (temporal): N/A — no concurrent behavior introduced.

## Technical Decisions

### Decision: Iron vs. hand-rolled opaque type validation

**Context**: The project already uses `opaque type` for `NodeKey`,
`MiddlewareName`, `CellId`, etc. with hand-rolled `apply`/`from`/`unsafeApply`
constructors and `Either`-based validation (for `NodeKey`).

**Options considered**:
1. Keep hand-rolled validation, add `require` checks where missing
2. Migrate to Iron refined types (`A :| C`)
3. Use a different refined library (refined4s, scala-refined)

**Decision**: Migrate to Iron (option 2). Iron's `A :| C` is a subtype of
`A`, so existing code that treats refined types as their underlying type
compiles unchanged. Iron provides compile-time checking for inline literals
and `refineEither` for runtime values. The `iron-cats` module provides
`Eq`/`Show`/`Order` instances, and `iron-upickle` provides `ReadWriter`
instances — both needed by the project. Iron is full Scala 3 (no Scala 2
legacy), actively maintained, and compatible with Scala 3.8.4.

**Consequences**: Adds Iron as a dependency to adk4s-core,
adk4s-harness-api, adk4s-orchestration. The `verified` module is excluded
(Iron macros conflict with Stainless). A compile-spike is required first
to verify Iron + Scala 3.8.4 + JDK 26 compatibility.

### Decision: Conservative strategy for numeric parameters (internal refinement)

**Context**: `maxSteps`, `maxConcurrency`, `maxParseAttempts` are public
API parameters typed as `Int`. Changing them to `Int :| Positive` would
be source-incompatible for all callers.

**Options considered**:
1. Change public API parameters to `Int :| Positive` (breaking)
2. Keep public API as `Int`, refine internally via `refineEither` (conservative)
3. Add overloaded methods accepting `Int :| Positive` alongside `Int`

**Decision**: Option 2 (conservative). Public API parameters stay `Int`;
refinement happens internally via `refineEither[Positive]`, returning
`Either[ConfigError, …]` or lifting into `IO.raiseError(ConfigError(…))`.

**Consequences**: No source-incompatible changes to public APIs. Invalid
values are rejected at the internal boundary with a typed `ConfigError`
instead of being silently accepted. Callers who want compile-time safety
can refine before calling and use the overloaded methods (future work).

### Decision: CheckpointId promotion from transparent alias to opaque type

**Context**: `type CheckpointId = String` is a transparent alias — any
`String` is a `CheckpointId`, including `""`. This is the only type in the
change that is currently a transparent alias rather than an opaque type.

**Options considered**:
1. Keep as transparent alias, add runtime validation at use sites
2. Promote to opaque type `String :| NonEmpty` (source-incompatible but restores type boundary)
3. Promote to opaque type without constraint (just opaque, not refined)

**Decision**: Option 2. Promote to opaque `String :| NonEmpty`. The
source incompatibility is intentional — it restores the type boundary that
the transparent alias violated. All call sites that pass a raw `String`
must be updated to refine first.

**Consequences**: Source-incompatible change for `CheckpointStore` callers.
The implementation-order artifact sequences this after the compile-spike
and after the adk4s-core types (which provide `NonNegative`/`ConfigError`).
The `CheckpointStore` API methods (`get`, `set`, `delete`, `keys`) all
need signature updates.

### Decision: Throwing overload preserved for MemoryPolicy

**Context**: `MemoryPolicy.apply(recallK)` currently throws
`IllegalArgumentException` via `require`. Migrating to `refineEither`
returns `Either[ConfigError, MemoryPolicy]`, which is source-incompatible
for callers that expect `MemoryPolicy` directly.

**Options considered**:
1. Replace `apply` with `applyEither` only (breaking)
2. Keep `apply` (throwing, delegates to `refineUnsafe`) + add `applyEither` (non-breaking)
3. Replace `apply` with `applyEither`, add `unsafeApply` for callers who want to throw

**Decision**: Option 2. Keep the existing `apply` as a throwing overload
(delegates to `refineUnsafe`) and add `applyEither` returning
`Either[ConfigError, MemoryPolicy]`. This preserves source compatibility
while providing the typed error path.

**Consequences**: Two construction paths. The throwing overload is
annotated as delegating to `refineUnsafe` for callers that catch
`IllegalArgumentException`. New code should prefer `applyEither`.

### Decision: ValidatedGraph as proof-carrying refined type

**Context**: `GraphExecutor.execute` currently calls `graph.compile` and
handles the result with `fold` (or raises a generic `Exception`). The
graph is re-validated on each execution.

**Options considered**:
1. Just replace `Exception` with `GraphCompilationError`, no proof type
2. Introduce `ValidatedGraph` as a proof-carrying type that `executeGraph` consumes
3. Make `Graph.compile` return `Either[List[GraphValidationError], ValidatedGraph]` directly

**Decision**: Option 2. Introduce `ValidatedGraph` as `Graph :|
GraphValidated`, constructed via `ValidatedGraph.from(g)` after
`g.compile` succeeds. `executeGraph`/`executeGraphParallel` accept
`ValidatedGraph` and skip re-validation. The public `execute`/`executeWithError`
methods keep their `Graph` parameter and validate internally (delegating to
`ValidatedGraph.from`), so they remain source-compatible.

**Consequences**: Internal execution path avoids redundant re-validation.
The proof is a type — `ValidatedGraph` cannot be constructed without a
successful compile. The public API is unchanged; the proof type is an
internal optimization.
