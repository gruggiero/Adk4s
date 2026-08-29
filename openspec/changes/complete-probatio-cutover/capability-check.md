# Capability Check

**Project profile**: `openspec/capability-profile.md` — re-verified 2026-08-29
**Verification result**: **3 rows corrected** (listed below)

The profile was last re-verified 2026-08-25 by `complete-probatio-porting`. Two
of its rows had already drifted by the time that change finished, because the
change itself moved them and the profile was not refreshed at the end.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Mutation tool — `mutate` list | "4 probatio files: `Validator.scala`, `ProvenanceFields.scala`, `Ledger.scala`, `SubcommandEntrypoints.scala`" | ONE file: `**/probatio/plugin/ShimGenerator.scala`, plus a `test-filter` pinned to `HookCutoverShimSpec`/`ShimGeneratorSpec` | `stryker4s.conf` read 2026-08-29 — the `hook-cutover` spec retargeted it and left it retargeted |
| Mutation tool — thresholds | "break=90, low=91, high=95" | `high = 90`, `low = 80`, `break = 0` | `stryker4s.conf` `thresholds` block read 2026-08-29. **`break = 0` means Stryker never fails the build on score** — this is a reporting configuration, not a gate |
| Formal verification — `probatio-verified` contents | "`ConformanceModel`" | `ConformanceModel`, `ChainStateKernel`, `LedgerValidatorKernel`, `BannerEngineKernel` | `ls verified/probatio/src/main/scala/org/sinemenda/probatio/{core,verified}/`; the three kernels landed at commit `f984ccd` (spec 8, provenance-validation) |

A fourth, cosmetic correction: the Modules row said "the 4 probatio modules"
while listing five; corrected to name `probatio-spike` explicitly.

### Why the threshold correction matters to this change

The proposal's verification strategy names Ring 5 thresholds of 80% (CLI
adapters) and 90% (core logic). With `break = 0` the build cannot enforce them.
This change must therefore **read the reported score and record it in the
ledger**, and may not treat a zero exit from `stryker4s` as Ring 5 green. The
`cli-wiring` spec's recorded 16.39% score passed the build for exactly this
reason.

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `probatioOracleDiff` | sbt task — runs the bats oracle twice (predecessor seams vs. probatio seams) in one repository and reports the per-file pass/fail delta | proposal §Approach step 1; `specs/cutover-gate/spec.md` |
| GraalVM native-image build for `probatio-cli` | build/tooling — `NativeImagePlugin` is already enabled and configured (`--no-fallback`, `-O1`, output `target/native-image/probatio`), but **no binary has ever been produced** | proposal §Affected Capabilities; `specs/native-gate-delivery/spec.md` |
| GraalVM toolchain on this host | external prerequisite — required by the above; not currently detected | `specs/native-gate-delivery/spec.md` (setup task) |

No new library dependency is introduced. The allowed-dependency set of
`non-goals-guard` R-X3 (os-lib, upickle/ujson/upack, mainargs, munit,
scalameta, hedgehog) is sufficient for every spec in this change; `scalameta`
is listed as allowed but is **not** needed here because `concept-scanner` is
out of scope.

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | **yes** | `probatioScalacOptions` = `-Werror`, `-Wconf:cat=deprecation:e`, `-Wconf:cat=feature:e`, `-Wsafe-init`, scoped to `probatio-core`/`probatio-cli` only (R-CS5). Repo-wide `scala3Options` additionally escalates `PatternMatchExhaustivity` and `MatchCaseUnreachable` to errors — adding `Event.PostBash` and `UnresolvedReason.Unattributable` will therefore fail Ring 0 until every match is updated, which is the intended forcing function. |
| R1 lint | **yes** | WartRemover 3.5.8, Scalafix DisableSyntax, `danger-scan` on the diff. **Caveat**: `danger-scan.sh` is currently an exec shim onto a probatio stub that returns clean without scanning — Ring 1's dangerous-pattern half is therefore **not currently enforceable**. Until `specs/danger-reconcile-engines` lands, Ring 1 danger-scan MUST be run via `scanner/danger-scan.sh.predecessor.bak` and the invocation recorded in the ledger. |
| R2 architecture | **yes** | `probatioDependencyLint` alias over all four probatio subprojects; R-ARCH1 forbidden set enforced by the `dependencyLint` task in `build.sbt`. |
| R3 property tests | **yes** | munit 1.3.3 + Hedgehog 0.13.1 (`hedgehog-munit % Test`) via `Dependencies.probatioTestDeps`. **Plus** bats 1.14.0 with the 17-file / 282-test oracle at `openspec/schemas/verified-scala3/tests/` — this is the acceptance suite per `migration-protocol` R-M1. NOT ScalaCheck (forbidden for probatio by R-X3). |
| Concurrency kit | **no — and not needed** | cats-effect `TestControl` exists for adk4s modules but **cats-effect is a forbidden dependency for `workflow/*`** (R-ARCH1). The concurrent surface in this change is subprocess execution (`ledger run`, `gate post-bash`), which is tested with recorded process outcomes and an injected clock seam, not with a concurrency test kit. Recorded here so the absence is a stated fact, not an omission. |
| R4 wire compatibility | **yes** | The three `.jq` contracts are executable checkers (`jq -e -f`). jq 1.8.1 present. `ConformanceModel` in `probatio-verified` already mirrors all three. |
| R5 mutation | **yes, with a caveat** | sbt-stryker4s 0.21.0. `mutate` and `test-filter` MUST both be retargeted per spec (the conf is currently pinned to `ShimGenerator.scala`). `break = 0` means the score must be **read and recorded**, not inferred from the exit code. |
| R6 formal | **yes** | Stainless 0.9.9.3 (bundled jar), Z3 4.13.4, frontend pinned to Scala 3.7.2. `probatio-verified` is the mirror leaf and already contains `ChainStateKernel`, `LedgerValidatorKernel`, `BannerEngineKernel`, `ConformanceModel`. `probatio-core dependsOn(probatio-verified % Test)` is wired, so the bridge test runs in `probatio-core/test`. Enable with `sbt -J-Xmx6g ring6`. |
| R7 model checking | **no — not applicable** | No TLA+/Apalache in this project. The only ordering invariant (ledger append-only) is covered by Ring 6. |
| R8 adversarial review | **yes** | Fresh-context subagent per the `openspec-adversarial-review` skill. **Standing instruction for this change** (proposal §Verification Strategy): verify against the built artifact invoked as a subprocess, not against in-JVM function calls. |
| R9 telemetry | **no** | No otel4s/Daut anywhere in `workflow/*`. |
| Code intelligence | **yes** | Metals MCP endpoint reachable: `scanner/metals-call.sh probe` exited 0 on 2026-08-29 (`http://localhost:8394/mcp`, Metals 1.6.7). The Step 0 impact scan and Step 12 removal audit therefore run in **semantic** mode, not git-grep fallback. Relevant because this change **removes** seven `Subcommand` variants and their entrypoint objects — a removal audit is mandatory. |
| shellcheck | **yes** | 0.11.0. Applies to the shims and to any predecessor script this change touches. |

## Reading of the profile that this change depends on

Two profile facts are load-bearing for the proposal and are re-established here
rather than inherited:

1. **`-Werror` IS active for probatio** (`probatioScalacOptions`), while it is
   NOT active repo-wide. Every warning in `probatio-core`/`probatio-cli` is a
   Ring 0 failure. Verified 2026-08-29 by reading `build.sbt:63-76`.
2. **`probatio-core` may not touch cats, cats-effect, fs2, llm4s, workflows4s,
   or adk4s** (R-ARCH1, enforced by the `dependencyLint` task). The new
   filesystem readers (`RepositoryFacts`, `GateStateDir`) must therefore live in
   `probatio-cli`, not `probatio-core`; `probatio-core` receives their results
   as values. This is the same discipline `PredecessorCheck`/`GrantWaiver`
   already follow and it constrains the design of every spec in this change.
