# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-13
**Verification result**: CLEAN (profile matches build; no Scala stack changes from this change)

## Corrections applied to the project profile

None. This change modifies the verified-scala3 workflow's own bash/jq
tooling and adapter configs in `openspec/schemas/verified-scala3/` — no
`build.sbt`, `project/Versions.scala`, `project/Dependencies.scala`, or
Scala source changes. The project profile's Scala stack description is
unaffected.

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| `gate.sh --event tool-call` | new bash event (pre-execution block) | proposal §oracle-ordering-lock; the universal blocking tier across Claude Code, Devin, pi |
| `gate.sh --event post-bash` | new bash event (post-execution observation) | proposal §ambient-evidence-capture; ambient ledger capture |
| oracle phase state | new git-dir state machine | proposal §New Concepts; `oracle`/`implementation`/`verified` per (change, spec) |
| grant token | new git-dir state | proposal §New Concepts; harness-observed human approval |
| R8 provenance field | new ledger record field | proposal §New Concepts; `session` on `ring: "R8"` rows |

These are workflow-tooling capabilities, not Scala project capabilities. They
do not appear in `build.sbt` or `project/Dependencies.scala`. They are
declared in the workflow's own hook contract (`gate.sh` + adapters) and
state directory (`.git/verified-scala3-gate/`).

## Ring availability for THIS change

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | yes (bats) | bats tests run; `shellcheck` + `shfmt` pass on modified scripts. No sbt compile (no Scala code changed). |
| R1 lint | yes (shellcheck + shfmt) | `shellcheck` (declared prerequisite, CI-enforced); `shfmt -d` (declared prerequisite, CI-enforced). Scalafix/WartRemover N/A (no Scala code). |
| R2 architecture | no | No Scala module dependencies; this change touches only `openspec/schemas/verified-scala3/` tooling. Skip impact: none — the workflow's own layering is not Scala module layering. |
| R3 property/scenario | yes (bats) | bats scenario tests in `openspec/schemas/verified-scala3/tests/` — MANDATORY. Each enforcement mechanism gets a bats case. No CONCURRENT behavior (bash scripts are sequential). |
| R4 wire/persistence | yes (state files + ledger rows) | Phase/grant state files are new git-dir state (never committed); ambient capture appends ledger rows in the existing JSONL format. Backward-compat: existing `evidence-ledger.jsonl` fixtures decode unchanged. |
| R5 mutation | no | Stryker4s targets Scala only; no bash mutation tooling. Skip impact: bats scenario tests provide the equivalent coverage — each mechanism has a case that would fail before the fix. |
| R6 formal | no | No PureScala kernel; bash/jq logic tested via bats scenarios. Skip impact: the jq contract files are deterministic, total programs — bats tests exercising them are the verification. |
| R7 model checking | no | No distributed/event-driven invariants. |
| R8 adversarial review | yes | MANDATORY — fresh-context reviewer checks each mechanism against the failure report's drift pattern and the harness-agnostic blocking-surface analysis. |
| R9 telemetry | no | No telemetry stack detected (otel4s NOT PRESENT per capability-profile). |
| Concurrency kit | N/A | No concurrent behavior (bash scripts are sequential). |
| Code intelligence | N/A | No Scala code to semantically analyze; git grep is sufficient for bash/jq tooling. |

## Harness blocking surface (verified, not assumed)

This change's design rests on which harnesses can block, and where. Recorded
here as a capability-profile consequence so the specs can rely on it without
re-deriving it.

| Harness | Pre-execution tool block | Post-edit observation | Turn-completion block | Session/fork detection | Source |
|---------|--------------------------|----------------------|-----------------------|------------------------|--------|
| Claude Code | yes — `PreToolUse`, exit-2 / `{"decision":"block"}` | yes — `PostToolUse` (verified live) | yes — `Stop` (verified end-to-end) | `$CLAUDE_CODE_SESSION_ID` (verified in hook env) | `hooks/adapters/claude.settings.json`, hooks README adapter table |
| Devin CLI | yes — `PreToolUse` (documented, same contract) | yes — `PostToolUse` (documented) | yes — `Stop` (documented, **NOT first-hand verified**) | none verified (PPID fallback, flagged) | `hooks/adapters/devin.hooks.v1.json`, hooks README adapter table |
| pi | yes — `tool_call` → `{block:true,reason}` (only blocking event) | yes — `tool_result` (loads cleanly) | **NO — verified absent** (`agent_settled` notification-only) | in-process extension sees fork/session | `hooks/adapters/pi/verified-scala3-gate.ts` adapter comment, pi docs/extensions.md |

**Consequence**: the only enforcement point that exists on all three
harnesses is the pre-execution tool block. Every mandatory enforcement
mechanism in this change is therefore a pre-execution decision
(`gate.sh --event tool-call`). The completion gate remains a backstop for
the 2/3 harnesses that have it; pi is compensated by the stronger
pre-execution discipline (specs 1–2 prevent illegitimate states from being
created rather than detecting them at the exit door). This is the
harness-agnostic invariant the specs enforce.
