# Spec: Spec-Lint Engine

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Schema | The lint checks are the schema's mechanical pre-pass; this spec ports them without altering any verdict | [schema.md](../../../../concepts/schema.md) |
| Strangler Migration Protocol | The lint tool is one of the swapped seams; its parity is the swap's acceptance criterion | [strangler-migration-protocol.md](../../../../concepts/strangler-migration-protocol.md) |

This spec does not alter either concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `LintReport` | final case class | `org.sinemenda.probatio.core` |
| `RequirementVerdict` | final case class | `org.sinemenda.probatio.core` |
| `Verdict` | enum (Bound, Resolved, Unbound) | `org.sinemenda.probatio.core` |
| `CheckId` | enum (F1–F10) | `org.sinemenda.probatio.core` |
| `LintWarning` | final case class | `org.sinemenda.probatio.core` |
| `Outcome[+A]` | enum | `org.sinemenda.probatio.core` |
| `DriftScan` | object | `org.sinemenda.probatio.core` |
| `StdoutRenderer[A]` | trait | `org.sinemenda.probatio.cli` |
| `SubcommandWiring` | object | `org.sinemenda.probatio.cli` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `SpecDocument` | final case class | A parsed spec document: its requirement, property and temporal blocks, its obligation rows, its declared sections, each with source line numbers |
| `RequirementBlock` | final case class | One `### Requirement:` heading with its title, source line, body text, and its scenario headings |
| `PropertyBlock` | final case class | One `### Property:` heading with its title, source line, and whether it declares a generator strategy |
| `TemporalBlock` | final case class | One `### Temporal:` heading with its title, source line, and whether it declares trigger and response events |
| `ObligationRow` | final case class | One Proof-Obligations data row: its source cell, enforcement cell, artifact cell, and source line |
| `ObligationSource` | enum (`ByTitle`, `ByOrdinal`, `Typed`, `Unresolvable`) | The resolved form of an obligation's Source cell — the input to the reachability and existence checks |
| `CheckOutcome` | enum (`Pass`, `Fail`, `Warn`) | One check's result, carrying its identifier, source line, and message. Total: a check that did not run is not representable as `Pass` |
| `LintContext` | final case class | The applicability facts a lint run reads from the repository, as data |

## ADDED Requirements

### Requirement: The lint engine reproduces the predecessor's verdict on every fixture

For every spec document in the fixture corpus, the engine SHALL produce the same
set of failure identifiers, the same set of warning identifiers, and the same
source line for each, as the predecessor implementation produces for that
document.

**Given** a spec document and the predecessor's recorded output for it
**When** the engine lints that document
**Then** the engine's failure identifiers with their lines, and its warning
identifiers with their lines, are equal as sets to the predecessor's

**Rationale**: The feature freeze forbids extending checks or altering verdicts.
Equality against the predecessor on a fixture corpus is the only statement of
that freeze which can be mechanically checked; a hand-written expectation would
be free to encode the port's behaviour rather than the predecessor's.

#### Scenario: Happy path — a clean document produces no failures

**Given** a spec document with one requirement carrying a normative obligation
word, one scenario, and one obligation row naming that requirement by its exact
title
**When** the engine lints it
**Then** no failure is reported

#### Scenario: Error path — a requirement named by no obligation is reported unenforced

**Given** a spec document with two requirements and an obligation table whose rows
name only the first
**When** the engine lints it
**Then** a reachability failure is reported, naming the second requirement's title
and its source line

#### Scenario: Edge case — an obligation whose source names nothing resolvable

**Given** an obligation row whose source cell reads only the bare word for a
requirement, with no identifier following it
**When** the engine lints it
**Then** a source-resolution failure is reported at that row's line, and the row
is not counted toward any requirement's reachability

#### Scenario: Adversarial — a typed source naming a heading that does not exist

**Given** an obligation row whose source cell names a property by a title that
appears nowhere in the document
**When** the engine lints it
**Then** an existence failure is reported at that row's line naming the dangling
title; the row does not resolve to the similarly-named property that does exist

### Requirement: A check that could not be evaluated is never reported as passing

The engine SHALL represent every check's result as passed, failed, or warned, and
SHALL NOT represent an unevaluated check as passed.

**Given** a check whose applicability depends on a repository fact
**When** that fact could not be read
**Then** the run reports that it could not be determined, and the exit status is
the could-not-determine status — not the clean status

**Rationale**: "N/A", "passes" and "already handled" are claims, not verdicts.
The single most common way a lint engine manufactures a false clean result is by
letting an unevaluated conditional check default to absent, which reads as
passing.

#### Scenario: Adversarial — an unreadable repository fact yields could-not-determine, not clean

**Given** a repository whose concept registry directory exists but cannot be read
**When** the engine lints a document
**Then** the run reports could-not-determine naming the unreadable path, and the
exit status is the could-not-determine status

#### Scenario: Happy path — an absent registry makes its dependent check inapplicable, and says so

**Given** a repository with no concept registry directory
**When** the engine lints a document
**Then** the applicability facts state that the registry is absent and that the
dependent check does not apply, and the dependent check contributes no failure

#### Scenario: Edge case — a present registry makes its dependent check apply

**Given** a repository whose concept registry contains at least one concept
document, and a spec document with no section citing concepts
**When** the engine lints it
**Then** the structural half of the registry check fails, naming the missing
section

### Requirement: The applicability facts are read from the repository on every run

The engine SHALL derive its applicability facts from the repository at the moment
of the run, and SHALL NOT emit a fact it did not read.

**Given** a repository whose registry, type inventory, capability profile, and
installed instruction documents are in some state
**When** the engine reports its applicability facts
**Then** every reported presence, absence, and count corresponds to the state read
during that run

**Rationale**: The ported gate currently prints a fixed set of applicability facts
and then asserts that they were read from disk. That sentence is the workflow's
own claim-versus-evidence rule, violated by the tool that states it.

#### Scenario: Happy path — a present registry is reported present with its count

**Given** a repository whose concept registry contains a known number of concept
documents
**When** the engine reports its applicability facts
**Then** the registry is reported present with exactly that count

#### Scenario: Adversarial — a present registry is never reported absent

**Given** a repository whose concept registry contains at least one concept
document
**When** the engine reports its applicability facts
**Then** the registry is not reported absent under any circumstance, including
when the run is invoked from a working directory other than the repository root

#### Scenario: Error path — an installed instruction document declaring an older schema is reported as drift

**Given** an installed instruction document whose declared schema version is lower
than the repository's schema version
**When** the engine reports its applicability facts
**Then** the drift is reported, naming the install location, the declared version,
and the repository version

### Requirement: The lint tool's caller-facing surface accepts the predecessor's invocation forms

The lint tool SHALL accept a target given as a positional path, and SHALL accept
the artifact-checking and facts-only modifiers, producing for each the output the
predecessor produces.

**Given** a caller invoking the lint tool as the predecessor is invoked
**When** the tool runs
**Then** the invocation is accepted and its output matches the predecessor's for
that invocation

**Rationale**: The ported tool currently requires two named parameters the
predecessor does not have and rejects the positional target the predecessor
requires. Any caller written against the workflow's documented surface — including
the gate and the shim — cannot drive it.

#### Scenario: Happy path — a positional change directory is linted

**Given** a change directory containing spec documents
**When** the tool is invoked with that directory as its only argument
**Then** every spec document under it is linted and the summary names the number
of documents examined

#### Scenario: Happy path — the facts-only modifier prints the applicability facts and stops

**Given** any repository
**When** the tool is invoked with the facts-only modifier
**Then** the applicability facts are printed, no document is linted, and the exit
status is clean

#### Scenario: Error path — an unreadable target is could-not-determine

**Given** a target path that does not exist
**When** the tool is invoked with it
**Then** the run reports could-not-determine naming the path, and the exit status
is the could-not-determine status

#### Scenario: Adversarial — the artifact-checking modifier must not be silently ignored

**Given** a spec document with an obligation row naming an artifact path that
matches no tracked file, and the artifact-checking modifier
**When** the tool runs
**Then** an artifact-resolution failure is reported naming that path; the modifier
does not pass through unhandled

## Properties (Ring 3)

### Property: verdict-parity-with-predecessor

**Invariant**: For every document in the fixture corpus, the engine's failure set
and warning set (identifier paired with line) equal the predecessor's.

**Generator strategy**: `genCorpusDocument` — constructive, drawn from a fixed
corpus extracted from the predecessor's own bats fixtures plus every spec document
under `openspec/specs/` and `openspec/changes/`. This is a model-based property:
the predecessor script is the model, executed as a subprocess, and the engine is
the system. Hedgehog `cover`: `has-failures` ≥ 30%, `clean` ≥ 30%,
`has-warnings-only` ≥ 10%. Constructive (corpus enumeration), not filtered.

```
forAll { (doc: CorpusDocument) =>
  val port = engine.lint(doc)
  val pred = runPredecessor(doc)
  port.failures.map(f => (f.id, f.line)).toSet == pred.failures.map(f => (f.id, f.line)).toSet &&
  port.warnings.map(w => (w.code, w.line)).toSet == pred.warnings.map(w => (w.code, w.line)).toSet
}
```

### Property: reachability-is-total

**Invariant**: For every parsed document, every requirement is either named by at
least one obligation row that resolved to it, or reported as unenforced. No
requirement is silently absent from both.

**Generator strategy**: `genSpecDocument` — constructive: builds a document from
a generated list of requirement titles (0–8), a generated list of obligation rows
whose source cells are drawn from `{exact title of a generated requirement,
ordinal, typed reference to a generated heading, typed reference to an absent
heading, bare unresolvable}`, and a generated section set. No filtering. Hedgehog
`cover`: `all-reachable` ≥ 20%, `some-unreachable` ≥ 30%, `ordinal-source` ≥ 15%,
`dangling-typed-source` ≥ 15%, `zero-requirements` ≥ 5%.

```
forAll { (doc: SpecDocument) =>
  val r = engine.lint(doc)
  doc.requirements.forall { req =>
    r.rowsResolvingTo(req).nonEmpty ^ r.failures.exists(f => f.id == Reachability && f.names(req))
  }
}
```

### Property: unmatched-rows-are-reported-never-dropped

**Invariant**: Every obligation row is accounted for — it either resolves to a
requirement, or appears in the unresolvable report. The count of resolved rows
plus unresolvable rows equals the number of data rows parsed.

**Generator strategy**: `genSpecDocument` as above, with `cover`:
`has-unresolvable-row` ≥ 30%. Constructive.

```
forAll { (doc: SpecDocument) =>
  val r = engine.lint(doc)
  r.resolvedRows.size + r.unresolvableRows.size == doc.obligationRows.size
}
```

### Property: applicability-reflects-repository

**Invariant**: For every generated repository shape, the reported applicability
facts equal the facts derived directly from that shape.

**Generator strategy**: `genRepositoryShape` — constructive: generates a temporary
directory tree with an independently chosen presence/absence and item count for
the registry, the type inventory, the capability profile, and each of the six
instruction install roots, plus a declared schema version per installed root.
Hedgehog `cover`: `registry-present` ≥ 40%, `registry-absent` ≥ 30%,
`drift-present` ≥ 20%, `no-install-anywhere` ≥ 10%.

```
forAll { (shape: RepositoryShape) =>
  val facts = reader.read(shape.root)
  facts.registryPresent == shape.registryPresent &&
  facts.registryCount == shape.registryCount &&
  facts.driftWarnings.map(_.rootPath).toSet == shape.rootsWithOlderStamp
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A `LintReport` constructed with `lintSuccess = true` while its `verdicts` list omits a requirement present in the `SpecDocument` | A clean report that does not account for every requirement is the silent-pass defect | `assertDoesNotCompile` on a smart-constructor bypass, in `SpecLintEngineSpec` |
| `CheckOutcome` pattern match omitting a case | A check result must be handled exhaustively; exhaustiveness is escalated to an error repo-wide | `assertDoesNotCompile` in `SpecLintEngineSpec` |
| A file read or `System.getenv` call inside `probatio-core`'s lint engine | Applicability facts must arrive as values from the CLI layer (R-ARCH1 placement rule) | `assertDoesNotCompile` in `SpecLintEngineSpec`, mirroring the existing `PredecessorCheck` compile-negative |

## Formal Contracts (Ring 6)

Route: **verified mirror**. The reachability fold is the decision at the centre of
this spec. The shipped code folds over case classes carrying strings; the mirror
reduces a document to `(numRequirements: BigInt, rowTargets: List[BigInt])` where
a target is a requirement index or `-1` for unresolvable.

### Contract: reachabilityFold

**Precondition** (`require`): every target is either `-1` or in `[0, numRequirements)`.

**Postcondition** (`ensuring`): the reported unenforced set and the set of
requirement indices appearing in `rowTargets` are exact complements within
`[0, numRequirements)`, and the count of rows with target `-1` equals the reported
unresolvable count.

```scala
def reachabilityFold(numRequirements: BigInt, rowTargets: List[BigInt]):
    (List[BigInt], BigInt) = {
  require(rowTargets.forall(t => t == BigInt(-1) || (t >= 0 && t < numRequirements)))
  // pure model
}.ensuring { case (unenforced, unresolvableCount) =>
  unenforced.forall(i => !rowTargets.contains(i)) &&
  unresolvableCount == rowTargets.count(_ == BigInt(-1))
}
```

**Bridge property test**: `SpecLintBridgeSpec` runs the shipped reachability fold
and the mirror on the same generated documents, mapping titles to indices.

**Delegated to Ring 3**: verdict parity with the predecessor
(`verdict-parity-with-predecessor`) is delegated — it quantifies over document
text, which has no PureScala model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Failure and warning sets match the predecessor on every corpus document | Requirement: The lint engine reproduces the predecessor's verdict on every fixture + Property: verdict-parity-with-predecessor | model-based property test against the predecessor as a subprocess | `workflow/core/src/test/scala/org/sinemenda/probatio/core/SpecLintParitySpec.scala` |
| A requirement named by no obligation is reported unenforced | Scenario: Error path — a requirement named by no obligation is reported unenforced + Property: reachability-is-total | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/SpecLintEngineSpec.scala` |
| An unresolvable source cell is reported, never counted as reachability | Scenario: Edge case — an obligation whose source names nothing resolvable + Property: unmatched-rows-are-reported-never-dropped | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/SpecLintEngineSpec.scala` |
| A typed source naming an absent heading is a failure, not a fuzzy match | Scenario: Adversarial — a typed source naming a heading that does not exist | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/SpecLintEngineSpec.scala` |
| An unevaluated check is not representable as passing | Requirement: A check that could not be evaluated is never reported as passing + Compile-Negative: CheckOutcome pattern match omitting a case | type system (`CheckOutcome` is total, exhaustiveness escalated to error) + compile-negative test | `CheckOutcome` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/CheckOutcome.scala`; `SpecLintEngineSpec` |
| An unreadable applicability fact yields could-not-determine, never clean | Scenario: Adversarial — an unreadable repository fact yields could-not-determine, not clean | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SpecLintCmdSpec.scala` |
| Reported applicability facts equal the repository's actual state | Requirement: The applicability facts are read from the repository on every run + Property: applicability-reflects-repository | property test over generated repository shapes | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/RepositoryFactsSpec.scala` |
| A present registry is never reported absent | Scenario: Adversarial — a present registry is never reported absent | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/RepositoryFactsSpec.scala` |
| An older declared schema version is reported as drift | Scenario: Error path — an installed instruction document declaring an older schema is reported as drift | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/RepositoryFactsSpec.scala` |
| The predecessor's invocation forms are accepted | Requirement: The lint tool's caller-facing surface accepts the predecessor's invocation forms + Scenario: Happy path — a positional change directory is linted | scenario tests executing the built artifact | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SpecLintCmdSpec.scala` |
| The artifact-checking modifier is not silently ignored | Scenario: Adversarial — the artifact-checking modifier must not be silently ignored | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SpecLintCmdSpec.scala` |
| The lint engine performs no file I/O and reads no environment | Compile-Negative: A file read or System.getenv call inside probatio-core's lint engine | compile-negative test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/SpecLintEngineSpec.scala` |
| The reachability fold's unenforced set is the exact complement of the resolved set | Property: reachability-is-total + Property: unmatched-rows-are-reported-never-dropped + Contract: reachabilityFold | formal contract (Ring 6) + bridge property test | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/SpecLintKernel.scala`; `workflow/core/src/test/scala/org/sinemenda/probatio/core/SpecLintBridgeSpec.scala` |
| No check was added, removed, or re-scoped relative to the predecessor | Requirement: The lint engine reproduces the predecessor's verdict on every fixture | adversarial review (Ring 8), fresh context, comparing the engine's check set against the predecessor's documented check list | Ring 8 review record in `implementation-progress.md` |
| The lint tool's bats file reaches parity with the predecessor control | Requirement: The lint engine reproduces the predecessor's verdict on every fixture | differential oracle run (see the cutover-gate spec) | `openspec/schemas/verified-scala3/tests/workflow-hygiene.bats` and `fact-extraction.bats`, run under the differential harness |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `SpecLintEngine` | object (new) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/SpecLintEngine.scala` | the F1–F10 and W1–W7 checks as pure functions over `SpecDocument` and `LintContext` |
| `SpecDocumentParser` | object (new) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/SpecDocumentParser.scala` | markdown → `SpecDocument`; pure, takes the document text as a string |
| `RepositoryFacts` reader | object (new) | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/RepositoryFactsReader.scala` | the only file-reading component; shared with the live-fact-banner spec |
| `SpecLintCmd` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | replaces the clean-report stub; flag surface changes to the predecessor's |
| `StdoutRenderer[LintReport]` | given instance | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/StdoutRenderer.scala` | must render the predecessor's exact line shapes, including the facts block |
| `scanner/spec-lint.sh.predecessor.bak` | reference implementation | `openspec/schemas/verified-scala3/scanner/` | the model for the parity property; executed as a subprocess by the parity test |
| `DriftScan.installRoots` | value | `workflow/core/src/main/scala/org/sinemenda/probatio/core/DriftScan.scala` | corrected from three roots to six (see the live-fact-banner spec) |
| `sbt "probatio-core/test"` | build step | `probatio-core` | Ring 3 |
| `sbt -J-Xmx6g ring6` | build step | `probatio-verified` | Ring 6 |
