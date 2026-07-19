# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     This file lets `openspec list` and task tooling report progress; the
     apply phase also tracks detailed state in implementation-progress.md.
     Keep both in sync — check boxes here as each spec completes.

     RULES:
     - One `## <n>. <spec-name>` section per spec, in implementation-order.md order
     - Per-spec checkboxes follow the schema cycle: typed contract (human gate) →
       test oracle (human gate) → implementation → applicable rings → concept-delta
       + inventory update + checkpoint
     - List only the rings that apply to that spec (skip those marked `—` in the
       Ring Applicability table)
     - Prerequisite work (build restructure, deps, static-analysis config) goes
       first in the owning spec's section
     - Every task is observable and stack-specific — never "implement the spec" -->

## 1. [first-spec-name]

- [ ] [prerequisite work, if any — e.g. build/module/dependency setup]
- [ ] Step 1 — typed contract: [new types/signatures] (compiles, human gate)
- [ ] Step 2 — test oracle: scenarios + [N] properties + compile-negative stubs (human gate)
- [ ] Step 3 — implementation
- [ ] Rings: [applicable rings for this spec, e.g. R0 R1 R2 R3 R5 R8]
- [ ] Concept-delta check + update openspec/concept-inventory.md + checkpoint

## 2. [next-spec-name]

- [ ] Step 1 — typed contract: [...] (human gate)
- [ ] Step 2 — test oracle: scenarios + [N] properties (human gate)
- [ ] Step 3 — implementation
- [ ] Rings: [...]
- [ ] Concept-delta check + inventory update + checkpoint

<!-- ... one section per spec ... -->
