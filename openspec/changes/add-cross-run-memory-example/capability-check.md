# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-07-19
**Verification result**: CLEAN for stack detection; 2 rows corrected (stale "this change" remarks from the archived `add-memory-orchestration-hook` change pruned per the profile's own maintenance rule). 1 NEW finding recorded for THIS change (missing test-scope dep).

## Verification procedure

Re-read against the build on 2026-07-19:
- `project/Versions.scala` — all version constants unchanged from the profile's recorded values (Scala 3.8.4 / 3.7.2, cats-effect 3.7.0, fs2 3.13.0, llm4s 0.3.4, workflows4s 0.6.2, smithy4s 0.18.55, munit 1.3.3, munit-cats-effect 2.2.0, hedgehog 0.13.1, logback 1.5.34, all sbt plugin versions).
- `build.sbt` — 8 modules confirmed: `structured-llm`, `structured-llm-test-models`, `adk4s-core`, `adk4s-memory-api`, `adk4s-memory-testkit`, `adk4s-orchestration`, `adk4s-examples`, `verified`.
- `adk4s-orchestration` depends on `adk4s-memory-api` (line 123) — the wiring the previous change added is now part of the baseline build, so the profile's "this change adds → adk4s-memory-api" remark is stale.
- `adk4s-examples` depends on `adk4s-core`, `adk4s-orchestration`, `structured-llm`, `structured-llm-test-models` (lines 135–139). It does **NOT** depend on `adk4s-memory-api` or `adk4s-memory-testkit` directly. `adk4s-memory-api` is available transitively via `adk4s-orchestration`, which is sufficient for `FileBackedAgentMemory` to implement `AgentMemory[F]`. `adk4s-memory-testkit` is **NOT** available — see Findings below.
- `scala3Options` (lines 35+) unchanged: `-deprecation`, `-feature`, `-unchecked`, `-Xkind-projector:underscores`, `-source:future`, plus the exhaustiveness escalation `-Wconf:name=PatternMatchExhaustivity:e,name=MatchCaseUnreachable:e`. `-Werror` still NOT active.
- `.scalafix.conf`, `.scalafmt.conf`, `stryker4s.conf`, WartRemover config — unchanged from the profile's recorded state.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Module dependency graph (line 34) | `adk4s-orchestration → adk4s-core, structured-llm (this change adds → adk4s-memory-api)` | `adk4s-orchestration → adk4s-core, structured-llm, adk4s-memory-api` (parenthetical removed — the dep is now baseline, landed by the archived `2026-07-19-add-memory-orchestration-hook`) | build.sbt:119–124 |
| Narrative line 43 | `This change adds a dependency adk4s-orchestration → adk4s-memory-api ...` | Removed (stale — the dep is baseline) | build.sbt:123 |

No stack-detection rows changed: every library version, test framework, property framework, mutation tool, formal-verification tool, and static-analysis rule in the profile matches the build.

## Findings for THIS change

### Finding 1 (BUILD WIRING — required): `adk4s-examples` needs a test-scope dep on `adk4s-memory-testkit`

`FileBackedAgentMemorySpec` (spec §6) must run `AgentMemoryLaws(indexesContent = true).all` against the file-backed double. `AgentMemoryLaws` lives in `adk4s-memory-testkit` (MAIN scope — it is a downstream-consumable laws trait, not a test-only internal). `adk4s-examples` currently has no dependency on `adk4s-memory-testkit`:

```
adk4s-examples → adk4s-core, adk4s-orchestration, structured-llm, structured-llm-test-models   (build.sbt:135–139)
```

`adk4s-memory-api` is reachable transitively via `adk4s-orchestration` (sufficient for `FileBackedAgentMemory` to implement `AgentMemory[F]` in main sources). But `adk4s-memory-testkit` is NOT reachable — sbt does not propagate test dependencies transitively, and `adk4s-orchestration` does not depend on the testkit at all.

**Required build change** (part of this change's implementation, NOT a library code change — consistent with the proposal's "no library code changes" scope):

```scala
// build.sbt — adk4s-examples
lazy val `adk4s-examples` = (project in file("adk4s-examples"))
  .dependsOn(
    `adk4s-core`,
    `adk4s-orchestration`,
    `structured-llm`,
    `structured-llm-test-models`,
    `adk4s-memory-testkit` % Test   // NEW — for FileBackedAgentMemorySpec laws
  )
```

This is a one-line build.sbt edit, scoped to Test, adding no main-scope coupling. It is the second in-repo consumer of `adk4s-memory-testkit` (the first being `adk4s-memory-api`'s own tests via the `InMemoryAgentMemorySpec`). The proposal's "no library code changes" statement holds — build wiring is not library code, and the testkit is specifically designed as a "downstream-consumable behavioral laws" module (profile line 62).

**Profile update when implemented**: add `adk4s-examples % Test → adk4s-memory-testkit` to the module dependency graph.

### Finding 2 (advisory): `adk4s-examples` reaches `AgentMemory` only transitively

`FileBackedAgentMemory.scala` lives in `adk4s-examples` main sources and implements `AgentMemory[F]` from `adk4s-memory-api`. The dep is transitive (`adk4s-examples → adk4s-orchestration → adk4s-memory-api`). This compiles and is fine for examples, but if a future refactor decouples `adk4s-orchestration` from `adk4s-memory-api`, the example breaks. No action required now — recorded for awareness. The spec's `⚠ VERIFY` step should confirm the transitive path is acceptable rather than adding a direct main-scope dep (examples are edge code; transitive deps are normal at the edge).

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `adk4s-examples % Test → adk4s-memory-testkit` dependency | build wiring (Test scope) | proposal §"What Changes", this artifact Finding 1, spec §6 |

No new libraries, no new modules, no new tooling. The change reuses the existing memory stack (`adk4s-memory-api`, `adk4s-memory-testkit`) and the existing orchestration hook (`adk4s-orchestration`'s `MemoryAwareRunner` / `MemoryPolicy`).

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 Compile | ✅ | `sbt adk4s-examples/compile`. Exhaustiveness escalation active but N/A — no sealed ADT extended by this change. |
| R1 Lint | ✅ | Scalafix + WartRemover (relaxed set, same as rest of `adk4s-examples`) + scalafmt. Examples are edge code; the relaxed wart set applies. |
| R2 Architecture | ⚠️ Advisory only | No custom scalafix arch rules. `org.adk4s.examples.*` may import everything (profile line 141). The new `org.adk4s.examples.memory` package inherits this. No new purity boundary. |
| R3 Property tests | ✅ | Hedgehog 0.13.1 via hedgehog-munit. `FileBackedAgentMemorySpec` runs `AgentMemoryLaws(indexesContent = true).all` (Hedgehog properties). `CrossRunMemorySmokeSpec` is a munit scenario test asserting A1 + A2. **No concurrent behavior** — `MemoryAwareRunner` is a sequential decorator; the file-backed memory is single-writer/single-reader JSON-lines. `TestControl` NOT required. |
| R4 Wire/persistence | ✅ Manual | `FileBackedAgentMemory` introduces a JSON-lines persistence format for `Episode`. No fixture framework, but the laws suite covers `remember`→`recall` round-trip and the spec adds an explicit reload-across-instances property (write with one instance, recall with a fresh instance at the same path) to prove cross-process persistence. upickle/ujson is the serializer (transitive via llm4s; used directly in adk4s-core). |
| R5 Mutation | ⏸ Skipped | Example/demo code. The laws suite already provides the mutation-equivalent safety net for `FileBackedAgentMemory`. Not retargeting stryker4s. Recorded in proposal. |
| R6 Formal | ⏸ N/A | Not pure-domain `verified` module code. Effectful `IO` example wiring. |
| R7 Model checking | ❌ | No TLA+/Apalache. Skip with stated impact (none — no distributed/event-driven invariants). |
| R8 Adversarial review | ✅ | MANDATORY. Fresh-context reviewer checks: mock answers from injected context (not script), two smoke-test stacks share only temp dir, `FileBackedAgentMemory` honors all four laws in spirit. |
| R9 Telemetry | ❌ | No otel4s/Daut. Skip with stated impact (none — no telemetry stack). |
| Concurrency kit | ✅ available, NOT used | `TestControl` available via cats-effect 3.7.0 but not required — no concurrent behavior in this change. |
| Code intelligence | ✅ | Metals MCP endpoint at `http://localhost:8394/mcp` (per-project). The `⚠ VERIFY` step (resolving `MemoryAwareRunner` return type, `MemoryPolicy.default` render prefix, `Retriever` method name, mock-model convention, `InMemoryAgentMemory` scoring visibility) SHOULD use the semantic recipes (`scanner/metals-call.sh`, impact-scan) first, git grep as fallback. Semantic answers trusted only post-compile. |

## Consequences for downstream artifacts

- **specs**: must specify the `adk4s-examples % Test → adk4s-memory-testkit` build edit as an explicit requirement (not hidden in the test description), because it is the one build change this change makes.
- **design**: the `FileBackedAgentMemory` JSON-lines format is the one new wire format — design records the upickle `ReadWriter[Episode]` derivation and the reload-across-instances property.
- **implementation-order**: the build.sbt edit is a prerequisite for `FileBackedAgentMemorySpec` and must be sequenced before the test file.
- **apply phase**: generates munit + Hedgehog tests (NOT ScalaTest, NOT ScalaCheck). Uses `munit.FunSuite` / `munit.CatsEffectSuite` for the smoke test, `hedgehog.munit.HedgehogSuite` for the laws properties (the laws themselves already use Hedgehog; the spec just runs them).
