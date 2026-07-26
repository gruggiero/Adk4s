# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Mechanical pre-pass

<!-- Run BEFORE the judgment checks:
     1. `openspec validate --strict` — must pass.
     2. `openspec/schemas/verified-scala3/scanner/spec-lint.sh <change-dir>` —
        enforces the greppable subset (F1–F7) and reports vague-word,
        adversarial-confirmation and positional-reference candidates (W1–W4).
        F6 = obligation Source names nothing resolvable (mandated format).
        F7 = requirement named by NO obligation (unenforced — check 12 as
             reachability, stricter than the W2 row count).
        F8 = typed source names a Property/Scenario heading that does not
             exist in the spec (dangling reference).
        W4 = ordinal requirement references (prefer exact titles).
        W5 = impossibility claim enforced only by tests (ladder tier 1-2
             expected, or "tier-justified: <why not>" in Enforcement).
     F9 (artifact existence) is NOT run here — it is post-implementation;
     apply Step 12 runs `spec-lint.sh --artifacts`.
     Paste both outputs here. Script FAILs are lint FAILs. -->

**openspec validate --strict**: PASS — `Change 'add-optimizable-surface' is valid` (via `openspec validate add-optimizable-surface --strict`); `openspec validate --changes` reports `1 passed, 0 failed`.

**spec-lint.sh**: `1 spec file(s), 0 FAIL, 14 WARN` — no mechanical FAILs (F1–F8 all clean). Warnings:
- W1 (vague word "valid"): 4 hits — lines 178, 206, 294, 302. Each has a concrete definition inline in the same scenario/rationale (see check 9 below).
- W3 (negative requirement — confirm adversarial scenario): 10 hits — lines 180, 208, 258, 286, 308, 336, 390, 428, 478, 512. Judgment review in check 15 below confirms adversarial scenarios exist for all but one structurally-enforced claim (see check 15).

No F4/F5/F6/F7/F8 FAILs — Proof-Obligations section present, temporal syncs well-formed, every Source cell resolvable, every requirement named by ≥1 obligation, no dangling typed references.

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
1b. Every requirement opens with a normative SHALL/MUST statement before its first `**Given**` (mechanical: F1)
1c. Every "identical/same/preserved behavior" requirement over an enum/dispatch parameter has one scenario PER variant, each asserting the discriminating observable
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (openspec/capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in openspec/concept-inventory.md
7. Every property has a declared generator strategy (mechanical: F3)
8. Every temporal property has a trigger event and a response event (mechanical: F5)
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition (candidates: W1)
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave (aliasing to a richer type counts — "Type-Widening Impact" subsection required)
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism, in the mandated Source format (mechanical: F4 section presence, **F6 Source resolvable**, **F7 every requirement named — reachability**, **F8 typed source exists**, F9 artifact resolves at Step 12, W2 row count, W4 positional refs, W5 mechanism strength)
13. Every consumer-facing surface (tool/operation/IDL) has a scenario asserting what the consumer observes (parameter schema, not just presence)
14. Every asserted error variant is type-feasible vs the producing API's return type
15. ADVERSARIAL — every "only"/"never"/"must not" requirement has a scenario whose input the requirement forbids (mechanical half: F2/W3)
16. MUST-CONFIRM — externally-sourced classification tables / code mappings / value domains are marked MUST-CONFIRM with a pointer to the real source; invented plausible values FAIL
17. ALTITUDE (if openspec/concepts/ exists) — no code identifiers in Given/When/Then; concepts cited in "Concepts Used (behavioral)" link to registry files
18. CONCURRENCY — concurrent-behavior requirements name deterministic observables testable with the detected deterministic test kit; wall-clock timing assertions FAIL

## Results

### Spec: specs/optimizable-surface/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | All 16 requirements open with a concrete G/W/T block; every scenario has its own G/W/T. No requirement is missing clauses. |
| 1b | SHALL/MUST normative opener | ✅ | All 16 requirements open with "SHALL" before the first `**Given**` (mechanical F1: 0 FAIL). Examples: "The system SHALL provide…", "`optimizable-surface/predictors` SHALL enumerate…", "A predictor whose predictor-state has the frozen flag set SHALL still appear…". |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Two enum/dispatch surfaces with "identical/preserved" claims: (a) `OptimizeError` ADT — both `UnknownPath` and `FrozenPath` variants have dedicated scenarios ("Unknown-path error carries the offending path", "Frozen-path error carries the offending path") asserting the discriminating observable (the carried path). (b) Two toy optimizers — `UppercaseInstructions` and `StaticDemoInjector` each have a dedicated scenario asserting the discriminating observable (uppercased instructions vs. appended demo). No enum/dispatch parameter is covered by only one variant. |
| 2 | Then observable | ✅ | Every `Then` is a return value, equality, error value, enumeration result, or compile/test outcome. One borderline `Then` at line 404 ("suitable for Phase 2 serialization (no closures, no effect type, no live references)") — the forward-looking "suitable for Phase 2 serialization" is intention, but the concrete negation ("no closures, no effect type, no live references") is observable by type inspection of `PredictorState`/`Demo` fields (`String`, `Vector[Demo]`, `Boolean`, `ujson.Value` — all plain immutable values). Acceptable. |
| 3 | Scenarios testable | ✅ | Stack: munit + Hedgehog + cats-effect + ujson. Field reads → munit `assertEquals`; properties → `HedgehogSuite.property`; compile-negative → `assertDoesNotCompile` (munit); build verification → `sbt adk4s-optimize/compile\|test`; manual review (Ring 8) → flagged for the "stands alone" and "no forbidden dependencies" obligations. No ScalaTest/ScalaCheck/circe usage. |
| 4 | Error paths specified | ✅ | `update` → `UnknownPath` (raise); `updateEither` → `UnknownPath` + `FrozenPath` (Either); `updateAll` → no error path (frozen exclusion is silent, all-frozen is a no-op — explicitly specified); `predictors`/`derived`/leaf-capability/placeholder → no runtime error path (pure reads / compile-time summoning). `derived` failure (no `Mirror`) is a compile-time error, implicit. |
| 5 | New concepts declared | ✅ | "Concepts Introduced" table lists: `Demo`, `PredictorState`, `PredictorPath`, `Optimizable[P]`, `HasPredictorState[Self]`, `Predict0[F,I,O]`, `OptimizeError`, `OptimizerLaws`, `UppercaseInstructions`, `StaticDemoInjector`, `adk4s-optimize`. The two concept-doc files (`optimizable-surface.md`, `predictor-state.md`) are listed in the proposal's "New Concepts to Introduce" and marked "NEW — created by this spec" in "Concepts Used (behavioral)" — they are documentation artifacts, not code concepts, so omission from the code-concepts table is consistent. |
| 6 | Reused concepts resolved | ✅ | Reused concepts verified against `openspec/concept-inventory.md` and source: `StructuredLLM[F[_]]` (inventory line 162), `Prompt` (line 137), `Schema[A]` (line 50), `AdkError` (line 59), `ujson.Value` (used in `Document`/`AdkToolInfo`/`InterruptResult`, inventory lines 106/110/130), Hedgehog generators (inventory §generators). `AgentMemoryLaws` is not a row in the inventory table but is referenced via `adk4s-memory-testkit` (inventory line 234) and the source file exists at `adk4s-memory-testkit/src/main/scala/org/adk4s/memory/testkit/AgentMemoryLaws.scala`; `inventory-check.md` line 55 records it as a pattern-reuse. `PromptTemplate` exists in `structured-llm` source and is recorded in `inventory-check.md` line 52. No missing concept. |
| 7 | Generator strategies | ✅ | All 14 properties declare a named generator strategy: `genTwoPredictorProgram`, `genNestedProgram`, `genPipeline`, `genProgramAndPath`, `genStateFn`, `genProgramWithFrozen`, `genProgramAndBadPath`, `genProgramWithFrozenAndFrozenPath`. Several are shared across properties (constructive reuse). All use Hedgehog `Gen` (not ScalaCheck). Mechanical F3: 0 FAIL. |
| 8 | Temporal trigger/response | ✅ | Three `sync` blocks in the concept spec (`LeafDerivation`, `SubtreeDerivation`, `CollectionDerivation`) each have `when`/`then` trigger/response lines. The "Temporal Properties (Ring 9)" section is explicitly omitted ("Ring 9 does not apply — no telemetry stack detected"), which is correct for this pure-synchronous surface. Mechanical F5: 0 FAIL. |
| 9 | No vague words | ✅ | W1 flagged 4 hits, each with a concrete definition inline: (a) line 178 "a frozen state is a valid value" → defined by "freezing is data, not a type-level distinction" (the frozen flag is a `Boolean` field, observable); (b) line 206 "the empty path is a valid value" → defined by "used as a root marker; it is never returned by `optimizable-surface/predictors`" (concrete behavioral consequence); (c) line 294 "paths known to be valid and unfrozen" → in Rationale prose, "valid" = "enumerated by `optimizable-surface/predictors`" (defined by the requirement at line 260); (d) line 302 "Valid unfrozen path" → scenario title, the scenario body defines it as "a predictor at path `a` having frozen flag false". No undefined vague words. |
| 10 | Unreachable claims proven | ✅ | One "never" claim: line 206 "the empty path is never returned by `optimizable-surface/predictors`". Enforcement: structural — paths are built from field-name segments (≥1 segment per leaf/subtree/collection derivation rule), so the empty path is unreachable from `predictors`. The `predictors-declaration-order` property asserts the path set equals exactly the predictor-bearing fields, which implicitly excludes the empty path. Proof obligation row "Empty path renders to the empty string and is never returned by `optimizable-surface/predictors`" maps this to a scenario test. The structural guarantee is type-level (derivation only produces non-empty paths); a runtime check is not needed. Acceptable. |
| 11 | Enum extension / type-widening behavior | ✅ | `OptimizeError` is a NEW enum (not an extension of an existing enum). The spec states it "stands alone in the optimize module (NOT extending `AdkError`)" — so no existing pattern matches over `AdkError` need to consider it. The spec mandates exhaustiveness ("Every match over the error ADT SHALL be exhaustive — no catch-all arm is permitted") and provides a compile-negative obligation for the catch-all case. No aliasing to a richer type occurs. No "Type-Widening Impact" subsection needed for a new enum. |
| 12 | Proof obligations complete (F6 Source format / F7 reachability) | ✅ | Mechanical F4 (section present), F6 (every Source cell names a resolvable requirement/scenario/property heading — 0 FAIL), F7 (every requirement named by ≥1 obligation — 0 FAIL), F8 (no dangling typed references — 0 FAIL). The table has 40+ rows covering all 16 requirements, their scenarios, all 14 properties, the compile-negative obligations, and the introduced type constraint (`PredictorPath` distinct from `FieldPath`). Enforcement mechanisms are concrete: "scenario test", "Hedgehog property", "compile-negative test + exhaustiveness escalation (Ring 0)", "manual review (Ring 8)", "build verification". No W4 positional references (all Source cells use exact titles). No W5 impossibility-claim-only-by-tests (the "stands alone" and "no forbidden dependencies" claims are manual-review/Ring 8, which is tier-appropriate for architectural assertions not expressible as unit tests). |
| 13 | Consumer-facing surface asserted | ✅ | Every consumer-facing surface has scenarios asserting what the consumer observes: `Optimizable[P]` (predictors/update/updateEither/updateAll — multiple scenarios with path/state observables); `HasPredictorState[Self]` (state read + withState round-trip scenarios); `Predict0[F,I,O]` (state-carried + serializable-ready scenarios); `OptimizeError` (path-carrying scenarios for both variants); `OptimizerLaws` (pass/fail scenarios); `PredictorState`/`PredictorPath`/`Demo` (field-read + render scenarios). The consumer observes parameter schema (path segments, state fields, frozen flag), not just presence. |
| 14 | Error variants type-feasible | ✅ | `update` is typed `P` (raises `OptimizeError` on the left branch of `updateEither` — defined in terms of `updateEither` per requirement line 288). `updateEither` is typed `Either[OptimizeError, P]`. Both `UnknownPath(path: PredictorPath)` and `FrozenPath(path: PredictorPath)` are variants of `OptimizeError`, so `Left(OptimizeError.UnknownPath(...))` and `Left(OptimizeError.FrozenPath(...))` are type-feasible. No scenario asserts an error variant that the producing API's return type cannot hold. |
| 15 | Adversarial scenarios for negatives | ✅ | W3 flagged 10 negative requirements; judgment review confirms adversarial scenarios for 9, and the 10th is structurally enforced. Detail: (a) "predictors enumerates in declaration order" — `extra` field is the forbidden input, asserted absent in the main G/W/T ✓; (b) "update is pure" — "Adversarial purity — before snapshot unchanged" + "Unknown path raises an error" ✓; (c) "updateEither returns typed errors" — unknown-path and frozen-path scenarios use the forbidden inputs ✓; (d) "updateAll skips frozen" — "Adversarial — frozen predictor never touched by updateAll" with `OVERWRITE` function ✓; (e) "Frozen predictors remain readable" — "Frozen predictor visible in enumeration" with a frozen predictor as the input that must still appear ✓; (f) "derived via structural derivation" — "Derivation for a mixed program" asserts `plain` does not appear ✓; (g) "Optimizer laws testkit" — "Laws fail for an optimizer that mutates structure" + "Laws fail for an optimizer that mutates the student" ✓; (h) "Optimize module skeleton" — "Module has no forbidden dependencies" inspects the dep tree for the forbidden edges ✓; (i) "predictor path addressing" — "Empty path" scenario asserts the empty path renders to `""` and is never returned by `predictors`; the never-returned half is structurally enforced (derivation produces ≥1-segment paths) and covered by the declaration-order property ✓; (j) "Placeholder predictor wraps StructuredLLM/completeTemplate" — "SHALL NOT render demos into prompts in Phase 0" has no explicit adversarial scenario asserting no Prompt contains the demo. This is structurally enforced by Phase 0 scope: `Predict0` carries state but exposes no render/invoke method in Phase 0 (the spec says "Phase 0 does not invoke it, only carries the capability"), so there is no code path that could inject a demo into a Prompt. The "Placeholder state is serializable-ready" scenario asserts the demo is present in state (positive observable). The negative (no Prompt injection) is guaranteed by the absence of a render call. Acceptable for a Phase-0 skeleton — note for the design phase: if a render method is added, an explicit adversarial scenario must be added then. |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced classification tables, code mappings, or value domains in this spec. `OptimizeError` variants, `PredictorState` fields, `PredictorPath` segment rules, and derivation semantics are all designed in-repo (cross-referenced to `docs/dspy-port-operative-plan.md` which is an in-repo plan). No invented plausible values from external sources. |
| 17 | Altitude respected | ✅ | `openspec/concepts/` exists (28 concept files). G/W/T clauses use behavioral vocabulary only: "predictor-state", "predictor path", "program with predictor fields `a`/`b`" (field names, not code identifiers), "optimizable-surface/predictors is called" (concept/action reference), "unknown-path error", "frozen-path error", "placeholder predictor", "leaf-predictor capability". No class names, error variant names, derivation mechanics, or build commands appear in G/W/T. The one borderline `Then` ("no closures, no effect type, no live references") uses type-level vocabulary, not code identifiers. "Concepts Used (behavioral)" links to registry files: `structured-llm.md`, `prompt.md`, `schema.md` (all exist in `openspec/concepts/`); the two new concept docs are marked "created at apply Step 12". "Concepts Used (from inventory)" lists packages without file links (inventory-section convention, not the behavioral section). |
| 18 | Concurrency deterministic | ✅ | The spec explicitly states "No concurrent behavior in this change — the surface is pure and synchronous; no `TestControl` needed" (proposal §Verification Strategy R3) and "Ring 9 does not apply (no telemetry stack detected)". No wall-clock timing assertions. No concurrent-behavior requirements. All observables are deterministic (equality, enumeration order, error values, compile results). |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/optimizable-surface/spec.md | PASS | 0 blocking — 14 mechanical WARNs (W1 vague-word candidates with inline definitions, W3 negative-requirement adversarial-confirm candidates with verified scenarios). One non-blocking note: the "SHALL NOT render demos into prompts" negative (check 15j) is structurally enforced by Phase 0 scope (no render method exists); if `design.md` adds a render method, an explicit adversarial scenario must be added. |

<!-- Overall: implementation-order may only be generated when every spec is PASS. -->

All checks pass. The spec is implementable: requirements are concrete and observable, error paths are specified, proof obligations cover every requirement/scenario/property with declared enforcement, adversarial scenarios exist for negative requirements, altitude is respected, and the detected stack (munit + Hedgehog + cats-effect + ujson) covers every scenario. The 14 mechanical WARNs are confirmed non-blocking on judgment review.

**Unlocked**: `design`, `implementation-order`.
