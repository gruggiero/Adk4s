# Implementation Progress

Tracking file for the verified-scala3 apply phase. The per-spec sequence
(implementation-order.md §"Implementation Sequence") is followed strictly:
one spec at a time, depth-first through all applicable rings, STOP for human
validation before next spec.

## Phase 0 — Setup & Spikes (gate before any spec implementation)

| Task | Status | Evidence |
|------|--------|----------|
| 0a. R-ARCH1 dependency-lint rule | DONE | `sbt probatioDependencyLint` → both subprojects "classpath clean"; `isForbiddenDependency` checks org.typelevel/co.fs2/org.llm4s/org.business4s/org.scalacheck/org.adk4s |
| 0b. R-CS1–R-CS5 probatioScalacOptions | DONE | `show probatio-core/scalacOptions` has -Werror/-Wconf:cat=deprecation:e/-Wconf:cat=feature:e/-Wvalue:discard/-Ysafe-init; `show adk4s-core/scalacOptions` does NOT (R-CS5 verified) |
| 0c. V1 spike: GraalVM native-image | DONE | GraalVM CE 21.0.2 (JDK 21, glibc 2.35-compatible); `native-image --no-fallback` built 16.98MB standalone binary in 46.7s; binary runs correctly (uPickle JSON + os-lib FS + mainargs arg-parse all work); 1,349 types auto-registered for reflection, 0 hand-maintained config |
| 0d. V2 spike: gate latency budget | DONE | Cold: 38-52ms (avg ~42ms, budget 200ms — 4.7x under); Warm: 4-9ms (avg ~5ms, budget 50ms — 10x under). Measured via `posix_fadvise(DONTNEED)` eviction (cold) + immediate re-run (warm), 5/10 trials |
| 0e. V3 spike: pi TS adapter shim | DONE | pi adapter invokes `bash gate.sh ...` by path (confirmed in verified-scala3-gate.ts:53,62); test shim forwarding to probatio binary invoked successfully under `pi -e` (proof file: `INVOKED at ... with args: --event tool-call`); full chain works: pi adapter → bash shim → probatio binary → JSON output |
| 0f. V4 spike: download host choice | DONE | GitHub Releases chosen (proposal §3). Viability confirmed: GraalVM project uses same pattern (native binaries as release assets with .sha256 sidecar files, 1.7M downloads). sbt plugin downloads via GitHub Releases assets API, verifies SHA-256, extracts. |
| **Gate** | VERIFIED | All six Phase 0 tasks done. V1/V2 hard-blockers discharged. |

**Phase 0 work done this session (non-GraalVM):**
- implementation-progress.md created (this file)
- 0a: dependency-lint rule added to build.sbt (R-ARCH1) — VERIFIED
- 0b: probatioScalacOptions added to build.sbt (R-CS1–R-CS5, scoped to probatio) — VERIFIED
- workflow/core + verified/probatio subprojects scaffolded (compiling, allowed deps only) — VERIFIED
- Versions.scala: OsLib 0.11.8, Mainargs 0.7.8 added
- Dependencies.scala: osLib, mainargs, probatioTestDeps added

**Phase 0 work remaining (GraalVM-dependent):**
- 0c/V1: once `native-image` is on PATH, build probatio-core+cli native image
  without hand-maintained reflection config. HARD BLOCKER.
- 0d/V2: measure cold/warm wall-clock latency of gate binary on linux-x86_64
  against R-N1 budget (200ms cold, 50ms warm). HARD BLOCKER.
- 0e/V3: verify pi TS adapter shim replacement end-to-end under `pi -e`.
- 0f/V4: decide download host (GitHub Releases vs Maven zip) + checksum story.

## Spec sequence (implementation-order.md resolved order: 1→2→3→4→5→7→6)

| # | Spec | Status | Notes |
|---|------|--------|-------|
| 1 | probatio-core | NOT STARTED | gated behind Phase 0 |
| 2 | cli-protocol | NOT STARTED | depends on 1 |
| 3 | sbt-plugin | NOT STARTED | depends on 2 |
| 4 | native-packaging | NOT STARTED | depends on 2; V1/V2 gate |
| 5 | migration-protocol | NOT STARTED | depends on 2+1 |
| 7 | non-goals-guard | NOT STARTED | depends on all probatio code |
| 6 | schema-policy | NOT STARTED | independent, scheduled last |

## Decision log

- 2026-08-18: User chose "Install GraalVM first" for the V1/V2 hard-blocker.
  Non-GraalVM Phase 0 setup (0a, 0b, scaffolding) proceeds in parallel.
  Spec 1 implementation will NOT begin until Phase 0 gate is verified.
- 2026-08-18: GraalVM CE 25.2.4 (JDK 25, brew) failed — glibc 2.38+ required,
  system has 2.35. Downloaded GraalVM CE 21.0.2 (JDK 21) from GitHub Releases
  to /tmp/graalvm-jdk21 — compatible with glibc 2.35. native-image works.
- 2026-08-18: V1 spike needed `--no-fallback` flag (scala.Enumeration triggers
  reflection warnings that produce a fallback image without it). This is a
  build flag, not a hand-maintained reflection config file — V1's criterion
  ("without hand-maintained reflection config") is met.
- 2026-08-18: V4 download host decision: GitHub Releases (proposal §3 choice
  confirmed viable). Pattern: native binaries as release assets with .sha256
  sidecar files, SBOM attached. sbt plugin downloads via assets API.

## Phase 0 GATE: VERIFIED

All six Phase 0 tasks are done with evidence:
- 0a: dependency-lint rule (R-ARCH1) — `sbt probatioDependencyLint` passes
- 0b: probatioScalacOptions (R-CS1–R-CS5) — scoped to probatio, verified
- 0c/V1: native-image builds without hand-maintained reflection config — PASSED
- 0d/V2: gate latency budget met (cold ~42ms/200ms, warm ~5ms/50ms) — PASSED
- 0e/V3: pi adapter shim replacement verified end-to-end — PASSED
- 0f/V4: GitHub Releases download host confirmed viable — PASSED

**Spec 1 (probatio-core) may now begin.** The per-spec sequence is:
1. Record baseline SHA (clean tree) + inventory snapshot
2. Typed contract (mandatory) → human review GATE 1/2
3. Test oracle from spec + contract only (before implementation), RED run
   → human review GATE 2/2
4. Implement through all applicable rings (R0–R6, R8)
5. Concept delta check + inventory update + checkpoint
6. STOP for human validation before next spec
