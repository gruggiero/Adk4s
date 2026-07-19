# Capability Check

<!-- Per-change verification report against the PROJECT capability profile
     (openspec/capability-profile.md — the living document; see
     templates/capability-profile.md for its format). This report is what
     gets archived with the change; the profile itself never is.

     Cases:
     - Project profile missing → it was CREATED by this change (say so, and
       summarize the detection evidence).
     - Project profile exists → it was RE-VERIFIED against the build; every
       corrected row is listed below. -->

**Project profile**: `openspec/capability-profile.md` — <!-- created now | verified <date> -->
**Verification result**: <!-- CLEAN (profile matches build) | N rows corrected (listed below) -->

## Corrections applied to the project profile

<!-- Rows added/fixed during re-verification, with evidence. "none" if clean. -->

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| <!-- e.g. workflows4s version --> | <!-- 0.4.2 --> | <!-- 0.6.2 --> | <!-- build.sbt / Versions.scala --> |

## Capabilities THIS change introduces

<!-- New modules, libraries, or tooling this change itself adds (cross-check
     the proposal's scope and the Step 12 build-dependency delta). These are
     appended to the project profile when the change implements them.
     "none" if not applicable. -->

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| <!-- e.g. otel4s --> | <!-- telemetry lib --> | <!-- proposal §, spec X --> |

## Ring availability for THIS change

<!-- The consequence table the proposal's verification strategy relies on:
     which rings are available/unavailable for this change given the profile
     (mutation tool present? Stainless? telemetry stack? deterministic
     concurrency kit?), each unavailable ring with its stated impact. -->

| Ring | Available | Note |
|------|-----------|------|
| R5 mutation | <!-- yes/no --> | <!-- retarget stryker4s.conf mutate list --> |
| R6 formal | <!-- yes/no --> | <!-- verified module / skip impact --> |
| R9 telemetry | <!-- yes/no --> | <!-- skip impact or setup task --> |
| Concurrency kit | <!-- yes/no --> | <!-- TestControl availability --> |
| Code intelligence | <!-- yes/no --> | <!-- Metals MCP endpoint status; recipes vs grep-fallback for this change --> |
