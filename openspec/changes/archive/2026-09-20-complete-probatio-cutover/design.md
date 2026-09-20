# Design: Complete the probatio cutover

## Package Structure

### Layers

The probatio subprojects already carry a hard layering rule; this change adds
nothing to it and leans on it heavily, because the single most common defect in
the current port is logic placed where it cannot reach the filesystem it needs.

| Layer | Module | May depend on | May NOT depend on |
|---|---|---|---|
| Verified mirror | `probatio-verified` (`verified/probatio`, Scala 3.7.2) | Stainless library only | everything else, including `probatio-core` |
| Pure decision core | `probatio-core` (`workflow/core`) | `probatio-verified % Test`, upickle/ujson, os-lib (types only) | cats, cats-effect, fs2, llm4s, workflows4s, adk4s (R-ARCH1); **and, by this change's discipline, `java.nio.file`, `java.lang.Process`, and `System.getenv`** |
| I/O adapter + CLI | `probatio-cli` (`workflow/cli`) | `probatio-core`, mainargs, os-lib, java.nio, subprocesses | cats, cats-effect, fs2, llm4s, workflows4s, adk4s (R-ARCH1) |
| Build adapter | `sbt-probatio` (`workflow/plugin`, Scala 2.12) | sbt API only | `probatio-core` in any form (R-S1) — communication is argv + exit codes + stdout |

The `probatio-core` restriction on file I/O and environment reads is not
currently a build rule — it is a per-file compile-negative test, first
established by `PredecessorCheck` and `GrantWaiver`. This change extends the same
compile-negative to every new core component (`SpecLintEngine`,
`DangerScanEngine`, `ReconcileEngine`, `CheckpointEngine`, the gate's
classification functions). Ring 2 enforcement remains `probatioDependencyLint`
for the dependency half; the I/O half stays compile-negative because no
dependency-level rule can express "may not call `Files.readAllBytes`".

**Why this matters here**: the ported gate returns `Undetermined` for every
pre-execution event because `PredecessorCheck` correctly refuses to read files
and *nothing in the CLI layer was written to read them for it*. The pure half
was built and the adapter half was skipped. Every spec in this change therefore
names its reader explicitly, in `probatio-cli`.

### New Packages

No new package is introduced. New types land in the existing three:

| Package | Additions |
|---|---|
| `org.sinemenda.probatio.core` | `SpecDocument`, `RequirementBlock`, `PropertyBlock`, `TemporalBlock`, `ObligationRow`, `ObligationSource`, `CheckOutcome`, `LintContext`, `SpecLintEngine`, `SpecDocumentParser`, `RequirementExtractor`, `RequirementSet`, `FactSource`, `DangerPattern`, `DangerHit`, `DangerReport`, `DangerScanEngine`, `Corroboration`, `ReconcileReport`, `ReconcileEngine`, `HarnessPayload`, `ToolOutcome`, `SessionId`, `RefusalBudget`, `HeartbeatRecord`, `RingEvidence`, `CheckpointReport`, `CheckpointEngine`, `ReplayVerdict`, `RepositoryFacts` (data only) |
| `org.sinemenda.probatio.cli` | `ProgramArgs`, `InvocationName`, `RepositoryFactsReader`, `GateStateDirReader`, `GateStateDir`, `HarnessPayloadReader`, `ChangedFilesReader` |
| `org.sinemenda.probatio.packaging` | `LatencyMeasurement` |
| `org.sinemenda.probatio.migration` (test-only) | `DifferentialResult`, `CutoverVerdict`, `CutoverGate`, `DifferentialHarness` |
| `org.sinemenda.probatio.core` in `probatio-verified` | `DispatchKernel`, `SpecLintKernel`, `GateKernel`, `ReconcileKernel`, `CutoverKernel` (new); `ChainStateKernel`, `BannerEngineKernel`, `LedgerValidatorKernel` (extended) |

**Removals** (six enum cases plus their entrypoint objects and two match arms) are
listed in `inventory-check.md` §Remove. They are part of the design, not a
side effect: an unimplemented tool must be unnameable rather than silently clean.

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|---|---|---|
| `MulticallDispatch.resolveAndSplit` | Selects a tool and splits the argument list | **Yes** — a decision with a suffix invariant; inputs reduce to `(Option[BigInt], List[BigInt])`. Mirror: `DispatchKernel`. This is the defect that shipped; a proof that the remainder is a bounded suffix is exactly the property that was violated |
| `SpecLintEngine.reachabilityFold` | Maps obligation rows to requirements, reports the unenforced set | **Yes** — a fold with a complement invariant; inputs reduce to `(BigInt, List[BigInt])`. Mirror: `SpecLintKernel` |
| `SpecLintEngine` F1–F10 / W1–W7 checks | Per-check verdicts over parsed documents | **No** — text matching over document structure; no fold or law at the centre. Bound instead by `verdict-parity-with-predecessor`, a model-based Ring 3 property against the predecessor executed as a subprocess |
| `SpecDocumentParser.parse` | Markdown → `SpecDocument` | **No** — parsing, not deciding. Covered by the same parity property plus `reachability-is-total` |
| `ChainState.compute` | The correctness verdict fold | **Yes** — already mirrored by `ChainStateKernel`; extended with the unattributable clause and the monotonicity/complement postcondition |
| `RequirementExtractor` | Spec documents → `RequirementSet` | **No** — projection, no decision. Covered by `counts-are-consistent` and the parity property |
| `Validator.validate` (15 clauses) | Record contract validation | **Yes** — already mirrored by `LedgerValidatorKernel`. Unchanged by this change |
| `CheckpointEngine.markerDecision` | Whether the presentation marker is granted | **Yes** — a two-condition decision guarding the gate's lock; inputs reduce to `(List[BigInt], List[BigInt], BigInt)`. Mirror: extended `LedgerValidatorKernel` |
| `CheckpointEngine.ringEvidence` | Per-ring record attribution | **No** — filtering by key equality; covered by `checkpoint-reports-every-requested-ring` |
| `RefusalBudget.apply` | At most one refusal per turn | **Yes** — a fold with an exactly-one invariant and a first-index law. Mirror: `GateKernel` |
| `ToolOutcome.classify` | Harness response → exit code or skip | **Yes** — a conservative classification; inputs reduce to a shape code plus a carried code. Mirror: `GateKernel`. This is where a fabricated exit code would be born |
| `BannerEngine.render` claim extraction | Which claims the banner emits for which facts | **Yes** — already mirrored by `BannerEngineKernel`; extended with the unreadable-is-not-absent clause |
| `DriftScan.scan` | Install-root version comparison | **No** — a per-root comparison with no cross-root invariant beyond cardinality, which `every-searched-root-is-scanned` states directly |
| `ReconcileEngine.corroborationFold` | Per-record corroboration classification | **Yes** — a fold whose classes must partition the record set, with a witness-existence law. Mirror: `ReconcileKernel` |
| `DangerScanEngine.scan` | Pattern matching over source text | **No** — text matching; no reducible decision. Bound by `danger-parity-with-predecessor` |
| `PredecessorCheck` / `GrantWaiver` | Existing gate decisions | **No** — already shipped and tested; unchanged by this change. Their Ring 3 properties stand |
| `CutoverGate.decide` | Per-file comparison → proceed or revert | **Yes** — a comparison fold with a witness law. Mirror: `CutoverKernel`. Test-only code, but the decision it makes authorises an irreversible swap |
| `BinaryResolution.resolve` | Native vs launcher | **No** — a three-case table already covered by `per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform` |
| `ReleaseValidator.validateAll` | Manifest completeness | **No** — a conjunction of independent field checks; `release-complete-iff-every-named-artifact-present-and-matching` states it directly |

Nine mirrors, five of them new. Every "No" row states its reason and names the
Ring 3 property that carries the obligation instead — silence is not a verdict.

### Effectful Code

All effects live in `probatio-cli` (and, for the differential harness, in
test-only migration code). Each reader is a named component so that "the pure
function had no adapter" cannot recur silently:

| Component | Effect | Consumed by |
|---|---|---|
| `RepositoryFactsReader` | Reads the registry, inventory, profile, six install roots, and active-change directories | `SpecLintCmd` (as `LintContext`), `GateCmd` (as `BannerInputs`) |
| `GateStateDirReader` | Reads and writes heartbeat, per-session fingerprint, grant tokens, presentation markers, oracle phase under the repository's git directory | `GateCmd` |
| `HarnessPayloadReader` | Reads the gate's input channel **at most once, in the top-level process** | `GateCmd` |
| `ChangedFilesReader` | Resolves a baseline and enumerates changed production files | `DangerScanCmd` |
| `SubcommandWiring` | Record file read/append, stdout/stderr, timestamp | every subcommand |
| `LedgerCmd` run/replay paths | Executes a subprocess and observes its exit status | `LedgerCmd` |
| `DifferentialHarness` | Materialises two seam-configured scanner trees, runs the suite twice, parses both outputs | `CutoverGate`, `probatioOracleDiff` |

**The at-most-once read is a design constraint, not an implementation detail.**
The predecessor records that reading its input channel from inside a command
substitution drained the stream for every later caller, so the production path
saw empty fields while every test passed. The reader is therefore invoked once,
in the entry point, and its result is passed as a value.

## Type Strategy — Invalid-State Prevention

| Invariant | Placement | Justification |
|---|---|---|
| An argument list never contains the program name | **Impossible to express** — `ProgramArgs` is an opaque type constructible only from a runtime entry point or a declared fixture | The defect shipped because a test could hand `dispatch` the shape the implementation expected. Making the two shapes distinct types removes the option |
| An unimplemented tool is not nameable | **Impossible to express** — the enum case is removed | Strictly stronger than a denylist; the same argument `Subcommand`'s scaladoc already makes for the absent mutation operations |
| A check result is never "absent, therefore passing" | **Impossible to express** — `CheckOutcome` has exactly three cases and exhaustiveness is escalated to a compile error | The classic silent-clean defect |
| A record's optional fields are never dropped on write | **Impossible to express** — the encoder takes the joined record type; no ten-field encoder exists | Currently a live defect: `LedgerRecordOptional` is declared and orphaned |
| A gate refusal is never unbounded | **Smart constructor** — `RefusalBudget` is consumed, not re-derivable | A budget expressed as a boolean flag would be re-settable by any caller |
| Two sessions never share a state file | **Impossible to express** — `SessionId` is opaque and constructible only through its lossless encoder | A lossy sanitiser is what collapses distinct identities |
| A block reason is always present on a block | **Impossible to express** — `GateDecision.Block` requires a `BlockReason` (already shipped) | Unchanged |
| A refusal to cut over always names its evidence | **Impossible to express** — `CutoverVerdict.Revert` requires a `DifferentialResult` | The archived change recorded a green gate with no per-file evidence, so nothing could contradict it |
| A seam never resolves to both or neither implementation | **Smart constructor** — `SeamConfiguration` takes only the ported set; the predecessor set is derived | A two-set representation admits both states |
| Counts are monotone (`discharged ≤ resolved ≤ bound ≤ total`) | **Smart constructor** on `ChainStateReport`, plus a Ring 6 postcondition | A tuple of independent integers admits every impossible combination |
| An unresolved requirement always states a reason | **Smart constructor** — `UnresolvedEntry` rejects an empty reason list | A claim without evidence, in miniature |
| A witness classification always has a witness | **Smart constructor** — `Corroboration.Witnessed` carries the observing record | Otherwise it is testimony with a better label |
| A marker is never written on an undischarged spec | **Smart constructor** on `CheckpointReport`, plus a Ring 6 postcondition | The marker is what the gate's lock reads; an advisory marker makes the lock advisory |
| A budget verdict never exists without a measurement | **Impossible to express** — the verdict function's signature requires a measurement | An assumed budget is the defect |
| The banner never states a fact that was not read | **Smart constructor** — `BannerInputs` is constructible only via `BannerInputs.from(RepositoryFacts)`; the raw constructor becomes private | This is the live fabrication defect, and it arose precisely because the raw constructor was public |

Nothing in this table sits at "Risky" or "Bad". Two invariants sit at "Okay"
(validator) and are recorded as such: the record contract's 15 clauses (already
shipped, deliberately a validator because the contract is the wire format) and
the parsed-document shape (a parser cannot make malformed markdown
unrepresentable).

## Refined Type Strategy

Iron is **not available** to `probatio-*` — it is not in the allowed dependency
set (`non-goals-guard` R-X3, verified in `capability-check.md`). Refinement is
therefore expressed with Scala 3 `opaque type` plus a smart constructor
returning `Either`, which is the pattern the existing `SessionId`-shaped types in
this codebase would use.

### New Refined Types

| Type | Underlying | Constraint | Why refined |
|---|---|---|---|
| `ProgramArgs` | `List[String]` | Contains no program name; constructible only from a runtime entry point or a declared fixture | Crosses the process boundary; the defect lived exactly here |
| `InvocationName` | `String` | Non-empty; obtained from the runtime, never from an argument | Same boundary |
| `SessionId` | `String` | Its filename encoding is injective (base64url-style, lossless) | Persisted as a filename component; a collision silently merges two sessions' state |

### Types Kept as Plain

| Type | Why not refined |
|---|---|
| `SpecDocument`, `RequirementBlock`, `ObligationRow` | Internal to one process; every field is already constrained by the parser that produces them, and no field crosses a persistence boundary |
| `DangerHit`, `DangerReport` | Internal; the report is rendered, not persisted |
| `RepositoryFacts`, `LintContext`, `GateStateDir` | Snapshots of filesystem state, valid by construction of their reader |
| `LatencyMeasurement` | Its only invariant (sample count sufficiency) is a *verdict* input, not a construction constraint — an undersized measurement is a real, recordable observation whose consequence is "undetermined", not a value to reject |
| `DifferentialResult` | Test-only |

## IDL Model Layout

Not applicable. This change introduces no Smithy or protobuf model. The
`workflow/*` subprojects have no IDL stack, and smithy4s is an adk4s-side
dependency forbidden to them by R-ARCH1.

## Error Strategy

### Error Modeling

The three-way outcome protocol is already the project's error algebra and is
unchanged:

- `Outcome.Ran(a)` — ran, nothing wrong
- `Outcome.Finding(msg)` — ran, found something wrong
- `Outcome.Undetermined(reason)` — **could not establish anything**

Mapped at the boundary by `ExitCode.from` to 0 / 1 / 2. The load-bearing rule,
restated because this change repeatedly turns on it: **`Undetermined` is never
collapsed into `Finding` and never into `Ran`.** A caller receiving `Finding`
knows the tool worked and the input was bad; receiving `Undetermined` knows
nothing was established. Collapsing them makes a corrupt input indistinguishable
from a clean one.

Sub-algebras, each a sealed type with no catch-all:

| Algebra | Cases | Boundary |
|---|---|---|
| `CliError` | `UnknownSubcommand`, `MissingValue`, `InvalidEnum`, `UnknownFlag` | stderr line naming the offending token, then `Finding` |
| `ContractViolation` | 15 clause variants | `Finding` naming the clause index and description |
| `BlockReason` | `PredecessorNotVerified`, `PredecessorNotCheckpointed`, `GrantRequired`, … | the gate's block payload |
| `CheckOutcome` | `Pass`, `Fail`, `Warn` | the lint report |
| `ToolOutcome` | `Exit`, `Skip` | whether a record is written at all |
| `ReplayVerdict` | `Matches`, `Diverges`, `Unreplayable` | replay report and exit status |
| `CutoverVerdict` | `Proceed`, `Revert(evidence)` | the cutover decision |

### Error Propagation

Three rules, each traceable to a defect this change repairs:

1. **A reason is stated once.** `SubcommandWiring.readLedgerFile` currently embeds
   the `UNDETERMINED —` marker in its reason string and every caller prefixes it
   again. The marker becomes the boundary renderer's job; readers return bare
   reasons.
2. **An unreadable input is `Undetermined`, an unparseable input is
   `Undetermined`, an invalid-but-parsed input is `Finding`.** Applied uniformly
   across the record reader, the spec-document reader, and the state reader.
3. **A missing capability is `Undetermined`, never `Ran`.** The gate cannot read
   the state directory → the *decision* fails open (allow), but the *diagnostic*
   says the state could not be read. Failing open and reporting clean are
   different things; the current port does neither — it fails closed and reports
   nothing.

No `case _ =>` arm may return a valid domain value in any new code. Where a
catch-all is genuinely required at a string boundary (parsing an external token),
it maps to an error case and carries a `// danger-scan:allow` justification, the
convention already used in `SubcommandEntrypoints`.

## Compatibility Story (Ring 4)

Ring 4 applies. Three wire formats, each with an in-repository executable
contract that is the single statement of its shape:

| Format | Contract | What changes | Compatibility obligation |
|---|---|---|---|
| Evidence record | `scanner/ledger-record-contract.jq` | Nothing in the format. The port begins **emitting** the optional fields (`sha256`, `digest`, `wallTime`, `source`, `session`) it currently drops | Round-trip property over all 32 optional-field presence combinations; the existing mixed-shape fixture `tests/fixtures/evidence-ledger-v1.jsonl` must read cleanly; a record whose version is unrecognised makes the read `Undetermined`, never skipped |
| Correctness report | `scanner/chain-state-report-contract.jq` | `unmapped_obligations` becomes populated; `unresolved` gains entries carrying the `unattributable` reason. Field names and snake-case shape unchanged | Contract-conformance test executing the checker against the port's output for every fixture; the `unmapped_obligations` field is present even when empty |
| Hook envelope | `scanner/gate-hookjson-contract.jq` | `hookEventName` is corrected to the harness's own name (currently the internal enum name); the sixth event gains an envelope | Contract-conformance test per event variant |

Unknown/missing-field behaviour: unchanged and re-asserted. An unrecognised
record version is `Undetermined` (skipping it would make a forward-incompatible
record set read as partially discharged — silent degradation wearing a clean
result). An absent optional field is valid and distinguishes a written assertion
from a self-observation; that distinction is *load-bearing* for corroboration,
so "absent" and "present" are both meaningful states rather than a default.

Schema evolution: none. `non-goals-guard` R-X2 forbids schema changes beyond the
v14 rename, and this change introduces none.

## Pure Code (Ring 6 candidates)

See §Effect Boundaries — the triage table above is the Ring 6 verdict table, with
nine "Yes" rows (five new mirrors, four extended) and eleven "No" rows each
naming the Ring 3 property that carries its obligation instead.

Every mirror carries a **bridge property test** in the owning module's ordinary
test scope, running shipped code and model on the same generated inputs. Without
it the ring proves a property of a program nobody ships. The bridges are:

| Mirror | Bridge test | Module |
|---|---|---|
| `DispatchKernel` | `EntrypointBridgeSpec` | `probatio-cli` |
| `SpecLintKernel` | `SpecLintBridgeSpec` | `probatio-core` |
| `ChainStateKernel` | `ChainStateBridgeSpec` (existing, extended) | `probatio-core` |
| `GateKernel` | `GateBridgeSpec` | `probatio-cli` |
| `ReconcileKernel` | `ReconcileBridgeSpec` | `probatio-core` |
| `BannerEngineKernel` | `BannerBridgeSpec` | `probatio-cli` |
| `LedgerValidatorKernel` | `CheckpointBridgeSpec` (marker decision) | `probatio-core` |
| `CutoverKernel` | `CutoverBridgeSpec` | `probatio-core` (test scope) |

`probatio-core dependsOn(probatio-verified % Test)` is already wired.
`probatio-cli` is not; the three cli-side bridges require adding
`probatio-verified % Test` to `probatio-cli`, which is a build change this design
commits to. It does not violate R-ARCH1 — `probatio-verified` is a probatio
module with no forbidden dependencies.

## Verification Map

| Module / area | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R8 | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `probatio-cli` entry point + dispatch | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | ✅ | R3 includes subprocess conformance over the built assembly |
| `probatio-core` lint engine | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | ✅ | R3 includes model-based parity against the predecessor |
| `probatio-core` chain-state | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | R4 executes the report contract |
| `probatio-cli` gate | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | R4 executes the envelope contract |
| `probatio-cli` banner/facts reader | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | ✅ | |
| `probatio-core` danger + reconcile | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | ✅ | Reconcile is mirrored; danger is not |
| `probatio-core`/`cli` record + checkpoint | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | R4 executes the record contract |
| `probatio-migration` cutover gate (test-only) | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | ✅ | R5 skipped: test-only code, not shipped production logic |
| `sbt-probatio` + packaging | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | ✅ | R6 skipped: no fold or law; see the triage table |
| `probatio-verified` mirrors | ✅ | — | ✅ | — | — | — | ✅ | — | R1 skipped: WartRemover is disabled on the mirror leaf by build configuration |

**Ring 7** does not apply anywhere: no distributed or event-ordering invariant
beyond the record set's append-only discipline, which Ring 6 covers.
**Ring 9** does not apply anywhere: no telemetry stack in `workflow/*`.

**Ring 3 acceptance is defined once, for every spec**: the spec's own bats files
at parity with the predecessor control under `probatioOracleDiff`. A spec's unit
and property tests passing is necessary and not sufficient — that combination is
exactly what certified the current, broken port.

**Ring 5 caveat**: `stryker4s.conf` has `break = 0`, so the tool never fails the
build on score. Every Ring 5 claim in this change must cite the reported score,
not the exit code. Both `mutate` and `test-filter` must be retargeted per spec;
the file currently points at a single unrelated file.

## Technical Decisions

### Decision: Fix the entry point first and re-measure before porting any behaviour

**Context.** 122 of 282 oracle tests fail under the port. An unknown fraction of
those failures is caused by the argv defect making every subcommand unreachable,
and the rest by unimplemented behaviour. The two are indistinguishable from the
outside.

**Decision.** `cli-entrypoint-contract` is spec 1, and the differential harness
(`cutover-gate`'s deliverable) is built alongside it, before any behaviour is
ported. The oracle is re-measured immediately after.

**Alternative rejected.** Porting behaviour first and fixing the entry point last
would mean every intermediate measurement conflates "not implemented" with "not
reachable", and every intermediate claim of progress would be unfalsifiable —
which is how the current state arrived.

**Consequence.** The first spec produces a number, not a feature. That number is
the baseline every later spec is measured against.

### Decision: Redefine "oracle green" as parity with the predecessor, not zero failures

**Context.** The Conformance Property-Test Contract concept defines green as
`failed == 0`. The predecessor fails 20 of 282 tests, a recorded pre-existing
set. The predicate is therefore unsatisfiable by any implementation, including
the one it exists to certify.

**Decision.** Green becomes: no file fails more tests under the port than under
the predecessor, measured in the same repository under the same suite. Both
concept files are updated as part of `cutover-gate`.

**Alternatives rejected.** (a) *Fix the 20 pre-existing failures first* — that is
a separate change, and coupling them makes this one unshippable. (b) *Compare
totals rather than per-file counts* — that permits trading a regression in the
gate for an improvement in the record tool, which is precisely the trade this
workflow exists to forbid.

**Consequence.** The gate becomes satisfiable, so it can be enforced rather than
skipped. It is also strictly stronger than the total-based alternative.

### Decision: Remove the seven unported tools from the surface rather than leaving them as stubs

**Context.** Eight subcommands accept arguments, do nothing, and exit clean.
Porting them requires the scalameta native-image spike (R-N5), which has not been
run, and a python3 graph extractor whose subcommand surface the stub does not even
match.

**Decision.** Remove `registry-check`, `scan`, `removal-audit`, `impact-scan`,
`concept-scanner`, `graph`, and the `metals` `stop`/`call` sub-actions from the
dispatch surface. Their predecessor implementations remain live and are invoked
directly. `metals start` is retained because it is genuinely wired.

**Alternative rejected.** Leaving them as stubs. A caller cannot distinguish a
stub's clean exit from success, and 365 of the port's 612 mutants are NoCoverage
mutants inside them — the mechanical reason Ring 5 scored 16.39%.

**Consequence.** The port's surface shrinks from 16 tools to 9. This is visible
and intentional: an honest 9 is worth more than a 16 of which 7 lie.

### Decision: Bind every ported check to the predecessor by an executable parity property

**Context.** `non-goals-guard` R-X1 freezes the feature set — no check added, no
verdict altered. A hand-written expectation for each check would be free to encode
the port's behaviour rather than the predecessor's, which is how the current unit
tests came to pass against a broken product.

**Decision.** Each ported engine carries a model-based Ring 3 property whose
*model is the predecessor script executed as a subprocess*, over a fixture corpus
extracted from the predecessor's own bats fixtures plus the repository's real spec
documents.

**Alternative rejected.** Golden-file expectations. They freeze one run's output,
including its defects, and they do not fail when the port and the predecessor
diverge on an input nobody thought to add to the corpus.

**Consequence.** The predecessor scripts must remain on disk and executable for
the whole of this change — which they must anyway, as the revert target.

### Decision: Every filesystem reader is a named component in the CLI layer

**Context.** `PredecessorCheck` and `GrantWaiver` are correct pure functions with
no adapter, so the gate returns `Undetermined` unconditionally. `BannerEngine` is
a correct pure renderer with no adapter, so the banner is assembled from
constants. The same failure mode, twice.

**Decision.** Each spec names its reader (`RepositoryFactsReader`,
`GateStateDirReader`, `HarnessPayloadReader`, `ChangedFilesReader`) as a
deliverable with its own obligations, and the pure component's input type is
constructible only from that reader's output where the fabrication risk is real
(`BannerInputs.from`).

**Alternative rejected.** Letting the entrypoints read inline. That is what
happened; the reads never got written and nothing noticed, because no obligation
named them.

**Consequence.** Four new components, each with a property test over generated
repository shapes. The readers are the largest new surface in the change.

### Decision: Ring 8 verifies the built artifact as a subprocess, not functions in a JVM

**Context.** Two prior Ring 8 reviews passed a port whose every subcommand was
unreachable and whose banner fabricated its facts. Both reviews inspected
functions; both functions were correct.

**Decision.** The Ring 8 standing instruction for this change is to verify each
requirement against the built artifact invoked the way a shim invokes it.

**Consequence.** Ring 8 depends on the assembly being current. The review runs
after `probatio-cli/assembly`, and the reviewer is told which artifact path the
shims resolve.
