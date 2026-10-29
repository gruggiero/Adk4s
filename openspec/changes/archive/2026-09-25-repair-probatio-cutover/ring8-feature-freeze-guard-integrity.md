# Ring 8: Adversarial Spec-Compliance Review — feature-freeze-guard-integrity

- **Fresh context**: yes — isolated read-only subagent `devin-subagent-b7a88d9f`,
  inputs limited to the spec (`specs/feature-freeze-guard-integrity/spec.md`),
  the typed contract (`FeatureFreezeGuardIntegrityTypeContract.scala`), and the
  implementation diff (new files intent-to-added so the diff covered them). No
  implementation conversation, no progress tracker, no prior review.
- **Dangerous patterns**: clean — no `isInstanceOf`/`asInstanceOf`/`Any`/`var`/
  `null`; all `try`/`finally` sites carry `scalafix:ok`; mechanical scan: OK.
- **Oracle tampering**: none — the verdict-stability property compares verdicts
  AND warnings (stricter than the spec's "only verdicts").

## Verdicts

Every requirement clause PASS except the closed-identifier requirement, whose
enforcement was real but **vacuous** end-to-end (F1+F2). Three major and five
minor findings; all majors remediated before checkpoint.

### F1 — major: `emittedCheckIds` missed the summary `FAIL F#:` shape — FIXED

`GuardCorpusFixtures.failIdRe` was `"^FAIL (F[0-9]+) ".r`, requiring a space
after the identifier. Both arms emit two shapes: `FAIL F7 line 30: msg` (line-
numbered) and `FAIL F4: msg` / `FAIL F10: msg` (summary — no line number, no
space before the colon; predecessor `:469,474`, ported
`SubcommandEntrypoints.scala:1789`). A new check emitted in the summary shape —
the natural form for a summary-level check like F11 — was never collected, so
`unknownCheckIds` could never see it and the closed-set guard was blind to
exactly the violation it exists to catch.

**Fix**: regex is now `^FAIL (F[0-9]+)( line [0-9]+)?:` — the same grammar the
parity spec uses (`SpecLintParitySpec.scala:252`). New unit test
"emitted check identifiers collect the line-numbered and summary FAIL formats"
feeds both shapes plus an unknown `F11`.

### F2 — major: the closed-set scenario passed vacuously on `emitted=∅` — FIXED

Two vacuity paths: (a) a missing probatio binary returned `Set.empty` ids, so
`unknownCheckIds(∅).isEmpty` passed; (b) the archived corpus is conformant and
emits zero FAIL ids, so the everyday case collected nothing to check.

**Fix**: the scenario now `fail`s when the binary is absent (mirroring
`SubprocessConformanceSpec`'s "missing artifact is a FAILURE, not a skip"), and
lints a violating document asserting the emitted set is non-empty and contains
`F4` — exercising F1's fix end-to-end. The `verdict-stability-across-the-port`
property now also asserts `unknownCheckIds(emitted).isEmpty` on every generated
fixture, where FAILs actually occur.

### F3 — major: `matchesArchived` suffix match resolved the wrong corpus — FIXED

`dirName.endsWith(s"-$changeName")` let `resolve("probatio-cutover")` — and even
`resolve("cutover")` — silently resolve `2026-09-20-complete-probatio-cutover`'s
9-fixture corpus: a change existing in neither location was assigned a
*different* change's corpus, the exact failure the adversarial scenario forbids.
Latent (the pinned `corpusChangeName` is safe) but live for any suffix-colliding
name.

**Fix**: matching is now `dirName == changeName ||
dirName.matches(<YYYY-MM-DD-> + Pattern.quote(changeName))` — the date prefix
and an exact name tail are required. New surgical test asserts
`probatio-cutover` → `NotFound` while `complete-probatio-cutover` → `Resolved`.

### F4 — minor: `genFixture` could not violate F9 or F10 — FIXED (F10) / documented (F9)

F10 requires a spec without `## Concepts Used (behavioral)` (with ≥1
requirement and a present registry — the arms run with `cwd=repoRoot`, where
`openspec/concepts/` exists). `genFixture` now omits the section ~20% of the
time when `nReqs > 0` (`hasConcepts`), covering F10 in both directions. F9 is
opt-in (`--artifacts`, post-implementation) and the gate never passes it — the
documented coverage claim is narrowed to say so rather than pretend coverage.

### F5 — minor: no coverage label on the shipped `Finding` class — FIXED

`SpecLintBridgeSpec` gained `.cover(20, "resolved-with-disagreements")` so a
generator drift cannot silently drop the violation branch.

### F6 — minor, ACCEPTED: `NotFound(Nil)` is constructible

The spec required the searched-locations field be present, not statically
non-empty; `resolve` always produces ≥2 searched paths. Accepted leniency.

### F7 — minor: every non-0/1 exit collapsed to `"undetermined"` — FIXED

`runSpecLintArmText` now maps `2 → undetermined` and any other non-0/1 exit to
`error-<code>` — a crashed arm can no longer masquerade as a legitimate
undetermined.

### F8 — trivial: stale `???` comment — FIXED

### F9 — minor, CONFIRMED INTENTIONAL: bare-name archived dirs resolve

`dirName == changeName` matching a non-date directory under `archive/` is
deliberate (pinned by "an archived change directory named exactly the change
resolves"); the spec describes the dated convention as an example, not a
constraint.

## Post-remediation evidence

- `NonGoalsGuardSpec`: 38 tests, 37 green; sole failure = pre-existing
  `oracle immutability` falsification (out of spec-6 scope, recorded in the
  progress file).
- `SpecLintBridgeSpec`: 3/3 green including the new coverage label.
- **Ring 5 re-run** on the remediated sources (F1–F3 touched mutated code):
  32 mutants, 27 killed, 4 survived — the identical justified-equivalent set
  (`!os.isDir`→`false` ×2 where `Try` collapses the same case; `", "`/`"; "`
  diagnostic separators); all new `matchesArchived` mutants killed. **87.1%**.
- danger-scan: OK.
