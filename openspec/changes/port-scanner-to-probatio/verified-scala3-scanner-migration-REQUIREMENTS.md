# REQUIREMENTS — probatio: the verified-scala3 tooling rewrite

**Replace the verified-scala3 schema's bash/jq/python3 substratum with `probatio` — a versioned Scala 3 artifact under `org.sinemenda.probatio`, distributed as native binaries, integrated into sbt via a thin AutoPlugin. The schema itself is renamed `verified-scala3` → `probatio` at the v14 bump (one-major compat aliases).**

**Status:** Draft for OpenSpec change authoring
**Target change name:** `port-scanner-to-probatio` (short: "the probatio port")
**Org / repo:** `sinemenda/probatio` (GitHub org `sinemenda` — *sine menda*, "without defects"; also hosts `sinemenda/accordant4s`)
**Maven group:** `org.sinemenda.probatio`
**Touches:** `openspec/schemas/verified-scala3/scanner/`, `openspec/schemas/verified-scala3/hooks/`, `openspec/schemas/verified-scala3/tests/` (oracle, read-mostly), `openspec/schemas/verified-scala3/hooks/README.md` (prerequisite table), `openspec/schemas/verified-scala3/schema.yaml` (version bump + rename), `.pi/skills/openspec-*/` (script-path references), new `workflow/` sbt subprojects (`probatio-core`, `probatio-cli`, `sbt-probatio`), new GitHub release pipeline under `sinemenda/probatio`
**Depends on:** sbt 1.12.12 (present), Scala 3 (present), GraalVM for native-image (to be acquired — see §0, V1), GitHub org `sinemenda` (created)
**Informed by:** this document is the distilled output of the schema audit recorded below; every load-bearing claim cites the file it was verified against.

---

## 0. Verification note

This document was written against the repository at the current working tree.
Facts that are **verified** (read directly from the named file this week):

- Script inventory and line counts (§1.1 table) — measured with `wc -l`.
- The prerequisite table and the "JVM and network excluded" rule —
  `hooks/README.md`, prerequisite table and supersession note for schema v12.
- The 0/1/2 three-way exit-code protocol — headers of `scanner/ledger.sh`,
  `scanner/chain-state.sh`, `hooks/gate.sh`.
- The jq contract files and their role as the single statement of record
  formats — headers of `scanner/ledger-record-contract.jq`,
  `scanner/chain-state-report-contract.jq`, `scanner/gate-hookjson-contract.jq`.
- The existing Scala-script precedent — `scanner/concept-scanner.scala`
  (scala-cli, `//> using scala 3.5.2`, os-lib + scalameta), with compiled
  output committed under `scanner/.scala-build/`.
- The bats oracle: 18 files, ~6,364 lines, under `tests/` (`bats-core`
  installed at `~/.linuxbrew/bin/bats`).
- The three hook adapters — `hooks/adapters/claude.settings.json`,
  `hooks/adapters/devin.hooks.v1.json`, `hooks/adapters/pi/`.
- The gate banner's current two-part structure (schema v13): a short invariant
  block, then a "session context" block containing the schema-version row
  (read from `schema.yaml`), per-root INSTRUCTION DRIFT warnings (the
  `generatedBy: verified-scala3-schema/<N>` stamp (to be renamed
  `generatedBy: probatio-schema/<N>` per R-V2) is read across repo-level
  `.agents/.claude/.pi` and `$HOME/.agents/.claude/.zcode` skill installs),
  applicability rows that cite the spec-lint artifact's NUMBERED judgment
  checks (3, 6, 17 ALTITUDE, 18 CONCURRENCY), a live chain-state section when
  active changes exist, the `gate checks` tool list, and the
  "READ FROM DISK … facts, not recollection" trailer.
- The pre-execution `tool-call` tier carries TWO locks (schema v13): the
  oracle-ordering lock and the human-grant lock (non-production writes
  targeting spec N+1 are blocked while spec N's checkpoint has been presented
  but no granting user prompt has been observed). The completion tier exists
  on 2/3 harnesses (Claude Code, Devin); pi has no blocking completion event
  (verified absent, per `hooks/README.md`), so on pi the pre-execution tier
  is the only enforcement surface.
- Mill script bundled libraries — fetched from
  `mill-build.org/mill/scalalib/script.html`: OS-Lib, uPickle, Requests-Scala,
  MainArgs, PPrint; `//| mvnDeps`, `//| moduleDeps`, `ScalaModule.Raw`.
- sbt version: 1.12.12 (`project/build.properties`).

Facts that remain **unverified and must be spiked before implementation** (the
change's tasks.md MUST carry these as Ring-0/-1 spikes):

- **V1.** GraalVM native-image builds `probatio-core` + `probatio-cli` without
  hand-maintained reflection configuration. uPickle/ujson/os-lib/mainargs are
  believed GraalVM-safe in practice; scalameta (only needed by the
  concept-scanner port) is the known risk. Spike: native-image one module
  using uPickle read/write of the ledger ADTs, run against the fixture corpus.
- **V2.** Cold and warm wall-clock latency of the resulting `gate` binary
  meets the §6.4 budget on linux-x86_64 (the development platform).
- **V3.** The pi TS adapter (`hooks/adapters/pi/`) invokes `gate.sh` by path
  from the extension; the shim replacement must be verified end-to-end under
  `pi -e` (the hooks README records that Tier A post-edit runtime behavior was
  not yet exercised there).
- **V4.** Prebuilt-binary download host choice (GitHub Releases vs. Maven as
  zip artifacts) and checksum story for consumer machines.

---

## 1. Motivation

### 1.1 Current state (measured)

| Component | Lang | Lines | Role |
|---|---|---|---|
| `hooks/gate.sh` | bash | 1,492 | per-turn hook entry; two-part banner (invariant + session context w/ drift detection + live chain-state); Tier B never-blocking + dual-lock tool-call + completion (2/3 harnesses) |
| `scanner/spec-lint.sh` | bash | 605 | mechanical lint F1–F10 + CONTEXT facts |
| `scanner/chain-state.sh` | bash | 673 | the bound/resolved/discharged verdict |
| `scanner/ledger.sh` | bash | 616 | append-only evidence ledger |
| `scanner/checkpoint.sh` | bash | 514 | checkpoint assembly |
| `scanner/registry-check.sh` | bash | 346 | concept-registry edge checks |
| `scanner/reconcile.sh`, `scan.sh`, `removal-audit.sh`, `danger-scan.sh`, `impact-scan.sh`, `metals-start.sh`, `install-skills.sh`, `scan.sh` | bash | ~1,100 | orchestration; `metals-call.sh` does LSP Content-Length framing in shell |
| `*-contract.jq` ×3 | jq | 344 | executable record/report/payload format contracts, single source shared by impl + oracle |
| `scanner/openspec-graph.py` | python3 | 626 | transitive traceability/fact extraction for chain-state |
| `scanner/concept-scanner.scala` | scala-cli | 538 | semantic concept extraction via Scalameta |
| `tests/*.bats` ×18 | bats | ~6,364 | the oracle |

The workflow is **polyglot across four runtimes** (bash, jq, python3, JVM via
scala-cli) today — but the prerequisite table in `hooks/README.md` declares
only bash/git/jq/python3, and the no-JVM rule is de facto broken by
`concept-scanner.scala`. The discipline is real (append-only ledger; three-way
exit codes; contracts as a single executable statement) but every check of it
is enforced by hand-rolled shell convention rather than by a compiler.

### 1.2 Defect evidence (the ceiling is documented, not conjectured)

The scripts carry their own incident history in comments — each is a defect
class the proposed replacement removes by construction:

| Defect (in-file evidence) | Class removed by |
|---|---|
| `ledger.sh`: `shift 2` doesn't shift with one arg left → infinite loop parsing `--baseline` | MainArgs typed parsing (missing value = named parse error, by construction) |
| `chain-state.sh`: `die_undetermined` called before its variables were declared → `set -u` crash, undetermined surfaced as exit-1 shell crash | Constructor-initialized immutable values; no declaration-before-use |
| Every script: three-way 0/1/2 semantics hand-rolled and summarizable only in comments | Central sealed `Outcome` trait with the three cases as data |
| `gate.sh` v12 supersession note: hand-rolled JSON escaping in sed/awk "were where the defects lived" | uPickle/ujson emit; strings are strings, not bytes to escape |
| `metals-call.sh`: LSP Content-Length framing in shell | Byte streams framed by a library, not `read`/`printf` |
| Skill installs carry `generatedBy: verified-scala3-schema/<N>` and for years nothing read it (v11 changelog) — agents followed pre-drift instructions perfectly | The drift scan becomes a data-driven check in the ported tool (schema version from `schema.yaml`, stamps from six install roots), not a one-off shell loop |
| `chain-state.sh` re-reads spec-lint's *table structure* to attribute verdicts because "spec-lint's own output has no such attribution" | A typed `LintReport` AST consumed by both, once |

### 1.3 Why now

- The schema's own prereq policy is explicitly changeable per this project's
  owner (this change): the table is *declared and versioned*, not sacred; v12
  already changed it once, from a stricter rule that caused the JSON-escaping
  defects to the current one.
- Native packaging has been made **mandatory** by the same decision, which
  removes the sole remaining argument against a JVM implementation (per-turn
  hook latency).

---

## 2. Scope

### 2.1 In scope

1. **`probatio-core`** — Scala 3 library in this repo's build: the ported
   domain — `LedgerRecord`/`ChainStateReport`/`GatePayload` ADTs, the three
   jq contracts as validators, spec-lint checks F1–F10 + CONTEXT facts,
   chain-state verdict logic, ledger operations, checkpoint assembly,
   registry-check, graph extraction (port of `openspec-graph.py`), the metals
   JSON-RPC client, and the concept-scanner logic (moved off scala-cli).
2. **`probatio-cli`** — mainargs-based entrypoints: one subcommand per
   current script, argv/stdout/exit-code **byte-compatible** with the bash
   originals. Multicall binary `probatio` dispatching on `argv(1)`.
3. **Native packaging** — GraalVM native-image launchers; prebuilt binaries
   per platform on GitHub Releases; jar fallback.
4. **sbt AutoPlugin** (`sbt-probatio`) — thin; installs the binary,
   exposes delegating sbt tasks.
5. **Hook shims** — `gate.sh`, `spec-lint.sh`, etc. become 3-line `exec`
   shims to the `probatio` binary; adapters untouched.
6. **Migration conformance** — the existing bats oracle is the porting
   acceptance suite and passes **unmodified** at every migration step; the
   `.jq` contracts live on temporarily as conformance fixtures.
7. **Prerequisite table amendment** (schema version bump): java removed from
   *runtime* (native binaries), jq/python3/shellcheck/shfmt retired when
   their last consumer migrates, bash retained only for shims.
8. **Consumer updates** — the three `.pi/skills/` that reference scanner
   script paths (`openspec-code-intel`, `openspec-property-tests`,
   `openspec-scan-concepts`), CI templates, and the schema docs.
9. **Metals JSON-RPC client in Scala** — replaces `metals-start.sh` /
   `metals-call.sh` (LSP framing, initialization handshake, lifecycle).

### 2.2 Out of scope — explicitly, with reasons

| Excluded | Reason |
|---|---|
| Rewriting `openspec` (the npm CLI) itself | Different project, different owner |
| Mill/Kotlin-script/gradle adapters for consumers | The sbt plugin is **one** adapter of potentially several; a mill adapter is a separate change when a mill-concrete need exists |
| Rewriting the schema's spec templates or the ring definitions | The workflow semantics do not change — only the tools' implementation language |
| sbt 1.x→2.x migration of this repo's build | Independent change; the plugin must work on sbt 1.x because this repo *is* sbt 1.x |
| Making the pi adapter call the binary directly in TS | The shim keeps adapter code untouched; direct calling is a future optimization |
| Extending the workflow with new checks | Feature-freeze during port: one behavior change at a time is already too many |

---

## 3. Architecture

```
probatio tooling (this repo during migration; extraction-ready by construction)
├── workflow/core     sbt subproject `probatio-core`  (org.sinemenda.probatio:probatio-core_3)
│                     Scala 3 lib — ADTs, validators (ex-jq contracts),
│                     parsers (markdown tables/headings/spec-structure),
│                     check logic. NO main, NO args parsing, NO GraalVM config.
│                     Depends on NOTHING adk4s-side (R-ARCH1).
├── workflow/cli      sbt subproject `probatio-cli`  (org.sinemenda.probatio:probatio-cli_3)
│                     mainargs @main entrypoints; each = one current script;
│                     Outcome[Int] at the boundary maps to exit 0/1/2.
│                     Multicall binary `probatio` (alias `prob`).
│                     Own version scheme (vX.Y), own publish config.
├── workflow/plugin   sbt subproject `sbt-probatio`  (org.sinemenda.probatio:sbt-probatio_2.12)
│                     sbt 1.x AutoPlugin (Scala 2.12). Thin: links NOTHING
│                     from probatio-core; farms everything to the binary.
│                     Tasks: probatioInstall, probatioSpecLint, probatioChainState,
│                     probatioCheckpoint, probatioLedgerAppend.
│                     On sbt 2.x: unchanged, links against Scala 3 build.
└── distribution      GitHub Releases (from sinemenda/probatio, tags prefixed v/):
                      probatio multicall binary
                      × {linux-x86_64, macos-aarch64, macos-x86_64,
                      windows-x86_64 (jar-only fallback)}
                      + full assembly JAR + SHA-256 checksums per artifact + SBOM.
```

### 3.1 Repository strategy — PHASED, not binary

**During migration: this repo.** The strangler (§5 cap `migration-protocol`)
is oracle-driven and the oracle *lives here, next to the schema*: bats
suites, jq contract fixtures, goldens. Porting one subcommand must be ONE PR
touching impl + oracle + schema atomically — the drift detector compares
installed skill `generatedBy` stamps against `schema.yaml`'s version, so the
schema and the tool/banner version are two ends of one invariant that must
bump together. The changelog itself is the second reason: every workflow
feature exists because THIS repo's own changes (dogfooding) generated the
incident that taught it. A tools-only repo would (a) need an externally
released schema to validate against — circular during the migration — and
(b) sever the same-day schema-edit ↔ adk4s-change feedback loop the schema's
entire development has ridden.

**After migration, at a stated trigger: a dedicated repo.** Triggers (any
one): (a) a consumer project *outside this repo* version-pins the tools;
(b) the tools' release cadence visibly diverges from adk4s's, polluting
tags/releases for both audiences (adk4s today publishes nothing —
`publish / skip := true`, no `publishTo`, no publish CI — so probatio
becomes this org's FIRST released artifact either way); (c) the native-image
matrix (3 OSes) becomes measurable per-PR cost on the library repo. The
even-cleaner end-state at extraction time is to move THE WHOLE
`openspec/schemas/verified-scala3` tree — schema, docs/tutorial, skills,
hook adapters, bats oracle, ported tooling — into `sinemenda/probatio` as a
dedicated product repo, because the schema is already a product-in-hiding
consumers copy. The `sinemenda` org already exists and will also host
`sinemenda/accordant4s` (model-based testing oracle — a future probatio
companion).

**The boundary that makes both options cheap (R-ARCH1):** the tooling
subprojects depend on NOTHING in adk4s — no library module, no test util,
no shared source. A dependency-lint rule (the project's own Ring 2
mechanism, already in the build) enforces it in CI; extraction then reduces
to `git filter-repo` + a publish-config change, not a rewrite. The plugin is
already forbidden (R-S1) from linking `probatio-core`; the same discipline
applies in reverse.

**Data flow, unchanged from today:** hook adapters → shim script (`exec`s the
multicalled binary at a known path, e.g. `~/.cache/probatio/bin/probatio` or
`target/probatio/bin/probatio` in-project) → `probatio gate --event …` →
uPickle-encoded hook JSON on stdout → adapter embeds it. Concepts:

- **The protocol is the artifact.** argv surface, 0/1/2 exit codes, and the
  JSON payload shapes (as defined by the three `.jq` contracts until §5's
  port of them) are the public contract; implementations are fungible.
- **Three stages of migration, each independently compile-checked:**
  1. Contracts ported to `probatio-core` as validators. The `.jq` files
     remain as conformance fixtures; a property test asserts validator ⊨
     contract over the fixture corpus and vice versa.
  2. `probatio-cli` subcommands ported one at a time, earliest-dependency-
     first, each verified against the unchanged bats oracle via the already-
     existing `*_OVERRIDE` seams (e.g. `SPEC_LINT_OVERRIDE`,
     `CHAIN_STATE_OVERRIDE`, `DANGER_SCAN_OVERRIDE`).
  3. Hook shims replaced last, only after every subcommand behind them has
     a green bats run against the `probatio` binary.

**sbt plugin design (the "how"):**
- `probatioVersion := "14.0.0"` (pins the release).
- `probatioInstall` resolves the platform binary, in order: (1) download from
  pinned GitHub Release URL + SHA-256 verify into `target/probatio/bin/`
  (offline-cacheable via coursier's HTTP cache); (2) fall back to the resolved
  assembly JAR + emit a `java -jar` launcher script as `bin/probatio` (warns
  once: startup latency active until GraalVM binary available); (3) if a
  `GraalVMHome` setting is provided, native-image locally (dev/endoskeleton
  only — consumer machines never build from source by default).
- `probatioSpecLint` etc. are `taskKey[Unit]`s that `Process(binary +: args).!`
  and map exit 0→success, 1→`sys.error(findings)` (task failure with the lint
  output in the message), 2→`sys.error(undetermined-reason)` with a distinct
  message so CI logs never conflate the two.
- `probatioLedgerAppend` deliberately exposes only `append`: the CLI is
  append-only by construction (no `update`/`delete` subcommand exists in the
  binary, same as today's script — but now enforced by the parser, since a
  subcommand that doesn't exist is a MainArgs parse error with the subcommand
  name in the error).
- Hook-wire settings: `probatioGateShim` writes the shim; the pi adapter /
  claude settings / devin hooks remain pointing at `hooks/gate.sh`, now a
  3-line shim — adapters untouched (V3 spike required for pi only).

---

## 4. Semantics that must not change (the porting invariants)

This change is a **port, not a redesign**. The following are invariant and
are stated here so the openspec proposal can make "preservation of X" its
first-class requirement set:

1. **Three-way exit codes.** 0 = ran, nothing wrong; 1 = ran, found something
   (a finding); 2 = could not determine († includes unreadable ledger, unknown
   record version, spec-lint non-lint failure, missing prerequisites). Output
   on 2 MUST still be emitted on stdout so unconditional readers see the
   reason, matching `chain-state.sh`'s documented behavior.
2. **Append-only ledger.** No update/delete/rewrite/edit subcommand. The
   current script enforces this *before* argument parsing by name-denylisting;
   the CLI enforces it by the subcommand not existing. Both equivalent; the
   binary is strictly stronger (unparseable vs. denylisted).
3. **Undetermined is never collapsed into "wrong".** The treachery the whole
   schema exists to avert: "a corrupt ledger reads as clean" is the defect
   class this change exists to remove.
4. **spec-lint CONTEXT facts and F1–F10 semantics, including the numbered
   conditional checks.** Judgment/applicability split preserved: the script
   decides applicability of the conditional rules only — and states it for
   *both* polarities ("check 17 ALTITUDE **APPLIES** …" / "…is N/A (attested
   by this script, not assumed)"), citing the spec-lint artifact's numbered
   judgment checks (3, 6, 17 ALTITUDE, 18 CONCURRENCY — including the
   detected deterministic test kit line). Where a check is marked APPLIES,
   recording "N/A" for it is a finding, and the port must keep that
   machine-attested contract exact.

   4a. **INSTRUCTION DRIFT detection.** The banner compares the schema
   version (read from `schema.yaml`) against the `generatedBy:
   probatio-schema/<N>` stamp (formerly `verified-scala3-schema/<N>`; the
   rename is part of R-V2) in every installed skill copy across the six
   searched roots (repo `.agents/.claude/.pi`; `$HOME`
   `.agents/.claude/.zcode`), prints drift warnings with the re-install
   instruction, and prints an explicit "no skill installed" line when none of
   the roots match. Silent drift is a defect class; silence about *checking*
   for drift is the same class.

   4b. **The banner is assembled from live reads, not remembered state.**
   Two parts: the short invariant block (verbatim-match text) and the
   session-context block (context facts + workflow position + a live
   chain-state section naming unresolved requirements per active change),
   closed by the `gate checks` tool list and the "READ FROM DISK …
   facts, not recollection" trailer. Suppression fingerprints the assembled
   facts per session — an unchanged payload injects nothing; a changed one
   re-injects in full.
5. **The gate's blocking asymmetry.** `--event session-start`,
   `prompt-submit` and `post-edit` exit 0 unconditionally. The `tool-call`
   tier carries exactly two locks, both preserved: the oracle-ordering lock
   (production edit refused during the oracle phase) and the human-grant lock
   (spec N+1 non-production writes refused while spec N's checkpoint lacks an
   observed granting prompt). The `completion` tier refuses at most once per
   turn (bounded refusal; `stop_hook_active` or the session marker) and
   exists only where the harness has a blocking completion event — on pi it
   does not, and the pre-execution tier is the only enforcement surface. A
   port that silently universalizes or silently drops any of these has
   changed the schema, not ported it.
6. **Hook payload shapes** — `hookSpecificOutput.additionalContext`,
   decision/payload JSON consumed by the three adapters — byte-stable. Diffs
   in whitespace acceptable to adapters' parsers only if adapters don't
   string-match (devin does not; claude does not; pi adapter does not — V3).
7. **Baseline/baseline-SHA semantics, opened-on-change scoping, and the W1–W7
   warning-only output** — all preserved.

---

## 5. Requirements

These are written in a style directly liftable into the change's
`specs/*/spec.md` files. Each is tagged with its enforcement artifact for the
later Proof-Obligations row.

### Capability `cli-protocol` — the public protocol

**R-P1. The CLI SHALL expose one subcommand per predecessor script.**
`gate`, `spec-lint`, `chain-state`, `ledger`, `checkpoint`,
`registry-check`, `reconcile`, `scan`, `removal-audit`, `danger-scan`,
`impact-scan`, `metals` (with `start`/`stop`/`call` sub-subcommands,
replacing the two-script split), `concept-scanner`, `graph` (port of
`openspec-graph.py` subcommands), `install-skills` / `install-hooks`.
[Enforcement: CLI surface snapshot test]

**R-P2. Every subcommand SHALL implement the three-way exit protocol.**
0/1/2 as in §4.1; a subcommand without the possibility of an "undetermined"
outcome SHALL still document that explicitly in `--help`.
[Enforcement: bats oracle, unchanged]

**R-P3. Stdout payloads SHALL be byte-compatible with the current contract
files.** For each of the three `.jq` contracts, the CLI output of the ported
tool SHALL be accepted by the `.jq` contract, and the current scripts' output
SHALL be accepted by the ported Scala validator.
[Enforcement: conformance property test §5 capability `conformance`]

**R-P4. Arg parsing errors SHALL name the missing/invalid flag.**
Unknown subcommand, missing required value, invalid enum → single-line error
on stderr, exit 1, with the offending token in the message.
[Enforcement: `arg-parse.bats` extension of existing oracle]

**R-P5. `--help` SHALL list every flag with its default.**
The current scripts document flags only in their headers; the CLI exposes
them at runtime so a consumer never opens the source to discover the
interface.

**R-P6. Multicall dispatch SHALL work both by argv(1) and by argv(0).**
`probatio gate …` and a symlink named `gate`→`probatio` are equivalent; the
shim uses the former, future install layouts the latter.

### Capability `probatio-core` — the ported logic

**R-C1. `LedgerRecord` SHALL be an immutable product type (package
`org.sinemenda.probatio`), and validation of every contract clause SHALL be
a total function returning a disjoint sum type.** All 12 clauses of
`ledger-record-contract.jq` appear as `Either[ContractViolation, LedgerRecord]`
cases; the closed `ring` domain (R0–R9, manual) is a sealed enum.

**R-C2. The ledger SHALL be append-only at the type level.** The
`Ledger` module exposes `read`, `append`, and `validate`; no `update`,
`delete`, or `rewrite` function is defined.

**R-C3. Chain-state computation SHALL be referentially transparent given
inputs.** `(SpecLintReport, Ledger, Requirements, Baseline)` → `Either[Undetermined, ChainStateReport]`; reads no files internally. IO lives in
`probatio-cli` only. (Today's script documents "NEVER REIMPLEMENTED" against
spec-lint's own judgment; the port preserves this by taking `SpecLintReport`
as structured input rather than re-executing or re-parsing heuristic tables.)

**R-C4. spec-lint's output SHALL carry per-requirement verdict attribution.**
The unstructured-table re-parsing that today's `chain-state.sh` does to
recover attribution is replaced by a typed `LintReport` produced by spec-lint
and consumed by chain-state as uPickle JSON.
[Decided: this is the **one deliberate behavioral improvement** over §2.2's
feature freeze, because it removes the documented fragility "this script only
re-reads table STRUCTURE" and replaces it with a typed seam. It does not
change what either tool *decides*, only how the decision is *transmitted*.]

**R-C5. The port SHALL NOT change lint F1–F10 verdicts on any fixture.**
The fixtures under `tests/fixtures/` produce identical verdict+warning sets
before and after (property test over `ChainStateReport`/`SpecLintReport`
equality).

**R-C5b. The drift/context/banner engine SHALL be a pure function over declared inputs.**
Given (schema version, skill-install scan results, registry/inventory/profile
presence + counts, detected test kit, active-change list + per-change chain
state), the banner text is a pure rendering — the same inputs byte-identical
output, so the v13 banner's wiring (which root's drift line appears where)
is property-testable against golden fixtures captured from the real scripts.
[Enforcement: golden-fixture conformance suite over the banner contract]

**R-C6. The metals client SHALL frame LSP correctly on partial reads.**
Content-Length parsing over arbitrary buffer boundaries; initialization
handshake with configurable timeout; log to stderr, never stdout.

### Capability `native-packaging` — distribution of binaries

**R-N1. The per-turn `gate` binary SHALL start and emit its payload within
the latency budget.** Warm: <150 ms p50 on linux-x86_64 (measured with
`hyperfine` on the `gate --event prompt-submit` happy path). Cold (after
`sync; echo 3 > /proc/sys/vm/drop_caches` is *not* required for the measurement;
just first-invocation-after-boot on a typical dev machine): <1 s p50.
If the budget is unmet with `-O1` native-image, the change does not ship —
the measurement is a Ring 5 gate, not a post-release discovery.

**R-N2. Native-image SHALL be mandatory for `gate` and optional-with-warning
for the other subcommands.** The once-per-ring tools (`spec-lint`,
`chain-state`, `ledger`, `checkpoint`) may run from JAR where binaries are
unavailable; `gate` runs from native image or the consumer installs a
prebuilt binary. No consumer machine builds native-image unless it opts in.

**R-N3. Every release SHALL include: per-platform binary, assembly JAR,
SHA-256 checksums, SBOM (SPDX JSON), and a `sources` jar.** The plugin's
`probatioInstall` verifies the checksum before executing the binary for the
first time.
[Platforms committed: linux-x86_64, macos-aarch64, macos-x86_64.
Windows-x86_64: JAR fallback only, documented as such — the workflow's
hook tiers are POSIX-shaped today and a native windows port is a separate
change if/when a consumer needs it.]

**R-N4. The release pipeline SHALL be CI-reproducible.** `sbt
probatioCli/nativeImage` in GitHub Actions per platform, matrix on `os`; the
same tag produces byte-identical checksums given the same toolchain versions.

**R-N5. scalameta (concept-scanner) SHALL be spike-verified under native
image (V1) before its port is scheduled.** If the spike fails, the concept
scanner stays on JAR (it is a once-per-change Ring 0 tool, not per-turn) and
the change ships without it — documented as a known non-natived tool rather
than a silent exception.

### Capability `sbt-plugin` — build integration

**R-S1. The plugin SHALL be a valid sbt 1.x AutoPlugin published for
`scala212` / `sbt1` as `org.sinemenda.probatio:sbt-probatio`.** It links no
code from `probatio-core` (which is Scala 3 / TASTy) — communication is argv
+ exit codes + stdout JSON only.

**R-S2. The plugin SHALL forward-declare future sbt 2 support.** Settings and
tasks written sbt-2-ready: no `in GlobalScope` abuse, no deprecated operators,
`Def.task` composition idiomatic.

**R-S3. `probatioInstall` resolution order SHALL be: prebuilt binary →
assembly JAR launcher → (opt-in) local native-image.** Each fallback emits
one distinct sbt log line (`[info]`/`[warn]`), never silent.

**R-S4. Task exit-code mapping SHALL distinguish finding from undetermined.**
Exit 1 → task failure with `"probatio <tool> reported N finding(s): …"`.
Exit 2 → task failure with `"probatio <tool> could not determine: …"`. Both
fail the build, but a CI operator distinguishing them in logs is by design.

**R-S5. `probatioGateShim` SHALL (re)generate the hook shims idempotently.**
The shim is `#!/usr/bin/env bash`, `exec "<resolved-probatio>" gate "$@"` —
three lines. Running `probatioGateShim` twice produces byte-identical output.
Deleting shims is supported (`probatioUninstall`).

**R-S6. The plugin SHALL NOT execute at build load time beyond settings
declaration.** No network resolution, no binary execution during `sbt`
startup — all side effects behind explicit tasks (sbt AutoPlugin hygiene and
the hook-latency lesson applied symmetrically: don't slow consumers' builds).

**R-ARCH1 (cross-cutting; cited from §3.1). The tooling subprojects SHALL
reference zero adk4s code** — no adk4s library module, test utility, or
shared source — enforced by a build-level dependency-lint rule that fails if
any `workflow/*` project's classpath reaches an adk4s module. This is what
keeps "extract to `sinemenda/probatio`" (§3.1 triggers) a `git filter-repo`
operation rather than an untangling. [Enforcement: the lint rule, as a CI
step alongside the other R-ARCH checks]

### Capability `migration-protocol` — how the port is proven safe

**R-M1. The bats oracle SHALL be the porting acceptance suite and SHALL pass
unmodified at every step of the incremental port.** The existing
`*_OVERRIDE` environment seams (`SPEC_LINT_OVERRIDE`, `CHAIN_STATE_OVERRIDE`,
`DANGER_SCAN_OVERRIDE`, `GATE_command` shim-ability) are the swap-points; a
step is complete when the oracle is green with the ported tool substituted at
its seam and everything else still on bash.

**R-M2. Conformance between Scala validators and `.jq` contracts SHALL be a
property test, not a spot check.** Generate records satisfying/ violating
each of the 12 clauses of `ledger-record-contract.jq` (and analogously for
the two report contracts); assert `validate(record).isRight ⟺
jq -e -f contract.jq` exit 0. Corpus: the existing `tests/fixtures/` plus
derived generators. The `.jq` files are deleted as a scheduled step *after*
conformance is green in CI for one full release cycle.

**R-M3. Hook shims SHALL be swapped only after the last subcommand behind
them has passed R-M1.** Order: `ledger` + `chain-state` first (purest, best
oracle coverage); then `checkpoint`, `registry-check`, `reconcile`; then
`spec-lint` (largest, its output feeds chain-state — port *with* the typed
`LintReport` seam of R-C4); then `metals` (highest defect-risk in the
original, smallest blast radius); `gate` last (per-turn latency, stranding
risk documented in its own header).

**R-M4. During migration, the installed schema in this repo SHALL have
exactly one implementation per tool active.** No bash+scala duplicate
installations; the `install-skills.sh`/`install-hooks.sh` step asserts
precisely-one via the shim's resolved target.

**R-M5. The `.pi/skills/openspec-*` documents SHALL be updated atomically
with the tool swap.** Hard-coded script paths (`openspec/schemas/…/scanner/
impact-scan.sh` etc.) are re-expressed as `probatio <tool>` invocations; no
skill references a non-existent path at any commit on main.

### Capability `schema-policy` — prerequisite and version changes

**R-V1. The prerequisite table SHALL be amended in one schema version
increment.** Out: `jq`, `python3`, `shellcheck`, `shfmt` (post-port). In:
`curl`-equivalent (binary download is coursier/Java HTTP), optionally GraalVM
(declared "required only to build from source; prebuilt binaries otherwise").
Retained: `bash` (shims), `git`, `bats` (oracle — migration is incremental),
`openspec` (CLI). Java: removed as a stated *runtime* prerequisite in the
native-binary happy path; retained where JAR fallback is active — the table
states both rows explicitly rather than hiding the fallback.

**R-V2. The schema version bump SHALL be v14, and the schema SHALL be
renamed `verified-scala3` → `probatio` at this bump** (`name: probatio` in
`schema.yaml`, `formerly: verified-scala3` in CHANGELOG). The
`generatedBy: verified-scala3-schema/<N>` skill stamp becomes
`generatedBy: probatio-schema/<N>`; the drift detector reads the new stamp
and treats the old stamp as pre-rename (drift by design, with a one-time
migration message). Env var `VERIFIED_SCALA3_HOOKS` becomes `PROBATIO_HOOKS`,
with the old name read as a deprecated alias for one major (warning on
stderr). Cache/state dirs move from `~/.cache/verified-scala3/` to
`~/.cache/probatio/` (auto-migrated on first run). The CHANGELOG SHALL cite
this change by name.

**R-V3. `hooks/README.md`'s "Still excluded: JVM and network" statement
SHALL be rewritten to reflect the new policy** — "JVM: not required at hook
runtime under the default binary install; required for build-from-source and
JAR-fallback. Network: not required at hook runtime; required once per
version per project for binary install."

### Capability `non-goals-guard` — what this change refuses

**R-X1. The port SHALL NOT extend F1–F10 checks, alter verdicts, or add
workflow features.** Any such idea is filed as a separate change. (This
requirement is what keeps R-M1 interpretable: a behavior delta breaks the
oracle in a way indistinguishable from a port bug.)

**R-X2. The port SHALL NOT change the schema for projects consuming
verified-scala3 via copy, beyond the rename in R-V2.** Consumers who don't
use sbt are unaffected except that their `install-hooks.sh` now installs
`probatio` binaries instead of scripts; the hook payload their harness sees
is unchanged.

**R-X3. scalafmt, munit, ScalaCheck, os-lib, uPickle, mainargs are the
allowed dependencies of `probatio-core`/`probatio-cli`; cats/cats-effect are
explicitly excluded** — the workflow's shipped artifacts stay dependency-
minimal, and the AGENTS.md FP mandate applies to the *product* code under
validation, not to the validating tool. (A one-line-scala-style deviation,
recorded here so the openspec proposal can cite it explicitly.)

---

## 6. Risks and mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | GraalVM native-image of uPickle/ujson requires reflection metadata (V1) | Spike at Ring 0 *before* the port starts; budget one week; fallback: JAR for non-gate tools, never for gate — if gate can't natively-image within R-N1's budget, the change doesn't ship (stated in R-N1). |
| R2 | scalameta native-image failure (V1 second spike) | R-N5: concept-scanner exempted to JAR, documented. |
| R3 | Consumer machine with no internet at install | Assembly JAR published to Maven; `probatioInstall` resolves JARs via coursier (offline-capable) |
| R4 | A ported subcommand subtly diverges from the bash original on an uncovered path | R-M1 oracle + R-M2 conformance + the `git diff` of behavior-freeze (R-X1); no subcommand swaps until green at seam |
| R5 | Hook shim breaks a harness adapter (esp. pi, V3) | Shim byte-stability test; pi adapter end-to-end under `pi -e` before merge to main |
| R6 | Binary size / download time | Multicall binary ≈ single 20–40 MB image across all tools; per-version, per-project cached; CI uses the download cache |
| R7 | Schema consumers mid-change on old scripts during the port | The port happens on a branch; consumers consume released schema versions; the bump to v14 is the single visible inflection |
| R8 | Native-image on macos-aarch64 runners (CI cost) | GitHub-hosted M-series runners since 2024; fallback to cross-compiled from macos-x86_64 not attempted (ROSETTA results unreproducible) — only native CI |
| R9 | Agent-facing skills go stale relative to tools | R-M5 atomic updates; a skill-doc lint step (trivial: grep for forbidden `scanner/*.sh` against installed files) wired into CI |

---

## 7. Rollout phases (tasks.md skeleton)

1. **Phase 0 — spikes (V1–V4)** — native-image PoC on uPickle module; gate-
   shaped latency measurement; pi adapter end-to-end verification plan;
   release-host selection. **Gate:** all four verified or the change is
   re-scoped before any porting begins.
2. **Phase 1 — `probatio-core` skeleton + first contracts** — `LedgerRecord`
   + validator + conformance test against `ledger-record-contract.jq`; no
   CLI. Green conformance gate.
3. **Phase 2 — `probatio-cli` with `ledger` and `chain-state`** — substituted
   at `SPEC_LINT_OVERRIDE`/`CHAIN_STATE_OVERRIDE` seams; bats green.
4. **Phase 3 — packaging** — assembly + native-image wired; GitHub Release
   workflow matrix; plugin `probatioInstall` exists and resolves a local build.
5. **Phase 4 — remainder of CLI** — per R-M3 order; bats green at each step.
6. **Phase 5 — shims + policy + rename** — hook shims swapped, prerequisite
   table amended, schema renamed `verified-scala3` → `probatio` at v14,
   CHANGELOG, skills updated, env-var/cache-dir migration.
7. **Phase 6 — contract retirement** — `.jq` files and `openspec-graph.py`
   and `concept-scanner.scala` deleted after one full release cycle of green
   conformance; schema docs updated.
8. **Phase 7 — extraction DECISION (not execution)** — re-evaluate the §3.1
   triggers exactly once, at the first post-v14 checkpoint: if any trigger
   fired, plan the dedicated-repo migration as its own openspec change (the
   `git filter-repo` + publish-coordinate move to `sinemenda/probatio` is a
   small, self-contained change by R-ARCH1); otherwise record the evaluated
   non-event in the progress tracker, with date and mechanism (a recorded
   limitation, not a forgotten one).

---

## 8. Open questions to resolve in the openspec proposal

1. ~~Repo for the FIRST release~~ — **resolved: `sinemenda/probatio`**. The
   org exists; the repo is created at Phase 3. The first published binary
   comes from `sinemenda/probatio` releases (this repo's working tree during
   the migration, extracted per §3.1 Phase 7).
2. Version scheme for the released binaries vs. the schema version — same,
   or independent (a CLI can rev faster than a schema)?
3. Host for binaries: GitHub Releases (default, aligns with devin/claude
   consumer machines) vs. also publishing zip-artifacts to Maven. If both —
   which is authoritative?
4. `sources` jar: publish generated scala-cli-style single-file mirrors for
   perusal, or only GitHub source browsing?
5. Do we sign the artifacts (cosign/GPG), or is SHA-256 + HTTPS sufficient
   for the threat model of these consumers?
6. ~~Plugin's setting namespace~~ — **resolved: `probatio` prefix** (e.g.
   `probatioInstall`, `probatioSpecLint`). Terse, collision-safe, matches the
   binary name. The legacy `vs`/`verifiedScala3` prefixes are not used.
7. Maven groupId for `accordant4s` when it joins `sinemenda` —
   `org.sinemenda.accordant4s` (mirrors probatio's `org.sinemenda.probatio`),
   or a shared `org.sinemenda` umbrella with per-project artifacts?

---

*End of REQUIREMENTS. The openspec change derived from this document should
map §5's capabilities 1:1 to spec files under `openspec/changes/<change>/specs/`,
§7's phases to `tasks.md`, §4's invariants to the proposal's "non-goals and
preservations" section, and §0's spikes to Ring 0/1 tasks.*
