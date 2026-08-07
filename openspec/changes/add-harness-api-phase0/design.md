# Design: Add Harness API Phase 0

<!-- DESIGN artifact for the `add-harness-api-phase0` change under the
     `verified-scala3` schema. Grounded in the dependency artifacts
     (proposal.md, capability-check.md, inventory-check.md, spec-lint.md) and
     the six specs (harness-state, agent-middleware, middleware-stack,
     harness-agent, checkpoint-store-fpoly, middleware-laws). The full typed
     contract lives in the design doc
     (docs/deepagents4s-phase0-agent-middleware-DESIGN.md §3–§6) — this
     artifact captures the project-specific package/effect/type/error/
     compatibility/verification decisions that the apply phase MUST follow.

     DETECTED stack (openspec/capability-profile.md wins over config.yaml):
     Scala 3.8.4 (verified leaf: 3.7.2), cats-effect 3.7.0, fs2 3.13.0,
     smithy4s 0.18.55 (JsonValue = smithy4s.Document), upickle/ujson 4.4.3
     (llm4s boundary only), munit 1.3.3 + munit-cats-effect 2.2.0,
     Hedgehog 0.13.1, TestControl (transitive), Stainless 0.9.9.3 + Z3,
     sbt-stryker4s 0.21.0, WartRemover 3.6.1, Scalafix (DisableSyntax +
     RemoveUnused + OrganizeImports + NoUjsonIn*). -->

## Package Structure

<!-- Project-specific layer rules derived from
     openspec/capability-profile.md §"Domain Purity Rules". The new modules
     slot into the existing 10-module graph without disturbing the
     structured-llm / adk4s-core / adk4s-orchestration layering. -->

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|-------------|
| Harness API (pure + effectful trait) | `org.adk4s.harness` | `adk4s-core` (`InvokableTool`, `ToolInput`/`ToolOutput`, `JsonValue`/`JsonValueCodec`, `AdkError`), llm4s `Message`/`Completion`/`CompletionOptions`, cats-effect, cats-kernel (`Kleisli`), smithy4s-json | workflows4s, logback, http, adk4s-orchestration, adk4s-memory-api, fs2-io | `allowed: { from: org.adk4s.harness, to: [org.adk4s.core.component, org.adk4s.core.tools, org.adk4s.core.json, org.adk4s.core.error, org.llm4s.llmconnect.model] }` |
| Harness testkit (main-scope laws) | `org.adk4s.harness.testkit` | `adk4s-harness-api`, cats-effect, munit (main), hedgehog-munit (main) | workflows4s, llm4s LLM client, adk4s-orchestration, fs2-io, logback | `allowed: { from: org.adk4s.harness.testkit, to: [org.adk4s.harness, cats.effect, munit, hedgehog] }` |
| Orchestration (loop + checkpoint) | `org.adk4s.orchestration.agent`, `org.adk4s.orchestration.interrupt` | `adk4s-harness-api`, `adk4s-core`, `structured-llm`, llm4s, cats-effect, fs2 | logback, http | existing rule unchanged + new `adk4s-harness-api` allowed |
| Verified mirror (Ring 6 model) | `org.adk4s.verified` | stdlib, Stainless library only | everything project-local (leaf module, Scala 3.7.2) | existing leaf rule unchanged |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.adk4s.harness` | Harness API | `AgentMiddleware[F]`, `HarnessState`, `StateCell[A]`, `MiddlewareStack[F]`, `StackError`, `ModelRequest`/`ModelResponse`, `ToolCallCtx`/`ToolCallOut`, `PromptSection`/`SystemPrompt`, `MiddlewareName`, `StateDecodeError`, `CellVisibility` |
| `org.adk4s.harness.testkit` | Harness testkit | `AgentMiddlewareLaws` (L0–L10), `SemilatticeLaws` (L11), `DeterministicChatModel`, Hedgehog `Generators` — all main-scope, downstream-consumable |
| `org.adk4s.verified` (extended) | Verified mirror | `StackKernel` (project/mergeBack model), `SemilatticeKernel` (commutativity/associativity/idempotence model) — new files in the existing leaf module |

### Module dependency graph (after this change)

```
adk4s-examples → adk4s-core, adk4s-orchestration, structured-llm, structured-llm-test-models
adk4s-examples % Test → adk4s-memory-testkit
adk4s-eval → structured-llm
adk4s-eval % Test → cats-effect-testkit
adk4s-orchestration → adk4s-core, structured-llm, adk4s-memory-api, adk4s-harness-api   ← NEW EDGE
adk4s-orchestration % Test → adk4s-harness-testkit                                        ← NEW EDGE (Test scope)
adk4s-harness-testkit → adk4s-harness-api                                                  ← NEW MODULE
adk4s-harness-testkit % Test → verified % Test                                             ← NEW EDGE (Ring 6 bridge)
adk4s-harness-api → adk4s-core                                                              ← NEW MODULE
adk4s-harness-api % Test → verified % Test                                                  ← NEW EDGE (Ring 6 bridge)
adk4s-optimize → structured-llm, verified % Test
adk4s-memory-testkit → adk4s-memory-api
adk4s-memory-api → adk4s-core
adk4s-core → structured-llm, llm4s/core
structured-llm → llm4s/core, workflows4s-core, smithy4s (core+json)
structured-llm-test-models → structured-llm
verified → (leaf, Scala 3.7.2, Stainless, not aggregated)
```

Two new sbt modules (`adk4s-harness-api`, `adk4s-harness-testkit`) are
aggregated by the root project, matching the `adk4s-memory-api` /
`adk4s-memory-testkit` precedent. The `verified` leaf gains two new source
files (`StackKernel.scala`, `SemilatticeKernel.scala`) but remains a
non-aggregated leaf pinned to Scala 3.7.2; TASTy backward compatibility
lets the 3.8.4 modules read its artifact for the Ring 6 bridge tests.

## Effect Boundaries

<!-- The shipped code mixes pure algorithms (Ring 6 mirror candidates) with
     effectful orchestration (Ring 3). The boundary is drawn so the pure
     kernel — the part with a decision/fold/law at its centre — is extractable
     into a PureScala model. -->

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `HarnessState.get` / `set` coherence | Typed heterogeneous map: `get(c)(set(c)(v)(s)) == v`, `get(c)(set(d)(v)(s)) == get(c)(s)` for `c.id != d.id` | Yes — `HarnessStateKernel` mirror (BigInt-keyed map, get/set laws) |
| `HarnessState.project` (visibility-based parent→child) | Fold over declared cells: Private→initial, Inherited/Shared→parent value | Yes — `StackKernel.project` mirror (BigInt keys, Visibility ADT) |
| `HarnessState.mergeBack` (visibility-based child→parent fold) | Fold over children: Shared→`cell.merge` fold, Private/Inherited→unchanged | Yes — `StackKernel.mergeBack` mirror |
| `HarnessState.mergeBack` order-independence (semilattice precondition) | For semilattice `merge`, any permutation of children yields equal result | Yes — `StackKernel` permutation contract |
| `StateCell.merge` semilattice laws | commutativity, associativity, idempotence of `merge` for `Shared` cells | Yes — `SemilatticeKernel` mirror (parametric `merge: (A, A) => A`) |
| `MiddlewareStack` monoid identity / associativity (pure fold combinators) | `empty` is identity; `++` is associative syntactically | Yes (syntactic) — observational equivalence (L1/L2) is Ring 3 |
| `MiddlewareStack.validated` duplicate detection | Accumulate `DuplicateCellId` / `DuplicateToolName` errors | No — string comparison over `CellId`/tool names; Ring 3 property (`validated-duplicate-detection`) |
| `HarnessState.snapshot` / `restore` (codec round-trip) | Serialize to `JsonValue` (DObject), restore with leniency | No — `ReadWriter`/`JsonValue` not modelled in PureScala; Ring 3 properties (L7, L8) |
| `SystemPrompt.render` | `base ++ sections` in stack order | No — string concatenation; trivial, covered by scenario tests |

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `AgentMiddleware[F[_]]` | `F[_]: Applicative` (hooks), `F[_]: Monad` (stack sequencing) | Four-hook middleware trait; `beforeAgent`/`afterAgent` return `F[HarnessState]`; `wrapModelCall`/`wrapToolCall` are `Kleisli[F, _, _]` |
| `MiddlewareStack[F].beforeAgent` / `afterAgent` | `F[_]: Monad` | Fold middlewares in stack/reverse-stack order |
| `MiddlewareStack[F].wrapModelCall` / `wrapToolCall` | `Kleisli[F, _, _]` | `foldRight` composition: m1 outermost |
| `HarnessAgent[F[_]]` | `F[_]: Async` | ReAct loop: model calls, tool execution, event emission, interrupt handling, `Ref`-based state for parallel tool merges |
| `CheckpointStore[F[_]]` | `F[_]: Sync` (`inMemory` factory) | F-polymorphic checkpoint persistence; `get`/`set`/`delete`/`keys` return `F[…]` |
| `AgentRunner.run` / `resume` | `IO` (Phase 0 fixed) | Interrupt snapshot via `CheckpointStateV2`; `resume` calls `HarnessState.restore` |
| `DeterministicChatModel` | `IO` | Test double: scripted `Completion` sequence, request-trace recording |

## Type Strategy — Invalid-State Prevention

<!-- Every invariant in the specs is placed on the hierarchy. "Risky" requires
     justification; "Bad" is forbidden. -->

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| A `MiddlewareStack` with duplicate cell ids or duplicate tool names cannot exist | Good | `MiddlewareStack` private constructor; only `validated` (returns `Either[NonEmptyList[StackError], …]`) and `empty` produce one; compile-negative test proves direct construction fails | Private constructor + smart constructor is the established pattern; `Either` accumulation gives all errors at once. "Best" (unconstructible type) would require dependent typing on the cell-id set, which Scala 3 cannot express ergonomically. |
| A `StateCell` without a `ReadWriter[A]` cannot exist | Best | `ReadWriter` is a required typeclass parameter at `StateCell.apply` — no codec, no cell | The codec is mandatory at declaration, making R2 (enumerable/snapshotable) unconditionally true. |
| A `HarnessState` entry keyed by `cell.id` always stores a value of the declaring cell's type `A` | Okay | Single `asInstanceOf` in `get`, justified by: the only write path is `set(cell)(value: A)` for a cell equal-by-id to the declaring one, and id uniqueness is validated at stack construction; Ring 6 mirror (`HarnessStateKernel`) proves get/set coherence | The cast is local and auditable; the invariant is mechanically verified in the mirror. A GADT-encoded `StateCell[A]` with a type-indexed map would be "Best" but requires `HMap`/shapeless machinery that is not in the detected stack and would complicate the API for no functional gain. |
| A non-exhaustive match over `StackError` / `HarnessResult` / `CellVisibility` cannot compile | Best | Exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) makes inexhaustive matches over sealed types a Ring 0 compile error; compile-negative tests prove it | The build's `-Wconf` escalation turns the sealed-ADT exhaustiveness guarantee into a compile-time fact. |
| `ReactAgent.create` cannot accept a `MiddlewareStack` | Best | Sugar signature has no `MiddlewareStack` parameter; compile-negative test proves a stack argument is a type mismatch | The sugar exposes only the empty-stack path; the stack-bearing path is the new `HarnessAgent` API. |
| `HarnessAgent` cannot be constructed without `Async[F]` | Best | `F[_]: Async` context bound on the class; compile-negative test proves `HarnessAgent[Option]` fails (no `Async[Option]`) | The type system enforces the minimal effect constraint the loop needs (concurrency, `Ref`-based state). |
| `afterAgent` cannot be invoked on an interrupted `HarnessResult` | Best | `afterAgent` is a stack hook called by the loop, not a public member of `HarnessResult`; compile-negative test proves `result.afterAgent(state)` does not compile | Prevents the double-fire-on-resume bug structurally. |
| A `CheckpointStore` referenced without a type parameter cannot compile | Best | Trait is now `CheckpointStore[F[_]]`; the unparameterized form no longer exists; compile-negative test | F-polymorphism is enforced at the type level. |
| `SerializableCheckpointMessage` / `CheckpointState` (v1) cannot appear in new code outside the v1-read compat decoder | Okay | Code-review gate + grep for usage outside the v1 compat module | The old types must exist for the v1 decode path; a Scalafix rule could make this "Good" but is not in the detected stack. |
| A corrupted `harnessState` cell value is a hard error, not silent data loss | Good | `HarnessState.restore` returns `Left(StateDecodeError(cellId, cause))`; `AgentRunner.resume` returns `RunResult.Failed` wrapping it | Smart-constructor-style `Either` return; the error names the cell and the codec failure. |
| A `Private` cell's parent value is unobservable by the child | Best | `HarnessState.project` sets Private cells to `initial` by construction; the child never sees the parent's value | Structural — the projection function cannot leak the parent's Private value. |
| A child's writes to a `Private` or `Inherited` cell are unobservable by the parent | Best | `HarnessState.mergeBack` skips Private/Inherited cells in the fold by construction | Structural — the merge function discards child writes to non-Shared cells. |
| A `Shared` cell used under parallel delegation has a semilattice `merge` | Risky | Phase 0 enforces this as a tested property (`SemilatticeLaws`) over stack-authored middlewares, not by construction; the capability-based alternative (Scala 3 capture checking) is deferred (design §9) | "Risky" because a non-semilattice merge compiles and runs but produces order-sensitive results under parallel tool calls. Justified: the semilattice discipline is the middleware author's obligation, documented in the spec, and checked by `SemilatticeLaws` at test time. The loop preserves order-independence *when the discipline holds* (Ring 6 mirror + Ring 3 property). A "Good" enforcement would require a `Semilattice[A]` typeclass bound on `Shared` cells, but that would forbid last-write-wins (the correct default for sequential-only shared cells). |
| A middleware cannot write to a cell it does not declare | Risky | Phase 0 enforces this as a tested property (L5 cell frame rule) over stack-authored middlewares, not by construction | "Risky" because `HarnessState.set` accepts any cell. Justified: the frame rule is a property of *middleware-authored* code, not of the state API; L5 detects cross-cell writes. The capability-based alternative is deferred (design §9). |

## Refined Type Strategy

<!-- The detected stack has NO refined-type library (no Iron, no `refined`).
     `JsonValue` is `smithy4s.Document` (an alias, not a constrained opaque
     type). The strategy below uses opaque types where the value domain
     warrants a smart constructor, and plain types where it does not. -->

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| `MiddlewareName` | `String` (opaque) | None at construction (any string accepted) | API boundary value — a middleware's name is used in `CellId` construction (`owner/name`) and `StackError` reporting. Opaque type prevents accidental `String`/`MiddlewareName` confusion without imposing a structural constraint. |
| `StateCell.CellId` | `String` (opaque) | Constructed as `s"${owner.value}/$name"` | Persisted identifier (survives checkpoints as a JSON key). Opaque type with a single constructor ensures the `owner/name` format is invariant. |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `CheckpointId` | Transparent alias (`type CheckpointId = String`) — source compatibility with existing `String` call sites is required; an opaque type would break 55+ examples. The spec mandates transparency. |
| `PromptSection.name` / `PromptSection.body` | Human-readable strings; no structural constraint. |
| `SystemPrompt.base` | Human-readable; no structural constraint. |
| `CheckpointMessage.role` | Enumerated string (`"user"`/`"assistant"`/`"system"`/`"tool"`); a sealed enum would be "Good" but the v1-read compat path produces `role` from raw JSON strings, and the round-trip property covers correctness. |
| `CheckpointToolCall.arguments` | JSON text (a `String`, not `JsonValue`) — keeps orchestration free of ujson per `migrate-json-codec`; the content is provider-specific and not structurally constrained. |
| `HarnessAgent.Config` fields | Configuration case class; plain typed values match the existing `ReactAgent.create` signature for source compatibility. |
| `version: Int` (in `CheckpointStateV2`) | Constant `2`; a literal type would be "Best" but adds no practical safety over a constant field + v1-read compat decoder. |

## IDL Model Layout

<!-- This change does NOT add or modify any Smithy IDL. `JsonValue`
     (= `smithy4s.Document`) is used as the serialization currency for
     `HarnessState.snapshot` and `CheckpointStateV2.harnessState`, but no
     new Smithy shapes are defined. The `CheckpointStateV2` /
     `CheckpointMessage` / `CheckpointToolCall` case classes `derive
     ReadWriter` (upickle), not Smithy codecs. The structured-llm Smithy
     codegen is NOT touched by this change. -->

No IDL changes. The change is library-level (new modules + orchestration
refactor), not wire-schema-level.

## Error Strategy

<!-- No swallowed errors. No default branches returning valid domain values.
     Errors are modeled as sealed hierarchies and propagated via `Either`
     (pure) or `F` (effectful). -->

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `StackError` (sealed enum) | `DuplicateCellId(id: StateCell.CellId, owners: List[MiddlewareName])`, `DuplicateToolName(name: String, owners: List[MiddlewareName])` | `MiddlewareStack.validated` — accumulates into `NonEmptyList[StackError]` |
| `StateDecodeError` (case class) | `StateDecodeError(cellId: StateCell.CellId, cause: Throwable)` | `HarnessState.restore` — corrupted cell value in a checkpoint |
| `HarnessResult` (sealed trait) | `Completed(finalAssistant: AssistantMessage, messages: List[Message], state: HarnessState)`, `Interrupted(signal: InterruptSignal, messages: List[Message], state: HarnessState)`, `Failed(error: AdkError, messages: List[Message], state: HarnessState)` | `HarnessAgent.generate` — loop outcome |
| `RunResult` (existing, extended) | `Completed` / `Interrupted` / `Failed` — unchanged variants; `Failed` now wraps `StateDecodeError` via `AdkError` | `AgentRunner.resume` — checkpoint resume outcome |

`StackError` and `HarnessResult` are sealed enums/traits — exhaustiveness
escalation (`-Wconf:name=PatternMatchExhaustivity:e`) makes non-exhaustive
matches a Ring 0 compile error. `StateDecodeError` is a single case class
(no enum needed — one failure mode). `AdkError` is the existing project
error hierarchy; `StateDecodeError` is wrapped into an `AdkError` variant
at the `AgentRunner.resume` boundary so the existing `RunResult.Failed`
shape is preserved.

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure (`MiddlewareStack.validated`) | `Either[NonEmptyList[StackError], MiddlewareStack[F]]` | `validated(ms)` accumulates all duplicate-cell-id and duplicate-tool-name errors |
| Pure → Pure (`HarnessState.restore`) | `Either[StateDecodeError, HarnessState]` | `restore(cells, json)` — corrupted cell → `Left`, unknown fields → `Right` (ignored) |
| Pure → Effect (`HarnessAgent.generate`) | lift `Either` into `F` via `.fromEither` / pattern match | `restore` failure in `resume` → `RunResult.Failed` |
| Effect → Effect (`HarnessAgent` loop) | `F` with `Async` error channel | `AgentInterruptedException` caught → `HarnessResult.Interrupted`; model/tool failure → `HarnessResult.Failed` |
| Loop → Caller (`HarnessAgent.generate`) | `F[HarnessResult]` — explicit outcome, not exceptions | `generate` returns `F[HarnessResult]`; the caller pattern-matches exhaustively |
| Checkpoint → Resume (`AgentRunner.resume`) | `RunResult` (existing) | unknown checkpoint id → `RunResult.Failed(CheckpointNotFoundError(id))`; corrupted `harnessState` → `RunResult.Failed(StateDecodeError(...))` |

## Compatibility Story (Ring 4)

<!-- REQUIRED: this change touches persisted checkpoint data
     (`CheckpointState` → `CheckpointStateV2`) and the `ReactAgent.create`
     API surface (55+ examples, `AgentTool`, graphStore). -->

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| `CheckpointState` v1 → v2 | upickle JSON | `CheckpointStateV2` derives `ReadWriter` with optional `version` field (absent ⇒ v1); v1 `role`+`content` pairs decode to `CheckpointMessage` with `toolCalls = Nil`, `toolCallId = None`; `harnessState` defaults to `DObject(Map.empty)` | Hedgehog property: `V1 read compatibility` — v1 payload decodes to `CheckpointStateV2`; munit scenario: v1 payload with tool messages |
| `CheckpointStateV2` v2 round-trip | upickle JSON | `derives ReadWriter` — `read(write(cpv2)) == cpv2` across all fields | Hedgehog property: `V2 round-trip` |
| Full-fidelity messages (`toolCalls` / `toolCallId`) | upickle JSON | `CheckpointMessage` preserves `AssistantMessage.toolCalls` and `ToolMessage.toolCallId` — the v1 defect (flattened away by `SerializableCheckpointMessage`) is fixed | Hedgehog property: `Full-fidelity preservation` |
| `harnessState` snapshot round-trip | `JsonValue` (DObject) | `HarnessState.snapshot` → `CheckpointStateV2.harnessState` → `HarnessState.restore` yields `Right(s)` up to absent-equals-initial | Hedgehog property: `harnessState snapshot round-trip` |
| `ReactAgent.create` source compatibility | Scala API | `ReactAgent.create(...)` re-expressed as sugar for `HarnessAgent[IO]` with `MiddlewareStack.empty`; signature unchanged | `sbt adk4s-examples/compile` — 55+ examples compile unchanged; compile-negative: `ReactAgent.create` with a `MiddlewareStack` argument does not compile |
| `CheckpointStore` IO call sites | Scala API | `CheckpointStore` (no type param) → `CheckpointStore[F[_]]`; existing call sites compile at `F = IO` with `[IO]` type argument | `sbt adk4s-orchestration/compile`; compile-negative: `CheckpointStore` without type parameter does not compile |
| `InterruptSignal.Stateful.state` | `JsonValue` (already migrated) | No change — `migrate-json-codec` already switched `state` from `ujson.Value` to `JsonValue`; `CheckpointStateV2.interruptSignalJson` carries the serialized signal | existing tests |

**Fixture obligation**: `old v1 checkpoint JSON → decode[CheckpointStateV2] →
expected v2 value (version=2, harnessState=DObject(Map.empty), toolCalls=Nil,
toolCallId=None)` and `new v2 value → write → read → same value`. The v1
fixture is generated from the old `CheckpointState` schema via
`upickle.default.write(CheckpointState(messages, interruptSignalJson,
agentName))` using the old `ReadWriter`.

## Pure Code (Ring 6 candidates)

<!-- Ring 6 is decided by ALGORITHMIC purity, not by whether the shipped code
     is itself verifiable. The shipped `HarnessState`/`StateCell.merge` use
     `ReadWriter`/`JsonValue`/`Map` and the Stainless frontend is pinned to
     Scala 3.7.2 while the build is 3.8.4 — both are reasons the mirror
     module exists, not reasons to skip. The VERIFIED-MIRROR pattern
     (templates/verified-mirror.md) applies: a PureScala model of the
     algorithm reduced to observable effect, plus a mandatory bridge
     property test binding shipped code to the model. -->

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `HarnessState.get`/`set` coherence | Typed-map lens laws | Yes — `HarnessStateKernel` mirror (BigInt-keyed `Map[BigInt, BigInt]`, get/set with `ensuring`) |
| `HarnessState.project` | Visibility-based parent→child fold | Yes — `StackKernel.project` mirror (`Visibility` ADT: `PrivateV`/`InheritedV`/`SharedV(merge)`, `Cell(id, visibility, initial)`, `ensuring` per-cell postcondition) |
| `HarnessState.mergeBack` | Visibility-based child→parent fold | Yes — `StackKernel.mergeBack` mirror (`ensuring`: Shared folds via `merge`, Private/Inherited unchanged) |
| `HarnessState.mergeBack` order-independence (semilattice) | Permutation equality for semilattice merges | Yes — `StackKernel` permutation contract (`ensuring`: for semilattice `merge`, any permutation yields equal result) |
| `StateCell.merge` semilattice laws | commutativity, associativity, idempotence | Yes — `SemilatticeKernel` mirror (`commutative`/`associative`/`idempotent`/`isSemilattice` with `ensuring`) |
| `MiddlewareStack` monoid identity / associativity (pure combinators) | `empty` identity, `++` associative | Yes (syntactic, trivial) — observational equivalence (L1/L2) is Ring 3 |
| `MiddlewareStack.validated` duplicate detection | Accumulate `StackError`s | No — string comparison over `CellId`/tool names; Ring 3 property `validated-duplicate-detection` |
| `HarnessState.snapshot` / `restore` | `JsonValue` codec round-trip | No — `ReadWriter`/`JsonValue` not in PureScala; Ring 3 properties L7 (codec round-trip), L8 (restore leniency) |
| `HarnessAgent` loop | ReAct iteration with effects | No — effectful (model calls, tool execution, event emission, interrupt handling); L0 observational equivalence is Ring 3 |
| `CheckpointStore[F]` | Effectful persistence interface | No — effectful; Ring 3 property `F-polymorphism` |
| `CheckpointStateV2` round-trip | upickle `ReadWriter` round-trip | No — upickle not in PureScala; Ring 3 properties `V1 read compatibility`, `V2 round-trip`, `Full-fidelity preservation` |
| `SystemPrompt.render` | `base ++ sections` string concat | No — trivial string concatenation; scenario tests |

### Ring 6 mirror modules

Two new PureScala mirrors land in the existing `verified` leaf module
(Scala 3.7.2, `stainlessEnabled := false` by default, verified by
`sbt -J-Xmx6g ring6`):

1. **`StackKernel.scala`** — models `HarnessState.project` /
   `mergeBack` as a fold over `Map[BigInt, BigInt]` keyed by `CellId`
   (modeled as `BigInt`), with a `Visibility` ADT
   (`PrivateV`/`InheritedV`/`SharedV(merge: (BigInt, BigInt) => BigInt)`).
   Three `ensuring` contracts: `project` (Private→initial,
   Inherited/Shared→parent), `mergeBack` (Shared folds, Private/Inherited
   unchanged), `mergeBack` order-independence (semilattice precondition).
   Bridge: `StackKernelBridgeSpec` (Hedgehog, in `adk4s-harness-testkit`
   test sources) runs real `HarnessState.project`/`mergeBack` and the model
   on the SAME generated cell sets and state values.

2. **`SemilatticeKernel.scala`** — models `StateCell.merge` semilattice
   laws as parametric functions over `merge: (A, A) => A` with `ensuring`
   clauses. Bridge: `SemilatticeModelBridgeSpec` (Hedgehog, in
   `adk4s-harness-api` test sources) runs real `StateCell.merge` and the
   model on the SAME generated merge functions and values.

Build wiring: `adk4s-harness-api dependsOn(verified % Test)` and
`adk4s-harness-testkit dependsOn(verified % Test)`. TASTy is backward
compatible, so the 3.8.4 modules read the 3.7.2 artifact.
`stainlessEnabled := false` by default, so bridge tests pay only a plain
compile of the model; verification is the separate `sbt -J-Xmx6g ring6`
step.

### Scope — what is delegated to Ring 3

| Law | Why not proven in Ring 6 | Covered by (Ring 3 property) |
|-----|--------------------------|------------------------------|
| Monoid identity (L1) | requires `F[_]`-effect observation and `ChatModel` double | `monoid-identity-any-position` |
| Monoid associativity (L2) | requires `F[_]`-effect observation | `monoid-associativity` |
| Hook distribution (L3) | requires `Kleisli` trace observation | `hook-distribution-wrapModelCall` |
| Disjoint commutativity (L6) | requires `F[_]`-effect observation and multi-hook interaction | `disjoint-commutativity` |
| Duplicate detection | requires `AgentMiddleware` construction and `CellId` string comparison | `validated-duplicate-detection` |
| Codec round-trip (L7) | `ReadWriter`/`JsonValue` not in PureScala | `L7-codec-round-trip` |
| Restore leniency (L8) | `JsonValue` not in PureScala | `L8-restore-leniency` |
| Privacy (L9) | real `HarnessState` uses `JsonValue`/`Map` | `L9-privacy` (Ring 3) + `StackKernel` project/mergeBack (Ring 6 model) |
| Merge-back neutrality (L10) | real `HarnessState` uses `JsonValue`/`Map` | `L10-merge-back-neutrality` |
| `mergeBack` order-independence over real `HarnessState` | `HarnessState` uses `JsonValue`/`Map` | `L11-mergeBack-order-independence` (Ring 3) + `StackKernel` permutation contract (Ring 6 model) |
| `DeterministicChatModel` determinism | depends on `ChatModel`/`Completion` types not in PureScala | `L0-conservative-refactor` (determinism is a precondition, checked by repeated-run equality) |
| L0–L10 observational equivalence | involves `HarnessAgent`/`ReactAgentImpl`/`Completion` — not PureScala | `L0-conservative-refactor` through `L10-merge-back-neutrality` |

If a target VC diverges in z3, it moves into this table with its Ring 3
property named — it is never silently dropped.

## Verification Map

<!-- For each module, state which rings apply. R8 (adversarial review) applies
     to every code-changing module. R9 (telemetry) is unavailable (no
     otel4s/Daut). R7 (model checking) is unavailable (no TLA+/Apalache). -->

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `adk4s-harness-api` (main: `AgentMiddleware`, `HarnessState`, `StateCell`, `MiddlewareStack`, `StackError`, `ModelRequest`/`ModelResponse`, `PromptSection`/`SystemPrompt`, `StateDecodeError`) | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — |
| `adk4s-harness-api` (test: `StackKernelBridgeSpec`-adjacent, `SemilatticeModelBridgeSpec`, `HarnessStateSpec`, `AgentMiddlewareSpec`, `MiddlewareStackSpec`, `MiddlewareStackLawsSpec`, `HarnessStateBoundarySpec`) | ✅ | ✅ | — | ✅ | — | — | ✅ | — | — | — |
| `adk4s-harness-testkit` (main: `AgentMiddlewareLaws`, `SemilatticeLaws`, `DeterministicChatModel`, `Generators`) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `adk4s-harness-testkit` (test: `AgentMiddlewareLawsSpec`, `SemilatticeLawsSpec`, `DeterministicChatModelSpec`, `StackKernelBridgeSpec`) | ✅ | ✅ | — | ✅ | — | — | ✅ | — | — | — |
| `adk4s-orchestration` (main: `HarnessAgent`, `HarnessResult`, `ReactAgent` sugar, `AgentRunner` refactor, `CheckpointStore[F]`, `CheckpointStateV2`, `CheckpointMessage`, `CheckpointToolCall`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — |
| `adk4s-orchestration` (test: `HarnessAgentSpec`, `HarnessAgentTypeContract`, `CheckpointStoreSpec`, `CheckpointStateV2Spec`, `AgentRunnerResumeSpec`) | ✅ | ✅ | — | ✅ | ✅ | — | — | — | — | — |
| `verified` (new: `StackKernel.scala`, `SemilatticeKernel.scala`) | ✅ | — | — | — | — | — | ✅ | — | — | — |
| `adk4s-examples` (no source change — compile-only gate for `ReactAgent.create` compatibility) | ✅ | — | — | — | — | — | — | — | — | — |
| `build.sbt` (new modules, new edges, stryker4s retarget) | ✅ | — | — | — | — | — | — | — | — | — |

Notes:
- R2 (architecture) is advisory only (no custom scalafix arch rules); the
  layer rules in §Package Structure are enforced by code review + import
  audit.
- R4 (compatibility) applies to `adk4s-orchestration` because it touches
  persisted checkpoint data and the `ReactAgent.create` API surface.
- R5 (mutation) targets `HarnessAgent.scala`, `HarnessResult.scala`
  (orchestration) and `**/harness/testkit/*.scala` (testkit) via
  `stryker4s.conf` retarget; threshold 85%.
- R6 (formal) applies to the `verified` leaf (the mirrors) and the bridge
  tests in `adk4s-harness-api` / `adk4s-harness-testkit` test sources.
- R9 (telemetry) is unavailable (no otel4s/Daut) — skip with stated impact.

## Technical Decisions

### Decision: Two new modules (`adk4s-harness-api` + `adk4s-harness-testkit`) rather than extending `adk4s-core`

**Context**: The middleware trait and state substrate need a home. Options
were (a) extend `adk4s-core`, (b) new dedicated API module, (c) put
everything in `adk4s-orchestration`.

**Options considered**:
1. Extend `adk4s-core` — keeps the dependency graph flat but pollutes
   `adk4s-core` with harness concepts (the same reason `adk4s-memory-api`
   is separate).
2. New `adk4s-harness-api` module + `adk4s-harness-testkit` sibling —
   follows the `adk4s-memory-api` / `adk4s-memory-testkit` precedent;
   gives deepagents4s a minimal compile-time surface.
3. Put in `adk4s-orchestration` — couples the API to the loop
   implementation; external consumers (deepagents4s Phase 1+) would depend
   on the whole orchestration module.

**Decision**: Option 2 — two new modules. `adk4s-harness-api` depends on
`adk4s-core` (for `InvokableTool`, `ToolInput`/`ToolOutput`, `JsonValue`,
`AdkError`); `adk4s-harness-testkit` depends on `adk4s-harness-api` with
`munit`/`hedgehog-munit` in **main** scope (not Test), so downstream
middleware authors consume `AgentMiddlewareLaws` as a regular dependency.

**Consequences**: 12 modules total (was 10). The root aggregator gains two
entries. `adk4s-orchestration` gains a `.dependsOn(adk4s-harness-api)` edge.
The testkit's main-scope munit/hedgehog deps match the
`adk4s-memory-testkit` precedent — no `sbt-testkit` export gymnastics.

### Decision: `promptSections(state: HarnessState)` — state-aware, per-request fold

**Context**: An earlier design had `def promptSections: List[PromptSection]`
— a static, no-argument contribution. This breaks any middleware whose
prompt contribution is state (recall, memory, notebook).

**Options considered**:
1. Static `promptSections: List[PromptSection]` — simpler, but forces
   state-aware middlewares to inject via `wrapModelCall`, bypassing the
   ordered, named, section-by-section prompt assembly.
2. `promptSections(state: HarnessState): List[PromptSection]` — state-aware;
   the loop folds it per request from current `HarnessState`.
3. `wrapModelCall`-only injection — no `promptSections` at all; every
   middleware rewrites `req.systemPrompt` directly.

**Decision**: Option 2 — state-aware `promptSections(state)`. The loop folds
`stack.allSections(state)` per request, so recall-loaded content (Skills /
Memory / GraphStore / DACE notebook) flows through the same auditable
assembly as static sections.

**Consequences**: `MiddlewareStack.allSections` becomes
`allSections(state)`. The loop's request-build step (design §6.1 step 3)
folds sections per request. L4/L6 laws' "contributes no overlapping
sections" wording is state-aware. Static-section middlewares
(Filesystem, SubAgent) ignore the parameter (`_ =>`). Blast radius is
small (one signature, one fold site, the composition rule, the loop step,
the L4/L6 laws) and it is Phase 0 (the cheapest moment to fix it).

### Decision: VERIFIED-MIRROR pattern for `HarnessState.project`/`mergeBack` and `StateCell.merge` semilattice laws

**Context**: The shipped `HarnessState` and `StateCell.merge` use
`ReadWriter`/`JsonValue`/`Map` and the Stainless frontend is pinned to
Scala 3.7.2 while the build is 3.8.4. Direct verification is impossible.
But the *algorithm* under `project`/`mergeBack` (a fold over children by
visibility) and the semilattice laws (commutativity, associativity,
idempotence) are pure and survive reduction to observable effect.

**Options considered**:
1. Skip Ring 6 — claim "effectful, not PureScala" and delegate everything
   to Ring 3 properties.
2. VERIFIED-MIRROR — extract the pure algorithm into a PureScala model
   (`StackKernel`, `SemilatticeKernel`) in the `verified` leaf, prove the
   contracts with Stainless, and bind the shipped code to the model via a
   bridge property test.
3. Wait for a Scala 3.7.2-compatible `JsonValue` model — defer Ring 6
   until the codec currency is PureScala-expressible.

**Decision**: Option 2 — VERIFIED-MIRROR. Two new mirrors in `verified/`:
`StackKernel` (project/mergeBack with `Visibility` ADT and `ensuring`
contracts) and `SemilatticeKernel` (commutativity/associativity/idempotence
with `ensuring`). Bridge tests (`StackKernelBridgeSpec`,
`SemilatticeModelBridgeSpec`) run real and model on the same generated
inputs.

**Consequences**: `adk4s-harness-api` and `adk4s-harness-testkit` gain
`dependsOn(verified % Test)` for the bridge tests. TASTy backward
compatibility (3.8.4 reads 3.7.2) makes this work.
`stainlessEnabled := false` by default — bridge tests pay only a plain
compile; verification is the separate `sbt -J-Xmx6g ring6` step. The
shipped code is bound to the model on exactly the proven invariants; laws
that require `F[_]`-effect observation (L1, L2, L3, L6) are delegated to
Ring 3 and named in the scope table rather than dropped.

### Decision: `CheckpointId` as transparent alias, not opaque type

**Context**: `CheckpointStore[F[_]]` method signatures read
`get(checkpointId: CheckpointId)`. Options were (a) opaque type with smart
constructor, (b) transparent alias `type CheckpointId = String`.

**Options considered**:
1. Opaque type — stronger type safety, prevents `String`/`CheckpointId`
   confusion.
2. Transparent alias — `type CheckpointId = String` — any `String` passes
   without conversion.

**Decision**: Option 2 — transparent alias. Source compatibility with
existing `String` call sites (55+ examples, `AgentRunner`,
`InterruptibleNode`) is required by the spec; an opaque type would break
them. The alias improves readability without imposing a conversion burden.

**Consequences**: `CheckpointId` is documentation, not a safety barrier.
The `CheckpointStore` spec mandates transparency; a future change could
opaque-ify it if call sites are updated.

### Decision: Phase 0 ships `F = IO` only, API is `F[_]: Async`-generic

**Context**: The `HarnessAgent` API is `F[_]: Async`-generic, consistent
with `ChatModel[F]`, `InvokableTool[F]`, `AgentMemory[F]`. But `ToolsNode`
and `AgentRunner` are IO-fixed in Phase 0.

**Options considered**:
1. `HarnessAgent` IO-fixed — simplest, but forecloses the later core-wide
   F-generalization.
2. `HarnessAgent[F[_]: Async]` generic, ship `IO` only — API is
   future-proof; Phase 0 runtime is `IO` via `type IOHarnessAgent =
   HarnessAgent[IO]`.
3. Full F-generalization of `ToolsNode`/`AgentRunner` now — out of scope
   for Phase 0.

**Decision**: Option 2 — `F[_]: Async`-generic API, `IO`-only runtime.
The `Async[F]` bound is the minimal constraint the loop needs (concurrency,
`Ref`-based state). Non-IO instantiation is API-legal but unsupported by
the shipped runtime (documented, not forbidden by the type system).

**Consequences**: `HarnessAgent[Option]` does not compile (no
`Async[Option]`) — compile-negative test. Phase 1+ code never binds to the
IO-fixed version. The testkit ships only `IO`-bound doubles in Phase 0.

### Decision: `afterAgent` skipped on interrupt, runs once on resume

**Context**: On `AgentInterruptedException`, the loop snapshots state
without running `afterAgent`. Options were (a) run `afterAgent` on
interrupt, (b) skip it on interrupt and run it on resume, (c) run it twice
(once on interrupt, once on resume).

**Options considered**:
1. Run `afterAgent` on interrupt — simplest, but double-counts (e.g. a
   GraphStore `remember` in `afterAgent` would fire on partial output).
2. Skip on interrupt, run once on resume — `afterAgent` is teardown for a
   normally-completed run; an interrupted run is not complete.
3. Run twice — clearly wrong (double-fire).

**Decision**: Option 2 — skip on interrupt, run once when the resumed run
terminates normally. The interrupted outcome carries the snapshot, the
interrupt signal, and the partial messages.

**Consequences**: `HarnessResult.Interrupted` does not expose
`afterAgent` (compile-negative test). `AgentRunner.resume` calls
`HarnessState.restore` then re-enters the loop; the resumed run's
`afterAgent` fires on its final state. The L0 property
(`afterAgent-skipped-on-interrupt`) and the resumed-run scenario test
verify this.
