# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-01
**Verification result**: 5 rows corrected (listed below)

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Modules count | 8 (missing `adk4s-optimize`) | 9 (includes `adk4s-optimize`) | build.sbt lines 128–143 — `adk4s-optimize` project landed by archived `2026-08-01-add-optimizable-surface` |
| Module dependency graph | missing `adk4s-optimize` line | `adk4s-optimize → structured-llm, verified % Test` added | build.sbt lines 128–132 |
| WartRemover excluded set | 9 warts excluded (`TripleQuestionMark`, `Any`, `DefaultArguments`, `IterableOps`, `AsInstanceOf`, `Throw`, `Var`, `OptionPartial`, `StringPlusAny`) | 3 warts excluded only (`TripleQuestionMark`, `Any`, `DefaultArguments`) — all others ACTIVE | build.sbt lines 27–31 — `Warts.unsafe.filterNot` filters exactly 3; the profile was stale from before the Phase 0 WartRemover tightening |
| Metals scanner path | `scanner/metals-start.sh` | `openspec/schemas/verified-scala3/scanner/metals-start.sh` | `find . -name metals-start.sh` — the scanner lives under the schema directory, not the repo root |
| Ring 6 status | "verified/ is package-doc only, no model, no bridge" | "verified/ contains `PredictorKernel`, `adk4s-optimize dependsOn(verified % Test)` wired, `PredictorModelBridgeSpec` bridge test runs" | archived `2026-08-01-add-optimizable-surface` implementation-progress.md Step 3(d) + Ring 6 |

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `adk4s-eval` module | new sbt module (package `org.adk4s.eval`) | proposal §Approach; spec `eval-core` R1.1–R1.9; spec `llm-judges` R1.10–R1.13; build wiring in implementation-order Step 0 |
| `adk4s-eval` → `structured-llm` dependency | module dependency (judges call `StructuredLLM.complete`) | proposal §Approach; cross-phase convention in `docs/dspy-port-operative-plan.md` |
| `adk4s-eval` → cats-effect, fs2-core dependency | module dependencies (parallel evaluation via fs2 `parEvalMap`) | proposal §Approach; spec `eval-core` R1.1, R1.3 |
| Judge Smithy schemas | hand-written `Schema.instance` definitions in `adk4s-eval` (⚠ VERIFY R1.11 — not in `structured-llm-test-models` which is test-only) | proposal §Approach; spec `llm-judges` R1.11 |

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | ✅ | `sbt adk4s-eval/compile` + `sbt adk4s-eval/Test/compile` — new module added to build.sbt; exhaustiveness escalation active for `EvalOutcome` / `EvalError` enums |
| R1 lint | ✅ | Scalafix + WartRemover (3-exclusion set: `TripleQuestionMark`, `Any`, `DefaultArguments` only — `Throw`, `Var`, `IterableOps`, `AsInstanceOf`, `OptionPartial`, `StringPlusAny` are ACTIVE). `EvalError extends Throwable` raised via `F.raiseError` — verify no bare `throw`. `ujson.Value` in export may trip `Any` (already excluded). scalafmt check. |
| R2 architecture | ⚠️ Advisory only | New `org.adk4s.eval` package purity rule to add: MAY import cats-effect, fs2-core, structured-llm, ujson; MUST NOT import workflows4s, llm4s LLM client, adk4s-core, adk4s-orchestration, adk4s-optimize. Recorded for Step 12 module-graph update. |
| R3 property tests | ✅ | Hedgehog 0.13.1 via hedgehog-munit. **Concurrent behavior present** (parallel evaluation + cancellation) — scenarios use `TestControl` (cats-effect 3.7.0, transitively available) for deterministic cancellation timing, never wall-clock sleeps. |
| R4 wire/persistence | ⚠️ Manual | `toJson`/`fromJson` round-trip with `formatVersion: 1` (R1.8); `toCsv` column contract. No fixture-based framework — Hedgehog property asserts round-trip equality. Applies because this change touches serialization (JSON export). |
| R5 mutation | ✅ available | sbt-stryker4s 0.21.0 + stryker4s.conf. Retarget `mutate` list to `adk4s-eval/src/main/scala/org/adk4s/eval/*.scala` (candidates: `Evaluate.scala`, string metrics). Deferred unless `design.md` identifies non-trivial logic. |
| R6 formal | ❌ skip (justified) | No pure decision/fold/law at the centre — the harness is fs2 orchestration over `F[_]` with trivial aggregate-mean arithmetic (one `foldLeft`). `Trace.forPredictor` is a one-line `filter`. If `design.md` reveals a non-trivial pure kernel (e.g. a normalization state machine), Ring 6 is re-evaluated. Stainless is available but has no kernel to prove here. |
| R7 model checking | ❌ | No TLA+/Apalache. Skip with stated correctness impact. |
| R8 adversarial review | ✅ (manual — always available) | MANDATORY. Runs BEFORE R5/R6/R7 in the apply sequence. Fresh-context reviewer checks every R1.* requirement against the diff. |
| R9 telemetry | ❌ | No otel4s/Daut. Skip with stated impact. |
| Concurrency kit | ✅ | `cats.effect.unsafe.TestControl` — transitively available via cats-effect 3.7.0. R1.3 cancellation test uses it. |
| Code intelligence | ✅ | Metals MCP endpoint at `http://localhost:8394/mcp` (probe confirmed reachable 2026-08-01). Apply phase prefers semantic recipes (`openspec/schemas/verified-scala3/scanner/metals-call.sh`, `impact-scan.sh`, `removal-audit.sh`); git grep is the fallback. |
