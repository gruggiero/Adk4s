package org.sinemenda.probatio.guard

/**
 * The oracle-sanction model (spec 5 of `finish-probatio-replacement`,
 * oracle-independence).
 *
 * The acceptance oracle is worth something only because it was written
 * from the specs, not from the implementation. The migration protocol's
 * Gate action requires the oracle to pass unmodified; this model gives
 * that rule a checkable meaning — the oracle holds only behavioural
 * tests, and every modification since the recorded baseline cites the
 * requirement that made it necessary.
 *
 * `SanctionVerdict` supersedes `OracleImmutabilityResult`: "modified"
 * stops being a verdict on its own — a modification is either sanctioned
 * or it is a finding. The third variant, `Undeterminable`, is new
 * reachable behaviour: an unreadable baseline, history or sanction
 * record is could-not-determine, never a pass.
 *
 * `OracleModification` is the guard's unit of history: one oracle file
 * changed in one commit since the recorded baseline. It is a supporting
 * carrier for `SanctionVerdict.Unsanctioned` — the spec introduces four
 * concepts; this fifth small type keeps the failing verdict typed rather
 * than a pair of bare strings (flagged at the Step-1 gate).
 *
 * spec: oracle-independence — Concepts Introduced (new): OracleTestKind
 * spec: oracle-independence — Concepts Introduced (new): OracleSanction
 * spec: oracle-independence — Concepts Introduced (new): SanctionVerdict
 * spec: oracle-independence — Concepts Introduced (new): OracleBaseline
 */

/**
 * Whether a test observes a tool's behaviour — runs it, reads its output
 * and status — or asserts over its source text. Only behavioural tests
 * belong to the acceptance oracle; a structural test lives in the
 * implementation-shape suite where following the implementation is
 * expected.
 *
 * spec: oracle-independence — Requirement: The acceptance oracle holds only behavioural tests
 */
enum OracleTestKind:
  case Behavioural
  case Structural

/**
 * One modification of an acceptance-oracle file since the recorded
 * baseline: the file that changed and the commit that changed it. The
 * guard's history is a list of these; a failing verdict names them by
 * exactly this pair.
 */
final case class OracleModification(file: String, commit: String)

/**
 * One commit of the oracle's history, newest-first as `git log` emits
 * it. `message` is carried because real history has one — and because
 * the adversarial scenario plants the old search phrase in it — but the
 * guard's decision NEVER inspects it: the baseline is the recorded
 * commit, not the commit a message search happens to find.
 *
 * spec: oracle-independence — Scenario: Adversarial — a commit message containing the old search phrase does not move the baseline
 */
final case class HistoryEntry(commit: String, message: String, files: List[String])

/**
 * A persisted record that one modification of an oracle file was
 * required by a named requirement of a named spec. `spec` and
 * `requirement` are required fields — a sanction that names no
 * requirement is the rubber stamp this spec prevents (compile-negative).
 *
 * spec: oracle-independence — Requirement: Every oracle modification cites the requirement that made it necessary
 * spec: oracle-independence — Compile-Negative: A sanction with no cited requirement
 */
final case class OracleSanction(
  file: String,
  commit: String,
  spec: String,
  requirement: String
)

/**
 * The commit from which oracle modifications are counted — recorded
 * explicitly beside the oracle, never discovered by searching commit
 * messages. Whether the recorded value resolves to a commit is checked
 * by the adapter reading the record; an unresolvable baseline is the
 * `Undeterminable` case, not a silent default.
 *
 * spec: oracle-independence — Requirement: The baseline is recorded, not discovered
 */
final case class OracleBaseline(commit: String)

/**
 * The per-sanction check behind `acceptedModifications`: a sanction is
 * accepted only when the cited requirement resolves AND its text names
 * the modified file or one of its tests. The rejected variants carry the
 * sanction so a rejection names both the modification and the cited
 * requirement.
 *
 * spec: oracle-independence — Scenario: Adversarial — a sanction citing an unrelated requirement is rejected
 * spec: oracle-independence — Scenario: Adversarial — a sanction citing a requirement that does not exist is rejected
 */
enum SanctionCheck:
  /** The cited requirement resolved and names the file or a test in it. */
  case Accepted(sanction: OracleSanction)
  /** The cited requirement resolved but names neither file nor test. */
  case RejectedUnrelated(sanction: OracleSanction)
  /** The cited requirement does not exist in the cited spec. */
  case RejectedUnresolvable(sanction: OracleSanction)

/**
 * A structural finding: a test asserting over a tool's source text,
 * named by the file that holds it and its `@test` title — the pair a
 * "structural test left in the oracle" report must carry.
 *
 * spec: oracle-independence — Scenario: Adversarial — a structural test left in the oracle is reported
 */
final case class StructuralTest(file: String, title: String)

/**
 * The immutability guard's decision against the sanction record.
 *
 *   - `AllSanctioned` carries the baseline the verdict was earned
 *     against — a passing verdict with an implicit baseline cannot be
 *     reproduced (compile-negative: `AllSanctioned` without the
 *     baseline does not typecheck where a `SanctionVerdict` is
 *     required).
 *   - `Unsanctioned` names each uncovered modification by file and
 *     commit.
 *   - `Undeterminable` is could-not-determine: an unreadable input is
 *     never a pass.
 *
 * spec: oracle-independence — Requirement: The guard passes exactly when every modification is sanctioned
 * spec: oracle-independence — Type-Widening Impact (supersedes OracleImmutabilityResult)
 */
enum SanctionVerdict:
  case AllSanctioned(baseline: OracleBaseline)
  case Unsanctioned(modifications: List[OracleModification])
  case Undeterminable(reason: String)

  /** True iff every modification since the baseline is sanctioned. */
  def isAllSanctioned: Boolean = this match
    case SanctionVerdict.AllSanctioned(_) => true
    case _                              => false

  /** True iff at least one modification has no accepted sanction. */
  def isUnsanctioned: Boolean = this match
    case SanctionVerdict.Unsanctioned(_) => true
    case _                             => false

  /** True iff an input could not be read — never a pass. */
  def isUndeterminable: Boolean = this match
    case SanctionVerdict.Undeterminable(_) => true
    case _                               => false

  /** The modifications an unsanctioned verdict names; empty otherwise. */
  def namedModifications: List[OracleModification] = this match
    case SanctionVerdict.Unsanctioned(mods) => mods
    case _                                => List.empty[OracleModification]
