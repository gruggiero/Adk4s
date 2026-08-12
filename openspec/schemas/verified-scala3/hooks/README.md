# hooks/ — harness enforcement for verified-scala3

Every check in this workflow lives in `scanner/*.sh`, and every one of them is
**opt-in**: an agent that does not run the script never sees the fact that
would have corrected it. A review once recorded the ALTITUDE rule as `N/A` on
a repository holding 30 concept files, because it answered a factual question
from memory instead of from the filesystem.

The scripts fix what happens **when** they run. Hooks fix **whether** they run.

## Scope: Tier B only

This directory currently ships **context injection**, nothing else.
`gate.sh` never blocks and always exits 0.

Blocking gates (spec-lint on `spec.md` writes, registry-check before commit)
are deliberately absent. A blocking hook that misfires strands the agent with
no way forward, and that risk is only worth taking after the non-blocking path
has proved itself in daily use. Promote later, on evidence.

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
gate.sh --event session-start [--format hook-json|text] [--repo PATH]
```

| `--format` | Emits | For |
|---|---|---|
| `hook-json` | `{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"…"}}` | Claude Code, Codex CLI, Devin CLI |
| `text` | plain text | pi, and any harness that embeds stdout itself |

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

| Harness | Adapter | Event | Verification |
|---|---|---|---|
| **pi** | `adapters/pi/verified-scala3-gate.ts` | `before_agent_start`, once per session | **End-to-end.** Ran under `pi -e`; the payload appears in the message stream as `customType: verified-scala3-context`, before the model call |
| **Claude Code** | `adapters/claude.settings.json` | `SessionStart` | Output shape validated against the documented `hookSpecificOutput.additionalContext` schema; JSON parse-checked |
| **Devin CLI** | `adapters/devin.hooks.v1.json` | `SessionStart` | `SessionStart` is one of Devin's 8 documented events and its command/stdin contract matches Claude's. **Context injection on Devin `SessionStart` is not first-hand verified** — if it ignores `additionalContext`, switch the adapter to `--format text` |

pi has no return-based injection on `session_start`, so the SessionStart
equivalent is `before_agent_start` fired once. It runs on every prompt, so the
payload is fingerprinted and re-injected only when the facts change — which is
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

Set a trace file and start a session. Every invocation appends one line,
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

## Portability

`gate.sh` is bash + git only — no JVM, no network, no `jq` — the same rule as
the gate checks, for the same reason: a check that only runs on one machine is
a check that stops running.
