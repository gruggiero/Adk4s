# Spec Lint Report

Thirteen spec files under `specs/` — twelve new capabilities and one MODIFIED delta
(`cli-wiring`). **Verdict: all PASS.** No spec blocks design or implementation-order.

## Mechanical pre-pass

**1. `openspec validate --changes --strict`** — **PASS**, `1 passed, 0 failed`, zero issues.

Two failures were found and fixed during this pass. `oracle-fixture-repair`'s first
requirement and `cli-wiring`'s restated requirement were rejected with *"must contain SHALL
or MUST"* although both contained one. The cause was measured, not guessed: **the
validator reads only the first line of a normative statement.** Both had their keyword on
line 2. This also explains the previous change's apparently different failure, where a
statement began with "When" and carried its SHALL on line 2. Both statements were
reflowed. A check over all 13 files found no other statement with its keyword off the
first line.

**2. `scanner/spec-lint.sh openspec/changes/finish-probatio-replacement --artifacts`** —
`spec-lint: 13 spec file(s), 0 FAIL, 34 WARN`. All 34 warnings are **W3**
(adversarial-confirmation candidates); each is confirmed under check 15. Zero F-checks
failed. The artifact check (F9) ran, so every named suite resolves to a tracked file.

One warning was fixed during drafting: a **W1** on the word "valid" in a clause of
`jar-launcher-dispatch`.

**3. `scanner/registry-check.sh .`** — run because the inventory check required a re-run
once specs existed. **It first FAILED**, then passed after a fix:

- *First run*: `registry-check: FAILED`, with 2 `SPEC` rows — `surface-honesty` and
  `registry-check-port`, *"has a Concepts Used (behavioral) table with 2 row(s) but NO
  reference parsed"*.
- *Cause*: all 13 specs named their behavioural concepts in prose ("Strangler migration
  protocol"). The verifier accepts only a backticked identifier, a markdown link, or a bare
  CamelCase name, and it derives each concept's identifier from the first word of its
  `# Concept:` heading. The identifiers are `Strangler`, `TraceabilityGraph` and
  `Conformance`. All 13 tables were rewritten to the backticked form the archived specs
  use.
- *Second run*: `registry-check: OK (817 implementation-map tokens verified, 15 spec concept
  references checked, 5 weak binding(s) to tighten)`.

**A latent defect in the verifier itself, surfaced by this pass.** Before the fix, the
eleven one-row tables *also* parsed to no reference, and they **passed**. The verifier
flags an unparsed table only when it has more than one row
(`registry-check.sh:303–305`). A one-row table that cites nothing is accepted without
checking anything. The port reproduces the predecessor's verdict by design, so fixing this
is a verdict change. It is recorded in `registry-check-port`'s Implementation Anchors for
the next change, not fixed here.

**4. CONTEXT block — copied verbatim from the run:**

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v14
  behavioural registry  openspec/concepts/             PRESENT (38 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
       F10 checks the structural half; W7 lists code-identifier candidates;
       reading the clause prose for behavioural altitude is still your job.
  type inventory        openspec/concept-inventory.md  PRESENT (405 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: TestControl testkit
```

**Applicability, read from that block:** checks **3**, **6**, **17** and **18** all
**APPLY**. None is recorded N/A anywhere in this report. Unlike the previous change's run,
this block carries no pre-rename stamp warnings — the stamp migration landed.

## Per-check results

Columns: **1** jar-launcher-dispatch, **2** hermetic-test-processes, **3**
archive-safe-fixtures, **4** oracle-independence, **5** oracle-fixture-repair, **6**
delivery-verified, **7** entrypoint-split, **8** installer-swap, **9** surface-honesty,
**C** cli-wiring (delta), **10** registry-check-port, **11** legacy-name-retirement, **12**
schema-directory-rename.

| # | Check | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | C | 10 | 11 | 12 |
|---|-------|---|---|---|---|---|---|---|---|---|---|----|----|----|
| 1 | Concrete Given/When/Then | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 1b | SHALL/MUST before Given (first line) | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 1c | Per-variant scenarios for same-behaviour claims | P | P | — | — | P | — | P | — | — | — | P | P | — |
| 2 | Every Then observable | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 3 | Testable with detected stack | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 4 | Error paths specified | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 5 | New concepts in Concepts Introduced | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 6 | Reused concepts exist in inventory | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 7 | Generator strategy per property | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 8 | Temporal trigger/response | — | — | — | — | — | — | — | — | — | — | — | — | — |
| 9 | No vague words | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 10 | Unreachability claims have type proof | P | P | P | P | P | P | — | P | P | — | P | P | — |
| 11 | Enum change states match behaviour | P | P | P | P | P | P | P | P | P | — | P | P | P |
| 12 | Proof Obligations cover every requirement | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 13 | Consumer surface asserted | P | — | — | — | — | — | P | — | P | P | P | P | — |
| 14 | Error-variant type feasibility | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 15 | Adversarial scenario per negative requirement | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 16 | MUST-CONFIRM on external value domains | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 17 | ALTITUDE — behavioural vocabulary in clauses | P | P | P | P | P | P | P | P | P | P | P | P | P |
| 18 | Concurrency — deterministic observable | P | P | — | P | P | P | P | P | — | — | P | P | P |

`P` = pass. `—` = the check has no subject in that spec. Checks 3, 6, 17 and 18 apply per
the CONTEXT block and are never N/A; a `—` under 18 means that spec has no concurrent or
subprocess behaviour to judge.

## Notes where the verdict needed judgment

**Check 1c — per-variant coverage.** Same-behaviour claims range over closed sets in six
specs. Each covers every variant: archive and generic dispatch agree over the full
argument alphabet (1); every controlled variable is drawn independently (2); every tool
name in both cases and on both channels (5); the whole invocation corpus before and after
the split (7); every alias × every set-state × versions on both sides of the window (11);
the verifier's four verdict kinds (10).

**Check 3 — testability (APPLIES).** Every property is Hedgehog-shaped with an explicit
generator, and every scenario is munit- or bats-shaped. Obligations enforced by **observed
runs** rather than tests are named as such: the predecessor-control match (1), the
two-environment runs (2), the hosted CI runs and the local release candidate (6), the
scalameta spike (10), and the CLI-resolution re-check (12). Obligations enforced by
**manual review** are also named: the root-cause records (5), the Devin confirmation (5),
the disposition of historical oracle edits (4), and the evidence-entry check (6).

**Check 6 — reused concepts (APPLIES).** Every name in every "Concepts Used (from
inventory)" table was matched mechanically against `openspec/concept-inventory.md`
(405 rows): **none missing**. `CutoverKernel`, cited through the proposal, had no row and
was added by this change's inventory check. `GraphNode` is cited with its probatio package,
because two distinct concepts share the name.

**Check 9 — vague words.** A grep of every clause across the 13 files for the five banned
words returned nothing after the W1 fix above.

**Check 10 — unreachability.** Every "unconstructible" claim carries a compile-negative
obligation, or, for the three lint-enforced claims, a static rule in `.scalafix.conf`. The
three `—` specs make no such claim: the split, the delta and the rename rely on behavioural
and filesystem checks.

**Check 11 — enumeration changes.** Five specs change an enumeration, and each has a
Type-Widening Impact subsection. `ToolId` and `SwapOrder` gain seams (8, 10). `Subcommand`
gains the verifier (10) and loses `Metals` (9) — the narrowing's dead-arm case is stated.
`ToolSurfaceClassification.Ported` changes shape (9). `SanctionVerdict` replaces
`OracleImmutabilityResult`, whose new third variant must be handled as could-not-determine
(4). The rest state that they widen nothing.

**Check 13 — consumer surface.** Six columns change what a user or harness observes (five specs and the delta), and
each asserts the observable: dispatch and help through the archive (1), surface behaviour
unchanged by the split (7), the removed subcommand rejected (9, C), the verifier's report
lines identical to the predecessor's (10), and the probatio variable names in every message
(11).

**Check 15 — the 34 W3 warnings.** Checked mechanically: every requirement in all 13 files
has at least one scenario headed "Adversarial". One requirement — `cli-wiring`'s restated
one — had an adversarial scenario whose heading did not say so. It was renamed. No
negative requirement is pinned by positive examples alone.

**Check 16 — MUST-CONFIRM.** Three external value domains, each marked with its
authoritative source and its in-repository record:

- the Devin hook payload's tool-name field (5);
- the hosting service's trigger for the CI job, and the authorisation that publishing needs
  (6);
- the openspec CLI's schema resolution (12). This one was **measured while drafting**
  (openspec 1.3.1, Linux: a symbolic-link alias resolves an active change; archived changes
  are never resolved). It is recorded with its date, and still required to be re-established
  at apply time.

The harness session-variable names in (2) are not external: the controlled set is defined
as the variables *this repository's code reads*, which is determinable from the tree.

**Check 17 — ALTITUDE (APPLIES; W7 silence is not a pass).** A grep of every clause for
backticks, file extensions and camelCase found one token: `parity-session` in (2), a literal
test value, which the rule permits. Clause prose was also read: "the tool run through its
archive", "a spawned tool", "the pre-execution tier", "the code-intelligence server" —
behavioural vocabulary throughout. Two specs deliberately name **acceptance-suite files** in
their normative statements (5, 11). This is required by `oracle-independence`'s sanction
rule, which accepts an oracle edit only if the sanctioning requirement names the file. The
file names were kept out of the Given/When/Then clauses themselves. Every spec's
behavioural table now cites its concept by the identifier the registry declares, and the
registry check confirms all 15 references.

**Check 18 — concurrency (APPLIES).** Subprocess behaviour runs through ten specs, and each
names a deterministic observable: termination status, output lines, a file-tree digest, a
per-file failure count, a verdict. None asserts a wall-clock bound. The detected kit,
`TestControl`, is unreachable from `workflow/*` (R-ARCH1). The substitution — recorded
outcomes, an injected clock seam, and now the hermetic process environment of spec 2 —
is stated in the proposal and the capability check.

## Verdict

| Spec | Verdict |
|------|---------|
| `specs/jar-launcher-dispatch/spec.md` | **PASS** |
| `specs/hermetic-test-processes/spec.md` | **PASS** |
| `specs/archive-safe-fixtures/spec.md` | **PASS** |
| `specs/oracle-independence/spec.md` | **PASS** |
| `specs/oracle-fixture-repair/spec.md` | **PASS** |
| `specs/delivery-verified/spec.md` | **PASS** |
| `specs/entrypoint-split/spec.md` | **PASS** |
| `specs/installer-swap/spec.md` | **PASS** |
| `specs/surface-honesty/spec.md` | **PASS** |
| `specs/cli-wiring/spec.md` (MODIFIED delta) | **PASS** |
| `specs/registry-check-port/spec.md` | **PASS** |
| `specs/legacy-name-retirement/spec.md` | **PASS** |
| `specs/schema-directory-rename/spec.md` | **PASS** |

No FAIL. Design and implementation-order may proceed.
