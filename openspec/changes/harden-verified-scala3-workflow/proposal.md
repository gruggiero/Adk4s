# Proposal: Harden verified-scala3 Workflow (escape-analysis levers)

## Why

The escape analysis of the `llm4s-middleware-and-dedup` review
(`docs/escape-analysis-code-review-llm4s-middleware-and-dedup.md`) found that all
7 reported issues escaped the verified-scala3 workflow through a small number of
recurring process gaps, not 7 unrelated accidents. This change amends
`openspec/schemas/verified-scala3/schema.yaml` to close those gaps so the same
classes of error cannot slip again. It touches **only the workflow definition**
(YAML) — no production Scala code.

The five root causes addressed (each mapped to its High-priority lever):

1. **Oracle corruption (Finding 6).** A test was loosened with
   `.toString.contains(...) || contains(...)` to accommodate an implementation
   that could not produce the spec's named error variant, so Ring 3 could not
   catch a direct spec violation. → *Oracle-faithfulness rule + error-variant
   type-feasibility check.*
2. **Behavior-preservation tested on a single enum variant (Finding 1).** A
   4-branch enum mapping had 1 branch tested. → *spec-lint check 1c +
   per-variant test-oracle rule.*
3. **Type-alias widening never audited for downstream matches (Findings 2–3).**
   Aliasing `Role`/`Message` to a richer llm4s enum silently widened every
   catch-all match across the module graph; spec-lint explicitly mis-reasoned
   "not extended". → *specs rule 11 + spec-lint check 11 fix + new Step 0
   PUBLIC-TYPE-CHANGE IMPACT SCAN (the only true structural blind spot).*
4. **Consumer-facing surface underspecified (Finding 5).** "Visible tool" meant
   name+presence, not the parameter schema the LLM sees. → *specs rule 12 +
   spec-lint check 13.*
5. **Dead code after a refactor (Finding 4).** `RemoveUnused` does not flag
   public/opaque members; concept-delta is additive-only. → *Step 12 REMOVAL
   AUDIT + Ring 1 grep scope.*

A secondary cross-cutting gap — **diff-scoped rings miss files that merely
import a changed public type** (Findings 2, 3, 7) — is closed by broadening the
Ring 1 dangerous-pattern grep to scan importers (Step 0 impact-scan output) and
adding the `ExhaustiveDomainConversion` Ring 2 rule.

## What Changes

- `openspec/schemas/verified-scala3/schema.yaml`:
  - **specs artifact instruction** — add rules 11 (Type-Widening / Alias
    Impact), 12 (Consumer-Facing Surface), 13 (Error-Variant Feasibility).
  - **spec-lint instruction** — add checks 1c (behavior-preservation per
    variant), 13 (consumer-facing surface scenario), 14 (error-variant
    feasibility); amend check 11 to treat "alias to a richer type" as an
    extension.
  - **apply Step 0** — add the PUBLIC-TYPE-CHANGE IMPACT SCAN.
  - **apply Step 2 (test oracle)** — add ORACLE FAITHFULNESS and
    BEHAVIOR-PRESERVATION COVERAGE rules.
  - **apply Step 4 (Ring 1)** — broaden the dangerous-pattern grep scope to
    importers of widened/aliased public types; call out the
    `case other => <valid domain value>` anti-pattern.
  - **apply Step 5 (Ring 2)** — add the `ExhaustiveDomainConversion` rule.
  - **apply Step 12** — add the REMOVAL AUDIT.

## Approach

Single YAML edit set (already applied). Each amendment names the artifact/ring
and quotes the offending failure mode from the escape analysis. No code, no
migration.

## Verification Strategy

This is a **build-metadata-only / docs-only** change to the workflow definition
itself; it changes no Scala source. Ring applicability:

- [ ] Ring 0 (compile) — N/A (no Scala)
- [ ] Ring 1 (lint) — N/A (no Scala)
- [ ] Ring 2 (architecture) — N/A (no Scala)
- [ ] Ring 3 (property tests) — WAIVER (docs/workflow-config only; the
      "tests" are below)
- [ ] Ring 4 (wire compat) — N/A
- [ ] Ring 5 (mutation) — N/A
- [ ] Ring 6 (formal) — N/A
- [ ] Ring 8 (adversarial review) — performed manually: each amendment was
      checked to (a) preserve YAML validity, (b) be internally consistent with
      the other amendments it cross-references (specs rule N ↔ spec-lint check
      N), and (c) actually address its target failure mode from the escape
      analysis.

**Self-tests (run as part of this change):**
1. `schema.yaml` parses as valid YAML and `openspec` still loads the schema.
2. Every cross-reference resolves: specs rule 11 ↔ spec-lint check 11; specs
   rule 12 ↔ spec-lint check 13; specs rule 13 ↔ spec-lint check 14; Step 0
   impact scan ↔ Ring 1 importer scope.
3. Dry-run sanity: re-linting the *archived* `llm4s-middleware-and-dedup` specs
   against the new checks would have FAILED checks 1c (single-variant
   behavior-preservation), 11 (alias widening), 13 (tool parameter schema), 14
   (`InvalidArguments` from an `Either[String,_]` handler) — confirming the
   amendments are load-bearing.

## Correctness Risk Level

**Low** — the change is a YAML workflow definition; the only "correctness" is
internal consistency and that the amendments target real failure modes (shown by
the escape analysis). High blast-radius (it shapes every future change), but
low per-edit risk and trivially reversible.

## Typed Contract Decision

**Waiver (human-approved)** — docs/workflow-config only; no Scala types or
signatures are introduced. The "contract" is the set of numbered rules above.

## New Concepts to Introduce

None (workflow definition only).
