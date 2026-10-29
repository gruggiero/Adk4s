# Spec: Installer Swap

Both installers were ported to predecessor surface parity and never swapped: the skill
installer and the hook installer still run as their bash predecessors, and the hook
installer still needs python3 to merge harness configuration. The differential comparison
lists both among the tools its suite exercises but cannot compare, because neither is a
seam.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | **Modified.** The protocol's **State** gains two seams, and its declared swap order gains two positions. Its **Swap** and **Gate** actions apply to them unchanged. The concept file's State section is updated as part of this spec. | `openspec/concepts/strangler-migration-protocol.md` |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ToolId` (migration) | enum (7 seams) | `org.sinemenda.probatio.migration` |
| `SwapOrder` | enum | `org.sinemenda.probatio.migration` |
| `SeamConfiguration` | final case class | `org.sinemenda.probatio.migration` |
| `CutoverGate` | object (`decide`, `authoriseSwap`) | `org.sinemenda.probatio.migration` |
| `ShimSwap` | final case class | `org.sinemenda.probatio.migration` |
| `InstallTarget` | enum | `org.sinemenda.probatio.core` |
| `InstallMode` | enum | `org.sinemenda.probatio.core` |
| `PrerequisiteProbe` | final case class | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

None. `ToolId` and `SwapOrder` each gain two cases, for the skill installer and the hook
installer.

### Type-Widening Impact

`ToolId` gains two cases and `SwapOrder` gains two positions. Every match over either must
handle them. The probatio modules escalate exhaustiveness to an error, so an unhandled case
fails compilation. The known matches — the seam-path, predecessor-source and override-variable
mappings, the serialisation codec, and the harness's seam resolution — each handle the new
cases explicitly. None may absorb them into a catch-all: a seam classified as "some other
tool" is how the ledger seam went unmeasured for a whole migration.

## ADDED Requirements

### Requirement: Each installer is a comparison seam

The differential comparison SHALL treat the skill installer and the hook installer as seams,
materialising the predecessor in one arm and the port in the other, and neither installer
MAY remain among the tools the comparison cannot compare.

**Given** the differential comparison's seam set
**When** it is enumerated
**Then** it contains the skill installer and the hook installer

#### Scenario: Happy path — both installers are compared

**Given** a comparison run
**When** its result is reported
**Then** the suite files exercising the installers carry predecessor and ported counts

#### Scenario: Adversarial — no installer is reported as not-compared

**Given** a comparison run
**When** its not-compared list is read
**Then** neither installer appears in it

### Requirement: An installer is swapped only at control parity

Each installer SHALL be swapped only when every suite file exercising it is at or below the
predecessor's failure count, and an installer MUST NOT be swapped on a worse or unmeasured
file.

**Given** an installer seam and the suite files exercising it
**When** the swap decision is taken
**Then** the installer is swapped only if no such file is worse and all were measured

#### Scenario: Happy path — an installer at parity is swapped

**Given** an installer whose exercising files are all at or below the predecessor
**When** the swap decision is taken
**Then** the installer is swapped and the decision records its comparison

#### Scenario: Adversarial — an installer with a worse file is not swapped

**Given** an installer one of whose exercising files is worse than the predecessor
**When** the swap decision is taken
**Then** the installer stays on its predecessor and the decision names the file

### Requirement: After the swap, the live installer is the port

After its swap, each installer's live invocation path SHALL reach the ported implementation,
and its predecessor MUST remain on disk as the revert target.

**Given** a swapped installer
**When** it is invoked through its usual path
**Then** the ported implementation runs, and the predecessor implementation is present as a
revert target

#### Scenario: Happy path — the skill installer runs the port

**Given** the swapped skill installer
**When** it is invoked through its usual path
**Then** the ported implementation runs

#### Scenario: Adversarial — a swap that removes the predecessor is refused

**Given** an installer swap whose predecessor implementation is not present
**When** the swap decision is taken
**Then** the swap is refused naming the missing predecessor

### Requirement: The hook installer needs no script interpreter

After its swap, the hook installer SHALL merge harness configuration without python3, and its
live path MUST NOT require an interpreter beyond the tool itself.

**Given** an environment without python3
**When** the hook installer merges a harness configuration with the write instruction
**Then** the merge completes and the configuration holds the workflow's hooks

**Rationale**: the predecessor merges with an embedded python3 program. The port does not
need one, and the swap is what makes that true for users.

#### Scenario: Happy path — a merge succeeds without python3

**Given** an environment with no python3 on the search path
**When** the swapped hook installer merges configuration
**Then** it completes and the configuration holds the hooks

#### Scenario: Adversarial — a foreign configuration is still not clobbered

**Given** a harness configuration the installer did not generate, and no python3
**When** the swapped installer runs with the write instruction
**Then** it refuses to overwrite that file and names it — the port keeps the predecessor's
no-clobber refusal

## Properties (Ring 3)

### Property: seam-set-covers-the-swap-order

**Invariant**: the seam set and the declared swap order are in bijection after both gain the
two installer entries.

**Generator strategy**: enumerated, not sampled — both domains are small closed sets, and the
property loops over their full enumeration. The finite-domain limit is stated.

```
property("seam set and swap order are in bijection") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(
    swapOrder.map(seamOf).toSet == allSeams.toSet && swapOrder.length == allSeams.length
  )
}
```

### Property: installer-swap-requires-a-complete-comparison

**Invariant**: for every comparison result over an installer's files, the swap is authorised
only when every file was measured in both arms and none is worse.

**Generator strategy**: `genComparisonResult` — reused from the swap decision's existing
suite, constructive over per-file presence flags and failure counts, drawn independently so
incomplete and regressing comparisons arise by construction.

```
property("an installer swap requires a complete comparison") {
  for {
    result <- genComparisonResult.forAll
  } yield Result.assert(
    authoriseSwap(result).authorised == (result.isComplete && !result.hasRegression)
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A seam named by a free string | A string seam can name a tool that is not a seam, which is how a tool drops out of the comparison | `assertDoesNotCompile("SeamConfiguration(Set(\"install-hooks\"))")` — the field is typed by the seam enum |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: the swap-authorisation decision is already verified in the
ledger-validator kernel, and this spec adds seams, not a decision.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Both installers are compared | Requirement: Each installer is a comparison seam + Scenario: Happy path — both installers are compared | differential run, recorded in the evidence ledger | `harness-install-verification.bats` via `probatioOracleDiff` |
| No installer is reported not-compared | Requirement: Each installer is a comparison seam + Scenario: Adversarial — no installer is reported as not-compared | scenario test | `DifferentialHarnessSpec` |
| Seam set and swap order are in bijection | Property: seam-set-covers-the-swap-order | Hedgehog property (enumerated) | `SwapOrderSpec` |
| A seam cannot be a free string | Compile-Negative: A seam named by a free string | compile-negative test | `DifferentialHarnessCompileNegative` |
| An installer at parity is swapped | Requirement: An installer is swapped only at control parity + Scenario: Happy path — an installer at parity is swapped | scenario test | `CutoverGateSpec` |
| An installer with a worse file is not swapped | Requirement: An installer is swapped only at control parity + Scenario: Adversarial — an installer with a worse file is not swapped + Property: installer-swap-requires-a-complete-comparison | Hedgehog property | `CutoverGateSpec` |
| The skill installer runs the port | Requirement: After the swap, the live installer is the port + Scenario: Happy path — the skill installer runs the port | scenario test | `HookCutoverShimSpec` |
| A swap without its predecessor is refused | Requirement: After the swap, the live installer is the port + Scenario: Adversarial — a swap that removes the predecessor is refused | scenario test | `CutoverRevertSpec` |
| A merge succeeds without python3 | Requirement: The hook installer needs no script interpreter + Scenario: Happy path — a merge succeeds without python3 | scenario test with python3 removed from the search path | `InstallToolSurfaceParitySpec` |
| A foreign configuration is not clobbered | Requirement: The hook installer needs no script interpreter + Scenario: Adversarial — a foreign configuration is still not clobbered | scenario test | `InstallToolSurfaceParitySpec` |
| Every exercising file is at control parity | Criterion: this spec's exit criterion | bats oracle against the genuine predecessor control | `harness-install-verification.bats`, `ambient-capture-wiring.bats`, `fact-extraction.bats`, `workflow-hygiene.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| Skill installer | predecessor script | `openspec/schemas/verified-scala3/scanner/install-skills.sh` (64 lines) | Becomes a forwarding script; the original is kept as its revert target |
| Hook installer | predecessor script | `openspec/schemas/verified-scala3/hooks/install-hooks.sh` (145 lines) | As above; its embedded python3 merge leaves the live path |
| Exercising suite files | bats suites | per the harness's 2026-09-25 not-compared list: `ambient-capture-wiring.bats` (hook installer), `fact-extraction.bats` and `workflow-hygiene.bats` (skill installer), plus `harness-install-verification.bats` | The comparison's exit-criterion files |
| `ToolId`, `SwapOrder` | enums | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/{SeamTypes,SwapOrder}.scala` | Gain two entries each |
| Ported installers | entrypoints | the two installer entrypoint files after `entrypoint-split` | Unchanged; already at surface parity |
| The concept file | behavioural concept | `openspec/concepts/strangler-migration-protocol.md` | State: two seams and two swap positions |
| Ring 5 note | — | `stryker4s.conf` | Seam code is in test sources: move-to-main-and-back. The installers' own files were scored by the previous change |
