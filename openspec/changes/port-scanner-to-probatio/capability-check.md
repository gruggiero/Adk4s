# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-17
**Verification result**: 2 rows corrected (listed below); all other rows match
the build. Re-verification covered `build.sbt` (13 module declarations),
`project/Versions.scala`, `project/Dependencies.scala`, `project/plugins.sbt`,
`stryker4s.conf`, `.scalafix.conf`, `.scalafmt.conf`, and the
`openspec/schemas/verified-scala3/tests/` directory.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Testing → Mutation tool → `stryker4s.conf` fixed `mutate` list | "currently 5 `adk4s-harness-testkit` files: `AgentMiddlewareLaws`, `SemilatticeLaws`, `SimpleHarnessLoop`, `DeterministicChatModel`, `Generators`" | "currently 4 `adk4s-record` files: `RecordedChatModel.scala`, `RecordedEmbedder.scala`, `RecordingToolMiddleware.scala`, `Redaction.scala`" | `stryker4s.conf` `mutate = […]` block read this session; thresholds unchanged (break=90, low=91, high=95) |
| Shell / Script Tooling → "Existing tests for the 11 scripts" | "❌ NONE … no tests do. There is no precedent or convention to follow: the first change to ship bash establishes the `.bats` layout and conventions." | "✅ 17 `.bats` files under `openspec/schemas/verified-scala3/tests/` (chain-state, checkpoint-from-ledger, correctness-invariant, discharge-fidelity, evidence-capture, evidence-ledger, fact-extraction, gate-payload, harness-install-verification, hook-tiers, human-grant-lock, judgment-ring-integrity, judgment-ring-provenance, oracle-ordering-lock, ambient-capture-wiring, ambient-evidence-capture, workflow-hygiene) + `tests/helpers.bash`. The `.bats` layout and conventions are ESTABLISHED — this is the oracle the porting change (R-M1) substitutes against." | `ls openspec/schemas/verified-scala3/tests/*.bats` → 17 files, this session |

**Rows verified CLEAN (no correction needed):**

- Scala 3.8.4 main / 3.7.2 `verified` pin — `project/Versions.scala` lines 8–10.
- sbt 1.12.12 — `project/build.properties`.
- 13 modules — `build.sbt` has 13 `lazy val` module declarations (matches
  profile; the schema instruction's "12 modules" quick-reference is overridden
  by the profile per its own "profile wins" rule).
- All library versions (llm4s 0.3.4, cats-effect 3.7.0, fs2 3.13.0,
  typesafe-config 1.4.9, workflows4s 0.6.2, smithy4s 0.18.55, upickle 4.4.3,
  iron 3.3.2, logback 1.5.34, munit 1.3.3, munit-cats-effect 2.2.0,
  hedgehog 0.13.1) — `project/Versions.scala`.
- WartRemover: plugins.sbt 3.6.1, Versions.scala `SbtWartremover = 3.5.8`
  (stale val, unused) — profile already records this correctly.
- scalafix 0.14.7, stryker4s 0.21.0 — `project/plugins.sbt`.
- Shell tooling versions: bats 1.14.0, shellcheck 0.11.0, shfmt 3.13.1,
  jq 1.6, python3 3.14.5 — all re-confirmed via `--version` this session,
  paths `/home/linuxbrew/.linuxbrew/bin/{bats,shellcheck,shfmt,python3}`,
  `/usr/bin/jq`.
- Exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e,
  name=MatchCaseUnreachable:e`) — `build.sbt` lines 57–58.
- `-Werror` NOT active — `build.sbt` `scala3Options` (lines 47–60) confirmed
  absent; the `verified` module override (line 335) also lacks it.
- Metals MCP endpoint `http://localhost:8394/mcp`, Metals 1.6.7 — profile row
  stands (not re-probed this session; the endpoint is the very thing this
  change ports, so its status during the port is addressed in the ring table
  below).

## Capabilities THIS change introduces

These are declared in the proposal (§3 Architecture, R-CS1–R-CS5,
R-N1–R-N5, R-S1–R-S6) and will be appended to the project profile when the
change implements them (apply Step 12). They are NOT yet in the build.

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `probatio-core` sbt subproject | Scala 3.8.4 library (`org.sinemenda.probatio:probatio-core_3`) | proposal §3; specs/probatio-core/spec.md (R-C1…R-C6, R-C5b) |
| `probatio-cli` sbt subproject | Scala 3.8.4 mainargs CLI (`org.sinemenda.probatio:probatio-cli_3`) | proposal §3; specs/cli-protocol/spec.md (R-P1…R-P6) |
| `sbt-probatio` sbt subproject | sbt 1.x AutoPlugin, Scala 2.12 (`org.sinemenda.probatio:sbt-probatio_2.12`) | proposal §3; specs/sbt-plugin/spec.md (R-S1…R-S6) |
| `mainargs` | CLI arg-parsing library (com.lihaoyi) | proposal §3, R-P4, R-P5; R-X3 allowed-dep set |
| `os-lib` | filesystem/path library (com.lihaoyi) | proposal §3; R-X3 allowed-dep set |
| `scalameta` | Scala AST parsing (concept-scanner port) | proposal §1.1, R-N5; spike-gated (V1) |
| GraalVM `native-image` | build-time native compilation (not a runtime dep) | proposal §3 distribution; R-N1…R-N5; spike-gated (V1/V2) |
| `probatioScalacOptions` build setting | strict scalac flag set (R-CS1…R-CS5) | proposal "Compiler Strictness Requirements"; specs/probatio-core/spec.md |
| dependency-lint rule (R-ARCH1) | build-level check failing on adk4s→probatio classpath reach | proposal §3.1, R-ARCH1; specs/non-goals-guard/spec.md |
| GitHub Releases distribution | native binaries + JAR + SHA-256 + SBOM + sources per platform | proposal §3 distribution; specs/native-packaging/spec.md (R-N3, R-N4) |

**R-X3 dependency boundary (recorded so the profile can enforce it):** the
allowed dependencies of `probatio-core`/`probatio-cli` are scalafmt
(build-only), munit + Hedgehog (test), os-lib, uPickle/ujson, mainargs,
scalameta (concept-scanner only). **cats/cats-effect are explicitly
excluded** — the workflow's shipped artifacts stay dependency-minimal, and
the AGENTS.md FP mandate applies to *product* code under validation, not to
the validating tool. This is a recorded one-line deviation from the repo's
global FP rule, cited in R-X3 and the proposal's Ring 1 note.

**Consequence for the profile's Domain Purity Rules table:** a new row will
be added at implementation time:

| Layer/Package | Must NOT import | May import |
|---|---|---|
| `org.sinemenda.probatio.core` (pure ported logic) | cats, cats-effect, fs2, adk4s-*, llm4s, workflows4s | stdlib, ujson/uPickle, scalameta (concept-scanner only) |
| `org.sinemenda.probatio.cli` (CLI boundary) | cats, cats-effect, fs2, adk4s-*, llm4s, workflows4s | stdlib, ujson/uPickle, mainargs, os-lib, probatio-core |
| `org.sinemenda.probatio.plugin` (sbt AutoPlugin, Scala 2.12) | cats, cats-effect, fs2, adk4s-*, llm4s, workflows4s, probatio-core | sbt APIs, stdlib |

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | ✅ | New `probatio-core`/`probatio-cli` compile under `scala3Options` + R-CS1–R-CS5 strict set (`-Werror`, deprecation/feature escalation, `-Wvalue:discard`, `-Ysafe-init`); `sbt-probatio` compiles under Scala 2.12 for sbt 1.x; shims `bash -n`-gated. The `verified` module's full `scalacOptions` override (build.sbt line 335) does NOT inherit the new flags — deliberate (3.7.2 Stainless pin). |
| R1 lint | ✅ | Scalafix DisableSyntax + WartRemover (relaxed set) + scalafmt on Scala subprojects; `shellcheck` 0.11.0 + `shfmt -i 2 -ci` on shims. **R-X3 excludes cats from probatio** — WartRemover's cats-related warts are moot there; the AGENTS.md FP mandate does not apply to the validating tool (recorded deviation). |
| R2 architecture | ⚠️ → ✅ with setup task | Today Ring 2 is advisory-only (no custom scalafix arch rules). R-ARCH1 adds one build-level dependency-lint rule: fails if any `workflow/*` project's classpath reaches an adk4s module. R-CS5 adds a check that adk4s modules do not gain `-Werror` as a side effect. Both are setup tasks in `tasks.md`. |
| R3 property | ✅ | Hedgehog 0.13.1 (NOT ScalaCheck — the requirements doc R-X3 names ScalaCheck, but the detected stack is Hedgehog per this profile; design phase resolves the discrepancy by using Hedgehog). R-M2 conformance (validator ⊨ `.jq` contract, both directions), R-C5 verdict equality before/after, R-C5b golden-fixture banner conformance. Coverage via Hedgehog `cover`; seed-fixing via fixed `Seed`. |
| R4 wire/persistence | ✅ | R-P3 byte-compatible stdout vs the three `.jq` contracts; R-M2 conformance as property test; ledger append-only round-trip + old-fixture parse; hook payload byte-stability (§4.6). The 17-file bats oracle is itself the wire-compatibility suite. |
| R5 mutation | ✅ | sbt-stryker4s 0.21.0. **Retarget `stryker4s.conf` `mutate` list** from the current 4 `adk4s-record` files to each spec's changed `probatio-core`/`probatio-cli` files before running. Thresholds: 90–95% pure domain logic (validators, verdict logic, banner engine), 80–90% adapters (CLI entrypoints, metals client). No mutation tooling for bash shims — stated impact: 3-line `exec` wrappers, judged by shellcheck + bats oracle. |
| R6 formal | ✅ via verified-mirror | Pure kernels exist: chain-state verdict logic (R-C3), 12-clause ledger validator (R-C1), banner/drift engine (R-C5b). Mirror lives in the `verified` leaf (Scala 3.7.2) modeling the *decision* only; bridge property test binds shipped code to the model. **Caveats**: (a) R-X3 excludes cats from probatio — the mirror uses PureScala stdlib only, no cats; (b) the mirror models the decision (verdict attribution, clause satisfaction), not the ujson/uPickle wire layer; (c) if a separate probatio-scoped mirror leaf is cleaner than reusing `verified/`, the design phase decides — both are viable per `templates/verified-mirror.md`. |
| R7 model checking | ❌ | No TLA+/Apalache detected. Skip with stated correctness impact: the gate's two-lock blocking asymmetry is stateful but single-threaded per session; its invariant is covered by the bats oracle (hook-tiers, human-grant-lock, oracle-ordering-lock) + Ring 8, not by model checking. |
| R8 adversarial review | ✅ (mandatory) | Fresh-context reviewer; runs BEFORE Rings 5/6/7. Especially load-bearing for this change: the reviewer must look for silent fallback mappings, `case _` defaults, and partial functions that could satisfy the bats oracle while violating §4's preservations (collapsing exit-2 into exit-1, dropping a drift root, universalizing the completion tier). |
| R9 telemetry | ❌ | No otel4s/Daut detected. Skip with stated impact: probatio is a build/CLI tool with no runtime telemetry surface. |
| Concurrency kit | ✅ (available, not expected to be needed) | `cats-effect-testkit` `TestControl` is on the test classpath. **But**: probatio-core is pure by construction (R-C3 — IO lives in probatio-cli only); the metals client does blocking LSP request/response, not parallel streams; the gate's blocking tiers are hook-event semantics, not internal concurrency. If any concurrency scenario emerges in design, it uses `TestControl` (never wall-clock sleeps). Stated as available-not-required. |
| Code intelligence | ✅ (with a noted circularity) | Metals MCP endpoint `http://localhost:8394/mcp` (Metals 1.6.7) is available for adk4s-side impact scans (apply Steps 0/12 use `impact-scan.sh`/`removal-audit.sh`). **Circularity**: this change ports the metals client itself (R-C6) — the endpoint being replaced is the one the apply phase uses. During the port, the existing endpoint works; after the port, the new `MetalsClient` serves the same recipes. Git grep is the deterministic fallback throughout. |

## Consequences recorded for downstream artifacts

1. **`specs/*` MUST generate code for Hedgehog, not ScalaCheck.** The
   requirements doc R-X3 names ScalaCheck; the detected stack is Hedgehog
   0.13.1. The design artifact resolves this by using Hedgehog and recording
   the deviation from the requirements doc. This is not a stylistic choice —
   `capability-profile.md` says "NOT ScalaCheck" and the profile wins.
2. **`implementation-order.md` MUST record two setup tasks before any spec
   implementation begins**: (a) the R-ARCH1 dependency-lint rule; (b) the
   R-CS1–R-CS5 `probatioScalacOptions` setting on the probatio subprojects.
   Both are preconditions for Ring 0/Ring 2 discharging on this change.
3. **`tasks.md` Phase 0 carries the V1–V4 spikes** as gated pre-porting tasks
   (per proposal §0 and the requirements doc §7). The gate is: all four
   verified or the change is re-scoped before any porting begins. V1
   (GraalVM native-image of uPickle) and V2 (gate latency budget) are
   hard-blockers — R-N1 states the change does not ship if the latency budget
   is unmet.
4. **The 17-file bats oracle is the porting acceptance suite (R-M1).** The
   profile's corrected row makes this explicit: the `.bats` layout and
   conventions are ESTABLISHED, not something this change invents. The
   `*_OVERRIDE` env seams (`SPEC_LINT_OVERRIDE`, `CHAIN_STATE_OVERRIDE`,
   `DANGER_SCAN_OVERRIDE`, `GATE_command`) are the swap-points.
5. **Ring 6 mirror placement is a design-phase decision**, not a profile
   fact. Both options (reuse `verified/` leaf vs. new probatio-scoped mirror
   leaf pinned to 3.7.2) are viable; the profile records only that the
   mechanism exists and the caveats (R-X3 cats exclusion, 3.7.2 pin,
   decision-only modeling).
