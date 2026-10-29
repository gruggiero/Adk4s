# Spec: Surface Honesty

The tool surface and the unported-tool register together are meant to say, for every tool,
which implementation runs. Today they say it by name only. A subcommand that is named but
does nothing — the code-intelligence server start, which reports "server started", exits
clean and launches nothing — classifies as ported. So do two installers whose live paths run
their predecessors. And the traceability tool's predecessor, which nothing on the
enforcement path calls any more, is still an executable that the tutorial tells users to run.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | **Modified.** The protocol's **State** records which tools are ported. This spec changes what "ported" means, from *named on the surface* to *reached by the live invocation path*. The concept file's State section is updated as part of this spec. | `openspec/concepts/strangler-migration-protocol.md` |
| `TraceabilityGraph` (Traceability graph) | Its user-facing entry point becomes the ported subcommand. The concept's behaviour is unchanged. | `openspec/concepts/traceability-graph.md` |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Subcommand` | enum (11 cases) | `org.sinemenda.probatio.cli` |
| `HelpRegistry` | object | `org.sinemenda.probatio.cli` |
| `CliError` | sealed abstract class | `org.sinemenda.probatio.cli` |
| `UnportedTool` | final case class | `org.sinemenda.probatio.core` |
| `PortBlocker` | enum | `org.sinemenda.probatio.core` |
| `ToolSurfaceClassification` | enum (`Ported(subcommand: String)`, `Registered(tool)`) | `org.sinemenda.probatio.core` |
| `UnportedToolRegister` | object | `org.sinemenda.probatio.core` |
| `MetalsClient.MetalsSession` | final case class | `org.sinemenda.probatio.core` |
| `TraceabilityGraph` | final case class | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `LiveRoute` | enum (`ThroughPort(subcommand)`, `Predecessor(path)`) | What a tool's live invocation path reaches: the port through a forwarding script or the launcher, or its predecessor script. |

`ToolSurfaceClassification` is **modified**: `Ported` carries the `LiveRoute` that reached
the port and a `Subcommand` rather than a free string, so a tool can classify as ported only
by being reached.

`Subcommand` is **narrowed**: it loses `Metals`.

### Type-Widening Impact

`Subcommand` loses a case. Every match over it drops its `Metals` arm: the dispatcher, the
help registry and the entrypoint-contract tests. A narrowing cannot make a match silently
absorb a case, but it can leave a dead arm that refers to a removed case — the compiler
rejects that.

`ToolSurfaceClassification.Ported` changes shape. Its producer is the register's
classification, and its consumers are the register check and its suite. Both are rewritten
in this spec.

## ADDED Requirements

### Requirement: A subcommand that does not do its work is not on the surface

The tool surface SHALL expose only subcommands that perform their described work, and the
code-intelligence server subcommand MUST NOT be selectable while its start operation
launches nothing.

**Given** the tool surface after this change
**When** the code-intelligence server subcommand is invoked
**Then** it is rejected as an unknown subcommand

**Rationale**: on 2026-09-25 its start operation printed "server started" and exited clean
with no process launched and no endpoint recorded — a recognised-and-silent tool, which
`cli-entrypoint-contract` forbids.

#### Scenario: Happy path — the remaining subcommands are all selectable

**Given** every subcommand still on the surface
**When** each is invoked for help
**Then** each is recognised

#### Scenario: Adversarial — the removed subcommand never reports success

**Given** an invocation of the code-intelligence server's start operation through the tool
**When** it runs
**Then** it is rejected as unknown, it does not terminate with the clean status, and its
output never says the server started

### Requirement: The code-intelligence scripts are registered with their blockers

Both code-intelligence scripts SHALL be recorded in the unported-tool register with a blocker
from the closed set and the instructions that cite them, and neither MAY classify as ported.

**Given** the unported-tool register
**When** it is read
**Then** it has an entry for the server-start script and one for the server-call script, each
naming its blocker and its citing instructions

#### Scenario: Happy path — both scripts are registered

**Given** the register after this change
**When** it is read
**Then** both code-intelligence scripts appear with a blocker and their citations

#### Scenario: Adversarial — the server-start script does not classify as ported

**Given** the register check after this change
**When** the server-start script is classified
**Then** it classifies as registered, not ported

### Requirement: A tool is ported only when its live path reaches the port

The register check SHALL classify a tool as ported only when its live invocation path reaches
the ported implementation, and a tool whose live path reaches its predecessor MUST NOT
classify as ported even when its name is on the surface.

**Given** an executable tool in the workflow's tool directories
**When** it is classified
**Then** it is ported if and only if its live invocation path reaches the port; otherwise it
must be in the register

#### Scenario: Happy path — a forwarding script classifies as ported

**Given** a tool whose live file forwards to the ported subcommand
**When** it is classified
**Then** it is ported, and the classification names the route

#### Scenario: Adversarial — a named-but-unswapped tool is a finding

**Given** a tool whose name is on the surface but whose live file is its predecessor script,
and which has no register entry
**When** it is classified
**Then** the check reports it as unclassified — not ported

#### Scenario: Error path — an unreadable live file is could-not-determine

**Given** a tool whose live file cannot be read
**When** it is classified
**Then** the outcome is could-not-determine naming the file, and it is not classified as
ported

### Requirement: The traceability tool's predecessor is a revert target, not a live tool

The predecessor traceability script SHALL be kept only as a revert target, and no workflow
instruction or tutorial page MAY direct users to run it.

**Given** the workflow's instructions and tutorial pages
**When** they are read
**Then** they direct users to the ported traceability subcommand, documenting its five
operations and their arguments, and none directs them to the predecessor script

#### Scenario: Happy path — the tutorial documents the ported subcommand

**Given** the tutorial's tooling and practice pages
**When** they are read
**Then** each traceability example invokes the ported subcommand

#### Scenario: Adversarial — no instruction runs the predecessor script

**Given** every instruction, skill and tutorial page in the schema
**When** they are searched for an invocation of the predecessor traceability script
**Then** none is found outside the historical record

## Properties (Ring 3)

### Property: ported-iff-live-route-reaches-the-port

**Invariant**: for every tool and every state of its live file, the classification is ported
if and only if the live file forwards to the port.

**Generator strategy**: `genToolState` — constructive over tools drawn from the tool
directories, each given a live-file state from {forwards to the port, predecessor script,
unreadable, absent}, crossed with whether the tool's name is on the surface and whether it
has a register entry. All combinations arise by construction; no filtering.

```
property("ported iff the live route reaches the port") {
  for {
    t <- genToolState.forAll
    c  = classify(t)
  } yield Result.assert(c.isPorted == (t.liveFile == ForwardsToPort))
}
```

### Property: surface-names-only-working-subcommands

**Invariant**: every subcommand on the surface, invoked for help, is recognised and does not
report success for work it did not do.

**Generator strategy**: enumerated, not sampled — the domain is the closed subcommand set.
The finite limit is stated.

```
property("the surface names only working subcommands") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(Subcommand.values.forall(s => help(s).recognised))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A ported classification carrying a free string | A string name is how a tool classified ported without being reached | `assertDoesNotCompile("ToolSurfaceClassification.Ported(\"metals\")")` — `Ported` takes a `Subcommand` and a `LiveRoute` |
| Selecting the removed subcommand | A removed case must be unparseable, not merely unhandled | `assertDoesNotCompile("Subcommand.Metals")` |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: a two-variant classification driven by one observed fact
per tool; pinned by the type and `ported-iff-live-route-reaches-the-port`.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The remaining subcommands are selectable | Requirement: A subcommand that does not do its work is not on the surface + Scenario: Happy path — the remaining subcommands are all selectable + Property: surface-names-only-working-subcommands | Hedgehog property (enumerated) | `CliSurfaceSpec` |
| The removed subcommand never reports success | Requirement: A subcommand that does not do its work is not on the surface + Scenario: Adversarial — the removed subcommand never reports success | subprocess scenario test | `SubprocessConformanceSpec` |
| The removed subcommand is unparseable | Compile-Negative: Selecting the removed subcommand | compile-negative test | `SubcommandTypeContract` |
| Both code-intelligence scripts are registered | Requirement: The code-intelligence scripts are registered with their blockers + Scenario: Happy path — both scripts are registered | scenario test | `UnportedToolRegisterSpec` |
| The server-start script is not ported | Requirement: The code-intelligence scripts are registered with their blockers + Scenario: Adversarial — the server-start script does not classify as ported | scenario test | `UnportedToolRegisterSpec` |
| A forwarding script classifies as ported | Requirement: A tool is ported only when its live path reaches the port + Scenario: Happy path — a forwarding script classifies as ported | scenario test | `UnportedToolRegisterSpec` |
| A named-but-unswapped tool is a finding | Requirement: A tool is ported only when its live path reaches the port + Scenario: Adversarial — a named-but-unswapped tool is a finding + Property: ported-iff-live-route-reaches-the-port | Hedgehog property | `UnportedToolRegisterSpec` |
| An unreadable live file is could-not-determine | Requirement: A tool is ported only when its live path reaches the port + Scenario: Error path — an unreadable live file is could-not-determine | scenario test | `UnportedToolRegisterSpec` |
| A ported classification cannot carry a free string | Compile-Negative: A ported classification carrying a free string | compile-negative test | `UnportedToolRegisterTypeContract` |
| The tutorial documents the ported subcommand | Requirement: The traceability tool's predecessor is a revert target, not a live tool + Scenario: Happy path — the tutorial documents the ported subcommand | scenario test over the tutorial pages | `workflow-hygiene.bats` |
| No instruction runs the predecessor script | Requirement: The traceability tool's predecessor is a revert target, not a live tool + Scenario: Adversarial — no instruction runs the predecessor script | scenario test over the schema tree | `workflow-hygiene.bats` |
| The register is complete for the tree | Criterion: this spec's exit criterion | register check against the real tool directories, recorded in the evidence ledger | `UnportedToolRegisterSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The stub | object | `MetalsCmd` (its own file after `entrypoint-split`); `MetalsClient.initialize` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/MetalsClient.scala:104` | `initialize` returns `initialized = true` for any timeout of 10 ms or more, with no process launched |
| Surface references to `Metals` | code | `ProbatioMain.scala:88`, `HelpRegistry.scala:27,136`, `CliSurfaceSpec.scala:89`, `EntrypointContractSpec.scala:166` | Each drops its `Metals` arm. No skill or schema instruction invokes the subcommand, measured 2026-09-25 |
| The live `cli-wiring` requirement | spec | `openspec/specs/cli-wiring/spec.md:237` | Modified by this change's `specs/cli-wiring/spec.md` delta |
| Register | document + check | `openspec/schemas/verified-scala3/unported-tools.md`; `UnportedToolRegister` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/UnportedToolRegister.scala` | Gains two entries; classification by live route |
| Traceability predecessor | python script | `openspec/schemas/verified-scala3/scanner/openspec-graph.py` → `…/openspec-graph.py.predecessor.bak` | Kept as the conformance suite's executable model |
| Tutorial pages | HTML | `openspec/schemas/verified-scala3/docs/09-tooling.html` (lines 70, 519, 527, 531, 568), `docs/10-practice.html` (line 169) | Rewritten to the ported subcommand |
| The concept file | behavioural concept | `openspec/concepts/strangler-migration-protocol.md` | State: "ported" means reached by the live path |
| Ring 5 note | — | `stryker4s.conf` | Retarget to the register and its classification |
