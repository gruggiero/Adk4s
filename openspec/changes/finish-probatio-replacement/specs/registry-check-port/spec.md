# Spec: Registry Check Port

The concept-registry verifier checks that every code symbol a behavioural concept's
implementation map names still exists, that every declared fold field is folded, and that
every concept a change spec cites is declared. It runs in continuous integration and is named
in every session banner, so it sits on the enforcement path. It is registered as unported,
blocked on the scalameta native-image spike — but it uses only text search. The blocker was
misassigned, and the verifier can be ported now. The spike itself has never been run; this
spec runs it, so the concept scanner's blocker is re-established rather than inherited.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | **Modified.** The protocol's **State** gains the verifier as a seam; **Swap** and **Gate** apply unchanged. The concept file's State section is updated as part of this spec. | `openspec/concepts/strangler-migration-protocol.md` |
| `Conformance` (Conformance Property-Test Contract) | The ported verifier's verdicts must agree with the predecessor's in both directions, over the repository's registry and over generated registries. | `openspec/concepts/conformance-property-test-contract.md` |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Subcommand` | enum | `org.sinemenda.probatio.cli` |
| `UnportedTool` | final case class | `org.sinemenda.probatio.core` |
| `PortBlocker` | enum | `org.sinemenda.probatio.core` |
| `UnportedToolRegister` | object | `org.sinemenda.probatio.core` |
| `ToolId` (migration) | enum | `org.sinemenda.probatio.migration` |
| `SwapOrder` | enum | `org.sinemenda.probatio.migration` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RegistryRow` | final case class (conceptFile, line, tokens, citedFile) | One implementation-map or synchronization row as the verifier reads it. |
| `BindingVerdict` | enum (`Verified`, `Weak(token, citedFile)`, `Stale(token)`, `SpecCitation(problem)`) | The per-token and per-citation outcome, in the predecessor's four report kinds. |
| `RegistryReport` | final case class (verdicts, tokensChecked, specReferencesChecked) | The whole run; `passed` is derived as "no `Stale` and no `SpecCitation`". |

`Subcommand` **gains** the verifier's case. `ToolId` and `SwapOrder` **gain** its seam.

### Type-Widening Impact

`Subcommand` gains one case, which the dispatcher, the help registry and the surface tests
must each handle. `ToolId` gains one seam, which the seam mappings, the codec and the
harness's resolution must each handle. Exhaustiveness escalation makes an unhandled case a
compile error. No match may absorb the new case into a catch-all.

## ADDED Requirements

### Requirement: The ported verifier reaches the predecessor's verdict on every readable registry

The ported verifier SHALL report the same verdicts, the same report lines and the same
termination status as the predecessor for every registry the predecessor can read, and it
MUST NOT report a token verified that the predecessor reports stale.

**Given** a repository with a behavioural registry
**When** the predecessor and the port each verify it
**Then** their report lines are identical, line for line, and their termination statuses are
equal

#### Scenario: Happy path — the repository's own registry agrees

**Given** this repository's registry, specs and source tree
**When** both verifiers run
**Then** their reports are identical, including the five weak-binding warnings present on
2026-09-25

#### Scenario: Adversarial — a symbol removed from the tree is stale in both

**Given** a registry row naming a symbol that exists nowhere in the tracked source
**When** both verifiers run
**Then** both report that token stale and both terminate with the finding status

#### Scenario: Adversarial — a spec citing an undeclared action is reported by both

**Given** a change spec whose behavioural-concepts table cites an action absent from the
concept's actions block
**When** both verifiers run
**Then** both report the citation, naming the spec and the action

#### Scenario: Edge case — a repository with no registry verifies nothing, as before

**Given** a repository with no behavioural-concepts directory
**When** the port runs
**Then** it states there is nothing to verify and terminates with the clean status, as the
predecessor does

### Requirement: An unreadable input is could-not-determine

The ported verifier SHALL terminate with the could-not-determine status when it cannot read
its inputs, and it MUST NOT report success or failure on an input it did not read.

**Given** a registry directory that exists but cannot be read, or a source tree whose tracked
files cannot be listed
**When** the port runs
**Then** it terminates with the could-not-determine status, naming the unreadable input

**Rationale**: the predecessor has only two exit statuses. On an unreadable input it produces
whatever its text search returns, which is a verdict on data it never saw. This is the one
place the port deliberately differs from its predecessor, and it differs only on inputs the
predecessor cannot read — no verdict on a readable registry changes.

#### Scenario: Happy path — an unreadable registry is named

**Given** an unreadable concepts directory
**When** the port runs
**Then** the outcome is could-not-determine naming that directory

#### Scenario: Adversarial — an unreadable input does not produce an OK line

**Given** an unreadable input
**When** the port runs
**Then** its output contains no success summary line

### Requirement: The verifier is swapped under the comparison

The verifier SHALL become a comparison seam and be swapped only when the suite files that
exercise it are at or below the predecessor's failure count, and its predecessor MUST remain
on disk as the revert target.

**Given** the verifier's seam and its exercising suite files
**When** the swap decision is taken
**Then** it is swapped only at parity, and its predecessor stays as the revert target

#### Scenario: Happy path — the verifier at parity is swapped

**Given** the verifier's exercising files at or below the predecessor
**When** the swap decision is taken
**Then** it is swapped, and continuous integration and the session banner invoke the port

#### Scenario: Adversarial — a worse file keeps the predecessor

**Given** one exercising file worse than the predecessor
**When** the swap decision is taken
**Then** the verifier stays on its predecessor and the decision names the file

### Requirement: The scalameta spike is run and its result recorded

The scalameta native-image spike SHALL be run and its outcome recorded with its evidence, and
the concept scanner's register entry MUST state the blocker that the recorded outcome
establishes.

**Given** the spike: a native executable built with the release toolchain that parses a
Scala 3 source file with scalameta
**When** it is built and run
**Then** its outcome — built and parsed, failed to build, or built but failed to parse — is
recorded with the build log and the parse output

**Rationale**: the concept scanner has been gated on this spike since the first porting
change, and the spike has never run. A blocker that has never been tested is an assumption,
not a fact.

#### Scenario: Happy path — a successful spike moves the scanner's blocker

**Given** a spike that builds and parses successfully
**When** its result is recorded
**Then** the concept scanner's register entry no longer names the spike, and names the
blocker that remains

#### Scenario: Adversarial — a failed spike keeps the blocker with its evidence

**Given** a spike that fails to build or to parse
**When** its result is recorded
**Then** the scanner's entry keeps the spike blocker and cites the recorded failure

#### Scenario: Adversarial — the verifier's misassigned blocker is not carried forward

**Given** the register after this spec
**When** it is read
**Then** it has no entry for the verifier, which is now ported, and no entry names the spike as
the verifier's blocker

## Properties (Ring 3)

### Property: verdicts-agree-with-the-predecessor

**Invariant**: for every readable registry, the port's report lines and termination status
equal the predecessor's.

**Generator strategy**: `genRegistry` — constructive. Concept files are built from a closed
alphabet of row shapes: a symbol present in the cited file, a symbol present only elsewhere,
an absent symbol, a kebab-case token, a file path, an `openspec/` path, an ellipsis path, a
concept/action reference, and a fold declaration with present and missing fields. They are
combined with a generated source tree and 0–2 change specs whose behavioural tables cite
declared and undeclared actions. Each registry is materialised in a temporary repository.
Model-based: the predecessor runs as a subprocess in the hermetic environment. Edge cases: an
empty registry, a registry with no specs, and the repository's own registry.

```
property("verdicts agree with the predecessor") {
  for {
    reg <- genRegistry.forAll
  } yield Result.assert(runPort(reg) == runPredecessor(reg))
}
```

### Property: passed-iff-no-stale-and-no-spec-problem

**Invariant**: for every report, the run passes if and only if it has no stale verdict and no
spec-citation problem; weak verdicts never fail it.

**Generator strategy**: `genVerdictList` — constructive over lists of 0–12 verdicts drawn from
the four kinds, so all-weak, one-stale-among-weak and one-spec-problem lists arise by
construction.

```
property("passed iff no stale and no spec problem") {
  for {
    vs <- genVerdictList.forAll
  } yield Result.assert(
    RegistryReport(vs).passed == !vs.exists(v => v.isStale || v.isSpecCitation)
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A report whose pass flag is set directly | Pass must be derived from the verdicts, never asserted | `assertDoesNotCompile("RegistryReport(Nil, 0, 0, passed = true)")` — `passed` is derived |
| A stale verdict without its token | A stale finding that does not name what is stale cannot be fixed | `assertDoesNotCompile("BindingVerdict.Stale()")` |

## Formal Contracts (Ring 6)

The pass decision — no stale and no spec problem, weak never fails — is a fold over the
verdict list and sits at the centre of the verifier. It is mirrored alongside the spec-lint
kernel.

### Contract: registryPassed

```
def registryPassed(vs: List[VerdictModel]): Boolean = {
  ...
} ensuring { result =>
  result == !vs.exists(v => v.isStale || v.isSpecCitation) &&
  (vs.forall(_.isWeakOrVerified) ==> result)
}
```

A bridge property binds the shipped report to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The repository's own registry agrees | Requirement: The ported verifier reaches the predecessor's verdict on every readable registry + Scenario: Happy path — the repository's own registry agrees | model-based scenario test (predecessor as subprocess) | `ConformanceSpec` |
| A removed symbol is stale in both | Requirement: The ported verifier reaches the predecessor's verdict on every readable registry + Scenario: Adversarial — a symbol removed from the tree is stale in both | model-based scenario test | `ConformanceSpec` |
| An undeclared action citation is reported by both | Requirement: The ported verifier reaches the predecessor's verdict on every readable registry + Scenario: Adversarial — a spec citing an undeclared action is reported by both | model-based scenario test | `ConformanceSpec` |
| No registry verifies nothing, as before | Requirement: The ported verifier reaches the predecessor's verdict on every readable registry + Scenario: Edge case — a repository with no registry verifies nothing, as before | scenario test | `ConformanceSpec` |
| Verdicts agree over generated registries | Property: verdicts-agree-with-the-predecessor | Hedgehog model-based property (Ring 4: report lines are the wire format) | `ConformanceSpec` |
| An unreadable registry is named | Requirement: An unreadable input is could-not-determine + Scenario: Happy path — an unreadable registry is named | scenario test | `CliSurfaceSpec` |
| An unreadable input yields no OK line | Requirement: An unreadable input is could-not-determine + Scenario: Adversarial — an unreadable input does not produce an OK line | scenario test | `CliSurfaceSpec` |
| Pass is derived from verdicts | Property: passed-iff-no-stale-and-no-spec-problem | Hedgehog property | `LintReportSpec` |
| A pass flag cannot be set directly | Compile-Negative: A report whose pass flag is set directly | compile-negative test | `SpecLintEngineTypeContract` |
| A stale verdict must name its token | Compile-Negative: A stale verdict without its token | compile-negative test | `SpecLintEngineTypeContract` |
| The verifier at parity is swapped | Requirement: The verifier is swapped under the comparison + Scenario: Happy path — the verifier at parity is swapped | differential run, recorded in the evidence ledger | `CutoverGateSpec` |
| A worse file keeps the predecessor | Requirement: The verifier is swapped under the comparison + Scenario: Adversarial — a worse file keeps the predecessor | scenario test | `CutoverGateSpec` |
| A successful spike moves the scanner's blocker | Requirement: The scalameta spike is run and its result recorded + Scenario: Happy path — a successful spike moves the scanner's blocker | manual spike run, build log and parse output recorded | `UnportedToolRegisterSpec` |
| A failed spike keeps the blocker with evidence | Requirement: The scalameta spike is run and its result recorded + Scenario: Adversarial — a failed spike keeps the blocker with its evidence | manual spike run, recorded | `UnportedToolRegisterSpec` |
| The misassigned blocker is not carried forward | Requirement: The scalameta spike is run and its result recorded + Scenario: Adversarial — the verifier's misassigned blocker is not carried forward | scenario test over the register | `UnportedToolRegisterSpec` |
| The pass decision is verified | Invariant: the run passes iff no stale verdict and no spec problem | Stainless verification + bridge property test | `SpecLintKernel` (extended) + `SpecLintBridgeSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| Predecessor | bash script | `openspec/schemas/verified-scala3/scanner/registry-check.sh` (346 lines) | Three passes: symbols, fold fields, spec references. Exits 0 or 1 only. No `concepts/` directory → "nothing to verify", exit 0 |
| Output contract | report lines | same file | `STALE`, `WEAK`, `SPEC`, the fold-field line, the `registry-check: OK (…)` summary, and the `FAILED —` paragraph |
| Misassigned blocker | register entry | `openspec/schemas/verified-scala3/unported-tools.md` | `GatedOnSpike(native-packaging V1 scalameta spike)`; the script uses only grep, awk and bash — verified 2026-09-25 |
| Callers | configuration | `.github/workflows/verify.yml` (the registry-check step), the session banner's "gate checks" line, `schema.yaml`, the three CI templates | Invoke the port after the swap |
| Spike | throwaway module | `workflow/spike` — today it covers uPickle, os-lib and mainargs only | A scalameta target is added and built with the release toolchain |
| `SpecLintKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/SpecLintKernel.scala` | Extended with the pass contract |
| Latent predecessor defect, carried by parity | bash script | `registry-check.sh:303–305` | A behavioural-concepts table whose rows parse to **no** reference is reported only when it has more than one row. A one-row table that cites nothing passes silently. Found 2026-09-25 while linting this change: all 13 of its specs initially cited concepts in prose; the two two-row tables failed and the eleven one-row tables passed without any reference being checked. The port reproduces the predecessor's verdict (the first requirement), so fixing it is a verdict change, recorded for the next change rather than made here |
| Ring 5 note | — | `stryker4s.conf` | Retarget to the new verifier engine and its entrypoint |
