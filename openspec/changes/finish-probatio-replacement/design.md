# Design: Finish replacing verified-scala3 with probatio

## Package Structure

All work lands in the `workflow/*` modules, the Stainless mirror leaf, the schema directory,
the acceptance suite and the CI configuration. No adk4s module is touched.

### Layers

| Layer | Package / location | May depend on | Must NOT depend on |
|-------|--------------------|---------------|--------------------|
| Decision core | `org.sinemenda.probatio.core` | stdlib, `ujson`, `probatio-verified % Test` | cats, cats-effect, fs2, llm4s, workflows4s, adk4s, scalacheck; **any file I/O or environment read** |
| CLI adapter | `org.sinemenda.probatio.cli` (one file per subcommand after `entrypoint-split`) | `probatio-core`, stdlib, `os-lib`, `ujson`, `mainargs` | the forbidden set above |
| Packaging | `org.sinemenda.probatio.packaging` | stdlib, `os-lib` | the forbidden set |
| sbt plugin | `org.sinemenda.probatio.plugin` | sbt API, stdlib | `probatio-core` (R-S1) |
| Verified mirror | `verified/probatio` | Stainless library, stdlib | everything project-local |
| Test infrastructure | `org.sinemenda.probatio.migration`, `…guard`, and a shared test-support source for `HermeticEnv` and `ChangeLocation` | `probatio-core`, `os-lib`, munit, Hedgehog | the forbidden set |
| Acceptance oracle | `openspec/schemas/<schema>/tests/` — behavioural tests only | bash, bats, jq, git | — |
| Implementation-shape suite | `openspec/schemas/<schema>/tests/shape/` (new) | bash, bats, grep | — not guarded for immutability |

Ring 2 enforces two rules, both already wired:

- **R-ARCH1** (`probatioDependencyLint`) — nothing in the forbidden set on any `workflow/*`
  classpath.
- **No I/O in core** (`NoIOInProbatioCore`) — the core may not read files or the
  environment. Three specs press on it. `jar-launcher-dispatch` classifies an invocation
  source (the classification is core, reading the runtime name is the adapter).
  `registry-check-port` ports a tool about files (parsing and verdicts are core, the tree
  listing is the adapter). `legacy-name-retirement` routes every environment read through an
  alias table (the table and resolution are core, the read of the environment map is the
  adapter).

This change adds **two lints** to the same `.scalafix.conf` mechanism, each a `DisableSyntax`
block scoped to `workflow/*/src/test/scala/**`: no raw process construction, and no literal
path into a named change's active directory. A third, for `legacy-name-retirement`, is
scoped to main sources: no direct `env.get` on a controlled-variable name outside the alias
table.

### New files

| New | Location | Why here |
|-----|----------|----------|
| `InvocationSource` | `probatio.cli` | the source of a program name is an adapter fact; the dispatch decision over it stays total and pure |
| `HermeticEnv`, `ControlledVariable`, `ChangeLocation` | shared test-support source | used by every process-spawning and fixture-reading test |
| `OracleTestKind`, `OracleSanction`, `SanctionVerdict`, `OracleBaseline` | `probatio.guard` | the guard's own model |
| `ToolNameSource` | `probatio.core` | the pre-execution tier's decision input |
| `ToolchainIdentity` | `probatio.packaging` | release-check input |
| `LiveRoute` | `probatio.core` | the register's classification input |
| `RegistryRow`, `BindingVerdict`, `RegistryReport` | `probatio.core` | the verifier's pure engine |
| `LegacyAlias` | `probatio.core` | alias resolution is a decision |
| `SchemaAlias` | `probatio.core` | a recorded fact read by the rename checks |
| 11 entrypoint files | `probatio.cli` | one per subcommand, plus one shared-helpers file |
| Sanction record | beside the acceptance oracle | persisted data the guard reads |
| Register entries | `unported-tools.md` | two code-intelligence scripts added, the verifier removed |

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|---|---|---|
| Archive-aware dispatch (`resolveSource`) | An archive source dispatches like the generic name; a foreign executable name is rejected by that name; the classification is total | **Yes** — extends `DispatchKernel`. Three-way decision over a closed source type, reducible to a tag and argument identities. |
| Sanction verdict (`sanctionVerdict`) | The guard passes iff every modification is sanctioned and every input is readable | **Yes** — set inclusion over finite sets; extends `SpecLintKernel` next to the existing `GuardResult`. |
| Registry pass decision (`registryPassed`) | The run passes iff there is no stale verdict and no spec problem; weak never fails | **Yes** — a fold over the verdict list; extends `SpecLintKernel`. |
| Alias resolution | Ordered lookup: probatio name, then legacy inside the window | **No** — a two-step ordered lookup with no fold or law; pinned by `alias-resolution-is-ordered-and-bounded`, which enumerates every combination. |
| State-directory migration | Move legacy state once, never over existing state | **No new kernel** — the same law as the cache migration, which is already verified. A bridge property binds the state-directory instance to it. |
| Swap authorisation for the new seams | Authorise only on a complete, regression-free comparison | **No new kernel** — `LedgerValidatorKernel.authoriseSwap` already verifies it; this change adds seams, not a decision. |
| Register classification by live route | Ported iff the live file forwards to the port | **No** — a two-variant classification driven by one observed fact; the type's two cases and a property pin it. |
| Hermetic-environment construction | Keep a controlled variable iff declared | **No** — set membership over a closed enumeration; pinned by the type and one property. |
| Toolchain check | Accept iff tested and candidate identities are equal | **No** — equality; pinned by one property. |
| Resolver, split, rename | Lookup, relocation, path rewrite | **No** — no decision, fold or law at the centre. |

Three kernels extended, seven candidates declined with a stated reason.

### Effectful Code

| Code | Effect | Boundary discipline |
|------|--------|---------------------|
| Program-name read | reads the runtime's command string and process information | Produces an `InvocationSource`; the dispatcher never inspects a raw string for a suffix |
| Launchers (`bin/probatio`, the plugin's installed launcher) | exec a binary or `java -jar` | Unchanged in shape; once dispatch handles archive sources they work as written. The test-only symlink workaround is removed |
| `HermeticEnv` | builds a child process environment | The only way tests build one; a lint rejects the alternatives |
| Differential harness | materialises two worktrees and runs two suites | Now provisions the built tool into each arm for tests that call it directly |
| Registry verifier adapter | lists tracked files, reads concept and spec files | Hands the core strings; the core returns a `RegistryReport` |
| Gate state directory | moves legacy state on first use | Idempotent; never overwrites; reports a left-behind legacy directory |
| CI job | runs on the hosting service | Observed, not inferred; published only with the maintainer's authorisation |

**Deterministic concurrency.** The detected kit (`TestControl`) is unreachable from
`workflow/*` (R-ARCH1). Determinism comes from three things. Recorded process outcomes and
an injected clock seam are carried over from earlier changes. The hermetic process
environment is new: a spawned tool sees only the variables its test declares. That third
one is the direct remedy for the finding that made one parity property depend on who ran
it. This is a stated substitution, not a waiver.

## Type Strategy — Invalid-State Prevention

| Invariant | Tier | Mechanism | Justification |
|-----------|------|-----------|---------------|
| A program name is never read from an unclassified string | **Impossible** | `InvocationName` constructed only from `InvocationSource` | Reading the archive's file name as a tool name is the defect |
| An archive source carries no tool name | **Impossible** | `Archive(path)` holds a path, not a subcommand | Otherwise the defect is representable again |
| A test environment never inherits wholesale | **Impossible** + **static rule** | `HermeticEnv` has a private constructor and no inherit factory; a lint forbids raw process construction in tests | The inherited-variable leak is the finding |
| A test never names a change's active path literally | **Static rule** | `.scalafix.conf` block; tier-justified: a path is a string literal, which the type system cannot forbid | Two changes in a row broke on archiving |
| An absent change location says where it looked | **Impossible** | `ChangeLocation.Absent(searched)` requires the list | An unlocatable fixture must be diagnosable |
| An oracle sanction always cites a requirement | **Impossible** | `OracleSanction` requires spec and requirement | A citation-free sanction is a rubber stamp |
| A sanction cites a requirement that names its test | **Rejected by validator** | the guard reads the cited requirement's text; tier-justified: whether a paragraph mentions a file is a property of prose, not of a value | Prevents appending sanctions to pass the guard |
| A passing guard verdict names its baseline | **Impossible** | `SanctionVerdict.AllSanctioned` carries `OracleBaseline` | A verdict with an implicit baseline cannot be reproduced |
| An unreadable guard input is never a pass | **Impossible** | the third variant `Undeterminable`; the kernel's contract | Same boundary the correctness verdict enforces |
| An absent tool name is distinct from a read-only one | **Impossible** | `ToolNameSource.Absent` vs `Supplied(name)` | The empty string is how absence became invisible |
| A release check always compares toolchains | **Impossible** | validation requires the tested `ToolchainIdentity` | Otherwise an untested binary can ship |
| A ported classification is earned by a route | **Impossible** | `Ported(subcommand, LiveRoute)`; no string constructor | Name-only classification is the finding |
| The removed subcommand is unparseable | **Impossible** | `Metals` is removed from the enumeration | Unrecognised, not recognised-and-silent |
| A registry run's pass is derived | **Impossible** | `passed` is computed from the verdicts | A settable pass flag can disagree with its verdicts |
| A stale verdict names its token | **Impossible** | `Stale(token)` requires it | An anonymous finding cannot be fixed |
| Every controlled environment variable goes through the alias table | **Static rule** | `.scalafix.conf` block on main sources; tier-justified: `env.get(<string>)` is expressible whatever the types | Four variables escaped the last rename by being read directly |
| An alias has a closing version | **Impossible** | `LegacyAlias.closesAt` is required | An alias that never closes is not a deprecation |
| A seam is never a free string | **Impossible** | `SeamConfiguration` typed by the seam enum | A string seam can name a non-seam |

No invariant sits at Risky or Bad. The three tier-justified placements (two static rules and
one validator) each state why a type cannot carry them.

## Refined Type Strategy

The probatio modules do not use Iron (R-ARCH1 keeps it off the classpath). The equivalent
discipline is closed enumerations, private constructors and opaque types with smart
constructors, as the existing probatio code does.

### New Refined Types

| Type | Underlying | Constraint | Why |
|------|-----------|------------|-----|
| `OracleBaseline` | opaque over `String` | resolves to a commit (checked at construction by the adapter) | A baseline that does not resolve is the undeterminable case, not a silent default |
| `ToolchainIdentity.version` | opaque over `String` | non-empty, read from the binary | An empty identity would compare equal to another empty one |

### Types Kept as Plain

| Type | Why |
|------|-----|
| `InvocationSource`, `ToolNameSource`, `LiveRoute`, `BindingVerdict`, `SanctionVerdict`, `OracleTestKind`, `ChangeLocation` | Closed enumerations; the sealing is the constraint |
| `RegistryRow`, `RegistryReport`, `OracleSanction`, `LegacyAlias`, `SchemaAlias` | Aggregates; their constraints are required fields and derived members |

## IDL Model Layout

Not applicable — no API operation or IDL service. Wire formats are the three `.jq` contracts,
the graph export, the new sanction record and the verifier's report lines, covered under
Compatibility Story.

## Error Strategy

### Error Modeling

The three-way outcome (`Ran`, `Finding`, `Undetermined`) stays the only error algebra. This
change adds no fourth case. It extends the could-not-determine boundary to three places that
lacked it:

- the registry verifier on an unreadable input — the one place the port differs from its
  predecessor, and only on inputs the predecessor cannot read;
- the oracle guard on an unreadable baseline, history or sanction record;
- the release check on an unreadable toolchain identity.

`CliError` is unchanged. An archive invocation is no longer an error: it is a dispatch
classification, exactly as the event-name fallback became one in the previous change.

### Error Propagation

| Boundary | Discipline |
|----------|-----------|
| Core → adapter | `Outcome[A]` or a named `Either` left; never throws, never logs |
| Adapter → process | `Outcome` maps to the three termination statuses |
| Launcher → tool | a launcher that finds no built tool says so, names the locations it searched, and does not exit clean |
| Guard → suite | undeterminable is a failure of the run, never a pass |
| Unreadable input anywhere | `Undetermined` naming the input |

## Compatibility Story (Ring 4)

| Surface | How compatibility is preserved and tested |
|---------|-------------------------------------------|
| Tool output across the entrypoint split | Every observable — output, error output, termination status — compared before and after, over the acceptance suite, the subprocess conformance corpus and the parity properties' seeded inputs. Moved bodies compared byte for byte. |
| The three `.jq` contracts | Re-executed after the split; unchanged. |
| The graph export | Round-trip and predecessor agreement re-run after the split. |
| Registry verifier report lines | Line-for-line equal to the predecessor over the repository's registry and generated registries. The report lines are the wire format that CI and the session banner consume. |
| The sanction record | New format; round-trip property over titles containing quotes, em-dashes and non-ASCII characters. |
| Gate state directory | First-use migration, idempotent and lossless; state written by fixtures into the legacy directory is carried across, which is what lets six acceptance files pass unmodified. |
| Legacy environment variables | Honoured as aliases through v15; the probatio name wins when both are set; one deprecation notice per legacy read. |
| Schema directory | An alias keeps the previous name resolvable for active changes. Archived pins are not resolved by the CLI (measured), are left unedited, and are counted by one test. |

## Verification Map

| Area | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R8 | Notes |
|---|---|---|---|---|---|---|---|---|---|
| `probatio-core` decisions | ✓ | ✓ | ✓ | ✓ | ✓ | 90% | ✓ | ✓ | Three kernels extended |
| `probatio-cli` adapters | ✓ | ✓ | ✓ | ✓ | ✓ | 80% | — | ✓ | Scored per subcommand file from `entrypoint-split` on |
| `probatio-plugin` | ✓ | ✓ | ✓ | ✓ | — | 80% | — | ✓ | The installed launcher |
| `probatio-packaging` | ✓ | ✓ | ✓ | ✓ | — | 80% | — | ✓ | Toolchain identity in the release check |
| Test infrastructure (`migration`, `guard`, shared support) | ✓ | ✓ | ✓ | ✓ | ✓ | 80% | ✓ | ✓ | **Test sources** — move-to-main-and-back for Ring 5 |
| `probatio-verified` | ✓ | — | ✓ | ✓ | — | — | ✓ | ✓ | Scala 3.7.2; invoke directly |
| Acceptance oracle, shape suite, CI | — | ✓ | — | ✓ | — | — | — | ✓ | shellcheck; the oracle is the acceptance instrument |

**Ring 5 rule for this change:** a score below threshold is either raised, or recorded at the
checkpoint as a failed ring. It is never dispositioned as "in-diff survivors justified".

**Ring 8 standing instruction:** every requirement is checked against the shipped artifact in
a JAR-only environment and with harness session variables set.

## Technical Decisions

### Decision: the archive is a dispatch classification, not a launcher trick

**Context.** Under `java -jar` the runtime reports the archive's file name as the program
name, and dispatch rejects it. One test works around this by symlinking the archive as
`probatio`.

**Decision.** Classify where a program name came from. An archive source dispatches exactly
as the generic name does. Launchers stay as written, and the test workaround is removed.

**Alternatives rejected.** *Put the symlink trick in the launchers*: Windows is the release
matrix's JAR-only platform, and symbolic links are disabled by default on Windows checkouts.
*Pass the name through a system property*: every launcher, and every user running
`java -jar` by hand, would have to remember it; a raw invocation would still fail.

### Decision: test isolation is enforced by a type and a lint, not by per-test hygiene

**Context.** Six acceptance files unset the harness session variable individually, and one
of nineteen process-spawning Scala suites does. The one parity property that leaked was
green at its checkpoint and red in a harness session.

**Decision.** One helper per test language builds a child environment from a fixed base plus
declared variables. A lint forbids every other way of starting a process in test code.

**Consequence.** Tests that currently pass only because of an inherited variable will fail
when isolated. That is expected. Each is triaged as a finding or a fixture fix, and recorded.

### Decision: the oracle keeps only behavioural tests, and every edit to it must be named by a spec

**Context.** Structural tests assert over a tool's source text. Every swap forces an edit to
them, and the immutability guard has been red and accepted since the previous change.

**Decision.** Structural tests move to a separate shape suite that is expected to follow the
implementation. An oracle edit is valid only if the cited spec requirement names the edited
file or test. The guard's baseline is a recorded commit, not a commit-message search.

**Why the naming rule.** A sanction list is only as good as what it costs to add an entry.
Requiring the sanctioning requirement to name the test moves the decision into a spec, where
it passes through two human gates and an adversarial review.

### Decision: split the entrypoints mechanically, before any other entrypoint edit

**Context.** 4,701 lines hold eleven entrypoints. Two of the previous change's three
mutation-threshold misses were measured against that denominator, and six specs of this change edit
entrypoints.

**Decision.** One object per file, with no edit inside a moved body, verified byte for byte.
It runs before the specs that edit entrypoints, so each of their diffs is small and each of
their mutation scores measures their own code.

**Known limit.** The gate alone is about 1,640 lines. Splitting it per tier would be an edit,
not a move, and is out of scope.

### Decision: one native toolchain, pinned

**Context.** Local builds use the plugin's unpinned default (GraalVM 22.3.1); the release
workflow installs GraalVM CE 21.0.2. Every test and the recorded latency concern the first;
a release would ship the second.

**Decision (proposed; confirmed at `delivery-verified`'s typed-contract gate).** Pin
`nativeImageVersion` to the **release** toolchain and re-establish the evidence on it: the
subprocess conformance run and the latency measurement. The alternative — downgrading the
release to the older local default — would keep the old evidence valid by shipping an older
compiler. Either way, the release check rejects a candidate whose toolchain differs from the
tested one.

### Decision: rename the schema directory with a symbolic-link alias, and leave history alone

**Context.** Measured while drafting (openspec 1.3.1): renaming breaks every active change
that pins the old name; a symbolic link from the old name restores resolution; the CLI never
resolves archived changes.

**Decision.** Move the directory with history preserved; add the link; set the project
configuration to the new name; rewrite the live references; leave the 19 archived pins as
they are.

**Known limit.** A checkout with symbolic links disabled (the Windows default) does not
create the link. The alias check reports this rather than passing. The mechanism is
re-established on the CLI version in use before the move, per its MUST-CONFIRM.

### Decision: migrate the gate state directory once, and never read two

**Context.** The state directory is renamed, and state already exists in the legacy one.

**Decision.** On first use, move legacy state into the new directory. If both exist, the new
one wins, and the legacy directory is reported rather than merged. The gate never reads two
directories: two readers would need a conflict rule, and a conflict rule is a verdict.

**Consequence.** A fixture that writes legacy state after the new directory exists is not
migrated. That is why `legacy-name-retirement` names all seven acceptance files that pin the
old name, so any such edit is sanctioned.
