package org.sinemenda.probatio.core

/**
 * The chain-state computation as a pure function (R-C3).
 *
 * Computes chain state over `(per-spec lint outcomes, ledger, extracted
 * requirements, baselines, change)`. Reads no files internally; all file
 * I/O, git queries, and subprocesses live in the CLI layer. The same
 * inputs always produce the same output — no environment variables,
 * wall-clock time, or external state.
 *
 * spec: probatio-core — Requirement: Chain-state computation is referentially transparent
 * spec: probatio-core — Property: Chain-state computation is referentially transparent
 * spec: chain-state-attribution — Requirement: The correctness verdict is computed over the change's actual requirements
 */
object ChainState:

  /** A requirement in the requirements list — its owning spec name and title. */
  final case class Requirement(
    spec: String,
    requirement: String
  )

  /**
   * Compute chain state from declared inputs.
   *
   * Returns either an undetermined result with a reason, or a chain-state
   * report with bound, resolved, and discharged counts.
   *
   * Parameters:
   *
   * @param lints
   *   the per-spec lint outcomes — spec name → the `Outcome` of linting
   *   that spec's document. A run that did not complete
   *   (`Outcome.Undetermined`/`Outcome.Finding`) makes the whole result
   *   undetermined, and a spec named by `reqs.specNames` that has no entry
   *   here is a lint/extraction disagreement — also undetermined. Findings
   *   inside a report are data, not a run failure: a `Ran` report carrying
   *   FAIL findings still produces a measured verdict.
   * @param ledger
   *   every validated record in the ledger file, UNFILTERED — the per-spec
   *   baseline matching and the forgive-unchanged check are applied inside,
   *   so the staleness rejection stays a pure, kernel-mirrorable function.
   * @param reqs
   *   the extracted requirement set — the entrypoint may not pass a bare
   *   `List[Requirement]`: the set is produced by `RequirementExtractor`,
   *   carries its `FactSource`, and an empty `requirements` list is a fact
   *   about the change, not a placeholder for an extraction that never ran.
   * @param specBaselines
   *   spec name → that spec's RESOLVED baseline (the per-spec baseline map
   *   from `implementation-progress.md` after `git rev-parse`; empty when no
   *   map was parsed). A row for that spec counts when its baseline is the
   *   spec's resolved baseline or `resolvedBaseline` — the predecessor reads
   *   the ledger once per mapped spec and once unfiltered, so both baselines
   *   admit evidence.
   * @param baseline
   *   the effective baseline echoed into the report (the predecessor's
   *   `EFFECTIVE_BASELINE` — the progress-file fallback when present, else
   *   the gate's `--baseline`, carried verbatim, NOT `rev-parse`d).
   * @param resolvedBaseline
   *   `baseline` after `git rev-parse` — the staleness filter the ledger
   *   read applies to specs absent from `specBaselines` (the predecessor's
   *   `full_effective`). Callers pass the same string twice when no
   *   resolution step ran.
   * @param change
   *   the change name — only ledger records for this change are considered.
   * @param artifactUnchanged
   *   `(rowBaseline, artifact) => Boolean` — the forgive-unchanged oracle.
   *   A ledger record whose baseline is stale for its spec still counts as
   *   evidence iff the artifact it ran has not changed since the row's
   *   baseline (the predecessor's `git diff --quiet <row.baseline> HEAD --
   *   <artifact>`). The CLI supplies the git-backed predicate; pass
   *   `(_, _) => false` to disable forgiveness entirely.
   *
   * spec: probatio-core — Scenario: same inputs produce same output
   * spec: probatio-core — Scenario: an unreadable ledger yields undetermined, not zero
   * spec: probatio-core — Scenario: a failed lint yields undetermined, not zero
   * spec: probatio-core — Scenario: a genuinely empty ledger is reported as zero discharged
   * spec: chain-state-attribution — Requirement: A requirement whose obligations cannot be attributed is never counted as discharged
   * spec: chain-state-attribution — Requirement: An obligation that maps to no known requirement is reported separately, never dropped and never misattributed
   * spec: chain-state-attribution — Compile-Negative: ChainState.compute with a literal Nil requirement list
   */
  def compute(
    lints: Map[String, Outcome[LintReport]],
    ledger: Ledger.LedgerData,
    reqs: RequirementSet,
    specBaselines: Map[String, String],
    baseline: String,
    resolvedBaseline: String,
    change: String,
    artifactUnchanged: (String, String) => Boolean
  ): Either[ChainStateUndetermined, ChainStateReport] =
    // Every spec that was read (or produced a requirement) must have a
    // completed lint run — a missing or failed lint means the bound/resolved
    // facts for its requirements were never measured, which is undetermined,
    // never a clean report.
    val neededSpecs: List[String] =
      (reqs.specNames ++ reqs.requirements.map(_.spec)).distinct
    val lintBySpec: Either[ChainStateUndetermined, Map[String, LintReport]] =
      neededSpecs.foldLeft[Either[ChainStateUndetermined, Map[String, LintReport]]](
        Right(Map.empty)
      ) { (acc, spec) =>
        acc.flatMap { (m: Map[String, LintReport]) =>
          lints.get(spec) match
            case Some(Outcome.Ran(report)) => Right(m + (spec -> report))
            case Some(Outcome.Undetermined(reason)) =>
              Left(
                ChainStateUndetermined(
                  change,
                  baseline,
                  s"spec-lint outcome for spec '$spec' did not complete ($reason); cannot determine bound/resolved"
                )
              )
            case Some(Outcome.Finding(reason)) =>
              Left(
                ChainStateUndetermined(
                  change,
                  baseline,
                  s"spec-lint outcome for spec '$spec' did not complete ($reason); cannot determine bound/resolved"
                )
              )
            case None =>
              Left(
                ChainStateUndetermined(
                  change,
                  baseline,
                  s"spec-lint outcome for spec '$spec' did not complete; cannot determine bound/resolved"
                )
              )
        }
      }
    lintBySpec match
      case Left(u)       => Left(u)
      case Right(bySpec) =>
        // Per-spec finding indexes: the F9 lines (degraded join) and the F9
        // artifact tokens (graph join — the predecessor joins graph
        // obligations on the artifact token in spec-lint's own message).
        val f9Lines: Map[String, Set[Int]] = bySpec.map { (s, r) =>
          s -> r.findings.collect { case CheckOutcome.Fail(CheckId.F9, Some(l), _) => l }.toSet
        }
        val f9Artifacts: Map[String, Set[String]] = bySpec.map { (s, r) =>
          s -> r.findings
            .collect { case CheckOutcome.Fail(CheckId.F9, _, m) => m }
            .flatMap(f9ArtifactToken)
            .toSet
        }

        // Per requirement, in the predecessor's order: unbound (spec-lint
        // found no binding row) → bound-but-unattributable (degraded only;
        // graph reports the same shape as unresolved) → unresolved (a
        // mapped obligation carries an F9 finding) → the ledger read.
        val verdicts: List[(Requirement, Option[UnresolvedReason])] =
          reqs.requirements.map { (req: Requirement) =>
            val report: LintReport = bySpec(req.spec)
            val bound: Boolean =
              report.requirementRows.getOrElse(req.requirement, Nil).nonEmpty
            if !bound then req -> Some(UnresolvedReason.Unbound)
            else
              val mapped: List[ExtractedObligation] =
                reqs.obligations.filter(o => o.spec == req.spec && o.requirementClaims.contains(req.requirement))
              if mapped.isEmpty then
                req -> Some(
                  reqs.source match
                    case FactSource.Degraded => UnresolvedReason.Unattributable
                    case FactSource.Graph    => UnresolvedReason.Unresolved
                )
              else
                val carriesFinding: Boolean = reqs.source match
                  case FactSource.Degraded =>
                    mapped.exists(o => f9Lines.getOrElse(req.spec, Set.empty).contains(o.line))
                  case FactSource.Graph =>
                    mapped.exists(o => o.artifacts.exists(f9Artifacts.getOrElse(req.spec, Set.empty)))
                if carriesFinding then req -> Some(UnresolvedReason.Unresolved)
                else
                  val specBaseline: String =
                    specBaselines.getOrElse(req.spec, resolvedBaseline)
                  def qualifies(r: LedgerRecord): Boolean =
                    r.change == change && r.spec == req.spec &&
                      (r.baseline == specBaseline || r.baseline == resolvedBaseline ||
                        artifactUnchanged(r.baseline, r.artifact))
                  val obls: List[(Boolean, Boolean)] = mapped.map { o =>
                    val rows: List[LedgerRecord] =
                      ledger.records.filter(r => qualifies(r) && r.obligation == o.obligation)
                    (rows.nonEmpty, rows.exists(_.exit == 0))
                  }
                  // The worst case across the mapped obligations: all green
                  // → ok; all have rows but one is red → failed (negative
                  // evidence); any obligation with no rows at all →
                  // undischarged (absence of evidence). Never collapsed.
                  val allHaveRows: Boolean = obls.forall(_._1)
                  val allGreen: Boolean    = obls.forall(_._2)
                  if allGreen then req -> None
                  else if allHaveRows then req -> Some(UnresolvedReason.Failed)
                  else req                     -> Some(UnresolvedReason.Undischarged)
          }

        // Obligations that map to no requirement but carry a finding are
        // reported separately — never dropped, never misattributed.
        val unmapped: List[UnmappedObligation] = reqs.source match
          case FactSource.Degraded =>
            reqs.obligations.collect {
              case o
                  if o.unmappable &&
                    f9Lines.getOrElse(o.spec, Set.empty).contains(o.line) =>
                // The artifact token comes from spec-lint's own F9 message
                // at that line, exactly as the predecessor recovers it; the
                // fallback names the failure rather than leaking the raw log.
                val artifact: String = bySpec
                  .get(o.spec)
                  .flatMap(r =>
                    r.findings.collectFirst {
                      case CheckOutcome.Fail(CheckId.F9, Some(l), m) if l == o.line => m
                    }
                  )
                  .flatMap(f9ArtifactToken)
                  .getOrElse(s"<unrecoverable artifact token, spec-lint line: ${o.line}>")
                UnmappedObligation(o.spec, o.line, artifact)
            }
          case FactSource.Graph =>
            reqs.obligations.collect {
              case o if o.unmappable && o.artifact.nonEmpty =>
                UnmappedObligation(o.spec, o.line, o.artifact)
            }

        val unresolved: List[UnresolvedEntry] = verdicts.flatMap {
          case (req, Some(reason)) =>
            UnresolvedEntry.of(req.spec, req.requirement, List(reason)).toList
          case (_, None) => Nil
        }
        val total: Int  = verdicts.length
        val boundN: Int = total - verdicts.count(_._2.contains(UnresolvedReason.Unbound))
        val unresolvedN: Int = verdicts.count(_._2.contains(UnresolvedReason.Unresolved)) +
          verdicts.count(_._2.contains(UnresolvedReason.Unattributable))
        val undischargedN: Int = verdicts.count(_._2.contains(UnresolvedReason.Undischarged)) +
          verdicts.count(_._2.contains(UnresolvedReason.Failed))
        val resolvedN: Int   = boundN - unresolvedN
        val dischargedN: Int = resolvedN - undischargedN
        ChainStateReport.fromCounts(
          change,
          baseline,
          total,
          boundN,
          resolvedN,
          dischargedN,
          unresolved,
          unmapped
        ) match
          case Right(report) => Right(report)
          case Left(clause) =>
            Left(
              ChainStateUndetermined(
                change,
                baseline,
                s"internal error — the assembled report does not satisfy its own contract ($clause)"
              )
            )

  /** The artifact token in an F9 message — `artifact 'X' does not resolve`. */
  private val f9ArtifactRe: scala.util.matching.Regex =
    "artifact '([^']+)'".r

  private def f9ArtifactToken(message: String): Option[String] =
    f9ArtifactRe.findFirstMatchIn(message).map(_.group(1))
