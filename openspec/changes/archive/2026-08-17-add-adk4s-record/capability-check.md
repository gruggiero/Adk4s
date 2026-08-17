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

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-14
**Verification result**: 3 rows corrected (listed below); all other rows match the build

The project profile was re-verified against `build.sbt`,
`project/Versions.scala`, `project/Dependencies.scala`, `project/plugins.sbt`,
`project/build.properties`, `stryker4s.conf`, and landed source. Three rows
had drifted from the build and were corrected in place (the profile is a
living document). No rows were missing; no new capabilities the build does
not already have were found.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Refined types (Libraries table) | Version `(see build.sbt adk4s-harness-api deps)`; notes mentioned only `adk4s-harness-api` and `MiddlewareName`/`CellId` | Version `3.3.2`; notes list all four modules using Iron (`structured-llm`, `adk4s-core`, `adk4s-harness-api`, `adk4s-orchestration` via `ironUpickle`) and the full set of established refined newtypes (`NodeKey`, `Positive`/`NonNegative`, `MiddlewareName`, `CellId`, `CheckpointId`); records the `add-iron-refined-types` migration | `project/Dependencies.scala` (`iron`, `iron-cats`, `ironUpickle` all `Versions.Iron` = 3.3.2); `build.sbt` lines 69, 90, 111, 238 (`iron` in structured-llm, adk4s-core, adk4s-harness-api; `ironUpickle` in adk4s-orchestration); `openspec/concept-inventory.md` rows 47–56 |
| Module dependency graph | `adk4s-orchestration → adk4s-core, structured-llm, adk4s-memory-api` | `adk4s-orchestration → adk4s-core, structured-llm, adk4s-memory-api, adk4s-harness-api, adk4s-harness-testkit % Test` | `build.sbt` `adk4s-orchestration` `.dependsOn(...)` block |
| Module dependency graph | `adk4s-examples → adk4s-core, adk4s-orchestration, structured-llm, structured-llm-test-models` | `adk4s-examples → adk4s-core, adk4s-orchestration, structured-llm, structured-llm-test-models, adk4s-eval` | `build.sbt` `adk4s-examples` `.dependsOn(...)` block |
| Mutation tool (Testing table) | `mutate` list "currently `**/harness/HarnessState.scala`, stryker4s.conf:12" | `mutate` list is 5 `adk4s-harness-testkit` files: `AgentMiddlewareLaws`, `SemilatticeLaws`, `SimpleHarnessLoop`, `DeterministicChatModel`, `Generators` | `stryker4s.conf` `mutate = [...]` block |

> **Why the Iron correction matters for THIS change.** The proposal's D1
> drift note was rewritten in this session to correct a prior false claim
> that "Iron is NOT PRESENT". `adk4s-record` will depend on `adk4s-core`,
> which already carries the full `iron` + `iron-cats` dependency, so
> `RolloutId` (`NonEmpty`) and `maxEntries` (`numeric.Positive`) reuse the
> established `NodeKey` `RefinedType` / `ToolsNodeConfig` `refineEither`
> precedent with **no new dependency**. The profile row now reflects this.

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `adk4s-record` module | new sbt subproject (module 13) | proposal §3 (Module placement), spec `adk4s-record-module` |
| `fs2-io` (file recorder) | library already in stack (`fs2` Seq), source-scoped to file recorder per Ring 2 | proposal D3, spec `recorder-sink` |
| `hedgehog-munit` MAIN scope (`RecorderLaws`) | library already in stack, main-scope variant for downstream-consumable laws | proposal D3, spec `recorder-laws`; precedent `adk4s-harness-testkit` (`hedgehogMunitMain`) |
| `cats-effect-testkit` MAIN scope (if `RecorderLaws` needs `TestControl`) | library already in stack, main-scope variant | spec `recorder-laws`; precedent `adk4s-harness-testkit` (`catsEffectTestkitMain`) — only if a law exercises concurrency; this change is sequential (proposal Ring 3), so likely NOT needed |
| AR-REC-1 / AR-REC-2 | two new Scalafix custom arch rules | proposal §7.3, spec `adk4s-record-module` |

No **new external library** is introduced: Iron, fs2 (core+io), upickle,
cats-effect, munit, Hedgehog, and `verified` (Test scope) are all already in
the stack. The new module wires existing dependencies. This is confirmed by
`project/Dependencies.scala` — every `ModuleID` the proposal's build.sbt
snippet references (`catsEffect`, `fs2Core`/`fs2`, `munitMain`,
`munitCatsEffect`, `hedgehogMunit`/`hedgehogMunitMain`, `upickle`, `iron`,
`testDeps`) already exists as a `val` there.

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | ✅ | `scala3Options` with exhaustiveness escalation; Iron-refined types via `RefinedType`/`refineEither` (no new dep). New module compiles with `sbt adk4s-record/compile`. |
| R1 lint | ✅ | Scalafix `DisableSyntax` + `RemoveUnused` + `OrganizeImports`; WartRemover relaxed set (3 exclusions, no new exclusions permitted per proposal). D2 RESOLVED: `CanonicalForm.body` is a typed union generated from Smithy IDL — no `ujson` in the canonical form. ujson confined to llm4s boundary via `JsonValueCodec`. The existing `NoUjsonIn*` Scalafix family confines `ujson.Value` to `org.adk4s.core.json`/`.tools`; `org.adk4s.record.canonical` does not need allowlisting. |
| R2 architecture | ⚠️ Advisory → becoming enforced | Currently advisory (no custom arch rules installed). THIS change introduces AR-REC-1 (no ambient nondeterminism in canonicalization) and AR-REC-2 (no unordered iteration in canonicalization) as Scalafix arch rules — the first enforced arch rules in the project. Module purity: `adk4s-record` must NOT depend on `workflows4s`, `adk4s-orchestration`, `adk4s-optimize`, `adk4s-eval`, `logback`; `fs2-io` source-scoped to the file recorder. |
| R3 property tests | ✅ MANDATORY | Hedgehog 0.13.1 via `hedgehog-munit`. `RecorderLaws` (RL0–RL12) in MAIN scope (`hedgehogMunitMain`), parameterized over `Recorder[F]`, run against `noop`/`inMemory`/`file`. RL3/RL4 mutation-generator-driven over `RequestMutation` ADT. **No concurrent behavior** (single-flight out of scope, proposal §9.2) — no `TestControl` required for the wrappers; the `file` recorder uses `Async[F]` for resource-safe I/O but introduces no parallelism/cancellation/timeouts. |
| R4 wire/persistence | ✅ applies | `file` recorder writes JSONL (persisted). Round-trip codec tests (RL7), golden-file canonical-form pinning (§9.5), `keyVersion` forward-compat (REC-25). |
| R5 mutation | ✅ | sbt-stryker4s 0.21.0. **Retarget `stryker4s.conf` `mutate` list** to the canonicalization package and `Recorder` implementations (the two silent-failure surfaces). Threshold at/above project default (break=90/low=91/high=95), not relaxed. |
| R6 formal | ✅ applies (verified-mirror) | `verified` leaf module (Scala 3.7.2, Stainless 0.9.9.3, Z3 4.13.4). Two PureScala models: (1) recorder coherence (RL1 finite-map pair, `HarnessState` get/set precedent), (2) tool-call id normalization idempotence + order preservation (REC-4). `adk4s-record dependsOn(verified % Test)` wires the bridge test (TASTy backward compatible: 3.8.4 reads 3.7.2). Hash collision-freedom assumed (injective abstract function, stated). |
| R7 model checking | ❌ | No TLA+/Apalache. Sequence numbering is a monotonic counter, not a distributed ordering protocol. Skip with stated impact (proposal Ring 7 row). |
| R8 adversarial review | ✅ MANDATORY | Fresh-context reviewer, runs BEFORE Rings 5/6/7. |
| R9 telemetry | ❌ | No otel4s/Daut. Skip with stated impact (proposal Ring 9 row). This change does not affect API operations or `AgentEvent` sequences. |
| Concurrency kit | ✅ available (not required) | `TestControl` available transitively via cats-effect 3.7.0. NOT required for this change (sequential — single-flight out of scope). Available if a future single-flight decorator (§9.2) needs it. |
| Code intelligence | ✅ available | Metals MCP endpoint `http://localhost:8394/mcp` (per-project, Metals 1.6.7). Apply phase prefers `metals-call.sh`/`impact-scan.sh`/`removal-audit.sh` for the public-type-change impact scan (Step 0) and orphan audit (Step 12); git grep is the fallback and the only CI tool. cellar CLI available for llm4s/workflows4s API lookup. |
