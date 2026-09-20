# Spec Lint Report

## Mechanical pre-pass

**openspec validate --strict**: **PASS**

```
- Validating...
✓ change/complete-probatio-cutover
Totals: 1 passed, 0 failed (1 items)
```

Five requirements initially failed this check because the SHALL fell on the
second line of the normative paragraph; the validator reads only the first line.
All five were rewrapped so the obligation word is on line one.

**spec-lint.sh**: **PASS** — `spec-lint: 9 spec file(s), 0 FAIL, 32 WARN`

All 32 warnings are W3 (adversarial confirmation for a negative requirement).
No W1, W2, W4, W5, W6 or W7 remains. Per-spec W3 counts: spec-lint-engine 5,
gate-event-completeness 5, ledger-checkpoint-parity 5, cli-entrypoint-contract 4,
danger-reconcile-engines 4, chain-state-attribution 3, native-gate-delivery 3,
cutover-gate 2, live-fact-banner 2.

Each W3 is a prompt to confirm that a requirement containing "only", "never" or
"must not" carries a scenario whose input it forbids. Confirmed individually
under check 15 below — every such requirement has at least one scenario headed
"Adversarial" whose Given is the forbidden input.

**Findings fixed during this pass** (the run that produced them is recorded, not
summarised away):

| Finding | Spec | Fix |
|---|---|---|
| `FAIL F6 line 351: Source names no resolvable reference: Contract: resolveAndSplit` | cli-entrypoint-contract | `Contract:` is not an accepted typed-source kind. Paired with `Requirement:` + `Property:` so the row resolves |
| `FAIL F6 line 499: Source names no resolvable reference: Contract: classifyOutcome` | gate-event-completeness | same fix |
| `FAIL F6 line 352: Source names no resolvable reference: Contract: reachabilityFold` | spec-lint-engine | paired with two `Property:` sources |
| `WARN W6: spec declares Formal Contracts (Ring 6) but no obligation names a bridge/mirror artifact` | native-gate-delivery | the spec has no Ring 6; the section heading was renamed to "Ring 6 Applicability" so it no longer declares a contract it does not have |
| `WARN W1 line 154: vague word ... **Given** any valid configuration` | cutover-gate | replaced with "a configuration built through its smart constructor" |
| `WARN W5 line 131: requirement "A seam resolves to exactly one implementation" claims a state is impossible but is enforced only by tests` | cutover-gate | Enforcement cell upgraded to name the smart constructor (ladder tier 2), which is what actually enforces it |
| `WARN W1 line 146: vague word ... minimal valid argument list` | cli-entrypoint-contract | replaced with "the shortest argument list that tool accepts without reporting a missing or unrecognised flag" |

F9 (artifact existence) is not run here — it is post-implementation, at apply
Step 12 via `spec-lint.sh --artifacts`.

**Note on which binary produced this output.** The lint was run with
`scanner/spec-lint.sh.predecessor.bak`, invoked directly. The live
`scanner/spec-lint.sh` is an exec shim onto the ported binary, which returns a
clean report without reading any spec (proposal §Why, defect 3) and whose CLI
surface does not accept a positional change directory. Using the shim here would
have produced a PASS that examined nothing. Recorded because this change exists
to remove exactly that condition, and its own artifacts must not be certified by
the tool it is repairing.

### Applicability (CONTEXT block, verbatim)

```
spec-lint: CONTEXT — repository facts. These decide each conditional check's
           APPLICABILITY. Compliance remains yours; applicability does not.
  schema                openspec/schemas/verified-scala3  v14
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/git/rs/adk4s/.claude/skills is schema v13, this schema is v14.
     Checks added after v13 are NOT in the instructions you are following.
     Re-install (scanner/install-skills.sh) before trusting this report.
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/git/rs/adk4s/.pi/skills is schema v13, this schema is v14.
     Checks added after v13 are NOT in the instructions you are following.
     Re-install (scanner/install-skills.sh) before trusting this report.
  !! INSTRUCTION DRIFT: skill at /home/gruggiero/.zcode/skills is schema v13, this schema is v14.
     Checks added after v13 are NOT in the instructions you are following.
     Re-install (scanner/install-skills.sh) before trusting this report.
  behavioural registry  openspec/concepts/             PRESENT (37 concepts)
    -> check 17 ALTITUDE **APPLIES**. "N/A" is not a valid verdict for it.
       F10 checks the structural half; W7 lists code-identifier candidates;
       reading the clause prose for behavioural altitude is still your job.
  type inventory        openspec/concept-inventory.md  PRESENT (306 typed rows)
    -> check 6 (reused concepts exist) **APPLIES**.
  capability profile    openspec/capability-profile.md PRESENT
    -> checks 3 (testable with detected stack) and 18 (CONCURRENCY) **APPLY**
       deterministic test kit detected: TestControl testkit
```

**Instruction drift is live and unresolved.** All three installed skill sets
declare schema v13 against a v14 schema. Checks added after v13 are not in the
instructions being followed. This is recorded, not waved past: it is a
pre-existing condition of the repository, it is not caused by this change, and it
is one of the facts the ported banner currently fails to report at all
(`live-fact-banner`). Re-installing the skills is a prerequisite for trusting a
*future* lint report; this report's mechanical half comes from the script, whose
checks are the script's own and are unaffected by the skill version.

| Conditional check | CONTEXT says | Verdict allowed |
|---|---|---|
| 3 · testable with detected stack | capability profile PRESENT | **APPLIES** |
| 6 · reused concepts resolved | type inventory PRESENT (306 rows) | **APPLIES** |
| 17 · ALTITUDE | behavioural registry PRESENT (37 concepts) | **APPLIES** — N/A is not a valid verdict |
| 18 · CONCURRENCY | deterministic test kit detected: TestControl | **APPLIES** where a spec has concurrent behaviour |

**Check 18 scope note.** `TestControl` is a cats-effect facility, and cats-effect
is a *forbidden* dependency for `workflow/*` under R-ARCH1 (`capability-check.md`).
So the detected kit exists for the project but is unavailable to these specs. The
concurrent surface here is subprocess execution (`ledger run`, the gate's
post-tool observation, the differential harness). Check 18 is therefore judged
against the rule it protects — no wall-clock assertions, deterministic
observables only — rather than against the specific kit. Every affected
requirement names an observable (an exit status, a recorded field, a per-file
count), none names a duration. This is stated as a reasoned application of the
check, not a waiver.

## Results

### Spec: specs/cli-entrypoint-contract/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | Every requirement and scenario carries all three clauses |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | `dispatch-equivalence-across-signals` covers both invocation signals per tool; the tool enum is small and the generator covers each ≥ 5% |
| 2 | Then observable | ✅ | Selected tool, delivered argument list, named token, exit status |
| 3 | Scenarios testable | ✅ | munit + Hedgehog, plus subprocess execution of the assembly |
| 4 | Error paths specified | ✅ | Unknown token, empty argument list, missing entry point |
| 5 | New concepts declared | ✅ | `ProgramArgs`, `InvocationName` |
| 6 | Reused concepts resolved | ✅ | All 7 present in the inventory |
| 7 | Generator strategies | ✅ | F3 clean; 4 properties, each with strategy, constructive/filtered, and cover labels |
| 8 | Temporal trigger/response | ✅ | N/A — no temporal properties (Ring 9 not applicable) |
| 9 | No vague words | ✅ | W1 cleared |
| 10 | Unreachable claims proven | ✅ | "not constructible" claims carry compile-negative obligations at ladder tier 1 |
| 11 | Enum extension / type-widening | ✅ | `Subcommand` **shrinks**; the spec states that every match over it must be updated or Ring 0 fails on exhaustiveness |
| 12 | Proof obligations complete | ✅ | F4/F6/F7/F8 clean; 12 obligations, every requirement named by ≥ 1 |
| 13 | Consumer-facing surface asserted | ✅ | The tool surface is the consumer surface; scenarios assert the delivered argument list, not merely that a tool was selected |
| 14 | Error variants type-feasible | ✅ | `CliError` variants are the producing type's own |
| 15 | Adversarial scenarios for negatives | ✅ | 4 W3 prompts, each answered: value-spelling-a-tool-name, program-name-prefixed list, unported tool name, missing entry point |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced table |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses use "tool", "invocation name", "argument list" — no module or class names. Code identifiers confined to Implementation Anchors |
| 18 | Concurrency deterministic | ✅ | Subprocess execution asserts exit status, never duration |

**Verdict: PASS**

### Spec: specs/spec-lint-engine/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Parity is asserted per check identifier via the corpus property, with `cover` forcing failure, clean and warning-only arms |
| 2 | Then observable | ✅ | Failure identifiers with lines, warning identifiers with lines, exit status |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; predecessor run as a subprocess is the model |
| 4 | Error paths specified | ✅ | Unreadable fact, unresolvable source, dangling typed source, unreadable target |
| 5 | New concepts declared | ✅ | 8 concepts |
| 6 | Reused concepts resolved | ✅ | All 9 present in the inventory |
| 7 | Generator strategies | ✅ | F3 clean; 4 properties with cover labels; the corpus generator is enumerative, not filtered |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | |
| 10 | Unreachable claims proven | ✅ | "not representable as passing" is enforced by `CheckOutcome`'s totality plus escalated exhaustiveness — ladder tier 1 |
| 11 | Enum extension / type-widening | ✅ | `CheckOutcome` is new; the spec states exhaustive handling is compile-enforced |
| 12 | Proof obligations complete | ✅ | 14 obligations; every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | The tool's invocation forms are the consumer surface; a requirement and three scenarios assert them |
| 14 | Error variants type-feasible | ✅ | Outcomes are the three-way status enum |
| 15 | Adversarial scenarios for negatives | ✅ | 5 W3 prompts answered: dangling typed source, unreadable fact, present-registry-never-absent, artifact modifier not ignored |
| 16 | MUST-CONFIRM marks present | ⚠️ | **The F1–F10 / W1–W7 check semantics are not marked MUST-CONFIRM.** Judged not required: their authoritative source is *in this repository* (`scanner/spec-lint.sh.predecessor.bak`), and the spec binds to it by an executable parity property rather than by restating the rules. MUST-CONFIRM exists for domains whose source is outside the repo. Recorded as a judgment, not an omission |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "spec document", "obligation row", "applicability facts" |
| 18 | Concurrency deterministic | ✅ | N/A — no concurrent behaviour |

**Verdict: PASS**

### Spec: specs/chain-state-attribution/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | Three statements rewrapped so SHALL is on line one |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Each `UnresolvedReason` variant that the computation can now emit has a scenario asserting it |
| 2 | Then observable | ✅ | Counts, unresolved entries with reasons, unmapped list, exit status |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; predecessor as the model |
| 4 | Error paths specified | ✅ | No spec documents, unreadable ledger, unparseable extractor output |
| 5 | New concepts declared | ✅ | `RequirementSet`, `FactSource` |
| 6 | Reused concepts resolved | ✅ | 15 concepts; 12 in the inventory, 3 forward-declared from `spec-lint-engine` with the introducing spec named |
| 7 | Generator strategies | ✅ | F3 clean; 5 properties; `unattributable-is-reachable-and-never-discharged` builds its case constructively rather than filtering |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | |
| 10 | Unreachable claims proven | ✅ | Impossible counts and reasonless entries are compile-negatives at ladder tier 2 |
| 11 | Enum extension / type-widening | ✅ | No variant is added. `UnresolvedReason.Unattributable` already exists and is unreachable; the spec makes it reachable and says so. **This is a reachable-variant-set growth in the type-widening sense** — the spec's obligations name the matches that must handle it |
| 12 | Proof obligations complete | ✅ | 15 obligations; every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | The emitted report is the consumer surface; a Ring 4 obligation executes the report contract against it |
| 14 | Error variants type-feasible | ✅ | `ChainStateUndetermined` is the producing type's own left branch |
| 15 | Adversarial scenarios for negatives | ✅ | 3 W3 prompts answered: unattributable-not-discharged, ordinal-not-attributed, empty-vs-unread |
| 16 | MUST-CONFIRM marks present | ✅ | No externally-sourced table |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "requirement", "obligation row", "recorded run", "baseline" |
| 18 | Concurrency deterministic | ✅ | The extractor runs as a subprocess; the observable is its reported path, not its duration |

**Verdict: PASS**

### Spec: specs/gate-event-completeness/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Each harness-response shape has its own scenario; `envelope-conforms-to-contract` covers each event variant ≥ 10% |
| 2 | Then observable | ✅ | Exit status, appended record, block reason, emitted envelope field |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; adapter configuration files read as the event-name source |
| 4 | Error paths specified | ✅ | Unknown event, unreadable state, missing predecessor implementation |
| 5 | New concepts declared | ✅ | 6 concepts |
| 6 | Reused concepts resolved | ✅ | All 15 present in the inventory |
| 7 | Generator strategies | ✅ | F3 clean; 6 properties; `refusal-budget-is-bounded-and-nonzero` generates the blockable count separately so the empty case is excluded by construction |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | |
| 10 | Unreachable claims proven | ✅ | Fabricated exit codes and raw session strings are compile-negatives at ladder tier 1–2 |
| 11 | Enum extension / type-widening | ✅ | `GateEvent` **gains** a variant. The spec states that every existing match must handle it and that exhaustiveness escalation makes omission a Ring 0 failure; a compile-negative obligation covers it |
| 12 | Proof obligations complete | ✅ | 25 obligations; every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | The envelope is the harness-facing surface; a requirement and two scenarios assert its event-name field, and a Ring 4 obligation executes the envelope contract |
| 14 | Error variants type-feasible | ✅ | `BlockReason` variants are the producing type's own |
| 15 | Adversarial scenarios for negatives | ✅ | 5 W3 prompts answered, including three separate forbidden-input scenarios for the recording requirement |
| 16 | MUST-CONFIRM marks present | ⚠️ | **The harness response shapes are not marked MUST-CONFIRM.** They are externally sourced — the predecessor establishes them "first-hand from this project's own session transcripts, because the field is not documented". Judged acceptable because the spec does not restate the shapes as a table to be implemented from; it binds them to the predecessor's classifier via scenarios and to the adapter files via the event-name test. **Flagged for the implementer**: do not invent a response shape the predecessor does not handle |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "harness response", "state directory", "turn", "session" |
| 18 | Concurrency deterministic | ✅ | The post-tool path observes a reported exit status; no duration is asserted anywhere |

**Verdict: PASS** (with the MUST-CONFIRM note carried into implementation)

### Spec: specs/live-fact-banner/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Each fact kind (registry, inventory, profile, install root, active change) has present, absent and unreadable scenarios |
| 2 | Then observable | ✅ | Emitted banner text, reported counts, warning set, whether anything was emitted |
| 3 | Scenarios testable | ✅ | munit + Hedgehog over materialised temporary trees |
| 4 | Error paths specified | ✅ | Unreadable fact, undetermined chain-state, no install anywhere |
| 5 | New concepts declared | ✅ | `RepositoryFacts` |
| 6 | Reused concepts resolved | ✅ | 10 in the inventory, 1 forward-declared from `spec-lint-engine` |
| 7 | Generator strategies | ✅ | F3 clean; 4 properties; `suppression-tracks-facts` generates equal and changed arms 50/50 by construction |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | |
| 10 | Unreachable claims proven | ✅ | Hand-constructed banner inputs and in-renderer file reads are compile-negatives |
| 11 | Enum extension / type-widening | ✅ | No enum changes. `installRoots` is a value, not a type; its widening is covered by a compile-negative on arity |
| 12 | Proof obligations complete | ✅ | 16 obligations; every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | The banner text is the agent-facing surface; `banner-states-only-read-facts` asserts its content, not merely its presence |
| 14 | Error variants type-feasible | ✅ | Unreadable is a distinct fact state, not an absence |
| 15 | Adversarial scenarios for negatives | ✅ | 2 W3 prompts answered: present-never-absent, changed-fact-defeats-suppression |
| 16 | MUST-CONFIRM marks present | ✅ | The six install roots are in-repository (the predecessor's own list) |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "registry", "install root", "active change", "session" |
| 18 | Concurrency deterministic | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/danger-reconcile-engines/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | `danger-parity-with-predecessor` covers each of the eight pattern classes ≥ 8%; each `Corroboration` variant has a scenario |
| 2 | Then observable | ✅ | Reported occurrences with file and line, classification per record, exit status |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; predecessor scripts as the models |
| 4 | Error paths specified | ✅ | Unresolvable baseline, unknown parameter, unreadable record set |
| 5 | New concepts declared | ✅ | 5 concepts |
| 6 | Reused concepts resolved | ✅ | All 8 present in the inventory |
| 7 | Generator strategies | ✅ | F3 clean; 5 properties; `justification-excludes-exactly-its-own-occurrence` builds its pair constructively |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | |
| 10 | Unreachable claims proven | ✅ | Witness-without-witness and summary/content disagreement are compile-negatives at ladder tier 2 |
| 11 | Enum extension / type-widening | ✅ | `DangerPattern` and `Corroboration` are new; both carry exhaustiveness compile-negatives |
| 12 | Proof obligations complete | ✅ | 20 obligations; every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | Both tools' invocation forms are asserted by scenarios |
| 14 | Error variants type-feasible | ✅ | Three-way status enum |
| 15 | Adversarial scenarios for negatives | ✅ | 4 W3 prompts answered: no-clean-without-scanning, testimony, contradicted, no-discharge-verdict |
| 16 | MUST-CONFIRM marks present | ⚠️ | **The eight pattern classes are not marked MUST-CONFIRM.** Same judgment as `spec-lint-engine` check 16: the authoritative list is in-repository (`danger-scan.sh.predecessor.bak`), and the spec binds to it by parity rather than restating it. The spec names the classes only in the concept table, in behavioural terms |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "dangerous construct", "justification comment", "recorded run", "observer" |
| 18 | Concurrency deterministic | ✅ | N/A |

**Verdict: PASS**

### Spec: specs/ledger-checkpoint-parity/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | One statement rewrapped |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Each `ReplayVerdict` variant and each operation has its own scenario; `parity-with-predecessor` covers each operation ≥ 10% |
| 2 | Then observable | ✅ | Persisted line content, replay verdict per record, per-ring evidence, whether a marker exists, exit status |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; predecessor as the model; the record contract executed for Ring 4 |
| 4 | Error paths specified | ✅ | Unreadable record set, unknown ring, unparseable verdict, unknown operation |
| 5 | New concepts declared | ✅ | `RingEvidence`, `CheckpointReport`, `ReplayVerdict` |
| 6 | Reused concepts resolved | ✅ | 10 in the inventory, 1 forward-declared from `gate-event-completeness` |
| 7 | Generator strategies | ✅ | F3 clean; 6 properties; `marker-written-iff-evidenced-and-discharged` generates all four condition combinations |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | |
| 10 | Unreachable claims proven | ✅ | Field-dropping encoders, modification operations and inconsistent markers are compile-negatives at ladder tier 1–2 |
| 11 | Enum extension / type-widening | ✅ | `ReplayVerdict` is new with an exhaustiveness compile-negative. `LedgerRecord` gains persisted fields — the spec carries Ring 4 round-trip and mixed-shape obligations for the widening |
| 12 | Proof obligations complete | ✅ | 24 obligations; every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | The persisted record is the wire surface; Ring 4 executes the record contract against it. Both tools' operations are asserted |
| 14 | Error variants type-feasible | ✅ | `ContractViolation` is the validator's own return type |
| 15 | Adversarial scenarios for negatives | ✅ | 5 W3 prompts answered, including the fields-not-dropped and marker-refusal cases |
| 16 | MUST-CONFIRM marks present | ✅ | The record format's authoritative source is the in-repository record contract, cited as the Ring 4 artifact |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "record", "ring", "baseline", "marker", "operation" |
| 18 | Concurrency deterministic | ✅ | Replay executes subprocesses and asserts their exit status; no duration is asserted |

**Verdict: PASS**

### Spec: specs/cutover-gate/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | F1 clean |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Both `CutoverVerdict` variants have scenarios; the comparison generator covers no-file-worse, one-worse, and worse-but-total-improves |
| 2 | Then observable | ✅ | The decision, the named worse file, the resolved seam implementation, the recorded comparison |
| 3 | Scenarios testable | ✅ | munit + Hedgehog over materialised shim trees; the differential harness is itself a deliverable |
| 4 | Error paths specified | ✅ | Incomplete comparison, differing file sets, mismatched repositories, modified suite, missing predecessor |
| 5 | New concepts declared | ✅ | `DifferentialResult`, `CutoverVerdict` |
| 6 | Reused concepts resolved | ✅ | All 10 present in the inventory |
| 7 | Generator strategies | ✅ | F3 clean; 5 properties; `total-improvement-does-not-excuse-a-regression` constructs its regression directly |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | W1 cleared |
| 10 | Unreachable claims proven | ✅ | The both/neither seam state and the evidence-free refusal are compile-negatives at ladder tier 2, named in the Enforcement cell (W5 cleared) |
| 11 | Enum extension / type-widening | ✅ | `CutoverVerdict` is new with an exhaustiveness compile-negative |
| 12 | Proof obligations complete | ✅ | 18 obligations; every requirement named |
| 13 | Consumer-facing surface asserted | ✅ | The gate's decision and its recorded evidence are the surface; scenarios assert both |
| 14 | Error variants type-feasible | ✅ | `Revert` carries the comparison by construction |
| 15 | Adversarial scenarios for negatives | ✅ | 2 W3 prompts answered: one-file-worse-blocks, arms-differ-refused |
| 16 | MUST-CONFIRM marks present | ✅ | The measured 2026-08-29 comparison is in-session evidence, cited with its numbers |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "comparison", "seam", "suite", "implementation" |
| 18 | Concurrency deterministic | ✅ | The two oracle runs are compared by per-file counts, never by duration or ordering |

**Verdict: PASS**

### Spec: specs/native-gate-delivery/spec.md

| # | Check | Status | Detail |
|---|-------|--------|--------|
| 1 | Given/When/Then concrete | ✅ | |
| 1b | SHALL/MUST normative opener | ✅ | One statement rewrapped |
| 1c | Per-variant behavior-preservation scenarios | ✅ | Native platform, non-native platform, per-turn tool and once-per-ring tool each have their own scenario |
| 2 | Then observable | ✅ | The delivered artifact, the recorded measurement, the shim's target, the warning count, the completeness report |
| 3 | Scenarios testable | ✅ | munit + Hedgehog; the measurement itself is a recorded manual run, declared as such |
| 4 | Error paths specified | ✅ | Unmeasured tool, undersized sample set, blocked resolution, unwritable shim, missing artifact, checksum mismatch |
| 5 | New concepts declared | ✅ | `LatencyMeasurement` |
| 6 | Reused concepts resolved | ✅ | All 9 present in the inventory |
| 7 | Generator strategies | ✅ | F3 clean; 5 properties with cover labels straddling every threshold |
| 8 | Temporal trigger/response | ✅ | N/A |
| 9 | No vague words | ✅ | |
| 10 | Unreachable claims proven | ✅ | An evidence-free budget verdict and a hardcoded shim target are compile-negatives at ladder tier 1 |
| 11 | Enum extension / type-widening | ✅ | No enum changes |
| 12 | Proof obligations complete | ✅ | 17 obligations; every requirement named. **One obligation is discharged by a manual run** (the latency measurement on the target host) and says so explicitly, naming the evidence record as its artifact |
| 13 | Consumer-facing surface asserted | ✅ | The shim's target and the fallback warning are the operator-facing surface; both are asserted |
| 14 | Error variants type-feasible | ✅ | `ResolutionResult.Blocked` is the resolver's own variant |
| 15 | Adversarial scenarios for negatives | ✅ | 3 W3 prompts answered: unmeasured-not-met, launcher-not-accepted, missing-artifact-not-complete |
| 16 | MUST-CONFIRM marks present | ✅ | The 150 ms budget is in-repository (`openspec/specs/native-packaging/spec.md`) |
| 17 | Altitude respected | ✅ | F10 clean; W7 silent, and read: clauses say "per-turn tool", "native artifact", "launcher", "release" |
| 18 | Concurrency deterministic | ⚠️ | **This spec asserts a wall-clock quantity by design** — a start-up latency budget. Check 18 forbids wall-clock *timing assertions* standing in for a behavioural observable. Here the latency IS the requirement, inherited from `native-packaging` R-N1. The spec does not assert a duration as a proxy for correctness; it requires a recorded measurement with a stated sample count, and makes the absence of one an undetermined verdict rather than a pass. Judged compliant with the check's purpose |

**Verdict: PASS**

## Summary

| Spec | Verdict | Blocking Issues |
|------|---------|-----------------|
| specs/cli-entrypoint-contract/spec.md | **PASS** | 0 |
| specs/spec-lint-engine/spec.md | **PASS** | 0 — 1 note (check 16: pattern/check semantics bound by parity, not restated) |
| specs/chain-state-attribution/spec.md | **PASS** | 0 |
| specs/gate-event-completeness/spec.md | **PASS** | 0 — 1 note carried to implementation (check 16: do not invent a harness response shape) |
| specs/live-fact-banner/spec.md | **PASS** | 0 |
| specs/danger-reconcile-engines/spec.md | **PASS** | 0 — 1 note (check 16, as above) |
| specs/ledger-checkpoint-parity/spec.md | **PASS** | 0 |
| specs/cutover-gate/spec.md | **PASS** | 0 |
| specs/native-gate-delivery/spec.md | **PASS** | 0 — 1 note (check 18: the latency budget is the requirement, not a proxy) |

**Overall: PASS.** All nine specs may proceed to design and implementation-order.

### Standing conditions recorded, not resolved

1. **Instruction drift is live** — all three installed skill sets are v13 against
   a v14 schema. Not caused by this change; re-install before trusting a future
   lint report.
2. **This report was produced by the predecessor lint script, not the shipped
   tool.** The shipped tool would have returned a clean report having read
   nothing. That is the condition this change removes, and the first future lint
   report that can honestly be produced by the ported tool is the one taken after
   `spec-lint-engine` reaches parity under the differential harness.
