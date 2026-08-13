# Spec: Fact Extraction Unification

<!-- DELTA spec for the `fact-extraction-unification` capability. Fixes D5
     (medium: three parallel parsers of the Proof-Obligations table,
     documented divergence) from the 2026-08-12 substratum review. Promotes
     openspec-graph.py to the single fact extractor (option a). -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | Workflow tooling only; no registry concept changes | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `openspec-graph.py export` | Python subcommand | Emits req→oblig→artifact→code topology as JSON for chain-state consumption |
| `spec-lint.sh --format json` | bash output mode | Machine-readable JSON output for chain-state consumption |
| python3 (declared prerequisite) | prerequisite | Added to the v12 declared prerequisite set |

## ADDED Requirements

### Requirement: openspec-graph.py is the single fact extractor

`openspec-graph.py export` SHALL emit the requirement→obligation→artifact→code
topology as a JSON document. `chain-state.sh` SHALL consume this JSON via jq
instead of re-parsing spec files with awk. The awk table-parsing in
chain-state SHALL be deleted.

**Given** a change with specs containing Proof-Obligations tables
**When** `openspec-graph.py export --change-dir DIR --change NAME` is invoked
**Then** it emits a JSON document with requirements, obligations, and
artifacts as structured fields

**Rationale**: Three parallel parsers (spec-lint awk, chain-state awk,
openspec-graph.py) read the same table with documented divergence. Promoting
the graph to the single fact extractor deletes the bug class. (Review D5,
option a.)

#### Scenario: export emits structured JSON

**Given** a change with one spec containing 5 obligations
**When** `openspec-graph.py export` is invoked
**Then** the JSON output contains 5 obligation objects, each with spec,
obligation, and artifact fields

#### Scenario: chain-state consumes graph JSON instead of awk parsing

**Given** a change directory with specs
**When** `chain-state.sh` computes bound/resolved
**Then** it invokes `openspec-graph.py export` and parses the JSON via jq,
not awk table-surgery

#### Scenario: the unattributable escape hatch is deleted

**Given** an obligation whose title does not exactly match spec-lint's
named_exists result
**When** chain-state computes its verdict
**Then** the graph resolves the source uniformly (ordinal, title, or
property); the `unattributable` reason-code is no longer emitted

### Requirement: spec-lint.sh provides JSON output

`spec-lint.sh` SHALL accept a `--format json` flag that emits its F7 (bound)
and F9 (resolved) verdicts as a JSON document, instead of human-readable
prose. `chain-state.sh` SHALL consume this JSON when available.

**Given** a spec with F7 and F9 findings
**When** `spec-lint.sh --format json` is invoked
**Then** it emits a JSON array of finding objects with `check`, `requirement`,
`verdict`, and `reason` fields

**Rationale**: Today chain-state regexes spec-lint's human prose (summary
lines, "no specs found…" strings). A machine interface retires the fragility
class. (Review D5 sub-fix.)

#### Scenario: JSON output for F7 findings

**Given** a spec with one unbound requirement
**When** `spec-lint.sh --format json` is invoked
**Then** the JSON contains an object with `check: "F7"`, the requirement name,
`verdict: "FAIL"`, and a reason

#### Scenario: JSON output for clean spec

**Given** a spec with no findings
**When** `spec-lint.sh --format json` is invoked
**Then** the JSON is an empty array (or an array of PASS verdicts)

#### Scenario: chain-state consumes JSON instead of regex

**Given** `spec-lint.sh --format json` available
**When** `chain-state.sh` needs F7/F9 verdicts
**Then** it calls `spec-lint.sh --format json` and parses the JSON, not
regex over human prose

### Requirement: python3 is a declared prerequisite

python3 SHALL be added to the workflow's declared prerequisite set in
`schema.yaml` and verified by `install-skills.sh --check-installed`. CI
templates SHALL install python3.

**Given** the workflow's prerequisite declaration
**When** `install-skills.sh --check-installed` is invoked
**Then** it checks for python3 and reports if missing

**Rationale**: Promoting openspec-graph.py to the gate path adds python3 as a
runtime dependency. The v12 prerequisite mechanism (declare, install in CI,
assert) makes this explicit. (Review D5, §4.)

#### Scenario: check-installed verifies python3

**Given** python3 is installed
**When** `install-skills.sh --check-installed` is invoked
**Then** python3 is listed as present

#### Scenario: check-installed reports missing python3

**Given** python3 is NOT installed
**When** `install-skills.sh --check-installed` is invoked
**Then** python3 is listed as missing and the exit code is non-zero

### Requirement: Bash chain-state degrades gracefully without python3

When python3 is unavailable, `chain-state.sh` SHALL fall back to its
bash-only mode (awk table parsing) and emit a trace line documenting the
degradation. The gate MUST NOT silently produce wrong verdicts.

**Given** python3 is NOT installed
**When** `chain-state.sh` is invoked
**Then** it falls back to awk parsing, emits a trace line "python3 unavailable;
using degraded bash-only mode", and produces its best-effort verdict

**Rationale**: The gate path must fail safely. Silent wrong verdicts are the
defect class this workflow exists to prevent. (Review D5, §4, mirroring
concept-scanner's grep fallback.)

#### Scenario: degraded mode is documented

**Given** python3 is NOT installed
**When** `chain-state.sh` is invoked
**Then** the trace output contains "python3 unavailable; using degraded
bash-only mode"

#### Scenario: degraded mode still produces a report

**Given** python3 is NOT installed
**When** `chain-state.sh` is invoked
**Then** it still emits a valid JSON report (via awk fallback), not an
undetermined verdict

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| export emits structured JSON | Requirement: openspec-graph.py is the single fact extractor + Scenario: export emits structured JSON | bats scenario: 5 obligations in JSON | `tests/fact-extraction.bats` |
| chain-state consumes graph JSON | Requirement: openspec-graph.py is the single fact extractor + Scenario: chain-state consumes graph JSON instead of awk parsing | bats scenario: jq over graph export | `tests/fact-extraction.bats` |
| unattributable escape hatch deleted | Requirement: openspec-graph.py is the single fact extractor + Scenario: the unattributable escape hatch is deleted | bats scenario: no unattributable reason | `tests/fact-extraction.bats` |
| spec-lint --format json for F7 | Requirement: spec-lint.sh provides JSON output + Scenario: JSON output for F7 findings | bats scenario: F7 finding in JSON | `tests/fact-extraction.bats` |
| spec-lint --format json for clean | Requirement: spec-lint.sh provides JSON output + Scenario: JSON output for clean spec | bats scenario: empty array | `tests/fact-extraction.bats` |
| chain-state consumes JSON not regex | Requirement: spec-lint.sh provides JSON output + Scenario: chain-state consumes JSON instead of regex | bats scenario: JSON interface used | `tests/fact-extraction.bats` |
| python3 declared as prerequisite | Requirement: python3 is a declared prerequisite + Scenario: check-installed verifies python3 | bats scenario: check-installed verifies | `tests/fact-extraction.bats` |
| check-installed reports missing python3 | Requirement: python3 is a declared prerequisite + Scenario: check-installed reports missing python3 | bats scenario: missing python3 | `tests/fact-extraction.bats` |
| degraded mode documented | Requirement: Bash chain-state degrades gracefully without python3 + Scenario: degraded mode is documented | bats scenario: trace line emitted | `tests/fact-extraction.bats` |
| degraded mode produces report | Requirement: Bash chain-state degrades gracefully without python3 + Scenario: degraded mode still produces a report | bats scenario: awk fallback works | `tests/fact-extraction.bats` |
