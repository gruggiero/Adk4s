# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-07-24
**Verification result**: CLEAN on versions/modules/libraries; 7 stale "this change" remarks pruned (the profile was seeded by the archived `add-memory-orchestration-hook` change and still carried its change-specific annotations). No build-version corrections needed — `project/Versions.scala`, `project/Dependencies.scala`, and `build.sbt` all match the profile (Scala 3.8.4 / 3.7.2, sbt 1.12.12, cats-effect 3.7.0, fs2 3.13.0, llm4s 0.3.4, workflows4s 0.6.2, smithy4s 0.18.55, munit 1.3.3, munit-cats-effect 2.2.0, Hedgehog 0.13.1, 8 modules).

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Fatal warnings note | "This change extends the sealed `AgentEvent` ADT, so the new variant MUST be handled..." | Generalized to "Any change extending a sealed ADT (e.g. `AgentEvent`, `AdkError`) MUST handle the new variant..." | build.sbt scala3Options (unchanged); stale annotation pruned |
| Deterministic concurrency kit note | "This change touches INTERRUPT/RESUME semantics, so its concurrency scenarios MUST use `TestControl`..." | Generalized to "Any change touching concurrency/timeouts/cancellation/interruption MUST use `TestControl`..." | cats-effect 3.7.0 (unchanged); stale annotation pruned |
| Formal verification note | "NOT applicable to this change (the hook is effectful `IO` wiring...)" | Generalized to "Applicable only to PureScala modules in `verified`; effectful `IO` wiring is NOT a fit." | stale annotation pruned |
| Typed Contract Placement | "This change's contracts land in `adk4s-orchestration/.../memory/typecontract/`..." | Removed the change-specific path; kept the generic pattern + compile command | stale annotation pruned |
| Domain Purity `org.adk4s.orchestration.memory` row | "(NEW — this change)" | "(memory orchestration hook)" | stale annotation pruned |
| Domain Purity prose | "The new `org.adk4s.orchestration.memory` package is the key Ring 2 boundary this change introduces..." | "The `org.adk4s.orchestration.memory` package is a Ring 2 boundary..." | stale annotation pruned |
| Ring Availability Summary (R0/R2/R4/R5/R6 rows) | change-specific notes ("the new `AgentEvent` variant...", "Retarget mutate list to `**/memory/MemoryHook.scala`...", "Ring 6 skipped for this change", etc.) | Generalized project-scoped notes | stale annotations pruned |
| Compile & Test Commands (single test / mutation / Ring 6 rows) | change-specific commands (`testOnly org.adk4s.orchestration.memory.MemoryHookSpec`, `sbt "adk4s-orchestration/stryker4s"`, "NOT used by this change") | Generic placeholders (`<fully.qualified.Spec>`, `<module>/stryker4s`, "PureScala `verified` module only") | stale annotations pruned |

## Capabilities THIS change introduces

<!-- These are appended to the project profile when the change implements them
     (apply Step 12). Recorded here so the apply phase knows the build-dependency
     delta and the spec/typed-contract placement. Cross-checked against the
     proposal's "New Concepts to Introduce" and "What Changes" sections. -->

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `adk4s-optimize` module | new sbt subproject (package `org.adk4s.optimize`) | proposal §Approach, §What Changes; spec `optimizable-surface` |
| `adk4s-optimize` → `structured-llm` dependency | new module-graph edge | proposal §Approach (depends on `structured-llm`, cats-effect, fs2); apply Step 12 updates `build.sbt` + `project/Dependencies.scala` |
| `Optimizable[P]` typeclass + `Mirror` derivation | new domain typeclass | proposal §New Concepts; spec `optimizable-surface` |
| `HasPredictorState[Self]` typeclass | new leaf capability | proposal §New Concepts; spec `optimizable-surface` |
| `PredictorState` / `PredictorPath` / `Demo` | new case classes | proposal §New Concepts; spec `optimizable-surface` |
| `Predict0[F, I, O]` placeholder | new case class (placeholder predictor) | proposal §New Concepts; spec `optimizable-surface` |
| `OptimizeError` enum (`UnknownPath`, `FrozenPath`) | new error ADT (stands alone in `org.adk4s.optimize`, NOT extending `AdkError` — to be settled in `design.md`) | proposal §New Concepts, §Risks; spec `optimizable-surface` |
| `OptimizerLaws` testkit | new main-scope munit laws suite (pattern follows `AgentMemoryLaws`) | proposal §New Concepts; spec `optimizable-surface` |
| `org.adk4s.optimize` Ring 2 purity rule | new layer boundary | apply Step 12 appends a row to the profile's Domain Purity Rules |

**Note on `OptimizeError` vs `AdkError`**: the cross-phase convention in `docs/dspy-port-operative-plan.md` recommends that new error ADTs stand alone in their module and bridge to `org.adk4s.core.error.AdkError` later. This is a `design.md` decision item (proposal ⚠ VERIFY). The capability-check records the recommendation; the spec will state the chosen shape.

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 Compile | yes | `sbt adk4s-optimize/compile` once the module is added (apply Step 12). Exhaustiveness escalation applies to the new `OptimizeError` enum — every match over it must be exhaustive from day one. |
| R1 Lint | yes | Scalafix + WartRemover + scalafmt. ⚠ VERIFY: `Mirror`-derived code and `ujson.Value` payloads may trip the temporarily-excluded `Any`/`AsInstanceOf` warts — confirm in Ring 1 during apply; narrow exclusions rather than re-enabling broadly. |
| R2 Architecture | advisory only | No custom scalafix arch rules. The new `org.adk4s.optimize` package boundary is manual (code review + import audit). Apply Step 12 adds the purity-rule row: `org.adk4s.optimize` MAY import cats-effect, fs2, structured-llm, ujson; MUST NOT import workflows4s, llm4s LLM client, adk4s-orchestration, adk4s-core.tools. |
| R3 Property tests | yes (MANDATORY) | Hedgehog 0.13.1 via hedgehog-munit. Properties for R0.1–R0.8 + `OptimizerLaws`. **No concurrent behavior** in this change — the surface is pure and synchronous; `TestControl` is NOT required. |
| R4 Wire/persistence | N/A | State is serializable-ready by shape but no serialization is implemented or round-tripped in Phase 0 (deferred to Phase 2 per scope). No fixtures touched. |
| R5 Mutation | available, deferred | sbt-stryker4s 0.21.0 + stryker4s.conf present. Retarget `mutate` list to `adk4s-optimize` changed files. Deferred: the production logic is `Mirror`-derived boilerplate + pure data covered by the laws suite; may be re-targeted in `design.md` if a non-trivial helper emerges. |
| R6 Formal | N/A | Stainless is available but scoped to the `verified` PureScala module. `Optimizable` uses `Mirror`/`inline` and `ujson.Value` — not a PureScala fit. |
| R7 Model checking | no | No TLA+/Apalache. Skip with stated impact (no distributed/event-driven invariants in this change). |
| R8 Adversarial review | yes (MANDATORY) | Fresh-context reviewer; runs BEFORE R5/R6/R7. Checks for silent fallbacks in `update`, `case _` defaults swallowing `UnknownPath`/`FrozenPath`, partial functions in derivation, frozen-flag bypass via public APIs. |
| R9 Telemetry | no | No otel4s/Daut. Skip with stated impact (no API operations/event sequences affected). |
| Concurrency kit | yes (not needed) | `TestControl` available via cats-effect 3.7.0 but NOT required — this change has no concurrent behavior. |
| Code intelligence | yes | Metals MCP endpoint at `http://localhost:8394/mcp` (per-project, startable via `scanner/metals-start.sh`). Apply phase prefers semantic recipes (impact-scan, removal-audit) for the new module; git grep is the fallback and the only CI tool. Semantic answers trusted only post-compile. |
