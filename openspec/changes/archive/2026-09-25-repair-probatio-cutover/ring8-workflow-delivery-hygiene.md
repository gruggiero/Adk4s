# Ring 8 — adversarial spec-compliance review: workflow-delivery-hygiene

Change: `repair-probatio-cutover` — spec 9/11.
Baseline: `8b1e6047021c7a1a425a4ab562b038540eb5937d`.
Three fresh-context passes, each an isolated read-only subagent with inputs
limited to spec + typed contract + baseline diff (no implementation
conversation).

## Pass 1 — PARTIAL findings, remediated

2 requirements PASS / 2 PARTIAL. Findings:

- F1: workflow enumeration globbed `.github/workflows/*.yml` on disk — an
  untracked workflow would evade. Fixed: enumeration is `git ls-files`
  (tracked-only) plus an explicit untracked-file check.
- F2: neutralisation detection was step-level only — a job-level `if:` /
  `continue-on-error` outside every step block evaded. Fixed: job-level scan
  (strict regex, outside-step-block predicate) reports all required steps.
- F3: `paths:`/`paths-ignore:` trigger filters not detected as
  not-on-each-change. Fixed in `triggersOnChange`.
- F4: `invokedToolPaths` dropped a trailing `/` handling, `isPureExecShim`
  recognised only the 2-line shim shape, `toolViolations` lacked an `isFile`
  guard; stale comments in `ShimGenerator` header and `workflow-hygiene.bats`.
  All fixed.
- Cross-cutting: `SeamSwapRunner.performSwap` (differential defect generator)
  still wrote the absolute canonical shim — now writes the relative
  `SCRIPT_DIR` form; `ArmTypes.resolveSeam` stale byte-identical comment fixed.

## Pass 2 — APPROVE WITH NOTES (post-remediation verification)

All pass-1 remediations confirmed landed; all requirements PASS. Notes N1–N5
recorded as residual hardening gaps in the new check code; N6 (verify.yml
tracking — discharged: file is `git ls-files`-visible) and N7 (orphan
`adk4s-agent/src/test` dir, pre-existing, out of scope).

- N1: `carriesAbsolutePath` dropped line 1 unconditionally — a shebang-less
  absolute shim could evade.
- N2: `- if: false` on the step marker line evaded step-level detection.
- N3: a commented-out needle (`# bats …`) satisfied the check.
- N4: `- ` inside a `run: |` literal block was mistaken for a step marker,
  masking a step-level guard.
- N5: module discovery keyed to the declared `lazy val x = project` shape —
  recorded as a stated convention.

## Pass 3 — APPROVE WITH NOTES (operative)

Isolated read-only subagent `devin-subagent-040ca8da`; inputs: spec +
post-remediation diff (`/tmp/spec9-diff.txt`, 27 files) + repo read access.

- N1 **CONFIRMED**: `carriesAbsolutePath` exempts line 1 only when it starts
  with `#!`; shebang-less `exec /opt/tool run` flagged. Check-of-the-check
  test added ("absolute-path detection does not require a shebang").
- N2 **CONFIRMED**: neutraliser split — strict `^\s*(if|continue-on-error)\s*:`
  for job-level (cannot match `- if:` markers), dash-tolerant variant for
  step-block scan; `- if: false` marker-line guard caught. Synthetic
  `markerGuarded` case added.
- N3 **CONFIRMED**: `!line.trim.startsWith("#")` guard precedes needle match.
  Synthetic `commentNeedle` case added.
- N4 **CONFIRMED**: literal-block-scalar regions (`run: |`/`>`, incl.
  `[+-]?` chomping) computed and excluded from `realStepStarts`; a `- `
  inside a literal can no longer mask a guard. Blank-line continuation and
  inline `- run: |` hand-traced correct. Synthetic `literalDash` case added.
- Sanity: all 7 committed shims identical 3-line `SCRIPT_DIR` form; no
  `/home/gruggiero` or `exec "/` under the schema tree; `verify.yml`
  triggers on push-to-main + pull_request, no `paths:` filter, no
  neutralising guards; all 15 test-bearing modules enumerated
  (`verified`/`probatio-verified`/`probatio-spike` have no `src/test`).

Residual notes (non-blocking, fail-safe direction — false-negatives only
under deliberately crafted YAML): N8 explicit indentation indicators /
trailing comments on block-scalar headers; N9 needle match is lexical
per-line, not YAML-key-scoped; N10 `triggersOnChange` substring matching.
Recorded for the check's future hardening; the current tree's workflows do
not use those forms.

**Verdict: APPROVE WITH NOTES.** Post-pass-3 test state: `sbt-probatio`
87/87 green; danger-scan clean.
