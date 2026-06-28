# Proposal: [Change Title]

## Why

<!-- Why is this change needed? What problem does it solve?
     Be specific about the business or technical motivation.
     (OpenSpec requires a `## Why` heading — keep this name.) -->

## What Changes

<!-- What capabilities are affected? What is explicitly OUT of scope?
     (OpenSpec requires a `## What Changes` heading — keep this name.) -->

### Affected Capabilities

<!-- List the spec files that will be created or modified.
     Use the format: specs/<capability-name>/spec.md -->

- `specs/<capability-1>/spec.md` — [brief description]
- `specs/<capability-2>/spec.md` — [brief description]

### Out of Scope

<!-- Explicitly state what this change does NOT include. -->

## Approach

<!-- High-level approach to solving the problem. Not the full design
     (that comes in design.md) — just enough to evaluate feasibility. -->

## Correctness Risk Level

<!-- low / medium / high, with one-line justification.
     High-risk examples: evaluators, typecheckers, desugarers, mappers,
     money arithmetic, persistence schema changes, anything with
     fallback/default paths. -->

**Risk**: [low / medium / high] — [justification]

## Verification Strategy

<!-- Check which verification rings apply to this change.
     Ring 0 and Ring 1 always apply.
     Ring 3 and Ring 8 are MANDATORY for every code-changing spec.
     Ring availability comes from capability-profile.md — do not check a ring
     whose tooling is absent without adding a setup task. -->

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern scan
- [ ] Ring 2: Architecture — project-specific layer dependencies, sealed domain types, effect discipline
- [x] Ring 3: Property-based tests — MANDATORY (waiver only for docs/formatting/build-metadata/test-only changes; state waiver + rationale here if claimed)
- [ ] Ring 4: Wire/persistence compatibility — round-trips, old fixtures, snapshots (REQUIRED if serialization/persistence/wire data is touched)
- [ ] Ring 5: Mutation testing — Stryker4s on changed files, threshold ____% (90–95% pure domain logic, 80–90% adapters)
- [ ] Ring 6: Formal verification — Stainless, PureScala modules only
- [ ] Ring 7: Model checking — TLA+/Apalache for distributed/event-driven invariants
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY for code changes
- [ ] Ring 9: Telemetry — span contracts, temporal monitors (only if telemetry stack detected)

## Typed Contract Decision

<!-- The typed contract phase is MANDATORY for every code-changing spec.
     Classify each affected spec. A waiver requires explicit human approval. -->

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract (full/minimal/waiver) | Justification |
|------|--------------------------------------|---------------|
| <!-- specs/x/spec.md --> | <!-- full --> | <!-- introduces new ADT --> |

## Existing Concepts to Reuse

<!-- Reference entries from concept-inventory.md that this change will use.
     If the inventory is empty (new project), write "None — new project." -->

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| <!-- e.g. AccountId --> | <!-- e.g. opaque type --> | <!-- e.g. domain --> | <!-- e.g. reuse as-is --> |

## New Concepts to Introduce

<!-- Preview of domain types, service methods, error variants, or IDL
     operations that this change will create. These are refined in the
     spec and design phases — this is a directional preview. -->

| Concept | Kind | Purpose |
|---------|------|---------|
| <!-- e.g. TransferResult --> | <!-- e.g. case class --> | <!-- e.g. holds from/to balances --> |

## Risks and Mitigations

<!-- What could go wrong? How will you detect and handle it? -->
