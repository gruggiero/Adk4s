# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — created 2026-07-18
(seeded from this change's formerly change-scoped inventory during the schema
v6 migration; that inventory was a manual/regex scan cross-checked against
the archived `2026-07-05-add-memory-api` change, preserving provenance).
**Consistency check**: 3 gaps found and fixed (listed below) — verified with
the fixed MULTI-MODULE semantic scanner (`scanner/scan.sh`, 383 rows on this
repo; the pre-fix scanner silently found 0 on multi-module builds, which is
why the manual scan existed).

## Stale rows fixed

<!-- These were MISSING rows (manual-scan gaps caught by the scanner), added
     with scan provenance — no existing provenance was touched. -->

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| `FallbackSemantic` | missing | enum row, `org.adk4s.core.runnable` | scan:RunnableOps.scala (new row) |
| `GraphWorkflowContext.Event` | missing | nested sealed trait row, `org.adk4s.orchestration.execution` | scan:GraphWorkflowContext.scala (new row) |
| `SectionType` | missing | enum row, `org.adk4s.structured.template` | scan:PromptSyntax.scala (new row) |

Scanner-only names `consolidates/definitions/prefixing/value/values` were
inspected and are regex false positives (words in prose/comments), not types.
Scanner scope note: the scanner also reports `adk4s-examples` and `verified`
module types; the inventory deliberately omits them (application-edge code
and formal-verification mirrors, per its scope note).

## Behavioral Concepts (registry pass)

**registry-check.sh**: OK (604 implementation-map tokens verified, 0 spec
concept references checked, 2 weak binding(s) to tighten)
**Stale implementation-map rows**: none
**Unregistered actions / syncs / state components**: none flagged — the
change's new `MemoryRecalled`/`MemoryWritten` events and the memory hook are
declared in this change's specs; registry updates land with implementation
(apply Step 12). WEAK bindings in `react-agent.md` (`isDefined`, `foreach`
not in the cited file) are tightening candidates, not failures.

## Concepts relevant to THIS change

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `AgentEvent` | sealed trait | `org.adk4s.core.interrupt` | extend (adds `MemoryRecalled`, `MemoryWritten`) |
| `RunResult` | sealed trait | `org.adk4s.orchestration.agent` | reuse (`MemoryAwareRunner` matches on it) |
| `AgentMemory[F]` | service trait | `org.adk4s.memory` | reuse |
| `Episode`, `SourceType`, `MemoryHit` | case class / enum | `org.adk4s.memory` | reuse |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` | reuse (decorated) |

The specs' "Concepts Used / Introduced" tables remain the commitments; this
is the working excerpt.
