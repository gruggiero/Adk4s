## Target Module
`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/`

## IMPLEMENTED Requirements

### Requirement: Fork Specification ✅
The system provides `WIOForkNode` with ordered `Branch` cases, each containing a `predicate: I => Option[I]` and a `workflow: WIO[I, Err, O, Ctx]`. The `binaryFork` helper creates a two-branch fork from a boolean condition. Cases are evaluated in order, selecting the first predicate that yields `Some(caseInput)`.

#### Scenario: Create fork with two cases ✅
- **GIVEN** two case predicates and two branch workflows
- **WHEN** a `WIOForkNode` is created (e.g. via `binaryFork`)
- **THEN** the specification preserves case order in the `branches: List[Branch]`

#### Scenario: Evaluate fork selects first matching case ✅
- **GIVEN** a `WIOForkNode` with two cases where both predicates could match
- **WHEN** the fork is evaluated at runtime via `WIO.Fork`
- **THEN** the first matching case is selected

### Requirement: Workflows4s Branch Integration ✅
The system compiles `WIOForkNode` to Workflows4s `WIO.Fork` constructs using runtime condition evaluation. `WIOGraph.compileFork` matches fork branches to outgoing graph edges by index, chains each branch with its downstream nodes via `WIO.AndThen`, and produces a `WIO.Fork`.

#### Scenario: Compile fork node to WIO fork ✅
- **GIVEN** a WIOGraph containing a `WIOForkNode`
- **WHEN** the graph is compiled via `WIOGraph.toWIO`
- **THEN** the resulting WIO uses `WIO.Fork` and runtime predicates for branch selection

### Requirement: Fork Otherwise Branch ✅
`WIOForkNode` supports an optional otherwise branch via `WIOForkNode.withOtherwise` factory method. The otherwise branch is appended as a catch-all branch with `predicate = _ => Some(_)`. During `compileFork`, trailing branches without outgoing edges use their workflow directly (not chained with downstream nodes).

#### Scenario: Evaluate fork selects otherwise ✅
- **GIVEN** a `WIOForkNode` created via `WIOForkNode.withOtherwise` where no case predicate matches
- **WHEN** the fork is evaluated at runtime
- **THEN** the otherwise branch is selected

#### Scenario: Create fork with otherwise ✅
- **GIVEN** two case predicates and an otherwise workflow
- **WHEN** a `WIOForkNode` is created via `WIOForkNode.withOtherwise(branches, otherwise)`
- **THEN** the otherwise branch is appended as a catch-all branch

#### Scenario: Fork with otherwise and edges ✅
- **GIVEN** a graph with a fork node (with otherwise) connected to downstream nodes
- **WHEN** the graph is compiled and executed
- **THEN** matching branches chain with downstream nodes via `WIO.AndThen`, and the otherwise branch uses its workflow directly

### Requirement: Fork Router (Clarification) ✅
The original spec called for a separate "Fork Router" class. In the wiograph implementation, fork routing is handled directly by `WIO.Fork` at runtime — there is no separate router. This is sufficient for WIO parity. A separate `ForkRouter` is NOT required unless runtime fork evaluation needs to happen outside of WIO compilation.
