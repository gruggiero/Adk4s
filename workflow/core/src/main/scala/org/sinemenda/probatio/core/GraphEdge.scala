package org.sinemenda.probatio.core

/**
 * A relation of the traceability graph — the nine edge kinds the
 * predecessor maintains as conventions on which table produced them:
 *
 *   - `Declares`       — concept → action, concept → sync
 *   - `DefinesSync`    — concept → sync (the same sync node; the two
 *                        rels are distinct because the registry states
 *                        them in different sections)
 *   - `ImplementedBy`  — concept → code path (implementation map)
 *   - `Cites`          — spec → concept or action (Concepts Used)
 *   - `Uses`           — spec → type (Concepts Used (from inventory))
 *   - `Introduces`     — spec → type (Concepts Introduced)
 *   - `HasRequirement` — spec → requirement
 *   - `EnforcedBy`     — requirement → obligation (the obligation is the
 *                        requirement's enforcement claim; `link` records
 *                        how the row was linked)
 *   - `VerifiedBy`     — obligation → artifact (the artifact is the
 *                        obligation's evidence)
 *
 * A closed enum: an edge kind written as a free string can name a rel
 * the export consumers do not understand, which would be silently
 * emitted and silently dropped on read-back.
 *
 * spec: graph-tool-port — Concepts Introduced (new): GraphEdge
 */
enum GraphEdge:
  case Declares, DefinesSync, ImplementedBy, Cites, Uses, Introduces,
    HasRequirement, EnforcedBy, VerifiedBy

object GraphEdge:

  /** The wire `rel` field — the predecessor's lowercase rel word. */
  def wireName(e: GraphEdge): String = e match
    case Declares        => "declares"
    case DefinesSync     => "defines-sync"
    case ImplementedBy   => "implemented-by"
    case Cites           => "cites"
    case Uses            => "uses"
    case Introduces      => "introduces"
    case HasRequirement  => "has-req"
    case EnforcedBy      => "enforced-by"
    case VerifiedBy      => "verified-by"

/**
 * How a proof-obligation row was linked to its requirement — the
 * predecessor's three link words, recorded on `enforced-by`
 * (requirement → obligation) edges for obligations rendering and the
 * audit's manual-review warnings.
 *
 *   - `Explicit` — the row's Source cell named "Requirement N" or "RN"
 *   - `Title`    — the row's Source cell is the requirement's title
 *                  prefix (first 40 characters)
 *   - `Inferred` — token overlap (≥ 2 shared words) between the Source
 *                  cell and the requirement title
 *
 * spec: graph-tool-port — Requirement: Every obligation row is recorded, linked, or reported unlinkable
 */
enum ObligationLink:
  case Explicit, Title, Inferred

object ObligationLink:

  /** The wire `link` field — the predecessor's lowercase link word. */
  def wireName(l: ObligationLink): String = l match
    case Explicit => "explicit"
    case Title    => "title"
    case Inferred => "inferred"

/**
 * One directed edge: `from` -[`rel`]-> `to`, with the predecessor's
 * optional attributes — `planned` is set only by `Cites` edges whose
 * Concepts Used row marked the concept `(new|created by)`; `link` is set
 * only by `EnforcedBy` (requirement → obligation) edges to record the
 * obligation-link basis (explicit ordinal / title prefix / inferred). An
 * optional field rather than a stringly-typed attr map keeps the
 * predecessor's two attributes typed and nothing else representable.
 *
 * `Edge` is a record, not a kind — the relation kind is `GraphEdge`,
 * which is why a free-string relation cannot be expressed.
 *
 * spec: graph-tool-port — Concepts Introduced (new): GraphEdge
 */
final case class Edge(
  from: String,
  rel: GraphEdge,
  to: String,
  planned: Option[Boolean],
  link: Option[ObligationLink]
)

object Edge:

  /** An edge carrying no optional attributes. */
  def plain(from: String, rel: GraphEdge, to: String): Edge =
    Edge(from, rel, to, None, None)
