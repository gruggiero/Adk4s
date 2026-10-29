# Spec: Graph Tool Port

The workflow's artifact traceability graph is the one tool that answers reachability
questions — does every requirement reach an enforcement artifact? — that neither text
search nor a compiler index can answer, because its nodes are workflow artifacts rather
than code. It is the last predecessor implementation on the correctness path that the port
does not cover, and the correctness verdict tool depends on it.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | This spec adds a seam the migration did not previously have: the traceability tool moves from its predecessor implementation to the ported one. The protocol's **Swap** and **Gate** actions apply to it unchanged. | `openspec/concepts/strangler-migration-protocol.md` |
| `Conformance` (Conformance Property-Test Contract) | The ported tool's exported graph must agree with the predecessor's on the repository's own corpus — the same bidirectional-equivalence discipline the ported validators already carry. | `openspec/concepts/conformance-property-test-contract.md` |

This spec does not alter either concept's purpose, actions, state, or synchronizations.
Whether the traceability graph warrants its own behavioural concept file is flagged for
the human gate; this spec does not create one.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `SpecDocument` | final case class | `org.sinemenda.probatio.core` |
| `SpecDocumentParser` | object | `org.sinemenda.probatio.core` |
| `LintReport` | final class, private constructor | `org.sinemenda.probatio.core` |
| `RequirementVerdict` | final case class | `org.sinemenda.probatio.core` |
| `CheckOutcome` | enum | `org.sinemenda.probatio.core` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `Subcommand` | enum | `org.sinemenda.probatio.cli` |
| `HelpRegistry` | object | `org.sinemenda.probatio.cli` |
| `StdoutRenderer[A]` | typeclass | `org.sinemenda.probatio.cli` |
| `ChainState` | object | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `GraphNode` | sealed hierarchy (`Concept`, `Action`, `Sync`, `TypeEntry`, `Spec`, `Requirement`, `Obligation`, `Artifact`, `Code`) | The nine node kinds the traceability graph ranges over. |
| `GraphEdge` | enum (`Declares`, `DefinesSync`, `ImplementedBy`, `Cites`, `Uses`, `Introduces`, `HasRequirement`, `EnforcedBy`, `VerifiedBy`) | The nine edge kinds. |
| `TraceabilityGraph` | final case class (nodes, edges, unlinkable) | The graph. `unlinkable` is a required field holding every row the parsers could not bind, so a graph that hides what it could not read is unconstructible. |
| `GraphQuery` | enum (`Export`, `Stats`, `Impact(target)`, `Obligations(change)`, `ConceptCode(concept)`) | The five ported operations as a closed set. |
| `ReachabilityResult` | final case class (reaching, unenforcedRequirements, artifactlessObligations) | The obligations audit's output: which requirements reach an enforcement artifact and which do not. |
| `ConceptRegistryDoc` | final case class + parser | The parsed behavioural registry: concepts, actions, syncs, implementation-map rows. |
| `InventoryDoc` | final case class + parser | The parsed type inventory: rows of concept, kind, package, provenance. |
| `UnlinkableRow` | final case class (source, line, text, reason) | One row a parser read but could not bind to a node, with the reason it could not. |

`Subcommand` is **modified**: it regains the traceability-tool case, which the entrypoint
contract removed when the tool had no implementation. That removal's rule — a tool with no
implementation is not nameable on the tool surface — is satisfied rather than violated,
because this spec supplies the implementation.

### Type-Widening Impact

`Subcommand` gains one case. Every match over it must handle it; the probatio modules
compile with exhaustiveness escalated to an error, so an unhandled case fails Ring 0. The
known matches are enumerated in Implementation Anchors. None may absorb the new case into
a catch-all: a tool silently classified as "some other subcommand" is how the previous
surface shipped tools that returned success having done nothing.

## ADDED Requirements

### Requirement: The graph is built from the three declared sources

The traceability graph SHALL be built from the behavioural registry, the type inventory,
and the active changes' specs, and it MUST NOT read the archived changes.

**Given** a repository carrying a behavioural registry, a type inventory, and one or more
active changes
**When** the graph is built
**Then** it contains nodes drawn from all three sources, and no node drawn from an
archived change

**Rationale**: the archive holds every historical spec. Including it would make every
reachability answer a statement about history rather than about the work in flight, and
would grow the graph without bound.

#### Scenario: Happy path — all three sources contribute nodes

**Given** a repository with a registry, an inventory, and one active change carrying specs
**When** the graph is built
**Then** it contains concept nodes, type nodes, and specification nodes

#### Scenario: Adversarial — an archived change contributes no nodes

**Given** a repository whose archive contains a change with specs, and one active change
**When** the graph is built
**Then** no node originates from the archived change

#### Scenario: Error path — an unreadable source is could-not-determine

**Given** a repository whose type inventory cannot be read
**When** the graph is built
**Then** the outcome is could-not-determine naming the inventory, and no partial graph is
emitted

### Requirement: A row that cannot be bound is reported, never dropped

Every row a parser reads but cannot bind to a node SHALL appear in the graph's unlinkable
set with a stated reason, and a parser MUST NOT discard an unbindable row silently.

**Given** a source containing a row whose referenced target does not resolve
**When** the graph is built
**Then** the graph's unlinkable set contains that row with its source, line, text and the
reason it could not be bound

**Rationale**: a graph that silently drops what it could not parse answers every
reachability question optimistically — the unreachable requirement is simply missing from
the answer. This is the confidently-wrong failure mode the tool exists to detect, and the
predecessor's own documentation names it.

#### Scenario: Happy path — a resolvable row binds and does not appear as unlinkable

**Given** a source whose every row resolves
**When** the graph is built
**Then** the unlinkable set is empty

#### Scenario: Adversarial — an unresolvable row appears with its reason

**Given** an implementation-map row citing a symbol that exists nowhere in the tree
**When** the graph is built
**Then** the unlinkable set contains that row, naming its source file, its line, and the
unresolved symbol

#### Scenario: Adversarial — a graph cannot be built without its unlinkable set

**Given** a caller assembling a graph from nodes and edges alone
**When** the code is compiled
**Then** compilation fails: the graph's constructor requires the unlinkable set

### Requirement: The obligations audit reports every requirement that reaches no artifact

The obligations audit SHALL report each requirement that has no path to an enforcement
artifact and each obligation that names no artifact, and it MUST NOT report a requirement
as enforced on the strength of an obligation whose artifact does not resolve.

**Given** a change whose specifications carry requirements and obligations
**When** the obligations audit runs
**Then** it reports the requirements reaching an enforcement artifact, the requirements
reaching none, and the obligations naming no artifact

#### Scenario: Happy path — a fully enforced change reports no unenforced requirement

**Given** a change in which every requirement is named by an obligation whose artifact
resolves
**When** the audit runs
**Then** the unenforced list is empty and the artifactless list is empty

#### Scenario: Adversarial — a requirement named only by an artifactless obligation is unenforced

**Given** a change with a requirement named by exactly one obligation, whose artifact does
not resolve to a tracked file
**When** the audit runs
**Then** that requirement appears in the unenforced list

#### Scenario: Adversarial — a requirement named by no obligation is unenforced

**Given** a change with a requirement that no obligation names
**When** the audit runs
**Then** that requirement appears in the unenforced list

#### Scenario: Edge case — a change with no requirements reports empty lists, not an error

**Given** a change whose specifications carry no requirements
**When** the audit runs
**Then** both lists are empty and the outcome is ran-and-found-nothing

### Requirement: The exported graph agrees with the predecessor on the repository corpus

The exported graph SHALL contain the same node set and the same edge set as the
predecessor implementation's export for the same repository state.

**Given** the repository's own registry, inventory and active changes
**When** both the ported tool and the predecessor export the graph
**Then** the two node sets are equal and the two edge sets are equal

**Rationale**: this is a port. The predecessor is the specification of what the graph
contains, and it is executable, so agreement is measurable rather than asserted.

#### Scenario: Happy path — the two exports agree on the repository corpus

**Given** the repository at the change's baseline
**When** both implementations export
**Then** the node sets and edge sets are equal

#### Scenario: Adversarial — a disagreement is reported per node, not summarised

**Given** a corpus on which the two exports differ
**When** the comparison runs
**Then** it names each differing node and edge rather than reporting only a count

### Requirement: The correctness verdict consumes the graph and states when it cannot

The correctness verdict tool SHALL obtain its requirement set from the traceability graph,
and when the graph cannot be produced it MUST state that it is operating without it rather
than silently narrowing its input.

**Given** a change whose graph can be produced
**When** the correctness verdict is computed
**Then** the verdict's requirement set is drawn from the graph

**Given** a change whose graph cannot be produced
**When** the correctness verdict is computed
**Then** the output states that the graph was unavailable and names the reason

**Rationale**: the predecessor consumes the graph and emits a degraded-mode trace when it
cannot. The port does neither: it neither consumes the graph nor says it is not consuming
it. Porting the tool removes the predecessor's stated cause for degradation — an absent
script interpreter — but not the condition: the graph can still fail to build because a
source is unreadable.

#### Scenario: Happy path — an available graph supplies the requirement set

**Given** a change whose three sources are readable
**When** the correctness verdict is computed
**Then** the requirement set comes from the graph and no degraded-mode statement appears

#### Scenario: Adversarial — an unavailable graph produces a stated degradation, not silence

**Given** a change whose behavioural registry cannot be read
**When** the correctness verdict is computed
**Then** the output states that the graph was unavailable and names the registry, and the
output is not a verdict computed from a narrowed requirement set

### Requirement: The tool surface names the five operations

The tool SHALL accept exactly the five operations the predecessor accepts, and it MUST NOT
accept an operation name that has no implementation.

**Given** the tool invoked with each of the five operation names in turn
**When** the operation is dispatched
**Then** each runs its own operation

**Given** the tool invoked with a name outside the five
**When** the operation is dispatched
**Then** the invocation is rejected naming the unknown operation

#### Scenario: Happy path — each of the five operations dispatches

**Given** each of the five operation names in turn
**When** the tool is invoked
**Then** each dispatches to its own operation

#### Scenario: Adversarial — an unimplemented operation name is rejected

**Given** an operation name that the predecessor does not accept
**When** the tool is invoked
**Then** the invocation is rejected naming that operation, and the tool does not terminate
with the clean status

#### Scenario: Consumer surface — the help output names the five operations and their arguments

**Given** the tool invoked for help
**When** the help output is produced
**Then** it names each of the five operations, the argument each requires, and the three
termination statuses

## Properties (Ring 3)

### Property: unlinkable-rows-are-conserved

**Invariant**: for every source corpus, the number of rows read equals the number of rows
bound to nodes plus the number of rows in the unlinkable set — no row is lost.

**Generator strategy**: `genSourceCorpus` — constructive. Builds registry, inventory and
spec documents from a closed alphabet of row shapes: resolvable rows, rows citing missing
symbols, rows with malformed syntax, and rows citing archived changes. Sizes 0–12 rows per
source. Edge cases: an empty source, a source of only unlinkable rows, a source of only
resolvable rows.

```
property("no row is lost between reading and binding") {
  for {
    corpus <- genSourceCorpus.forAll
    graph   = build(corpus)
  } yield Result.assert(
    corpus.rowCount == graph.boundRowCount + graph.unlinkable.length
  )
}
```

### Property: reachability-is-transitive-and-grounded

**Invariant**: for every graph, a requirement is reported as reaching an enforcement
artifact if and only if there is a path from it through obligations to a node of artifact
kind — reachability is the transitive closure, and it never terminates on an obligation.

**Generator strategy**: `genGraph` — constructive over node/edge sets built by generating
a requirement count 0–10, then for each requirement independently choosing a chain shape
from {no obligation, obligation without artifact, obligation with artifact, two
obligations one of which has an artifact, a cycle}. Building by chain shape rather than by
random edges guarantees each reachability case is covered by construction.

```
property("reachability is the transitive closure to an artifact node") {
  for {
    graph <- genGraph.forAll
    result = audit(graph)
  } yield Result.assert(
    result.reaching.toSet ==
      graph.requirements.filter(r => graph.pathsFrom(r).exists(_.endsAtArtifact)).toSet
  )
}
```

### Property: export-round-trips

**Invariant**: for every graph, exporting it to the wire format and reading it back yields
an equal graph — node set, edge set and unlinkable set all preserved.

**Generator strategy**: `genGraph` as above, extended with unlinkable rows drawn from
`genUnlinkableRow`. Edge cases: the empty graph, a graph with only unlinkable rows, node
labels containing quotes, newlines and non-ASCII characters.

```
property("export round-trips") {
  for {
    graph <- genGraph.forAll
    back   = readExport(writeExport(graph))
  } yield Result.assert(back == graph)
}
```

### Property: export-agrees-with-the-predecessor

**Invariant**: for the repository's own corpus, the ported export's node and edge sets
equal the predecessor export's. The predecessor is executed as the model.

**Generator strategy**: the corpus is the repository itself — a fixed, constructive corpus
rather than a sampled one, since the requirement is agreement on the real sources. The
property additionally runs over `genSourceCorpus` fixtures materialised into temporary
repositories, so agreement is tested beyond the single real corpus. Determinism: the
predecessor runs as a subprocess and only its output is compared; no timing is observed.

```
property("export agrees with the predecessor") {
  for {
    corpus <- genSourceCorpus.forAll
    ported  = exportPorted(corpus)
    model   = exportPredecessor(corpus)
  } yield Result.assert(ported.nodes == model.nodes && ported.edges == model.edges)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A graph built without its unlinkable set | A graph that can omit what it could not parse answers reachability optimistically | `assertDoesNotCompile("TraceabilityGraph(nodes, edges)")` — the constructor requires the third field |
| An unlinkable row without a reason | A row recorded as unbindable without saying why cannot be acted on | `assertDoesNotCompile("UnlinkableRow(src, 1, \"text\")")` — the variant requires the reason |
| A node kind written as a free string | A node kind named by string can name a kind that does not exist, which silently drops it from every query | `assertDoesNotCompile("GraphNode.of(\"conceptt\", id)")` — node kinds are a sealed hierarchy with no string constructor |
| A reachability result that reports only a count | A summarised disagreement cannot be acted on and hides which requirement is unenforced | `assertDoesNotCompile("ReachabilityResult(reachingCount = 3)")` — the type carries lists, not counts |

## Formal Contracts (Ring 6)

The reachability closure is the algorithm at the centre of this spec: a transitive closure
over a finite graph, decided per requirement. It is mirrored in the verification module.

### Contract: reaches

```
def reaches(edges: List[(NodeId, NodeId)], from: NodeId,
            artifacts: List[NodeId], fuel: BigInt): Boolean = {
  require(fuel >= 0)
  require(fuel >= edges.length)   // enough fuel to traverse every edge once
  ...
} ensuring { result =>
  // grounded: a true answer means some artifact is reachable
  (result ==> artifacts.exists(a => pathExists(edges, from, a))) &&
  // complete: a reachable artifact means a true answer
  (artifacts.exists(a => pathExists(edges, from, a)) ==> result)
}
```

### Contract: auditConservation

```
def audit(graph: TraceabilityGraph): ReachabilityResult = {
  ...
} ensuring { result =>
  // every requirement is classified exactly once
  result.reaching.length + result.unenforcedRequirements.length ==
    graph.requirements.length &&
  // the two lists are disjoint
  result.reaching.forall(r => !result.unenforcedRequirements.contains(r))
}
```

Termination is by explicit fuel bounded by the edge count, per the recorded Ring 6
practice for graph traversals. A bridge property binds the shipped audit to these models.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The graph draws from all three sources | Requirement: The graph is built from the three declared sources + Scenario: Happy path — all three sources contribute nodes | scenario test | `SpecLintEngineSpec` |
| An archived change contributes no nodes | Requirement: The graph is built from the three declared sources + Scenario: Adversarial — an archived change contributes no nodes | scenario test | `SpecLintEngineSpec` |
| An unreadable source is could-not-determine | Requirement: The graph is built from the three declared sources + Scenario: Error path — an unreadable source is could-not-determine | scenario test | `SpecLintEngineSpec` |
| A resolvable corpus has an empty unlinkable set | Requirement: A row that cannot be bound is reported, never dropped + Scenario: Happy path — a resolvable row binds and does not appear as unlinkable | scenario test | `SpecLintEngineSpec` |
| An unresolvable row appears with its reason | Requirement: A row that cannot be bound is reported, never dropped + Scenario: Adversarial — an unresolvable row appears with its reason | scenario test | `SpecLintEngineSpec` |
| No row is lost between reading and binding | Property: unlinkable-rows-are-conserved | Hedgehog property | `SpecLintEngineSpec` |
| A graph cannot omit its unlinkable set | Compile-Negative: A graph built without its unlinkable set | compile-negative test | `SpecLintEngineTypeContract` |
| An unlinkable row cannot omit its reason | Compile-Negative: An unlinkable row without a reason | compile-negative test | `SpecLintEngineTypeContract` |
| A node kind cannot be a free string | Compile-Negative: A node kind written as a free string | compile-negative test | `SpecLintEngineTypeContract` |
| A fully enforced change reports no unenforced requirement | Requirement: The obligations audit reports every requirement that reaches no artifact + Scenario: Happy path — a fully enforced change reports no unenforced requirement | scenario test | `SpecLintEngineSpec` |
| An artifactless obligation does not enforce | Requirement: The obligations audit reports every requirement that reaches no artifact + Scenario: Adversarial — a requirement named only by an artifactless obligation is unenforced | scenario test | `SpecLintEngineSpec` |
| An unnamed requirement is unenforced | Requirement: The obligations audit reports every requirement that reaches no artifact + Scenario: Adversarial — a requirement named by no obligation is unenforced | scenario test | `SpecLintEngineSpec` |
| A requirementless change reports empty lists | Requirement: The obligations audit reports every requirement that reaches no artifact + Scenario: Edge case — a change with no requirements reports empty lists, not an error | scenario test | `SpecLintEngineSpec` |
| Reachability is the transitive closure to an artifact | Property: reachability-is-transitive-and-grounded | Hedgehog property | `SpecLintEngineSpec` |
| A reachability result cannot report only a count | Compile-Negative: A reachability result that reports only a count | compile-negative test | `SpecLintEngineTypeContract` |
| The export round-trips | Property: export-round-trips | Hedgehog property (Ring 4 wire format) | `ConformanceSpec` |
| The export agrees with the predecessor on the corpus | Requirement: The exported graph agrees with the predecessor on the repository corpus + Property: export-agrees-with-the-predecessor | Hedgehog model-based property (predecessor run as a subprocess) | `ConformanceSpec` |
| A disagreement is reported per node | Requirement: The exported graph agrees with the predecessor on the repository corpus + Scenario: Adversarial — a disagreement is reported per node, not summarised | scenario test | `ConformanceSpec` |
| An available graph supplies the requirement set | Requirement: The correctness verdict consumes the graph and states when it cannot + Scenario: Happy path — an available graph supplies the requirement set | bats oracle | `fact-extraction.bats` |
| An unavailable graph produces a stated degradation | Requirement: The correctness verdict consumes the graph and states when it cannot + Scenario: Adversarial — an unavailable graph produces a stated degradation, not silence | bats oracle | `fact-extraction.bats` |
| Each of the five operations dispatches | Requirement: The tool surface names the five operations + Scenario: Happy path — each of the five operations dispatches | scenario test | `CliSurfaceSpec` |
| An unimplemented operation name is rejected | Requirement: The tool surface names the five operations + Scenario: Adversarial — an unimplemented operation name is rejected | scenario test | `CliSurfaceSpec` |
| The help output names the operations and arguments | Requirement: The tool surface names the five operations + Scenario: Consumer surface — the help output names the five operations and their arguments | scenario test | `CliHelpSpec` |
| The new subcommand case is handled exhaustively | Type-Constraint: the subcommand enum gains the traceability-tool case | compiler exhaustiveness escalation (error, not warning) | Ring 0 on `probatio-core`, `probatio-cli` |
| Reachability and audit conservation are verified | Invariant: reachability is grounded, complete and conserving | Stainless verification + bridge property test | `VerifiedKernelBridgeSpec` |
| The suite file reaches control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control | `fact-extraction.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `openspec-graph.py` | predecessor implementation | `openspec/schemas/verified-scala3/scanner/openspec-graph.py` | 626 lines, 5 subcommands, 9 node kinds, 9 edge kinds. The executable model for the agreement property. Stays on disk as the revert target. |
| `TraceabilityGraph`, `GraphNode`, `GraphEdge`, `GraphQuery`, `ReachabilityResult`, `UnlinkableRow` | new types | `workflow/core/src/main/scala/org/sinemenda/probatio/core/` | New; pure, no file I/O — R-ARCH1 and the no-I/O-in-core rule both apply |
| `ConceptRegistryDoc`, `InventoryDoc` | new parsers | `.../core/` | Two of the three parsers are new; the spec parser already exists |
| `SpecDocumentParser` | object | `.../core/SpecDocumentParser.scala` | Reused as the third parser — not reimplemented |
| `GraphCmd` | new object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | The adapter: all file reading lives here |
| `Subcommand` | enum | `.../cli/Subcommand.scala:25` | Regains the traceability case. Matches over it: `cliName` (`:52`), `fromString` (`:44`), the help registry, and the wiring table — each must handle the new case |
| `ChainStateCmd` | object | `.../cli/SubcommandEntrypoints.scala:1799` | Gains the graph consumption seam and the degraded-mode statement |
| `fact-extraction.bats` | bats suite | `openspec/schemas/verified-scala3/tests/` | Tests 2, 6, 9 are the regression. Test 9 asserts the predecessor's degraded-mode wording, which names an absent script interpreter — retargeted here to the ported tool's own unavailability condition, as the interpreter is no longer involved |
| Ring 6 kernel | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/` | New reachability kernel, the tenth in the mirror |
| Ring 5 note | — | `stryker4s.conf` | Implementation is in main sources; no move procedure needed |
