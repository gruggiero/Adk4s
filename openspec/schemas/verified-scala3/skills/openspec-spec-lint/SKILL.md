---
name: openspec-spec-lint
description: >
  Lint every spec in an OpenSpec change for ambiguity, testability, concept
  resolution, generator strategies, and proof-obligation completeness, and
  write spec-lint.md. Fails fast if any spec is too ambiguous to implement
  safely — a FAIL verdict blocks design and implementation-order.
globs:
  - "openspec/changes/*/specs/**/*.md"
  - "openspec/concept-inventory.md"
  - "openspec/capability-profile.md"
  - "openspec/changes/*/spec-lint.md"
metadata:
  generatedBy: verified-scala3-schema/13.0.0
---

# Spec Lint Skill

## Purpose

Specs are the oracle for everything downstream (typed contract, test oracle,
adversarial review). An ambiguous or untestable spec produces code that
passes its own weak tests while violating intent. This skill checks every
spec BEFORE implementation and blocks progress until specs are precise.

## When to Use

- When creating the `spec-lint` artifact (after specs, before design)
- After any spec edit during implementation (re-lint the changed spec)
- Manually via `/opsx:spec-lint`

## Mechanical Pre-Pass

Before any judgment checks:

1. Run `openspec validate --strict` — it must pass.
2. Run `openspec/schemas/verified-scala3/scanner/spec-lint.sh <change-dir>`
   — it mechanically enforces the greppable subset (SHALL/MUST placement,
   scenario presence for only/never/must-not requirements, generator-strategy
   lines, Proof-Obligations presence, temporal trigger/response lines, the
   mandated obligation Source format, and requirement→obligation
   reachability) and reports vague-word candidates. Its FAILs are lint
   FAILs; paste its summary into the report.
   - **F6** — an obligation `Source` cell names nothing resolvable. The
     mandated format (specs rule 8): `Requirement: <exact title>`
     (preferred), `Requirement N` / `RN` (positional), or a TYPED
     non-requirement source (`Property: <name>`, `Property N`,
     `Scenario: <heading>`, `Invariant:`, `Compile-Negative:`, `Temporal:`,
     `Criterion:`, `MUST-CONFIRM:`), combinable with " + ". A bare
     "Requirement" with no identifier names nothing → FAIL.
   - **F7** — a requirement that NO obligation names: unenforced. This is
     check 12 as reachability and supersedes the row-count heuristic (W2):
     a table with enough rows still fails if a requirement is unnamed.
   - **F8** — a typed source names a `Property:`/`Scenario:` heading that
     does not exist in the spec. A mistyped reference binds to nothing.
   - **W4** — ordinal references; reordering requirements silently
     re-points them. Prefer exact titles.
   - **W5** — the normative statement claims a state is IMPOSSIBLE
     ("cannot be constructed", "unrepresentable") but the obligation is
     enforced only by tests. The ladder's top tiers exist for that claim
     shape; silence it with `tier-justified: <why not>` in Enforcement.
   - **F10** — a behavioural registry exists (`openspec/concepts/`) but a
     spec with requirements has no `## Concepts Used (behavioral)` section.
     The structural half of the altitude rule needs no semantics.
   - **W7** — a code identifier inside a Given/When/Then clause: a build
     command, a source file, a fully-qualified name, or a token the
     project's own ledgers class as code (in `concept-inventory.md`, absent
     from the registry). CANDIDATES, not verdicts — judge each, and record
     which you accepted. Silence is not a pass: W7 matches shapes, not
     prose, and a clause can breach altitude in plain English.
   - **F9** is NOT run in this pass. Artifact existence is a
     post-implementation fact: apply Step 12 runs
     `spec-lint.sh --artifacts`, where every code-shaped Artifact token must
     resolve to a tracked file.

3. **Read the script's CONTEXT block and copy it into the report verbatim.**

   **Applicability is never yours to infer.** A conditional check has two
   halves — does this rule APPLY to this repository (a fact), and does this
   spec OBEY it (judgment). The script reads the first off the filesystem and
   prints it unconditionally, including the negative case, so that even "N/A"
   is machine-attested:

   | Check | Conditional on |
   |---|---|
   | 3, 18 | `openspec/capability-profile.md` (+ deterministic test kit) |
   | 6 | `openspec/concept-inventory.md` |
   | 17 | `openspec/concepts/` |

   Rules: a check the CONTEXT block reports as **APPLIES** may NOT be
   recorded N/A. A check recorded N/A must quote the line that says so.

   This exists because a review once marked the ALTITUDE rule N/A on a
   repository holding 30 concept files, having assumed the registry did not
   exist without looking. Everything downstream of that assumption was
   reasoned correctly and was wrong. **Do not answer a factual question from
   memory when a script answers it from the filesystem.**

## Checks

For EVERY spec file under `openspec/changes/<CHANGE>/specs/`, evaluate:

1. **Given/When/Then concreteness** — every requirement has all three
   clauses with concrete states/actions/outcomes.
2. **Observability** — every `Then` names an observable effect: a return
   value, persisted event, emitted message, or error value. "The system
   handles it correctly" is a FAIL.
3. **Testability** — every scenario can be exercised with the detected stack
   (openspec/capability-profile.md): test kits exist for actor scenarios, fixtures
   are possible for wire scenarios.
4. **Error paths** — every requirement specifies failure behavior; no
   requirement leaves an error case open.
5. **Concepts Introduced completeness** — every new public concept mentioned
   anywhere in the spec appears in the "Concepts Introduced" table.
6. **Concepts Used resolution** — every entry in "Concepts Used" exists in
   openspec/concept-inventory.md (exact name, kind, package).
7. **Generator strategies** — every property declares its generator
   (constructive vs filtered, edge cases, classify labels). Flag heavy
   `suchThat` usage — discards weaken coverage.
8. **Temporal properties** — every EARS pattern names a trigger event and a
   response event.
9. **Vague words** — flag "valid", "fast", "reasonable", "correct",
   "appropriate", "properly", "as expected" unless a concrete definition
   sits next to them.
10. **Unreachable claims** — every "unreachable"/"cannot happen"/"never
    occurs" claim cites a type-level proof (with a compile-negative
    obligation) or an explicit runtime check.
11. **Enum/GADT extensions** — any spec extending an existing enum/GADT
    states how all existing pattern matches behave for the new variants.
12. **Proof obligations** — the Proof Obligations table covers EVERY
    requirement, scenario, invariant, and introduced type constraint, each
    with a declared enforcement mechanism (type system, smart constructor,
    property test, compatibility fixture, static rule, formal contract,
    model check, runtime monitor, adversarial review, or explicit manual
    review). An obligation with no mechanism is a FAIL. The requirement→
    obligation binding must be VERIFIABLE, not merely plausible: the Source
    format is mandated and machine-checked (F6), and every requirement must
    be named by at least one obligation (F7 — reachability). Do not accept
    "the table looks complete" as a pass.
13. **Consumer-facing surface** — every tool/operation/IDL surface has a
    scenario asserting what the consumer observes (parameter schema, not
    just presence/name).
14. **Error-variant feasibility** — every asserted error variant is
    type-feasible against the producing API's return type (a handler typed
    `Either[String, R]` cannot carry a structured error variant).
15. **Adversarial scenarios** — every requirement containing "only",
    "never", or "must not" has at least one scenario whose INPUT the
    requirement forbids. Positive examples alone FAIL.
16. **MUST-CONFIRM** — every classification table, code mapping, or value
    domain whose authoritative source is outside the repo is marked
    MUST-CONFIRM with a pointer to the real source. Invented plausible
    values FAIL.
17. **Altitude** — no code identifiers (module names, error classes, build
    commands) inside Given/When/Then; they belong in
    "## Implementation Anchors". Cited concepts in
    "## Concepts Used (behavioral)" link to registry files.
    *Applicability comes from the CONTEXT block, not from your reading of
    the repo.* Mechanical: F10 (section presence) + W7 (identifier
    candidates). Judgment: whether the cited concepts are the right ones,
    whether each W7 candidate is domain vocabulary or code, and whether the
    clause prose stays behavioural where no backticks appear at all.
18. **Concurrency** — every concurrent-behavior requirement names a
    deterministic observable testable with the detected deterministic test
    kit; a wall-clock timing assertion ("within N ms") FAILS.

## Output

Write `openspec/changes/<CHANGE>/spec-lint.md` using the template at
`openspec/schemas/verified-scala3/templates/spec-lint.md`: one check table
per spec, a PASS/FAIL verdict per spec, and a summary table.

Every ❌ row must cite the exact offending heading and what is missing —
actionable, not "consider clarifying".

## Verdict Handling

- **All PASS**: design and implementation-order may proceed.
- **Any FAIL**: STOP. Present the failing checks. The specs must be fixed
  (which may require going back to the human for intent), then re-lint.
  Do NOT weaken a check to make a spec pass; do NOT proceed "provisionally".
