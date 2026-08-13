# Spec: Workflow Hygiene

<!-- DELTA spec for the `workflow-hygiene` capability. Fixes D7 (low:
     dangling reference in drift detector remediation) and D8 (low: hygiene
     batch) from the 2026-08-12 substratum review. -->

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
| (none) | — | Hygiene fixes only; no new domain types |

## ADDED Requirements

### Requirement: INSTRUCTION DRIFT remediation references the correct script

`spec-lint.sh`'s INSTRUCTION DRIFT message SHALL reference
`scanner/install-skills.sh` (the script that exists), not
`verified-scala3/sync-skills.sh` (which does not exist).

**Given** spec-lint detects an instruction drift (schema version mismatch
between skills and schema.yaml)
**When** the drift message is printed
**Then** it says "Re-install (scanner/install-skills.sh)", not
"Re-install (verified-scala3/sync-skills.sh)"

**Rationale**: The detector for stale instructions carries a wrong remediation
pointer. (Review D7.)

#### Scenario: drift message names the correct script

**Given** a schema version mismatch between skills and schema.yaml
**When** spec-lint emits the INSTRUCTION DRIFT message
**Then** the message references `scanner/install-skills.sh`

### Requirement: Every tool-name in scanner messages resolves to a tracked file

A bats property SHALL verify that every tool-name mentioned in scanner
messages (remediation pointers, error messages, trace lines) resolves to a
file tracked in the repository.

**Given** all scanner scripts and their message strings
**When** the bats property extracts tool-name references from messages
**Then** each referenced path resolves to a tracked file

**Rationale**: A dangling reference in an enforcement tool breeds confusion
about where enforcement lives. (Review D7.)

#### Scenario: all message references resolve

**Given** the scanner scripts' message strings
**When** the bats property checks each referenced path
**Then** all paths resolve to tracked files (no dangling references)

### Requirement: Dead duplicate case arm is removed

`ledger.sh` SHALL NOT contain the dead duplicate
`update|delete|rewrite|edit)` case arm at the bottom of the subcommand
dispatch. The pre-parse check already handles these.

**Given** `ledger.sh` source code
**When** inspected for the `update|delete|rewrite|edit)` case arm
**Then** it is absent (removed as dead code)

**Rationale**: Dead code in an enforcement tool breeds confusion about where
enforcement lives. (Review D8.)

#### Scenario: dead case arm is absent

**Given** `ledger.sh` source
**When** grep for `update|delete|rewrite|edit)`
**Then** no match is found in the subcommand dispatch section

### Requirement: gate.sh parses cwd with jq, not sed

`gate.sh` SHALL extract `cwd` from the hook JSON payload using jq (the
declared prerequisite), not a `sed` regex over JSON.

**Given** `gate.sh` extracting `cwd` from hook JSON
**When** the extraction code is inspected
**Then** it uses `jq -r`, not `sed`

**Rationale**: Parse structured data with the structured parser. jq is a
declared prerequisite loaded two lines later. (Review D8.)

#### Scenario: cwd extracted via jq

**Given** `gate.sh` source code
**When** inspected for the cwd extraction
**Then** it uses `jq -r '.cwd'` (or equivalent), not `sed`

### Requirement: Heartbeat is written after the relevance guard

`gate.sh` SHALL write the heartbeat file AFTER the relevance guard
determines the current repo is an openspec project, not before. The
`.git/verified-scala3-gate/` directory MUST NOT be created in non-openspec
repos.

**Given** `gate.sh` running in a non-openspec git repo
**When** the relevance guard determines the repo is not an openspec project
**Then** no `.git/verified-scala3-gate/` directory is created

**Rationale**: The heartbeat written before the guard creates
`.git/verified-scala3-gate/` in every git repo the hook fires in, including
non-openspec ones. (Review D8.)

#### Scenario: non-openspec repo is not polluted

**Given** a git repo without an `openspec/` directory
**When** `gate.sh` fires
**Then** `.git/verified-scala3-gate/` is NOT created

#### Scenario: openspec repo gets the heartbeat

**Given** a git repo with an `openspec/` directory
**When** `gate.sh` fires and the relevance guard passes
**Then** `.git/verified-scala3-gate/` is created (heartbeat written after guard)

### Requirement: Invariant banner is defined once

`schema.yaml` SHALL define the invariant banner text exactly once at a
top-level key (e.g. `invariant_banner: |`) and reference it wherever the
banner appears in instruction blocks, instead of duplicating the text
verbatim in each instruction. The mechanism is a top-level YAML key whose
value is interpolated into instructions by the openspec CLI (or, if the CLI
does not support interpolation, a YAML anchor `&invariant` on a standalone
node referenced by `*invariant` in each instruction's `instruction` block —
but only if the instruction blocks can be restructured to use the anchor as
a standalone node rather than a substring of a block scalar).

**Given** `schema.yaml` source
**When** inspected for the invariant banner text
**Then** it appears once as a definition and is referenced elsewhere, not
duplicated verbatim 10 times

**Rationale**: 10 copies of the banner drift. YAML anchors enforce identity
for standalone YAML nodes, but the invariant is currently a substring of
`instruction: |` block scalars, which anchors cannot reference. The fix
extracts the invariant to a standalone key and interpolates it. (Review D8.)

#### Scenario: banner defined once, referenced by alias

**Given** `schema.yaml` source
**When** the invariant banner text is searched
**Then** it appears as a `&invariant` anchor definition once, and all other
occurrences use `*invariant`

### Requirement: Payload named-unresolved list is capped

The gate payload's named-unresolved list SHALL be capped at N entries with
a "+M more" suffix when the list exceeds N. The counts carry the invariant;
the full list is available via `chain-state.sh` on demand.

**Given** a change with 60 unresolved requirements
**When** the gate payload is assembled
**Then** the named list shows the first N entries followed by "+M more"
(M = 60 - N), not all 60 entries

**Rationale**: A 60-requirement spec mid-flight prints 60+ lines (~1,300–1,500
tok/turn). Capping removes the only unbounded per-turn cost. (Review §7.1.)

#### Scenario: list capped at N

**Given** a change with 60 unresolved requirements and N=10
**When** the gate payload is assembled
**Then** the named list shows 10 entries followed by "+50 more"

#### Scenario: short list is not capped

**Given** a change with 5 unresolved requirements and N=10
**When** the gate payload is assembled
**Then** all 5 entries are listed (no "+M more" suffix)

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Drift message references correct script | Requirement: INSTRUCTION DRIFT remediation references the correct script + Scenario: drift message names the correct script | bats scenario: drift message text | `tests/workflow-hygiene.bats` |
| All message references resolve | Requirement: Every tool-name in scanner messages resolves to a tracked file + Scenario: all message references resolve | bats property: grep message strings, check paths | `tests/workflow-hygiene.bats` |
| Dead case arm removed | Requirement: Dead duplicate case arm is removed + Scenario: dead case arm is absent | bats scenario: grep for dead arm | `tests/workflow-hygiene.bats` |
| cwd parsed with jq | Requirement: gate.sh parses cwd with jq, not sed + Scenario: cwd extracted via jq | bats scenario: grep for jq not sed | `tests/workflow-hygiene.bats` |
| Non-openspec repo not polluted | Requirement: Heartbeat is written after the relevance guard + Scenario: non-openspec repo is not polluted | bats scenario: no .git/verified-scala3-gate/ | `tests/workflow-hygiene.bats` |
| Openspec repo gets heartbeat | Requirement: Heartbeat is written after the relevance guard + Scenario: openspec repo gets the heartbeat | bats scenario: heartbeat after guard | `tests/workflow-hygiene.bats` |
| YAML anchors for invariant banner | Requirement: Invariant banner is defined once + Scenario: banner defined once, referenced by alias | bats scenario: banner defined once | `tests/workflow-hygiene.bats` |
| Payload list capped at N | Requirement: Payload named-unresolved list is capped + Scenario: list capped at N | bats scenario: 60 entries, N=10 | `tests/workflow-hygiene.bats` |
| Short list not capped | Requirement: Payload named-unresolved list is capped + Scenario: short list is not capped | bats scenario: 5 entries, no suffix | `tests/workflow-hygiene.bats` |
