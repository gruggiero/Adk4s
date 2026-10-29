# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-09-25 (previously
verified 2026-09-20 by `repair-probatio-cutover`)
**Verification result**: **5 rows corrected, 2 rows added.** Two of the corrections are
rows this change's verification strategy leans on directly: the Stainless mirror, and the
mutation configuration. One correction is a new finding.

Detection was performed against the build and the tools, not against the previous
profile: `build.sbt`, `project/build.properties`, `project/Versions.scala`,
`project/plugins.sbt`, `stryker4s.conf`, the `verified/probatio` source listing, the
built native binary's embedded toolchain string, `.github/workflows/`, a live
`metals-call.sh probe`, and the version of every shell tool on PATH.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Formal verification — contents | `probatio-verified/` has **NINE** PureScala objects | **TEN** — `ReachabilityKernel` was added by `graph-tool-port` and never recorded | `find verified/probatio -name '*.scala' -path '*main*'` → 10 files |
| Ring 6 status row | the same NINE-object list | TEN, `ReachabilityKernel` added | as above |
| Mutation tool | "currently SIX files … **no** `test-filter`" | **Stop recording a snapshot.** Every spec retargets the list, so this row has now been stale in three different ways since 2026-08-25, each within one spec. The row now says to read the file. Observed today: **two** files (`UnportedTool.scala`, `UnportedToolRegister.scala`) **with** a `test-filter` | `stryker4s.conf` |
| Native image | "GraalVM CE 21.0.2" | **Two toolchains.** `build.sbt` pins no `nativeImageVersion`, so local builds use the plugin default; the local binary reports **GraalVM 22.3.1**. `release-probatio.yml` installs **GraalVM CE 21.0.2**. | `strings workflow/cli/target/native-image/probatio` → `GraalVM 22.3.1`; `release-probatio.yml:37,41,45` |
| Metals MCP endpoint | "STATUS 2026-09-20: NOT RUNNING" | re-probed 2026-09-25: **still not running** | `metals-call.sh probe` → unreachable |
| Modules | re-verified 2026-09-20 | re-verified 2026-09-25 — still exactly 18 project definitions, set unchanged | `grep -cE '^lazy val … = project' build.sbt` → 18 |

**Rows added:**

| Row | Content | Why it is needed now |
|-----|---------|----------------------|
| `openspec` CLI | 1.3.1. Resolves a schema by its directory name. Initially recorded as *not established — MUST-CONFIRM*; **then measured while drafting the specs**: a symbolic-link alias resolves an active change pinning the old name, and the CLI does not resolve archived changes at all. Re-established at apply time | `schema-directory-rename` depends on it |
| Continuous integration | `verify.yml` exists but **has never executed**; builds only the JAR; no release tag exists; `.github/` needs `git add -f` | `jar-launcher-dispatch` and `delivery-verified` depend on it |

**Rows re-verified and correct, left unchanged:** Scala 3.8.4 / 3.7.2, sbt 1.12.12,
munit 1.3.3, Hedgehog 0.13.1, upickle 4.4.3, os-lib 0.11.8, sbt-native-image 0.4.0,
`probatioScalacOptions` (exactly `-Werror`, `-Wconf:cat=deprecation:e`,
`-Wconf:cat=feature:e`, `-Wsafe-init`), Stryker thresholds `high=90 / low=80 / break=0`,
shellcheck 0.11.0, bats 1.14.0, the `TestControl` qualification (adk4s modules only).

### A new finding from this pass: the tested binary and the released binary differ

Every test run, every Ring 8 review of the built artifact, and the recorded gate latency
(129.4 ms median, against a 150 ms budget) ran against a **GraalVM 22.3.1** binary. A
release would ship a **21.0.2** binary that no test has run. The latency budget in
particular is a measured property of one toolchain, and it is not established for the
other.

This is not in the proposal as drafted. It belongs with `delivery-verified`, whose job is
to make the delivered artifact the one that was tested: either pin the release toolchain
to the tested one, or run the conformance suite and the latency measurement against the
release toolchain. **The proposal has been amended accordingly.**

### Consequences for the proposal

- The Ring 6 line names `DispatchKernel` (extended for the archive dispatch case) and — as
  corrected while writing the specs — the spec-lint kernel (the sanction decision and the
  registry verifier's pass decision), not the cutover kernel. Both kernels exist, and the
  mirror holds ten, not nine.
- The Ring 5 line already says "retarget `mutate` per spec", which matches the corrected
  row. It needed no change.

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| Explicit invocation-name channel for the JAR path | launcher contract + runtime read (no new library) | proposal § Affected Capabilities; `specs/jar-launcher-dispatch/spec.md` |
| JAR-only subprocess conformance run | test configuration + CI job | `specs/jar-launcher-dispatch/spec.md`, `specs/delivery-verified/spec.md` |
| Hermetic process helper (Scala and bats) | test infrastructure (no new library) | `specs/hermetic-test-processes/spec.md` |
| Two new lints: no raw process construction in tests; no literal active-change path | custom Scalafix rules (the project already ships custom rules, e.g. `NoIOInProbatioCore`) plus a bats-side check | `specs/hermetic-test-processes/spec.md`, `specs/archive-safe-fixtures/spec.md` |
| Implementation-shape suite | new bats suite directory, outside the acceptance oracle | `specs/oracle-independence/spec.md` |
| Oracle sanction record | new persisted file (Ring 4 subject) | `specs/oracle-independence/spec.md` |
| Ported registry verifier | new `probatio-core` engine + `Subcommand` case | `specs/registry-check-port/spec.md` |
| Pinned or verified release toolchain | build setting or CI configuration | `specs/delivery-verified/spec.md` (amended by this check) |

**No new library dependency.** R-ARCH1 is unaffected. This is checked again at apply
Step 12 (build-dependency delta).

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | yes | `probatioScalacOptions` on every probatio module; exhaustiveness escalated repo-wide. The entrypoint split and the `Subcommand`/`ToolId` changes are forcing functions. |
| R1 lint | yes | Scalafix (including custom rules), WartRemover, dangerous-pattern scan, shellcheck 0.11.0. This change adds two lints. |
| R2 architecture | yes | `probatioDependencyLint`, R-ARCH1, `NoIOInProbatioCore`. The entrypoint split must keep decisions in core and I/O in the adapter. |
| R3 property tests | yes | Hedgehog 0.13.1. The acceptance oracle is bats 1.14.0 against the genuine predecessor control, **and** in a JAR-only environment — verified buildable today by a worktree carrying only the assembly JAR. |
| R3 concurrency kit | **no — substituted** | `TestControl` is unreachable from `workflow/*` (R-ARCH1). Determinism comes from recorded process outcomes, an injected clock seam, and — new in this change — a hermetic process environment. A stated substitution, not a waiver. |
| R4 wire compat | yes | The three `.jq` contracts (`jq` 1.6 present); the graph export; the new sanction record; the state-directory migration; **schema resolution of every active change under both names** — the symbolic-link alias was measured to work on openspec 1.3.1 while drafting the specs; the CLI does not resolve archived changes at all. |
| R5 mutation | yes | sbt-stryker4s 0.21.0. Retarget per spec; the current list is the previous spec's two files plus its test filter. `break = 0`, so the **score** is read. Main sources only — the `migration` and `guard` packages need the move-to-main-and-back procedure. |
| R6 formal | yes | Stainless 0.9.9.3, `probatio-verified` pinned to Scala 3.7.2, **ten** kernels present. Invoke directly; the `ring6` alias is broken under sbt 1.12. |
| R7 model checking | no | No distributed or event-ordering invariant. Stated skip. |
| R8 adversarial review | yes | Fresh-context subagent per spec. The standing instruction to review **in a JAR-only environment with harness session variables set** is feasible: both conditions were reproduced during this change's research. |
| R9 telemetry | no | No telemetry stack on `workflow/*`. Stated skip. |
| Native image build | yes, via the plugin | `native-image` is not on PATH; `sbt probatio-cli/nativeImage` fetches its toolchain through coursier. That toolchain is **22.3.1**, not the release's 21.0.2 (see the finding above). |
| Continuous integration | **conditional** | `verify.yml` exists but runs only once the branch reaches GitHub. Pushing is an outward action: `delivery-verified` asks the maintainer to authorise it at apply time. |
| Code intelligence | **no — grep fallback** | Metals endpoint not running (re-probed today). Apply Steps 0 and 12 use text search unless it is started and re-probed. `impact-scan` and `removal-audit` are themselves unported predecessors. |
