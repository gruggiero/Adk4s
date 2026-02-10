## ADDED Requirements
### Requirement: Cast-Free Branching Tests
The system SHALL implement branching routing tests without using `isInstanceOf`, relying on pattern matching to confirm expected branch variants or WIO shapes.

#### Scenario: Assert branch type via pattern matching
- **GIVEN** a Branch created via `Branch.apply` or `Branch.pure`
- **WHEN** the test validates the branch type
- **THEN** the test uses pattern matching instead of `isInstanceOf`

#### Scenario: Assert WIO branch type via pattern matching
- **GIVEN** a WIO produced by `WIOBranch.fork` or `WIOBranch.branch`
- **WHEN** the test validates the workflow shape
- **THEN** the test uses pattern matching instead of `isInstanceOf`
