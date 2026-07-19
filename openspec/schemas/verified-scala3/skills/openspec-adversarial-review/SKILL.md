---
name: openspec-adversarial-review
description: >
  Ring 8 — adversarial spec-compliance review. After the test oracle passes
  (Ring 3/4), compare every requirement against the implementation diff
  looking for ways the code could satisfy the tests while violating the
  English spec: silent fallback mappings, case _ defaults, partial functions,
  invalid states constructible through public APIs, violating private
  helpers. Runs in a FRESH CONTEXT (no implementation conversation), BEFORE
  the expensive rings (5/6/7). Produces a requirement-by-requirement
  PASS/PARTIAL/FAIL compliance report. Mandatory before every checkpoint.
globs:
  - "openspec/changes/*/specs/**/*.md"
  - "src/main/scala/**/*.scala"
  - "src/test/scala/**/*.scala"
metadata:
  generatedBy: verified-scala3-schema/6.0.0
---

# Adversarial Spec-Compliance Review Skill (Ring 8)

## Purpose

A passing test suite is NOT proof of spec compliance. Real example from this
workflow's history (`add-eligibility-in-between-operators`): all tests
passed, yet `cmpOpToOrderOp` silently mapped invalid operators to
`OrderOp.Lte`, `cmpOpToCountOp` mapped `IN`/`BETWEEN` to `CountOp.Gte`, and
directly constructing `Expr.Count(..., CmpOp.IN, ...)` silently produced
wrong behavior. This ring hunts for exactly that class of bug.

Review the code as an adversary: assume the implementation is trying to pass
the tests while violating the spec, and try to prove it.

## When to Use

- Step 8 of the apply phase — MANDATORY for every code-changing spec, after
  Ring 3 (test oracle) and Ring 4 (compatibility), BEFORE Ring 5 (mutation)
  and Ring 6/7 (formal/model). This ordering is deliberate: the review is
  cheap, frequently changes code and adds tests, and mutation must run on
  final code — tests added here also kill mutants, so the recorded mutation
  score is the true one.
- Manually on any implemented spec.

## Fresh-Context Mandate

The review MUST be performed with NO implementation context: a fresh
session/subagent whose ONLY inputs are

1. the spec (every Requirement, Scenario, Property, Compile-Negative
   Obligation, Proof Obligation),
2. the approved typed contract, and
3. the implementation diff against the spec's Step 0 baseline SHA
   (`git diff <baseline-SHA>`).

Explicitly NOT an input: the conversation that produced the code. Self-review
by the author context is the confirmation-bias setup this ring exists to
defeat — the author knows what the code is "supposed" to do and reads that
intent into it. If a fresh context is genuinely impossible, say so in the
report: the checkpoint carries `fresh-context: no` and the human weighs the
verdicts accordingly.

## Procedure

### 1. Gather the evidence

- Read the spec: every Requirement, Scenario, Property, Compile-Negative
  Obligation, and Proof Obligation.
- Compute the implementation diff for this spec:
  `git diff <baseline-SHA>` (the baseline recorded at Step 0 — never the
  unanchored working tree, which after spec 1 contains other specs' changes).
- Read the diff COMPLETELY — including private helpers, companion objects,
  and default parameter values. Private helpers are where violations hide.

### 2. Requirement-by-requirement comparison

For EVERY requirement in the spec, locate the code that implements it and
answer in writing:

1. Does the code do what the English requirement says — in all branches,
   not just the tested ones?
2. Could the code pass the CURRENT tests while violating this requirement?
   (Weak generators, untested private helpers, untested direct-construction
   paths.) If yes, the requirement is at most PARTIAL.
3. Can a public API construct a state this requirement forbids? Try the
   direct construction path, not just the sanctioned pipeline (e.g.
   constructing an AST node directly instead of going through the
   typechecker).
4. Are negative paths exercised directly, or only inferred?

### 3. Dangerous-pattern hunt

Run `openspec/schemas/verified-scala3/scanner/danger-scan.sh <baseline-SHA>`
(mechanical candidates), then judge each hit in the diff; every hit must be
justified (`// danger-scan:allow <reason>`) or fixed:

- fallback/default mappings (a function mapping an enum it shouldn't accept
  to some "harmless" value)
- `case _` in domain logic returning a VALID domain value for invalid input
- comments saying "unreachable", "cannot happen", "should never" without a
  type-level proof
- partial functions; `.get` on Option/Either/Try; unsafe `head`/`tail`/`last`
- unchecked casts, `asInstanceOf`, `@unchecked`
- swallowed errors (`case NonFatal(_) => ...` discarding the error,
  `.recover` to a default domain value)
- default parameter values that mask missing data

### 4. Oracle-tampering check

The oracle was approved at Step 2; verify it was not weakened since:

- no error assertion loosened to `.toString.contains(...)` / substring
  fallbacks where the spec names a structured variant
- no generator narrowed (added filter, tightened `Range`, dropped edge case)
  relative to the approved generator strategy declared in the spec

Any tampering found → the affected requirement is at most PARTIAL and the
oracle change needs human re-approval.

### 5. Verdict per requirement

```
Requirement-by-requirement review:
- Requirement A: PASS
- Requirement B: PARTIAL — public behavior passes; private helper
  cmpOpToCountOp maps IN to CountOp.Gte, violating the mapping contract
- Requirement C: FAIL — direct construction Expr.Count(..., CmpOp.IN, ...)
  produces wrong behavior; no type-level or runtime guard
```

For each PARTIAL/FAIL, state the fix class, strongest first:
1. type-level prevention (narrow the algebra, split the enum, GADT index)
2. smart constructor / explicit runtime rejection
3. new targeted test that would have caught it
Never "add a defensive fallback" — silent mapping to valid behavior is the
bug, not the fix.

### 6. Gate

- Any FAIL → fix it (re-running Rings 0–4 as affected) before proceeding to
  Ring 5.
- Any PARTIAL → fix it, or present it to the human for explicit approval at
  the checkpoint. The checkpoint summary MUST carry the verdict list.
- All PASS → record the report; proceed to Ring 5.

## Output Format

```
Ring 8: Adversarial Spec-Compliance Review — <spec-name>

Fresh context: yes/no (if no: why)
Baseline: <sha>   Diff reviewed: <files>
Dangerous patterns found: <N> (<fixed M> / <justified K>)
Oracle tampering: none / <findings>
Requirements: <P> PASS, <Q> PARTIAL, <R> FAIL

<verdict list>

<for each PARTIAL/FAIL: evidence (file:line), why tests missed it, fix applied/proposed>
```
