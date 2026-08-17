# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-12
**Verification result**: CLEAN against the current build (pre-change). The one
row this change *modifies* (`Refined types: none → Iron 3.3.1`) is a
forward-delta recorded in §"Capabilities THIS change introduces" below; it is
NOT yet applied to the project profile — that happens at apply Step 12.

## Re-verification against the build (2026-08-12)

The project profile was re-read against `build.sbt`,
`project/Versions.scala`, `project/Dependencies.scala`, and
`project/plugins.sbt`. No stale rows found in the rows this change depends on:

| Profile row | Profile value | Build evidence (re-read this session) | Match? |
|-------------|---------------|----------------------------------------|--------|
| Scala version | 3.8.4 (main); 3.7.2 (`verified`) | `Versions.scala:9-10` | yes |
| Modules | 11 | `build.sbt` project blocks | yes |
| Effect system | cats-effect 3.7.0 | `Versions.scala:16`, `Dependencies.scala:17-18` | yes |
| Streaming | fs2 3.13.0 (core+io) | `Versions.scala:17`, `Dependencies.scala:20-23` | yes |
| JSON (internal) | smithy4s `Document` (`JsonValue`) 0.18.55 | `Versions.scala:18`, `Dependencies.scala:37-40` | yes |
| JSON (llm4s boundary) | upickle/ujson 4.4.3 | `Versions.scala:19`, `Dependencies.scala:46-48` | yes |
| Refined types | none | `Dependencies.scala` has no Iron entry | yes (pre-change) |
| Test framework | munit 1.3.3 + munit-cats-effect 2.2.0 | `Versions.scala:23-24`, `Dependencies.scala:78-87` | yes |
| Property testing | Hedgehog 0.13.1 (hedgehog-munit % Test) | `Versions.scala:25`, `Dependencies.scala:58-68` | yes |
| Deterministic concurrency kit | cats-effect `TestControl` | `Dependencies.scala:89-93` (cats-effect-testkit % Test) | yes |
| Mutation tool | sbt-stryker4s 0.21.0 + `stryker4s.conf` | `Versions.scala:35`, `Dependencies.scala:122-123` | yes |
| Formal verification | Stainless (bundled), `verified` leaf @ 3.7.2, `ring6` alias | `build.sbt:269-311` | yes |
| WartRemover | `Warts.unsafe` minus excluded set; `verified` exempt | `build.sbt:27-31`, `build.sbt:280` | yes |
| Exhaustiveness escalation | `-Wconf:name=PatternMatchExhaustivity:e,name=MatchCaseUnreachable:e` | `build.sbt:43-44` (`scala3Options`) | yes |
| Telemetry | none | no otel4s/Daut dep | yes |

## Corrections applied to the project profile

none — the profile rows this change relies on are accurate as of 2026-08-12.
The single row this change *will* modify (`Refined types`) is a forward-delta,
not a correction of a stale value; it is recorded below and applied at
implementation time.

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `io.github.iltotore::iron` 3.3.1 | Refined-types library (core) | proposal §Approach (1); `specs/core-types` |
| `io.github.iltotore::iron-cats` 3.3.1 | Cats typeclass instances for refined types (`Eq`/`Show`/`Order`) | proposal §Approach (2); `specs/core-types` |
| `io.github.iltotore::iron-upickle` 3.3.1 | upickle codec bridge for refined types (serialization round-trip) | proposal §Approach (2), §Risks; `specs/core-types`, `specs/harness-state`, `specs/checkpoint-store-fpoly` |
| `ConfigError` | New `AdkError` variant (typed config/identifier refinement failure) | proposal §New Concepts; `specs/error-hierarchy-dedup` |
| `GraphCompilationError` | New `AdkError` variant (typed graph-compile failure) | proposal §New Concepts; `specs/wio-graph`, `specs/error-hierarchy-dedup` |
| `ValidatedGraph` | Refined type alias `Graph :| GraphValidated` (proof-carrying) | proposal §New Concepts; `specs/wio-graph` |

**Module-scope of the new dependencies** (enforced at apply Step 12 and
verified by Ring 2):

| Module | Iron added? | Justification |
|--------|-------------|---------------|
| `adk4s-core` | yes | `NodeKey`, `ToolsNodeConfig.maxConcurrency` |
| `adk4s-harness-api` | yes | `MiddlewareName`, `StateCell.CellId` |
| `adk4s-orchestration` | yes | `CheckpointId`, `MemoryPolicy`, `GraphExecutor`/`ValidatedGraph` |
| `structured-llm` | yes (for `maxParseAttempts` boundary) | `StructuredLLM` factory |
| `verified` | **NO** | Stainless frontend (3.7.2) incompatible with Iron's `FromExpr` macros; leaf module has no opaque types needing refinement. Ring 2 verifies this exclusion. |
| `adk4s-examples` | no (transitive via core/orchestration) | Examples consume refined types through the public API; no direct Iron dep needed. |

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | yes | `sbt <module>/compile`; exhaustiveness escalation active — adding `AdkError` variants fails every non-exhaustive `AdkError` match (intended). |
| R1 lint | yes | Scalafix `DisableSyntax` (noThrows — the `require` removal helps), WartRemover. Iron's `:|` is a plain type alias — no `Any`/`isInstanceOf` wart. `danger-scan.sh` is Scala-only and applies (this change ships `.scala`). |
| R2 architecture | yes | Verify Iron NOT added to `verified`; verify `ujson` confinement (`NoUjsonIn*`) still holds — `iron-upickle` bridges at the existing upickle boundary only. |
| R3 property | yes | Hedgehog 0.13.1 (`hedgehog.munit.HedgehogSuite`); coverage via `cover`; seed-fixing via fixed `Seed`. No concurrency introduced by this change (refinement is pure; the `IO.raiseError` swap is in an already-sequential fold) — no `TestControl` scenarios required (stated in proposal). |
| R4 wire/persistence | yes | `NodeKey`/`CheckpointId`/`MiddlewareName`/`CellId` serialize via upickle + `JsonValue`. `iron-upickle` is the bridge mechanism. Round-trip properties + existing snapshot tests. |
| R5 mutation | yes | sbt-stryker4s 0.21.0; `stryker4s.conf` has a fixed `mutate` list — **MUST retarget** to each spec's changed files (`NodeKey.scala`, `MemoryPolicy.scala`, `GraphExecutor.scala`, `ToolsNodeConfig.scala`, …) before running. Threshold 90% (pure domain logic). |
| R6 formal | **waived** | Iron's `:|`/`FromExpr`/`RefinedTypeOps` are unverifiable in Stainless (opaque types + inline macros); `verified` module excluded. Numeric-bound logic (`>= 0`, `> 0`) is trivial — no PureScala mirror warranted. Waiver stated in proposal. |
| R7 model checking | no | No TLA+/Apalache in stack. Skip. |
| R8 adversarial review | yes | MANDATORY; fresh-context reviewer before R5/R6/R7. Critical: confirm no silent-fallback lets invalid `recallK` through after `require` → `refineEither`; confirm `ValidatedGraph` cannot be constructed without `graph.compile` succeeding. |
| R9 telemetry | no | No otel4s/Daut. Skip. |
| Concurrency kit | yes (not needed) | `TestControl` available via cats-effect-testkit, but this change introduces no concurrent behavior — not exercised. |
| Code intelligence | yes | Metals MCP endpoint at `http://localhost:8394/mcp` (per-project); `openspec-code-intel` recipes (impact-scan, removal-audit) for the `AdkError` extension and `NodeKey` call-site migration. git grep fallback for CI. |

## Consequences for downstream artifacts

- **specs**: generate Hedgehog properties (NOT ScalaCheck); munit `FunSuite`/
  `HedgehogSuite` (NOT ScalaTest). Round-trip tests use `iron-upickle` +
  existing `JsonValueCodec`.
- **design**: the `AdkError` extension triggers the exhaustiveness escalation
  across every existing `AdkError` match — the design MUST enumerate the
  affected match sites and the new-variant handling for each.
- **implementation-order**: the compile-spike (Iron + `NodeKey` only) is the
  first task — it converts the INFERRED Scala-3.8.4 compatibility into a
  VERIFIED fact before any further migration. Human-gate tier: risk=medium
  ⇒ two separate gates (typed-contract + test-oracle) per spec, not combined.
- **apply Step 12**: update `openspec/capability-profile.md` row
  `Refined types | none` → `Iron 3.3.1 (core + cats + upickle) | adk4s-core,
  adk4s-harness-api, adk4s-orchestration, structured-llm | NOT in verified`.
