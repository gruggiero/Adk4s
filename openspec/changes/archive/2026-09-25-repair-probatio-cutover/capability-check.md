# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-09-20 (previously
verified 2026-08-29 by `complete-probatio-cutover`)
**Verification result**: **7 rows corrected** — 6 in the project profile, 1 in
`openspec/config.yaml`. The profile was stale on the two rows this change's Ring 5 and
Ring 6 strategies depend on most, and recorded a code-intelligence endpoint that is not
running.

Detection was performed against the build, not against the previous profile: `build.sbt`,
`project/build.properties`, `project/Versions.scala`, `stryker4s.conf`,
`verified/probatio/` source listing, `.metals/mcp.{url,pid}`, and a live
`metals-call.sh probe`.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Mutation tool | mutate list = ONE file (`**/probatio/plugin/ShimGenerator.scala`) + a `test-filter` pinned to `HookCutoverShimSpec`/`ShimGeneratorSpec` | mutate list = **SIX** files (`packaging/{BudgetVerdict,LatencyMeasurement,ReleaseCheck,ReleaseManifestIO,ReleaseValidator}.scala`, `cli/SubcommandWiring.scala`); **no `test-filter` key present**. Added the conf's own main-sources-only caveat. | `stryker4s.conf` (read 2026-09-20) |
| Formal verification — contents | `probatio-verified/` has **FOUR** PureScala objects | **NINE**: `ConformanceModel`, `ChainStateKernel`, `LedgerValidatorKernel`, `BannerEngineKernel`, `DispatchKernel`, `SpecLintKernel`, `CutoverKernel`, `ReconcileKernel`, `GateKernel` | `find verified/probatio -name '*.scala'` |
| Ring 6 status row | same FOUR-object claim restated in the ring table | corrected to the same NINE | as above |
| Ring 0 row — probatio flags | `probatioScalacOptions` listed as `-Werror`, `-Wconf:cat=deprecation:e`, `-Wconf:cat=feature:e`, `-Wvalue:discard`, `-Ysafe-init` | exactly FOUR flags: `-Werror`, `-Wconf:cat=deprecation:e`, `-Wconf:cat=feature:e`, `-Wsafe-init`. `-Wvalue:discard` appears only in the R-CS3 **comment** and is not active; the init flag is `-Wsafe-init`, not `-Ysafe-init` | `build.sbt:71–76` |
| Metals MCP endpoint | recorded as the project's endpoint, evidence = a probe | endpoint URL retained as *configuration*, with **STATUS 2026-09-20: NOT RUNNING** — probe reports unreachable, `.metals/mcp.pid` (961961) names a dead process | `metals-call.sh probe`; `ps -p 961961` → no such process |
| Ring 3 row — concurrency kit | "Concurrency scenarios use `TestControl`" (unqualified) | qualified: `TestControl` applies **in adk4s modules only**. It ships with cats-effect, which R-ARCH1 forbids on the `workflow/*` classpath, so it is unreachable from `probatio-core`/`probatio-cli`. probatio determinism comes from recorded process outcomes + an injected clock seam | `build.sbt` R-ARCH1 dependency-lint rules; profile's own domain-purity table (probatio-core forbids cats-effect) |
| Modules row | "Re-verified 2026-08-25" | re-verified 2026-09-20: `build.sbt` declares exactly 18 `lazy val … = project` definitions — the 17 listed plus `probatio-spike`; set unchanged | `grep -cE '^lazy val .* = \(?project' build.sbt` → 18 |
| `openspec/config.yaml` (not the profile) | "12 modules (see build.sbt for the current list)" | "18 sbt projects (17 aggregated-or-leaf + `probatio-spike`)" — the profile wins over config.yaml per the artifact instruction | profile Modules row |

Rows re-verified and found **correct**, so left unchanged: Scala 3.8.4 / 3.7.2
(`Versions.scala:9–10`), sbt 1.12.12 (`build.properties`), munit 1.3.3 + munit-cats-effect
2.2.0, Hedgehog 0.13.1, upickle/ujson 4.4.3, os-lib 0.11.8, mainargs 0.7.8, Stryker
thresholds `high=90 / low=80 / break=0`, WartRemover `Warts.unsafe` minus
`TripleQuestionMark`/`Any`/`DefaultArguments`, and the repo-wide exhaustiveness
escalation (`-Wconf:name=PatternMatchExhaustivity:e`, `name=MatchCaseUnreachable:e` in
`scala3Options`).

### Consequence for the proposal

The proposal's Ring 5 paragraph repeated the profile's stale ONE-file + `test-filter`
claim. That was a claim inherited from a document, not a fact read from the build — the
defect class this workflow exists to remove. The proposal has been corrected to the
six-file, no-`test-filter` state and to the main-sources-only constraint, which matters
here because three of this change's specs put their implementation in **test** sources
(`probatio.migration`, `probatio.guard`).

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| Traceability-graph model + three markdown parsers | new `probatio-core` subsystem (no new library — reuses upickle/ujson and the existing `SpecDocumentParser`) | proposal § New Concepts (`TraceabilityGraph`, `GraphNode`, `GraphEdge`, `GraphQuery`, `ReachabilityResult`, `ConceptRegistryDoc`, `InventoryDoc`); `specs/graph-tool-port/spec.md` |
| `graph` subcommand on the CLI surface | `Subcommand` enum case + entrypoint | proposal § Existing Concepts (`Subcommand` regains `Graph`); `specs/graph-tool-port/spec.md` |
| Graph-export JSON | new wire format (Ring 4 subject) | proposal § Verification Strategy, Ring 4; `specs/graph-tool-port/spec.md` |
| Graph reachability-closure kernel | 10th PureScala object in `probatio-verified` | proposal § Verification Strategy, Ring 6; `specs/graph-tool-port/spec.md` |
| CI job running the bats oracle, the differential and the probatio suites | new `.github/workflows/` entry | proposal § Affected Capabilities; `specs/workflow-delivery-hygiene/spec.md` |
| Machine-checked unported-tool register | new checked artifact + `ToolSurfaceClassification` | proposal § New Concepts; `specs/unported-tool-register/spec.md` |

**No new library dependency.** The graph port replaces a python3 script with Scala that
uses only what `probatio-core` already has (stdlib, os-lib at the adapter boundary,
ujson). R-ARCH1 is unaffected. This is checked again at apply Step 12 (build-dependency
delta).

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | yes | `probatioScalacOptions` (`-Werror` + deprecation/feature escalation + `-Wsafe-init`) applies to `probatio-core`/`probatio-cli`; repo-wide exhaustiveness escalation applies everywhere. Adding a `Subcommand` case and new sealed hierarchies is therefore a forcing function — every existing match must handle them or fail to compile. |
| R1 lint | yes | Scalafix `DisableSyntax` + `NoIOInProbatioCore`, WartRemover, `danger-scan` on the diff. shellcheck present for the regenerated shims and the CI job. |
| R2 architecture | yes | `probatioDependencyLint` across all four probatio modules. Load-bearing for `graph-tool-port`: the parsers must sit in `probatio-core` with **no** file I/O or `System.getenv`, all reading in the `probatio-cli` adapter. |
| R3 property tests | yes | Hedgehog 0.13.1 via `HedgehogSuite`; coverage assertions via `cover`, seed-fixing via fixed `Seed`. Plus the bats oracle as the acceptance suite. |
| R3 concurrency kit | **no — substituted** | `TestControl` is unreachable from probatio (cats-effect banned by R-ARCH1; corrected in the profile above). This change spawns two full bats suites, observes subprocess exits, and reads a file tree. Determinism is obtained from recorded process outcomes and an injected clock seam — **a stated substitution with equivalent determinism, not a waiver**. No wall-clock sleeps. |
| R4 wire compat | yes | The three `.jq` contracts remain executable oracles (`jq` present at `/bin/jq`). The graph `export` JSON is a new format: round-trip plus agreement with `openspec-graph.py export` on this repository's corpus (python3 present — the predecessor is runnable as the model). |
| R5 mutation | yes | sbt-stryker4s 0.21.0. **Retarget `mutate` per spec** (current list is the spec-9 six-file set); add a `test-filter` only where needed. `break = 0` → Stryker never fails the build, so a Ring 5 claim must read the **score**, not the exit code. **Main-sources-only constraint applies**: `differential-harness-integrity`, `feature-freeze-guard-integrity` and parts of `ledger-checkpoint-cutover` live in test sources and need the documented move-to-main-and-back procedure. |
| R6 formal | yes | Stainless 0.9.9.3 + smt-z3 (Z3 4.13.4), `probatio-verified` pinned to Scala 3.7.2, wired as `probatio-core dependsOn(probatio-verified % Test)`. Nine kernels already present; this change extends `ChainStateKernel` (UNDETERMINED boundary) and adds a graph reachability-closure kernel. **Invoke directly** — `sbt -J-Xmx6g 'set probatio-verified/stainlessEnabled := true' 'probatio-verified/compile'`; the `ring6` alias is broken under sbt 1.12 (backtick project ids in `addCommandAlias`). |
| R7 model checking | no | No distributed or event-ordering invariant. The graph is a reachability computation over a static corpus; the ledger's append-only discipline is covered by R6. Stated skip, no correctness impact for this change's scope. |
| R8 adversarial review | yes | Fresh-context subagent per spec, before R5/R6. Standing instruction recorded in the proposal: check the shipped artifact as a subprocess, and for spec 1 check whether the mechanism can pass when what it measures is absent. |
| R9 telemetry | no | No telemetry stack on the `workflow/*` classpath and none proposed. Stated skip; correctness impact nil — this change ships no API operation or event sequence. |
| Code intelligence | **no — grep fallback** | Metals MCP endpoint is configured but **not running** (corrected above). Apply Steps 0 and 12 (impact scan, removal audit) use `git grep` for this change unless the endpoint is started and re-probed. Impact: broader, textual result sets — acceptable here because the change's public-type growth is confined to probatio modules that compile under `-Werror` exhaustiveness escalation, which catches the missed-match class the semantic scan exists to find. Note `impact-scan.sh` and `removal-audit.sh` are themselves unported predecessors (see `unported-tool-register`). |
