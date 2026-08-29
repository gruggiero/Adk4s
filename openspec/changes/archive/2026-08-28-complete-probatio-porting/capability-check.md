# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-25
**Verification result**: 5 rows corrected (listed below)

## Corrections applied to the project profile

The project profile was last verified 2026-08-17 by the `port-scanner-to-probatio` capability-check. Re-verification against the current `build.sbt`, `project/Versions.scala`, `project/Dependencies.scala`, and `stryker4s.conf` found 5 stale rows — all caused by the `port-scanner-to-probatio` change landing its 4 new modules and retargeting `stryker4s.conf` after the profile's last verification date.

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Modules (Build & Language) | 13 modules — did not list `probatio-core`, `probatio-cli`, `sbt-probatio`, `probatio-verified`, or `probatio-spike` | 17 modules — the 4 probatio modules added under `workflow/` and `verified/probatio`, plus `probatio-spike` (throwaway, not aggregated) | `build.sbt` lines 490, 528, 573, 608, 706 — `lazy val probatio-core`, `probatio-cli`, `sbt-probatio`, `probatio-verified`, `probatio-spike` |
| Module dependency graph | ended at `verified → (leaf, Scala 3.7.2, Stainless, not aggregated)` — no probatio edges | 5 new edges: `probatio-core → probatio-verified % Test`, `probatio-cli → probatio-core`, `sbt-probatio → (Scala 2.12, no probatio-core link — R-S1)`, `probatio-verified → (leaf)`, `probatio-spike → (throwaway)` | `build.sbt` `.dependsOn` and `.settings` blocks for each probatio module |
| Mutation tool (Testing) | `stryker4s.conf` mutate list = 4 `adk4s-record` files (`RecordedChatModel.scala`, `RecordedEmbedder.scala`, `RecordingToolMiddleware.scala`, `Redaction.scala`) | mutate list = 4 probatio files (`probatio/core/Validator.scala`, `probatio/core/ProvenanceFields.scala`, `probatio/core/Ledger.scala`, `probatio/cli/SubcommandEntrypoints.scala`) | `stryker4s.conf` lines 14–19 — the `port-scanner-to-probatio` change retargeted it |
| Formal verification (Testing + Ring Availability) | mentioned only `verified/` with `PredictorKernel`; no mention of `probatio-verified/` or `ConformanceModel` | both mirror modules listed: `verified/` has `PredictorKernel`; `probatio-verified/` has `ConformanceModel`; `ring6` alias enables BOTH; `probatio-core dependsOn(probatio-verified % Test)` is wired | `build.sbt` line 608 (`probatio-verified`), line 495 (`probatio-core .dependsOn(probatio-verified % Test)`), `verified/probatio/src/main/scala/.../ConformanceModel.scala` |
| Libraries / Compile commands / Domain purity | no probatio entries (os-lib, mainargs, sbt-native-image, probatio compile/test commands, probatio Ring 2 rules) | added: os-lib 0.11.8, mainargs 0.7.8, sbt-native-image 0.4.0; `probatio-core/compile`, `probatio-cli/compile`, `sbt-probatio/compile`, `probatio-verified/compile` + test variants; native binary command; dependency-lint command; 4 probatio domain-purity rows | `project/Versions.scala` lines 28–29, 43; `project/Dependencies.scala` lines 73–92; `build.sbt` compile/test/nativeImage/dependencyLint blocks |

No rows were removed. The 13 adk4s module rows and all prior content remain accurate — the corrections are purely additive (new modules) or updates to mutable fields (stryker4s.conf mutate list, Ring 6 mirror contents).

## Capabilities THIS change introduces

This change (`complete-probatio-porting`) wires existing stub entrypoints and swaps existing bash shims. It introduces NO new modules, NO new libraries, and NO new tooling. The 4 probatio modules and their dependencies (os-lib, mainargs, sbt-native-image, upickle) were introduced by the archived `port-scanner-to-probatio` change and are now recorded in the project profile (see corrections above).

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| none | — | This change populates existing stubs (`SubcommandEntrypoints.scala`) and replaces existing bash scripts with 3-line shims. No new build dependencies. |

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | ✅ | `sbt probatio-cli/compile` — the wiring lives in `workflow/cli/` (Scala 3.8.4, `probatioScalacOptions` with `-Werror`). `sbt sbt-probatio/compile` for the plugin (Scala 2.12.20). |
| R1 lint | ✅ | Scalafix DisableSyntax + WartRemover (no `isInstanceOf`/`asInstanceOf`/`Any`/mutable vars) + scalafmt. The wiring code is in `org.sinemenda.probatio.cli` — subject to the same WartRemover rules as the existing CLI code. |
| R2 architecture | ✅ | `sbt probatioDependencyLint` — R-ARCH1: the wiring MUST NOT import cats/cats-effect/fs2/llm4s/workflows4s/adk4s. The CLI layer may use os-lib, ujson, mainargs, and `probatio-core` only. Enforced at compile time via the `dependencyLint` task in `build.sbt`. |
| R3 property tests | ✅ | Hedgehog 0.13.1 via hedgehog-munit for the Scala-side wiring (arg parse → core call → stdout shape). The bats oracle (17 `.bats` files) is the acceptance suite at each `*_OVERRIDE` seam — this is the primary regression oracle for the port. **Concurrency**: this change does NOT introduce concurrent behavior. The gate is single-threaded per turn. The metals subcommand uses blocking LSP I/O (synchronous request/response over a socket), not async streaming. No `TestControl` needed. |
| R4 wire compatibility | ✅ | The ledger JSONL format, chain-state report JSON, and gate hook-json payload MUST be byte-compatible with the bash originals. Verified by the 3 `.jq` conformance contracts (`ledger-record-contract.jq`, `chain-state-report-contract.jq`, `gate-hookjson-contract.jq`) as property tests (R-M2). Old fixtures under `tests/fixtures/` round-trip through the ported subcommands unchanged. |
| R5 mutation | ✅ | sbt-stryker4s 0.21.0. `stryker4s.conf` mutate list already includes `probatio/cli/SubcommandEntrypoints.scala` (the primary file this change populates). Retarget to add any new wiring helper files per spec before running. Threshold 90%. |
| R6 formal | ✅ (no new kernel) | The decision logic (`Validator`, `ChainState`, `PredecessorCheck`, `GrantWaiver`, `BannerEngine`) is already Ring-6-verified in `probatio-core` and `probatio-verified`. The wiring layer is I/O adaptation (os-lib file reads, ujson parsing, stdout printing) — not a pure kernel. No new verified mirror is introduced. The bridge property test is the oracle-green check at the `*_OVERRIDE` seam (the bats suite proves the wired subcommand produces the same outcomes as the bash original). |
| R7 model checking | ❌ | No TLA+/Apalache. N/A for this change — no distributed/event-driven invariants in the wiring layer. |
| R8 adversarial review | ✅ | MANDATORY for every code-changing spec. Fresh-context reviewer checks each wired subcommand for: silent fallback mappings, `case _` defaults that hide spec violations, partial functions that crash instead of returning `Outcome.Undetermined`, and stdout-shape divergences from the bash original that the bats oracle does not exercise. |
| R9 telemetry | ❌ | No otel4s/Daut in `workflow/*`. N/A for this change. |
| Concurrency kit | ✅ (not needed) | `TestControl` is available transitively via cats-effect 3.7.0, but this change does NOT touch concurrency. The gate is single-threaded; metals uses blocking I/O. No `TestControl` scenarios required. |
| Code intelligence | ✅ | Metals MCP endpoint at `http://localhost:8394/mcp` (per-project instance). The `metals` subcommand itself is one of the 16 being wired — its wiring uses `MetalsClient` (LSP framing, already in `probatio-core`). For the apply phase's own code-intel needs (impact-scan, removal-audit), the Metals endpoint is available; git grep is the fallback. |
| Bats oracle | ✅ | 17 `.bats` files under `openspec/schemas/verified-scala3/tests/` — the acceptance suite for the strangler migration. Each wired subcommand is verified by setting the corresponding `*_OVERRIDE` env var (`SPEC_LINT_OVERRIDE`, `CHAIN_STATE_OVERRIDE`, `DANGER_SCAN_OVERRIDE`, `GATE_command`) to invoke the `probatio` binary and running the full oracle. The oracle MUST pass unmodified (R-M1). |
