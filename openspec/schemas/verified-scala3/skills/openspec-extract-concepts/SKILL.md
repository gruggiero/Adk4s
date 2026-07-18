---

name: openspec-extract-concepts
description: >
  Bootstrap the behavioral concept registry (openspec/concepts/) for a project
  by extracting concepts — purpose / state / actions / operational principle,
  plus synchronizations — from the existing code. One-time per project (then
  the registry is maintained as a living document by every change).
metadata:
  generatedBy: verified-scala3-schema/1.1.0
---

# Extract Concepts Skill

## Purpose

Populate `openspec/concepts/` with behavioral concepts in the Meng & Jackson
sense (*"What You See Is What It Does"*, arXiv:2508.14511). The registry gives
specs a stable behavioral vocabulary (`Concept/action`) so they survive
refactors; the Implementation map in each concept file is the single place
code identifiers live.

## When to Use

- Once, when adopting the verified-scala3 workflow's concept registry in a
  project (new or existing).
- Again only if a whole new subsystem lands without specs (rare — normally
  changes maintain the registry incrementally).

## Prerequisites

Copy the templates first:

- `openspec/schemas/verified-scala3/templates/concept-registry/README.md`
  → `openspec/concepts/README.md` (fill placeholders)
- `openspec/schemas/verified-scala3/templates/concept-registry/concept.md`
  is the per-concept skeleton.

## Extraction recipe

Work from the code, not from documentation. The registry is **descriptive**:
record what the code does, including its defects, as deviations — never
idealize.

### 1. Find the concepts

| Code signal | Concept element |
|---|---|
| Persistent entity command/event enums (one enum variant group per facet) | one concept per facet; variants → **actions** |
| Persisted state structures (per-entity state fields) | **state** (write Alloy-style relational declarations) |
| Pure computation services invoked per request (no persistent state of their own) | computation concepts — state may be empty or cache-only; inputs become action **arguments** (honest signatures) |
| Reference data compiled into the binary | degenerate concepts (state, no actions) — keep them if specs need a stable name for the data |
| HTTP servers, topic consumers (transport entry points) | **bootstrap concepts** (paper §6.7): `Web`, `TopicIngestion[Topic]` — root actions only |

Rules of thumb learned in the pilot:

- A shared service/entity hosting several facets is **not** one concept — check
  whether any command handler reads another facet's state slice. If none
  does, the facets are independent concepts co-located for persistence; the
  shared host goes in each Implementation map's "runtime host" row.
- Datatype libraries (expression ASTs, evaluators, codecs) are **not**
  concepts. Their user-facing surface (e.g. DSL paths) is vocabulary that
  concept specs may use; the modules themselves are listed in specs'
  Implementation Anchors, not here.

### 2. Extract actions with honest completion cases

For each command handler, read the code and record **every** completion case
— the success reply and each rejection, with the exact conditions. Error
messages in the concept file should match what the code actually returns.
If validation happens at fold/replay time instead of command time, that is a
**deviation**, not an action case — record it.

### 3. Find the synchronizations

| Code signal | Sync |
|---|---|
| Consumer handler: topic message → entity command | ingestion sync (`when Topic/message ... then Concept/record`) |
| Producer invoked after an entity ack | notification sync — note best-effort vs transactional as a deviation |
| Endpoint code calling a second concept after the first acks (backfills, enrichment) | request-path sync — almost always best-effort; record it |
| HTTP middleware intercepting generic endpoints for a specific payload kind | upload/dispatch sync |
| Imperative multi-step recipes in service impls (read A, read B, compute C, respond) | rewrite as a single sync with `where`-clause reads |

Every sync gets: paper-style `when/where/then` block, an `impl:` pointer,
and a deviation note. Add every sync to the README's Synchronization index.

### 4. Record value-domain provenance

For every classification table, code mapping, or enum vocabulary the concept
relies on: record where the authoritative values live (source system, doc,
spreadsheet) and whether they are confirmed. Unconfirmed domains are what
specs must mark `MUST-CONFIRM`.

### 5. Verify before finishing

Run `openspec/schemas/verified-scala3/scanner/registry-check.sh` — every
Implementation-map row must verify. Then cross-check the catalog table in
README.md and the Synchronization index.

## Output

- `openspec/concepts/README.md` — conventions + catalog + sync index
- `openspec/concepts/<kebab-name>.md` — one per concept
- A short report: N concepts, N syncs, deviations worth human attention
  (defects surfaced during extraction are a feature — list them).

## Important Rules

- DESCRIBE what exists. Do NOT invent behavior, error cases, or value
  domains. If a fact cannot be established from the code, mark it
  MUST-CONFIRM instead of guessing.
- Code identifiers go ONLY in Implementation maps and `impl:` lines.
- Deviations are observations, not TODOs — recording them does not commit
  anyone to fixing them.
