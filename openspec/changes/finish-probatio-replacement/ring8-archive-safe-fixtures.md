# Ring 8: Adversarial Spec-Compliance Review — archive-safe-fixtures

Fresh context: yes (isolated read-only subagent; spec + contract + baseline diff only; no implementation conversation)
Baseline: `8ea5f6f7fc357bba0cba4e7d4969b05036ec3533`
Diff reviewed: `ChangeLocation.scala` (new), `GuardCorpus.scala`, `DifferentialHarness.scala`,
`OracleDiffRunner.scala`, `NonGoalsGuardSpec.scala`, `DifferentialHarnessSpec.scala`,
`FeatureFreezeGuardIntegrityTypeContract.scala`, `.scalafix-tests.conf`, `.scalafix-tests-2.12.conf`,
`workflow/verify-test-lint.sh`
Dangerous patterns found: 12 (8 justified / 4 open at review time — 3 remediated, 1 documented-boundary)
Oracle tampering: none (post-approval changes were strictly additive coverage: 3 Ring-5 scenarios +
1 new planted bats line)
Initial verdicts: 2 PASS, 1 PARTIAL, 0 FAIL

## Requirement verdicts

- Requirement: Test code locates a change through the archive-aware resolver — **PASS**
  (exact-equality archive matching verified; `complete-x` cannot resolve as `x`; newest-first
  ordering verified; `searchedLocations`/`resolve` share the same basis)
- Requirement: The predecessor-control fixture is read after archiving — **PASS**
  (absent → Undetermined naming every searched location; located-but-missing → Undetermined
  naming the probed fixture path; real archived fixture verified present)
- Requirement: A literal active-change path in test code is rejected — **PARTIAL → fixed**
  (six within-scope evasions found; see below)
- Compile-Negative Obligation — **PASS** (letter); spirit-gap noted: `Absent(Nil)` is
  constructible under the approved contract — sanctioned, flagged only.

## Findings and resolutions

1. **PARTIAL — lint evasions (fixed)**. The four original patterns missed: a single segment
   carrying slashes (`repoRoot / "openspec/changes/x"`), `os.SubPath` segments
   (`repoRoot / os.SubPath("openspec/changes/x")`), a non-literal name segment
   (`repoRoot / "openspec" / "changes" / nameVar`), `Path.of`, variadic `Paths.get("openspec",
   "changes", "x")`, and bats anchors `$(git rev-parse --show-toplevel)`/`$REPO_ROOT`/`$GIT_ROOT`
   plus the `;`-separated `cd "$ROOT"; cat openspec/changes/x` shape. Resolution: rule
   `NoRepoRootChangePathChain` now covers all three chain spellings and flags descent past
   `changes` by any non-`archive` segment; `NoCwdChangePath` gained `Path.of`; new
   `NoCwdChangePathVariadic` covers the comma-separated form; the bats scanner gained the
   `REPO_ROOT` and `git rev-parse --show-toplevel` anchors and allows `;` inside the span.
   All five rule ids are planted and asserted per module by `verify-test-lint.sh`.
   Residual boundary (documented, inherent to a text-level mechanism): multi-line indirection
   (`base="$ROOT"` on one line, `openspec/changes/x` on another) carries no anchor token.

2. **Open — malformed control JSON bypassed the Undetermined channel (fixed)**.
   `predecessorControl` returns `Ran(path)` on existence alone; `OracleDiffRunner` then parsed
   `ujson.read(...)("measuredAtBaseline")` outside the Try-wrapped channel — a malformed control
   produced a raw exception. Now folded: unparseable control → printed UNDETERMINED + `fail`
   naming the path and the parse error.

3. **Open — `os.pwd` anchoring was a silent-skip hole (fixed)**. Both `OracleDiffRunner` tests
   rooted at `os.pwd`; under a wrong cwd the schema-tests gate silently skipped. `repoRoot` is
   now git-derived (`git rev-parse --show-toplevel` via `HermeticEnv.capture`, same mechanism as
   `DifferentialHarnessSpec`), and an unresolvable root is an error — never a fallback that can
   read as "schema absent".

4. **Open — unlocatable change names could return a wrong directory (fixed)**.
   `resolve("archive", …)` returned `Active(changes/archive)` — the archive ROOT as a change —
   and `resolve("..")`/`resolve("a/b")` could escape or throw. `isLocatable` now rejects empty
   names, `/`-carrying names, `.`/`..`, and the reserved `archive` segment: unlocatable names
   resolve to `Absent(Nil)` and probe nothing.

## Informational (no fix required)

- Invalid calendar dates (`9999-99-99-x`) match the date regex and sort last — deterministic;
  spec silent.
- "names both locations" for the double-archive case is satisfied via `searchedLocations`, not
  carried on `Archived` — enforcement holds through the scenario test.
- `Try(os.list…).getOrElse(Nil)` swallows an archive-listing failure into `Absent` — fail-closed
  and diagnosable (the archive root remains named in `searched`); justified: the closed enum has
  no Error variant.
