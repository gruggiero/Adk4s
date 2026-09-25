Ring 8: Adversarial Spec-Compliance Review — hermetic-test-processes

Fresh context: **yes** — reviewed by an isolated read-only subagent whose only
inputs were the spec (`specs/hermetic-test-processes/spec.md`), the typed
contract, the baseline diff vs `8a0c15f`, and on-disk verification. The
reviewer had no access to the implementing conversation.

Baseline: 8a0c15f4a10046d041a09ef7fb3a839fb095ad29
Diff reviewed: the full spec-1 delta — `HermeticEnv`/`ControlledVariable`/
`HermeticEnvGens`/`HermeticResult` (three copies: core, cli, plugin-2.12),
~30 migrated process-spawning test sites across the three modules,
`helpers.bash` central clearing + scattered `env -u` removals,
`.scalafix-tests.conf` + `.scalafix-tests-2.12.conf` wired via
`Test / scalafixConfig`, `workflow/verify-test-lint.sh`,
`DifferentialHarnessSpec` env-independence property, compile-negative +
type-contract pins, `SessionIdSpec`, `GateEventSpec`, `GateBannerCompatSpec`,
`OutcomeSpec` cover probes.

Requirements: **4 PASS, 1 PARTIAL, 0 FAIL** at review time → **all findings
remediated or dispositioned** (see below). Compile-negative obligations: PASS.
Oracle tampering: none found.

Dangerous patterns: **8 found** — 5 justified (private constructor,
`pb.environment()` inside the sole `processBuilder` route sanctioned with
`scalafix:ok` markers, `System.getenv` read-once inside `HermeticEnv.build`'s
declared filter, the two-environment sentinel arm), 3 open at review time —
**all 3 resolved**:

---

## Findings and remediation

### F1 — Env-independence property could vacuously pass on an empty domain — RESOLVED
At review, `spawningSuites match case Nil => Gen.constant(os.Path("/nonexistent-suite-dir"))`
let an empty suite listing produce two empty TAP runs — `dependent` empty → PASS
measuring nothing. **Fix:** `spawningSuites` is a static `lazy val`; the property
matches on it first — the `Nil` arm returns `Result.failure` lifted through
`Gen.constant(()).forAll.map` ("process-spawning suite domain is empty — the
property measured nothing"). No fake path exists anywhere in the file. Verified:
property green at n=50 in both environments.

### F2 — `genControlledSubset` coverage remained statistically flaky — RESOLVED
Weights 1/15 for `full-controlled-set` (≈6.7% expected vs 4% minimum at n=30)
meant a ~40% chance the coverage check alone falsified the run — every observed
"falsification" of this property was coverage-only (`dependent` empty on all
cases; file-written diagnostics confirmed). **Fix:** weights now 4/4/6/8
(empty / harness-session-only / full / bitmask-partial) and `testLimit` 30 → 50.
Edge-category coverage-miss ≈0.3% per category; combined property flake ≈1%.
Verified: property green under both environments, coverage labels all met.

### F3 — Lint evasion vectors — RESOLVED
The banned-shape patterns missed three idiomatic forms: `sys.process` without
the `scala.` prefix (Predef alias), parenless `.environment` (Java nullary
methods need no parens in Scala), and `Runtime.getRuntime` (the other idiomatic
spawn path, also env-inheriting). **Fix:** patterns widened in BOTH confs —
`(?:scala\.)?sys\.process`, `\.environment\b` (matches parenless and parens),
new rule `NoRuntimeExec` = `Runtime\s*\.\s*getRuntime`. Three purity-spec
`"sys.process"` string literals carry `scalafix:ok` markers (the regex is
text-level). `verify-test-lint.sh` now plants and asserts ALL FIVE rule ids
per module; `os.proc` is planted as a string literal for the plugin (no os-lib
dependency on its classpath — the text-level rule fires identically).
Verified: 5/5 rules fire with file+line on all three modules;
`Test/scalafix --check` green on all three.

### Compile-negative assertions strengthened (review recommendation)
The two compile-negative tests previously asserted only `err.nonEmpty` — any
unrelated error satisfied them. They now assert the rejection is for the right
reason: `private`/`cannot be accessed` for the constructor, `not a member`/
`Not found` naming `inherit` for the absent factory. Green in both modules.

### Spec/design text corrected (review recommendation)
The spec prescribed scoping the lint "by a `fileFilter` glob" — `fileFilter`
is not a `DisableSyntax` field (javap-verified); the `NoIOInProbatioCore`
precedent was itself dead config never listed in `rules`. spec.md proof
obligations + anchor and design.md now record the real mechanism:
`.scalafix-tests.conf` / `.scalafix-tests-2.12.conf` wired via
`Test / scalafixConfig`.

---

## Informational findings — accepted as-is

- `GATE_OVERRIDE` appears in `SeamTypes.ToolId.overrideEnvVar(Gate)` but is read
  by no current tool and is absent from `ControlledVariable`. Belongs to
  `installer-swap`'s domain (spec 8), not this spec.
- `OutcomeSpec`'s missed-minimum probe is already deterministic
  (`Gen.constant(0)` vs an 80% minimum — 0% generation, guaranteed miss) and
  asserts both non-ok status and label naming. No change needed.
- `capture`/`captureMerged` write stdin before draining output — a child that
  writes more than the pipe buffer before reading could deadlock. Matches the
  replaced code exactly; not an environment leak. Accepted.
- Ring 5 (93.75%) carries over: the mutate target (`HermeticEnv` class) is
  byte-identical post-restore; Ring-8 edits touched only gens/test code outside
  the mutation surface.
