# branching-routing Specification

## Purpose
TBD - created by change add-branching-routing. Update Purpose after archive.

## ADDED Requirements
### Requirement: Branch ADT
The system SHALL provide a sealed trait Branch[I] that determines which node to route to based on input.

#### Scenario: Create invoke branch
- **WHEN** `Branch(condition, targets)` is called with an I => IO[NodeKey] function
- **THEN** an InvokeBranch instance is created with the provided condition and endNodes set

#### Scenario: Create pure branch
- **WHEN** `Branch.pure(condition, targets)` is called with an I => NodeKey function
- **THEN** an InvokeBranch instance is created with the condition wrapped in IO.pure

#### Scenario: Create stream branch
- **WHEN** `Branch.stream(condition, targets)` is called with a Stream[IO, I] => IO[NodeKey] function
- **THEN** a StreamBranch instance is created with the provided condition and endNodes set

#### Scenario: Create binary branch
- **WHEN** `Branch.binary(predicate, ifTrue, ifFalse)` is called with an I => IO[Boolean] predicate
- **THEN** a Branch is created that routes to ifTrue when predicate returns true, otherwise to ifFalse

#### Scenario: Create endIf branch
- **WHEN** `Branch.endIf(predicate, otherwise)` is called with an I => IO[Boolean] predicate
- **THEN** a Branch is created that routes to NodeKey.END when predicate returns true, otherwise to otherwise

#### Scenario: Get endNodes from branch
- **WHEN** `branch.endNodes` is called
- **THEN** the set of target node keys for the branch is returned

### Requirement: Router
The system SHALL provide a Router[I] that manages multiple branches and determines routing decisions.

#### Scenario: Create empty router
- **WHEN** `Router.empty[I]` is called
- **THEN** a Router instance with an empty branches map is returned

#### Scenario: Route from node with invoke branch
- **WHEN** `router.route(fromNode, input)` is called with a node that has an InvokeBranch
- **THEN** the branch condition is evaluated with the input and the resulting NodeKey is returned

#### Scenario: Route from node with stream branch using invoke method
- **WHEN** `router.route(fromNode, input)` is called with a node that has a StreamBranch
- **THEN** an IllegalStateException is raised indicating invoke routing cannot be used with stream branch

#### Scenario: Route from node with invoke branch using stream method
- **WHEN** `router.routeStream(fromNode, inputStream)` is called with a node that has an InvokeBranch
- **THEN** the input stream is compiled to the last element and the branch condition is evaluated

#### Scenario: Route from node with stream branch using stream method
- **WHEN** `router.routeStream(fromNode, inputStream)` is called with a node that has a StreamBranch
- **THEN** the branch condition is evaluated with the entire stream and the resulting NodeKey is returned

#### Scenario: Route from undefined node
- **WHEN** `router.route(fromNode, input)` is called with a node that has no defined branch
- **THEN** an IllegalStateException is raised with the node key value

#### Scenario: Add branch to router
- **WHEN** `router.addBranch(fromNode, branch)` is called
- **THEN** a new Router instance is returned with the branch added to the branches map

### Requirement: Workflows4s Branch Integration
The system SHALL provide WIOBranch integration for branching patterns in Workflows4s WIO.

#### Scenario: Create binary fork in WIO
- **WHEN** `WIOBranch.fork(condition, ifTrue, ifFalse)` is called with an I => Boolean condition
- **THEN** a WIO is created that evaluates the condition and routes to ifTrue when true, otherwise to ifFalse

#### Scenario: Create multi-way branch in WIO
- **WHEN** `WIOBranch.branch(selector, branches, default)` is called with an I => K selector
- **THEN** a WIO is created that uses the selector to choose from the branches map, falling back to default

#### Scenario: Create endIf pattern in WIO
- **WHEN** `WIOBranch.endIf(condition, continueWith, endValue)` is called with an I => Boolean condition
- **THEN** a WIO is created that returns endValue when condition is true, otherwise continues with continueWith workflow
