# Capability Check

**Project profile**: `openspec/capability-profile.md` — created 2026-07-18
(seeded from this change's formerly change-scoped profile during the schema
v6 migration to project-scoped living documents).
**Verification result**: CLEAN — the profile was freshly detected on
2026-07-18 against build.sbt / project/Versions.scala / project/Dependencies.scala
and moved as-is; no rows required correction at seeding time.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| none | — | — | profile freshly detected the same day |

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `adk4s-orchestration → adk4s-memory-api` dependency | module dependency | proposal (hook spec); profile's module dependency graph carries the annotation |
| `org.adk4s.orchestration.memory` package | new Ring 2 layer | design.md; domain-purity rules row already in the project profile |

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | yes | exhaustiveness escalation active in `scala3Options`; the new `AgentEvent` variants force all matches to handle them |
| R1 lint | yes | scalafix + WartRemover + scalafmt + danger-scan.sh |
| R2 architecture | advisory | no custom scalafix arch rules; `org.adk4s.orchestration.memory` layer rules enforced by review + import audit |
| R3 property tests | yes | Hedgehog via hedgehog-munit; `cover` assertions; concurrency scenarios via `TestControl` |
| R4 compatibility | n/a | no serialization/wire data touched (checkpoint serialization unchanged) |
| R5 mutation | yes | retarget `stryker4s.conf` mutate list to this spec's changed files |
| R6 formal | n/a | hook is effectful IO wiring, not PureScala |
| R7 model checking | no | no TLA+/Apalache — skip with stated impact |
| R8 adversarial | yes | fresh-context, before Rings 5/6/7 |
| R9 telemetry | no | no telemetry stack — skip with stated impact |
| Concurrency kit | yes | cats-effect `TestControl` (transitive, no extra dep) |
