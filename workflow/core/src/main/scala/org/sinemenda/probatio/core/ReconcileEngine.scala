package org.sinemenda.probatio.core

/**
 * How a recorded run's outcome is supported (spec 6).
 *
 * The five classes partition the record set — every record receives
 * exactly one:
 *
 *  - `SelfObserved` — the record carries its own observation: a `digest`
 *    (a `ledger run` row — the recorder executed the command and read
 *    its own exit code) or `source: "ambient"` (the gate's own
 *    observation of the harness outcome). Nothing to corroborate.
 *  - `Witnessed` — a written green claim for which an ambient record at
 *    the same key recorded the same outcome. The observing record is
 *    carried: a witness classification without a witness is testimony
 *    wearing the wrong label.
 *  - `Testimony` — a written green claim on a deterministic ring that no
 *    ambient record observed.
 *  - `Contradicted` — a written green claim whose ambient observers at
 *    the same key all recorded a different exit.
 *  - `Exempt` — a judgment-ring record (R2/R8/manual — no deterministic
 *    command exists to witness it) or a record of a failing run (it
 *    discharges nothing, so it needs no witness).
 *
 * spec: danger-reconcile-engines — Concepts Introduced (new): Corroboration
 * spec: danger-reconcile-engines — Compile-Negative: Corroboration.Witnessed constructed without the observing record
 */
enum Corroboration:
  case SelfObserved

  /**
   * `observer` is an ambient record at the claim's key carrying the
   * claim's outcome; `preceding`/`following` are the ambient records at
   * that key before/after it in ledger order. The full ambient set —
   * the predecessor's `$w` — is `preceding ++ (observer :: following)`:
   * the observer is a required field, so "witnessed without a witness"
   * is unconstructible, and `ClaimVerdict.observed` keeps the
   * predecessor's `$w | map(.exit)` ledger order.
   */
  case Witnessed(
    observer: ValidatedRecord,
    preceding: List[ValidatedRecord],
    following: List[ValidatedRecord]
  )
  case Testimony

  /**
   * `observer :: others` is the full set of ambient records at the
   * claim's key, none carrying the claim's outcome.
   */
  case Contradicted(observer: ValidatedRecord, others: List[ValidatedRecord])
  case Exempt

object Corroboration:

  /** The predecessor's verdict token for the reportable classes. */
  def verdictToken(c: Corroboration): String = c match
    case Witnessed(_, _, _) => "witnessed"
    case Testimony          => "testimony"
    case Contradicted(_, _) => "contradicted"
    case SelfObserved       => "self-observed"
    case Exempt             => "exempt"

/**
 * One claim's verdict entry — the shape the predecessor emits per claim
 * in the report's `testimony`/`contradicted` arrays: the claim's key
 * fields, the verdict token, and `observed` — the exits of every ambient
 * record at the key (empty for testimony).
 */
final case class ClaimVerdict(
  spec: String,
  ring: Ring,
  obligation: String,
  command: String,
  baseline: String,
  verdict: String,
  observed: List[Int]
)

/**
 * The per-row corroboration classification for a change (spec 6).
 *
 * `classifications` covers every in-scope record exactly once — the
 * classes partition the record set by construction. Every count and
 * verdict list is a derived view of `classifications`, so the summary
 * can never disagree with the contents.
 *
 * The report deliberately carries NO discharge verdict: whether an
 * obligation is discharged is the correctness computation's decision
 * (chain-state), not corroboration's. Two tools answering the same
 * question is how the predecessor's three parallel parsers came to
 * disagree.
 *
 * spec: danger-reconcile-engines — Concepts Introduced (new): ReconcileReport
 * spec: danger-reconcile-engines — Requirement: Corroboration does not decide whether an obligation is discharged
 */
final case class ReconcileReport private (
  change: String,
  classifications: List[ReconcileEngine.Classified]
):
  // A public `copy` would admit a report whose views no longer derive
  // from its rows. Sealed shut; intentionally never invoked.
  @scala.annotation.nowarn("msg=unused private member")
  private def copy(
    change: String = change,
    classifications: List[ReconcileEngine.Classified] = classifications
  ): ReconcileReport = new ReconcileReport(change, classifications)

  /** The number of in-scope records (the predecessor's `rows`). */
  def rows: Int = classifications.length

  /** The number of ambient (witness) records in scope. */
  def witnesses: Int =
    classifications.count((c: ReconcileEngine.Classified) => c.record.provenance.source.contains("ambient"))

  /**
   * The verdict entries for every claim row, in record order —
   * witnessed, testimony, and contradicted. The predecessor's `claims`
   * count is `claims.length`.
   */
  def claims: List[ClaimVerdict] =
    classifications.flatMap { (c: ReconcileEngine.Classified) =>
      c.corroboration match
        case Corroboration.Witnessed(_, _, _) => List(verdictOf(c))
        case Corroboration.Testimony          => List(verdictOf(c))
        case Corroboration.Contradicted(_, _) => List(verdictOf(c))
        case Corroboration.SelfObserved | Corroboration.Exempt =>
          List.empty[ClaimVerdict]
    }

  /** The witnessed claims, in record order. */
  def witnessed: List[ClaimVerdict] = claims.filter(_.verdict == "witnessed")

  /** The testimony claims, in record order. */
  def testimony: List[ClaimVerdict] = claims.filter(_.verdict == "testimony")

  /** The contradicted claims, in record order. */
  def contradicted: List[ClaimVerdict] = claims.filter(_.verdict == "contradicted")

  /** True when at least one claim is testimony or contradicted. */
  def hasFindings: Boolean = testimony.nonEmpty || contradicted.nonEmpty

  /** Build a claim's verdict entry — the predecessor's verdict object. */
  private def verdictOf(c: ReconcileEngine.Classified): ClaimVerdict =
    val observed: List[Int] = c.corroboration match
      case Corroboration.Witnessed(o, before, after) =>
        (before ++ (o :: after)).map(_.record.exit)
      case Corroboration.Contradicted(o, others) => (o :: others).map(_.record.exit)
      case Corroboration.Testimony | Corroboration.SelfObserved | Corroboration.Exempt =>
        List.empty[Int]
    ClaimVerdict(
      spec = c.record.record.spec,
      ring = c.record.record.ring,
      obligation = c.record.record.obligation,
      command = c.record.record.command,
      baseline = c.record.record.baseline,
      verdict = Corroboration.verdictToken(c.corroboration),
      observed = observed
    )

object ReconcileReport:

  /**
   * The only construction route — the report wraps the engine's
   * per-record classifications; every count and list is a derived view,
   * so the summary cannot disagree with the contents.
   */
  def of(
    change: String,
    classifications: List[ReconcileEngine.Classified]
  ): ReconcileReport =
    new ReconcileReport(change, classifications)

/**
 * The pure corroboration classifier (spec 6) — the reconcile half.
 *
 * No file I/O, no environment reads: the record set arrives already
 * read and validated by the CLI adapter (`readLedgerFile` +
 * `Ledger.readValidated`), and the report is data. The classifier
 * answers only "does this row's outcome have support beyond its
 * writer's assertion" — never "is the obligation discharged" (that is
 * `ChainState.compute`'s decision; two tools answering the same
 * question is the defect the boundary exists to prevent).
 *
 * spec: danger-reconcile-engines — Requirement: A recorded run whose outcome only its writer asserted is reported as uncorroborated
 * spec: danger-reconcile-engines — Implementation Anchor: ReconcileEngine
 */
object ReconcileEngine:

  /** One in-scope record paired with its corroboration class. */
  final case class Classified(record: ValidatedRecord, corroboration: Corroboration)

  /**
   * The rings whose discharge is a human judgment — no deterministic
   * command exists to witness them. The predecessor's
   * `["R2", "R8", "manual"]`.
   */
  val judgmentRings: Set[Ring] = Set(Ring.R2, Ring.R8, Ring.Manual)

  /**
   * Classify every record whose `change` matches, optionally narrowed by
   * `spec`/`baseline` — the predecessor's `$rows` filter, applied before
   * claims and witnesses are derived (a witness outside the filter does
   * not corroborate).
   *
   * A claim is a written record of a green run (`exit == 0`) on a
   * deterministic ring carrying neither `digest` nor `source: "ambient"`.
   * A witness is an ambient record at the same (spec, ring, baseline,
   * command) — the witness watched THE SAME COMMAND, per the
   * predecessor's comment and `ambient-capture-wiring.bats`.
   */
  def classify(
    records: List[ValidatedRecord],
    change: String,
    spec: Option[String],
    baseline: Option[String]
  ): ReconcileReport =
    // The predecessor's $rows filter — applied BEFORE claims and
    // witnesses are derived, so a witness outside the filter does not
    // corroborate.
    val inScope: List[ValidatedRecord] = records.filter { (r: ValidatedRecord) =>
      r.record.change == change &&
      spec.forall((s: String) => s == r.record.spec) &&
      baseline.forall((b: String) => b == r.record.baseline)
    }
    val ambient: List[ValidatedRecord] =
      inScope.filter((r: ValidatedRecord) => r.provenance.source.contains("ambient"))
    def sameKey(claim: ValidatedRecord, observer: ValidatedRecord): Boolean =
      observer.record.spec == claim.record.spec &&
        observer.record.ring == claim.record.ring &&
        observer.record.baseline == claim.record.baseline &&
        observer.record.command == claim.record.command
    val classifications: List[Classified] = inScope.map { (r: ValidatedRecord) =>
      val corroboration: Corroboration =
        if r.provenance.digest.nonEmpty || r.provenance.source.contains("ambient") then Corroboration.SelfObserved
        else if judgmentRings.contains(r.record.ring) || r.record.exit != 0 then Corroboration.Exempt
        else
          ambient.filter((o: ValidatedRecord) => sameKey(r, o)) match
            case Nil             => Corroboration.Testimony
            case first :: others =>
              // Witnessed iff an ambient record at the key carried the
              // claim's (green) exit — the first such record is the
              // observer; `preceding`/`following` keep the full key set
              // in ledger order so `observed` is the predecessor's `$w`.
              val atKey: List[ValidatedRecord] = first :: others
              atKey.span((o: ValidatedRecord) => o.record.exit != 0) match
                case (preceding, witness :: following) =>
                  Corroboration.Witnessed(witness, preceding, following)
                case (_, Nil) =>
                  Corroboration.Contradicted(first, others)
      Classified(r, corroboration)
    }
    ReconcileReport.of(change, classifications)
