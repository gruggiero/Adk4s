# Proposal: Port the verified-scala3 scanner to probatio

## Why

The verified-scala3 schema's gate/scanner tooling is polyglot across four
runtimes today — bash, jq, python3, and JVM via scala-cli — yet its
prerequisite table declares only bash/git/jq/python3, and the no-JVM rule is
de facto broken by `scanner/concept-scanner.scala`. The discipline is real
(append-only ledger; three-way 0/1/2 exit codes; `.jq` contracts as a single
executable statement of record formats) but every check of it is enforced by
hand-rolled shell convention rather than by a compiler.

The scripts carry their own incident history in comments — each a defect class
the proposed replacement removes by construction:

- `ledger.sh`: `shift 2` doesn't shift with one arg left → infinite loop
  parsing `--baseline`. Removed by MainArgs typed parsing (missing value =
  named parse error, by construction).
- `chain-state.sh`: `die_undetermined` called before its variables were
  declared → `set -u` crash; "undetermined" surfaced as an exit-1 shell crash.
  Removed by constructor-initialized immutable values; no
  declaration-before-use.
- Every script: three-way 0/1/2 semantics hand-rolled and summarizable only in
  comments. Removed by a central sealed `Outcome` trait with the three cases as
  data.
- `gate.sh` v12 supersession note: hand-rolled JSON escaping in sed/awk "were
  where the defects lived". Removed by uPickle/ujson emit.
- `metals-call.sh`: LSP Content-Length framing in shell. Removed by byte
  streams framed by a library.
- Skill installs carry `generatedBy: verified-scala3-schema/<N>` and for years
  nothing read it. Removed by making the drift scan a data-driven check.
- `chain-state.sh` re-reads spec-lint's *table structure* to attribute
  verdicts because "spec-lint's own output has no such attribution". Removed
  by a typed `LintReport` AST consumed by both, once.

Native packaging has been made mandatory by the same owner decision that
makes the prerequisite table changeable, which removes the sole remaining
argument against a JVM implementation (per-turn hook latency). Full defect
evidence and measured line counts are in
`docs/verified-scala3-scanner-migration-REQUIREMENTS.md` §1.1–§1.2.

## What Changes

This is a **port, not a redesign**. The workflow semantics do not change —
only the tools' implementation language. The bash/jq/python3/scala-cli
substratum is replaced by `probatio` — a versioned Scala 3 artifact under
`org.sinemenda.probatio`, distributed as GraalVM native binaries, integrated
into sbt via a thin AutoPlugin. The schema itself is renamed
`verified-scala3` → `probatio` at the v14 bump (one-major compat aliases).

### Affected Capabilities

The requirements doc §5 defines seven capabilities, mapped 1:1 to spec files:

- `specs/cli-protocol/spec.md` — the public protocol: one subcommand per
  predecessor script, three-way exit codes, byte-compatible stdout payloads,
  arg-parse error attribution, `--help`, multicall dispatch (R-P1…R-P6).
- `specs/probatio-core/spec.md` — the ported logic: `LedgerRecord`/
  `ChainStateReport`/`GatePayload` ADTs, the three `.jq` contracts as
  validators, spec-lint F1–F10 + CONTEXT facts, chain-state verdict logic,
  append-only ledger at the type level, referentially-transparent chain-state,
  typed `LintReport` seam, drift/banner engine as a pure function, metals
  JSON-RPC client (R-C1…R-C6, R-C5b).
- `specs/native-packaging/spec.md` — distribution: per-turn `gate` latency
  budget, mandatory native-image for `gate` / optional-with-warning for
  others, per-platform release artifacts (binary + JAR + SHA-256 + SBOM +
  sources), CI-reproducible release pipeline, scalameta native-image spike
  (R-N1…R-N5).
- `specs/sbt-plugin/spec.md` — build integration: sbt 1.x AutoPlugin
  (`org.sinemenda.probatio:sbt-probatio_2.12`), links no `probatio-core` code,
  `probatioInstall` resolution order, task exit-code mapping distinguishing
  finding from undetermined, idempotent `probatioGateShim`, no load-time side
  effects (R-S1…R-S6).
- `specs/migration-protocol/spec.md` — how the port is proven safe: the bats
  oracle is the acceptance suite and passes unmodified at every step,
  conformance between Scala validators and `.jq` contracts is a property test,
  shim swap order, exactly-one implementation per tool during migration,
  atomic `.pi/skills/openspec-*` updates (R-M1…R-M5).
- `specs/schema-policy/spec.md` — prerequisite table amendment and version
  bump: jq/python3/shellcheck/shfmt retired post-port, java removed as a
  runtime prerequisite (native happy path), v14 bump with rename
  `verified-scala3` → `probatio`, `generatedBy` stamp rename, env-var +
  cache-dir migration with one-major deprecated aliases, `hooks/README.md`
  policy rewrite (R-V1…R-V3).
- `specs/non-goals-guard/spec.md` — what this change refuses: no F1–F10
  extensions, no verdict alterations, no workflow features; no consumer
  schema changes beyond the R-V2 rename; the allowed-dependency set for
  probatio-core/cli (R-X1…R-X3). This spec is the feature-freeze contract that
  keeps R-M1 interpretable.
- `specs/provenance-validation/spec.md` — closes a gap discovered during the
  port: the ported `Validator` checks only the 12 required-field clauses of
  the ledger record contract, but the jq contract has 15 enforcement clauses.
  The 3 omitted clauses are: optional-field type checks (sha256, digest,
  wallTime), observer provenance (source must be "ambient"), and session
  provenance (adversarial-review ring rows MUST carry session). This spec
  extends the validator to 15 clauses, lifts the `ContractViolation` cap from
  12 to 15, and requires the CLI `ledger append` entrypoint to validate before
  writing and `Ledger.read` to validate every row on read (R-PV1…R-PV4).

### Out of Scope (and preservations — the porting invariants)

The requirements doc §2.2 and §4 define both what is excluded and what must
not change. These are first-class requirements of this change, not
formalities:

**Excluded (§2.2):**

- Rewriting `openspec` (the npm CLI) itself — different project, different owner.
- Mill/Kotlin-script/gradle adapters for consumers — the sbt plugin is one
  adapter of potentially several; a mill adapter is a separate change.
- Rewriting the schema's spec templates or ring definitions — workflow
  semantics do not change.
- sbt 1.x→2.x migration of this repo's build — independent change; the plugin
  must work on sbt 1.x because this repo *is* sbt 1.x.
- Making the pi adapter call the binary directly in TS — the shim keeps
  adapter code untouched; direct calling is a future optimization.
- Extending the workflow with new checks — feature-freeze during port.

**Preserved invariants (§4) — a port that silently changes any of these has
changed the schema, not ported it:**

1. Three-way exit codes (0 = ran clean; 1 = ran, found something; 2 = could
   not determine). Output on 2 MUST still be emitted on stdout so
   unconditional readers see the reason.
2. Append-only ledger — no update/delete/rewrite/edit subcommand. Enforced
   before by name-denylisting; enforced now by the subcommand not existing
   (unparseable vs. denylisted — strictly stronger).
3. Undetermined is never collapsed into "wrong" — the treachery the whole
   schema exists to avert ("a corrupt ledger reads as clean").
4. spec-lint CONTEXT facts and F1–F10 semantics, including the numbered
   conditional checks (3, 6, 17 ALTITUDE, 18 CONCURRENCY) and the
   judgment/applicability split. Where a check is marked APPLIES, recording
   "N/A" for it is a finding.
   - 4a. INSTRUCTION DRIFT detection — compares schema version from
     `schema.yaml` against `generatedBy: probatio-schema/<N>` stamps across
     six install roots; prints drift warnings + re-install instruction; prints
     an explicit "no skill installed" line when none match. Silent drift is a
     defect class; silence about *checking* for drift is the same class.
   - 4b. The banner is assembled from live reads, not remembered state —
     short invariant block (verbatim-match) + session-context block (context
     facts + workflow position + live chain-state section) + `gate checks`
     tool list + "READ FROM DISK … facts, not recollection" trailer.
5. The gate's blocking asymmetry — `session-start`/`prompt-submit`/`post-edit`
   exit 0 unconditionally; `tool-call` carries exactly two locks
   (oracle-ordering + human-grant); `completion` refuses at most once per turn
   and exists only where the harness has a blocking completion event (on pi it
   does not — pre-execution tier is the only enforcement surface).
6. Hook payload shapes — `hookSpecificOutput.additionalContext`, decision/
   payload JSON consumed by the three adapters — byte-stable.
7. Baseline/baseline-SHA semantics, opened-on-change scoping, W1–W7
   warning-only output — all preserved.

## Approach

Three new sbt subprojects under a `workflow/` prefix, plus a distribution
pipeline and hook shims:

```
probatio tooling (this repo during migration; extraction-ready by construction)
├── workflow/core     probatio-core   (org.sinemenda.probatio:probatio-core_3)
│                     Scala 3 lib — ADTs, validators (ex-jq contracts),
│                     parsers, check logic. NO main, NO args, NO GraalVM config.
│                     Depends on NOTHING adk4s-side (R-ARCH1).
├── workflow/cli      probatio-cli    (org.sinemenda.probatio:probatio-cli_3)
│                     mainargs @main entrypoints; each = one current script;
│                     Outcome[Int] at the boundary maps to exit 0/1/2.
│                     Multicall binary `probatio` (alias `prob`).
├── workflow/plugin   sbt-probatio    (org.sinemenda.probatio:sbt-probatio_2.12)
│                     sbt 1.x AutoPlugin (Scala 2.12). Thin: links NOTHING
│                     from probatio-core; farms everything to the binary.
└── distribution      GitHub Releases (from sinemenda/probatio, tags v/):
                      probatio multicall binary
                      × {linux-x86_64, macos-aarch64, macos-x86_64}
                      + windows-x86_64 (jar-only fallback)
                      + assembly JAR + SHA-256 + SBOM + sources.
```

**Repository strategy is phased, not binary.** During migration the tooling
lives in this repo next to the schema it validates (the bats oracle, jq
contract fixtures, and goldens all live here). The strangler is oracle-driven
and the oracle lives next to the schema: porting one subcommand is ONE PR
touching impl + oracle + schema atomically, because the drift detector
compares installed skill `generatedBy` stamps against `schema.yaml`'s version
— the schema and the tool/banner version are two ends of one invariant that
must bump together. After migration, at a stated trigger (any one of: an
outside consumer version-pins the tools; release cadence diverges; the
native-image matrix becomes measurable per-PR cost), extraction to a
dedicated `sinemenda/probatio` repo reduces to `git filter-repo` + a
publish-config change by R-ARCH1.

**The boundary that makes both options cheap (R-ARCH1):** the tooling
subprojects depend on NOTHING in adk4s — no library module, no test util, no
shared source. A build-level dependency-lint rule (the project's own Ring 2
mechanism) enforces it in CI.

**Three stages of migration, each independently compile-checked:**
1. Contracts ported to `probatio-core` as validators. The `.jq` files remain
   as conformance fixtures; a property test asserts validator ⊨ contract over
   the fixture corpus and vice versa.
2. `probatio-cli` subcommands ported one at a time, earliest-dependency-first,
   each verified against the unchanged bats oracle via the existing
   `*_OVERRIDE` seams (`SPEC_LINT_OVERRIDE`, `CHAIN_STATE_OVERRIDE`,
   `DANGER_SCAN_OVERRIDE`, `GATE_command`).
3. Hook shims replaced last, only after every subcommand behind them has a
   green bats run against the `probatio` binary.

**Data flow, unchanged from today:** hook adapters → shim script (`exec`s the
multicalled binary at a known path) → `probatio gate --event …` →
uPickle-encoded hook JSON on stdout → adapter embeds it. The protocol (argv
surface, 0/1/2 exit codes, JSON payload shapes) is the public contract;
implementations are fungible.

The one deliberate behavioral improvement over the feature freeze (R-C4):
spec-lint's output carries per-requirement verdict attribution via a typed
`LintReport` consumed by chain-state as uPickle JSON, replacing the
documented fragility where `chain-state.sh` "only re-reads table STRUCTURE".
This does not change what either tool *decides*, only how the decision is
*transmitted*.

## Correctness Risk Level

**Risk**: high — this ports the workflow's own correctness-critical tooling
(the gate/scanner that decides whether a spec may proceed) and the defect
class it exists to avert ("a corrupt ledger reads as clean") is exactly the
class a silent port bug would reintroduce. The port has fallback/default
paths at every boundary (exit-2 undetermined, drift detection, banner
assembly, JAR-fallback resolution) and byte-compatibility with existing wire
formats is a hard constraint. High risk + multi-spec complexity ⇒ every spec
keeps two separate human gates (typed-contract and test-oracle); none combine.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types. New
  `probatio-core`/`probatio-cli` subprojects compile under the build's
  existing `-Wconf` exhaustiveness escalation **plus the strict set defined by
  R-CS1…R-CS5** (`-Werror`, deprecation/feature escalation, `-Wvalue:discard`,
  `-Ysafe-init`); `sbt-probatio` compiles under Scala 2.12 for sbt 1.x. Shims
  are `bash -n`-gated. The strict flags are scoped to probatio only (R-CS5) —
  repo-wide `-Werror` is a separate change.
- [x] Ring 1: Lint — Scalafix DisableSyntax + WartRemover + scalafmt on the
  Scala subprojects; `shellcheck` + `shfmt -i 2 -ci` on the shims and any
  retained scripts. Note: R-X3 excludes cats/cats-effect from probatio and
  restricts deps; the AGENTS.md FP mandate applies to *product* code under
  validation, not the validating tool — a recorded deviation.
- [x] Ring 2: Architecture — **applies with a setup task.** R-ARCH1 requires a
  new build-level dependency-lint rule that fails if any `workflow/*`
  project's classpath reaches an adk4s module. Today Ring 2 is advisory-only
  (no custom scalafix arch rules); this change adds the one rule it needs.
  The plugin is forbidden (R-S1) from linking `probatio-core`; the same
  discipline applies in reverse.
- [x] Ring 3: Property-based tests — MANDATORY. R-M2 conformance is a property
  test (validator ⊨ `.jq` contract over the fixture corpus, both directions —
  extended to 15 clauses by R-PV1);
  R-C5 is a property test over `ChainStateReport`/`SpecLintReport` equality
  before/after; R-C5b is a golden-fixture conformance suite over the banner
  contract. **Detected framework is Hedgehog 0.13.1 (NOT ScalaCheck)** per
  `openspec/capability-profile.md`; the requirements doc R-X3 names
  "ScalaCheck" — this discrepancy is resolved in the design phase by using
  Hedgehog to match the detected stack (adding ScalaCheck would contradict the
  profile's "NOT ScalaCheck" consequence). **Concurrency note**: probatio-core
  is pure by construction (R-C3 — IO lives in probatio-cli only); the metals
  client does blocking LSP request/response, not parallel streams; the gate's
  blocking tiers are hook-event semantics, not internal concurrency. No
  deterministic-concurrency scenarios are required; if any emerge in design,
  they use `TestControl` (never wall-clock sleeps).
- [x] Ring 4: Wire/persistence compatibility — **applies.** R-P3 requires
  byte-compatible stdout payloads against the three `.jq` contracts; R-M2
  makes conformance a property test; the ledger is an append-only persisted
  format (round-trip + old-fixture parse); hook payload shapes are byte-stable
  (R-§4.6). The bats oracle is itself the wire-compatibility suite.
- [x] Ring 5: Mutation testing — Stryker4s on changed `probatio-core`/`probatio-cli`
  production logic. Threshold per profile: 90–95% pure domain logic (the
  validators, verdict logic, banner engine), 80–90% adapters (CLI
  entrypoints, metals client). Retarget `stryker4s.conf` `mutate` list per
  spec. No mutation tooling for bash shims — stated impact: shims are 3-line
  `exec` wrappers, judged by shellcheck + the bats oracle instead.
- [x] Ring 6: Formal verification — **applies via the verified-mirror
  pattern.** Pure kernels exist: chain-state verdict logic
  (`(SpecLintReport, Ledger, Requirements, Baseline) →
  Either[Undetermined, ChainStateReport]`, R-C3), the 15-clause ledger
  contract validator (R-C1 for clauses 0–11, R-PV1 for clauses 12–14), and the banner/drift engine (R-C5b — pure
  rendering). These are decision/fold kernels expressible in PureScala at an
  abstraction. Caveats to resolve in design: (a) R-X3 excludes cats from
  probatio, and the `verified` leaf module is pinned to Scala 3.7.2 while
  probatio-core targets 3.8.4 — the mirror lives in the `verified` module
  (or a new probatio-scoped mirror leaf) with a bridge property test binding
  shipped code to the model, per `templates/verified-mirror.md`; (b) the
  mirror models the *decision* (verdict attribution, contract clause
  satisfaction), not the ujson/uPickle wire layer.
- [ ] Ring 7: Model checking — no TLA+/Apalache detected (profile). Skip with
  stated correctness impact: the gate's two-lock blocking asymmetry is
  stateful but single-threaded per session; its invariant is covered by the
  bats oracle + Ring 8, not by model checking.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY (fresh-context
  reviewer; runs BEFORE Rings 5/6/7). For this change it is especially
  load-bearing: the adversarial reviewer must look for silent fallback
  mappings, `case _` defaults, and partial functions that could satisfy the
  bats oracle while violating §4's preservations (e.g. collapsing exit-2 into
  exit-1, dropping a drift root, universalizing the completion tier).
- [ ] Ring 9: Telemetry — no otel4s/Daut detected (profile). Skip with stated
  impact.

**Ring 0/1 spikes (§0 of the requirements doc) — carried as Phase 0 tasks in
`tasks.md`, gated before any porting begins:**

- **V1.** GraalVM native-image builds `probatio-core` + `probatio-cli` without
  hand-maintained reflection configuration. uPickle/ujson/os-lib/mainargs
  believed GraalVM-safe; scalameta (concept-scanner port) is the known risk.
- **V2.** Cold/warm wall-clock latency of the `gate` binary meets the R-N1
  budget on linux-x86_64.
- **V3.** The pi TS adapter invokes `gate.sh` by path; the shim replacement
  must be verified end-to-end under `pi -e`.
- **V4.** Prebuilt-binary download host choice (GitHub Releases vs. Maven zip
  artifacts) and checksum story.

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
| `specs/cli-protocol/spec.md` | Full | New CLI surface (subcommands, multicall dispatch, exit-code mapping, arg-parse error algebra) — public API signature change. |
| `specs/probatio-core/spec.md` | Full | New ADTs (`LedgerRecord`, `ChainStateReport`, `GatePayload`, `LintReport`, `Outcome`), validators (ex-`.jq` contracts), verdict logic, banner/drift engine, metals client — domain types + evaluator logic + persistence/wire. |
| `specs/native-packaging/spec.md` | Full | Release artifact contract (binary + JAR + SHA-256 + SBOM + sources), `probatioInstall` resolution order, latency budget — public API + distribution wiring. |
| `specs/sbt-plugin/spec.md` | Full | New sbt AutoPlugin (settings/tasks namespace), `probatioInstall`/`probatioGateShim`/`probatioUninstall` signatures, exit-code→task-failure mapping — public API signature change. |
| `specs/migration-protocol/spec.md` | Full | The `*_OVERRIDE` seam contract, shim swap order, exactly-one-implementation invariant, conformance property-test contract — messaging/persistence wiring. |
| `specs/schema-policy/spec.md` | Full | Schema rename + v14 bump, `generatedBy` stamp rename, env-var (`VERIFIED_SCALA3_HOOKS`→`PROBATIO_HOOKS`) + cache-dir migration with deprecated aliases — persistence/wire + public API change. |
| `specs/non-goals-guard/spec.md` | Full | The allowed-dependency set (R-X3) and feature-freeze contract (R-X1) are enforceable invariants — encoded as a typed contract the dependency-lint rule and the oracle both consume. |
| `specs/provenance-validation/spec.md` | Full | Extends `ContractViolation` from 12 to 15 variants (error algebra change), introduces `ProvenanceFields` and `ValidatedRecord` types, requires write-time and read-time validation enforcement — public API signature change + persistence/wire. |

## Existing Concepts to Reuse

The probatio tooling subprojects depend on NOTHING adk4s-side (R-ARCH1), so
they reuse no adk4s library types. They do reuse the *behavioral contracts*
defined by the existing scanner, which are themselves the porting source of
truth:

| Concept | Kind | Location | Notes |
|---------|------|----------|-------|
| `ledger-record-contract.jq` | jq contract (15 clauses: 12 required-field + 3 provenance) | `openspec/schemas/verified-scala3/scanner/` | Single statement of the ledger record format; ported to a Scala validator (R-C1 for clauses 0–11, R-PV1 for clauses 12–14) and retained as a conformance fixture (R-M2). |
| `chain-state-report-contract.jq` | jq contract | `openspec/schemas/verified-scala3/scanner/` | Chain-state report format; ported to a validator, retained as fixture. |
| `gate-hookjson-contract.jq` | jq contract | `openspec/schemas/verified-scala3/scanner/` | Hook payload shape; ported to a validator, retained as fixture. |
| bats oracle (18 files, ~6,364 lines) | test suite | `openspec/schemas/verified-scala3/tests/*.bats` | The porting acceptance suite (R-M1); passes unmodified at every step. |
| `*_OVERRIDE` env seams | swap-points | `gate.sh` / oracle helpers | `SPEC_LINT_OVERRIDE`, `CHAIN_STATE_OVERRIDE`, `DANGER_SCAN_OVERRIDE`, `GATE_command` — the strangler seams. |
| `concept-scanner.scala` | scala-cli script | `openspec/schemas/verified-scala3/scanner/` | Scalameta-based semantic concept extraction; ported off scala-cli into probatio-core (R-N5 spike for native-image). |
| `openspec-graph.py` | python3 script | `openspec/schemas/verified-scala3/scanner/` | Transitive traceability/fact extraction for chain-state; ported to probatio-core as `graph` subcommand. |

No `openspec/concept-inventory.md` rows are reused — the inventory is adk4s
domain types and probatio is a leaf-by-construction. The
`concept-inventory` artifact for this change will record the new
`org.sinemenda.probatio` types as they are introduced per spec.

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `Outcome[A]` | sealed enum (`Ran[A]`, `Finding`, `Undetermined`) | The three-way exit protocol as data (§4.1); maps to exit 0/1/2 at the CLI boundary. |
| `LedgerRecord` | immutable case class + `Ring` enum (R0–R9, manual) | The ported ledger record ADT; 12 contract clauses validated by a total function returning `Either[ContractViolation, LedgerRecord]` (R-C1). |
| `Ledger` | module (`read`, `append`, `validate` only) | Append-only at the type level — no `update`/`delete`/`rewrite` (R-C2). |
| `ContractViolation` | sealed trait | Disjoint sum of the 15 clause failures (12 required-field + 3 provenance: optional-field type, observer provenance, session provenance). |
| `ChainStateReport` | case class | Bound/resolved/discharged verdict (R-C3); computed by a pure function over `(SpecLintReport, Ledger, Requirements, Baseline)`. |
| `SpecLintReport` / `LintReport` | typed AST | Per-requirement verdict attribution (R-C4); replaces `chain-state.sh`'s table-structure re-parsing. Consumed by chain-state as uPickle JSON. |
| `GatePayload` | case class | The hook JSON payload (ex-`gate-hookjson-contract.jq`); byte-stable (§4.6). |
| `BannerEngine` | pure function | `(schemaVersion, skillInstallScan, registry/inventory/profile presence, detectedTestKit, activeChanges) → banner text` (R-C5b); byte-identical output for identical inputs. |
| `DriftScan` | pure function | Compares `schema.yaml` version against `generatedBy: probatio-schema/<N>` stamps across six install roots (§4.4a). |
| `MetalsClient` | LSP JSON-RPC client | Content-Length framing over partial reads, init handshake, lifecycle; logs to stderr (R-C6). Replaces `metals-start.sh`/`metals-call.sh`. |
| `ProvenanceFields` | immutable case class | The optional provenance fields from the jq contract (sha256, digest, wallTime, source, session) carried alongside `LedgerRecord` as a companion value (R-PV1). |
| `ValidatedRecord` | immutable case class | A `LedgerRecord` paired with its `ProvenanceFields`, produced by the extended validator when all 15 clauses pass (R-PV1). |
| `probatio` multicall binary | native-image launcher | Dispatches on `argv(1)` or `argv(0)` (R-P6); one subcommand per predecessor script (R-P1). |
| `sbt-probatio` AutoPlugin | sbt 1.x plugin (Scala 2.12) | `probatioInstall`, `probatioSpecLint`, `probatioChainState`, `probatioCheckpoint`, `probatioLedgerAppend`, `probatioGateShim`, `probatioUninstall` (R-S1…R-S6). Links no `probatio-core` code. |
| dependency-lint rule | build-level check | Fails if any `workflow/*` project's classpath reaches an adk4s module (R-ARCH1). |
| `probatioScalacOptions` | build setting (Seq[String]) | The strict flag set defined by R-CS1…R-CS5 below; applied to `probatio-core`/`probatio-cli` only (clean slate, highest risk surface). |

## Compiler Strictness Requirements (preview — bound in `specs/probatio-core/spec.md`)

The defect class this port averts is "a corrupt ledger reads as clean" — i.e.
**silent fallbacks and discarded results**. The probatio subprojects are a
clean slate (no legacy to grandfather, R-ARCH1 isolates them from adk4s), so
this is the moment to apply the strictest scalac set the toolchain supports.
These requirements are recorded here as a forward-declaration; they are
formalized as R-* requirements with `[Enforcement: …]` tags in
`specs/probatio-core/spec.md` (and the CLI-boundary subset in
`specs/cli-protocol/spec.md`) once the `specs` artifact is reached.

**Evidence of current state** (read from `build.sbt` lines 47–60 this session):
`scala3Options` today contains `-deprecation`, `-feature`, `-unchecked`,
`-Xkind-projector:underscores`, `-Wunused:all`, and two targeted escalations
(`-Wconf:name=PatternMatchExhaustivity:e`, `-Wconf:name=MatchCaseUnreachable:e`).
`-Werror` is **absent**; `-deprecation`/`-feature` only *warn*. The `verified`
module (line 335) has a full `scalacOptions` override and would NOT inherit
flags added to `scala3Options` — it is pinned to Scala 3.7.2 for Stainless;
that is deliberate and unaffected by these requirements.

### R-CS1. `-Werror` SHALL be active on `probatio-core` and `probatio-cli`.

The master switch: combined with the existing `-Wunused:all` and exhaustiveness
escalations, every warning becomes fatal. A port using a deprecated API or
leaving an unused import would fail Ring 0, not compile clean.
[Enforcement: `sbt probatio-core/compile` exits non-zero on any warning;
recorded as a Ring 0 gate in `specs/probatio-core/spec.md`]

### R-CS2. `-deprecation` and `-feature` SHALL be escalated to errors.

`-Wconf:cat=deprecation:e` and `-Wconf:cat=Feature:e`. Today both only warn; a
port using a deprecated API would compile clean. R-X3 restricts dependencies
and the schema renames env vars — the deprecated-alias paths
(`VERIFIED_SCALA3_HOOKS` → `PROBATIO_HOOKS`, R-V2) are exactly where a silent
fallback could hide. `-feature` escalation forbids `given Conversion`
shortcuts the port must not reach for.
[Enforcement: Ring 0 compile gate; the deprecated-alias path is additionally
asserted by a Ring 3 property test in `specs/schema-policy/spec.md`]

### R-CS3. `-Wvalue:discard` SHALL be active — discarded non-Unit values are errors.

**Directly targets the silent-fallback defect class.** A validator that
computes `Either[ContractViolation, LedgerRecord]` and drops the result, or a
verdict function that ignores a branch's output, becomes a compile error
rather than a silent skip. This is the single highest-value addition for this
change's risk profile.
[Enforcement: Ring 0 compile gate on `probatio-core`/`probatio-cli`]

### R-CS4. `-Ysafe-init` SHALL be active on `probatio-core` and `probatio-cli`.

Catches unsafe initialization order (Scala 3.5+; the build is on 3.8.4 ✅, so
the flag is available). The requirements doc §1.2 calls out `chain-state.sh`'s
"die_undetermined called before its variables were declared" as a defect the
port removes by construction; `-Ysafe-init` is the compiler enforcement of
that same invariant for the new ADTs with companion validators and the metals
client's lifecycle.
[Enforcement: Ring 0 compile gate]

### R-CS5. The strict flag set SHALL be scoped to probatio, not applied repo-wide.

`-Werror` repo-wide is a separate decision: it would surface every
currently-suppressed warning across 12 adk4s modules and likely break the
build on first application. Bundling a repo-wide `-Werror` flip into the
probatio port would mix a behavior-preserving port with a build-strictness
migration and violate R-X1's feature freeze. The flags apply to
`probatio-core`/`probatio-cli` only; a repo-wide rollout is its own change
once existing warnings are triaged.
[Enforcement: the `probatioScalacOptions` setting is declared on the probatio
subprojects only; a Ring 2 check asserts the adk4s modules' `scalacOptions`
do not gain `-Werror` as a side effect of this change]

### Concrete flag set (for the `specs` artifact to bind)

```scala
// probatio-core / probatio-cli only
scalacOptions ++= scala3Options ++ Seq(
  "-Werror",
  "-Wconf:cat=deprecation:e",
  "-Wconf:cat=Feature:e",
  "-Wvalue:discard",
  "-Ysafe-init"
)
```

### Considered and deferred (recorded so the decision is auditable)

| Flag | Decision | Reason |
|---|---|---|
| `-Wnonunit:unit` | Deferred | Catches `if (cond) () else someValue` shape bugs; lower payoff than `-Wvalue:discard`, occasionally noisy on effectful `IO.unit` returns. Revisit if Ring 8 finds a discarded-value shape R-CS3 misses. |
| `-language:strictEquality` | Deferred (candidate for `probatio-core` only) | Strongest available — would make `LedgerRecord == LedgerRecord` a compile error without `derives CanEqual`. Very strict; may fight llm4s/workflows4s types at boundaries. Decide at design phase; if adopted, scope to `probatio-core` (the pure leaf), not repo-wide. |
| `-Wunused:imports` | Already active | Listed only so it is not re-added; subsumed by `-Wunused:all`. |
| `-Xlint` | Not applicable | Scala 2 only; Scala 3 splits lint into individual `-W` flags (covered above). |
| `-Wextra-implicit` | Not recommended | Low payoff given probatio's minimal dep set (R-X3 excludes cats, so few givens). |
| `-language:unsafeNulls` | Rejected | Relaxes null safety; do not add. |

## Risks and Mitigations

| # | Risk | Mitigation |
|---|------|------------|
| R1 | GraalVM native-image of uPickle/ujson requires reflection metadata (V1) | Spike at Phase 0 *before* porting begins; fallback: JAR for non-gate tools, never for gate — if gate can't natively-image within R-N1's budget, the change doesn't ship (stated in R-N1). |
| R2 | scalameta native-image failure (V1 second spike) | R-N5: concept-scanner exempted to JAR, documented as a known non-natived tool rather than a silent exception. |
| R3 | Consumer machine with no internet at install | Assembly JAR published to Maven; `probatioInstall` resolves JARs via coursier (offline-capable). |
| R4 | A ported subcommand subtly diverges from the bash original on an uncovered path | R-M1 oracle + R-M2 conformance + R-X1 behavior-freeze; no subcommand swaps until green at its seam. Ring 8 adversarial review looks specifically for silent fallback mappings. |
| R5 | Hook shim breaks a harness adapter (esp. pi, V3) | Shim byte-stability test; pi adapter end-to-end under `pi -e` before merge to main. |
| R6 | Binary size / download time | Multicall binary ≈ single 20–40 MB image across all tools; per-version, per-project cached; CI uses the download cache. |
| R7 | Schema consumers mid-change on old scripts during the port | The port happens on a branch; consumers consume released schema versions; the bump to v14 is the single visible inflection. |
| R8 | Native-image on macos-aarch64 runners (CI cost) | GitHub-hosted M-series runners since 2024; only native CI (no ROSETTA cross-compile — unreproducible). |
| R9 | Agent-facing skills go stale relative to tools | R-M5 atomic updates; a skill-doc lint step (grep for forbidden `scanner/*.sh` against installed files) wired into CI. |
| R10 | R-X3 names ScalaCheck but the detected stack is Hedgehog | Resolved in design: use Hedgehog to match `capability-profile.md`; record the deviation from the requirements doc in the design artifact. |
| R11 | Ring 6 mirror vs. R-X3's cats exclusion + Scala 3.7.2 pin | Mirror lives in the `verified` leaf (or a new probatio-scoped mirror leaf pinned to 3.7.2) modeling the *decision* only; bridge property test binds shipped code to the model. |

## Open Questions (from requirements doc §8, to resolve in design/proposal)

1. ~~Repo for the FIRST release~~ — resolved: `sinemenda/probatio` (org exists;
   repo created at Phase 3).
2. Version scheme for released binaries vs. schema version — same, or
   independent (a CLI can rev faster than a schema)?
3. Host for binaries: GitHub Releases (default) vs. also publishing zip
   artifacts to Maven. If both — which is authoritative?
4. `sources` jar: publish generated scala-cli-style single-file mirrors, or
   only GitHub source browsing?
5. Do we sign the artifacts (cosign/GPG), or is SHA-256 + HTTPS sufficient?
6. ~~Plugin's setting namespace~~ — resolved: `probatio` prefix.
7. Maven groupId for `accordant4s` when it joins `sinemenda` —
   `org.sinemenda.accordant4s` or a shared `org.sinemenda` umbrella?
