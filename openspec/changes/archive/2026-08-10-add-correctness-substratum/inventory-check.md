# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-08 (existed; verified and refreshed in place, provenance preserved)
**Consistency check**: **TABLES CLEAN — 5 stale prose/metadata items corrected**

This change touches **no Scala source**. It is shell, YAML and markdown, so it
uses and introduces zero entries in the type inventory. That makes this
artifact almost entirely a *project-level verification* rather than a
change-scoped one — and the verification found real staleness.

## Verification method

Ran the SEMANTIC scanner rather than a manual pass:

```
scanner/scan.sh . --output <scratch>/inv-verify.md
→ 7 opaque types, 63 sealed types, 294 case classes,
  15 service traits, 45 smithy models, 123 generators   (0 parse failures)
```

scala-cli 1.5.0 + Scalameta. **This matters**: the inventory's own Consistency
Check told future readers not to bother (see item 2 below).

Opaque types — the smallest well-defined set, so the sharpest diff — match
**exactly, 7/7**: `ToolSchema`, `RunPath`, `NodeKey`, `FieldPath`,
`StateCell.CellId`, `MiddlewareName`, `Schema`.

## Stale rows fixed

No **table** row was stale — the inventory's data is current. Every correction
was to the prose and metadata *describing* that data, which is a distinct and
more insidious failure: the numbers were right, the sentences about them were
not, and only the sentences were ever read.

| # | Item | Was | Now | Provenance kept |
|---|------|-----|-----|-----------------|
| 1 | `## Concepts This Change Will Introduce` heading + body | *"the `add-cross-run-memory-example` change (**this change**)"* — that change was **archived 2026-07-26** | Renamed `## Per-Change Provenance`, with an explicit dated per-change subsection (matching the add-eval-core pattern already used below it) | ✅ all `Introduced By` cells untouched |
| 2 | Consistency Check — scanner note | *"The semantic scanner's 0-result run is a known limitation (it expects a top-level `src/`); the manual scan is authoritative."* | **Obsolete since schema v6**, which fixed multi-module discovery. Re-verified today: scanner runs clean, finds 294 case classes. Replaced with the actual run result. | n/a |
| 3 | Consistency Check — opaque count | *"the **five** opaque types"* | **7** — has been since `add-harness-api-phase0` added `MiddlewareName` and `StateCell.CellId` | n/a |
| 4 | Registry pass — concept count | *"(25 concepts)"* | **31** | n/a |
| 5 | Registry pass — check result | `604 tokens, 0 spec refs` — run **2026-07-18** | `740 tokens, 15 spec refs` — run **2026-08-08** | previous entry retained inline for comparison |

### Item 2 is the one that cost something

The note did not merely go stale — it **instructed future verifications to
distrust a working tool**. Schema v6's changelog names this precise defect
("previously a silent empty scan on multi-module builds") and fixed it. But the
*consequence* of the bug had been written into the project's living document as
a standing limitation, and nothing re-tested it. Every consistency check since
has been manual while a semantic scanner sat available.

A recorded limitation must be re-tested, not inherited. This is the same shape
as the schema v11 defect (`generatedBy` recorded and never read) and is
directly load-bearing on this change's thesis.

## Behavioral Concepts (registry pass)

**registry-check.sh**: `OK (740 implementation-map tokens verified, 15 spec concept references checked, 2 weak binding(s) to tighten)` — exit 0.

**Stale implementation-map rows**: none.

**Weak bindings (2, pre-existing, non-blocking)**: `react-agent.md` cites
`isDefined` and `foreach` against
`adk4s-orchestration/.../agent/ReactAgent.scala`; both are stdlib `Option`
methods, not identifiers declared in that file. Not caused by this change.
Tighten by citing their own file or dropping them from the map.

**Flags cleared (resolved by shipped code, never cleared in the document)**:

| Flag as written | Actual state 2026-08-08 |
|---|---|
| *"NEW candidate concept `MemoryHook` / `MemoryAwareRunner` — does not yet have a registry file"* | ✅ `openspec/concepts/memory-aware-runner.md` **exists** |
| *"EXTENDED concept `AgentEventStream` — updating `agent-event-stream.md` is PART OF implementing the events spec"* | ✅ **done** — the file carries `MemoryRecalled` / `MemoryWritten` (4 references) |

Both flags were accurate when written and became false when the work landed.
Nothing re-read them, so the inventory has been reporting completed work as
outstanding for roughly three weeks. A resolved flag must be cleared by the
change that resolves it.

**Unregistered actions / syncs / state components**: none outstanding.

## Concepts relevant to THIS change

**Reused from the inventory: NONE. Introduced into the inventory: NONE.**

This is a deliberate and checkable claim, not an omission. The change ships
bash, YAML and markdown; it declares no Scala type, touches no `package`
clause, and adds no generator. Consequences:

- The specs' "Concepts Used (from inventory)" and "Concepts Introduced" tables
  will be **empty**, and that is the correct content — not a gap for spec-lint
  check 5/6 to flag.
- The Step 12 **concept delta** for this change must be **empty**. The
  before/after scanner snapshots should be identical; any difference is a
  defect, since no Scala source is in scope.
- The Step 12 **build-dependency delta** is likewise empty for `build.sbt` /
  `project/` — but **not** empty overall: the new host-tool prerequisites
  (`jq`, `shellcheck`, `bats`, `shfmt`) are an unreviewed behaviour source
  exactly like a library and must be reported there. See capability-check,
  open item 4.

The concepts this change *does* introduce — Correctness Invariant, Evidence
Ledger, Chain State, Hook Heartbeat — are **workflow-level**, not Scala types.
They belong in the proposal's New Concepts table (where they are recorded) and
NOT in `openspec/concept-inventory.md`, whose scope is the project's Scala type
surface. Adding them there would corrupt the inventory's meaning.

### Behavioural registry

This change does **not** alter any concept's purpose, state, actions, or
synchronizations — it changes the *workflow that produces* concepts, not the
concepts themselves. No `openspec/concepts/*.md` file needs updating, and
`registry-check.sh` must still pass at Step 12.

**However**, the specs for this change will still be checked by
registry-check's pass 3 (it verified 15 spec concept references today, from
`add-harness-api-phase0`). If this change's specs cite no behavioural concepts
— the expected outcome — pass 3 simply finds nothing to check for them. The
ALTITUDE rule (spec-lint check 17) **APPLIES** regardless, because the registry
exists: `N/A` is not an available verdict for it, per the CONTEXT block
`spec-lint.sh` prints.
