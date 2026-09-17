package org.sinemenda.probatio.core

/**
 * Which extraction path produced a `RequirementSet` — the transitive graph
 * extractor (`openspec-graph.py`, invoked as a subprocess by the CLI layer)
 * or the in-process fallback over parsed `SpecDocument`s. Reported, never
 * inferred: a `Degraded` result is stated as degraded, never presented as
 * though the extractor ran.
 *
 * spec: chain-state-attribution — Concepts Introduced (new): FactSource
 * spec: chain-state-attribution — Requirement: The extraction path used for the requirement set is reported
 */
enum FactSource:
  case Graph, Degraded

object FactSource:

  /** The name used on the diagnostic channel when reporting the path. */
  def asString(s: FactSource): String = s match
    case Graph    => "graph"
    case Degraded => "degraded"

/**
 * An obligation row normalised across both extraction paths.
 *
 * `requirementClaims` are the requirement titles the row's source claims —
 * UNVALIDATED: a claim naming no real requirement of its spec is still
 * recorded here, and the attribution step in `ChainState.compute` decides
 * per-path whether such a row maps, is unmapped, or is dropped (the two
 * predecessor paths genuinely differ: degraded mode reports a
 * finding-carrying row with no exact-title `Requirement:` segment as
 * unmapped; graph mode reports only a row whose `sources` array is empty).
 *
 * `unmappable` is that per-path judgment evaluated at extraction time,
 * where the path's own semantics live: for `Degraded` it is "no
 * `Requirement: <title>` segment names a requirement of this spec"; for
 * `Graph` it is "the export's `sources` array is empty". A row that is
 * `unmappable` AND carries a spec-lint finding is reported in
 * `unmapped_obligations` — never dropped, never misattributed.
 *
 * `artifact`/`artifacts` are populated only by the graph path (the export
 * carries them per obligation). The degraded path recovers the offending
 * artifact token from spec-lint's own F9 message text, exactly as the
 * predecessor does — `artifact` is `""` and `artifacts` is `Nil` there.
 *
 * spec: chain-state-attribution — Requirement: An obligation that maps to no known requirement is reported separately, never dropped and never misattributed
 */
final case class ExtractedObligation(
  spec: String,
  line: Int,
  obligation: String,
  artifact: String,
  artifacts: List[String],
  requirementClaims: List[String],
  unmappable: Boolean
)

/**
 * The requirements of a change, extracted once from its spec documents:
 * `specNames` are the specs that were read (in discovery order),
 * `requirements` are the (spec, title) pairs in document order, and
 * `obligations` is the normalised obligation table. `source` records which
 * extraction path produced the set.
 *
 * Emptiness is a fact, not a placeholder: a `RequirementSet` whose
 * `requirements` are empty but whose `specNames` are non-empty is a change
 * whose spec documents genuinely declare no requirements — distinguishable
 * from an unreadable extraction, which never produces a `RequirementSet`
 * at all (the caller reports could-not-determine).
 *
 * spec: chain-state-attribution — Concepts Introduced (new): RequirementSet
 * spec: chain-state-attribution — Requirement: The correctness verdict is computed over the change's actual requirements
 */
final case class RequirementSet(
  specNames: List[String],
  requirements: List[ChainState.Requirement],
  obligations: List[ExtractedObligation],
  source: FactSource
):

  /** A genuinely empty requirement set — distinct from an unread one. */
  def isEmpty: Boolean = requirements.isEmpty

object RequirementSet:

  /** The empty set, tagged with the path that produced it. */
  def empty(source: FactSource): RequirementSet =
    RequirementSet(Nil, Nil, Nil, source)

/**
 * Builds a `RequirementSet` from parsed `SpecDocument`s — the single seam
 * through which requirement facts enter chain-state. Pure and total: no
 * file I/O, no subprocesses. The CLI layer discovers and parses the spec
 * documents and, when python3 and `openspec-graph.py` are runnable,
 * supplies the graph export as `graphExport`.
 *
 * spec: chain-state-attribution — Requirement: The correctness verdict is computed over the change's actual requirements
 * spec: chain-state-attribution — Requirement: The extraction path used for the requirement set is reported
 */
object RequirementExtractor:

  /**
   * A spec document paired with its spec name — the predecessor's
   * `spec_name`, the basename of the directory holding `spec.md`.
   */
  final case class NamedSpec(name: String, document: SpecDocument)

  /**
   * The canonical extraction entry point. When `graphExport` is `Some` and
   * structurally usable, the graph path produces the set
   * (`FactSource.Graph`); when it is `None` (extractor absent, unrunnable,
   * or its output did not parse) or the export is malformed, the in-process
   * fallback produces it (`FactSource.Degraded`). Which path ran is carried
   * on the result's `source` — the caller reports it, never infers it.
   */
  def extract(
    specs: List[NamedSpec],
    graphExport: Option[ujson.Value]
  ): RequirementSet =
    // Requirements always come from the parsed documents — the predecessor
    // enumerates `### Requirement:` titles from the spec files on BOTH
    // paths (the graph only normalises the obligation sources). Empty
    // titles are invisible to the predecessor (`[ -n "$title" ]`).
    val requirements: List[ChainState.Requirement] = specs.flatMap { (s: NamedSpec) =>
      s.document.requirements
        .filter((b: RequirementBlock) => b.title.nonEmpty)
        .map((b: RequirementBlock) => ChainState.Requirement(s.name, b.title))
    }
    val specNames: List[String] = specs.map(_.name)
    graphExport match
      case Some(exportValue) if usableExport(exportValue) =>
        RequirementSet(
          specNames,
          requirements,
          graphObligations(specs, exportValue),
          FactSource.Graph
        )
      case Some(_) | None =>
        RequirementSet(
          specNames,
          requirements,
          degradedObligations(specs),
          FactSource.Degraded
        )

  /**
   * The predecessor's `jq -e '.obligations'` gate: the export is usable iff
   * it is an object whose `.obligations` member exists and is neither null
   * nor false. An `[]` IS usable — an empty-but-real obligation table is
   * graph data, not a fallback trigger. Public: the CLI layer applies this
   * gate BEFORE it reports which extraction path ran, so a parseable but
   * unusable export is announced as degraded, never as graph.
   */
  def usableExport(exportValue: ujson.Value): Boolean =
    exportValue match
      case obj: ujson.Obj =>
        obj.obj.get("obligations") match
          case Some(ujson.Null)  => false
          case Some(ujson.False) => false
          case Some(_)           => true
          case None              => false
      case _ => false // danger-scan:allow non-object-export — only a ujson.Obj satisfies the `.obligations` gate

  /**
   * The degraded path: obligation rows come from the parsed documents'
   * `chainRows` — the chain-state script's OWN awk row set, which admits
   * rows spec-lint's source check never sees (empty or comment `Source`
   * cells, `Obligation`-prefixed cells, rows after a `### ` heading that
   * interrupted spec-lint's scan but not this script's) and skips rows
   * spec-lint admits (any `|` row before the first separator). A row
   * maps to a requirement iff one of its `+`-separated Source segments
   * is exactly `Requirement: <title>` for a real title of this spec —
   * the predecessor's `seg_trimmed == "Requirement: $title"` test.
   */
  private def degradedObligations(specs: List[NamedSpec]): List[ExtractedObligation] =
    specs.flatMap { (s: NamedSpec) =>
      val titles: Set[String] =
        s.document.requirements.map((b: RequirementBlock) => b.title).toSet
      s.document.chainRows.map { (r: ObligationRow) =>
        val claims: List[String] =
          r.source
            .split('+')
            .toList
            .map((seg: String) => seg.trim)
            .collect { case requirementClaim(t) => t }
        ExtractedObligation(
          spec = s.name,
          line = r.line,
          obligation = obligationCell(r.raw),
          artifact = "",
          artifacts = Nil,
          requirementClaims = claims,
          unmappable = !claims.exists(titles.contains)
        )
      }
    }

  /**
   * The graph path: the export's `.obligations[]` entries carry the spec
   * name, obligation text, artifacts, and the resolved `sources` array —
   * each source's `.requirement` is an exact-title claim the extractor
   * already resolved (ordinal, title, or inferred — resolved uniformly,
   * which is why `unattributable` does not exist on this path).
   * Obligations naming a spec that was not read contribute nothing, as in
   * the predecessor (`select(.spec == $sp)` over the discovered files).
   */
  private def graphObligations(
    specs: List[NamedSpec],
    exportValue: ujson.Value
  ): List[ExtractedObligation] =
    val byName: Map[String, NamedSpec] = specs.map(s => s.name -> s).toMap
    exportValue match
      case obj: ujson.Obj =>
        obj.obj.get("obligations") match
          case Some(ujson.Arr(items)) =>
            items.toList.flatMap { (item: ujson.Value) =>
              item match
                case entry: ujson.Obj =>
                  for
                    spec       <- strField(entry, "spec")
                    named      <- byName.get(spec)
                    obligation <- strField(entry, "obligation")
                  yield
                    val sources: List[ujson.Value] = arrField(entry, "sources")
                    val claims: List[String] = sources.flatMap {
                      case src: ujson.Obj => strField(src, "requirement")
                      case _ => // danger-scan:allow non-object-source — malformed source carries no requirement claim
                        None
                    }
                    val artifact: String = strField(entry, "artifact").getOrElse("")
                    // `.artifacts[]?` only — the predecessor reads no
                    // singular fallback, so an entry carrying `artifact`
                    // without `artifacts` contributes no artifacts to the
                    // resolved check.
                    val artifacts: List[String] =
                      arrField(entry, "artifacts").collect { case ujson.Str(a) => a }
                    ExtractedObligation(
                      spec = spec,
                      line = obligationLine(named.document, obligation),
                      obligation = obligation,
                      artifact = artifact,
                      artifacts = artifacts,
                      requirementClaims = claims,
                      unmappable = sources.isEmpty
                    )
                case _ => None // danger-scan:allow non-object-entry — a malformed obligation entry contributes no row
            }
          case _ => Nil // danger-scan:allow non-array-obligations — `.obligations` of the wrong shape yields no rows
      case _ => Nil // danger-scan:allow non-object-export — a malformed export yields no rows

  /** A `Requirement: <title>` Source segment — title captured verbatim. */
  private object requirementClaim:
    private val prefix: String = "Requirement: "
    def unapply(seg: String): Option[String] =
      if seg.startsWith(prefix) then Some(seg.substring(prefix.length))
      else None

  /** The Obligation column of a `|`-delimited table row (field 1, trimmed). */
  private def obligationCell(raw: String): String =
    raw.split("\\|", -1).lift(1).map(_.trim).getOrElse("")

  /**
   * The graph obligation's line — the predecessor recovers it with
   * `grep -nF "$uobl"` (first line containing the text, 1-based).
   */
  private def obligationLine(document: SpecDocument, obligation: String): Int =
    val idx: Int = document.lines.indexWhere((l: String) => l.contains(obligation))
    if idx >= 0 then idx + 1 else 1

  private def strField(obj: ujson.Obj, key: String): Option[String] =
    obj.obj.get(key).collect { case ujson.Str(s) => s }

  private def arrField(obj: ujson.Obj, key: String): List[ujson.Value] =
    obj.obj.get(key) match
      case Some(ujson.Arr(items)) => items.toList
      case _ => Nil // danger-scan:allow absent-or-non-array — missing/mistyped member means empty list, never coerced
