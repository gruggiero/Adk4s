# Spec: Judgment Ring Provenance

<!-- DELTA spec for the `judgment-ring-provenance` capability. The failure
     report's Ring 8 drift was self-assessment: "I wrote 'no case _ default'
     in the progress file, self-assessing compliance without invoking the
     openspec-adversarial-review skill or producing a structured report."
     D4 (shipped) made judgment-ring rows name a resolvable hashed report
     artifact. This spec adds provenance: the report's recorded session
     must differ from the implementing session — the fresh-context
     property, enforced where the harness can observe it, honestly
     declared where it can't. -->

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
| `session` field on adversarial-review ring rows | new ledger record field | The session id that produced the adversarial-review report; checkpoint requires it to differ from the implementing session |
| R8 provenance check | new checkpoint requirement | The checkpoint's R8 line requires the report's recorded session to differ from the implementing session, or an explicit human-attested limitation where the harness can't observe it |

## ADDED Requirements

### Requirement: Adversarial-review ring rows carry a session provenance

A ledger row with `ring: "R8"` SHALL carry a `session` field identifying
the session that produced the report. The `session` value SHALL be the same
identity gate.sh uses for fingerprint suppression (`--session`,
`$CLAUDE_CODE_SESSION_ID`, `$VERIFIED_SCALA3_SESSION_ID`, or the PPID
fallback), so the checkpoint can compare it to the implementing session.

**Given** an R8 report is recorded via `ledger.sh run` or `ledger.sh append`
with `ring: "R8"`
**When** the row is written
**Then** the row carries a `session` field whose value is the producing
session's identity

**Rationale**: Without provenance, the checkpoint cannot distinguish a
fresh-context review from a same-context self-assessment — the exact
defeat of Ring 8 the failure report names. The session field makes
"fresh-context" a machine-decidable predicate where the harness exposes
session identity.

#### Scenario: an adversarial-review row carries a session

**Given** an adversarial-review report is recorded
**When** the ledger row is written
**Then** the row has a `session` field with a non-empty value

#### Scenario: an adversarial-review row without a session is rejected

**Given** an attempt to write a `ring: "R8"` row with no `session` field
**When** `ledger.sh` validates the row against
`ledger-record-contract.jq`
**Then** the row is rejected (the contract requires `session` for adversarial-review rows)

### Requirement: The checkpoint requires the adversarial-review session to differ from the implementing session

`checkpoint.sh` SHALL report the adversarial-review ring as having no
recorded evidence when its ledger row's `session` equals the implementing
session (the session that produced the implementation diff), unless an
explicit human-attested limitation is recorded. The implementing session is
the session that recorded the implementation's ledger rows (R0/R3 etc.).

**Given** an R8 ledger row with `session: S1` and implementation ledger
rows (R0/R3) with `session: S1` (same session)
**When** `checkpoint.sh report` generates the R8 line
**Then** the R8 ring is reported as having no recorded fresh-context
evidence, with a reason naming the same-session match

**Rationale**: The fresh-context mandate (schema Step 8): "the review MUST
be performed by a reviewer with NO implementation context." Same-session is
the proxy for same-context where the harness exposes session identity. It
is not a perfect proof of freshness (a session could in principle be
reused), but it changes the cost of cheating from "write one sentence of
self-assessment" to "forge a session id" — the achievable bar the schema
already chose for the ledger.

#### Scenario: a same-session adversarial review is flagged

**Given** an adversarial-review row `session: S1` and implementation rows `session: S1`
**When** checkpoint generates the adversarial-review line
**Then** the ring is reported as no recorded fresh-context evidence
(same session)

#### Scenario: a different-session adversarial review is accepted

**Given** an adversarial-review row `session: S2` and implementation rows `session: S1`
(S2 ≠ S1)
**When** checkpoint generates the adversarial-review line
**Then** the ring is reported as discharged with fresh-context evidence

### Requirement: Where the harness cannot observe session, the limitation is explicit

The checkpoint SHALL present the R8 provenance as a human-attested
limitation with the date and the mechanism by which freshness was
established when the harness provides no verified session identity (the
PPID fallback is an inference, not a confirmed fact — flagged in the hooks
README for Devin), rather than silently accepting a self-assessment.

**Given** an R8 row whose `session` is the PPID fallback (unverified)
**When** checkpoint generates the R8 line
**Then** the R8 ring is reported with a limitation note naming the
unverified session source and requiring explicit human attestation of
freshness

**Rationale**: v12's second corollary: "a recorded limitation is
re-established before it is relied upon — it carries the date and the
mechanism by which it was established." Applying it to the schema's own
blind spot (Devin's unverified session) turns an implicit self-assessment
pass into an honest, dated declaration — the difference between "passes"
(a claim) and "human-attested, mechanism: X" (a recorded limitation).

#### Scenario: an unverified session source is flagged as a limitation

**Given** an R8 row whose `session` is the PPID fallback
**When** checkpoint generates the R8 line
**Then** the R8 line names the unverified session source and requires
explicit human attestation

#### Scenario: a verified session source is not flagged

**Given** an R8 row whose `session` is `$CLAUDE_CODE_SESSION_ID` (verified
present in the hook env) or a pi in-process session
**When** checkpoint generates the R8 line
**Then** the R8 line uses the session for the fresh-context check without a
limitation note

### Requirement: The adversarial-review report artifact remains resolvable and hashed

Adversarial-review rows SHALL continue to name a resolvable, sha256-hashed
report artifact (the D4 requirement, shipped, remains in force). The
provenance field is additive: it does not relax the artifact-existence or
hash requirement.

**Given** an R8 ledger row
**When** `ledger.sh verify` runs
**Then** the row's `artifact` resolves to a tracked file and the `sha256`
matches the file's current hash (the D4 check), in addition to the new
`session` provenance check

**Rationale**: D4 made the report a file that exists; this spec makes the
report a file produced by a different session. The two together close the
self-assessment defect: the report must exist AND come from elsewhere.

#### Scenario: an adversarial-review row with a missing artifact is rejected

**Given** an adversarial-review row whose `artifact` does not resolve to a tracked file
**When** `ledger.sh verify` runs
**Then** the row is flagged (D4 check, unchanged)

#### Scenario: an adversarial-review row with a matching artifact and a different session passes

**Given** an adversarial-review row with a resolvable hashed artifact and `session: S2`
differing from the implementing session `S1`
**When** `ledger.sh verify` and `checkpoint.sh report` run
**Then** the row passes both the D4 artifact check and the new provenance
check

## Properties (Ring 3)

No Hedgehog properties. All verification via bats scenario tests.
Property-equivalent invariant: **an R8 ring is reported as
fresh-context-discharged iff (resolvable hashed artifact) AND (session
differs from implementing session OR an explicit limitation is recorded).**
Asserted by enumerating the (artifact, session, implementing-session)
decision table in `tests/judgment-ring-provenance.bats`.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Adversarial-review row carries a session | Requirement: Adversarial-review ring rows carry a session provenance + Scenario: an adversarial-review row carries a session | bats scenario: adversarial row has session | `tests/judgment-ring-provenance.bats` |
| Adversarial-review row without session rejected | Requirement: Adversarial-review ring rows carry a session provenance + Scenario: an adversarial-review row without a session is rejected | bats scenario: no session rejected | `tests/judgment-ring-provenance.bats` |
| Same-session adversarial review flagged | Requirement: The checkpoint requires the adversarial-review session to differ from the implementing session + Scenario: a same-session adversarial review is flagged | bats scenario: same session flagged | `tests/judgment-ring-provenance.bats` |
| Different-session adversarial review accepted | Requirement: The checkpoint requires the adversarial-review session to differ from the implementing session + Scenario: a different-session adversarial review is accepted | bats scenario: different session accepted | `tests/judgment-ring-provenance.bats` |
| Unverified session flagged as limitation | Requirement: Where the harness cannot observe session, the limitation is explicit + Scenario: an unverified session source is flagged as a limitation | bats scenario: unverified session limitation | `tests/judgment-ring-provenance.bats` |
| Verified session not flagged | Requirement: Where the harness cannot observe session, the limitation is explicit + Scenario: a verified session source is not flagged | bats scenario: verified session clean | `tests/judgment-ring-provenance.bats` |
| Adversarial-review artifact remains resolvable and hashed | Requirement: The adversarial-review report artifact remains resolvable and hashed + Scenario: an adversarial-review row with a missing artifact is rejected | bats scenario: missing artifact rejected | `tests/judgment-ring-provenance.bats` |
| Adversarial-review with artifact and different session passes | Requirement: The adversarial-review report artifact remains resolvable and hashed + Scenario: an adversarial-review row with a matching artifact and a different session passes | bats scenario: artifact and session pass | `tests/judgment-ring-provenance.bats` |
