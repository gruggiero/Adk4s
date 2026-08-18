# Spec: CLI Protocol

<!-- Delta spec for the port-scanner-to-probatio change. Defines the public
     CLI protocol of the `probatio` multicall binary: one subcommand per
     predecessor script, the three-way 0/1/2 exit protocol, byte-compatible
     stdout payloads against the three .jq contracts, arg-parse error
     attribution, --help with defaults, and multicall dispatch by argv(1)
     and argv(0). Covers R-P1…R-P6 from the requirements doc §5 capability
     `cli-protocol`. R-ARCH1: probatio depends on NOTHING adk4s-side — the
     "Concepts Used (from inventory)" table is empty by construction. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Ledger record contract | The executable `.jq` contract that defines the wire format for ledger records; the CLI's stdout MUST be byte-compatible with it | `openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq` |
| Chain-state report contract | The executable `.jq` contract that defines the wire format for chain-state reports; the CLI's stdout MUST be byte-compatible with it | `openspec/schemas/verified-scala3/scanner/chain-state-report-contract.jq` |
| Gate hook-json contract | The executable `.jq` contract that defines the wire format for gate hook payloads; the CLI's stdout MUST be byte-compatible with it | `openspec/schemas/verified-scala3/scanner/gate-hookjson-contract.jq` |
| Bats oracle | The 17-file acceptance suite that the CLI MUST pass unmodified at every migration step | `openspec/schemas/verified-scala3/tests/*.bats` |
| `*_OVERRIDE` env seams | The swap-points (`SPEC_LINT_OVERRIDE`, `CHAIN_STATE_OVERRIDE`, `DANGER_SCAN_OVERRIDE`, `GATE_command`) through which the ported CLI is substituted into the oracle | `openspec/schemas/verified-scala3/hooks/gate.sh`, oracle helpers |
| Three-way exit protocol (NEW — created by this spec) | The 0/1/2 exit-code mapping: 0 = ran clean, 1 = ran and found something, 2 = could not determine | this spec |
| Subcommand surface (NEW — created by this spec) | The 1:1 subcommand-per-predecessor-script dispatch | this spec |
| Multicall dispatch (NEW — created by this spec) | argv(1) and argv(0) dispatch to the same subcommand | this spec |
| Arg-parse error attribution (NEW — created by this spec) | Every parse error names the missing or invalid flag | this spec |
| Help output with defaults (NEW — created by this spec) | `--help` lists every flag with its default value | this spec |

This spec does not alter any existing concept's actions, state, or
synchronizations. The behavioral contracts (`.jq`, bats oracle, `*_OVERRIDE`
seams) are referenced as porting conformance fixtures, not modified. The new
concepts are created by this spec and registered at apply Step 12.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| — | — | — |

**R-ARCH1**: the `probatio` tooling subprojects depend on NOTHING adk4s-side —
no adk4s library module, test utility, or shared source. The inventory
re-use table is empty by construction. A build-level dependency-lint rule
(enforced in CI) fails if any `workflow/*` project's classpath reaches an
adk4s module. The `Outcome[A]` sealed enum used at the CLI boundary is
introduced by the `probatio-core` spec (this spec defines its mapping to
exit codes, not the type itself).

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Subcommand` | sealed enum | The exhaustive set of subcommand names, one per predecessor script: `gate`, `spec-lint`, `chain-state`, `ledger`, `checkpoint`, `registry-check`, `reconcile`, `scan`, `removal-audit`, `danger-scan`, `impact-scan`, `metals`, `concept-scanner`, `graph`, `install-skills`, `install-hooks` |
| `ExitCode` | sealed enum (`Clean`, `Finding`, `Undetermined`) | The three-way exit protocol as data; maps to process exit codes 0, 1, 2 respectively |
| `CliError` | sealed trait | Arg-parse error algebra: `UnknownSubcommand(token)`, `MissingValue(flag)`, `InvalidEnum(flag, value)`, `UnknownFlag(flag)` — each carries the offending token |
| `MulticallDispatch` | module | Dispatch by argv(1) (subcommand argument) or argv(0) (symlink basename); both resolve to the same `Subcommand` |
| `HelpOutput` | case class | The `--help` output: subcommand name, every flag with its default (or "required" / "none"), and the three-way exit protocol documentation |
| `probatio` multicall binary | native-image launcher | The single binary that dispatches to all subcommands; alias `prob` |

## ADDED Requirements

### Requirement: One subcommand per predecessor script

The CLI SHALL expose exactly one subcommand per predecessor script, and
MUST NOT expose subcommands beyond the predecessor set. The subcommand
names are: `gate`, `spec-lint`, `chain-state`, `ledger`, `checkpoint`,
`registry-check`, `reconcile`, `scan`, `removal-audit`, `danger-scan`,
`impact-scan`, `metals` (with `start`/`stop`/`call` sub-subcommands,
replacing the two-script split), `concept-scanner`, `graph` (port of the
graph extraction tool's subcommands), `install-skills`, and
`install-hooks`.

**Given** the set of predecessor scripts being ported
**When** the CLI's subcommand surface is enumerated
**Then** there is exactly one subcommand per predecessor script, and no
subcommand exists that does not correspond to a predecessor script

**Rationale**: The port is 1:1 with the predecessor scripts — a port, not a
redesign. A subcommand that does not correspond to a predecessor script is
a new feature, which the feature freeze (R-X1) forbids. The `metals`
subcommand consolidates the two-script `metals-start.sh` / `metals-call.sh`
split into sub-subcommands; this is a structural consolidation, not a new
capability.

#### Scenario: Every predecessor script has a corresponding subcommand

**Given** the predecessor script inventory (gate, spec-lint, chain-state,
ledger, checkpoint, registry-check, reconcile, scan, removal-audit,
danger-scan, impact-scan, metals-start/metals-call, concept-scanner,
openspec-graph, install-skills, install-hooks)
**When** the CLI subcommand surface is enumerated
**Then** each predecessor script maps to exactly one subcommand, and
`metals` exposes `start`, `stop`, and `call` sub-subcommands

#### Scenario: Unknown subcommand is rejected

**Given** a subcommand name not in the predecessor set (e.g. `update`)
**When** the CLI is invoked with that subcommand
**Then** the CLI rejects it with a single-line error on stderr naming the
unknown token and exits with a non-zero code

#### Scenario: Mutation subcommand does not exist (adversarial)

**Given** a subcommand name `update`, `delete`, `rewrite`, or `edit`
**When** the CLI is invoked with that subcommand
**Then** the CLI reports an unknown subcommand error — the mutation
subcommand does not exist in the subcommand enum and cannot be invoked

### Requirement: Three-way exit protocol for every subcommand

Every subcommand SHALL implement the three-way exit protocol: exit 0 means
the subcommand ran and found nothing wrong; exit 1 means the subcommand ran
and found something (a finding); exit 2 means the subcommand could not
determine (undetermined — includes unreadable ledger, unknown record
version, spec-lint non-lint failure, missing prerequisites). A subcommand
without the possibility of an undetermined outcome SHALL document that
explicitly in its `--help` output.

**Given** a subcommand that has been invoked with accepted arguments (no parse error)
**When** the subcommand completes its operation
**Then** the process exits with exactly one of 0, 1, or 2, and no other
exit code is producible

**Rationale**: The three-way exit protocol is the single most load-bearing
invariant of the schema (§4.1). It is the contract every hook adapter and
every CI consumer depends on. A sealed exit-code enum with exactly three
cases makes the protocol a compiler-checked fact, not a hand-rolled
convention summarizable only in comments.

#### Scenario: Clean run exits 0

**Given** a subcommand invoked against a repository with no findings and
no errors
**When** the subcommand completes
**Then** the process exits 0

#### Scenario: Finding exits 1

**Given** a subcommand invoked against a repository with one or more
findings (e.g. spec-lint detects a violation)
**When** the subcommand completes
**Then** the process exits 1

#### Scenario: Undetermined exits 2

**Given** a subcommand invoked against a repository where a precondition
cannot be determined (e.g. the ledger is unreadable, or a record version
is unknown)
**When** the subcommand completes
**Then** the process exits 2

#### Scenario: Subcommand without undetermined possibility documents it

**Given** a subcommand that cannot produce an undetermined outcome
**When** its `--help` output is inspected
**Then** the help text explicitly states that exit 2 is not produced by
this subcommand

### Requirement: Undetermined is never collapsed into a finding

The CLI SHALL never map an undetermined outcome to exit 1. An undetermined
condition MUST produce exit 2, never exit 1, regardless of how the
condition arises. The treachery the entire schema exists to avert — "a
corrupt ledger reads as clean" — is the defect class this requirement
removes by construction.

**Given** a subcommand encounters a condition it cannot determine
**When** the outcome is mapped to an exit code
**Then** the exit code is 2, never 1 and never 0

**Rationale**: §4.3 invariant. Collapsing undetermined into a finding (exit
1) or into clean (exit 0) silently masks the indeterminate case. The
sealed exit-code enum with exhaustive matching makes this a
compiler-enforced fact: the `Undetermined` case maps to exit 2 and no
`case _` default can redirect it.

#### Scenario: Unreadable ledger produces exit 2, not exit 1

**Given** a ledger file that exists but cannot be parsed (unknown record
version, corrupt content)
**When** the `ledger` or `chain-state` subcommand is invoked
**Then** the process exits 2 (undetermined), not 1 (finding) and not 0
(clean)

#### Scenario: Missing prerequisite produces exit 2, not exit 1

**Given** a subcommand that requires a prerequisite (e.g. a git repository,
a schema file) that is absent
**When** the subcommand is invoked
**Then** the process exits 2 (undetermined), not 1 (finding)

#### Scenario: Undetermined condition input is never collapsed (adversarial)

**Given** an undetermined condition (corrupt ledger, unknown record
version, missing prerequisite) — the exact input this requirement forbids
collapsing
**When** the outcome is mapped to an exit code
**Then** the exit code is 2 — it MUST NOT be 1 or 0, and any code path
that would produce 1 or 0 for this input is a defect

### Requirement: Output on undetermined is still emitted on stdout

The CLI SHALL emit its output on stdout even when the exit code is 2
(undetermined), so that unconditional readers (hook adapters, CI logs) see
the reason for the indeterminate state. The CLI MUST NOT suppress stdout
when the outcome is undetermined.

**Given** a subcommand that produces an undetermined outcome
**When** the process exits with code 2
**Then** stdout contains the output payload (including the reason for the
undetermined state), and stderr is reserved for diagnostics

**Rationale**: §4.1 invariant. `chain-state.sh`'s documented behavior is
that output on exit 2 is still emitted on stdout so unconditional readers
see the reason. A port that suppresses stdout on exit 2 breaks every
consumer that reads stdout unconditionally — the adapter would see an
empty payload and misinterpret the indeterminate state as a clean empty
result.

#### Scenario: Undetermined chain-state emits report on stdout

**Given** a `chain-state` invocation where the ledger is unreadable
**When** the process exits 2
**Then** stdout contains a report payload (with the undetermined reason),
not an empty stdout

#### Scenario: Undetermined gate emits payload on stdout

**Given** a `gate` invocation where a precondition cannot be determined
**When** the process exits 2
**Then** stdout contains the gate payload (with the undetermined reason),
and the hook adapter receives a non-empty payload

#### Scenario: Suppressed stdout on exit 2 is forbidden (adversarial)

**Given** an undetermined outcome where the implementation might be tempted
to suppress stdout (e.g. an exception path, an early return) — the exact
behavior this requirement forbids
**When** the process exits 2
**Then** stdout is non-empty — any code path that produces exit 2 with
empty stdout is a defect

### Requirement: Stdout payloads are byte-compatible with the contract files

The CLI output of each ported subcommand SHALL be byte-compatible with the
corresponding `.jq` contract (ledger record, chain-state report, gate
hook-json): the ported subcommand's stdout SHALL be accepted by the `.jq`
contract, and the current scripts' output SHALL be accepted by the ported
Scala validator. The stdout payloads MUST NOT deviate from the
contract-defined wire formats in any byte that the contract checks.

**Given** a ported subcommand that produces stdout governed by one of the
three `.jq` contracts
**When** the subcommand's stdout is piped to the corresponding `.jq`
contract
**Then** the `.jq` contract accepts the output (exit 0), and the same
output is accepted by the ported Scala validator

**Rationale**: §4.6 invariant. The `.jq` contracts are the single
executable statement of record formats, shared by implementation and
oracle. Byte-compatibility is the wire-format contract that makes the port
a drop-in replacement. R-M2 elevates this from a spot check to a property
test over the fixture corpus.

#### Scenario: Ledger output accepted by ledger record contract

**Given** the `ledger` subcommand producing a record on stdout
**When** the output is piped to the ledger record `.jq` contract
**Then** the contract accepts it (exit 0)

#### Scenario: Chain-state output accepted by chain-state report contract

**Given** the `chain-state` subcommand producing a report on stdout
**When** the output is piped to the chain-state report `.jq` contract
**Then** the contract accepts it (exit 0)

#### Scenario: Gate output accepted by gate hook-json contract

**Given** the `gate` subcommand producing a hook payload on stdout
**When** the output is piped to the gate hook-json `.jq` contract
**Then** the contract accepts it (exit 0)

#### Scenario: Current scripts' output accepted by ported validator

**Given** a fixture output captured from the current bash scripts
**When** the output is validated by the ported Scala validator
**Then** the validator accepts it (validation succeeds)

### Requirement: Arg parsing errors name the missing or invalid flag

Arg parsing errors SHALL name the missing or invalid flag in the error
message. Unknown subcommand, missing required value, invalid enum value,
and unknown flag each produce a single-line error on stderr with the
offending token in the message, and the process exits with a non-zero code.
The error message MUST NOT omit the offending token.

**Given** a CLI invocation with a parsing error (unknown subcommand,
missing required value, invalid enum, unknown flag)
**When** the parser encounters the error
**Then** a single-line error is emitted on stderr containing the offending
token, and the process exits with a non-zero code

**Rationale**: The predecessor `ledger.sh` had a `shift 2` defect that
caused an infinite loop parsing `--baseline` when one arg was left — the
error was silent. Typed arg parsing makes missing value a named parse error
by construction. Naming the offending token in the error message is the
minimum diagnostic a consumer needs to fix the invocation without opening
the source.

#### Scenario: Missing required value names the flag

**Given** a CLI invocation with `--baseline` but no value following it
**When** the parser encounters the missing value
**Then** the error message on stderr names `--baseline` as the flag
missing its value, and the process exits non-zero

#### Scenario: Unknown subcommand names the token

**Given** a CLI invocation with subcommand `foo` (not in the subcommand set)
**When** the parser encounters the unknown subcommand
**Then** the error message on stderr names `foo` as the unknown subcommand,
and the process exits non-zero

#### Scenario: Invalid enum value names the flag and value

**Given** a CLI invocation with `--event invalid` for the `gate`
subcommand (where `--event` expects a known event enum)
**When** the parser encounters the invalid enum value
**Then** the error message on stderr names `--event` and `invalid` as the
flag and invalid value, and the process exits non-zero

#### Scenario: Error without offending token is forbidden (adversarial)

**Given** a parse error where the implementation might emit a generic
message like "invalid arguments" without naming the token — the exact
behavior this requirement forbids
**When** the parser encounters any parsing error
**Then** the offending token appears in the error message — any error
message that omits the offending token is a defect

### Requirement: Help lists every flag with its default

The `--help` output SHALL list every flag accepted by the subcommand with
its default value (or "required" for mandatory flags, or "none" for flags
with no default). The help output MUST NOT omit any flag. A consumer
SHALL never need to open the source to discover the subcommand's
interface. Each subcommand's `--help` SHALL also document which exit codes
it can produce (0, 1, 2) and the conditions under which each is emitted.

**Given** a subcommand invoked with `--help`
**When** the help output is produced
**Then** every flag accepted by the subcommand is listed with its default
value, and the three-way exit protocol is documented for that subcommand

**Rationale**: The current scripts document flags only in their headers;
the CLI exposes them at runtime so a consumer never opens the source to
discover the interface. This is the port's one ergonomic improvement that
does not change behavior — it makes the existing interface discoverable.

#### Scenario: Help lists all flags with defaults

**Given** the `gate` subcommand invoked with `--help`
**When** the help output is produced
**Then** every flag (e.g. `--event`, `--change`, `--baseline`) is listed
with its default value or "required" marker

#### Scenario: Help documents exit codes

**Given** any subcommand invoked with `--help`
**When** the help output is produced
**Then** the output documents which of exit 0, 1, 2 the subcommand can
produce and the conditions for each

#### Scenario: Help omitting a flag is forbidden (adversarial)

**Given** a subcommand with a flag that the implementation might forget to
list in `--help` — the exact omission this requirement forbids
**When** the help output is produced
**Then** every flag is present — any flag missing from `--help` is a
defect

### Requirement: Multicall dispatch by argv(1) and argv(0)

The multicall binary SHALL dispatch to the named subcommand both by
argv(1) (the subcommand argument, e.g. `probatio gate --event …`) and by
argv(0) (the symlink basename, e.g. a symlink named `gate` pointing at the
binary). Both dispatch paths SHALL produce identical behavior for the same
subcommand. The CLI MUST NOT fail when only one dispatch signal is
present.

**Given** the multicall binary invoked either as `probatio <subcommand>
<args>` or as a symlink `<subcommand> <args>` where the symlink points at
the binary
**When** the dispatch mechanism resolves the subcommand
**Then** both paths select the same subcommand and produce identical
stdout, stderr, and exit code for the same arguments

**Rationale**: The hook shim uses the argv(1) form (`exec probatio gate
"$@"`); future install layouts use the argv(0) form (symlinks in a bin
directory). Both must work so the binary is fungible across install
strategies without reconfiguration.

#### Scenario: argv(1) dispatch works

**Given** the multicall binary invoked as `probatio gate --event
prompt-submit`
**When** the dispatch mechanism resolves the subcommand from argv(1)
**Then** the `gate` subcommand is selected and executes with the given
arguments

#### Scenario: argv(0) dispatch via symlink works

**Given** a symlink named `gate` pointing at the multicall binary,
invoked as `gate --event prompt-submit`
**When** the dispatch mechanism resolves the subcommand from argv(0)
**Then** the `gate` subcommand is selected and executes identically to the
argv(1) form

#### Scenario: argv(0) dispatch with alias works

**Given** a symlink named `prob` (the short alias) pointing at the
multicall binary, invoked as `prob gate --event prompt-submit`
**When** the dispatch mechanism resolves the subcommand
**Then** the `gate` subcommand is selected (argv(0) is the alias, argv(1)
is the subcommand)

#### Scenario: Dispatch failing on one signal is forbidden (adversarial)

**Given** an invocation where only one dispatch signal is present (e.g.
argv(0) is `gate` but argv(1) is not a subcommand, or argv(0) is `probatio`
and argv(1) is the subcommand) — the exact scenario where the
implementation might fail to dispatch
**When** the dispatch mechanism resolves the subcommand
**Then** the named subcommand is selected — any dispatch path that
fails when the other signal is absent is a defect

### Requirement: Append-only ledger surface — no mutation subcommands

The CLI SHALL NOT expose any subcommand that mutates, deletes, rewrites, or
edits a previously written ledger record. The only ledger-affecting
subcommand SHALL be `ledger` with an append action. No `update`, `delete`,
`rewrite`, or `edit` subcommand SHALL exist in the subcommand enum.

**Given** the CLI's subcommand enum
**When** the enum is inspected for mutation subcommands
**Then** no `update`, `delete`, `rewrite`, or `edit` subcommand exists, and
the `ledger` subcommand exposes only append (and read/validate) operations

**Rationale**: §4.2 invariant. The current script enforces append-only
before argument parsing by name-denylisting; the CLI enforces it by the
mutation subcommand not existing in the sealed enum. A subcommand that
doesn't exist is a parser error with the subcommand name in the error —
strictly stronger than denylisting (unparseable vs. denylisted).

#### Scenario: Ledger subcommand exposes only append

**Given** the `ledger` subcommand
**When** its available actions are enumerated
**Then** only `append` (and `read`/`validate`) operations are available;
no `update`, `delete`, `rewrite`, or `edit` action exists

#### Scenario: Mutation subcommand is unparseable (adversarial)

**Given** an invocation with a mutation subcommand name (`update`,
`delete`, `rewrite`, or `edit`) — the exact input this requirement forbids
**When** the parser encounters the mutation subcommand
**Then** the parser reports an unknown subcommand error naming the token,
and the mutation subcommand does not exist in the sealed enum — it is
unparseable, not merely denylisted

## Properties (Ring 3)

### Property: exit-code-mapping-is-total-and-disjoint

**Invariant**: The exit-code mapping is a total function from the outcome
enum to the exit-code enum. Every outcome maps to exactly one exit code in
{0, 1, 2}, and the three outcome cases map to distinct exit codes. No
other exit code is producible.

**Generator strategy**: `genOutcome` — constructive over the three
outcome cases (`Ran[A]`, `Finding`, `Undetermined`) with arbitrary
payloads. Edge cases: `Ran` with empty payload, `Finding` with one
finding, `Finding` with many findings, `Undetermined` with empty reason,
`Undetermined` with long reason. Coverage labels: `cover 33 "clean"`,
`cover 33 "finding"`, `cover 33 "undetermined"`.

```
property("exit-code-mapping-is-total-and-disjoint") {
  for outcome <- genOutcome.forAll
  yield
    val code = ExitCode.from(outcome)
    code == Outcome.toExitCode(outcome) &&
    (outcome match
      case Ran(_)      => code == 0
      case Finding     => code == 1
      case Undetermined => code == 2) &&
    code >= 0 && code <= 2
}
```

### Property: undetermined-never-collapses

**Invariant**: For every undetermined condition — regardless of the reason
(unreadable ledger, unknown record version, missing prerequisite,
non-lint failure) — the exit code is 2, never 1 and never 0.

**Generator strategy**: `genUndeterminedReason` — constructive over the
documented undetermined causes: corrupt ledger content, unknown record
version, missing schema file, missing git repository, spec-lint non-lint
failure. Edge cases: empty reason string, reason with special characters,
reason that resembles a finding message. Coverage labels: `cover 20
"unreadable_ledger"`, `cover 20 "unknown_version"`, `cover 20
"missing_prereq"`, `cover 20 "non_lint_failure"`, `cover 20 "other"`.

```
property("undetermined-never-collapses") {
  for reason <- genUndeterminedReason.forAll
  yield
    val outcome = Undetermined(reason)
    val code = ExitCode.from(outcome)
    code == 2 && code != 1 && code != 0
}
```

### Property: multicall-dispatch-equivalence

**Invariant**: For every subcommand and every argument list, dispatching
by argv(1) (`probatio <sub> <args>`) and dispatching by argv(0) (symlink
`<sub> <args>`) select the same subcommand and produce identical stdout,
stderr, and exit code.

**Generator strategy**: `genSubcommand` + `genArgs` — constructive over
the 16 subcommand names and arbitrary valid argument lists per
subcommand. Edge cases: subcommand with no args, subcommand with `--help`,
`metals` with each sub-subcommand, `install-skills` and `install-hooks`
with typical flags. Coverage labels: `cover 10 "gate"`, `cover 10
"spec-lint"`, `cover 10 "chain-state"`, `cover 10 "ledger"`, `cover 60
"other_subcommands"`.

```
property("multicall-dispatch-equivalence") {
  for (sub, args) <- genSubcommandAndArgs.forAll
  yield
    val byArgv1 = runProbatio(Array("probatio", sub) ++ args)
    val byArgv0 = runSymlink(sub, args)
    byArgv1.stdout == byArgv0.stdout &&
    byArgv1.stderr == byArgv0.stderr &&
    byArgv1.exitCode == byArgv0.exitCode
}
```

### Property: stdout-conformance-with-jq-contracts

**Invariant**: For every fixture in the corpus, the ported subcommand's
stdout is accepted by the corresponding `.jq` contract (exit 0), and the
current scripts' output is accepted by the ported Scala validator. This is
the CLI-level statement of R-P3; the full bidirectional conformance
property (R-M2) lives in the `migration-protocol` spec.

**Generator strategy**: `genFixture` — constructive over the existing
`tests/fixtures/` corpus plus derived generators producing records that
satisfy or violate each of the 12 ledger clauses (and analogously for the
two report contracts). Edge cases: minimal valid record, record at clause
boundary, record violating exactly one clause. Coverage labels: `cover 80
"valid_fixtures"`, `cover 20 "invalid_fixtures"`.

```
property("stdout-conformance-with-jq-contracts") {
  for fixture <- genFixture.forAll
  yield
    val stdout = runSubcommand(fixture.subcommand, fixture.args).stdout
    val jqAccepts = jqContract(fixture.contractFile, stdout) == 0
    val validatorAccepts = scalaValidator(fixture.contract, stdout).isRight
    jqAccepts && validatorAccepts
}
```

### Property: arg-parse-error-attribution

**Invariant**: Every arg-parse error message contains the offending token
(the unknown subcommand name, the missing flag name, or the invalid value).
No parse error message is generic (lacking the token).

**Generator strategy**: `genParseError` — constructive over the four
error kinds: `UnknownSubcommand`, `MissingValue`, `InvalidEnum`,
`UnknownFlag`, each with an arbitrary offending token. Edge cases: token
with special characters, token that resembles a valid flag, empty token.
Coverage labels: `cover 25 "unknown_subcommand"`, `cover 25
"missing_value"`, `cover 25 "invalid_enum"`, `cover 25 "unknown_flag"`.

```
property("arg-parse-error-attribution") {
  for error <- genParseError.forAll
  yield
    val msg = renderError(error)
    msg.contains(error.offendingToken) && msg.nonEmpty
}
```

### Property: help-lists-every-flag

**Invariant**: For every subcommand, the `--help` output lists every flag
accepted by that subcommand with its default value (or "required" / "none"
marker). No flag accepted by the subcommand is missing from the help
output.

**Generator strategy**: `genSubcommand` — constructive over the 16
subcommand names. The property cross-references the subcommand's declared
flags (from the arg parser definition) against the help output. Edge
cases: subcommand with no flags, subcommand with only required flags,
subcommand with optional flags. Coverage labels: `cover 100
"all_subcommands"`.

```
property("help-lists-every-flag") {
  for sub <- genSubcommand.forAll
  yield
    val help = runProbatio(Array("probatio", sub, "--help")).stdout
    val declaredFlags = subcommandFlags(sub)
    declaredFlags.forall(flag => help.contains(flag.name)) &&
    declaredFlags.forall(flag => help.contains(flag.defaultOrRequired))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A subcommand named `update`, `delete`, `rewrite`, or `edit` in the `Subcommand` sealed enum | Append-only ledger invariant (§4.2) — mutation subcommands must not exist | `assertDoesNotCompile("Subcommand.update")` — the constructor does not exist |
| An exit code other than 0, 1, or 2 produced by the exit-code mapping | Three-way exit protocol (§4.1) — only 0/1/2 are valid | Exhaustive match on sealed `Outcome` enum with no `case _` default; `-Wconf:cat=pattern-match-exhaustivity:e` makes non-exhaustive match a compile error |
| A `case _` default in the exit-code mapping that could collapse `Undetermined` to exit 1 | §4.3 invariant — undetermined is never collapsed into a finding | Exhaustive match on sealed `Outcome` with `-Werror` on pattern-match exhaustiveness; `case _` is unreachable (compile warning escalated to error) |
| A `CliError` variant without an `offendingToken` field | R-P4 — every parse error must name the offending token | The `CliError` sealed trait requires `offendingToken: String` on every variant; a variant without it does not compile |
| The `probatio` binary linking an adk4s module | R-ARCH1 — probatio depends on nothing adk4s-side | Build-level dependency-lint rule fails the build if any `workflow/*` project's classpath reaches an adk4s module |

## Ring 6 Cross-Reference (formal contracts live in probatio-core)

The CLI boundary's formal property is the exit-code mapping's totality and
disjointness — a total function from a sealed enum (three cases) to {0, 1,
2}. This is trivially total by construction (exhaustive match on a sealed
trait with `-Werror` on pattern-match exhaustiveness), so no separate
PureScala mirror is required for the mapping itself.

The pure kernels that the CLI delegates to — chain-state verdict logic
(`(SpecLintReport, Ledger, Requirements, Baseline) →
Either[Undetermined, ChainStateReport]`), the 12-clause ledger contract
validator, and the banner/drift engine — live in `probatio-core` and are
formally modeled in the `probatio-core` spec's Ring 6 section, with bridge
property tests binding shipped code to the model. The CLI boundary does
not re-implement any decision logic; it maps the `Outcome` returned by
`probatio-core` to an exit code and emits the `probatio-core`-produced
payload on stdout.

**Cross-reference**: the formal contracts and bridge property tests for the
decision kernels are in `specs/probatio-core/spec.md` → "Ring 6 Formal
Contracts". The conformance property (validator ⊨ `.jq` contract, both
directions) is in `specs/migration-protocol/spec.md` → R-M2. No separate
Ring 6 model or bridge test is created by this spec — the CLI boundary is
arg parsing + exit-code mapping, both enforced by compile-negative
obligations and Hedgehog properties (Ring 3), not by a PureScala mirror.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| One subcommand per predecessor script | Requirement: One subcommand per predecessor script | CLI surface snapshot test (enumerate subcommands, assert 1:1 with predecessor set) | CLI surface snapshot test (to be created in probatio-cli test sources) |
| No subcommands beyond predecessor set | Requirement: One subcommand per predecessor script | CLI surface snapshot test + compile-negative (sealed enum) | CLI surface snapshot test + Subcommand sealed enum (to be created) |
| Three-way exit protocol for every subcommand | Requirement: Three-way exit protocol for every subcommand | bats oracle (unchanged) + property test (exit-code-mapping-is-total-and-disjoint) | existing bats oracle + Hedgehog exit-code property test (to be created) |
| Undetermined never collapsed into finding | Requirement: Undetermined is never collapsed into a finding | property test (undetermined-never-collapses) + compile-negative (exhaustive match, no `case _`) | Hedgehog undetermined-collapse property test + Outcome sealed enum (to be created) |
| Output on undetermined emitted on stdout | Requirement: Output on undetermined is still emitted on stdout | bats oracle (chain-state, gate) + scenario test | existing bats oracle + CLI output scenario test (to be created) |
| Stdout payloads byte-compatible with contracts | Requirement: Stdout payloads are byte-compatible with the contract files | conformance property test (stdout-conformance-with-jq-contracts) + bats oracle | Hedgehog stdout-conformance property test + existing bats oracle |
| Arg parsing errors name the flag | Requirement: Arg parsing errors name the missing or invalid flag | property test (arg-parse-error-attribution) + arg-parse oracle extension | Hedgehog arg-parse property test (to be created) + arg-parse bats extension (to be created) |
| Help lists every flag with default | Requirement: Help lists every flag with its default | property test (help-lists-every-flag) + scenario test | Hedgehog help-completeness property test (to be created) |
| Multicall dispatch by argv(1) and argv(0) | Requirement: Multicall dispatch by argv(1) and argv(0) | property test (multicall-dispatch-equivalence) + scenario test | Hedgehog multicall-dispatch property test (to be created) |
| Append-only ledger surface | Requirement: Append-only ledger surface — no mutation subcommands | compile-negative (sealed enum, no mutation constructor) + CLI surface snapshot test | Subcommand sealed enum + CLI surface snapshot test (to be created) |
| Exit-code mapping totality | Property: exit-code-mapping-is-total-and-disjoint | property test (Hedgehog) | Hedgehog exit-code property test (to be created) |
| Multicall dispatch equivalence | Property: multicall-dispatch-equivalence | property test (Hedgehog) | Hedgehog multicall-dispatch property test (to be created) |
| Stdout conformance | Property: stdout-conformance-with-jq-contracts | property test (Hedgehog) + jq contract fixtures | Hedgehog stdout-conformance property test (to be created) |
| Arg-parse error attribution | Property: arg-parse-error-attribution | property test (Hedgehog) | Hedgehog arg-parse property test (to be created) |
| Help completeness | Property: help-lists-every-flag | property test (Hedgehog) | Hedgehog help-completeness property test (to be created) |
| No adk4s dependency (R-ARCH1) | Requirement: One subcommand per predecessor script (R-ARCH1 cross-cutting) | build-level dependency-lint rule (CI step) | build.sbt dependency-lint rule (to be created) |
| No mutation subcommand in enum | Requirement: Append-only ledger surface — no mutation subcommands | compile-negative test | Subcommand compile-negative test (to be created) |
| No exit code outside {0,1,2} | Requirement: Three-way exit protocol for every subcommand | compile-negative (exhaustive match + `-Werror`) | ExitCode sealed enum with exhaustive match (to be created) |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `probatio` multicall binary | native-image launcher | `org.sinemenda.probatio.cli` | Single binary dispatching to all subcommands; alias `prob` |
| `Subcommand` | sealed enum | `org.sinemenda.probatio.cli` | 16 entries: `Gate`, `SpecLint`, `ChainState`, `Ledger`, `Checkpoint`, `RegistryCheck`, `Reconcile`, `Scan`, `RemovalAudit`, `DangerScan`, `ImpactScan`, `Metals`, `ConceptScanner`, `Graph`, `InstallSkills`, `InstallHooks` |
| `ExitCode` | sealed enum | `org.sinemenda.probatio.cli` | `Clean` → 0, `Finding` → 1, `Undetermined` → 2; exhaustive match on `Outcome[A]` |
| `Outcome[A]` | sealed enum (`Ran[A]`, `Finding`, `Undetermined`) | `org.sinemenda.probatio` | Introduced by `probatio-core` spec; used at the CLI boundary for exit-code mapping |
| `CliError` | sealed trait | `org.sinemenda.probatio.cli` | `UnknownSubcommand(token)`, `MissingValue(flag)`, `InvalidEnum(flag, value)`, `UnknownFlag(flag)` — each carries `offendingToken: String` |
| `MulticallDispatch` | module | `org.sinemenda.probatio.cli` | Resolves subcommand from argv(1) (subcommand arg) or argv(0) (symlink basename); `prob` alias resolves to argv(1) dispatch |
| `HelpOutput` | case class | `org.sinemenda.probatio.cli` | `subcommand: Subcommand`, `flags: List[FlagHelp]`, `exitCodes: List[ExitCodeDoc]`; rendered to stdout on `--help` |
| `mainargs` | dependency (com.lihaoyi) | `build.sbt` | Arg-parsing library; `@main` entrypoints, one per subcommand; typed parsing makes missing value a named parse error by construction |
| `ProbatioMain` | main class | `org.sinemenda.probatio.cli` | Multicall entry point; reads argv(0) and argv(1), dispatches to `@main` entrypoint |
| `@main` entrypoints | functions | `org.sinemenda.probatio.cli` | One per subcommand: `Gate`, `SpecLint`, `ChainState`, `Ledger`, etc.; each returns `Outcome[Int]` mapped to exit code |
| Exit-code mapping | function | `org.sinemenda.probatio.cli` | `ExitCode.from(outcome: Outcome[A]): Int` — exhaustive match, no `case _` default; `-Wconf:cat=pattern-match-exhaustivity:e` enforces totality |
| `os-lib` | dependency (com.lihaoyi) | `build.sbt` | Filesystem/path library for CLI I/O (reading ledger, schema, fixtures) |
| `uPickle` / `ujson` | dependency (com.lihaoyi) | `build.sbt` | JSON emit for stdout payloads; replaces hand-rolled sed/awk JSON escaping |
| `probatioScalacOptions` | build setting | `build.sbt` | Strict scalac flags scoped to probatio subprojects: `-Werror`, deprecation/feature escalation, `-Wvalue:discard`, `-Ysafe-init` (R-CS1–R-CS5) |
| Dependency-lint rule | build-level check | `build.sbt` | R-ARCH1: fails if any `workflow/*` project's classpath reaches an adk4s module |
| `CliSurfaceSpec` | test suite | `probatio-cli/src/test` | Snapshot test: enumerate subcommands, assert 1:1 with predecessor set, assert no mutation subcommands |
| `ExitCodeSpec` | test suite | `probatio-cli/src/test` | Hedgehog properties: exit-code-mapping-is-total-and-disjoint, undetermined-never-collapses |
| `MulticallDispatchSpec` | test suite | `probatio-cli/src/test` | Hedgehog property: multicall-dispatch-equivalence; scenario tests for argv(1) and argv(0) |
| `CliConformanceSpec` | test suite | `probatio-cli/src/test` | Hedgehog property: stdout-conformance-with-jq-contracts over fixture corpus |
| `CliParseErrorSpec` | test suite | `probatio-cli/src/test` | Hedgehog property: arg-parse-error-attribution; scenario tests for each error kind |
| `CliHelpSpec` | test suite | `probatio-cli/src/test` | Hedgehog property: help-lists-every-flag; scenario tests for help output |
| `CliOutputSpec` | test suite | `probatio-cli/src/test` | Scenario tests: output on exit 2 is non-empty on stdout |
| `SubcommandTypeContract` | compile-negative test | `probatio-cli/src/test` | `assertDoesNotCompile("Subcommand.update")` etc. |
| `arg-parse.bats` | oracle extension | `openspec/schemas/verified-scala3/tests/` | Bats tests for arg-parse error attribution, extending the existing oracle |
