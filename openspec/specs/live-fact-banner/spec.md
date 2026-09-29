# Spec: Live-Fact Banner

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Schema | The banner is the schema's per-turn statement of the invariant plus the applicability facts it depends on | [schema.md](../../../../concepts/schema.md) |

This spec does not alter the concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `BannerEngine` | object | `org.sinemenda.probatio.core` |
| `BannerInputs` | final case class | `org.sinemenda.probatio.core` |
| `BannerOutput` | final case class | `org.sinemenda.probatio.core` |
| `ActiveChangeWithChainState` | final case class | `org.sinemenda.probatio.core` |
| `InstallRootScan` | final case class | `org.sinemenda.probatio.core` |
| `DriftScan` | object | `org.sinemenda.probatio.core` |
| `DriftWarning` | sealed trait | `org.sinemenda.probatio.core` |
| `DriftScanResult` | final case class | `org.sinemenda.probatio.core` |
| `ChainStateReport` | final case class | `org.sinemenda.probatio.core` |
| `ChainStateUndetermined` | final case class | `org.sinemenda.probatio.core` |
| `LintContext` | final case class | `org.sinemenda.probatio.core` (introduced by the spec-lint-engine spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RepositoryFacts` | final case class | The facts a run reads from the repository: schema version, registry presence and count, type-inventory presence and count, capability-profile presence, detected test kit, per-install-root instruction stamps, active changes and their artifact state |

## ADDED Requirements

### Requirement: Every fact the banner states is read during the run that states it

The banner SHALL be assembled from facts read from the repository during that
invocation, and SHALL NOT state a presence, absence, count, or version it did not
read.

**Given** a repository in some state
**When** the banner is assembled and emitted
**Then** every presence, absence, count, and version in the emitted text
corresponds to what was read from that repository during that invocation

**Rationale**: The banner closes with the sentence "The lines above were READ FROM
DISK just now; they are facts, not recollection." The ported gate assembles the
banner from fixed constants and prints that sentence anyway. Verified 2026-08-29
side by side on one repository containing one concept document: the predecessor
reported the registry present with its count and named an instruction-drift
warning; the port reported the registry absent and no instruction installed. A
tool that states fabricated facts under a claim of having read them is the
workflow's own prohibition, realised inside its enforcement mechanism.

#### Scenario: Happy path — a present registry is reported present with its exact count

**Given** a repository whose concept registry contains a known number of concept
documents
**When** the banner is emitted
**Then** the registry line reports present and names exactly that count

#### Scenario: Adversarial — a present registry is never reported absent

**Given** a repository whose concept registry contains at least one concept
document, and an invocation made from a working directory other than the
repository root
**When** the banner is emitted
**Then** the registry line does not report absent

#### Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent

**Given** a repository whose concept registry directory exists but cannot be read
**When** the banner is emitted
**Then** the registry line states that the fact could not be read, and does not
state absent

#### Scenario: Edge case — a genuinely absent registry is reported absent, with its consequence

**Given** a repository with no concept registry directory
**When** the banner is emitted
**Then** the registry line reports absent and states that the dependent check does
not apply, attributed to this run rather than assumed

### Requirement: Instruction drift is scanned across every install root the workflow searches

The drift scan SHALL examine every install root the workflow searches, and SHALL
report a root whose declared schema version differs from the repository's.

**Given** the set of install roots the workflow searches
**When** the drift scan runs
**Then** each root is examined and each root with a differing declared version is
reported, naming the root, its declared version, and the repository's version

**Rationale**: The scan currently examines three roots while its own
documentation names six. The three omitted roots are the user-scoped ones, which
is where the drift on this host actually is: the predecessor reports a v13
instruction set at a user-scoped root against a v14 repository; the port reports no
instruction set installed anywhere. A drift detector that does not look where
drift lives reports clean by construction.

#### Scenario: Happy path — a user-scoped root with an older declared version is reported

**Given** an install root under the user's home directory carrying an instruction
document that declares a lower schema version than the repository's
**When** the drift scan runs
**Then** that root is reported with both versions named

#### Scenario: Adversarial — no root is silently skipped

**Given** instruction documents installed at every searched root, each declaring a
version lower than the repository's
**When** the drift scan runs
**Then** every one of them is reported — the count of reported roots equals the
count of installed roots

#### Scenario: Edge case — a root carrying a pre-rename stamp is reported as pre-rename

**Given** an install root whose instruction document carries the stamp under the
schema's former name
**When** the drift scan runs
**Then** that root is reported as carrying a pre-rename stamp, distinctly from a
version mismatch under the current name

#### Scenario: Error path — no instruction document at any root is reported as such

**Given** no instruction document at any searched root
**When** the drift scan runs
**Then** the report states that no instruction document was found in the searched
roots, and names that drift checking is therefore unavailable

### Requirement: The active-change facts carry live chain-state, not a placeholder

The banner SHALL report, for each active change, the chain-state result obtained
during that invocation, and SHALL NOT report an empty active-change list for a
repository that has active changes.

**Given** a repository with one or more active changes
**When** the banner is emitted
**Then** each active change is named, with the artifacts present, the next
artifact, and the chain-state result obtained for it during that invocation

**Rationale**: The banner's purpose is to inject the live count of claims that
outrun their evidence on every turn, so it survives context compaction. An empty
active-change list makes that count structurally zero.

#### Scenario: Happy path — an active change with unresolved requirements shows the count

**Given** a repository with one active change whose chain-state reports unresolved
requirements
**When** the banner is emitted
**Then** the change is named and the unresolved count appears paired with its label

#### Scenario: Error path — a change whose chain-state cannot be determined says so

**Given** a repository with one active change whose ledger cannot be read
**When** the banner is emitted
**Then** the change is named and its chain-state is reported as undetermined with
its reason, not as zero unresolved

#### Scenario: Adversarial — an archived change is not reported as active

**Given** a repository whose archive contains changes and whose active set is empty
**When** the banner is emitted
**Then** no archived change is named as active

### Requirement: Repeated injections within one session are suppressed only when the underlying facts are unchanged

The banner SHALL be suppressed on a repeat injection within the same session if and
only if the facts it would state are unchanged since the last injection in that
session.

**Given** two injections within one session
**When** the second is evaluated
**Then** it is suppressed if the facts are unchanged and emitted if any fact
changed

**Rationale**: Injecting on every turn is what makes the banner survive context
compaction; suppressing unchanged repeats is what keeps it from becoming noise.
Suppression keyed on anything other than the facts — a counter, a timestamp —
would hide a change the reader needs.

#### Scenario: Happy path — an unchanged repeat is suppressed

**Given** a session that has injected once, and a repository whose facts are
unchanged
**When** a second injection is evaluated
**Then** nothing is emitted

#### Scenario: Adversarial — a changed fact defeats suppression

**Given** a session that has injected once, and a repository in which one fact has
since changed
**When** a second injection is evaluated
**Then** the banner is emitted

#### Scenario: Edge case — a different session is not suppressed by another session's state

**Given** one session that has injected, and a second, distinct session
**When** the second session's first injection is evaluated
**Then** the banner is emitted

## Properties (Ring 3)

### Property: facts-reflect-repository

**Invariant**: For every generated repository shape, every field of the read facts
equals the corresponding property of that shape.

**Generator strategy**: `genRepositoryShape` — constructive: materialises a
temporary directory tree with independently chosen registry presence and document
count (0–12), type-inventory presence, capability-profile presence, per-root
instruction presence and declared version (including pre-rename stamps), and an
active-change set (0–4) with generated artifact subsets. No filtering. Hedgehog
`cover`: `registry-present` ≥ 40%, `registry-absent` ≥ 25%, `drift-at-user-root` ≥ 20%,
`pre-rename-stamp` ≥ 10%, `no-install-anywhere` ≥ 10%, `has-active-changes` ≥ 40%.

```
forAll { (shape: RepositoryShape) =>
  val f = RepositoryFactsReader.read(shape.root)
  f.registryPresent == shape.registryPresent &&
  f.registryConceptCount == shape.registryCount &&
  f.inventoryPresent == shape.inventoryPresent &&
  f.profilePresent == shape.profilePresent &&
  f.activeChanges.map(_.name).toSet == shape.activeChangeNames
}
```

### Property: banner-states-only-read-facts

**Invariant**: For every generated repository shape, every presence word, absence
word, and integer count appearing in the emitted banner text is derivable from the
facts read for that shape. No literal presence or absence claim appears that the
facts do not support.

**Generator strategy**: `genRepositoryShape` as above. This is the anti-fabrication
property: the assertion extracts every claim token from the rendered text and
checks it against the facts record. Hedgehog `cover`: `registry-present` ≥ 40%,
`registry-absent` ≥ 25% — both arms are required, because a renderer that always
says "absent" passes a present-only corpus.

```
forAll { (shape: RepositoryShape) =>
  val f = RepositoryFactsReader.read(shape.root)
  val text = BannerEngine.render(BannerInputs.from(f)).payload
  extractClaims(text).forall(claim => f.supports(claim))
}
```

### Property: every-searched-root-is-scanned

**Invariant**: For every generated install-root configuration, the number of roots
the scan reports on equals the number of roots the workflow searches, and every
root with a differing declared version appears in the warnings.

**Generator strategy**: `genInstallRootConfig` — constructive: for each of the
searched roots independently, choose absent / present-at-current-version /
present-at-older-version / present-with-pre-rename-stamp. Hedgehog `cover`:
`all-absent` ≥ 8%, `all-present` ≥ 8%, `mixed` ≥ 50%, `user-scoped-drift` ≥ 25%.

```
forAll { (cfg: InstallRootConfig) =>
  val res = DriftScan.scan(cfg.schemaVersion, cfg.scans)
  cfg.scans.length == DriftScan.installRoots.length &&
  res.warnings.map(_.rootPath).toSet == cfg.rootsWithDifferingVersion
}
```

### Property: suppression-tracks-facts

**Invariant**: For every pair of successive injections in one session, the second
is suppressed if and only if the facts are equal.

**Generator strategy**: `genFactsPair` — constructive: generates one facts record,
then either returns it unchanged (equal arm) or mutates exactly one field (changed
arm), with the arm chosen 50/50 by construction rather than by filtering. Hedgehog
`cover`: `equal` ≥ 40%, `changed` ≥ 40%, and within `changed`, each mutated field
≥ 5%.

```
forAll { (pair: (RepositoryFacts, RepositoryFacts), s: SessionId) =>
  suppressSecond(s, pair._1, pair._2) == (pair._1 == pair._2)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `BannerInputs` constructed from literals rather than from a `RepositoryFacts` value | Constructing the banner's inputs by hand is exactly how the fabricated banner arose; the only public constructor takes a facts record | `assertDoesNotCompile` in `LiveFactBannerSpec` |
| A file read inside `BannerEngine` | The renderer must stay pure; reading belongs to the CLI layer (R-ARCH1 placement rule) | `assertDoesNotCompile` in `LiveFactBannerSpec` |
| `DriftScan.installRoots` referenced as a list of fewer roots than the workflow searches | A silently narrowed root list is the drift-blindness defect | compile-negative on a fixed-arity accessor, in `LiveFactBannerSpec` |

## Formal Contracts (Ring 6)

Route: **verified mirror**. `BannerEngineKernel` already exists in
`probatio-verified` and is extended. The mirror reduces the facts record to a
vector of `BigInt` codes (`-1` unreadable, `0` absent, `n>0` present with count `n`)
and the banner to the vector of claims it emits.

### Contract: bannerClaims

**Precondition** (`require`): every fact code is `>= -1`.

**Postcondition** (`ensuring`): the emitted claim vector has the same length as the
fact vector, and each emitted claim equals the corresponding fact code — no claim
is emitted for an unreadable fact as though it were absent.

```scala
def bannerClaims(facts: List[BigInt]): List[BigInt] = {
  require(facts.forall(_ >= -1))
  // pure model
}.ensuring { claims =>
  claims.length == facts.length &&
  claims.zip(facts).forall { case (c, f) => c == f } &&
  claims.zip(facts).forall { case (c, f) => (f == BigInt(-1)) ==> (c != BigInt(0)) }
}
```

**Bridge property test**: `BannerBridgeSpec` runs the shipped renderer's claim
extraction and the kernel on the same generated fact vectors.

**Delegated to Ring 3**: suppression and the install-root scan are delegated —
`suppression-tracks-facts` and `every-searched-root-is-scanned` cover them.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Read facts equal the repository's state | Requirement: Every fact the banner states is read during the run that states it + Property: facts-reflect-repository | property test over generated repository shapes | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/RepositoryFactsSpec.scala` |
| Every claim in the emitted banner is supported by a read fact | Property: banner-states-only-read-facts | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| A present registry is never reported absent | Scenario: Adversarial — a present registry is never reported absent | scenario test executing the built artifact from a non-root working directory | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| An unreadable fact is reported unreadable, not absent | Scenario: Error path — a fact that cannot be read is reported as unreadable, not as absent + Contract: bannerClaims | scenario test + formal contract (Ring 6) | `LiveFactBannerSpec`; `verified/probatio/src/main/scala/org/sinemenda/probatio/core/BannerEngineKernel.scala` |
| An absent registry is reported absent with its consequence attributed to this run | Scenario: Edge case — a genuinely absent registry is reported absent, with its consequence | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| Banner inputs cannot be hand-constructed | Compile-Negative: BannerInputs constructed from literals rather than from a RepositoryFacts value | smart constructor + compile-negative test | `BannerInputs` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/BannerEngine.scala`; `LiveFactBannerSpec` |
| The renderer performs no file I/O | Compile-Negative: A file read inside BannerEngine | compile-negative test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| Every searched install root is scanned | Requirement: Instruction drift is scanned across every install root the workflow searches + Property: every-searched-root-is-scanned | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DriftScanSpec.scala` (existing suite, extended) |
| No install root is silently skipped | Scenario: Adversarial — no root is silently skipped | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DriftScanSpec.scala` |
| The root list cannot be narrowed without failing to compile | Compile-Negative: DriftScan.installRoots referenced as a list of fewer roots than the workflow searches | compile-negative test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DriftScanSpec.scala` |
| A pre-rename stamp is reported distinctly | Scenario: Edge case — a root carrying a pre-rename stamp is reported as pre-rename | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DriftScanSpec.scala` |
| No instruction document anywhere is reported as such | Scenario: Error path — no instruction document at any root is reported as such | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| Active changes carry live chain-state | Requirement: The active-change facts carry live chain-state, not a placeholder + Scenario: Happy path — an active change with unresolved requirements shows the count | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| An undetermined chain-state is not reported as zero unresolved | Scenario: Error path — a change whose chain-state cannot be determined says so | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| An archived change is not reported active | Scenario: Adversarial — an archived change is not reported as active | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| Suppression happens exactly when the facts are unchanged | Requirement: Repeated injections within one session are suppressed only when the underlying facts are unchanged + Property: suppression-tracks-facts | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| One session's state does not suppress another's | Scenario: Edge case — a different session is not suppressed by another session's state | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LiveFactBannerSpec.scala` |
| The banner's bats file reaches parity with the predecessor control | Requirement: Every fact the banner states is read during the run that states it | differential oracle run (see the cutover-gate spec) | `openspec/schemas/verified-scala3/tests/gate-payload.bats` under the differential harness |
| No banner line states a fact the run did not read | Requirement: Every fact the banner states is read during the run that states it | adversarial review (Ring 8), fresh context, diffing the emitted banner against the predecessor's on the same repository | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `RepositoryFactsReader` | object (new) | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/RepositoryFactsReader.scala` | the single file-reading component; shared with the spec-lint-engine spec's `LintContext` |
| `BannerInputs.from` | method (new) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/BannerEngine.scala` | the only public way to build banner inputs; the raw constructor becomes private |
| `BannerEngine.render` | method | same file | unchanged in behaviour; the inventory row for it was corrected to this name on 2026-08-29 |
| `DriftScan.installRoots` | value | `workflow/core/src/main/scala/org/sinemenda/probatio/core/DriftScan.scala` | corrected from three roots to the six the workflow searches, matching its own scaladoc |
| `GateCmd.runEvent` | method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | stops constructing `BannerInputs` from literals |
| `BannerEngineKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/BannerEngineKernel.scala` | extended with the unreadable-fact clause |
| `hooks/gate.sh.predecessor.bak` | reference implementation | `openspec/schemas/verified-scala3/hooks/` | the model for banner content and suppression |
| `sbt "probatio-core/test" "probatio-cli/test"` | build step | both modules | Ring 3 |
