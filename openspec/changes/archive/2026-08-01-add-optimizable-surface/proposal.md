# Proposal: Add the Optimizable Predictor Surface (DSPy Port — Phase 0)

## Why

Every optimizer in the DSPy port (Phases 2–4) needs to (a) enumerate the tunable
LM-call sites inside an arbitrary user program, (b) read and replace their
instructions and demos as plain data, and (c) produce a *new* program value with
one site updated — without knowing the program's concrete types. Python DSPy
does this with runtime reflection (`self.__dict__` walking); Scala needs a
designed surface.

This surface is load-bearing for Phases 2–4 and effectively irreversible once
optimizers build on it, so it is spiked and frozen first, with **two
structurally different toy optimizers** as the acceptance test. Freezing the
erased surface early de-risks the compiler MVP (Phase 2) and the instruction
optimizer (Phase 3): they can be written against a stable `Optimizable[P]`
typeclass instead of discovering the shape as they go.

Phase 0 is the spike for the erased predictor surface. It introduces the
`adk4s-optimize` module skeleton and the `Optimizable[P]` typeclass with
`Mirror`-based derivation, plus an `OptimizerLaws` testkit. No real optimizer,
no prompt rendering, no trace capture, no persistence — only the surface and the
laws that prove it is usable by an unknown optimizer.

## What Changes

### Affected Capabilities

- `specs/optimizable-surface/spec.md` — new capability: the
  `Optimizable[P]` typeclass, `PredictorState`/`PredictorPath`/`Demo` data
  types, `HasPredictorState[Self]` leaf capability, `Mirror`-based derivation
  semantics (leaf / subtree / `Vector` recursion rule), the `OptimizeError` ADT,
  and the `OptimizerLaws` testkit that any future optimizer must satisfy.

### Out of Scope

- Real adapters (`Predict`, `ChainOfThought`, etc.) — Phase 2.
- Demo rendering into prompts (state is carried but not yet consumed) — Phase 2.
- Any real optimizer (bootstrap, MIPRO, GEPA, instruction optimization) —
  Phases 2–4.
- Trace capture (only the state shape is serializable-ready) — Phase 2/3.
- Save/load of `PredictorState` (shape must be serializable-ready, persistence
  is Phase 2).
- `Map[String, *]` recursion in derivation — deferred to Phase 3 per the plan's
  DECISION (only `Vector` is supported in Phase 0).
- The completion cache (`org.adk4s.core.cache`) — Phase 2.
- Signature/adapter work inside `structured-llm` — Phase 2.

## Approach

Introduce a new `adk4s-optimize` sbt module (package `org.adk4s.optimize`)
depending on `structured-llm`, cats-effect, and fs2 — matching the cross-phase
convention in `docs/dspy-port-operative-plan.md`. The module is a skeleton in
Phase 0: it exposes the erased surface and the laws testkit, nothing more.

The core abstraction is a typeclass `Optimizable[P]` over a program type `P`
(treated as an opaque, type-erased product from the optimizer's point of view):

- `predictors(p): Vector[(PredictorPath, PredictorState)]` — enumerate every
  tunable LM-call site with a stable path.
- `update(p, path, f): P` — pure update of one predictor's state by path.
- `updateAll(p, f): P` — update every non-frozen predictor.

Derivation is `Mirror`-based (`inline def derived`): for a case class `P`, each
field with a `HasPredictorState` instance contributes a leaf (path = field
name); each field with an `Optimizable` instance contributes its subtree with
the field name prepended; `Vector` fields of predictors contribute indexed
subtrees (segment = index as string); other fields are ignored.

A minimal placeholder `Predict0[F, I, O]` (thin wrapper over
`StructuredLLM.completeTemplate`, enough to carry state) stands in for the real
`Predict` until Phase 2. It implements `HasPredictorState` so it can be a leaf
in derived `Optimizable` instances.

The acceptance test is two structurally different toy optimizers
(`UppercaseInstructions`, `StaticDemoInjector`) that compile the same
two-predictor toy program through the *same* derived `Optimizable` instance with
no type-level knowledge of the program, plus a third program shape with a
predictor inside a `Vector` that round-trips. Both toy optimizers must pass
`OptimizerLaws`.

The `OptimizeError` ADT (`UnknownPath`, `FrozenPath`) stands alone in
`org.adk4s.optimize` per the cross-phase convention (recommendation: stand
alone, bridge to `AdkError` later) — to be settled in `design.md`.

The surface API is declared **frozen** in this change's `design.md`: any later
change to it requires its own proposal.

## Correctness Risk Level

**Risk**: medium — the surface is load-bearing for Phases 2–4 and effectively
irreversible once optimizers build on it; the `Mirror`-based derivation has
ergonomics risk (mixed leaf/subtree/`Vector` rule) that must be prototyped
before spec freeze, and the purity / frozen-path / round-trip laws are the only
guard against silent structural drift in later phases.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, `-Wconf` exhaustiveness
      escalation (the new `OptimizeError` enum and any sealed hierarchy must be
      matched exhaustively everywhere from day one).
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover (`Any`, `throw`, `var`,
      `isInstanceOf`/`asInstanceOf` excluded-set interactions to verify against
      the `Mirror` derivation), OrganizeImports.
- [ ] Ring 2: Architecture — new module `adk4s-optimize` added to the module
      graph; `openspec/project.md` and `capability-profile.md` updated in this
      change. (Ring 2 is not a configured gate in this repo's profile; recorded
      for the apply phase to update the module graph artifacts.)
- [x] Ring 3: Property-based tests — MANDATORY. Hedgehog properties for: R0.1
      declaration-order enumeration; R0.2 nested + `Vector` recursion with
      prefixed paths; R0.3 purity (before-snapshot unchanged after `update`);
      R0.4 `UnknownPath` error path; R0.5 frozen exclusion from `updateAll` +
      `FrozenPath` on direct `update`; R0.6 `update(p, path, identity) == p`
      round-trip; R0.7 both toy optimizers compile the same program through one
      `Optimizable` instance; R0.8 `OptimizerLaws` (student unchanged, frozen
      bit-identical, path-set preserved). **No concurrent behavior** in this
      change — the surface is pure and synchronous; no `TestControl` needed.
- [ ] Ring 4: Wire/persistence compatibility — NOT applicable. State is
      *serializable-ready* by shape but no serialization is implemented or
      round-tripped in Phase 0 (deferred to Phase 2 per scope).
- [ ] Ring 5: Mutation testing — deferred. The changed production logic is
      `Mirror`-derived boilerplate + pure data; Stryker4s is available but the
      high-value logic is covered by the laws suite. May be re-targeted in
      `design.md` if a non-trivial helper emerges.
- [x] Ring 6: Formal verification — APPLIES, via the VERIFIED-MIRROR pattern
      (schema v10, `templates/verified-mirror.md`). The earlier "not
      applicable" rationale was a statement about the shipped code's TYPES
      (`Mirror`/`inline`/`ujson.Value`) and about the Scala-version pin —
      neither is grounds to skip. The *algorithm* under
      `optimizable-surface/predictors` is a pure pre-order traversal of a
      field tree, and it has a pure kernel once inputs are reduced to
      observable effect: a program becomes a tree of
      predictor / plain / sub-program / collection nodes, and a path becomes
      a `List[BigInt]` of child indices. `PredictorKernel` (in the existing
      `verified` leaf module, pinned to 3.7.2) proves declaration-order
      enumeration, non-predictor exclusion, path-set preservation under
      `updateAll`, and round-trip identity. The shipped `Optimizable` is
      bound to that model by a MANDATORY bridge property test
      (`PredictorModelBridgeSpec`) running both on the same generated
      programs — without it the proof would be about a program we do not
      ship. Build wiring: `adk4s-optimize dependsOn(verified % Test)` (TASTy
      is backward compatible, so a 3.8.4 module may read the 3.7.2 artifact).
      Laws whose VCs diverge in z3 are DELEGATED to their named Ring 3
      property, never dropped.
- [ ] Ring 7: Model checking — NOT applicable (no distributed/event-driven
      invariants).
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY. Fresh-context
      reviewer checks every requirement against the implementation diff for:
      silent fallback mappings in `update` (e.g. `case _ => p`), `case _`
      defaults that swallow `UnknownPath`/`FrozenPath`, partial functions in
      derivation, invalid states constructible through public APIs (e.g. a
      `PredictorState` with `frozen = true` that `updateAll` still mutates),
      private helpers that violate the purity law.
- [ ] Ring 9: Telemetry — NOT applicable (no telemetry stack detected).

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract (full/minimal/waiver) | Justification |
|------|--------------------------------------|---------------|
| `specs/optimizable-surface/spec.md` | Full | Introduces a new module, a new typeclass (`Optimizable[P]`) with `Mirror`-based derivation semantics, new ADTs (`PredictorState`, `PredictorPath`, `Demo`, `OptimizeError`), a new leaf capability (`HasPredictorState`), and a new laws testkit (`OptimizerLaws`). This is the load-bearing erased surface for Phases 2–4 — full typed contract is mandatory. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `StructuredLLM[F[_]]` | service trait | `org.adk4s.structured.core` | `Predict0` placeholder wraps `completeTemplate`; reused as-is, not extended. |
| `Prompt` | case class | `org.adk4s.structured.core` | `Predict0` carries a `Prompt`-shaped template; reused as-is. |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` | `Predict0` output schema; reused as-is. |
| `ujson.Value` | JSON value | (upickle/ujson, transitive) | `Demo.input`/`Demo.output` and serializable-ready state payloads — repo standard JSON, NOT circe. |
| Hedgehog `HedgehogSuite` / `property` | property test kit | `hedgehog.munit` | `OptimizerLaws` and all Ring 3 properties extend this (project convention; NOT ScalaCheck). |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` | Test base classes (NOT ScalaTest). |
| `AdkError` conventions | sealed trait (error modeling pattern) | `org.adk4s.core.error` | Pattern reference for the new `OptimizeError` ADT — `OptimizeError` stands alone in `org.adk4s.optimize` per the cross-phase convention (bridge to `AdkError` later); to be settled in `design.md`. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `Demo` | case class | One input/output pair (`ujson.Value`, `ujson.Value`) — the few-shot example unit carried in `PredictorState`. |
| `PredictorState` | case class | Pure, serializable-ready view of one LM-call site's tunable state: `instructions: String`, `demos: Vector[Demo]`, `frozen: Boolean`. |
| `PredictorPath` | case class | Stable address of a predictor inside a program: `segments: Vector[String]` (case-class field names, outermost first); `render: String` for traces/logs. |
| `Optimizable[P]` | typeclass | The optimizer-facing capability of a program type `P`: `predictors`, `update`, `updateAll`. With `inline def derived` via `Mirror.ProductOf[P]`. |
| `HasPredictorState[Self]` | typeclass | Leaf capability: anything that *is* a predictor exposes `state`/`withState`. `Predict0` implements it. |
| `Predict0[F, I, O]` | case class (placeholder) | Minimal predictor wrapping `StructuredLLM.completeTemplate`, enough to carry `PredictorState`. Replaced by real `Predict` in Phase 2. |
| `OptimizeError` | enum (extends Throwable) | `UnknownPath(path: PredictorPath)`, `FrozenPath(path: PredictorPath)`. Stands alone in `org.adk4s.optimize`. |
| `OptimizerLaws` | testkit (munit main-scope) | Laws any `P => F[P]` optimizer must satisfy: student unchanged, frozen bit-identical, path-set preserved. Pattern follows `AgentMemoryLaws`. |
| `UppercaseInstructions` | toy optimizer (test only) | Rewrites every instruction string; derivation exerciser. |
| `StaticDemoInjector` | toy optimizer (test only) | Appends a fixed `Demo` to every predictor; derivation exerciser. |
| `optimizable-surface` (concept doc) | concept entry | `openspec/concepts/optimizable-surface.md` — the typeclass + derivation semantics. |
| `predictor-state` (concept doc) | concept entry | `openspec/concepts/predictor-state.md` — the state shape and frozen-flag semantics. |

## Risks and Mitigations

- **`Mirror` + `inline` derivation ergonomics** for the mixed leaf/subtree/`Vector`
  rule on Scala 3.8.4. *Mitigation*: prototype the derivation before spec freeze
  (⚠ VERIFY item 1 in the plan); fallback is a macro-free
  `summonInline`-chain derivation. Resolved in `design.md`.
- **Surface irreversibility** — once Phases 2–4 build on `Optimizable[P]`,
  changing it breaks every optimizer. *Mitigation*: freeze the API in
  `design.md` with a "any later change requires its own proposal" rule; cover
  the shape with `OptimizerLaws` so structural drift is caught.
- **`HasPredictorState` vs `Predictor[F]` supertrait** (⚠ VERIFY item 2) —
  interaction with `-source:future` and WartRemover `Any` exclusions.
  *Mitigation*: settle in `design.md` after prototyping both shapes.
- **`updateAll` semantics over entirely-frozen subtrees** (⚠ VERIFY item 3) —
  *Mitigation*: adopt the plan's recommendation that `frozen` is per-leaf only;
  subtree freezing is a Phase-2 `CompiledState.freeze` concern. Document in
  `design.md`.
- **`update` error channel shape** (R0.4 DECISION) — total-on-valid-paths vs
  `Either[OptimizeError, P]`. *Mitigation*: spec-lint demands the error path
  either way; adopt the safe `updateEither` as the public variant with
  `update` as the total-on-known-paths convenience. Settle in `design.md`.
- **WartRemover `Any` exclusion** — `ujson.Value` and `Mirror`-derived code may
  trip the temporarily-excluded `Any` wart. *Mitigation*: verify in Ring 1; if
  the wart fires, narrow the exclusion or use a typed wrapper rather than
  re-enabling `Any` broadly.
