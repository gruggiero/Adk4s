# Spec: CLI Wiring (delta)

This delta brings one requirement of the live `cli-wiring` specification into line with the
tool surface. As it stands, the requirement names ten subcommands that SHALL be wired. Four
of them — the concept scanner, its wrapper, and the two code-intelligence recipes — were
removed from the surface by `cli-entrypoint-contract` two changes ago, so the requirement
has contradicted the code since then. A fifth, the code-intelligence server, leaves the
surface in this change (`surface-honesty`). The requirement also carries a scenario that the
shipped server-start operation never met.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The requirement restated here enumerates which ported subcommands exist. It now matches the protocol's State as modified by `surface-honesty` and `registry-check-port`. | `openspec/concepts/strangler-migration-protocol.md` |

This delta does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Subcommand` | enum | `org.sinemenda.probatio.cli` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `InstallTarget` | enum | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

None.

## MODIFIED Requirements

### Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout

Each ported subcommand entrypoint SHALL wire to its core logic — namely `registry-check`,
`reconcile`, `graph`, `install-skills` and `install-hooks` — parse their flags from their
arguments, and emit output byte-compatible with their predecessor scripts. Each SHALL
implement the three-way exit protocol (0 clean, 1 finding, 2 undetermined). A tool that is
not ported — the concept scanner and its wrapper, the impact and removal recipes, and both
code-intelligence scripts — MUST NOT be selectable as a subcommand; it is recorded in the
unported-tool register instead.

**Given** a concept-registry verification invocation
**When** the entrypoint runs
**Then** its output matches the predecessor verifier's report format and its termination
status follows the three-way protocol

**Rationale**: each ported subcommand has a predecessor script with an established output
contract. The acceptance oracle exercises the ones it covers; the remainder are verified
against the predecessor's output by model-based properties. A tool without a port is
unparseable rather than recognised-and-silent, per `cli-entrypoint-contract`.

#### Scenario: registry-check emits the concept registry edge check report

**Given** a repository whose registry cites concepts
**When** the concept-registry verification runs
**Then** the report lists each concept's implementation-map verification result, and the
termination status is clean when every token resolves

#### Scenario: install-skills copies skills to every declared agent directory

**Given** a skill-installer invocation against a project root
**When** it runs with the write instruction
**Then** every declared agent directory under that root receives each skill document, and
the termination status is clean

#### Scenario: Adversarial — an unported tool is not selectable

**Given** the code-intelligence server, the concept scanner, or either code-intelligence
recipe invoked as a subcommand
**When** the tool dispatches
**Then** it is rejected as an unknown subcommand and does not terminate with the clean status

## Properties (Ring 3)

### Property: only-ported-tools-are-selectable

**Invariant**: a tool name is selectable as a subcommand if and only if it names a ported
tool.

**Generator strategy**: enumerated, not sampled — the union of the ported set and the
registered set is closed and small. The finite limit is stated.

```
property("only ported tools are selectable") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(
    (portedNames ++ registeredNames).forall(n => selectable(n) == portedNames.contains(n))
  )
}
```

## Compile-Negative Obligations

None beyond those in `surface-honesty` and `registry-check-port` — stated.

## Formal Contracts (Ring 6)

No formal contracts — stated skip: an enumeration of the surface, with no decision beyond set
membership.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The registry verification report matches its predecessor | Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout + Scenario: registry-check emits the concept registry edge check report | model-based property against the predecessor verifier (see `registry-check-port`) | `ConformanceSpec` |
| The skill installer writes every declared directory | Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout + Scenario: install-skills copies skills to every declared agent directory | scenario test | `InstallToolSurfaceParitySpec` |
| An unported tool is not selectable | Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout + Scenario: Adversarial — an unported tool is not selectable + Property: only-ported-tools-are-selectable | Hedgehog property (enumerated) | `CliSurfaceSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The live requirement | spec | `openspec/specs/cli-wiring/spec.md:237` | Replaced by this delta at archive |
| Removed scenario | spec | same file — *metals start launches the LSP server* | Never met by the shipped operation; the subcommand leaves the surface |
| Stale enumeration | spec | same requirement's normative statement | Named `scan`, `removal-audit`, `impact-scan`, `concept-scanner` as wired; all were removed from the surface by `cli-entrypoint-contract` |
