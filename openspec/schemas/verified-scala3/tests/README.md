# tests/ — bats suites for the workflow's own scripts

The workflow ships 11 tracked shell scripts. `gate.sh` runs on every session
and `spec-lint.sh` decides whether a spec may proceed — they are production
code for the workflow, and until schema v12 they had **no tests at all**.

Framework: **bats-core**, detected in `openspec/capability-profile.md`
(1.14.0 at the time of writing). It is a declared prerequisite, not an
assumption; see the profile's PREREQUISITE RULE.

## Layout

```
tests/
├── README.md                 this file — the conventions
├── helpers.bash              shared setup and assertions; loaded by every suite
├── <spec-name>.bats          one suite per OpenSpec spec, named after it
└── fixtures/                 committed inputs; NEVER regenerated to make a test pass
```

One suite per **spec**, not per script, so a suite maps 1:1 onto the
requirements it discharges and can be cited whole in a Proof Obligations
`Artifact` cell.

## Conventions

**Every test cites its source.** A test with no citation cannot be traced back
to a requirement, and an obligation naming it cannot be verified:

```bash
# spec: correctness-invariant — Scenario: a recognised version reads normally
@test "..." { ... }
```

Use `Scenario:` or `Property:` exactly as the heading appears in the spec —
`spec-lint.sh` F8 checks that typed references resolve, and a citation that
names a heading which does not exist is a dangling reference.

**Properties are enumerated, not sampled.** Bash has no generator or shrinker.
A property test loops over an explicitly listed finite domain, declared in the
spec's `**Generator strategy**` line. Where the true domain is unbounded, the
enumeration is a stated finite subset and that limit belongs in the
obligation — never claim sampled coverage for an enumerated test.

**Assert the exit code, not just the output.** New scripts in this workflow
SHALL use a three-way convention (design **Decision 2** of
`add-correctness-substratum`). It is prescriptive, not descriptive: of the 11
scripts predating it, only `metals-call.sh` and `spec-lint.sh` use 0/1/2,
`install-hooks.sh` uses 0/2, and the rest are 0/1 or 0-only.

| Code | Meaning |
|------|---------|
| `0` | ran, found nothing wrong |
| `1` | ran, found something wrong |
| `2` | **could not determine** — unreadable input, absent dependency |

A test that only checks `status -ne 0` cannot tell a finding from a broken
tool, which is the defect class this workflow exists to remove.

**A justified occurrence carries its justification.** Where a property cannot
mechanically distinguish a legitimate case from a violation, the legitimate one
declares itself with an inline marker naming a reason — `deps-rule:allow
<reason>` for the superseded-dependency-rule property, mirroring the existing
`// danger-scan:allow <reason>`. Never widen a matcher to make a legitimate
case pass: that weakens it for every future case too, and this project has
already shipped one property that could not catch its own motivating text.

**Never mutate the repository.** Tests run against a temporary copy or
construct inputs under `BATS_TEST_TMPDIR`. A suite that edits tracked files
makes its own result depend on the order tests ran in.

## Running

```bash
bats openspec/schemas/verified-scala3/tests/
```

Single suite:

```bash
bats openspec/schemas/verified-scala3/tests/correctness-invariant.bats
```

CI runs the whole directory, plus `shellcheck` and `shfmt -d`, per the
templates in `../ci/`.
