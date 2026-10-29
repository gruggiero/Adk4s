# graph-tool-port Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
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

