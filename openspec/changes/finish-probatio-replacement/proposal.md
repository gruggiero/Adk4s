# Proposal: Finish replacing verified-scala3 with probatio

## Why

`repair-probatio-cutover` (archived 2026-09-25) achieved its central goal: the
differential comparison now runs two genuinely different implementations, and its
"no file is worse" verdict is corroborated by an independent predecessor control
(predecessor 18 failures, port 17, of 287). All twelve regressions measured on
2026-09-20 are closed. But probatio does not yet replace verified-scala3.

### Measured state (2026-09-25, recorded in `docs/openPoints/probatio-replacement-status-2026-09-25.md`)

**The workflow only runs where a GraalVM native image was built locally.** Every
invocation through the JAR fallback fails before reaching a tool:

```
$ java -jar probatio-cli-assembly-0.1.0-SNAPSHOT.jar gate --event prompt-submit …
unknown subcommand: probatio-cli-assembly-0.1.0-SNAPSHOT.jar      (exit 1)
```

Multicall dispatch reads the program name from the runtime, and under `java -jar` that
is the JAR's filename. Both shipped launchers fall back to `java -jar` — the repository
launcher and the one the sbt plugin installs. In a worktree with the JAR but no native
image, three sampled oracle files passed 11 of 74 tests. That environment is exactly what
a fresh clone, an adopter, Windows (JAR-only in the release matrix) and the CI workflow
provide. A hook exiting 1 is a non-blocking error to the harness, so in production the
gate silently does not run. The fix is known and verified: a launcher that presents the
name `probatio` dispatches correctly. It already exists in one test and never reached the
product.

**Continuous integration has never executed.** The branch has never been pushed where
`verify.yml` runs, and that workflow provisions only the JAR, so its first run will fail.
Its "a regressing change fails the job" obligation was discharged by inference from a
local `bats` exit, not by an observed run. No release has ever been cut.

**Three tests are red at HEAD.** The change archived with all three.

- **Completion-tier parity depends on who runs it.** It fails in every run inside a
  Claude Code session and passes with `CLAUDE_CODE_SESSION_ID` unset. The test gives the
  port its session by flag, but gives the predecessor subprocess a variable that the
  predecessor ranks *below* `CLAUDE_CODE_SESSION_ID` — which the subprocess inherits
  from the invoking shell. Reproduced by hand: with the variable unset, both gates refuse
  with the same message. **This is a test-isolation defect, not a behaviour divergence.**
  It is a class: 19 test files spawn processes and only one handles the variable; 11 of
  the 17 bats files do not unset it.
- **A fixture path broke on archiving.** `DifferentialHarnessSpec` hardcodes the active
  change directory. This is the same defect `feature-freeze-guard-integrity` fixed in a
  different suite: the fix was applied to one test, not to the pattern.
- **The oracle-immutability guard is red and accepted.** The acceptance oracle was edited
  in five of eleven specs (+248 / −88 lines, four files), mostly without a recorded
  sanction. Every seam swap breaks the oracle's source-grepping tests, which then get
  edited to follow the implementation. A guard that stays red and is waved through guards
  nothing.

**Tools the register calls "ported" still run as their predecessors.** Both installers
reached surface parity but were never swapped. `probatio metals start` prints "server
started", exits 0 and launches nothing — a recognised-and-silent tool, which
`cli-entrypoint-contract` forbids. The register classifies by *name on the surface*, not
by *what the live invocation path runs*, so all three pass it.

**The rest:** `registry-check` is registered as blocked on the scalameta spike but uses
only grep, awk and bash; eleven oracle failures shared by both implementations are stale
fixtures (the locks block correctly once a tool name is supplied); six `chain-state`
failures shared by both are not root-caused; four environment variables have no probatio
name, and the gate's own refusal message tells users to set one of them; the schema
directory rename is recorded as deferred; and three specs' mutation scores fell below
their thresholds against a 4,701-line file that holds every subcommand.

### A caveat on the measurements

Every figure above was measured inside a Claude Code session. Both arms of every
comparison saw the same environment, so the comparisons are fair, but absolute counts may
differ on a CI runner — which is itself one of the defects this change fixes.

## What Changes

This change makes probatio run anywhere, makes its tests hermetic and its oracle
independent, finishes the swaps, ports what is portable, and finishes the rename.

It remains a **port, not a redesign**: `non-goals-guard` R-X1 holds. No F-check is added
and no verdict is altered. Two places could have required an exception and do not: the
completion-tier finding turned out to be a test defect rather than a divergence, and the
absent-tool-name behaviour keeps predecessor parity, gaining only a diagnostic line.

### Affected Capabilities

- `specs/jar-launcher-dispatch/spec.md` — every shipped launcher dispatches correctly
  when only the assembly JAR is present, through an explicit invocation-name channel
  rather than whatever the runtime reports. The JAR path gets a subprocess conformance
  run of its own. A materialised comparison arm is given the built artifact for tests that
  invoke it directly, so its predecessor arm matches an independent control (Finding A1;
  harness discrepancy).
- `specs/hermetic-test-processes/spec.md` — every test that spawns a workflow tool runs it
  in a hermetic environment: harness session variables are cleared unless the test sets
  them. Enforced by one shared helper per test language and a lint against raw process
  construction. The completion-tier parity property passes whoever runs it, and its
  declared cover minimums are enforced (Finding B1).
- `specs/archive-safe-fixtures/spec.md` — tests locate change artifacts through one
  archive-aware resolver, and a lint rejects a literal active-change path (Finding B2).
- `specs/oracle-independence/spec.md` — the oracle's source-grepping tests move to a
  separately named implementation-shape suite, leaving the acceptance oracle purely
  behavioural. Every oracle modification cites the requirement that sanctions it, and the
  immutability guard passes exactly when every modification since the baseline is
  sanctioned (Findings B3, G2).
- `specs/oracle-fixture-repair/spec.md` — the eleven stale lock fixtures supply a tool
  name; the six `chain-state` failures are root-caused and resolved; an absent tool name
  keeps predecessor parity but becomes visible in diagnostics; the Devin payload's
  tool-name field is confirmed against its documentation (Finding G1).
- `specs/delivery-verified/spec.md` — `verify.yml` is observed to run, and observed to
  fail on a deliberately regressing branch; a release candidate is built and validated by
  the release check locally (Findings A2, A3).
- `specs/entrypoint-split/spec.md` — the 4,701-line entrypoints file is split one file
  per subcommand, preserving behaviour, so each later spec's mutation score measures its
  own code (Finding H).
- `specs/installer-swap/spec.md` — the two installers become comparison seams and are
  swapped under the harness (Finding C1).
- `specs/surface-honesty/spec.md` — the unimplemented `metals` operations leave the tool
  surface and join the register; the python graph tool becomes a predecessor revert target
  and the tutorial documents `probatio graph`; the register classifies a tool as ported
  only when its live invocation path reaches the port (Findings C2–C4).
- `specs/registry-check-port/spec.md` — the concept-registry verifier is ported and
  swapped, its blocker having been misassigned. The scalameta spike is run and its result
  recorded, which re-establishes the concept scanner's blocker; the concept scanner itself
  is not ported (Finding D).
- `specs/legacy-name-retirement/spec.md` — every environment variable gains its probatio
  name, keeping the legacy alias for the declared window; user-facing messages name the
  new variables; the gate state directory and the pi adapter take probatio names, with the
  state directory migrated on first use (Findings E2–E4).
- `specs/schema-directory-rename/spec.md` — the schema directory takes its probatio name,
  and the previous name stays resolvable so all 19 changes that pin it keep resolving; the
  105 tracked references move with it; the recorded deferral is discharged (Finding E1).

### Out of Scope

- **Retiring jq, python3, shellcheck and shfmt as prerequisites.** Both installers probe
  for them, the port correctly so, because it was ported at parity. Changing the probed
  set changes a verdict, so it needs its own change outside the feature freeze. It also
  depends on this change's oracle-independence decision (the behavioural oracle is still
  bash and jq) and on the remaining ports. Recorded as the next change's first item.
- **Porting `scan.sh` + `concept-scanner.scala`, `impact-scan.sh`, `removal-audit.sh`,
  `metals-call.sh`, `metals-start.sh`.** They stay registered. This change runs the
  scalameta spike — the concept scanner's blocker — and records its outcome, but does not
  port the scanner.
- **Cutting a release.** Pushing a version tag publishes artifacts and is the
  maintainer's decision. `delivery-verified` builds and validates a release candidate
  locally.
- **Retiring the seven `.predecessor.bak` files.** Their rule requires a full change
  cycle held green *after* this change, CI included. That belongs to the next change.
- Any new F-check, verdict change or workflow feature (`non-goals-guard` R-X1).

## Approach

**Make it run anywhere, make the tests trustworthy, then finish the swaps, then rename.**

1. **Fix the launchers first.** Until the JAR path dispatches, every environment except
   one developer machine is broken, including the CI that is meant to guard the rest of
   this change.
2. **Make the tests hermetic and archive-safe.** Both are test-infrastructure defects
   that make a result depend on who ran it or on where the change directory lives.
   Nothing measured afterwards is trustworthy until they are fixed.
3. **Make the oracle independent, then repair its fixtures.** Fixing the stale fixtures
   means editing the oracle, which must go through the sanction mechanism — so the
   mechanism comes first.
4. **Observe CI.** Once the suites can be green, run `verify.yml` for real: once at a
   known-good commit, once on a deliberately regressing branch. This needs the branch
   pushed where the workflow runs, which the maintainer authorises at apply time.
5. **Split the entrypoints before touching them again.** The remaining specs all change
   entrypoints. Splitting first makes each one's mutation score meaningful and each diff
   reviewable.
6. **Finish the swaps and the surface.** Installers, the honest surface, the registry
   verifier.
7. **Rename last.** The environment variables and state directory, then the schema
   directory. The directory rename touches 105 files and would conflict with everything
   else if done earlier.

## Correctness Risk Level

**Risk**: **high** — this change alters the launch path of every tool, including the
blocking gate; edits the acceptance oracle itself, the instrument that decides every
other spec's exit criterion; refactors the file that holds every subcommand; and moves the
schema directory that 19 recorded changes resolve by name. The failure modes are the ones
this migration has already shipped: a tool that silently does not run, a test that passes
for the wrong reason, a guard that is red and waved through, and a record that stops
resolving.

## Verification Strategy

- [x] Ring 0: Compilation — `probatioScalacOptions` (`-Werror`, deprecation and feature
      escalation, `-Wsafe-init`) plus repo-wide exhaustiveness escalation on every
      probatio module. The entrypoint split and the seam and surface changes are forcing
      functions: every match over a changed enumeration must be revisited.
- [x] Ring 1: Lint — Scalafix DisableSyntax and `NoIOInProbatioCore`, WartRemover, the
      dangerous-pattern scan on the diff, and shellcheck on every changed script. Two new
      lints: no raw process construction in tests (hermetic), and no literal active-change
      path in tests (archive-safe).
- [x] Ring 2: Architecture — `probatioDependencyLint`; R-ARCH1 (no cats, cats-effect,
      fs2, llm4s, workflows4s, adk4s or scalacheck on any `workflow/*` classpath). The
      entrypoint split must not move decision logic into the adapter or I/O into the core.
- [x] Ring 3: Property-based tests — MANDATORY. munit + Hedgehog 0.13.1. The bats
      differential oracle is the acceptance suite, measured against the genuine
      predecessor control, and additionally in a JAR-only environment.
      **CONCURRENCY note** (check 18 applies): this change spawns processes throughout —
      launchers, two acceptance suites per comparison, CI jobs. The detected kit
      (`TestControl`) is unreachable, since R-ARCH1 bans cats-effect from `workflow/*`.
      Determinism comes from recorded process outcomes, an injected clock seam, and — new
      in this change — a **hermetic process environment**: a spawned tool sees only the
      variables its test declares. That last one is the direct remedy for the
      completion-tier finding. It is a stated substitution, not a waiver.
      **Coverage note:** Hedgehog cover minimums must fail the property when missed.
      Whether that is enforced on passing runs is to be established in
      `hermetic-test-processes`, not assumed.
- [x] Ring 4: Wire/persistence compatibility — REQUIRED. (a) The schema directory rename
      must keep all 19 recorded changes resolving; each is checked, and none is assumed.
      (b) The gate state directory migration must carry existing state, and must be
      idempotent and lossless. (c) The oracle-sanction record is a new persisted format
      and must round-trip. (d) The three `.jq` contracts and the graph export must still
      conform after the entrypoint split. (e) The registry verifier's output must agree
      with the predecessor's on the repository's own registry.
- [x] Ring 5: Mutation testing — Stryker4s 0.21.0. Retarget `mutate` per spec. `break = 0`,
      so a Ring 5 claim must **read the score**. From `entrypoint-split` onward, each spec
      is scored against its own subcommand file rather than all 4,701 lines. Thresholds:
      90% core decision logic, 80% adapters. **A score below threshold is not
      dispositioned as "in-diff survivors justified"** — it is either raised, or the spec's
      checkpoint records it as a failed ring. Stryker covers main sources only; the
      migration and guard test packages use the move-to-main-and-back procedure.
- [x] Ring 6: Formal verification — APPLIES. Invocation-name resolution (a JAR invocation
      through a launcher resolves to the multicall name, and resolution is total) extends
      `DispatchKernel`. The sanctioned-modification decision (the guard passes iff every
      modification is sanctioned) is a set-inclusion law over finite sets, added to the
      cutover kernel. Stainless 0.9.9.3, `probatio-verified` pinned to Scala 3.7.2,
      invoked directly (the `ring6` alias is broken under sbt 1.12). Specs with no
      decision at their centre state their skip.
- [ ] Ring 7: Model checking — not applicable. No distributed or event-ordering invariant;
      the append-only record discipline is covered by Ring 6.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY, fresh context, per spec,
      before Rings 5 and 6. **Standing instruction:** check each requirement against the
      shipped artifact **in a JAR-only environment and with harness session variables
      set**, not only on the developer's machine. Both defects this change fixes survived
      every earlier review because every review ran where a native image existed and a
      session variable was present.
- [ ] Ring 9: Telemetry — no telemetry stack on `workflow/*`.

## Typed Contract Decision

**Per-spec classification**:

| Spec | Typed contract | Justification |
|------|----------------|---------------|
| `specs/jar-launcher-dispatch/spec.md` | full | changes the invocation-name algebra and both launchers' contract |
| `specs/hermetic-test-processes/spec.md` | full | introduces the hermetic-environment type every process-spawning test must use |
| `specs/archive-safe-fixtures/spec.md` | minimal | a resolver and a lint; no production type changes |
| `specs/oracle-independence/spec.md` | full | new persisted sanction record; splits the oracle into two suite kinds; changes the guard's decision |
| `specs/oracle-fixture-repair/spec.md` | minimal | fixture edits through the sanction mechanism, plus one diagnostic line |
| `specs/delivery-verified/spec.md` | waiver — **pending human approval** | CI and release-candidate validation; test-and-configuration only, no production code. A waiver needs explicit approval; until given, this spec takes a minimal contract |
| `specs/entrypoint-split/spec.md` | minimal | behaviour-preserving refactor; signatures of the moved objects only |
| `specs/installer-swap/spec.md` | minimal | seam wiring and shim swap; no new domain type |
| `specs/surface-honesty/spec.md` | full | `Subcommand` loses a case; the register's classification changes to live-path |
| `specs/registry-check-port/spec.md` | full | new registry-verifier engine and output format |
| `specs/legacy-name-retirement/spec.md` | full | new alias table; state-directory migration |
| `specs/schema-directory-rename/spec.md` | full | schema resolution changes; the persisted pins of 19 recorded changes are a compatibility surface |

## Existing Concepts to Reuse

Verified against `openspec/concept-inventory.md` (404 typed rows) and `openspec/concepts/`
(38 concepts) during drafting.

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| Strangler migration protocol | behavioural concept | `openspec/concepts/strangler-migration-protocol.md` | reuse — the installer and registry-verifier swaps follow its Swap and Gate actions |
| Conformance property-test contract | behavioural concept | `openspec/concepts/conformance-property-test-contract.md` | reuse — the registry verifier's agreement with the predecessor |
| `InvocationName` / `MulticallDispatch` / `ProgramArgs` | opaque type / object / opaque type | `probatio.cli` | **modify** — the program name gains an explicit launcher-declared source |
| `Subcommand` / `HelpRegistry` / `CliError` | enum / object / class | `probatio.cli` | **modify** — `Subcommand` loses `Metals`; gains nothing |
| `ShimGenerator` / `InstallResolver` / `ShimTargetScope` / `BinaryResolution` | objects / enum | `probatio.plugin`, `probatio.packaging` | **modify** — the installed launcher uses the explicit name channel |
| `ReleaseCheck` | object | `probatio.packaging` | reuse — release-candidate validation |
| `ArmTree` / `ArmDivergence` / `SeamResolution` / `DifferentialHarness` / `CutoverGate` | case class / enum / case class / objects | `probatio.migration` (test-only) | **modify** — arms provision the built artifact; `ToolId` gains installer and registry-verifier seams |
| `ToolId` / `SwapOrder` / `SeamConfiguration` | enums / case class | `probatio.migration` (test-only) | **modify** — new seams |
| `OracleImmutabilityResult` / `FeatureFreezeViolation` / `KnownCheckId` / `FixtureCorpus` / `CorpusResolution` | enums / case classes | `probatio.guard` (test-only) | **modify** — immutability judged against the sanction record; the corpus resolver generalises to all fixtures |
| `UnportedTool` / `PortBlocker` / `ToolSurfaceClassification` / `UnportedToolRegister` | case class / enums / object | `probatio.core` | **modify** — classification by live path; new register entries |
| `TraceabilityGraph` / `GraphNode` (probatio) | case class / sealed trait | `probatio.core` | reuse — `probatio graph` documented as the successor |
| `EventDispatch` / `WitnessVerdict` / `PrePassOutcome` | enums | `probatio.core` | reuse as shipped |
| `InstallTarget` / `InstallMode` / `PrerequisiteProbe` | enums / case class | `probatio.core` | reuse — the ported installers, now swapped |
| `SchemaPolicy` / `CacheMigration` / `RenameDeferral` | objects / case class | `probatio.core`, `probatio.cli` | **modify** — alias table extended; state-directory migration follows the cache migration's pattern; the recorded deferral is discharged |
| `GateStateDir` | case class | `probatio.cli` | **modify** — renamed directory with first-use migration |
| `MetalsClient.MetalsSession` / `.MetalsError` | case class / sealed trait | `probatio.core` | **retire from the surface** — the model stays, the subcommand goes (the object itself has no inventory row; its nested types do) |
| `DispatchKernel` / `CutoverKernel` | Stainless objects | `probatio.verified` | **extend** for Ring 6 |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `InvocationSource` | enum | Where the program name came from — a native executable, a launcher that declared it, or a JAR fallback — so a JAR filename can never be read as a subcommand |
| `HermeticEnv` | final case class, private constructor | The environment a spawned tool receives in a test: a fixed base plus only what the test declares. Constructible only through the shared helper |
| `ChangeLocation` | enum (`Active(dir)`, `Archived(dir, date)`, `Absent(searched)`) | The archive-aware resolution of a change name; the only way tests locate change artifacts |
| `OracleTestKind` | enum (`Behavioural`, `Structural`) | Which suite a test belongs to; only behavioural tests are in the immutable oracle |
| `OracleSanction` | final case class (file, commit, requirement) | A persisted record that one oracle modification was required by a named spec requirement |
| `SanctionVerdict` | enum (`AllSanctioned`, `Unsanctioned(modifications)`) | The immutability guard's decision against the sanction record |
| `LiveRoute` | enum (`Shim(subcommand)`, `Predecessor(path)`) | What a tool's live invocation path actually reaches; the register classifies by this, not by surface name |
| `RegistryRow` / `BindingVerdict` | case class / enum (`Verified`, `Weak`, `Stale`) | The ported registry verifier's model and per-row outcome |
| `LegacyAlias` | final case class (legacy, current, window) | One legacy environment-variable name and the version at which its alias closes |
| `SchemaAlias` | final case class (previous, current) | The retained resolvability of the previous schema directory name |

## Risks and Mitigations

| Risk | Detection | Mitigation |
|---|---|---|
| The launcher fix works on Linux and fails on Windows, where the JAR is the only path and symlinks are awkward | A JAR-only conformance run; Ring 8 in a JAR-only environment | The name reaches the process through an explicit channel (a declared system property), not a filesystem trick. The symlink approach that works in one test today is the evidence, not the design. |
| Making tests hermetic turns currently-green tests red — tests that pass only because of an inherited variable | Run the full suites before and after, both inside and outside a harness session | Expected, and exactly what the spec is for. Each newly red test is triaged as a real finding or a fixture fix, and recorded. |
| The sanction record becomes a rubber stamp — every oracle edit simply gets a sanction added | Ring 8 reviews every sanction against the requirement it cites | A sanction must cite a requirement that *names the oracle change*. A requirement that doesn't mention the test is not a sanction. The first user is this change's own fixture repair. |
| The six `chain-state` failures turn out to be a shared implementation defect, not a stale fixture | Root-cause first, in `oracle-fixture-repair` Step 1 | If fixing them would change a verdict, the spec records the finding and stops; resolving it becomes a scoped R-X1 exception rather than a quiet fix. |
| Running CI requires pushing the branch — an outward action | The spec names the step | The push happens only with the maintainer's explicit authorisation at apply time. |
| The entrypoint split changes behaviour it was meant to preserve | The full suites, the differential and the subprocess conformance runs before and after, plus the parity properties | The split is mechanical: one object per file, no edits inside moved code. Ring 8 compares the moved code byte-for-byte against its origin. |
| The directory rename breaks resolution of archived changes | Ring 4 resolves all 19 pinned changes after the move | The previous name stays resolvable. How the workflow tool resolves a schema is **MUST-CONFIRM** against its own behaviour — tested, not assumed — before the directory moves. |
| Removing `metals` from the surface breaks an agent instruction that invokes it | The unported register's citation check | The register's citations list every instruction that references the tool; each is updated in the same commit. |
