# Concept Registry

<!-- Template: copy this file to openspec/concepts/README.md when adopting the
     registry in a project, then run the openspec-extract-concepts skill to
     populate the catalog. Replace bracketed placeholders. -->

Behavioral concepts for [project name], in the sense of Meng & Jackson,
*"What You See Is What It Does: A Structural Pattern for Legible Software"*
(arXiv:2508.14511): a **concept** is an independent unit of user-facing
functionality with a well-defined purpose, its own state, a set of actions,
and an operational principle. Concepts never reference each other;
cross-concept behavior is expressed as **synchronizations** — declarative
`when / where / then` rules.

This registry is **descriptive, not prescriptive**: it names the concepts
already latent in the code and maps them to their current implementation. It
does not require the code to be restructured. Where the implementation
violates the pattern's design rules, the concept file records the deviation
instead of hiding it.

## Relation to `openspec/concept-inventory.md`

The project-scoped `openspec/concept-inventory.md` (its sibling, maintained via `openspec-scan-concepts`) catalogs
**types** — it answers "does this type already exist?" during apply. This
registry catalogs **behavior** — it answers "what is the stable name for the
thing this spec changes?". Spec deltas refer to behavior as `Concept/action`;
code identifiers appear in exactly one place per concept — the
**Implementation map** — so a refactor updates one table, not every spec that
ever mentioned a type.

## File format

One file per concept, `openspec/concepts/<kebab-name>.md` (see
`concept.md` template):

1. **Concept specification** — paper format: `concept Name [TypeParams]`,
   `purpose`, `state` (relational, Alloy-style), `actions` (named args,
   success + error completion cases), `operational principle`.
2. **Implementation map** — table binding each state component and action to
   the code realizing it today. The *only* place code identifiers live.
3. **Synchronizations** — named `when / where / then` rules with an `impl:`
   pointer and noted deviations (e.g. best-effort instead of transactional).
4. **Deviations from the pattern** — where the code breaks concept
   independence, recorded as observations.

## Rules for spec authors

- **Where code may appear.** Requirements and scenarios use behavioral
  vocabulary only: `Concept/action` references, the project's user-facing
  surface language (DSL paths, API fields), new domain terms, and concrete
  test vectors. Module names, error class names, and build commands belong in
  the spec's `## Implementation Anchors` section — never in a Given/When/Then.
- **Negative requirements need adversarial scenarios.** Every requirement
  containing "only", "never", or "must not" gets at least one scenario whose
  input the requirement forbids. Implementations that only pass the positive
  examples do not satisfy the requirement.
- **`MUST-CONFIRM` for external data.** Any classification table, code
  mapping, or value domain whose authoritative source is outside the repo is
  marked `MUST-CONFIRM` in the spec. The apply phase MUST stop and ask for
  the real data — inventing plausible values is a schema violation. Concept
  files record the *provenance* of every value domain they cite.
- **Living document.** When a change alters a concept's actions, state, or
  syncs, updating the concept file is part of implementing that change.

## Machine check

`openspec/schemas/verified-scala3/scanner/registry-check.sh` runs three
passes and must be wired into CI (it is dependency-free — bash + git grep —
so it runs anywhere, including runners that cannot reach the build's
artifact repositories):

1. **Symbols** — every backticked symbol in every Implementation map and
   `impl:` line still exists in the source tree.
2. **Fold fields** — a state-fold row may declare the exact fields the fold
   populates with the convention `maps field1, field2, ...` (plain text, not
   backticked) next to a backticked path to the fold's file. Every listed
   field must appear in that file. USE THIS on every state-bearing concept:
   it is the machine check for the declared-but-never-folded class of bug —
   a field present on the model shape that no fold actually writes.
3. **Spec references** — every `Concept` / `Concept/action` cited in an
   active change spec's "## Concepts Used (behavioral)" table must be
   declared by a registry file (via `# Concept:` headings or `concept X`
   spec lines), and the action must appear in that file.

## Adoption checklist (new project)

1. Copy `templates/concept-registry/README.md` → `openspec/concepts/README.md`.
2. Run `scanner/install-skills.sh` to copy the schema's skill sources into
   the project's agent skill directories — `.claude/skills/`, `.pi/skills/`,
   `.devin/skills/` (repeat after every schema upgrade — these directories
   are often git-ignored, so the copies are local; the schema dir is the
   source of truth).
3. Run the `openspec-extract-concepts` skill to populate the registry.
4. Add a CI job running `scanner/registry-check.sh .` — ready-made templates
   for Azure DevOps, GitLab and GitHub Actions are in the schema's `ci/`
   directory (see `ci/README.md` for where each one goes and whether the
   host needs the pipeline registered).

## Catalog

| Concept | Status | One-line purpose |
|---|---|---|
| [ExampleConcept](example-concept.md) | ✅ | [one line] |

Extraction recipe, should new concepts appear: persistent entity
command/event enums → actions; persisted state structures → state; message
consumers, producers, and HTTP middleware → synchronizations; transport entry
points (HTTP, topics) → bootstrap concepts (§6.7 of the paper).

## Synchronization index

| Sync | Trigger | Effect | Defined in |
|---|---|---|---|
| [SyncName] | [Concept/action] | [Concept/action] | [file.md] |
