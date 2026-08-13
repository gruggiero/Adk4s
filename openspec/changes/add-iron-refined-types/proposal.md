# Proposal: Add Iron Refined Types (Tier A + B)

## Why

ADK4S models domain identifiers and configuration parameters as plain `String`
opaque types and raw `Int`s, with invariants enforced ad hoc — by hand-rolled
`Either`-returning smart constructors (`NodeKey.apply`/`from`), by a single
`require` that throws (`MemoryPolicy.recallK`), or **not at all**
(`MiddlewareName`, `StateCell.CellId`, `CheckpointStore.CheckpointId`, which
accept any `String` including the empty string). The project's own living
documents already anticipate a refined-types library:

- `openspec/concept-inventory.md` carries an "Iron/refined" constraint column
  in which every opaque type is currently recorded as
  `(none — plain opaque type)`.
- `openspec/capability-profile.md` records `Refined types | none | — | No
  Iron/refined`.
- The `typed-contract.scala` template already emits Iron-style scaffolding
  (`opaque type X = Underlying :| Constraint`, `RefinedTypeOps`).

Adopting Iron closes the gap these documents describe: it turns implicit
invariants into **types** (compile-time checked for literals, explicit
`refineEither` for runtime values), eliminates the duplicated
`apply`/`from`/`unsafeApply` constructors on `NodeKey`, replaces the throwing
`require` on `MemoryPolicy` with a typed `Either`, and gives the four
identifier newtypes a uniform, codec-compatible smart-constructor surface.
Iron's `A :| C` is a subtype of `A`, so refined values flow into existing
`String`/`Int` APIs and variance works without extra typeclass wiring — a
direct fit for a library that serializes these identifiers through upickle
(`iron-upickle` exists) and compares them with Cats (`iron-cats` exists).

## What Changes

This change introduces the Iron library (`io.github.iltotore::iron` 3.3.1) and
migrates the identifier newtypes (Tier A) and numeric/structural invariants
(Tier B) to refined types. It is a **type-safety + API-hardening** change: the
runtime behavior of valid inputs is preserved; invalid inputs that today throw
or silently pass are rejected at the type/boundary level with typed errors.

### Tier A — Identifier newtypes become Iron refined opaque types

- `NodeKey` (`adk4s-core`): `String :| (NonEmpty & Not[Reserved])`. The
  duplicated `apply`/`from`/`unsafeApply` constructors collapse into
  `RefinedTypeOps`. The reserved `START`/`END` values are handled via a
  distinct `ReservedNodeKey` type (they are intentionally *outside* the
  general constraint).
- `MiddlewareName` (`adk4s-harness-api`): `String :| NonEmpty` (currently
  unvalidated — an empty name silently yields `"owner/"` cell ids).
- `StateCell.CellId` (`adk4s-harness-api`): `String :| NonEmpty` with a format
  constraint (`DescribedAs`/`Match["[^/]+/[^/]+"]`); constructed via
  `refineEither` at the `CellId.apply` concatenation site.
- `CheckpointStore.CheckpointId` (`adk4s-orchestration`): promoted from a
  **transparent** `type CheckpointId = String` alias (which accepts any
  `String`) to an `opaque type CheckpointId = String :| NonEmpty`.

### Tier B — Numeric bounds + typed graph-compilation errors

- **Numeric bounds** on configuration/loop parameters, refined at the
  construction boundary:
  - `MemoryPolicy.recallK`: `Int :| NonNegative` — the existing
    `require(recallK >= 0, …)` throw is replaced by `refineEither`, returning
    a typed `ConfigError` instead of throwing.
  - `ToolsNodeConfig.maxConcurrency`: `Int :| Positive` (default `10`).
  - `ReactAgent`/`AgentRunner`/`HarnessAgent` `maxSteps`: `Int :| Positive`
    (default `10`). **Public API parameters stay `Int`** in this change
    (conservative strategy); refinement happens at the internal boundary via
    `refineEither`, returning `Either[ConfigError, …]`. This keeps the blast
    radius to examples/tests low. (A follow-up change may promote the public
    signatures.)
  - `StructuredLLM` `maxParseAttempts`: `Int :| Positive`.
- **Typed graph-compilation errors**: the active `GraphExecutor.execute` path
  currently folds `graph.compile(...)` and raises `new Exception("Graph
  validation failed: …")` via `IO.raiseError`. This change introduces a new
  `AdkError` variant `GraphCompilationError(errors: List[GraphValidationError])`
  and raises that instead of the generic `Exception`. Additionally, the
  **commented-out** `toWIO`/`compileFromNodeUnsafe` blocks (lines 22-36 and
  70-162 of `GraphExecutor.scala`) containing raw `throw new
  IllegalStateException`/`IllegalArgumentException` calls are **deleted** as
  dead code — they are not part of any active path and contradict the
  no-raw-throw discipline. A `ValidatedGraph` refined type
  (`Graph :| GraphValidated`) is introduced so that `executeGraph`/
  `executeGraphParallel` receive a graph already proven valid by
  `graph.compile`, eliminating redundant re-validation.

### Affected Capabilities

- `specs/core-types/spec.md` — `NodeKey` refined type, `ReservedNodeKey`,
  `ConfigError` variant for invalid identifiers.
- `specs/harness-state/spec.md` — `MiddlewareName` and `StateCell.CellId`
  refined types.
- `specs/checkpoint-store-fpoly/spec.md` — `CheckpointId` opaque refined type
  (promoted from transparent alias).
- `specs/tools-node/spec.md` — `maxConcurrency` refined bound.
- `specs/react-agent/spec.md` — `maxSteps` internal refinement boundary.
- `specs/memory-orchestration-hook/spec.md` — `MemoryPolicy.recallK` refined
  bound, `require` → `refineEither`.
- `specs/structured-llm/spec.md` — `maxParseAttempts` refined bound.
- `specs/wio-graph/spec.md` — `GraphCompilationError` variant,
  `ValidatedGraph` refined type, dead-code deletion.
- `specs/error-hierarchy-dedup/spec.md` — new `GraphCompilationError` and
  `ConfigError` (if not already present) `AdkError` variants.

### Out of Scope

- **`verified` module**: Iron is NOT added to the Stainless leaf module. It
  pins Scala 3.7.2 for the Stainless frontend and runs with
  `wartremoverErrors := Seq.empty` + `semanticdbEnabled := false`; Iron's
  `FromExpr` compile-time mechanism is incompatible with the Stainless
  frontend. The module has no opaque types needing refinement anyway.
- **Public signature changes** for `maxSteps: Int` → `maxSteps: Int :| Positive`
  on `ReactAgent`/`AgentRunner`/`HarnessAgent`. Kept as `Int` in this change
  (refine internally); a follow-up change may promote them.
- `CompletionOptions` fields (`temperature`, `topP`, `maxTokens`) — owned by
  the external `llm4s` library; not refinable from ADK4S.
- `FieldPath`, `RunPath`, `ToolSchema[A]`, `Schema[A]` — structural/collection
  newtypes with no meaningful scalar constraint; left as plain opaque types.
- `AddressSegment.Agent/Tool(name)` — secondary; names inherit safety from the
  upstream tool/agent definitions. Not refined in this change.
- Ring 6 (Stainless) mirrors for the refined-type constraints — Iron types are
  unverifiable in Stainless by construction; the numeric-bound *logic*
  (`>= 0`, `> 0`) is trivial and not worth a mirror. Stated explicitly.

## Approach

1. **Dependency wiring**: add `iron` (3.3.1), `iron-cats`, and `iron-upickle`
   to `project/Versions.scala` + `project/Dependencies.scala`; add to
   `adk4s-core`, `adk4s-harness-api`, and `adk4s-orchestration`. Compile-spike
   on Scala 3.8.4 first to verify the `_3` binary resolves and Iron's
   compile-time macros cooperate with the repo's `-Wconf`/WartRemover setup
   (this is INFERRED-compatible from Iron's docs; the spike makes it VERIFIED).
2. **Tier A migrations** (one spec each): rewrite each identifier newtype as
   `opaque type X = Underlying :| Constraint` with `RefinedTypeOps`. Preserve
   the `.value` extension and Cats `Eq`/`Show`/`Order` instances (via
   `iron-cats` derivation or explicit delegation). Update call sites that
   construct via the old `apply`/`from` to use `refineEither` where the input
   is runtime-derived, and direct literal construction where compile-time
   checkable.
3. **Tier B numeric**: introduce a `ConfigError` `AdkError` variant (or reuse
   if present). At each boundary (`MemoryPolicy.apply`,
   `ToolsNodeConfig.parallel`, `ReactAgent.create` internal, `StructuredLLM`
   factory), replace `require`/silent-acceptance with `refineEither` returning
   `Either[ConfigError, T]`. Public constructors that currently return `T`
   directly and rely on `require` throwing gain an `Either`-returning overload;
   the throwing overload is kept for source compatibility but delegates to
   `refineUnsafe` (explicit, attributable).
4. **Tier B graph**: add `GraphCompilationError` to the `AdkError` hierarchy;
   replace `IO.raiseError(new Exception(...))` in `GraphExecutor.execute`/
   `executeWithError` with `IO.raiseError(GraphCompilationError(errors))`;
   delete the commented-out `toWIO`/`compileFromNodeUnsafe` blocks; introduce
   `ValidatedGraph` so `executeGraph` takes a proven-valid graph.
5. **Inventory + profile update**: update `openspec/concept-inventory.md`
   constraint column and `openspec/capability-profile.md` "Refined types" row
   to reflect Iron's presence.

## Correctness Risk Level

**Risk**: medium — The change touches public identifier types used across
serialization boundaries (`NodeKey`, `CheckpointId` round-trip through upickle
and `JsonValue`), and replaces one throwing constructor (`MemoryPolicy.require`)
with a typed `Either`, which is a behavior change observable by callers that
caught `IllegalArgumentException`. No money/arithmetic/evaluator logic is
altered; valid-input behavior is preserved by construction (Iron's `A :| C` is
a subtype of `A`). The medium rating reflects the serialization-round-trip
surface and the `require` → `Either` semantic shift, both of which Ring 3
property tests and Ring 4 round-trip tests must pin down.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types, exhaustiveness
      `-Wconf` escalations (extending `AdkError` triggers the sealed-match
      escalation across every existing `AdkError` match).
- [x] Ring 1: Lint — Scalafix `DisableSyntax` (noThrows — the `require` removal
      helps; verify no new `throw`), WartRemover (Iron's `:|` is a plain type
      alias — no `Any`/`isInstanceOf`), `danger-scan.sh`.
- [ ] Ring 2: Architecture — verify Iron is NOT added to the `verified` module
      (Ring 2 purity rule); verify `ujson` confinement rules still hold
      (`iron-upickle` bridges at the existing upickle boundary only).
- [x] Ring 3: Property-based tests — MANDATORY. Hedgehog properties for:
      (a) `refineEither` round-trip — `x.refineEither[C].map(_.value) == Right(x)`
      for valid inputs; `Left` for invalid; (b) `NodeKey` reserved-value
      exclusion; (c) `MemoryPolicy.recallK` non-negative invariant (replaces
      the `require` throw with a typed `Left`); (d) `ValidatedGraph` —
      `graph.compile` success implies `executeGraph` does not re-validate.
      No concurrent behavior is introduced by this change (refinement is pure;
      the `IO.raiseError` swap is in an already-sequential fold), so no
      `TestControl` scenarios are required — stated explicitly.
- [x] Ring 4: Wire/persistence compatibility — REQUIRED. `NodeKey`,
      `CheckpointId`, `MiddlewareName`, `CellId` all serialize through
      upickle/`JsonValue`. Properties: `decode(encode(x)) == Right(x)` for
      refined values; old fixtures/snapshots containing these identifiers must
      still decode (Iron's subtype-of-underlying property guarantees this, but
      it MUST be verified, not assumed). `iron-upickle` integration is the
      mechanism.
- [x] Ring 5: Mutation testing — Stryker4s on the changed boundary files
      (`NodeKey.scala`, `MemoryPolicy.scala`, `GraphExecutor.scala`,
      `ToolsNodeConfig.scala`), threshold 90% (pure domain logic). Retarget
      `stryker4s.conf` `mutate` list per spec.
- [ ] Ring 6: Formal verification — **waived with rationale**: Iron's
      `:|`/`FromExpr`/`RefinedTypeOps` are unverifiable in Stainless (opaque
      types + inline macros), and the `verified` module is explicitly excluded
      from this change. The numeric-bound logic (`>= 0`, `> 0`) is trivial and
      not worth a PureScala mirror. No decision/fold/law at the centre of this
      change benefits from formal proof. Waiver stated per schema rules.
- [ ] Ring 7: Model checking — no TLA+/Apalache in the stack; skip.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY (fresh-context
      reviewer; runs BEFORE Rings 5/6/7). Critical for this change: confirm
      that the `require` → `refineEither` migration does not introduce a
      silent-fallback (`case _ => default`) that lets invalid `recallK`
      through, and that `ValidatedGraph` cannot be constructed without
      `graph.compile` succeeding.
- [ ] Ring 9: Telemetry — no otel4s/Daut in the stack; skip.

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
| `specs/core-types/spec.md` | Full | New `NodeKey` refined opaque type, `ReservedNodeKey` newtype, `ConfigError` variant — public type + error algebra change. |
| `specs/harness-state/spec.md` | Full | `MiddlewareName` and `StateCell.CellId` refined opaque types — public type change on a serialized boundary. |
| `specs/checkpoint-store-fpoly/spec.md` | Full | `CheckpointId` promoted from transparent alias to opaque refined type — public type change. |
| `specs/tools-node/spec.md` | Full | `maxConcurrency` refined bound + `ConfigError` return — public config constructor change. |
| `specs/react-agent/spec.md` | Minimal | `maxSteps` refinement is internal-only (public param stays `Int`); signatures of touched internal boundary only. |
| `specs/memory-orchestration-hook/spec.md` | Full | `MemoryPolicy.apply` `require` → `refineEither` — public constructor semantic change (throw → `Either`). |
| `specs/structured-llm/spec.md` | Minimal | `maxParseAttempts` internal refinement boundary; signatures of touched factory only. |
| `specs/wio-graph/spec.md` | Full | New `GraphCompilationError` variant + `ValidatedGraph` refined type + dead-code deletion — error algebra + public type change. |
| `specs/error-hierarchy-dedup/spec.md` | Full | New `GraphCompilationError` and `ConfigError` `AdkError` variants — error algebra extension (triggers exhaustiveness escalation across all `AdkError` matches). |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `NodeKey` | opaque type (String) | `org.adk4s.core.types` | Migrate to Iron refined; keep `.value`, `Eq`/`Show`/`Order`. |
| `FieldPath` | opaque type (Vector[String]) | `org.adk4s.core.types` | Reuse as-is (no scalar constraint). |
| `MiddlewareName` | opaque type (String) | `org.adk4s.harness` | Migrate to Iron refined (add `NonEmpty`). |
| `StateCell.CellId` | opaque type (String) | `org.adk4s.harness` | Migrate to Iron refined (add `NonEmpty` + format). |
| `CheckpointStore.CheckpointId` | transparent type alias (String) | `org.adk4s.orchestration.interrupt` | Promote to opaque refined. |
| `AdkError` | sealed trait | `org.adk4s.core.error` | Extend with `GraphCompilationError`, `ConfigError`. |
| `NodeKeyError` | case class (AdkError) | `org.adk4s.core.error` | Reuse/align with `ConfigError` for invalid identifiers. |
| `MemoryPolicy` | case class | `org.adk4s.orchestration.memory` | `recallK` field + `require` → refined. |
| `ToolsNodeConfig` | case class | `org.adk4s.core.tools` | `maxConcurrency` → refined. |
| `Graph` / `GraphValidationError` | case class / sealed | `org.adk4s.orchestration.graph` | `compile` returns `Validated`; feed into `ValidatedGraph`. |
| `GraphExecutor` | object | `org.adk4s.orchestration.execution` | `execute`/`executeWithError` error path; delete dead commented blocks. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `NodeKey` (refined) | opaque type `String :\| (NonEmpty & Not[Reserved])` | Compile-time/runtime-enforced non-empty, non-reserved node key. |
| `ReservedNodeKey` | opaque type / enum | The `START`/`END` reserved values, distinct from the general `NodeKey` constraint. |
| `MiddlewareName` (refined) | opaque type `String :| NonEmpty` | Non-empty middleware identity. |
| `StateCell.CellId` (refined) | opaque type `String :| (NonEmpty & Match["…"])` | Format-checked cell identity. |
| `CheckpointId` (refined) | opaque type `String :| NonEmpty` | Non-empty checkpoint identifier (was transparent alias). |
| `ConfigError` | `AdkError` variant | Typed error for invalid configuration/identifier refinement failures (replaces `require` throws). |
| `GraphCompilationError` | `AdkError` variant | Carries `List[GraphValidationError]`; replaces generic `Exception` in `GraphExecutor`. |
| `ValidatedGraph` | refined type alias `Graph :| GraphValidated` | Proof-carrying graph that has passed `graph.compile`; consumed by `executeGraph`. |
| `Positive`/`NonNegative` constraint aliases | type aliases | Iron numeric constraints reused across `maxSteps`/`recallK`/`maxConcurrency`/`maxParseAttempts`. |

## Risks and Mitigations

- **Iron / Scala 3.8.4 compile compatibility** (INFERRED, not yet verified):
  the `_3` binary should resolve on 3.8.4 by Scala 3 binary-compat convention,
  but Iron's `FromExpr` compile-time macros have not been tested under this
  repo's exact `-Wconf`/WartRemover configuration. **Mitigation**: the first
  implementation task is a compile-spike (add dep + refine `NodeKey` only) that
  turns the inference into a verified fact before any further migration.
- **Serialization round-trip regression**: `NodeKey`/`CheckpointId`/
  `MiddlewareName`/`CellId` flow through upickle and `JsonValue`. Iron's
  subtype-of-underlying property means a refined `String :| C` serializes as a
  `String`, but the `iron-upickle` codec must be wired and round-trip-tested
  against existing fixtures. **Mitigation**: Ring 4 properties + existing
  snapshot tests.
- **`require` → `refineEither` semantic shift**: callers of
  `MemoryPolicy.apply` that caught `IllegalArgumentException` will no longer
  see it; they get `Left(ConfigError)`. **Mitigation**: keep a
  `refineUnsafe`-backed throwing overload for source compatibility; document
  the deprecation; Ring 8 adversarial review confirms no silent fallback.
- **Exhaustiveness escalation**: adding `GraphCompilationError` and
  `ConfigError` to `AdkError` will fail compilation on every non-exhaustive
  `AdkError` match (Ring 0 `-Wconf` rule). **Mitigation**: this is the
  intended safety mechanism; the implementation task list includes updating
  all affected matches.
- **`verified` module isolation**: Iron must not leak into the Stainless leaf.
  **Mitigation**: add Iron only to `adk4s-core`/`adk4s-harness-api`/
  `adk4s-orchestration`; Ring 2 verifies the `verified` module's dependency
  list is unchanged.
