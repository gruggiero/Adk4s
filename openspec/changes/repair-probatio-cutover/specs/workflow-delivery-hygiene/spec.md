# Spec: Workflow Delivery Hygiene

The workflow ships as something other projects adopt: it carries installers for three
harnesses, six install roots, and continuous-integration templates. But its live forwarding
scripts hardcode one developer's checkout path, and nothing in continuous integration runs
its own acceptance suite. Both are how a red guard and a twelve-test regression reached an
archived, approved change.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol | The protocol's **Swap** action produces the forwarding scripts this spec makes portable. The protocol's **Gate** action is what this spec wires into continuous integration so it runs without being asked. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ShimGenerator` | object (`generateShim`) | `org.sinemenda.probatio.plugin` |
| `InstallResolver` | object (`resolve`, `resolveForShim`) | `org.sinemenda.probatio.plugin` |
| `BinaryResolution` | object | `org.sinemenda.probatio.packaging` |
| `Platform` | enum | `org.sinemenda.probatio.packaging` |
| `Subcommand` | enum | `org.sinemenda.probatio.cli` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ShimTargetScope` | enum (`RepositoryRelative(fromShimToBinary)`, `AbsoluteInstall(path)`) | Whether a forwarding script resolves its target relative to its own location or to an absolute installed path. The in-repository scripts use the first; a user-level install uses the second. |

### Type-Widening Impact

No public type gains a variant that existing code matches over. `ShimTargetScope` is new
and every match over it is written in this change. The shim generator's signature changes
to take the scope, so existing call sites stop compiling; they are enumerated in
Implementation Anchors.

## ADDED Requirements

### Requirement: An in-repository forwarding script resolves its target relative to itself

A forwarding script committed to the repository SHALL resolve the tool it forwards to
relative to its own location, and it MUST NOT contain an absolute path to a particular
checkout.

**Given** a forwarding script committed to the repository
**When** its content is inspected
**Then** the target is expressed relative to the script's own location

**Rationale**: the launcher the scripts forward to already resolves relatively. The scripts
do not, so every clone, worktree and continuous-integration runner forwards to a path that
does not exist there. The workflow is meant to be adopted by other repositories; a
committed absolute path makes that impossible.

#### Scenario: Happy path — a script invoked from a fresh clone reaches the tool

**Given** a fresh clone of the repository at a different filesystem location
**When** a forwarding script is invoked
**Then** it reaches the tool and terminates with the tool's own status

#### Scenario: Happy path — a script invoked from a linked worktree reaches the tool

**Given** a linked worktree of the repository
**When** a forwarding script is invoked
**Then** it reaches the tool

#### Scenario: Adversarial — no committed script contains an absolute checkout path

**Given** every forwarding script committed to the repository
**When** their contents are inspected
**Then** none contains an absolute path beginning at a filesystem root

#### Scenario: Error path — a target that cannot be safely quoted is refused

**Given** a resolution whose target path contains a character that would change what the
script executes
**When** the script would be generated
**Then** generation is refused naming the character, and no script is written

### Requirement: The generated script states which scope it resolved

The script generator SHALL take the resolution scope explicitly, and it MUST NOT infer the
scope from the target's shape.

**Given** a generation request
**When** the script is generated
**Then** the request carried the scope explicitly

**Rationale**: inferring "this looks absolute, so it is an install" is exactly the
reasoning that produced a committed absolute path nobody noticed. Making the scope an
argument makes the choice visible at every call site.

#### Scenario: Happy path — a repository-relative request produces a relative target

**Given** a generation request carrying the repository-relative scope
**When** the script is generated
**Then** its target is relative

#### Scenario: Happy path — an install request produces an absolute target

**Given** a generation request carrying the install scope and an installed path
**When** the script is generated
**Then** its target is that absolute path

#### Scenario: Adversarial — a generation request without a scope does not compile

**Given** a caller requesting generation without stating the scope
**When** the code is compiled
**Then** compilation fails

### Requirement: Continuous integration runs the acceptance suite and the module suites

The continuous-integration configuration SHALL run the acceptance suite, the differential
comparison, and every module test suite on each change, and a configuration that runs none
of them MUST NOT be the repository's only one.

**Given** the repository's continuous-integration configuration
**When** its jobs are enumerated
**Then** the acceptance suite, the differential comparison, and every module test suite
each appear

**Rationale**: the repository's only configuration builds release binaries. Nothing runs
the suite that decides whether the workflow enforces anything, which is why a failing
guard and a twelve-test regression were both merged and archived without notice.

#### Scenario: Happy path — a change that regresses the acceptance suite fails the job

**Given** a change that makes one acceptance-suite file worse than the predecessor
**When** the continuous-integration job runs
**Then** the job fails naming that file

#### Scenario: Happy path — a change that breaks a module suite fails the job

**Given** a change that makes one module test suite fail
**When** the job runs
**Then** the job fails naming that suite

#### Scenario: Adversarial — a job that reports success on an unrun suite is not sufficient

**Given** a job configuration in which the acceptance suite step is skipped
**When** the configuration is checked
**Then** the check reports the skipped step, and the configuration does not satisfy this
requirement

### Requirement: The continuous-integration templates name the tools that exist

The shipped templates SHALL invoke only tools present in the tree, and a template MUST NOT
name a tool that has been replaced or removed.

**Given** each shipped continuous-integration template
**When** the tools it invokes are resolved against the tree
**Then** each resolves to a present tool

#### Scenario: Happy path — every template's tools resolve

**Given** each of the shipped templates in turn
**When** its invoked tools are resolved
**Then** each resolves

#### Scenario: Adversarial — a template naming a removed tool is reported

**Given** a template invoking a tool that is not present in the tree
**When** the templates are checked
**Then** the check names the template and the missing tool

## Properties (Ring 3)

### Property: shim-resolves-from-any-location

**Invariant**: for every filesystem location a repository copy is placed at, an
in-repository forwarding script invoked from that copy reaches the tool within that same
copy — never a tool in another copy.

**Generator strategy**: `genRepositoryPlacement` — constructive over placements drawn from
a closed set of shapes: a sibling directory, a nested directory, a path containing spaces,
a path containing a non-ASCII character, and a linked worktree. The repository copy is
materialised at each. Edge cases: two copies present simultaneously, to catch a script that
reaches the wrong one.

```
property("a shim resolves from any location") {
  for {
    placement <- genRepositoryPlacement.forAll
    copy       = materialise(placement)
    reached    = invokeShim(copy)
  } yield Result.assert(reached.isWithin(copy))
}
```

### Property: no-committed-script-carries-an-absolute-path

**Invariant**: for every forwarding script committed to the repository, its content
contains no absolute filesystem path.

**Generator strategy**: enumerated, not sampled — the domain is the finite set of committed
forwarding scripts, discovered at test time rather than listed, so a newly added script is
covered automatically. This finite-domain limit is stated rather than presented as sampled
coverage.

```
property("no committed script carries an absolute path") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(
    committedShims.forall(s => !containsAbsolutePath(read(s)))
  )
}
```

### Property: generation-refuses-unquotable-targets

**Invariant**: for every target path containing a character that would change what the
generated script executes, generation is refused and no script is produced.

**Generator strategy**: `genTargetPath` — constructive over a union of safe paths and paths
seeded with each unsafe character independently, so both branches are covered by
construction rather than by filtering. Edge cases: the empty path, a path of only an unsafe
character, a path with an unsafe character at the boundary.

```
property("generation refuses unquotable targets") {
  for {
    path <- genTargetPath.forAll
    out   = generate(path, scope)
  } yield Result.assert(out.isRefusal == containsUnsafeCharacter(path))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A generation request without a scope | Inferring the scope from the target's shape is how a committed absolute path went unnoticed | `assertDoesNotCompile("ShimGenerator.generateShim(resolution, subcommand)")` — the scope is a required parameter |
| A repository-relative scope carrying an absolute path | The two scopes would otherwise be interchangeable, defeating the explicit choice | `assertDoesNotCompile("ShimTargetScope.RepositoryRelative(\"/usr/local/bin/x\")")` — the variant takes a relative path type |

## Formal Contracts (Ring 6)

No formal contracts. The decisions here — which scope, whether a character is safe to
quote, which jobs a configuration declares — are small closed classifications with no
fold, recursion, or arithmetic invariant at their centre, and they are fully covered by
the compile-negative obligations and the properties above. This is a stated skip, not an
omission.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A script invoked from a fresh clone reaches the tool | Requirement: An in-repository forwarding script resolves its target relative to itself + Scenario: Happy path — a script invoked from a fresh clone reaches the tool | scenario test | `HookCutoverShimSpec` |
| A script invoked from a linked worktree reaches the tool | Requirement: An in-repository forwarding script resolves its target relative to itself + Scenario: Happy path — a script invoked from a linked worktree reaches the tool | scenario test | `HookCutoverShimSpec` |
| A shim resolves within its own copy from any location | Property: shim-resolves-from-any-location | Hedgehog property | `HookCutoverShimSpec` |
| No committed script contains an absolute checkout path | Requirement: An in-repository forwarding script resolves its target relative to itself + Scenario: Adversarial — no committed script contains an absolute checkout path + Property: no-committed-script-carries-an-absolute-path | Hedgehog property (enumerated, discovered at test time) | `PluginSourceLintSpec` |
| An unquotable target is refused | Requirement: An in-repository forwarding script resolves its target relative to itself + Scenario: Error path — a target that cannot be safely quoted is refused + Property: generation-refuses-unquotable-targets | Hedgehog property | `ShimGeneratorSpec` |
| A repository-relative request produces a relative target | Requirement: The generated script states which scope it resolved + Scenario: Happy path — a repository-relative request produces a relative target | scenario test | `ShimGeneratorSpec` |
| An install request produces an absolute target | Requirement: The generated script states which scope it resolved + Scenario: Happy path — an install request produces an absolute target | scenario test | `InstallResolverSpec` |
| A generation request without a scope is unconstructible | Requirement: The generated script states which scope it resolved + Scenario: Adversarial — a generation request without a scope does not compile + Compile-Negative: A generation request without a scope | compile-negative test | `CompileNegative` |
| A relative scope cannot carry an absolute path | Compile-Negative: A repository-relative scope carrying an absolute path | compile-negative test | `CompileNegative` |
| A regressing change fails the job | Requirement: Continuous integration runs the acceptance suite and the module suites + Scenario: Happy path — a change that regresses the acceptance suite fails the job | manual review of one deliberately regressing branch, recorded in the evidence ledger — a job's behaviour on a failing input is observed by running it, not by a unit test | `release-probatio.yml` sibling workflow, run recorded in `evidence-ledger.jsonl` |
| A broken module suite fails the job | Requirement: Continuous integration runs the acceptance suite and the module suites + Scenario: Happy path — a change that breaks a module suite fails the job | manual review of one deliberately breaking branch, recorded in the evidence ledger | same |
| A skipped acceptance step is reported | Requirement: Continuous integration runs the acceptance suite and the module suites + Scenario: Adversarial — a job that reports success on an unrun suite is not sufficient | scenario test over the configuration | `PluginSourceLintSpec` |
| Every template's tools resolve | Requirement: The continuous-integration templates name the tools that exist + Scenario: Happy path — every template's tools resolve | scenario test | `PluginSourceLintSpec` |
| A template naming a removed tool is reported | Requirement: The continuous-integration templates name the tools that exist + Scenario: Adversarial — a template naming a removed tool is reported | scenario test | `PluginSourceLintSpec` |
| The whole suite reaches control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control, run from a fresh clone | `workflow-hygiene.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The five live forwarding scripts | shell scripts | `openspec/schemas/verified-scala3/hooks/gate.sh`, `.../scanner/{chain-state,danger-scan,reconcile,spec-lint}.sh` | Each currently hardcodes an absolute path into one developer's checkout; all five are tracked in the repository |
| `bin/probatio` | launcher script | `openspec/schemas/verified-scala3/bin/probatio` | Already resolves relatively; the model for what the forwarding scripts should do |
| `ShimGenerator.generateShim` | object method | `workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin/ShimGenerator.scala` | Gains the scope parameter; the unsafe-character refusal already exists and is retained |
| `InstallResolver.resolveForShim` | object method | `.../plugin/InstallResolver.scala` | Supplies the install-scope resolution; unchanged |
| `ShimTargetScope` | new enum | `.../plugin/` | New |
| `release-probatio.yml` | CI configuration | `.github/workflows/` | The repository's only workflow; a sibling is added rather than this one being changed |
| `github-actions.yml`, `gitlab-ci.yml`, `azure-pipelines.yml` | CI templates | `openspec/schemas/verified-scala3/ci/` | Shipped for adopters; each names tools by their pre-cutover paths |
| `probatioOracleDiff` | sbt command alias | `build.sbt:714` | The differential the job invokes |
| Ring 5 note | — | `stryker4s.conf` | `ShimGenerator.scala` is in main sources and is already the file the configuration currently pins; retarget rather than add |
