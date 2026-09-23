# Ring 8: Adversarial Spec-Compliance Review — install-tool-surface-parity

- **Fresh context**: yes — isolated read-only subagent `devin-subagent-f40627ad`,
  inputs limited to the spec (`specs/install-tool-surface-parity/spec.md`), the
  predecessor scripts (`scanner/install-skills.sh`, `hooks/install-hooks.sh`),
  the typed contract (`InstallSurface.scala`), and the implementation diff.
  No implementation conversation, no progress tracker.
- **Dangerous patterns**: mechanical scan **OK — no unjustified dangerous
  patterns since HEAD**; the review independently confirmed every catch-all is a
  diagnostic/`Left` arm, none maps unrecognized input to a valid domain value.
- **Oracle tampering**: none — the parity property compares exit status only;
  oracle strengthenings below make it stricter, never looser.

## Verdicts

Requirement-by-requirement: every requirement **PASS** except two **PARTIAL**:

- Consumer-surface scenario: `-h` usage did not name the three termination
  statuses (only `--help`'s registry render did) — **F4**, fixed.
- `surface-parity-with-the-predecessor`: implemented honestly as a model-based
  subprocess property, but the ported arm drives the entrypoints — it cannot
  see `ProbatioMain.dispatch`, which is exactly where **F1** lived. The six new
  dispatch-level tests now cover the interceptor shapes the property cannot
  reach.

## Defects found and remediated

- **F1 [major] `ProbatioMain.dispatch` intercepted `--help` in ANY position for
  every subcommand.** Production diverged from the predecessor on five shapes:
  `install-skills --help` (port 0, pred 1), `install-skills <root> --help`
  (port printed help and wrote nothing; pred installs, exit 0),
  `install-hooks --bogus --help` (port 0, pred 2),
  `install-hooks --project --help` (port 0, pred 2 — `--help` is the VALUE),
  `install-hooks --agent --help` (port 0 always; pred 0-or-2 via openspec
  check). **Fixed**: `InstallSkills`/`InstallHooks` own their help handling —
  the intercept no longer fires for them; `install-hooks --help` still exits 0
  through `parseHooksArgs`. Verified on the rebuilt native binary: 1, 2, 2, 0,
  0 — parity.
- **F2 [major] `--agent` did not word-split.** The predecessor's
  `for a in $AGENT` iterates words: `--agent "claude pi"` wires both
  harnesses; the port treated it as one unknown name and wired nothing (still
  exit 0 — invisible to status parity, so only a behavioral review could catch
  it). **Fixed**: `NamedHarness`'s name is split on whitespace inside
  `runInstaller`; each word dispatches through the same `wireHarness` cases —
  `"all"` in multi-word position is the predecessor's `*)` unknown-agent
  diagnostic, matching the script exactly (there is no `all` case inside the
  predecessor's loop either).
- **F3 [major] skill-source enumeration diverged on three environment
  shapes.** (a) Empty `skills/` dir: predecessor iterates the literal `*/`
  glob and `cp` fails → exit 1; the port installed nothing and exited 0 —
  accepting what the predecessor rejects. (b) A dot-directory under `skills/`:
  bash's glob skips it; `Files.list` included it → `NoSuchFileException` →
  Undetermined(2) where the predecessor exits 0. (c) A skill dir without
  `SKILL.md`: predecessor `cp` fails → 1; the port threw → Undetermined(2).
  **Fixed**: dot-directories excluded; empty source and missing `SKILL.md`
  are Findings (exit 1).

## Minor findings — fixed

- **F4** `hooksUsage` names `-h|--help` and the three termination statuses;
  the `-h` test now asserts selector, project selector, write instruction, and
  all three statuses (the `-h`/`--help` texts are identical, matching the
  predecessor).
- **F6** `schemaDirOf` resolves from the TARGET project root instead of the
  invocation cwd — `install-hooks --project <dir>` and `install-skills <root>`
  now work from an unrelated cwd (the predecessor's `$SELF_DIR` anchoring).
- **F7** The claude-merge test re-parses the merged file as JSON and asserts
  structure (foreign `keep` bool, surviving `keep-me` command, merged
  `gate.sh` commands) — substring assertions could pass on invalid JSON.
- **F8** The probe consumer-surface test asserts each prerequisite appears on
  its own `install-skills: <name> — present|MISSING` line.
- **F9** spec.md's proof-obligation table named unrelated artifacts
  (`InstallPreciselyOneSpec`, `HookCutoverSpec`, `MigrationProtocolSpec`);
  corrected to `InstallToolSurfaceParitySpec`, `InstallSurfaceParitySpec`.
  Files staged so spec-lint F9 resolution sees them — **spec-lint 0 FAIL**.

## Recorded, not fixed (adjudicated)

- **F5 [minor]** Write-failure exit class: port maps `IOException` →
  Undetermined(2); the predecessor's `set -e` gives 1. The spec itself
  sanctions Undetermined for the unwritable-agent-dir scenario while the
  parity property demands "the same status" — a spec-internal inconsistency.
  The sanctioned case is the only one pinned by a scenario; recorded.
- **F10–F11 [trivial]** Message-text divergences (`unrecognized` vs `invalid
  option`, `shift` diagnostic text, normalized source path, missing
  uninstall block in usage) — not spec-pinned. Environment edges:
  `install-skills.sh ""` as root, prerequisites supplied as exported bash
  functions, `.devin/hooks.v1.json` existing as a *directory*, python
  `ensure_ascii` vs raw UTF-8 output bytes — corners outside the declared
  surface, recorded.
- **F11** `runInstaller(NamedHarness("all"))` at the seam hits the
  unknown-agent path while the CLI maps `"all"` to `AllPresentHarnesses` —
  unreachable from dispatch; the predecessor's loop has no `all` case either,
  so the seam's behavior is actually the faithful one.
- **F12** Stale "Implementation lands at Step 3" doc comments — removed.

## Invocation matrix result

Both directions of divergence existed and are now closed: the port accepted
what the predecessor rejected (blanket `--help` intercept, empty skills
source) and rejected what the predecessor honoured (multi-word `--agent`,
`--project` from an unrelated cwd, dot-dir skill sources, missing `SKILL.md`
status class). The residual divergences are the adjudicated F5/F10–F12 set.

## Regression coverage added

12 tests in `InstallToolSurfaceParitySpec` (suite now **39/39**): multi-word
and whitespace `--agent`; dot-dir skip; empty skills source; missing
`SKILL.md`; `--project` schema resolution from an unrelated cwd; six
dispatch-level `--help` shapes (`install-skills --help`→1, `--bogus --help`→2,
`--project --help`→2, `--agent --help`→0, `-h --bogus`→0 / `--bogus -h`→2,
`<root> --help`→0-with-writes). Scalafix on the new regions clean
(sys.env×2 annotated, try/finally → `Using.resource`).
