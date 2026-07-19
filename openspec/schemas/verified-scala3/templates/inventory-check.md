# Inventory Check

<!-- Per-change verification report against the PROJECT concept inventory
     (openspec/concept-inventory.md — the living document; see
     templates/concept-inventory.md for its format). This report is what
     gets archived with the change; the inventory itself never is.

     Cases:
     - Project inventory missing → it was CREATED by this change via the
       multi-module semantic scanner (say so, note scanner vs manual scan).
     - Project inventory exists → it was VERIFIED (consistency check below);
       stale rows were fixed PRESERVING their provenance column — never
       re-created from scratch (a fresh scan loses which spec introduced
       each concept). -->

**Project inventory**: `openspec/concept-inventory.md` — <!-- created now (scanner|manual) | verified <date> -->
**Consistency check**: <!-- CLEAN | N stale rows fixed (listed below) -->

<!-- Consistency check = every recorded package path importable, every
     constraint matching the source expression, every generator existing in
     test sources as recorded. Spot-verify with the scanner:
     scanner/scan.sh . --output <scratch> and diff relevant sections. -->

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| <!-- e.g. MemoryEntry --> | <!-- pkg org.adk4s.memory --> | <!-- org.adk4s.memory.api --> | <!-- spec:add-memory-api/store --> |

## Behavioral Concepts (registry pass)

<!-- If the project has a concept registry (openspec/concepts/), run this
     pass AGAINST the registry (do not regenerate it):
     1. scanner/registry-check.sh — paste its summary line.
     2. Flag new candidate concepts/syncs for human review: new command/event
        enum variants not in any action table; new consumers/producers/
        middleware not in any Synchronizations section; new persisted state
        components not owned by any concept (and listed in a "maps ..."
        fold declaration).
     Delete this section if the project has no registry. -->

**registry-check.sh**: <!-- OK / FAILED (paste summary line) -->
**Stale implementation-map rows**: <!-- list or "none" -->
**Unregistered actions / syncs / state components**: <!-- list or "none" -->

## Concepts relevant to THIS change

<!-- Orientation for spec authors: the inventory entries this change will
     reuse (they feed the specs' "Concepts Used" tables) and a preview of
     what it introduces (they feed "Concepts Introduced"). The specs' tables
     remain the commitments; this is a working excerpt. -->

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| <!-- e.g. ChatModel --> | <!-- trait --> | <!-- org.adk4s.core.component --> | <!-- reuse --> |
