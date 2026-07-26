# Design: Add the Optimizable Predictor Surface (DSPy Port — Phase 0)

## Package Structure

<!-- The new `adk4s-optimize` module is a leaf above `structured-llm`. It
     mirrors the precedent set by `adk4s-memory-api` (a leaf above
     `adk4s-core`) and `adk4s-memory-testkit` (a leaf above
     `adk4s-memory-api` that publishes a main-scope laws suite). The
     forbidden-import list is derived from the detected stack in
     openspec/capability-profile.md: the optimize module must stay decoupled
     from the orchestration/tool-execution/workflow-engine layers so that
     Phase 2+ optimizers can be written against a stable, narrow surface. -->

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|---------------|
| Domain (pure) | `org.adk4s.optimize` (data types: `Demo`, `PredictorState`, `PredictorPath`, `OptimizeError`, `Optimizable`, `HasPredictorState`) | cats (kernel only, via transitive), ujson | cats-effect, fs2, workflows4s, llm4s LLM client, adk4s-orchestration, adk4s-core.tools, adk4s-core.component, adk4s-core.interrupt | No effect/streaming/actor imports; pure data + typeclass + `Mirror` derivation only |
| Adapter (effectful) | `org.adk4s.optimize` (`Predict0[F, I, O]` placeholder) | Domain + `org.adk4s.structured.core` (StructuredLLM, Prompt, Schema, PromptTemplate) + cats-effect + fs2-core | workflows4s, llm4s LLM client, adk4s-orchestration, adk4s-core.tools, adk4s-core.component, adk4s-core.interrupt | Effect-polymorphic `F[_]`; the only structured-llm surface touched is `StructuredLLM.completeTemplate` |
| Testkit (main-scope laws) | `org.adk4s.optimize.testkit` (`OptimizerLaws`) | Domain + cats-effect + munit (main scope) + hedgehog-munit | All of the above forbidden + adk4s-orchestration, adk4s-core | Pattern follows `adk4s-memory-testkit` (munit in MAIN scope, not Test) |
| Test fixtures (toy optimizers + toy programs) | `org.adk4s.optimize` (test sources: `UppercaseInstructions`, `StaticDemoInjector`, `ToyProgram`, `TwoPredictors`, `Outer`, `Inner`, `Pipeline`) | Domain + Testkit + cats-effect | (test-only, no Ring 2 enforcement beyond the module's own deps) | Compile-only; never published |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.adk4s.optimize` | Domain + Adapter | Pure data types + `Optimizable[P]` typeclass with `Mirror`-based `derived` + `Predict0[F, I, O]` placeholder + `OptimizeError` ADT |
| `org.adk4s.optimize.testkit` | Testkit | `OptimizerLaws` — main-scope munit laws suite (purity, frozen-preserved, path-set-preserved) consumable by future optimizer modules |

### Module wiring

The new sbt module `adk4s-optimize` is added to `build.sbt` and `project/Dependencies.scala`, mirroring the `adk4s-memory-api` + `adk4s-memory-testkit` precedent but collapsed into a single module (Phase 0 ships only the surface + the laws testkit; a split into `adk4s-optimize-api` + `adk4s-optimize-testkit` is premature — the spec-lint PASS verdict confirms the surface is small enough for one module). The laws testkit lives in a `testkit` sub-package so downstream Phase-2+ optimizer modules can depend on `adk4s-optimize` and reuse `OptimizerLaws` without a separate module.

```scala
lazy val `adk4s-optimize` = (project in file("adk4s-optimize"))
  .dependsOn(`structured-llm`)
  .settings(
    name := "adk4s-optimize",
    libraryDependencies ++= Seq(
      catsEffect,
      fs2Core,
      munitMain,        // main-scope munit for OptimizerLaws (mirrors adk4s-memory-testkit)
      munitCatsEffect,  // main scope — laws return IO[Boolean]
      hedgehogMunit     // Test scope — toy-optimizer property tests
    ) ++ testDeps,
    scalacOptions ++= scala3Options
  )
```

`munitMain`, `munitCatsEffect` (main scope), and `hedgehogMunit` (Test scope) are added to `project/Dependencies.scala` as main-scope variants where they don't already exist (`munitMain` already exists; `munitCatsEffectMain` and `hedgehogMunitMain` are new — see Decision: Testkit placement below). The module is **aggregated by the root build** so a top-level `sbt compile` includes it (unlike `verified`, which is deliberately excluded).

The module graph delta (recorded by apply Step 12 into `openspec/project.md` and `openspec/capability-profile.md`):

```
adk4s-optimize → structured-llm
adk4s-examples → adk4s-optimize (Test scope only, if a Phase-0 example is added — not required by this spec)
```

## Effect Boundaries

<!-- The surface is overwhelmingly pure: `Optimizable[P]` is a stateless
     typeclass with total functions, `PredictorState`/`PredictorPath`/`Demo`
     are plain immutable data, and `Optimizable.derived` is a compile-time
     `Mirror` macro. The only effectful code is `Predict0[F, I, O]`, which
     wraps `StructuredLLM.completeTemplate` — and even that is not invoked
     in Phase 0 (the spec says "Phase 0 does not invoke it, only carries the
     capability"). Ring 6 is not applicable because the pure code uses
     `Mirror`/`inline` and `ujson.Value`, which are not PureScala-friendly
     (Stainless is pinned to Scala 3.7.2 in the `verified` module). -->

### Pure Code (Ring 6 candidates — but NOT applied, see verification map)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `Demo`, `PredictorState`, `PredictorPath` | Plain immutable data (case classes) | No — uses `ujson.Value` (not PureScala-representable); Stainless is pinned to Scala 3.7.2 in `verified`, this module is on 3.8.4 |
| `Optimizable[P]` (predictors / update / updateAll / updateEither) | Pure typeclass methods — no I/O, no mutation, return new values | No — same reason; the purity/round-trip laws are enforced by Ring 3 Hedgehog properties instead |
| `Optimizable.derived` | Compile-time `Mirror`-based structural derivation | No — `inline`/`Mirror` macros are not Stainless-compatible |
| `HasPredictorState[Self]` (state / withState) | Pure leaf capability | No — same reason |
| `OptimizeError` (UnknownPath / FrozenPath) | Sealed error ADT | No — extends `Throwable` (not PureScala) |
| `OptimizerLaws` | Pure law checks returning `IO[Boolean]` | No — returns `IO`, not pure; covered by Ring 3 |

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `Predict0[F, I, O]` | `F[_] : Async` (via `StructuredLLM[F]`) | Placeholder predictor wrapping `StructuredLLM.completeTemplate`. **Not invoked in Phase 0** — only carries the capability and the `PredictorState`. Implements `HasPredictorState` so it can be a leaf in derived `Optimizable` instances. |
| `OptimizerLaws` | `IO[Boolean]` (each law) / `IO[Boolean]` (`all`) | Laws return `IO[Boolean]` so they can be run inside `munit.CatsEffectSuite` tests. The `IO` is pure (no real I/O — just `IO.pure` / `IO.delay` over `Optimizable.predictors`); it is effectful only in the cats-effect sense, not in the side-effect sense. Pattern follows `AgentMemoryLaws`. |
| `UppercaseInstructions[IO, P]`, `StaticDemoInjector[IO, P]` | `P => IO[P]` | Toy optimizers. The `IO` is pure (`IO.pure(Optimizable.updateAll(...))`); they exist to exercise the surface through the laws, not to perform real I/O. |

## Type Strategy — Invalid-State Prevention

<!-- Every invariant from the spec is placed on the hierarchy. The dominant
     technique here is "Best: invalid state is impossible to express" — the
     surface is a pure typeclass over immutable data, so most invariants are
     enforced by the type system or by structural derivation. The one
     "Okay: validator" placement is the `update`/`updateEither` path check,
     which is unavoidable: a `PredictorPath` is a runtime value (an
     optimizer can construct any path), so whether it addresses a real
     predictor can only be checked at runtime. The spec mandates this be a
     typed error (`UnknownPath`), never a silent fallback — see
     Compile-Negative Obligations in the spec. -->

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| `PredictorState` has exactly `instructions: String`, `demos: Vector[Demo]`, `frozen: Boolean` | Best | Case class with three fields — the type system makes any other shape unconstructible | Plain immutable data; no smart constructor needed |
| `Demo` is exactly `input: ujson.Value, output: ujson.Value` | Best | Case class with two fields | Same as above |
| `PredictorPath` segments are `Vector[String]`, outermost first | Best | Case class with one field; the "outermost first" ordering is a construction-time convention enforced by `Optimizable.derived` (the only producer of paths in Phase 0) | An optimizer can construct an arbitrary `PredictorPath(Vector("zzz"))` but it will fail the `update`/`updateEither` path check — see below |
| `OptimizeError` has exactly two variants (`UnknownPath`, `FrozenPath`) | Best | Sealed enum with two cases — adding a variant is a compile error everywhere it is matched (Ring 0 exhaustiveness escalation) | Compile-negative obligation in the spec: `assertDoesNotCompile("e match { case OptimizeError.UnknownPath(_) => () }")` |
| Every match over `OptimizeError` is exhaustive (no catch-all) | Best | `-Wconf:name=PatternMatchExhaustivity:e` (already in `scala3Options`) turns inexhaustive matches into compile errors | The spec mandates this; the build flag is already active project-wide |
| `Optimizable.update` is total on paths returned by `predictors` | Okay | Runtime path check in `updateEither` — returns `Left(OptimizeError.UnknownPath(path))` for paths not in the enumeration; `update` raises the same error on the left branch | A `PredictorPath` is a runtime value (an optimizer can construct any path), so the check cannot be type-level. The error is typed and total — no silent fallback. Compile-negative obligation: `update` returning `Unit` or mutating in place is forbidden by the return type `P` + WartRemover `Var`/`Throw` warts. |
| `Optimizable.update` is pure (input unchanged) | Best | Return type is `P` (a new value); WartRemover `Var` wart forbids in-place mutation; the case-class `copy` idiom produces a new value | The `update-purity` Hedgehog property is the runtime guard, but the type system + WartRemover make in-place mutation unrepresentable |
| `updateEither` returns `FrozenPath` for frozen predictors | Okay | Runtime frozen-flag check in `updateEither` — returns `Left(OptimizeError.FrozenPath(path))` when the addressed predictor's `state.frozen` is true | The frozen flag is a runtime `Boolean` field (the spec mandates "freezing is data, not a type-level distinction"), so the check is necessarily runtime. The error is typed and total. |
| `updateAll` skips frozen predictors | Best | `updateAll` is implemented as `predictors(p).filter(!_._2.frozen).foldLeft(p)((p, kv) => update(p, kv._1, f(kv._1, kv._2)))` — the filter makes it structurally impossible to apply `f` to a frozen predictor | The frozen exclusion is enforced by the implementation structure, not by a runtime check that could be bypassed. The `updateAll-skips-frozen` Hedgehog property is the runtime guard. |
| `updateAll` is a no-op when all frozen | Best | Follows from the filter above — an empty fold is the identity | Structural |
| `predictors` returns declaration order | Best | `Mirror.ProductOf` preserves `MirroredElemLabels` order; the derivation walks labels in order | Structural — `Mirror` guarantees declaration order |
| Non-predictor fields do not appear in `predictors` | Best | Derivation only contributes a field if it has a `HasPredictorState` or `Optimizable` instance (summoned via `summonInline`); fields with no instance are skipped | Structural — a field without the typeclass instance is not addressable |
| `PredictorPath` is distinct from `FieldPath` | Best | Different package (`org.adk4s.optimize` vs `org.adk4s.core.types`), different kind (case class with `render` method vs opaque newtype), different domain (LM-call site addressing vs workflow field mapping) | No type-level aliasing; the two types cannot be confused. Recorded as a Ring 8 manual-review obligation. |
| `Predict0` does not render demos into prompts in Phase 0 | Best | `Predict0` exposes no `run`/`render`/`complete` method in Phase 0 — it only carries `state` and `withState` (via `HasPredictorState`) and the `StructuredLLM` capability | Structural — there is no code path that could inject a demo into a `Prompt`. **Note for Phase 2**: if `Predict0` gains a `run` method, an explicit adversarial scenario must be added (spec-lint check 15j note). |
| Empty `PredictorPath` is never returned by `predictors` | Best | Derivation only produces paths with ≥1 segment (leaf = field name, subtree = field name + non-empty nested, collection = field name + index) | Structural — the empty path is a valid value (root marker) but is unreachable from `predictors`. The `predictors-declaration-order` property implicitly excludes it. |
| `Optimizable.derived` requires no hand-written instance | Best | `inline def derived[P <: Product](using m: Mirror.ProductOf[P]): Optimizable[P]` — the compiler synthesizes the instance from the `Mirror` | Structural — `summonInline[Optimizable[MyCaseClass]]` succeeds for any case class with the right field typeclass instances |

No "Risky" or "Bad" placements. The two "Okay" placements (path check, frozen check) are unavoidable because paths and frozen flags are runtime values; both are enforced by typed errors, never silent fallbacks.

## Refined Type Strategy

<!-- The detected stack has NO refined-type library (no Iron, no
     refined-scala). The repo convention is plain case classes for domain
     values and opaque newtypes for identifiers that need a distinct type
     without runtime overhead (e.g. `FieldPath`, `NodeKey`). This change
     follows that convention: no new refined types are introduced. -->

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| (none) | — | — | No refined-type library in the stack; the spec's invariants are enforced by the type system (case classes, sealed enum) and structural derivation, not by constrained opaque types. |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `instructions: String` | Human-readable free-form string; no structural constraint. An empty string is a valid value (the spec's "Default predictor-state" scenario asserts it). |
| `demos: Vector[Demo]` | Plain immutable collection; empty is valid. |
| `frozen: Boolean` | Plain boolean; both values are valid (the spec's "Frozen predictor-state is constructible" scenario asserts it). |
| `PredictorPath.segments: Vector[String]` | Plain collection of field-name strings; the empty vector is a valid root marker. A constrained opaque type would prevent the root-marker use case. |
| `Demo.input: ujson.Value`, `Demo.output: ujson.Value` | Arbitrary JSON values; no structural constraint (the optimizer carries them opaquely). |

The `Optimizable[P]` typeclass and `HasPredictorState[Self]` typeclass are plain typeclasses (not refined types) — they encode capabilities, not value constraints.

## IDL Model Layout

<!-- This change introduces NO API operations and NO Smithy schema changes.
     `Predict0` is a placeholder case class, not a Smithy operation. The
     `PredictorState`/`Demo`/`PredictorPath` data types are plain case
     classes (serializable-ready by shape for Phase 2, but no serialization
     is implemented or round-tripped in Phase 0 — see Compatibility Story).
     The `structured-llm-test-models` Smithy codegen module is not touched. -->

**Not applicable.** No services, no operations, no structures. The spec's "Concepts Used (from inventory)" references `Schema[A]` (opaque type) and `StructuredLLM[F[_]]` (service trait) as reuse targets, but this change does not extend either — `Predict0` wraps `completeTemplate` as a method call, not as a new Smithy operation.

## Error Strategy

<!-- `OptimizeError` is a NEW sealed enum that stands alone in
     `org.adk4s.optimize` — it does NOT extend `org.adk4s.core.error.AdkError`.
     This is the cross-phase convention from
     docs/dspy-port-operative-plan.md (line 35): new error ADTs stand alone
     in their module and bridge to `AdkError` later. The spec mandates
     exhaustiveness (no catch-all) and provides a compile-negative
     obligation for the catch-all case. -->

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `OptimizeError` (sealed enum, extends `Throwable`) | `UnknownPath(path: PredictorPath)`, `FrozenPath(path: PredictorPath)` | `Optimizable.update` (raises), `Optimizable.updateEither` (returns `Left`) |

**Decision: `OptimizeError` stands alone, NOT extending `AdkError`.** See Decision: Error ADT placement below.

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Pure → Pure (`updateEither`) | `Either[OptimizeError, P]` | `Optimizable.updateEither(p, path, f): Either[OptimizeError, P]` — returns `Left(OptimizeError.UnknownPath(path))` or `Left(OptimizeError.FrozenPath(path))` |
| Pure → Pure (`update`) | `P` (raises on left branch) | `Optimizable.update(p, path, f): P` — defined as `updateEither(p, path, f).fold(throw _, identity)`; the raising variant is the convenience for paths known to be valid and unfrozen |
| Pure → Effect (toy optimizers) | `IO[P]` (raises via `IO.raiseError`) | `UppercaseInstructions.compile(p): IO[P]` — `IO.pure(Optimizable.updateAll(p, ...))`; no error path in Phase 0 (toy optimizers only call `updateAll`, which has no error path) |
| Testkit → Test | `IO[Boolean]` (laws) | `OptimizerLaws.all(opt, p): IO[Boolean]` — returns `IO.pure(false)` if a law fails, never raises |

**No swallowed errors.** `updateEither` is total (never raises); `update` raises the exact `OptimizeError` variant from the left branch (no wrapping, no default). `updateAll` has no error path by design (frozen exclusion is silent — the spec mandates this). No `case _` defaults are permitted in matches over `OptimizeError` (Ring 0 exhaustiveness escalation + compile-negative obligation).

## Compatibility Story (Ring 4)

<!-- Ring 4 is NOT applicable to this change. The spec explicitly states
     (proposal §Verification Strategy R4): "NOT applicable. State is
     serializable-ready by shape but no serialization is implemented or
     round-tripped in Phase 0 (deferred to Phase 2 per scope)." No fixtures
     are touched, no persisted events, no wire data, no Smithy schema
     evolution. -->

**Not applicable.** No persisted data, no wire data, no Smithy schemas, no checkpoint state. `PredictorState`/`Demo`/`PredictorPath` are *serializable-ready by shape* (plain case classes with `String`/`Vector`/`Boolean`/`ujson.Value` fields — no closures, no effect types, no live references — see spec scenario "Placeholder state is serializable-ready"), but no `upickle.Writer`/`Reader` instances are derived or round-tripped in Phase 0. Phase 2 will add serialization and the corresponding Ring 4 fixture obligations.

## Verification Map

<!-- For each module/package, state which rings apply. This feeds directly
     into implementation-order.md and the per-spec ring pipeline.
     R8 (adversarial review) applies to every code-changing module. -->

| Module / Package | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|------------------|----|----|----|----|----|----|----|----|----|----|
| `org.adk4s.optimize` (data types: `Demo`, `PredictorState`, `PredictorPath`, `OptimizeError`) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.optimize` (`Optimizable[P]` + `HasPredictorState[Self]` + `Mirror` derivation) | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | — | — | — | ✅ (MANDATORY) | — |
| `org.adk4s.optimize` (`Predict0[F, I, O]` placeholder) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.optimize.testkit` (`OptimizerLaws`) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.optimize` test sources (`UppercaseInstructions`, `StaticDemoInjector`, toy programs) | ✅ | ✅ | — | ✅ (MANDATORY) | — | — | — | — | — | — |
| `build.sbt` + `project/Dependencies.scala` (new module wiring) | ✅ | — | ✅ | — | — | — | — | — | ✅ | — |

**Ring-by-ring rationale:**

- **R0 (Compile)**: All packages. Exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) is already active project-wide and applies to the new `OptimizeError` enum from day one. The new module must compile under `scala3Options` (`-deprecation`, `-feature`, `-unchecked`, `-Xkind-projector:underscores`).
- **R1 (Lint)**: All main + test packages. Scalafix (DisableSyntax, RemoveUnused, OrganizeImports, NoConfigFactory, NoSysEnv, NoKeywordTry/Catch/Finally) + WartRemover (`Warts.unsafe` minus `TripleQuestionMark`, `Any`, `DefaultArguments`). **⚠ VERIFY in Ring 1**: `Mirror`-derived code (`summonInline`, `erasedValue`, `constValue`) and `ujson.Value` payloads may trip the temporarily-excluded `Any`/`AsInstanceOf` warts. The existing `ToolInfer.scala` uses `@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))` on the `Mirror`-walking helpers — the same pattern will be used in `Optimizable.derived` if needed. Narrow the suppression to the specific helper, never re-enable `Any`/`AsInstanceOf` broadly.
- **R2 (Architecture)**: All main packages. The new `org.adk4s.optimize` package boundary is manual (no custom scalafix arch rules in this repo). Apply Step 12 appends the purity-rule row to `openspec/capability-profile.md`: `org.adk4s.optimize` MAY import cats-effect, fs2-core, structured-llm, ujson; MUST NOT import workflows4s, llm4s LLM client, adk4s-orchestration, adk4s-core.tools, adk4s-core.component, adk4s-core.interrupt. Enforced by code review + import audit (Ring 8).
- **R3 (Property tests)**: MANDATORY for `Optimizable` + `HasPredictorState` + `OptimizerLaws` + toy optimizers. Hedgehog 0.13.1 via `hedgehog-munit`. 14 properties (R0.1–R0.8 + 6 laws properties). **No concurrent behavior** — the surface is pure and synchronous; `TestControl` is NOT required.
- **R4 (Wire/persistence)**: NOT applicable. State is serializable-ready by shape but no serialization is implemented or round-tripped in Phase 0.
- **R5 (Mutation)**: Available, deferred. The production logic is `Mirror`-derived boilerplate + pure data covered by the laws suite. Stryker4s is available (`stryker4s.conf` present); the `mutate` list may be retargeted to `adk4s-optimize/src/main/scala/org/adk4s/optimize/Optimizable.scala` if a non-trivial helper emerges during implementation. Deferred unless implementation reveals a non-trivial pure helper.
- **R6 (Formal)**: NOT applicable. The surface uses `Mirror`/`inline` and `ujson.Value` — not PureScala modules. Stainless is pinned to Scala 3.7.2 in `verified`; this module is on 3.8.4. The purity/round-trip laws are enforced by Ring 3 Hedgehog properties instead.
- **R7 (Model checking)**: NOT applicable. No TLA+/Apalache in the stack. No distributed/event-driven invariants.
- **R8 (Adversarial review)**: MANDATORY for all main packages + build wiring. Fresh-context reviewer checks for: silent fallback mappings in `update` (e.g. `case _ => p`), `case _` defaults swallowing `UnknownPath`/`FrozenPath`, partial functions in derivation, frozen-flag bypass via public APIs (e.g. a `PredictorState` with `frozen = true` that `updateAll` still mutates), private helpers that violate the purity law, `OptimizeError` extending `AdkError` (must stand alone), forbidden imports in the new module.
- **R9 (Telemetry)**: NOT applicable. No otel4s/Daut in the stack. No API operations/event sequences affected.

## Technical Decisions

### Decision: `OptimizeError` stands alone, NOT extending `AdkError`

**Context**: The spec mandates a typed error ADT for `update`/`updateEither` failures. The proposal's ⚠ VERIFY item asks whether `OptimizeError` should extend `org.adk4s.core.error.AdkError` (the core error hierarchy) or stand alone in `org.adk4s.optimize`. The cross-phase convention in `docs/dspy-port-operative-plan.md` (line 35) recommends: "stand alone in their module; bridge later".

**Options considered**:
1. **Stand alone** — `enum OptimizeError extends Throwable` in `org.adk4s.optimize`, no `AdkError` dependency.
2. **Extend `AdkError`** — `enum OptimizeError extends AdkError` in `org.adk4s.optimize`, requires a dependency on `adk4s-core` (for `AdkError`).
3. **Bridge now** — `OptimizeError` stands alone but provides a `toAdkError: AdkError` conversion, requires a dependency on `adk4s-core`.

**Decision**: **Stand alone (option 1)**. `enum OptimizeError extends Throwable` with variants `UnknownPath(path: PredictorPath)` and `FrozenPath(path: PredictorPath)`. No dependency on `adk4s-core`.

**Consequences**:
- The `adk4s-optimize` module depends only on `structured-llm` (for `Predict0`'s `StructuredLLM.completeTemplate` wrapper) + cats-effect + fs2-core + ujson. It does NOT depend on `adk4s-core`, keeping the module graph decoupled (the spec's "Optimize module skeleton" requirement mandates no `adk4s-core` dependency).
- A later phase can add a bridge `OptimizeError.toAdkError: AdkError` (or a `given Conversion[OptimizeError, AdkError]`) in a module that depends on both — this is the "bridge later" path. The sealed enum shape makes adding a bridge non-breaking.
- `OptimizeError extends Throwable` (not `Exception`) so it can be raised by `Optimizable.update` (the raising variant) via `throw` — wait, the project conventions forbid `throw` (WartRemover `Throw` wart is in the active set). **Refinement**: `update` raises via `IO.raiseError` is not applicable here because `update` is pure (returns `P`, not `F[P]`). The raising variant must use a different mechanism. See Decision: `update` raising mechanism below.

### Decision: `update` raising mechanism

**Context**: The spec mandates `Optimizable.update(p, path, f): P` is "total on paths returned by `predictors`; for paths not enumerated, it SHALL raise an unknown-path error" and "SHALL be defined in terms of `updateEither`, raising the error on the left branch." But `update` is pure (returns `P`, not `F[P]`), and the project conventions forbid `throw` (WartRemover `Throw` wart is active). `OptimizeError extends Throwable` would allow `throw`, but that violates the wart.

**Options considered**:
1. **`throw` the `OptimizeError`** — `updateEither.fold(e => throw e, identity)`. Requires suppressing the `Throw` wart for this site.
2. **`OptimizeError` does NOT extend `Throwable`; `update` calls `sys.error` / `IllegalStateException`** — loses the typed error.
3. **`OptimizeError` extends `RuntimeException`** — `throw` still trips the wart.
4. **`update` is removed; only `updateEither` is public** — contradicts the spec, which mandates both.
5. **`OptimizeError extends Throwable`; `update` uses `.fold(e => throw e, identity)` with a targeted `@SuppressWarnings(Array("org.wartremover.warts.Throw"))`** — matches the existing `ToolInfer.scala` pattern of targeted `@SuppressWarnings` for unavoidable wart violations.

**Decision**: **Option 5**. `OptimizeError extends Throwable` (so it can be raised and caught as a typed value), and `update` is implemented as `updateEither(p, path, f).fold(e => throw e, identity)` with a targeted `@SuppressWarnings(Array("org.wartremover.warts.Throw"))` on the `update` method only. This mirrors the existing `ToolInfer.scala` pattern (`@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))` on `Mirror`-walking helpers).

**Consequences**:
- The `Throw` wart is suppressed on exactly one method (`update`), not broadly. The suppression is documented in a comment: "raises the typed `OptimizeError` from the left branch of `updateEither`; the `Throw` wart is suppressed because this is the spec-mandated raising variant of a total-on-valid-paths API".
- `updateEither` is the safe, total variant (no `throw`, no suppression) — it is the public API optimizers should prefer.
- The `Throw` wart remains active everywhere else in the module.
- Ring 8 adversarial review verifies the suppression is targeted to `update` only and that `updateEither` has no `throw`.

### Decision: `updateAll` semantics over entirely-frozen subtrees

**Context**: The proposal's ⚠ VERIFY item 3 asks: what does `updateAll` do when a nested subtree is entirely frozen? The plan recommends "`frozen` is per-leaf only; subtree freezing = freezing all leaves, done by `CompiledState.freeze` in Phase 2."

**Options considered**:
1. **Per-leaf `frozen` only** — `updateAll` filters `predictors(p).filter(!_._2.frozen)` and applies `f` to each non-frozen leaf. A subtree is "frozen" iff all its leaves are frozen. No subtree-level frozen flag.
2. **Subtree-level `frozen`** — `Optimizable` would need a `frozenSubtree(p, path): Boolean` method. Adds complexity; the spec's `PredictorState` has only a per-leaf `frozen` flag.

**Decision**: **Per-leaf `frozen` only (option 1)**. `updateAll` is implemented as:
```scala
def updateAll(p: P, f: (PredictorPath, PredictorState) => PredictorState): P =
  predictors(p).filter(!_._2.frozen).foldLeft(p) { (p, kv) =>
    update(p, kv._1, f(kv._1, kv._2))
  }
```
No subtree-level frozen flag exists in Phase 0. Subtree freezing is a Phase-2 `CompiledState.freeze` concern (it will freeze all leaves under a path).

**Consequences**:
- `updateAll` over an entirely-frozen subtree is a no-op (the filter yields no leaves under that subtree). This matches the spec's "All frozen — updateAll is a no-op" scenario.
- The `updateAll-skips-frozen` and `updateAll-path-set-preserved` Hedgehog properties guard this.
- Phase 2's `CompiledState.freeze` will iterate `predictors` and set `frozen = true` on each leaf under a path — no `Optimizable` change needed.

### Decision: Testkit placement — `testkit` sub-package vs separate module

**Context**: The spec's Implementation Anchors table says `OptimizerLaws` lives in `adk4s-optimize/src/main/scala/org/adk4s/optimize/OptimizerLaws.scala (or a org.adk4s.optimize.testkit companion — design.md)`. The `adk4s-memory-testkit` precedent uses a SEPARATE module (`adk4s-memory-testkit` depends on `adk4s-memory-api`) so downstream backends can depend on the laws without the implementation. Should `OptimizerLaws` follow the same split?

**Options considered**:
1. **Separate module** — `adk4s-optimize-testkit` depends on `adk4s-optimize`. Downstream Phase-2+ optimizer modules depend on `adk4s-optimize-testkit` for the laws.
2. **`testkit` sub-package in `adk4s-optimize`** — `OptimizerLaws` lives in `org.adk4s.optimize.testkit` (main scope). Downstream modules depend on `adk4s-optimize` and get the laws transitively.
3. **`OptimizerLaws` in `org.adk4s.optimize` (no sub-package)** — simplest, but pollutes the main package with test-only API.

**Decision**: **Option 2 — `testkit` sub-package in `adk4s-optimize`**. `OptimizerLaws` lives in `adk4s-optimize/src/main/scala/org/adk4s/optimize/testkit/OptimizerLaws.scala`. munit + munit-cats-effect are in MAIN scope (mirroring `adk4s-memory-testkit`'s `munitMain` usage).

**Consequences**:
- Phase 0 ships one module, not two. The split into `adk4s-optimize-api` + `adk4s-optimize-testkit` is premature — the surface is small (one typeclass + one leaf capability + one placeholder + one error ADT + one laws suite) and the spec-lint PASS verdict confirms it is implementable in one module.
- Downstream Phase-2+ optimizer modules depend on `adk4s-optimize` and get `OptimizerLaws` transitively via `import org.adk4s.optimize.testkit.OptimizerLaws`. If a later phase needs the laws without the `Optimizable` derivation (unlikely — optimizers need both), the split can happen then.
- `project/Dependencies.scala` needs new main-scope variants: `munitCatsEffectMain` (currently `munitCatsEffect` is `% Test`) and `hedgehogMunitMain` (currently `hedgehogMunit` is `% Test`). These are added alongside the existing `munitMain` precedent. The `testDeps` seq (Test scope) is unchanged.

### Decision: `Mirror`-based derivation shape — `summonInline` chain

**Context**: The proposal's ⚠ VERIFY item 1 asks to prototype the `Mirror`-based derivation for the mixed leaf/subtree/`Vector` rule on Scala 3.8.4 before spec freeze. The existing `ToolInfer.scala` pattern uses `inline def` + `erasedValue` + `summonInline` to walk `MirroredElemLabels` and `MirroredElemTypes`. The fallback is a macro-free `summonInline`-chain derivation.

**Options considered**:
1. **`inline def derived` with `summonInline` chain** — for each field type, `summonInline[HasPredictorState[FieldType]]` (leaf), `summonInline[Optimizable[FieldType]]` (subtree), or pattern-match on `Vector[FieldType]` (collection). The compiler tries each in order; the first that compiles wins.
2. **Scala 3 macro** — a full macro would give more control but is heavier and harder to debug.
3. **Hand-written instances** — contradicts the spec's "no hand-written instance" requirement.

**Decision**: **Option 1 — `inline def derived` with `summonInline` chain**, mirroring the `ToolInfer.scala` pattern. The derivation walks `MirroredElemLabels` and `MirroredElemTypes` in parallel; for each field type, it attempts (in order):
1. `summonInline[Optimizable[FieldType]]` — if it exists, recurse into the subtree, prefixing the field name.
2. `summonInline[HasPredictorState[FieldType]]` — if it exists, contribute a leaf with path = field name.
3. If the field type is `Vector[ElemType]` and `summonInline[HasPredictorState[ElemType]]` exists, contribute indexed leaves (segment = index as string).
4. Otherwise, skip the field (non-predictor).

The order matters: `Optimizable` is tried before `HasPredictorState` so a sub-program (which has both an `Optimizable` instance AND, if it's a predictor itself, possibly a `HasPredictorState` instance) is treated as a subtree, not a leaf. In Phase 0, `Predict0` has only `HasPredictorState` (no `Optimizable`), so it is always a leaf; toy programs (`TwoPredictors`, `Outer`, `Pipeline`) have only `Optimizable` (no `HasPredictorState`), so they are always subtrees. No ambiguity in Phase 0.

**Consequences**:
- The derivation is compile-time (no runtime reflection). A field with no `HasPredictorState` and no `Optimizable` instance is silently skipped — this is the spec's "other fields are ignored" rule, NOT a silent fallback (the field is not a predictor, so skipping it is correct behavior).
- The `Vector` recursion is structural: `summonInline[HasPredictorState[ElemType]]` is attempted for the element type. `Map[String, *]` is deferred to Phase 3 (the spec's "Out of Scope").
- The `Mirror`-walking helpers may need `@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))` (as `ToolInfer.scala` does) — targeted suppression, never broad.
- **⚠ VERIFY in Ring 1**: confirm `summonInline` + `erasedValue` + `constValue` do not trip the `Any` wart on Scala 3.8.4. If they do, narrow the suppression to the specific helper.

### Decision: `Predict0` shape — case class with `HasPredictorState`

**Context**: The proposal's ⚠ VERIFY item 2 asks whether `HasPredictorState` should instead be a `Predictor[F]` supertrait. The spec mandates `Predict0` implements `HasPredictorState`.

**Options considered**:
1. **`HasPredictorState[Self]` typeclass + `Predict0` case class** — `Predict0` is a case class with fields `state: PredictorState`, `template: PromptTemplate[I]`, `schema: Schema[O]`, `structured: StructuredLLM[F]`. It has a `given HasPredictorState[Predict0[F, I, O]]` instance. `Optimizable.derived` summons `HasPredictorState` for each field.
2. **`Predictor[F]` supertrait** — `Predict0 extends Predictor[F]`; `Predictor[F]` has `state`/`withState` methods. `Optimizable.derived` would need to recognize `Predictor[F]` subtypes via `summonInline[Predictor[FieldType]]` — but `summonInline` works on typeclasses, not subtypes, so this would require a typeclass anyway (`HasPredictorState` is that typeclass).
3. **`Predict0` is a case class that IS-A `HasPredictorState` via inheritance** — `Predict0 extends HasPredictorState[Predict0[F, I, O]]` — but `HasPredictorState` is a typeclass (trait with `self` parameter), not a parent trait.

**Decision**: **Option 1 — `HasPredictorState[Self]` typeclass + `Predict0` case class with a `given` instance**. `Predict0` is a plain case class; `HasPredictorState` is a typeclass with a `given` instance for `Predict0`. This matches the spec's "leaf-predictor capability" requirement and the `Mirror`-based derivation (which summons typeclass instances, not subtypes).

**Consequences**:
- `Predict0[F, I, O]` is a case class with fields: `state: PredictorState`, `template: PromptTemplate[I]`, `schema: Schema[O]`, `structured: StructuredLLM[F]`. The `state` field is the tunable site; the other fields are the capability (carried but not invoked in Phase 0).
- `given hasPredictorStateForPredict0[F[_], I, O]: HasPredictorState[Predict0[F, I, O]]` with `state(self) = self.state` and `withState(self, s) = self.copy(state = s)`.
- `Optimizable.derived` summons `HasPredictorState[Predict0[F, I, O]]` for any field of type `Predict0[F, I, O]` — the `given` is in the companion object of `Predict0` (or in a `given` import in the deriving file) so it is in scope.
- **⚠ VERIFY in Ring 1**: confirm `given HasPredictorState[Predict0[F, I, O]]` with `F[_]` / `I` / `O` type parameters does not trip the `Any` wart (the `state` field is `PredictorState`, not `Any`; `withState` returns `Predict0[F, I, O]`, not `Any`). The `Any` wart exclusion in `ThisBuild` is for `s"..."` string interpolation false positives — this `given` should not trip it.

### Decision: Surface API is FROZEN

**Context**: The proposal states "The surface API is declared frozen in this change's `design.md`: any later change to it requires its own proposal."

**Decision**: The following surface is FROZEN by this change. Any later change to any of these signatures requires a new OpenSpec proposal (not just a delta spec):

- `trait Optimizable[P]` — method signatures: `predictors(p: P): Vector[(PredictorPath, PredictorState)]`, `update(p: P, path: PredictorPath, f: PredictorState => PredictorState): P`, `updateEither(p: P, path: PredictorPath, f: PredictorState => PredictorState): Either[OptimizeError, P]`, `updateAll(p: P, f: (PredictorPath, PredictorState) => PredictorState): P`
- `object Optimizable` — `inline def derived[P <: Product](using m: Mirror.ProductOf[P]): Optimizable[P]`, `def apply[P](using o: Optimizable[P]): Optimizable[P]`
- `trait HasPredictorState[Self]` — `state(self: Self): PredictorState`, `withState(self: Self, s: PredictorState): Self`
- `final case class Demo(input: ujson.Value, output: ujson.Value)`
- `final case class PredictorState(instructions: String, demos: Vector[Demo], frozen: Boolean)`
- `final case class PredictorPath(segments: Vector[String])` with `def render: String`
- `enum OptimizeError extends Throwable` with `UnknownPath(path: PredictorPath)`, `FrozenPath(path: PredictorPath)`
- `final case class Predict0[F[_], I, O](state: PredictorState, template: PromptTemplate[I], schema: Schema[O], structured: StructuredLLM[F])` (field names and types; the `given HasPredictorState` instance)
- `OptimizerLaws` — the three laws (purity, frozen-preserved, path-set-preserved) and the `all` conjunction

**Consequences**:
- Phase 2+ changes may ADD to `OptimizeError` (new variants) — but adding a variant is a compile error everywhere it is matched (Ring 0 exhaustiveness), so it is a safe, reviewable change. Adding a variant does NOT require a new proposal (it extends the enum, not the surface). The spec's "Typed error ADT for update failures" requirement explicitly anticipates later variants ("adding a variant in a later phase is a compile error, not a silent fall-through").
- Phase 2+ changes may ADD new `Optimizable` instances (e.g. for `Map[String, *]` recursion) — but the `derived` semantics (leaf/subtree/collection rule) are frozen. A new recursion rule (e.g. `Map`) requires a new proposal.
- Phase 2+ changes may REPLACE `Predict0` with the real `Predict` — but `Predict` must implement `HasPredictorState` with the same `state`/`withState` signatures so derived `Optimizable` instances continue to work. Replacing `Predict0` is a new proposal (it changes the placeholder).
- Phase 2+ changes may ADD methods to `Optimizable` (e.g. `freeze`, `thaw`) — but the existing methods are frozen. Adding a method requires a new proposal.
