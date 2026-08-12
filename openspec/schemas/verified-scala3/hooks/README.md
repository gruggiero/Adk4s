# hooks/ — harness enforcement for verified-scala3

Every check in this workflow lives in `scanner/*.sh`, and every one of them is
**opt-in**: an agent that does not run the script never sees the fact that
would have corrected it. A review once recorded the ALTITUDE rule as `N/A` on
a repository holding 30 concept files, because it answered a factual question
from memory instead of from the filesystem.

The scripts fix what happens **when** they run. Hooks fix **whether** they run.

## Scope: Tier B and Tier A

**Tier B** (`session-start`, `prompt-submit`) is context injection. It never
blocks and always exits 0.

**Tier A** (`post-edit`, `completion`) was promoted from "deliberately
absent" at `spec:gate-payload` to shipped at `spec:hook-tiers`, on the
evidence Tier B accrued running in daily use across specs 1-5 of this same
change (see `implementation-progress.md` for the full record: five specs'
worth of real hook invocations, including inside this repository's own
git worktree, with zero session-strand incidents). Promotion is split in
two, matching how much each half can go wrong:

- **`post-edit`** — runs `spec-lint`/`danger-scan` immediately after a
  spec/production-source edit and returns findings. Like Tier B, it **never
  blocks** — the edit has already happened by the time this runs, so there
  is nothing to strand.
- **`completion`** — the **one genuine block** in this entire schema.
  Refuses turn completion when the turn claims a checkpoint/spec/ring result
  while chain state reports unresolved requirements or is itself
  undetermined. Bounded to at most one refusal per turn (never deadlocks —
  see below) and respects the same `VERIFIED_SCALA3_HOOKS=off` escape hatch
  as everything else in this file.

A blocking hook that misfires strands the agent with no way forward — that
risk is why `completion` shipped last, after `post-edit`, after every
non-blocking event had already proven itself.

## What gets injected

`gate.sh` assembles two sections and emits them at session start:

1. **Applicability facts** — delegated to `spec-lint.sh --context-only`, never
   recomputed here. A second implementation of the same facts could disagree
   with the one the lint reports, which is the drift defect rebuilt by hand.
2. **Workflow position** — active changes, artifacts present, next artifact.
   Derived from the schema's own artifact DAG in `schema.yaml`, not from a
   list copied into the script; a copied list is a future drift bug.

Roughly 1.4 KB, ~40 ms.

## The contract

```
gate.sh --event session-start|prompt-submit [--format hook-json|text] [--repo PATH] [--session ID] [--check-installed]
gate.sh --event post-edit --file PATH [--format hook-json|text] [--repo PATH]
gate.sh --event completion [--turn-text TEXT] [--stop-hook-active true|false] [--format hook-json|text] [--repo PATH] [--session ID]
```

| `--format` | Emits | For |
|---|---|---|
| `hook-json` | `{"hookSpecificOutput":{"hookEventName":"SessionStart"\|"UserPromptSubmit"\|"PostToolUse","additionalContext":"…"}}` for injection/post-edit events; `{"decision":"block","reason":"…"}` for a `completion` refusal | Claude Code, Codex CLI, Devin CLI |
| `text` | plain text | pi, and any harness that embeds stdout itself |

`--file`/`--turn-text`/`--stop-hook-active` are explicit overrides — mainly
for tests and manual runs. In real invocations they fall back to the JSON
Claude Code (and the other shell-family harnesses) already pipes to the
command's stdin: `--file` from `.tool_input.file_path`, `--turn-text` from a
best-effort scan of `.transcript_path`, `--stop-hook-active` from
`.stop_hook_active` directly.

**`post-edit`** classifies the path exactly the way `danger-scan.sh` and the
active-change convention already do — a spec file under an active change's
`specs/` runs `spec-lint.sh --artifacts`; a `.scala` file under `/src/main/`
runs `danger-scan.sh` — and returns findings as `additionalContext`/text.
Never invokes anything for any other path. Always exits 0.

**`completion`** detects a completion claim with a fixed, approximate
heuristic (this schema's own checkpoint marker, `Spec N/M complete`, plus a
few generic fallbacks — see `gate.sh`'s own comments; this is an explicitly
accepted Ring 8 limit, not a claim of precision). No claim → exit 0. A claim
while chain state is fully discharged → exit 0. A claim while requirements
are unresolved → exit 1, naming them. A claim while chain state is itself
undetermined → exit 2, with a reason distinct from "unresolved" ("evidence
could not be determined"). **Bounded refusal**: `stop_hook_active: true`
(Claude Code's own "I am already re-invoking after your prior block" signal)
always allows; for harnesses without that signal, a session-scoped marker
records one refusal and is cleared on the next `prompt-submit` — which
already marks a new turn's start — so a refusal never repeats within a turn
and never survives into the next one.

The gate runs on **every turn**, not only at session start — `--event
prompt-submit` maps to `UserPromptSubmit`. An unchanged payload (the facts
have not moved since the last call *in the same session*) is suppressed —
empty output, not a repeated injection. Session identity is `--session`,
falling back to `$CLAUDE_CODE_SESSION_ID`, then `$VERIFIED_SCALA3_SESSION_ID`,
then the invoking process's PPID (see `gate.sh`'s own comments for exactly
what each level verifies versus infers).

`--check-installed` answers "did the gate ever actually run here" from a
heartbeat file under the repo's git-dir — written unconditionally, before the
relevance guard, so a silent no-op invocation still counts. No operator setup
(no env var to remember to export) is required to read it:

```bash
openspec/schemas/verified-scala3/hooks/gate.sh --check-installed --repo .
# {"installed":true,"last_run":"2026-08-10T08:50:07Z","event":"prompt-submit"}
```

The heartbeat/suppression state lives under the repo's **git-dir**, resolved
with `git rev-parse --absolute-git-dir` — not assumed to be `$REPO/.git`.
That assumption broke silently inside a git **worktree**, where `.git` is a
plain pointer *file*, not a directory: the state dir was never created, so
neither the heartbeat nor per-session suppression ever engaged, with no
error. Found by hand-verifying this obligation from inside this project's own
worktree checkout; `tests/gate-payload.bats` now has a dedicated worktree
scenario so it cannot regress silently again.

Claude Code, Codex CLI and Devin CLI independently converged on the same hook
contract — JSON on stdin, exit 2 or `{"decision":"block","reason":…}` to stop
an action — so one script serves all three and only the config differs. pi and
OpenCode are in-process TypeScript, so their adapters shell out to the same
script. **The adapter decides when to ask and where to put the answer; all
logic stays in `gate.sh`.** That is what makes the adapters interchangeable.

If `openspec/` is absent, `gate.sh` emits nothing at all — a globally installed
hook must be invisible where it does not apply, or it becomes noise that people
disable, and a disabled hook enforces nothing.

## Adapters and how far each is verified

| Harness | Adapter | Event(s) | Verification |
|---|---|---|---|
| **pi** | `adapters/pi/verified-scala3-gate.ts` | `before_agent_start` (Tier B, once per prompt); `tool_result` on `write`/`edit` (Tier A post-edit) | Tier B: **end-to-end**, re-confirmed after the `gate.sh` rewrite that moved fingerprinting out of this adapter — ran under `pi -e`, payload appears in the message stream before the model call. Tier A post-edit: the extension **loads and runs without error** under `pi -e` after adding the `tool_result` handler (confirmed directly); the handler's own runtime behaviour was not exercised in that same run (no tool call reached — an unrelated provider-connectivity failure, not this code) — event name, tool-name filter, and `event.input.path` field are all verified against the installed `@earendil-works/pi-coding-agent` package's own `docs/extensions.md` and its `protected-paths.ts` example, not assumed. **Completion gate: not wired** — verified absent from pi's extension API, not an oversight; see the adapter file's own comment for what was checked |
| **Claude Code** | `adapters/claude.settings.json` | `SessionStart`, `UserPromptSubmit` (Tier B); `PostToolUse`, `Stop` (Tier A) | Tier B: **end-to-end**, both events, run live against this project's own `.claude/settings.json`. Tier A: **`PostToolUse` end-to-end** — installed live and confirmed firing on a real edit during this spec's own implementation (`--check-installed` flipped to `event:"post-edit"` immediately after a real `Edit` tool call, with no manual invocation in between). **`Stop` installed live, with human approval** given it is the one genuinely blocking mechanism in this schema — see `implementation-progress.md` for what it actually did when this spec's own checkpoint message triggered it |
| **Devin CLI** | `adapters/devin.hooks.v1.json` | `SessionStart`, `UserPromptSubmit`, `PostToolUse`, `Stop` | All four are documented Devin events with the same command/stdin contract as Claude's. **Not first-hand verified** — no Devin runtime available to confirm `additionalContext` is actually read, or that a `Stop` refusal is honoured; if `additionalContext` is ignored, switch the adapter to `--format text`. It also passes no `--session`, so it falls to the PPID fallback — an inference, not a confirmed session identity; if Devin ever shares one PPID across concurrent conversations, both Tier B suppression and the Tier A completion-gate's bounded refusal could wrongly collapse across them. Flagged, not fixed blind — add a verified `--session` source here once Devin documents one |

pi has no return-based injection on `session_start`, so the SessionStart
equivalent is `before_agent_start` fired once per prompt — already the
prompt-submission analogue, so it needed no new event wiring for this spec,
only the fingerprinting-moved-to-`gate.sh` change above. The payload is
suppressed and re-injected only when the facts change, per session — which is
a feature: if the agent creates `openspec/concepts/` mid-session, the altitude
rule's applicability changes and should be re-announced, not remembered.

## Install

```bash
openspec/schemas/verified-scala3/hooks/install-hooks.sh            # dry run
openspec/schemas/verified-scala3/hooks/install-hooks.sh --apply
```

Separate from `install-skills.sh` on purpose. A skill is inert text an agent
may read; a hook is a shell command the harness **executes**, every session,
with your permissions. That is a different decision, so it is a different
command, and it defaults to a dry run.

It targets only harnesses the project already uses (`.claude/`, `.pi/`,
`.devin/`), merges rather than clobbers, and is idempotent.

## Verifying it fires

Three levels, in increasing strength. Do them in order — each rules out a
different failure.

**1 · The script works** (rules out: broken script)

```bash
openspec/schemas/verified-scala3/hooks/gate.sh --event session-start --format text
```

**2 · The harness invokes it** (rules out: config not picked up)

The fastest check needs no setup — read the heartbeat `--check-installed`
writes on every invocation, including silent ones:

```bash
openspec/schemas/verified-scala3/hooks/gate.sh --check-installed --repo .
```

`"installed":false` after a session has definitely started means the config
never reached the harness. For finer-grained detail (every call, not just the
latest), set a trace file instead. Every invocation appends one line,
*including* the ones that emit nothing — "fired and stayed silent" and "never
fired" are indistinguishable from outside, and this is what separates them:

```bash
export VERIFIED_SCALA3_HOOKS_TRACE=/tmp/vs3-hooks.log
```

Then start the agent, and read the log:

```bash
cat /tmp/vs3-hooks.log
```

```
2026-08-07T19:07:31Z  event=session-start  format=hook-json repo=/…/adk4s   emit: 1396 chars
2026-08-07T19:07:31Z  event=session-start  format=text      repo=/…/plain   skip: no openspec/ here
```

An empty or missing file means the harness never called it — check that the
config landed where that harness reads it. The env var must be visible to the
process the harness spawns, so export it in the shell you launch the agent
from.

**3 · The model actually received it** (rules out: harness ran it but dropped
the output)

| Harness | How |
|---|---|
| pi | `pi -e <adapter> --mode json -p "hi"` — look for `customType: "verified-scala3-context"` in the message stream |
| Claude Code | start a session and ask, *before* it runs any tool: "how many concept files does the CONTEXT block report?" A correct number with no tool call means the injection landed |
| Devin | same question as Claude Code; if it cannot answer without looking, `additionalContext` was dropped — switch that adapter to `--format text` |

The level-3 question matters more than it looks. The hook exists to stop an
agent answering a repository question from memory; the test is whether it can
now answer one *without* looking.

## Disable / uninstall

```bash
export VERIFIED_SCALA3_HOOKS=off      # honoured by gate.sh, no uninstall needed
```

Or remove `.pi/extensions/verified-scala3-gate.ts`, `.devin/hooks.v1.json`, and
the `SessionStart` entry from `.claude/settings.json`.

## Prerequisites

The workflow declares an explicit, installable prerequisite set. It does not
depend on nothing — it depends on a stated list, for the same reason the old
zero-dependency rule existed: **a check that only runs on one machine is a
check that stops running.** Declaring and installing the set serves that
reasoning better than having no dependencies did, because the escaping and
testing that had to be hand-rolled were where the defects lived.

| Prerequisite | Required by |
|---|---|
| `bash` | every check and every hook — the interpreter they are written in |
| `git` | every check — diff, ls-files, and the per-spec baseline |
| `jq` | `gate.sh` and the scanners, for JSON parse and emit |
| `shellcheck` | Ring 1 — shell lint, run in CI and at apply Step 4 |
| `bats` | Ring 3 — the shell test suites in `../tests/`, run in CI and at apply Step 6 |
| `shfmt` | Ring 1 — shell formatting check, run in CI and at apply Step 4 |
| `openspec` | Ring 3 — the reachability tests render artifact instructions through the CLI, so the suite cannot run without it |

Still excluded for any gate check: **JVM** and **network**.

Superseded (schema v12): the rule was previously *"bash + git only — no JVM,
no network, no JSON processor"*, and `gate.sh` hand-rolled its JSON escaping
in `sed`/`awk` to honour it. That escaping is no longer required.
