# Design: verified-scala3 control plane

## Why this design

The substratum (data plane) is sound and merged (step 0 done). This change
adds the **control plane**: the enforcement tier that runs *before* a tool
executes, so that illegitimate states (oracle inversion, bypassed gates,
missing ledger) cannot be *created*, rather than being detected at the
exit door.

The design rests on one verified structural fact (capability-check.md
"Harness blocking surface"): **the only blocking point that exists on all
three harnesses (Claude Code, Devin, pi) is the pre-execution tool block.**
pi has no turn-completion block (verified absence); Claude and Devin do
(`Stop`), but the completion tier is therefore a 2/3-harness backstop, not
the front line. Every mandatory enforcement mechanism is a pre-execution
decision in `gate.sh --event tool-call`; the completion gate stays as the
backstop it already is.

## Architecture: all logic in gate.sh, adapters decide when to ask

The existing layering (hooks README: "the adapter decides when to ask, the
script decides what") is preserved and extended:

```
harness tool call ──► adapter (PreToolUse / tool_call)
                         │ shells out to
                         ▼
                   gate.sh --event tool-call ── reads ──► git-dir state
                         │                           (phase, grant, fingerprints)
                         │ emits
                         ▼
            {"decision":"block","reason":…}  (hook-json)  |  exit 2 (text)
                         │
                         ▼
              adapter maps block → {block:true,reason} (pi)
              or harness honors exit-2 / decision:block (Claude, Devin)
```

No new harness contract is invented. Claude Code's `PreToolUse`,
Devin's `PreToolUse`, and pi's `tool_call` already accept exactly this
shape (verified against the adapter configs and the pi extension docs).
The `tool-call` event is to `gate.sh` what `post-edit` and `completion`
already are: a new `--event` value, same script, same state dir, same
bounded-refusal discipline.

## Phase state machine (spec 1)

Per (change, spec) in `.git/verified-scala3-gate/phase-<change>-<spec>`
(worktree-safe via `git rev-parse --absolute-git-dir`, the same resolution
the substratum fix already uses):

```
                 (no RED row)         RED row, exit!=0,
   oracle ─────────────────────► oracle     ancestor of HEAD
     │                                          │
     │ RED ledger.sh run row                     │
     │ (exit != 0, baseline B_red                │
     │  ancestor of HEAD)                        ▼
     ▼                                     implementation
 implementation                                │
     │                                          │ GREEN row, exit==0,
     │ GREEN ledger.sh run row                   │ B_green descendant of B_red
     │ (exit == 0, B_green,                      │
     │  merge-base --is-ancestor B_red B_green)  ▼
     ▼                                        verified
 verified
```

Transitions are **additive and read-only-into-state**: a recorded RED run
moves the phase forward; the *absence* of one blocks only the
implementation edit (production `*/src/main/**`), never the oracle edit
(`*/src/test/**`) or `ledger.sh run`. So the agent is never blocked from
producing the evidence that unblocks it — the strand-the-agent failure
mode the README warns is "genuinely difficult to escape" is structurally
impossible.

The polarity predicate is a pure function of existing artifacts
(`ledger.sh run` rows ⊕ `git merge-base --is-ancestor`); it enforces the
*temporal structure* that defines the oracle (test-execution evidence
strictly precedes implementation existence), not the test content (Ring
8's judgment domain).

## Grant state (spec 2)

Per (change, spec) in `.git/verified-scala3-gate/grant-<change>-<spec-N>`,
written only on the `prompt-submit` event when a checkpoint-presentation
record exists for spec N (the hash of `checkpoint.sh report`'s output).
The `tool-call` gate refuses spec N+1's Step-0 signature (new baseline
recording, edits to spec N+1's `implementation-progress.md` section, files
under spec N+1's spec dir) until `grant-<spec-N>` exists.

The approval proxy is deliberately coarse — "the user typed anything
after the checkpoint" — because the property that matters is that the
input arrived through the user channel, not the agent's output stream. No
harness in the set lets the agent fabricate a user prompt; the agent
writing `[x] PASSED` in progress.md becomes inert decoration. Grants are
session-scoped (the same `--session` identity gate.sh uses for
fingerprints) and cleared on a new turn (the completion gate's
bounded-refusal discipline, applied by analogy).

## Ambient capture (spec 3)

The observation channel each harness already fires on tool completion
(Claude/Devin `PostToolUse`; pi `tool_result`) widens to `Bash`:
`gate.sh --event post-bash` matches the command against an enumerated
ring-shape table and appends a `ledger.sh` row with the **harness-observed**
exit code. The ledger format is unchanged (ambient capture is a new
*producer* of rows, not a new *format*); existing readers decode ambient
rows unchanged. The `post-bash` gate never blocks (post-hoc tier
discipline). This closes the last gap: `ledger.sh run` requires the agent
to remember to use it; ambient capture makes recording a side effect of
the observation channel, so the *absence* of rows becomes the
machine-visible signal (undetermined chain state → completion block on
Claude/Devin, poisoned context on pi).

## Adversarial-review provenance (spec 4)

D4 (shipped) made adversarial-review rows name a resolvable hashed report
artifact. This change adds a `session` field on those rows; `checkpoint.sh`
requires the report's session to differ from the implementing session (the
fresh-context property). Where the harness exposes verified session
identity (Claude `$CLAUDE_CODE_SESSION_ID`; pi in-process), the check is
mechanical. Where it can't (Devin's PPID fallback — an inference, flagged
in the hooks README), the checkpoint presents it as a human-attested
limitation with date + mechanism (v12's "recorded limitation re-established
before relied upon" corollary), not an implicit self-assessment pass.

## Harness install verification + adapter wiring (spec 5)

`gate.sh --check-installed` becomes an apply-Step-0 requirement (recorded
in the checkpoint; `installed:false` stops the apply phase). The Devin
adapter's `Stop`/`PreToolUse` blocking, documented but never observed,
gets the README's three-level verification (script works → harness invokes
it → model received it) executed and recorded; the adapter table is
corrected to what Devin actually does. The `tool-call` event is wired into
all three adapter configs — the wiring that makes specs 1–2 real on every
harness. A schema v13 changelog entry records the defect class.

## Effect boundaries and Ring 6 triage

This is a bash/jq/YAML/adapter change. There is no PureScala kernel and no
Scala production code. Ring 6 (formal) is N/A; the jq contract files are
deterministic, total programs and bats tests exercising them are the
verification. The phase-state and polarity logic is pure (a function of
ledger rows ⊕ git ancestry) — but it is bash, not PureScala, so its
"verification" is the bats decision-table enumeration, not Stainless.

| Module / Function | Purpose | Ring 6? |
|---|---|---|
| `gate.sh` tool-call phase/grant decision | refuse/gate tool calls | No — bash control flow; tested via bats decision-table enumeration |
| oracle-polarity predicate (ledger rows ⊕ git merge-base) | did test evidence precede implementation? | No — bash over git/jq; the purity is structural, not PureScala |
| `post-bash` ring-shape match + ledger append | ambient evidence capture | No — side-effecting (appends to ledger) |
| `checkpoint.sh` adversarial-review provenance check | fresh-context enforcement | No — bash/jq predicate |

## Type strategy — invalid-state prevention

The "invalid states" this change prevents are workflow-process states,
not Scala type states, but the same hierarchy applies:

| Invariant | Placement | Justification |
|---|---|---|
| Implementation cannot be written before a red oracle exists | **impossible to express** (the gate refuses the edit) | tier 1 — the illegitimate action is blocked before it runs, the strongest tier |
| Next spec cannot start without a prior grant | **impossible to express** (the gate refuses Step-0 actions) | tier 1 |
| Adversarial review cannot be self-assessed (same session) | **rejected by checkpoint** (the R8 line reports no evidence) | tier 2 — a smart-constructor-equivalent: the checkpoint rejects the row |
| Evidence row without a run | **visible as undetermined chain state** (ambient capture makes absence visible) | tier 3 — a validator (chain-state) flags the absence |

No `case _` fallback, no swallowed error, no default-branch-returns-valid.
The gate's decision is a pure function of (phase, grant, edit-target,
ledger-rows, baseline-ancestry); the bats suite enumerates the decision
table rather than sampling it.

## Compatibility story (Ring 4)

- **Ledger JSONL format**: unchanged. Ambient capture appends rows in the
  existing format; the `session` field on adversarial-review rows is a new
  *optional* field (existing readers decode rows without it unchanged; the
  contract requires it only for `ring: "R8"` rows). Backward-compat test:
  archived `evidence-ledger.jsonl` fixtures decode unchanged.
- **Git-dir state files**: new, never committed, worktree-safe (resolved
  via `git rev-parse --absolute-git-dir`, the substratum fix). No existing
  state format changes; phase/grant files are additive.
- **Adapter configs**: additive (new `PreToolUse`/`tool_call` entries); the
  existing `SessionStart`/`UserPromptSubmit`/`PostToolUse`/`Stop` entries are
  unchanged.

## Verification map

| Module / area | Rings applying | Why |
|---|---|---|
| `gate.sh` tool-call / post-bash events | R0, R1, R3, R8 | new bash events; bats decision-table; shellcheck/shfmt; fresh-context review vs failure-report drift |
| oracle phase state + polarity predicate | R0, R1, R3, R8 | pure bash/jq; bats enumeration; the mandatory spec (oracle inversion is the foundation) |
| grant tokens + checkpoint-presentation records | R0, R1, R3, R8 | state machine; bats event×state table |
| `post-bash` ring-shape match + ledger append | R0, R1, R3, R4, R8 | bash; bats; ledger format compat (R4) |
| `checkpoint.sh` adversarial-review provenance | R0, R1, R3, R4, R8 | bash/jq; bats; ledger contract (R4) |
| adapter configs (Claude/Devin/pi tool-call wiring) | R0, R1, R3, R8 | config; bats config-shape checks; Devin three-level verification |
| schema.yaml v13 changelog | R0, R3, R8 | YAML; bats entry-presence; fresh-context review |

## Bootstrapping (same discipline as the archived substratum-review change)

bats-only verification for this change. The live gate's verdicts are NOT
evidence during this change (the gate is being modified — spec 1 adds the
`tool-call` event, spec 3 adds `post-bash`). The bats suite is the
evidence. Re-install hooks and re-run the full gate only after all 5 specs
are complete. The gate's verdicts on this change are a finding to
investigate, not a reason to revert.
