# Spec Lint Report

<!-- Generated after the specs artifact, before design and implementation-order.
     A FAIL verdict on any spec BLOCKS implementation — fix the spec and
     refresh this report. The goal is to fail fast when a spec is too
     ambiguous to implement safely. -->

## Checks

Each spec is checked against:

1. Every requirement has concrete Given/When/Then clauses
2. Every `Then` is observable (return value, persisted event, emitted message, error value)
3. Every scenario is testable with the detected stack (capability-profile.md)
4. Every error path is specified
5. Every new public concept appears in "Concepts Introduced"
6. Every reused concept exists in concept-inventory.md
7. Every property has a declared generator strategy
8. Every temporal property has a trigger event and a response event
9. No vague words ("valid", "fast", "reasonable", "correct", "appropriate") without a concrete definition
10. Every "unreachable" claim has a type-level proof obligation or explicit runtime check
11. Every enum/GADT extension states how existing pattern matches behave
12. The Proof Obligations table covers every requirement, scenario, invariant, and introduced type constraint with a declared enforcement mechanism

## Results

### Spec: [specs/<capability-1>/spec.md]

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | <!-- ✅/❌ --> | <!-- offending heading + what's missing --> |
| 2 | Then observable | | |
| 3 | Scenarios testable | | |
| 4 | Error paths specified | | |
| 5 | New concepts declared | | |
| 6 | Reused concepts resolved | | |
| 7 | Generator strategies | | |
| 8 | Temporal trigger/response | | |
| 9 | No vague words | | |
| 10 | Unreachable claims proven | | |
| 11 | Enum extension behavior | | |
| 12 | Proof obligations complete | | |

**Verdict: PASS / FAIL**

<!-- Repeat the table per spec. -->

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| <!-- specs/x/spec.md --> | <!-- PASS/FAIL --> | <!-- count + one-line summary --> |

<!-- Overall: implementation-order may only be generated when every spec is PASS. -->
