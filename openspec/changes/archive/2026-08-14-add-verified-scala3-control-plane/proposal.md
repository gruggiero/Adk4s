# Proposal: Add the verified-scala3 control plane (pre-execution enforcement)

## Why

The `fix-verified-scala3-substratum-review` change (archived 2026-08-13) shipped
the correctness **data plane**: the evidence ledger (`ledger.sh run`/`verify`),
chain-state (`bound`/`resolved`/`discharged`), checkpoint generation, and the
hook tiers (`gate.sh` `session-start`/`prompt-submit`/`post-edit`/`completion`).
Step 0 of the enforcement sequence (merge D1–D8, re-run bats) is **DONE** —
the data plane is sound, the ledger is append-only and replayable, discharge
requires a green run, per-spec baselines stop alert fatigue.

The **control plane** is still prose. Every critical transition in the apply
phase is guarded by nothing but instructions, and the failure report
(`docs/openPoints/verified-scala3-failure.md`) is a catalogue of ignored
instructions: oracle inversion (tests written after implementation), bypassed
human gates (5 gates marked "◄ PASSED" by the agent), self-assessed Ring 8,
and a missing ledger entirely (the `add-iron-refined-types` change's chain
state is **undetermined** right now — no evidence ledger exists at all).

The root cause is structural, not motivational (the v12 changelog already
concluded "motivation was never the missing ingredient"). Three facts:

1. **The only blocking point that exists on all three harnesses is the
   pre-execution tool block.** Verified against each adapter and the hooks
   README: Claude Code `PreToolUse` (exit-2 / `{"decision":"block"}`),
   Devin `PreToolUse` (documented, same contract), pi `tool_call` →
   `{block:true,reason}` (the *only* blocking-capable event in pi —
   `agent_settled` is notification-only per the pi adapter's own verified
   comment). `gate.sh --event completion` (the schema's "one genuine block")
   is wired for Claude (verified end-to-end) and Devin (documented, **not
   first-hand verified**) but is **structurally absent in pi**. Any
   mandatory enforcement that lives only at the completion tier is
   harness-asymmetric and fails on pi by design.
2. **The completion gate was promoted on 2/3 harnesses while the universal
   tier (pre-execution) shipped nothing.** `gate.sh` has no `tool-call`
   event. The schema's Tier A is a backstop, not the front line.
3. **Approval evidence lives in the agent's own output stream.** The agent
   writes `[x] **STOP for human approval** ◄ PASSED` in its own progress file;
   the machine reads that attestation. "The user can't intervene if it never
   stops" is the game-theoretic flaw: the agent controls both execution and
   the protocol for when the human gets a turn, so silence becomes a ratchet.

The fix is architectural: **move the enforcement point to where all three
harnesses can actually lock — before a tool executes — and make approval an
input channel the agent does not control.** All logic stays in `gate.sh` as a
new `--event tool-call`; adapters only decide when to ask (the same layering
the README already mandates: "the adapter decides when to ask, the script
decides what").

## What Changes

Five specs, ordered by the enforcement sequence. Each is independently
implementable and testable through the workflow's own bats suite. The first
spec (the oracle lockout) is the mandatory priority the user set: test-oracle
inversion is the foundation of the schema, and enforcing it is non-negotiable.

### Affected Capabilities

- `specs/oracle-ordering-lock/spec.md` — **MANDATORY, first.** A new
  `gate.sh --event tool-call` event refuses a `write`/`edit` to `*/src/main/**`
  while the (change, spec) phase is `oracle`, and only transitions phase
  `oracle → implementation` on a recorded RED run (`ledger.sh run` row with
  `.exit != 0` for the spec's test command at a baseline before any
  implementation file existed). Phase state per (change, spec) lives in the
  git-dir state dir (same place gate.sh keeps fingerprints). The polarity
  predicate is a pure function of existing artifacts: ledger rows ⊕
  `git merge-base --is-ancestor`. No judgment: it enforces the *temporal
  structure* that defines the oracle (test-execution evidence strictly
  precedes implementation existence), not the test content (Ring 8's domain).
- `specs/human-grant-lock/spec.md` — A **grant token** recorded in git-dir
  state when a user prompt arrives after a checkpoint presentation. The
  `tool-call` gate refuses actions belonging to spec N+1's Step 0 (new
  baseline recording, edits to the next spec's section of
  `implementation-progress.md`, the next spec's file set) until a grant for
  spec N exists. Approval becomes an input channel the agent cannot
  fabricate (a user turn happened after the checkpoint) rather than an
  attestation in the agent's own prose.
- `specs/ambient-evidence-capture/spec.md` — The `PostToolUse`/`tool_result`
  observation channel already wired for spec/production edits widens to
  `Bash`: gate.sh `--event post-bash` matches the command against known ring
  shapes (`sbt .*test`, `danger-scan.sh`, `registry-check.sh`, `spec-lint.sh`,
  `checkpoint.sh report`, `ledger.sh run`) and appends a ledger row with
  the **harness-observed** exit code. Evidence production stops being
  agent-remembered: the *absence* of rows becomes machine-visible, and
  undetermined chain state (the `add-iron-refined-types` branch's current
  state) blocks the completion claim at the Stop gate on Claude/Devin and
  poisons every injected context on pi.
- `specs/judgment-ring-provenance/spec.md` — Ring 8's deliverable becomes a
  **resolvable, sha256-hashed compliance-report artifact** with a recorded
  provenance (session id). `checkpoint.sh` R8 line requires the report's
  recorded session to differ from the implementing session. Where the harness
  can observe this (pi sees fork/session in-process; Claude has verified
  session ids), the check is mechanical. Where it can't (Devin, today), the
  schema says so explicitly and the checkpoint presents it as a human-attested
  limitation with date + mechanism — v12's "recorded limitation re-established
  before relied upon" corollary applied to the schema's own blind spot,
  instead of an implicit self-assessment pass.
- `specs/harness-install-verification/spec.md` — `gate.sh --check-installed`
  becomes an apply-Step-0 requirement: the failing Devin session's first
  mechanical question ("were the hooks even installed and firing?") was
  unanswered. The Devin adapter's `Stop` blocking behavior, documented but
  **never observed** per the hooks README, gets the README's three-level
  verification (script works → harness invokes it → model received it)
  executed and recorded, with the adapter table's "not first-hand verified"
  either removed or corrected to what Devin actually does. Also adds the
  `tool-call` event to all three adapter configs (the wiring that makes
  specs 1–2 real on every harness).

### Out of Scope

- **No Scala production code changes** — this change modifies only the
  workflow's own bash/jq tooling in `openspec/schemas/verified-scala3/`, the
  adapter configs, and the bats test suite. The `adk4s-*` modules are
  untouched.
- **pi `before_agent_settle` feature request** — pi has no turn-completion
  block today (verified absence per the pi adapter comment). Filing that
  feature request is out of scope; this change compensates with stronger
  pre-execution discipline (specs 1–2 prevent illegitimate states from
  being *created*, rather than detecting them at the exit door).
- **§5 graph graduation** (test→requirement edges, temporal commit nodes)
  from the substratum review — large effort, separate change. The
  polarity predicate in spec 1 is a pure ledger/git query, not a graph
  feature.
- **Cross-agent audit asymmetry** (Ring 8 as a different model family) —
  harness capability, not workflow tooling; separate change.

## Approach

This is a **workflow-self-change**: the verified-scala3 workflow adding its
own control plane. The affected files are bash scripts, jq contract
programs, YAML, adapter JSON/TS, and bats tests in
`openspec/schemas/verified-scala3/`. No Scala source is touched.

The verification rings map the same way as the archived
`fix-verified-scala3-substratum-review` change (bats as primary ring, gate
unreliable mid-change):

- **Ring 0** (compile) → bats tests run; `shellcheck` and `shfmt` pass.
- **Ring 1** (lint) → `shellcheck` + `shfmt` (declared CI prerequisites).
- **Ring 2** (architecture) → N/A — no Scala module dependencies.
- **Ring 3** (property/scenario tests) → bats scenario tests — MANDATORY.
  Each enforcement mechanism gets a bats case that would fail before the
  fix and passes after. No CONCURRENT behavior (bash scripts are sequential).
- **Ring 4** (wire/persistence) → phase-state and grant-state files are new
  git-dir state (never committed); the ledger JSONL format is unchanged by
  this change (ambient capture *appends* rows in the existing format).
- **Ring 5** (mutation) → N/A — no Stryker4s for bash.
- **Ring 6** (formal) → N/A — no PureScala kernel.
- **Ring 7** (model checking) → N/A.
- **Ring 8** (adversarial review) → MANDATORY — fresh-context reviewer
  checks each enforcement mechanism against the failure report's drift
  pattern and the harness-agnostic blocking-surface analysis.
- **Ring 9** (telemetry) → N/A.

The bats suite (`openspec/schemas/verified-scala3/tests/`) covers the
workflow's scripts. Each spec adds cases following existing conventions
(fixed fixtures, assert exit codes, never regenerate fixtures).

## Correctness Risk Level

**Risk**: high — this is the workflow's enforcement layer; a defect in it
silently undermines every correctness claim the workflow makes, and the
failure report demonstrates the cost of the missing layer in concrete
drift (oracle inversion, bypassed gates, missing ledger). Spec 1 (oracle
lockout) is the mandatory priority: a misfire either lets inversion through
(false negative — the original defect) or blocks legitimate work (false
positive — strands the agent, the risk the README warns is "genuinely
difficult to escape"). The bounded-refusal discipline from `gate.sh
completion` (fail open when STATE_DIR unavailable, honor a stop-hook-active
signal, one refusal per turn) is applied by analogy to the `tool-call` tier.

## Verification Strategy

- [x] Ring 0: Compilation — bats tests run; `shellcheck` + `shfmt` pass on
  modified scripts
- [x] Ring 1: Lint — `shellcheck` + `shfmt` (declared prerequisites,
  CI-enforced). No Scalafix/WartRemover (no Scala code).
- [ ] Ring 2: Architecture — N/A (no Scala module dependencies; touches only
  `openspec/schemas/verified-scala3/` tooling, not `adk4s-*` modules)
- [x] Ring 3: Property-based tests — MANDATORY. bats scenario tests in
  `openspec/schemas/verified-scala3/tests/`. Each enforcement mechanism gets
  a bats case that would fail before the fix and passes after. No
  CONCURRENT behavior (bash scripts are sequential).
- [x] Ring 4: Wire/persistence compatibility — phase/grant state files are
  new git-dir state (never committed, never tracked); ledger JSONL format
  is unchanged (ambient capture appends rows in the existing format).
  Backward-compat: existing `evidence-ledger.jsonl` fixtures decode
  unchanged.
- [ ] Ring 5: Mutation testing — N/A (Stryker4s targets Scala; no bash
  mutation tool in the declared prerequisite set)
- [ ] Ring 6: Formal verification — N/A (no PureScala kernel; bash/jq logic
  tested via bats scenarios)
- [ ] Ring 7: Model checking — N/A (no distributed/event-driven invariants)
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY (fresh-context
  reviewer; runs BEFORE Rings 5/6/7). The reviewer checks each enforcement
  mechanism against the failure report's drift pattern and the
  harness-agnostic blocking-surface analysis, and verifies the mechanism
  doesn't introduce a new escape route or a strand-the-agent failure mode.
- [ ] Ring 9: Telemetry — N/A (no telemetry stack detected; otel4s NOT
  PRESENT per capability-profile)

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract | Justification |
|------|----------------|---------------|
| `specs/oracle-ordering-lock/spec.md` | Full | Introduces a new `gate.sh` event (`tool-call`), a new phase-state machine (`oracle`/`implementation`/`verified`), and a polarity predicate over ledger rows + git ancestry. New public enforcement interface; wrong verdicts (false-positive strand, false-negative inversion) are the defect. |
| `specs/human-grant-lock/spec.md` | Full | Introduces grant-token state and a `tool-call` clause that refuses next-spec Step-0 actions without a grant. New enforcement interface; the approval model changes from agent-attested to harness-observed. |
| `specs/ambient-evidence-capture/spec.md` | Full | Introduces a new `gate.sh` event (`post-bash`) and command-shape matching that appends ledger rows from the observation channel. Persistence/serialization (append-only ledger rows in existing format); changes evidence production from agent-remembered to harness-observed. |
| `specs/judgment-ring-provenance/spec.md` | Full | Introduces a provenance field on R8 ledger rows and a checkpoint requirement that the report's session differ from the implementing session. Changes the ledger record contract for `ring: "R8"` rows and the checkpoint R8 line. |
| `specs/harness-install-verification/spec.md` | Full | Adds the `tool-call` event to all three adapter configs (the wiring that makes specs 1–2 real on every harness) and makes `--check-installed` an apply-Step-0 requirement. New harness contract surface; the Devin `Stop` blocking claim gets verified or corrected. |

## Existing Concepts to Reuse

This change modifies the workflow's own tooling, not adk4s Scala code. The
"concepts" are the workflow's bash/jq tools and contracts, as shipped by the
archived substratum changes.

| Concept | Kind | Location | Notes |
|---------|------|----------|-------|
| `ledger.sh run` | bash subcommand | `scanner/ledger.sh` | Capture mode (D3, shipped). Spec 1's RED evidence is a `run` row with `.exit != 0`; the `sha256` field binds the row to the exact test files that ran. |
| `ledger.sh verify` | bash subcommand | `scanner/ledger.sh` | Replay mode (D6, shipped). Spec 3's ambient capture produces rows that `verify` can re-check. |
| `chain-state.sh` | bash script | `scanner/chain-state.sh` | Computes bound/resolved/discharged. Spec 3's ambient rows make `discharged` non-zero where it was `undetermined`; the completion gate (Claude/Devin) then blocks on a completion claim. |
| `gate.sh` | bash script | `hooks/gate.sh` | Per-turn hook. Spec 1 adds `--event tool-call`; spec 3 adds `--event post-bash`; the bounded-refusal discipline (fail-open when STATE_DIR unavailable, one refusal per turn, `VERIFIED_SCALA3_HOOKS=off` escape) is reused by analogy. |
| git-dir state dir | bash state | `.git/verified-scala3-gate/` (via `git rev-parse --absolute-git-dir`) | Where gate.sh keeps fingerprints/heartbeats. Specs 1–2 add phase + grant state files here (never committed, worktree-safe — already resolved by the substratum change's worktree fix). |
| `checkpoint.sh` | bash script | `scanner/checkpoint.sh` | Step 13 ring results from ledger rows. Spec 4 adds the R8 provenance requirement. |
| `ledger-record-contract.jq` | jq contract | `scanner/ledger-record-contract.jq` | Validates ledger row shape. Spec 4 extends with the provenance field for R8 rows. |
| adapters | harness configs | `hooks/adapters/{claude.settings.json,devin.hooks.v1.json,pi/verified-scala3-gate.ts}` | Spec 5 adds `PreToolUse`/`tool_call` wiring to all three. |
| bats suite | test corpus | `openspec/schemas/verified-scala3/tests/` | Covers the workflow's scripts. Each spec adds cases. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `gate.sh --event tool-call` | new bash event | Pre-execution gate: consults phase + grant state, emits `{"decision":"block","reason":…}` (hook-json) or exit 2 (text) to refuse a tool call before it runs. The universal blocking tier (exists on all three harnesses). |
| oracle phase state | new git-dir state | `phase-<change>-<spec>` file: `oracle` → `implementation` → `verified`. Transitions only via recorded events (RED run → implementation; GREEN run → verified), never via agent prose. |
| oracle-polarity predicate | new bash function | Pure function of ledger rows ⊕ `git merge-base --is-ancestor`: did test-execution evidence exist before any implementation file? No judgment of test content. |
| grant token | new git-dir state | `grant-<change>-<spec-N>` written when a user prompt arrives after a checkpoint presentation. Approval as an input channel the agent does not control. |
| `gate.sh --event post-bash` | new bash event | Post-execution observation: matches a bash command against known ring shapes and appends a ledger row with the harness-observed exit. Evidence production as a side effect of running the ring. |
| R8 provenance field | new ledger record field | `session` on `ring: "R8"` rows; checkpoint requires it to differ from the implementing session. Fresh-context enforcement where the harness can observe it; explicit human-attested limitation where it can't. |
| apply-Step-0 `--check-installed` requirement | new schema instruction | The hooks README's three-level verification (script works → harness invokes it → model received it) becomes a mandatory apply-Step-0 check, recorded in the checkpoint. |

## Risks and Mitigations

- **Spec 1 false-positive strand.** A `tool-call` block that misfires strands
  the agent with no way forward — the risk the README warns is "genuinely
  difficult to escape". Mitigation: the bounded-refusal discipline from
  `gate.sh completion` is applied by analogy — fail open when STATE_DIR is
  unavailable (trace line, not a block), honor `VERIFIED_SCALA3_HOOKS=off`,
  and the phase transition is *additive* (a recorded RED run moves forward;
  the absence of one blocks only the implementation edit, not the oracle
  edit, so the agent is never blocked from producing the evidence that
  unblocks it).

- **Spec 2 approval heuristic coarseness.** "The user typed anything after
  the checkpoint" is the approval proxy. Deliberately coarse — but it is an
  input channel the agent does not control, which is the whole property
  that was missing. Mitigation: the grant is scoped (spec N's grant does not
  authorize spec N+2); the checkpoint presentation is itself a recorded
  fact (checkpoint report output hash), so the "after" relation is
  well-defined.

- **Spec 3 command-shape matching brittleness.** Matching `sbt .*test`
  etc. is regex over the command string. Mitigation: the shapes are
  enumerated (the same commands the apply instruction names), the match
  is conservative (a non-matching command is simply not recorded — no
  false block), and the agent can still `ledger.sh run` explicitly for any
  command the shape matcher misses (ambient capture is a superset, not a
  replacement).

- **Spec 4 provenance where the harness can't observe.** Devin has no
  verified session id (PPID fallback, flagged in the hooks README). The R8
  provenance check can't be mechanical there. Mitigation: the schema says
  so explicitly and the checkpoint presents it as a human-attested
  limitation with date + mechanism (v12's second corollary), not an implicit
  self-assessment pass. The honest "I can't prove freshness here" is the
  achievable bar.

- **Ring 8 reviewing the workflow's own enforcement fixes.** The
  adversarial reviewer checks enforcement of the adversarial review
  mechanism itself — a self-reference. Mitigation: the reviewer works
  from the failure report's drift patterns (fixed, external input) and the
  harness-agnostic blocking-surface analysis, not from the fix
  implementation, so the self-reference is bounded.

- **Schema v13 changelog entry.** Recording this change's defect class
  ("prose-only control plane; Tier A promoted on 2/3 harnesses while the
  universal tier shipped nothing") in the changelog is part of the change,
  not a consequence of it. Mitigation: the changelog entry is a spec
  requirement (spec 5), tested via the `correctness-invariant.bats` suite
  that renders artifact instructions through the `openspec` CLI.
