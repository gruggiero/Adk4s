# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Mechanical pre-pass

<!-- Run BEFORE the judgment checks:
     1. `openspec validate --strict` — must pass.
     2. `openspec/schemas/verified-scala3/scanner/spec-lint.sh <change-dir>` —
        enforces the greppable subset (F1–F5) and reports vague-word and
        adversarial-confirmation candidates (W1–W3).
     Paste both outputs here. Script FAILs are lint FAILs. -->

**openspec validate --strict**: <!-- PASS / output -->
**spec-lint.sh**: <!-- paste summary line + findings -->

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
1b. Every requirement opens with a normative SHALL/MUST statement before its first `**Given**` (mechanical: F1)
1c. Every "identical/same/preserved behavior" requirement over an enum/dispatch parameter has one scenario PER variant, each asserting the discriminating observable
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (openspec/capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in openspec/concept-inventory.md
7. Every property has a declared generator strategy (mechanical: F3)
8. Every temporal property has a trigger event and a response event (mechanical: F5)
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition (candidates: W1)
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave (aliasing to a richer type counts — "Type-Widening Impact" subsection required)
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism (mechanical: F4/W2)
13. Every consumer-facing surface (tool/operation/IDL) has a scenario asserting what the consumer observes (parameter schema, not just presence)
14. Every asserted error variant is type-feasible vs the producing API's return type
15. ADVERSARIAL — every "only"/"never"/"must not" requirement has a scenario whose input the requirement forbids (mechanical half: F2/W3)
16. MUST-CONFIRM — externally-sourced classification tables / code mappings / value domains are marked MUST-CONFIRM with a pointer to the real source; invented plausible values FAIL
17. ALTITUDE (if openspec/concepts/ exists) — no code identifiers in Given/When/Then; concepts cited in "Concepts Used (behavioral)" link to registry files
18. CONCURRENCY — concurrent-behavior requirements name deterministic observables testable with the detected deterministic test kit; wall-clock timing assertions FAIL

## Results

### Spec: [specs/<capability-1>/spec.md]

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | <!-- ✅/❌ --> | <!-- offending heading + what's missing --> |
| 1b | SHALL/MUST normative opener | | |
| 1c | Per-variant behavior-preservation scenarios | | |
| 2 | Then observable | | |
| 3 | Scenarios testable | | |
| 4 | Error paths specified | | |
| 5 | New concepts declared | | |
| 6 | Reused concepts resolved | | |
| 7 | Generator strategies | | |
| 8 | Temporal trigger/response | | |
| 9 | No vague words | | |
| 10 | Unreachable claims proven | | |
| 11 | Enum extension / type-widening behavior | | |
| 12 | Proof obligations complete | | |
| 13 | Consumer-facing surface asserted | | |
| 14 | Error variants type-feasible | | |
| 15 | Adversarial scenarios for negatives | | |
| 16 | MUST-CONFIRM marks present | | |
| 17 | Altitude respected | | |
| 18 | Concurrency deterministic | | |

**Verdict: PASS / FAIL**

<!-- Repeat the table per spec. -->

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| <!-- specs/x/spec.md --> | <!-- PASS/FAIL --> | <!-- count + one-line summary --> |

<!-- Overall: implementation-order may only be generated when every spec is PASS. -->
