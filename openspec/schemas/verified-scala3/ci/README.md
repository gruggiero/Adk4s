# CI templates for the verified-scala3 gate checks

Each template installs the DECLARED PREREQUISITE SET (schema v12 — see
`../hooks/README.md`) and then runs the gate checks. None of them needs a JDK,
sbt, or an internal artifact repository, so they still run on a restricted
runner — which is the point of declaring a small installable set rather than
assuming a build environment.

Pick the template matching your host, copy it to the expected location, and
register the pipeline if your host requires it (Azure DevOps does; GitLab
picks up `.gitlab-ci.yml` automatically — check for an existing server-side
pipeline configuration before committing one).

| Host | Template | Copy to |
|---|---|---|
| Azure DevOps | `azure-pipelines.yml` | repo root (then create a pipeline pointing at it) |
| GitLab | `gitlab-ci.yml` | repo root as `.gitlab-ci.yml`, or include as a job in the existing one |
| GitHub | `github-actions.yml` | `.github/workflows/verified-scala3.yml` |

## What each template runs

| Step | Check |
|---|---|
| Install + **verify** prerequisites | A missing tool fails the job. It must not silently skip the ring below — a skipped ring reported as a pass is the defect this workflow exists to remove |
| Ring 1 | `shellcheck` over every tracked script |
| Ring 1 | `shfmt -d` over **changed files only** (see below) |
| Ring 3 | `bats ../tests/` |
| — | `registry-check.sh` (concept registry drift) |
| — | `spec-lint.sh` (active changes) |

**`shfmt` is invoked as `shfmt -i 2 -ci`.** The project's observed convention
is 2-space indent with indented `case` arms; bare `shfmt` defaults to tabs.
Measured over the 11 tracked scripts: default 1987 diff lines, `-i 2` 690,
**`-i 2 -ci` 515**. The setting is detected from the existing scripts, not
picked — running the default would reformat the whole tree away from its own
style.

**Why `shfmt` is scoped to ADDED files, not changed ones.** Even at the right
setting the scripts predate the formatter (515 diff lines tree-wide), and
`shfmt -d` checks a **whole file** — so gating on *changed* files makes any
edit to a legacy script fail on that script's pre-existing formatting.
This is not hypothetical: the commit that introduced this gate touched four
scanners for one-line lint annotations and would have failed on ~276 lines of
whitespace it never wrote. A gate that punishes unrelated edits gets disabled,
and a disabled gate enforces nothing.

`--diff-filter=A` keeps new shell code clean from day one without taxing edits
to old code. Promote to tree-wide after a one-time reformat commit
(`shfmt -i 2 -ci -w` over the tracked scripts), not before.

**Both lint steps cover `*.sh` and `*.bash`.** The bats helpers live in a
`.bash` file; a `*.sh`-only glob would have left them unchecked by the very
gate added to check them. `.bats` suites are deliberately NOT in the glob —
they are not valid bash (`@test` blocks), so shellcheck cannot parse them.
Their correctness is established by running them, which the Ring 3 step does.

**Why `shellcheck` is NOT scoped.** The tree is **clean: 12/12 files, zero
findings** (verified 2026-08-08). The five pre-existing findings carry
justifying annotations with reasons, so shellcheck gates the whole tree from
day one rather than only new files.
