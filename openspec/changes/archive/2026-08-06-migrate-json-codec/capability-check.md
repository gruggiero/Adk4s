# Capability Check

<!-- Per-change verification report against the PROJECT capability profile
     (openspec/capability-profile.md — the living document; see
     templates/capability-profile.md for its format). This report is what
     gets archived with the change; the profile itself never is.

     Cases:
     - Project profile missing → it was CREATED by this change (say so, and
       summarize the detection evidence).
     - Project profile exists → it was RE-VERIFIED against the build; every
       corrected row is listed below. -->

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-02
**Verification result**: CLEAN (profile matches build)

The project profile was re-verified against `build.sbt`,
`project/Versions.scala`, `project/Dependencies.scala`, `project/plugins.sbt`,
`stryker4s.conf`, and `.scalafix.conf`. Every row relevant to this change
(JSON stack, smithy4s-json, effect system, test framework, property framework,
mutation tooling, formal-verification module, deterministic-concurrency kit,
code-intelligence endpoint) matches the build files. No rows were added,
removed, or corrected.

Key confirmations for this change:

- **`smithy4s-json` is already a first-class declared dependency**
  (`Dependencies.smithy4s` includes both `smithy4s-core` and `smithy4s-json`
  at `Versions.Smithy4s = 0.18.55`). The jsoniter-scala backend it brings is
  the migration target. No build change is needed to land the new `JsonValue`
  type or the schema-derived tool codec — both are already on the classpath of
  every module that depends on `structured-llm` (which pulls `smithy4s`).
- **`upickle`/`ujson` is NOT declared in `Dependencies.scala`** — confirmed
  transitive via `org.llm4s:core:0.3.4`. The profile's "JSON | upickle / ujson
  | (transitive via llm4s)" row is accurate. This change's item 1 (declaring
  it explicitly wherever used) is a declaration change, not a new capability.
- **Module count is 10** (`structured-llm`, `structured-llm-test-models`,
  `adk4s-core`, `adk4s-memory-api`, `adk4s-memory-testkit`, `adk4s-optimize`,
  `adk4s-eval`, `adk4s-orchestration`, `adk4s-examples`, `verified`) — matches
  the profile. The proposal's affected modules (`adk4s-core`,
  `adk4s-orchestration`, `structured-llm`, `adk4s-optimize`, `adk4s-eval`) all
  exist and are wired as the profile's dependency graph describes.
- **Exhaustiveness escalation is active**
  (`-Wconf:name=PatternMatchExhaustivity:e,name=MatchCaseUnreachable:e` in
  `scala3Options`). This change extends no sealed ADT, but the new
  `JsonishValue`/`CoercionFlag`/`ParsingError` ADTs it introduces (per the
  `type-aware-sap-coercion` spec) MUST be matched exhaustively everywhere —
  Ring 0 will fail any partial match over them.
- **WartRemover `AsInstanceOf` is ACTIVE** (not in the excluded set). The
  proposal's item 6 (removing the four `asInstanceOf` sites in
  `ToolInfer`/`ToolSchema.derive`) is a WartRemover-compliance fix, not a
  suppression addition. This change should *reduce* suppressions, never add
  them.
- **`stryker4s.conf` mutate list is currently `**/memory/MemoryRetriever.scala`**
  (stale from a prior change) — Ring 5 for this change MUST retarget it to the
  changed production files (`JsonishParser`, `TypeCoercer`, `JsonValue`
  adapter, migrated field sites) before running, per the profile's stated rule.
- **`verified` module is non-empty** — it contains `PredictorKernel` (landed
  by the archived `add-optimizable-surface` change) and
  `adk4s-optimize dependsOn(verified % Test)` is wired with a bridge test.
  This change's Ring 6 candidate (`TypeCoercer` candidate-selection fold +
  enum-fuzzy-matching escalation) follows the same VERIFIED-MIRROR pattern;
  the `verified` module's Stainless toolchain is confirmed available
  (`sbt -J-Xmx6g ring6`, Stainless 0.9.9.3, smt-z3 fallback).
- **`cats-effect-testkit` (`TestControl`) is on `adk4s-eval % Test`**
  (build.sbt line 162: `:+ catsEffectTestkit`). This change introduces no new
  concurrency/streaming behavior (parsing and encoding are pure), so
  `TestControl` is not load-bearing for it — but it remains available if a
  spec needs to assert non-streaming behavior under deterministic time.

## Corrections applied to the project profile

none — the profile matched the build on every row inspected.

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| — | — | — | clean |

## Capabilities THIS change introduces

This change introduces **no new third-party library or tooling**. The
proposal's "Out of Scope" section explicitly commits to "No new third-party
JSON library" — everything needed (`smithy4s.Document`, `smithy4s-json`'s
jsoniter-scala backend) is already a declared dependency per
`Dependencies.smithy4s`.

The only build-file change is a *declaration* change (item 1): adding
`upickle`/`ujson` explicitly to `Dependencies.scala` wherever ADK4S-owned code
uses it directly, closing the undeclared-transitive-dependency gap. This does
not add a capability — the artifact is already on the classpath transitively
via `llm4s`; it merely makes the existing usage a declared, version-pinned
dependency so an llm4s upgrade cannot silently break ADK4S's own type
signatures.

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `upickle`/`ujson` explicit declaration | declaration of existing transitive dep (NOT a new library) | proposal §"What Changes" item 1; will land in `Dependencies.scala` during apply |
| `org.adk4s.core.json.JsonValue` | new project-local type alias (`= smithy4s.Document`) — no new library | proposal §"New Concepts to Introduce"; `specs/json-value-model/spec.md` |
| jsonish tolerant parser (`String => JsonishValue`) | new project-local function — no new library | proposal §"New Concepts to Introduce"; `specs/type-aware-sap-coercion/spec.md` |
| `TypeCoercer.coerce[A]` | new project-local typeclass method — no new library | proposal §"New Concepts to Introduce"; `specs/type-aware-sap-coercion/spec.md` |

None of these append a row to the project profile's Libraries table — they are
project-local code built on already-profiled dependencies. The profile's
"Ring Availability Summary" therefore needs no update from this change.

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | yes | `sbt compile` (all 10 modules). Exhaustiveness escalation active — new `JsonishValue`/`CoercionFlag`/`ParsingError` ADTs MUST be matched exhaustively. No `-Werror`, but the `-Wconf` escalations cover sealed-ADT matches. |
| R1 lint | yes | Scalafix (`DisableSyntax` incl. `noAsInstanceOf`/`noIsInstanceOf`) + WartRemover (`AsInstanceOf` ACTIVE). This change *removes* four `asInstanceOf` sites — net suppression reduction. The new Ring 2 guard (flagging `ujson.Value` outside the llm4s-adapter allowlist) is a new custom Scalafix regex rule mirroring the existing `NoConfigFactory`/`NoSysEnv` pattern. |
| R2 architecture | yes (advisory → enforced by new rule) | This change's core thesis IS a layering rule ("`ujson.Value` only at the llm4s boundary"). The proposal commits to adding a Scalafix `DisableSyntax`/custom-regex guard for it — upgrading Ring 2 from advisory to enforced for the `ujson` concern. Domain purity rules in the profile (`org.adk4s.structured.core`, `.sap`, `org.adk4s.optimize`, `org.adk4s.eval`) are the contract this change enforces. |
| R3 property tests | yes | Hedgehog 0.13.1 via `hedgehog-munit`. MANDATORY per proposal — no waiver. Coverage assertions via Hedgehog `cover`; seed-fixing via fixed `Seed`. The `type-aware-sap-coercion` spec's existing-but-unexercised properties become real oracles; new apostrophe-round-trip and per-migrated-type round-trip properties added. |
| R4 wire/persistence compat | yes (manual, REQUIRED) | No fixture framework, but the proposal commits to capturing JSON fixtures from the current `ujson`-based encoders (`InterruptSignal`, `EvaluationResult`, `Demo`) BEFORE any code change and asserting byte/semantic equivalence after. This is the profile's "⚠️ Manual" row made concrete for this change. |
| R5 mutation | yes | `sbt-stryker4s 0.21.0` + `stryker4s.conf`. **Retarget required**: the current `mutate = ["**/memory/MemoryRetriever.scala"]` is stale; point it at the changed production files (`JsonishParser`, `TypeCoercer`, `JsonValue` adapter, migrated field sites) per spec before running. Thresholds break=90/low=91/high=95 — the proposal's 90%/85% targets fit within `break=90`. |
| R6 formal | yes | `verified` leaf module (Scala 3.7.2, Stainless 0.9.9.3, smt-z3 fallback). Applicable to the `TypeCoercer` candidate-selection fold (pure total order over `CoercionScore`) and enum-fuzzy-matching escalation (monotonic penalty chain), per the VERIFIED-MIRROR pattern already proven by `PredictorKernel`. `JsonValue`/`Document` itself is a plain immutable ADT — not a Ring 6 candidate (no decision/fold/law at its center). Run: `sbt -J-Xmx6g ring6`. |
| R7 model checking | no | No TLA+/Apalache (profile row). Not applicable — no new distributed/event-driven protocol; `InterruptSignal`'s address-routing protocol is unchanged, only its `state` field's value type. Skip with stated impact (none expected). |
| R8 adversarial review | yes (manual — always available) | MANDATORY per proposal. Runs before R5/R6/R7 in the apply sequence (fresh-context reviewer). Explicit targets: silent stringly-typed fallbacks in `ToolSchema.derive`, dropped coercion flags in the tolerant parser, `JsonValue`↔`ujson.Value` round-trip loss across all `Document` variants incl. `DNull`. |
| R9 telemetry | no | No otel4s/Daut (profile row). Not applicable — no telemetry stack; this change touches pure parsing/encoding/serialization, not observability. Skip with stated impact (none). |
| Concurrency kit | yes | `cats-effect-testkit` (`TestControl`) on `adk4s-eval % Test`. Not load-bearing for this change (parsing/encoding are pure, no new concurrency/streaming), but available if a spec asserts non-streaming behavior under deterministic time. |
| Code intelligence | yes | Metals MCP endpoint at `http://localhost:8394/mcp` (per-project, Metals 1.6.7). Apply phase prefers the schema's semantic recipes (`metals-call.sh`, `impact-scan.sh`, `removal-audit.sh`) for the `ujson.Value` migration's find-usages and the `ToolInfer`/`ToolSchema.derive` removal audit; git grep is the fallback and the only CI tool. Semantic answers trusted only post-compile. |
