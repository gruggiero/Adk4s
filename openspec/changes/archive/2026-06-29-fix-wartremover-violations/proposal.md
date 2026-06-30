# Proposal: Incrementally Re-enable Excluded WartRemover Warts

## Why

The build (`build.sbt` lines 18–43) configures WartRemover Ring 1 as
`Warts.unsafe` minus nine temporarily-excluded warts:

```
TripleQuestionMark, Any, DefaultArguments, IterableOps, AsInstanceOf,
Throw, Var, OptionPartial, StringPlusAny
```

A code comment documents *why* each is excluded and *how* to fix it. The
exclusions exist because the codebase predates WartRemover adoption — they
are technical debt, not permanent policy. `TripleQuestionMark` is the only
intentional, permanent exclusion (`???` is allowed for stubs).

Leaving these warts disabled means Ring 1 cannot catch regressions for the
most dangerous patterns (`asInstanceOf`, `throw`, `var`, `Any`). The
project's own `AGENTS.md` rules forbid `isInstanceOf`/`asInstanceOf`, `Any`,
`var`, and `throw` — the disabled warts are exactly the enforcement for
those rules. Re-enabling them incrementally aligns the linter with the
documented coding conventions and prevents new violations from accumulating.

## What Changes

This change re-enables the eight temporarily-excluded warts one at a time,
in order of increasing risk and blast radius. For each wart:

1. Refactor every violation in compiled main sources (and test sources where
   the wart is enabled for tests) so the code passes the wart.
2. Remove that wart from the `filterNot` exclusion list in `build.sbt`
   (both the `ThisBuild` block and the `adk4s-examples` override).
3. Update the explanatory comment block to drop the fixed entry.
4. Verify the full build compiles and the existing test suite still passes.

The `verified` module stays exempt (`wartremoverErrors := Seq.empty`) — it
is pinned to Scala 3.7.2 for Stainless and is not in scope.

### Affected Capabilities

- `specs/wartremover-option-partial/spec.md` — eliminate `.get` on `Option`
  in favor of `.getOrElse` / pattern matching / `fold`, then re-enable
  `Wart.OptionPartial`.
- `specs/wartremover-iterable-ops/spec.md` — replace `.init` / `.last` with
  `dropRight(1)` / `lastOption`, then re-enable `Wart.IterableOps`.
- `specs/wartremover-var/spec.md` — replace mutable `var` parsing state in
  `JsonFixMiddleware` (and any other main-source `var`) with a recursive or
  fold-based immutable approach, then re-enable `Wart.Var`.
- `specs/wartremover-throw/spec.md` — replace `throw new ...` in main
  sources with `F[Either[Err, A]]` / `AdkError` return paths (and
  `fail(...)` in tests), then re-enable `Wart.Throw`.
- `specs/wartremover-asinstanceof/spec.md` — replace `asInstanceOf` runtime
  type dispatch (erased types in `Tool`, `ToolInfer`, `ToolSchema`,
  `WIOGraph`, `WIONode`, `GraphExecutor`) with type-safe pattern matching
  or a sealed-algebra redesign, then re-enable `Wart.AsInstanceOf`.
- `specs/wartremover-default-arguments/spec.md` — make case-class default
  arguments explicit (factory methods on companion or explicit call-site
  args), then re-enable `Wart.DefaultArguments`.
- `specs/wartremover-any-string-plus-any/spec.md` — replace `s"..."`
  interpolations that trigger `Wart.Any` / `Wart.StringPlusAny` with `+`
  string concatenation per the accordant4s pattern, then re-enable both
  `Wart.Any` and `Wart.StringPlusAny`.

`TripleQuestionMark` remains excluded by design — it is **not** in scope.

### Out of Scope

- The `verified` module (Stainless, Scala 3.7.2, exempt by policy).
- The `adk4s-agent/` and `adk4s/` directories — not declared in `build.sbt`,
  not compiled, not linted by WartRemover. Any files there are ignored.
- Re-enabling `Wart.TripleQuestionMark` (permanent exclusion for `???`
  stubs).
- New features, API additions, or behavior changes — this is a pure
  refactor whose goal is behavior-preserving lint compliance.
- Changes to `.scalafix.conf` (separate Ring 1 tool; tracked independently).

## Approach

Each wart is a self-contained, independently shippable spec. Specs are
implemented **depth-first** per the verified-scala3 workflow: a spec is
fully refactored, compiled, lint-clean, and tested before the next spec
begins. This keeps the blast radius of any single regression small and
lets each re-enablement land as its own reviewable unit.

Ordering (lowest risk / smallest surface first):

1. **OptionPartial** — mechanical `.get` → `.getOrElse` / pattern-match
   replacements. Largest file count but lowest per-site risk.
2. **IterableOps** — `.init` → `dropRight(1)`, `.last` → `lastOption` (with
   defined fallback behavior).
3. **Var** — `JsonFixMiddleware` rewrite to recursive/fold-based parsing;
   a few test/example `var`s become `Ref[IO, _]` or `val` + recursion.
4. **Throw** — main sources switch to `AdkError` / `F[Either[Err, A]]`;
   tests switch to munit `fail(...)` / `intercept`.
5. **AsInstanceOf** — the highest-risk spec. Erased-type dispatch in
   `Tool`/`ToolInfer`/`ToolSchema` and the `WIOGraph`/`WIONode`/
   `GraphExecutor` hierarchy moves to pattern matching over a sealed
   algebra or a `Mirror`-based type-safe registry. May require a typed
   contract redesign (see Typed Contract Decision below).
6. **DefaultArguments** — case-class defaults become explicit companion
   factories or explicit call-site arguments.
7. **Any / StringPlusAny** — `s"..."` interpolations that widen to `Any`
   switch to `+` concatenation. Done last because it is the most
   widespread mechanical change and benefits from the earlier specs
   stabilizing the build.

After all seven specs land, the `build.sbt` comment block is reduced to a
single line documenting the permanent `TripleQuestionMark` exclusion.

## Correctness Risk Level

**Risk**: high — the `AsInstanceOf` and `Throw` specs touch runtime type
dispatch (`Tool`/`ToolInfer`/`ToolSchema`) and error control flow
(`WIOGraph`/`GraphExecutor`) that are central to tool execution and graph
compilation; a behavior-changing refactor there can silently break tool
calling or workflow execution. The remaining specs are low/medium
mechanical risk, but the change as a whole is rated high because of these
two specs.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern
      scan. **This is the primary ring for this change**: each spec's
      acceptance criterion is that its wart is removed from the exclusion
      list and the build is lint-clean.
- [ ] Ring 2: Architecture — project-specific layer dependencies, sealed
      domain types, effect discipline (the `AsInstanceOf` spec may touch
      sealed-algebra layering; revisit in design.md)
- [x] Ring 3: Property-based tests — MANDATORY. Each spec adds regression
      tests (Hedgehog properties or munit scenarios) proving behavior is
      preserved after refactor. For `AsInstanceOf` and `Throw`, property
      tests cover the previously-`asInstanceOf`-dispatched and
      previously-`throw`-ing code paths.
- [ ] Ring 4: Wire/persistence compatibility — not touched (no
      serialization, event, or wire-format changes).
- [x] Ring 5: Mutation testing — Stryker4s on changed production files
      (`Tool`, `ToolInfer`, `ToolSchema`, `JsonFixMiddleware`,
      `WIOGraph`, `WIONode`, `GraphExecutor`), threshold 90% (pure
      domain logic).
- [ ] Ring 6: Formal verification — not applicable (no `verified`-module
      changes).
- [ ] Ring 7: Model checking — not applicable.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY. Each spec is
      reviewed for behavior preservation, especially `AsInstanceOf` and
      `Throw`.
- [ ] Ring 9: Telemetry — not applicable (no otel4s; no API/event-sequence
      changes).

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
| `specs/wartremover-option-partial/spec.md` | Minimal | Mechanical `.get` → `.getOrElse`/pattern-match; no signature changes. |
| `specs/wartremover-iterable-ops/spec.md` | Minimal | Mechanical `.init`/`.last` → `dropRight(1)`/`lastOption`; no signature changes. |
| `specs/wartremover-var/spec.md` | Minimal | `JsonFixMiddleware` internal state rewrite; private implementation, no public signature change. |
| `specs/wartremover-throw/spec.md` | Full | Changes error control flow: `throw` → `AdkError`/`F[Either[Err, A]]` alters public error algebra of affected methods (e.g. `NodeKey.apply`, `Graph` validation, `GraphExecutor` dispatch). |
| `specs/wartremover-asinstanceof/spec.md` | Full | Erased-type dispatch redesign may introduce a new sealed algebra / type-safe registry; public signatures of `Tool`, `ToolInfer`, `ToolSchema`, `WIOGraph`, `WIONode`, `GraphExecutor` may change. |
| `specs/wartremover-default-arguments/spec.md` | Minimal | Defaults become explicit; no new types, signatures stable (callers supply args or use companion factory). |
| `specs/wartremover-any-string-plus-any/spec.md` | Minimal | `s"..."` → `+` concatenation; no signature changes. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `AdkError` | sealed trait hierarchy | `org.adk4s.core.error` | Target for `throw` → error-return refactors; reuse existing variants (`GenericError`, `NodeNotFoundError`, `EdgeValidationError`, `ToolExecutionError`) before adding new ones. |
| `AgentInterruptedException` | exception (to be phased out as control flow) | `org.adk4s.core.error` | Currently thrown across tool/agent boundary; `Throw` spec must preserve interrupt semantics via `F[Either[Err, A]]`. |
| `Tool` / `InvokableTool` / `StreamableTool` | sealed trait tier | `org.adk4s.core.component` | `AsInstanceOf` spec redesigns runtime dispatch on these. |
| `ToolWrapper` | case class (dual storage) | `org.adk4s.core.tools` | Stores `originalToolFunction` + `executable`; `AsInstanceOf` spec must keep both paths working. |
| `WIONode` | sealed trait | `org.adk4s.orchestration.wiograph` | `AsInstanceOf` dispatch site; pattern-match over existing sealed subtypes. |
| `NodeKey` | opaque type | `org.adk4s.core.types` | `Throw` spec: `NodeKey.apply` currently throws on empty input; switch to `Either[AdkError, NodeKey]`. |
| `SchemaAlignedParser` | object | `org.adk4s.structured.sap` | `Var` spec: `JsonFixMiddleware` shares the lenient-JSON-recovery family; reuse its recursive approach. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `NodeKeyError` (tentative) | case class extending `AdkError` | Returned by `NodeKey.from` when input is empty, replacing `throw`. Name finalized in design.md. |
| `ToolDispatchResult` (tentative) | sealed trait | Type-safe replacement for `asInstanceOf`-based runtime dispatch in `Tool`/`ToolInfer`/`ToolSchema`. Shape finalized in design.md; may be unnecessary if pattern matching over existing sealed types suffices. |

No new IDL operations, service methods, or wire formats are introduced.
Any new error variants reuse the existing `AdkError` sealed hierarchy.

## Risks and Mitigations

- **Risk**: `AsInstanceOf` refactor changes runtime dispatch semantics for
  tool calling, silently breaking tool execution.
  **Mitigation**: Full typed contract; property tests over the
  `Tool`/`InvokableTool`/`StreamableTool` tier; Stryker4s on
  `Tool`/`ToolInfer`/`ToolSchema`; adversarial review.
- **Risk**: `Throw` → `Either` refactor changes interrupt/resume control
  flow (`AgentInterruptedException` is currently thrown and caught across
  the tool/agent boundary).
  **Mitigation**: Preserve interrupt semantics via `F[Either[Err, A]]` with
  `AgentInterruptedException` modeled as an `AdkError` variant; regression
  tests from `AgentRunnerTest` / `ToolsNodeInterruptTest` must pass
  unchanged.
- **Risk**: `Var` rewrite of `JsonFixMiddleware` changes lenient JSON
  recovery behavior, breaking `StructuredLLM` parsing.
  **Mitigation**: Reuse `SchemaAlignedParser`'s recursive approach;
  regression tests from `SchemaSamplesParsingSuite` must pass.
- **Risk**: `DefaultArguments` removal breaks call sites that rely on
  defaults across 55 example files.
  **Mitigation**: Provide companion factory methods preserving the old
  defaults; compile all examples as part of each spec's verification.
- **Risk**: Incremental ordering causes a later spec to regress an earlier
  spec's wart (e.g. an `AsInstanceOf` fix introduces a `throw`).
  **Mitigation**: Depth-first workflow — each spec re-enables its wart in
  `build.sbt` before the next begins, so later specs cannot reintroduce a
  re-enabled wart without failing the build.
