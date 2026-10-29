# Design: Repair the probatio cutover and finish the replacement

## Package Structure

All work lands in the four `workflow/*` modules plus the Stainless mirror leaf. No adk4s
module is touched.

### Layers

| Layer | Package | May depend on | Must NOT depend on |
|-------|---------|---------------|--------------------|
| Decision core | `org.sinemenda.probatio.core` | stdlib, `ujson` (upickle), `probatio-verified % Test` | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck, **and any file I/O or environment read** |
| CLI adapter | `org.sinemenda.probatio.cli` | `probatio-core`, stdlib, `os-lib`, `ujson`, `mainargs` | the same forbidden set |
| Packaging | `org.sinemenda.probatio.packaging` | stdlib, `os-lib` | the same forbidden set |
| sbt plugin | `org.sinemenda.probatio.plugin` | sbt API, stdlib | `probatio-core` (the plugin links no core code — R-S1) |
| Verified mirror | `org.sinemenda.probatio.verified` / `…probatio.core` under `verified/probatio` | Stainless library, stdlib | everything project-local |
| Migration / guard (test-only) | `org.sinemenda.probatio.migration`, `…guard` | `probatio-core`, `os-lib`, munit, Hedgehog | the same forbidden set |

The two rules Ring 2 enforces, both already wired and both load-bearing for this change:

- **R-ARCH1** (`probatioDependencyLint`): none of cats / cats-effect / fs2 / llm4s /
  workflows4s / adk4s / scalacheck may appear on any `workflow/*` classpath. This is why
  the detected deterministic concurrency kit is unavailable and why the substitution in
  §Effect Boundaries is a design decision rather than an oversight.
- **No-I/O-in-core** (`NoIOInProbatioCore` scalafix rule): the decision core may not read
  files or the environment. The graph port is the main pressure on this rule — it is a
  tool *about* files — and §Decision 2 records how the split is drawn.

### New Packages

None. Every new type lands in an existing package:

| New types | Package | Why here |
|-----------|---------|----------|
| `TraceabilityGraph`, `GraphNode`, `GraphEdge`, `GraphQuery`, `ReachabilityResult`, `UnlinkableRow`, `ConceptRegistryDoc`, `InventoryDoc` | `probatio.core` | pure model + parsers over strings |
| `PrePassOutcome`, `WitnessVerdict`, `EventDispatch` | `probatio.core` | decision types |
| `UnportedTool`, `PortBlocker`, `ToolSurfaceClassification`, `RenameDeferral` | `probatio.core` | pure classifications |
| `InstallTarget`, `InstallMode`, `PrerequisiteProbe`, `PrerequisiteReport` | `probatio.core` | the decisions; the probing is in the adapter |
| `GraphCmd` | `probatio.cli` | an entrypoint, alongside the other ten |
| `ShimTargetScope` | `probatio.plugin` | the plugin owns shim generation |
| `ArmTree`, `ArmDivergence`, `SeamResolution`, `FixtureCorpus`, `CorpusResolution` | `probatio.migration`, `probatio.guard` (test-only) | the harness and the guard are test infrastructure |

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|---|---|---|
| Graph reachability closure (`reaches`, `audit`) | Decides whether a requirement reaches an enforcement artifact — a transitive closure over a finite graph, reducible to a Boolean per requirement | **Yes** — decision at the centre, inputs reduce to node identities and a Boolean. New kernel, the tenth in the mirror. Termination by explicit fuel bounded by the edge count, per the recorded practice for traversals. |
| Chain-state pre-pass boundary (`computeVerdict`) | Decides whether a verdict exists at all, and the monotonicity of its counts | **Yes** — extends the existing chain-state kernel. The fold is already mirrored; this change adds the boundary condition and the count ordering. |
| Completion refusal predicate (`decideCompletion`) | Decides refusal from the evidence rows and the per-turn bound | **Yes** — extends the existing gate kernel, which already mirrors the refusal budget. |
| Event dispatch classification (`classify`) | Total classification of a supplied name into tier or fallback | **Yes** — extends the existing dispatch kernel with totality and fallback fidelity. |
| Arm divergence + worse-file fold | Decides whether the comparison is meaningful and which files regressed | **Yes** — extends the existing cutover kernel. Both are folds over finite lists with exact postconditions. |
| Guard outcome classification (`guardOutcome`) | Three-way outcome from a corpus resolution and a disagreement list | **Yes** — extends the existing spec-lint kernel. |
| Swap authorisation (`authoriseSwap`) | Decides whether a seam may be swapped from a comparison | **Yes** — extends the existing ledger-validator kernel. |
| Markdown parsers (registry, inventory, spec) | Turn text into documents | **No** — string processing with no decision, fold or law at the centre; Stainless models neither the regex engine nor the string algebra usefully. Enforced by Ring 3 round-trip and conservation properties instead (`unlinkable-rows-are-conserved`). |
| Cache/state directory migration | Moves directory contents | **No** — already verified as a kernel; this change supplies a caller, which is an adapter concern and not mirrorable. |
| Installer surface decisions (target, mode, foreign-file detection) | Small closed classifications | **No** — two- and three-variant enumerations with no fold or arithmetic; fully pinned by compile-negative obligations and Ring 3 properties. |
| Shim scope + quotability decision | Which scope, and whether a path is safely quotable | **No** — a character-set membership test; pinned by `generation-refuses-unquotable-targets`. |
| Tool surface classification | Ported vs registered | **No** — a two-variant total function; the type having exactly two cases *is* the proof. |

Seven kernels extended or added; five candidates declined with a stated reason. Silence is
not a verdict, so every candidate appears.

### Effectful Code

| Code | Effect | Boundary discipline |
|------|--------|---------------------|
| `GraphCmd` | reads the registry, the inventory and the active changes | All reading here; the core receives strings and returns a graph |
| `ChainStateCmd` | invokes the pre-pass, invokes the graph, reads the evidence record | Maps each termination to a `PrePassOutcome`; the core never learns a process existed |
| `GateCmd` tiers | reads the state area, the evidence record, the harness payload | Already the established split; the restored refusal adds no new read |
| `InstallSkillsCmd`, `InstallHooksCmd` | probe prerequisites, write agent and harness directories | Mode and target decided in core, applied here |
| Differential harness | materialises two trees, spawns two acceptance suites | Test-only. Determinism: recorded process outcomes; the arms' contents are digested, not timed |
| sbt plugin tasks | generate and install shims | Resolution decided in `InstallResolver`, applied by the task |

**Deterministic concurrency substitution.** The capability profile detects `TestControl` as
the project's deterministic kit. It ships with cats-effect, which R-ARCH1 forbids on the
`workflow/*` classpath, so it is unreachable from every module this change touches. The
substitute — already used by the existing probatio specs — is **recorded process outcomes
plus an injected clock seam**: a scenario supplies the exit status and output a subprocess
*would* produce rather than running one, and any time-dependent decision takes an injected
instant. No wall-clock sleep appears anywhere. This is a stated substitution with
equivalent determinism, not a waiver, and it was verified against the build during this
change's capability-check (which corrected the profile's previously unqualified claim).

## Type Strategy — Invalid-State Prevention

Every invariant this change introduces, placed on the hierarchy:

| Invariant | Tier | Mechanism | Justification |
|-----------|------|-----------|---------------|
| A comparison side that was never materialised cannot be compared | **Impossible** | `ArmTree` private constructor; only the materialising factory returns one | The defect being fixed is a comparison over unmaterialised arms; making the unmaterialised value unrepresentable removes the whole class |
| A divergence verdict cannot be decided from paths | **Impossible** | `ArmDivergence` variants carry `SeamResolution`, which requires a content digest | Two forwarding scripts at different paths hold identical bytes; path equality is the wrong equality and the type refuses to express it |
| A seam cannot be named by a free string | **Impossible** | `SeamConfiguration` is typed by the seam enum | A string seam can name a seam that does not exist, which is how a tool drops out of the comparison |
| A verdict report cannot exist without its producing pre-pass | **Impossible** | `ChainStateReport`'s factory accepts only `PrePassOutcome.Completed` | This is the exact shipped defect: counts assembled from an invocation that did not happen |
| A could-not-determine outcome cannot carry counts | **Impossible** | `ChainStateUndetermined` has no count field | A could-not-determine that carries numbers invites a reader to use them |
| "Could not read the corroboration" is distinct from "no corroboration" | **Impossible** | `WitnessVerdict` has three variants, not two | Collapsing them turns an environment fault into an enforcement verdict, in either direction |
| Event classification cannot fail | **Impossible** | `classify` returns `EventDispatch`, not an optional or either | A failable parse is precisely what produces the error status the port shipped |
| A fallback cannot discard what it fell back from | **Impossible** | `EventDispatch.Injection` requires the supplied name | Silent fallback is how the vocabulary drift went unnoticed |
| A graph cannot hide what it could not parse | **Impossible** | `TraceabilityGraph` requires the unlinkable set | The tool's own documentation names optimistic silence as its worst failure |
| A node kind cannot be a free string | **Impossible** | `GraphNode` is a sealed hierarchy with no string constructor | A mistyped kind silently drops from every query |
| A tool cannot be neither ported nor registered | **Impossible** | `ToolSurfaceClassification` has exactly two variants | The absence of a third case *is* the check |
| A register entry cannot omit its blocker | **Impossible** | `UnportedTool` requires `PortBlocker`; the blocker is a closed enum, not text | An entry with no stated reason is indistinguishable from an oversight |
| An empty fixture corpus cannot be built | **Impossible** | `FixtureCorpus` private constructor; the factory returns a resolution | An empty corpus is exactly how the guard came to pass vacuously |
| A rename deferral cannot omit its reason | **Impossible** | `RenameDeferral` requires reason and blocking coupling | A deferral with no reason is an omission wearing a label |
| An installer cannot default to writing | **Impossible** | `InstallMode` is a required parameter with no default | A write-by-default installer swapped behind an unchanged invocation modifies configuration nobody asked it to touch |
| A shim generation cannot infer its scope | **Impossible** | `ShimTargetScope` is a required parameter | Inferring "this looks absolute" is what produced a committed absolute path |
| A prerequisite report cannot be built from names alone | **Impossible** | `PrerequisiteReport` holds probes, not names | Otherwise checked-and-present is indistinguishable from not-checked |
| A checkpoint entry cannot assert an outcome without evidence | **Impossible** | `RingEvidence` required to construct an outcome-bearing entry | Already the shipped discipline; retained |
| A swap record cannot omit its comparison | **Impossible** | `ShimSwap` gains the comparison outcome as a required field | A swap whose justification is detached cannot be audited or reverted with reason |
| An unreadable install root is not an absent one | **Impossible** | `InstallRootState` has distinct variants | Reporting an unread root as empty turns a read failure into a verdict |
| The record format's fifteen clauses | **Rejected by validator** | The existing closed clause set + executable contract | Tier-justified: the clause set is a wire format owned by an external contract file; a type cannot express "this JSON satisfies clause 9" without reimplementing the contract, and a second copy would be free to disagree |
| Reachability grounds only at artifact nodes | **Rejected by validator** | The audit's classification + Stainless postcondition | Tier-justified: reachability is a property of an edge set, not of a value; the type system cannot express "this graph has a path", so the kernel's `ensuring` clause carries it |

No invariant sits at Risky or Bad. The two validator-tier placements each carry a written
justification, as the ladder requires.

## Refined Type Strategy

The probatio modules do **not** use Iron — the refined-type library is an adk4s
dependency and R-ARCH1 keeps it off this classpath. The equivalent discipline here is
opaque types with smart constructors plus private case-class constructors, which is what
the existing probatio code already does (`SessionId`, `LedgerRecord`, `LintReport`,
`BannerInputs`).

### New Refined Types

| Type | Underlying | Constraint | Why constrained |
|------|-----------|------------|-----------------|
| `SeamResolution.digest` | opaque over `String` | 64-character lowercase hex | A digest that can hold any string lets a caller compare paths by accident |
| `UnlinkableRow.reason` | opaque over `String` | non-empty | A reason that names nothing cannot be acted on |
| `PortBlocker` | closed enum, not a string | three named blockers | A free-text blocker cannot be checked and drifts |

### Types Kept as Plain

| Type | Why plain |
|------|-----------|
| `GraphNode`, `GraphEdge` | Closed hierarchies; the sealing is the constraint |
| `TraceabilityGraph` | An aggregate; its constraint is that the unlinkable field is required, which the case class expresses |
| `InstallTarget`, `InstallMode`, `EventDispatch`, `WitnessVerdict`, `PrePassOutcome` | Closed enums; no scalar to refine |
| `ArmTree` | Constrained by a private constructor rather than by a value predicate |

## IDL Model Layout

Not applicable — this change defines no API operation and no IDL service. The workflow's
wire formats are JSON documents governed by executable contract files, covered under
Compatibility Story below.

## Error Strategy

### Error Modeling

The workflow's error algebra is the **three-way outcome** already in place:
`Outcome.Ran` / `Outcome.Finding` / `Outcome.Undetermined`. This change adds no fourth
case and no parallel hierarchy. Its whole contribution to the error strategy is to stop
two sites from collapsing the third case into the second:

- the correctness verdict, which currently returns a finding carrying fabricated counts
  where the predecessor returns could-not-determine;
- the feature-freeze guard, which currently fails outright where an empty corpus should be
  could-not-determine.

Adapter-level parse and dispatch errors keep the existing `CliError` hierarchy
(`UnknownSubcommand`, `MissingValue`, `InvalidEnum`, `UnknownFlag`). `EventDispatch`
deliberately sits **outside** `CliError`: an unrecognised event name is no longer an error
at all, it is a dispatch decision.

### Error Propagation

| Boundary | Discipline |
|----------|-----------|
| Core → adapter | Core returns `Outcome[A]` or a named `Either` left; it never throws and never logs |
| Adapter → process | `Outcome` maps to the three termination statuses; no other mapping exists |
| Gate tiers → harness | Every tier fails **open** with a stated reason when its state is unreadable, and every refusal is bounded to one per turn |
| Unreadable input anywhere | `Undetermined` naming the input — never a default value, never a silent skip |

No default branch returns a valid domain value. Where a `case _` survives in the diff it
must carry a `danger-scan:allow` justification on the same line, which Ring 1 checks.

## Compatibility Story (Ring 4)

Four wire formats are in play. Three exist and must not change; one is new.

| Format | Contract | How compatibility is preserved and tested |
|--------|----------|-------------------------------------------|
| Evidence record | `ledger-record-contract.jq` | The ported validator and the contract must agree in **both** directions over a constructive corpus covering each clause independently, augmented by the committed fixture file. Every combination of optional fields round-trips. An unrecognised format version is could-not-determine, never a silent skip. |
| Correctness report | `chain-state-report-contract.jq` | The report shape is unchanged. What changes is **when** a report exists at all: the boundary spec makes it absent where it used to be fabricated. The contract still governs every report that is emitted. |
| Hook envelope | `gate-hookjson-contract.jq` | Unchanged shape. The event-name field must carry the harness's name for every one of the six events in both output formats — asserted by a real contract execution, not a shape check. |
| Graph export | **new** | Round-trip law (write then read yields an equal graph, unlinkable set included), plus agreement with the predecessor's export on the repository's own corpus and on generated corpora. Node labels containing quotes, newlines and non-ASCII characters are generated cases, not afterthoughts. |

Mixed legacy and current records must continue to read cleanly — the existing fixture file
is the regression witness.

## Verification Map

| Module / area | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R8 | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `probatio-core` decision logic | ✓ | ✓ | ✓ | ✓ | ✓ | 90% | ✓ | ✓ | Seven kernels extended/added; `-Werror` + exhaustiveness escalation active |
| `probatio-cli` adapters | ✓ | ✓ | ✓ | ✓ | ✓ | 80% | — | ✓ | I/O boundary; kernels live in core |
| `probatio-plugin` | ✓ | ✓ | ✓ | ✓ | — | 80% | — | ✓ | Shim scope; string-literal mutants excluded (the sbt key macro rejects mutated descriptions) |
| `probatio.migration` (test) | ✓ | ✓ | ✓ | ✓ | — | 80% | ✓ | ✓ | **Test sources** — Stryker collects main-source coverage only, so the move-to-main-and-back procedure applies |
| `probatio.guard` (test) | ✓ | ✓ | ✓ | ✓ | — | 80% | ✓ | ✓ | Same test-source caveat |
| `probatio-verified` | ✓ | — | ✓ | ✓ | — | — | ✓ | ✓ | Scala 3.7.2 pin; invoke directly, the `ring6` alias is broken under this sbt |
| Acceptance suite + CI | — | ✓ | — | ✓ | — | — | — | ✓ | shellcheck on scripts; the suite is the acceptance oracle, not a unit test |

Ring 7 does not apply: the graph is a reachability computation over a static corpus, and
the only ordering property — the record's append-only discipline — is covered by Ring 6.
Ring 9 does not apply: no telemetry stack on this classpath.

## Technical Decisions

### Decision: the comparison materialises trees rather than switching environment variables

**Context.** The shipped harness sets five `*_OVERRIDE` variables and runs the suite in
place. Those variables are *sub-tool stubbing* seams read by both the predecessor gate and
the ported gate to choose which tool the gate invokes — they were never a mechanism for
selecting which implementation is under test, and the suite's own tests overwrite them 36
times in one file. The result is two arms that execute the same on-disk scripts.

**Decision.** Each arm is a materialised copy of the tool tree with the predecessor or the
ported implementation written at each seam, and the arms' seams are digested and compared
before the suite runs.

**Alternatives rejected.** *Keep the environment-variable approach and add a `GATE_OVERRIDE`
reader*: the suite invokes the gate by file path, so a variable cannot redirect it without
changing every test — which would modify the oracle, forbidden by the acceptance protocol.
*Swap files in place and swap back*: a crash mid-run leaves the tree in the wrong state,
and the two arms could not run concurrently.

**Cost.** Two tree copies per run and roughly the current two-minute runtime doubled. If
that proves too slow for the ordinary test cycle, the runner moves out of the default suite
into the continuous-integration job and an explicit invocation — it is an acceptance gate,
not a unit test.

### Decision: the graph port splits parsing from reading at the core boundary

**Context.** The traceability tool is a tool *about* files, and the decision core may not
read files. A naive port would put the whole tool in the adapter, leaving the reachability
closure — the one part with a law worth proving — outside the module where kernels live.

**Decision.** The adapter reads three source trees and hands the core their **contents as
strings**; the core parses, builds the graph, and answers the five queries. The core learns
nothing about the filesystem.

**Consequence.** The reachability closure is a pure function over a finite edge set and
becomes the tenth Stainless kernel. The parsers stay in core too (they are string
functions) and are pinned by the conservation property rather than by a mirror.

### Decision: the event fallback is restored, and made loud

**Context.** The predecessor routes any unrecognised event name to the injection tier and
says nothing. The port rejects unrecognised names outright. Strict rejection is the better
typed contract; it is also a behaviour change that breaks four acceptance tests and, in
production, turns a vocabulary mismatch into a gate that does not run.

**Decision.** Restore the fallback exactly — same tier, same termination status — and add a
diagnostic line naming the supplied value.

**Why this is not a verdict change.** The feature freeze forbids altering what the workflow
concludes. The tier that runs and the status returned are identical to the predecessor's;
only a diagnostic line is added. The silence the predecessor kept is what let the drift
between the adapters and the gate go unnoticed until it was measured, so removing the
silence is a diagnostic improvement, not a new verdict.

### Decision: the directory rename is deferred, and the coupling is recorded as a type

**Context.** The schema declares its new name; its directory still carries the old one. The
workflow tool resolves a schema **by directory name**, the project configuration pins that
name, and eighteen archived changes plus this one record it in their own metadata. Renaming
the directory without a resolution alias breaks schema resolution for all of them.

**Decision.** Do not rename. Record the deferral as a `RenameDeferral` value carrying the
item, the reason, and the blocking coupling — checked, not prose.

**Why a type rather than a sentence.** The six unported tools were recorded as a paragraph
in an archived proposal, and one of them quietly became a dependency of the correctness
verdict. A deferral that a check can read is a deferral the next change inherits rather
than rediscovers. The same reasoning produces the unported-tool register.

### Decision: `ToolId` gains two seams rather than the register absorbing them

**Context.** The evidence-ledger and checkpoint tools have ported implementations that the
acceptance oracle has never run. They could be recorded as unported, or measured and
swapped.

**Decision.** Measure and swap. They are not blocked on anything — the implementations
exist, are unit-tested, and the swap order the migration concept declares already places
the ledger first. The seam set in code has five entries and no ledger seam, while the
declared swap order has six including one; the two have disagreed inside one test package
for the whole migration.

**Consequence.** Six acceptance files get their first real measurement. The count of
regressions this surfaces is unknown and may exceed the twelve already measured; that is a
result, and it is why this spec is sequenced after the harness is trustworthy.
