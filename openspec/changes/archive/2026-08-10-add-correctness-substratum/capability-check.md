# Capability Check

**Project profile**: `openspec/capability-profile.md` — verified 2026-08-08 (existed; re-verified against the build, updated in place)
**Verification result**: **3 rows corrected, 2 sections added** (listed below)

Detection was run against `build.sbt`, `project/Versions.scala`,
`project/plugins.sbt`, `stryker4s.conf`, `.metals/mcp.url`, the schema's
`ci/` templates, and `command -v` probes on the host. No value below is
copied from the schema's examples or from `openspec/config.yaml`.

## Corrections applied to the project profile

| Row | Was | Now | Evidence |
|-----|-----|-----|----------|
| Modules | 10 modules | **11** — adds `adk4s-harness-api` | `build.sbt:96` `lazy val \`adk4s-harness-api\` = (project in file("adk4s-harness-api"))`; introduced by the in-flight `add-harness-api-phase0` change |
| Module dependency graph | no `adk4s-harness-api` entry | `adk4s-harness-api → adk4s-core, verified % Test` | `build.sbt:97-100` — note the Ring 6 bridge dependency is already wired |
| Mutation tool (Ring 5 `mutate` list) | `**/memory/MemoryRetriever.scala` | `**/harness/HarnessState.scala` | `stryker4s.conf:12-14` — the list had already been retargeted by the in-flight change; the profile row was stale |

**Rows re-verified CLEAN** (checked, unchanged): Scala 3.8.4 / 3.7.2
(`Versions.Scala`, `Versions.ScalaVerified`); llm4s 0.3.4; cats-effect 3.7.0;
fs2 3.13.0; smithy4s 0.18.55; upickle 4.4.3; workflows4s 0.6.2; typesafe-config
1.4.9; logback 1.5.34; munit 1.3.3 / munit-cats-effect 2.2.0; Hedgehog 0.13.1;
plugins scalafix 0.14.7, scalafmt 2.6.1, scoverage 2.4.4, wartremover 3.6.1,
stryker4s 0.21.0; Metals MCP endpoint `http://localhost:8394/mcp` (probe:
`metals-call.sh probe` → *reachable*, matches `.metals/mcp.url`).

**Pre-existing note re-confirmed, not corrected**: `project/Versions.scala`
declares `SbtWartremover = "3.5.8"` while `project/plugins.sbt` pins `3.6.1`.
The profile already flags this as stale-and-unused; still true, still unused.
Out of scope here.

## Sections ADDED to the project profile

This change ships **bash**, and the profile described only the Scala stack.
A change shipping shell had no detected stack to target and would have had to
assume one — the exact failure this artifact exists to prevent. Two sections
added:

1. **`## Shell / Script Tooling (workflow scripts)`** — tool probes, the
   binding portability rule, and CI coverage.
2. **`### Ring availability for SHELL components`** — a per-ring row set for
   shell, so a bash change cannot silently inherit Scala verdicts.

### Detection results (shell)

Detected in two passes. The first pass (before human action) found the shell
toolchain empty; the human then installed the toolchain and lifted the `jq`
ban, and the second pass re-probed. Both are recorded — the first is why the
prerequisite set is now explicit rather than assumed.

| Tool | Pass 1 (initial) | Pass 2 (**current, authoritative**) | Path |
|------|------------------|-------------------------------------|------|
| `shellcheck` | ❌ ABSENT | ✅ **0.11.0** | `/home/linuxbrew/.linuxbrew/bin/shellcheck` |
| `bats` | ❌ ABSENT | ✅ **1.14.0** | `/home/linuxbrew/.linuxbrew/bin/bats` |
| `shfmt` | ❌ ABSENT | ✅ **3.13.1** | `/home/linuxbrew/.linuxbrew/bin/shfmt` |
| `jq` | ✅ 1.6 (banned) | ✅ **1.6 — ban LIFTED, now a prerequisite** | `/bin/jq` |
| `python3` | ✅ 3.14.5 | ✅ 3.14.5 | — |
| Tests for the 11 tracked `*.sh` | ❌ NONE | ❌ **still NONE** | — |

Versions verified by direct probe (`command -v` + `--version`), not from the
installation report — recording a capability from a claim rather than from the
host is the v11 defect this change exists to remove.

**POLICY CHANGE — the `jq` ban is lifted (human decision).** The old rule
(`hooks/README.md`: *bash + git only, no JVM, no network, no jq*) is replaced
by an explicit **prerequisite set**: `bash`, `git`, `jq`, `shellcheck`, `bats`,
`shfmt`. JVM and network remain excluded for gate checks. The retained
reasoning — *"a check that only runs on one machine is a check that stops
running"* — is now served by declaring and installing the set rather than by
having no dependencies.

Three consequences, all now in scope for this change:

1. **`hooks/README.md` and `gate.sh`'s header comment still assert "no jq" and
   are now factually wrong.** They must be amended here. A rule that the
   practice contradicts is the drift class this change exists to remove; it
   cannot ship one of its own.
2. **The Ring 3 harness question is settled the other way.** Pass 1 forced a
   hand-rolled plain-bash runner (bats was an external dependency the old rule
   forbade). With bats installed and the prerequisite set explicit, **bats is
   the detected framework** — generate `.bats` files. This supersedes the
   earlier recommendation in this document's own Consequences section.
3. **CI installs none of these.** The tools are on this host, largely under
   `/home/linuxbrew/.linuxbrew/bin`; `ci/github-actions.yml` has no install
   step and runs only `registry-check.sh`. Until it does, Rings 1 and 3 for
   shell are developer-local — the same opt-in weakness the hooks address.
   Adding the install + invocation step is in scope.

What did **not** change: there are still **zero tests** for the 11 tracked
scripts. That is now a *convention* gap (no layout to follow) rather than a
*capability* gap (no framework to use). The first bash change still establishes
the conventions.

### Ring 1 baseline — measured, not assumed

Having declared Ring 1 available, the next question is whether the tree passes
it today. Measured across all 11 tracked scripts:

**`shellcheck` — 8/11 clean, 5 findings in 3 files:**

| File | Finding |
|------|---------|
| `danger-scan.sh:62` | SC2086 unquoted `$ci` in `grep -nE $ci` — **intentional** (an optional `-i` word; quoting passes an empty arg). Needs `# shellcheck disable=SC2086` + reason. |
| `metals-call.sh:95` | SC2034 `init_resp` appears unused — **possibly genuine dead code**; verify. |
| `registry-check.sh:143,235,289` | SC2016 ×3, single quotes in awk programs — **intentional**. Need `disable=SC2016` + reason. |

Cheap to clean, and the annotation convention mirrors the schema's existing
`// danger-scan:allow <reason>` pattern: a suppression must carry a reason.
`gate.sh` — the file this change modifies most — is **already clean**.

**`shfmt` — 1955 diff lines across the 11 scripts.** Nothing in the tree has
ever been shfmt-formatted.

**Correction to the proposal:** its Ring 1 row asserted `shfmt -d` alongside
`shellcheck` without checking whether the tree passes. It does not, by a wide
margin. `shfmt -d` cannot be adopted as a blocking gate without a one-time
reformat of all 11 scripts, which would bury this change's real diff in 1955
lines of whitespace noise. Options, for the design artifact to settle:

| Option | Trade-off |
|---|---|
| **(a) shfmt on changed/new files only** (recommended) | No noise; the tree converges as scripts are touched. Gate is partial. |
| (b) One-time reformat commit, then blocking gate | Clean end state; a large mechanical commit that must land separately from this change to keep its diff reviewable. |
| (c) Drop shfmt | Loses formatting consistency; `shellcheck` still covers correctness, which is what this change is about. |

`shellcheck`, by contrast, can be adopted as a blocking gate immediately — 5
annotations away from a green tree.

### CI coverage (bounds how far hooks can be relied on)

`openspec/schemas/verified-scala3/ci/github-actions.yml` runs **only**
`registry-check.sh`. `spec-lint.sh` and `danger-scan.sh` are **not**
CI-enforced — they run only when an agent invokes them. This is directly
load-bearing on this change's thesis and is now recorded in the profile.

## Capabilities THIS change introduces

| Capability | Kind | Where declared in this change |
|------------|------|-------------------------------|
| Declared prerequisite set (`bash`,`git`,`jq`,`shellcheck`,`bats`,`shfmt`) | policy (replaces the zero-dependency portability rule) | capability-profile § PREREQUISITE RULE; amends `hooks/README.md` + `gate.sh` header |
| First `.bats` suite for the schema's scripts | test tooling (new convention) | proposal § Verification Strategy, Ring 3 |
| `evidence-ledger.jsonl` | persisted format (new, versioned) | proposal § What Changes / 2; format chosen in design Decision 1 |
| Hook heartbeat marker | persisted format (new) | proposal § What Changes / 5 |
| `PostToolUse` + `Stop` hook adapters | harness integration (new) | proposal § What Changes / 4 |
| CI install + invocation of `shellcheck`/`bats`/`shfmt` | CI (new) | `ci/*.yml`; closes the developer-local gap |

No new Scala module, library, or sbt plugin. The Step 12 build-dependency
delta for this change is expected to be **empty** for `build.sbt` / `project/`.
The new dependencies are **host tools**, declared in the prerequisite set —
they must be named in the Step 12 delta as such, since a prerequisite is an
unreviewed behaviour source exactly like a library.

## Ring availability for THIS change

This change is **shell + YAML + markdown**; no Scala source is touched. The
Scala ring rows therefore do not apply, and the shell rows govern.

| Ring | Available | Note |
|------|-----------|------|
| R0 compile | ⚠️ substitute | No compiler. Gate = `bash -n` per changed script + `schema.yaml` parses as YAML + `openspec status` still loads the schema. |
| R1 lint | ✅ | `shellcheck` 0.11.0 on every changed script + `shfmt -d`. Directly targets the quoting/word-splitting class in `gate.sh`'s string surgery. |
| R2 architecture | ⚠️ substitute | Assert the PREREQUISITE RULE (declared set only; no JVM, no network) per changed script. |
| R3 tests | ✅ **framework available; conventions to establish** | bats 1.14.0. Proposal's "MANDATORY, no waiver" stands and its precondition now exists. Task 1 becomes *establish the `.bats` layout*, not *build a runner*. |
| R4 compatibility | ⚠️ manual, **APPLIES** | The ledger is a persisted format read by `gate.sh` and the checkpoint generator: round-trip law + a fixture ledger from this version that must still parse after future edits. Written as `.bats` cases; no fixture framework. |
| R5 mutation | ❌ | No mutation tooling for bash. Skip; impact: surviving-mutant evidence is unavailable for the scripts. |
| R6 formal | ❌ N/A | Stainless is Scala-only. Also no decision/fold/law at the centre — the logic is I/O and string assembly. *(Stated as a verdict per schema v10: silence is not a verdict.)* |
| R7 model checking | ❌ | No TLA+/Apalache. |
| R8 adversarial review | ✅ | Manual, fresh-context. No longer the only substantive ring — R1 and R3 now carry real weight, which materially lowers this change's verification risk versus the Pass-1 assessment. |
| R9 telemetry | ❌ | No telemetry stack. |
| Concurrency kit | n/a | `TestControl` is cats-effect; no concurrent Scala in this change. |
| Code intelligence | ✅ | Metals MCP `http://localhost:8394/mcp` reachable. Of limited use here — Metals indexes Scala, not bash. `git grep` is the working tool for this change. |

## Decisions — RESOLVED

The three open decisions from Pass 1 are closed by the human's action
(toolchain installed, `jq` ban lifted, prerequisite set adopted):

1. ~~Ring 1 — install `shellcheck` or skip?~~ → **Installed (0.11.0).** Ring 1
   for shell is available and mandatory for this change; `shfmt -d` joins it.
2. ~~Ring 3 — plain-bash runner vs bats?~~ → **bats (1.14.0).** The old
   portability rule was the only argument against a framework; with the
   prerequisite set explicit, that argument is gone. Generate `.bats`.
3. ~~Thin verification base resting on Ring 8~~ → **materially improved.**
   R1 and R3 are now real. R5 remains unavailable (no bash mutation tooling)
   and R6/R7/R9 remain N/A, so R8 still matters, but it is no longer the only
   substantive evidence. The proposal's **High** risk rating is retained on
   blast-radius and false-confidence grounds — those are properties of what
   the change *does*, not of the tooling available to check it — but the
   staged ordering (ledger advisory before checkpoint-generating, `Stop` last)
   is now a prudence measure rather than a necessity.

## Remaining open items

| # | Item | Owner |
|---|------|-------|
| 1 | Amend `hooks/README.md` + `gate.sh` header: the "no jq" rule is obsolete | this change |
| 2 | Add tool install + `shellcheck`/`bats`/`shfmt` steps to `ci/*.yml` — otherwise Rings 1/3 are developer-local | this change |
| 3 | Establish `.bats` layout and naming conventions (none exist for 11 scripts) | this change, task 1 |
| 4 | Prerequisite set must be reported in the Step 12 build-dependency delta — a host-tool dependency is an unreviewed behaviour source like a library | this change, Step 12 |
