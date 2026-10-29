package org.sinemenda.probatio.core

/**
 * The typed non-requirement source kinds an obligation row can name — the
 * predecessor's `(Property|Properties|Scenario|Scenarios|Invariant|
 * Compile-Negative|Temporal|Criterion|Type-Constraint|MUST-CONFIRM|Design|
 * Non-goal)` alternation as a closed set. Carried on an obligation node as
 * the `source_kind` attribute; the wire emits the matched word verbatim.
 *
 * spec: graph-tool-port — Requirement: The graph is built from the three declared sources
 */
enum ObligationSourceKind:
  case Property, Properties, Scenario, Scenarios, Invariant,
    CompileNegative, Temporal, Criterion, TypeConstraint, MustConfirm,
    Design, NonGoal

object ObligationSourceKind:

  /** The wire attribute value — the predecessor's matched word. */
  def wireName(k: ObligationSourceKind): String = k match
    case Property        => "Property"
    case Properties      => "Properties"
    case Scenario        => "Scenario"
    case Scenarios       => "Scenarios"
    case Invariant       => "Invariant"
    case CompileNegative => "Compile-Negative"
    case Temporal        => "Temporal"
    case Criterion       => "Criterion"
    case TypeConstraint  => "Type-Constraint"
    case MustConfirm     => "MUST-CONFIRM"
    case Design          => "Design"
    case NonGoal         => "Non-goal"

/**
 * A node of the traceability graph — the nine kinds the predecessor
 * maintains as markdown-table conventions: concepts declare actions and
 * syncs, the inventory catalogs types, specs cite concepts and carry
 * requirements, and proof obligations bind requirements to artifacts.
 *
 * The hierarchy is sealed: a node kind named by a free string can name a
 * kind that does not exist, which silently drops it from every query —
 * there is no `GraphNode.of("kind", id)` constructor.
 *
 * `id` is the predecessor's identifier convention (`concept:X`,
 * `action:C/a`, `sync:C/s`, `type:T`, `spec:change/cap`,
 * `req:change/cap#N`, `oblig:change/cap#N`, `artifact:path`,
 * `code:path`) — derived, never stored, so an id cannot disagree with the
 * node it identifies.
 *
 * spec: graph-tool-port — Concepts Introduced (new): GraphNode
 * spec: graph-tool-port — Compile-Negative: A node kind written as a free string
 */
sealed trait GraphNode:

  /** The node identifier — `<kind>:<key>` per the predecessor convention. */
  def id: String

object GraphNode:

  /** A behavioural-registry concept. `file` is set only when the registry declared it. */
  final case class Concept(name: String, file: Option[String]) extends GraphNode:
    def id: String = s"concept:$name"

  /** An action a concept declares — `action:<concept>/<action>`. */
  final case class Action(concept: String, name: String) extends GraphNode:
    def id: String = s"action:$concept/$name"

  /** A synchronization a concept defines — `sync:<concept>/<sync>`. */
  final case class Sync(concept: String, name: String) extends GraphNode:
    def id: String = s"sync:$concept/$name"

  /**
   * A type-inventory entry — `type:<name>`. `section` and `provenance`
   * are set only when the inventory row supplied them; a type node
   * created by a spec's uses/introduces table carries neither.
   */
  final case class TypeEntry(
    name: String,
    section: Option[String],
    provenance: Option[String]
  ) extends GraphNode:
    def id: String = s"type:$name"

  /** An active change's spec document — `spec:<change>/<capability>`. */
  final case class Spec(change: String, capability: String, file: String) extends GraphNode:
    def id: String = s"spec:$change/$capability"

  /**
   * A requirement of a spec — `req:<change>/<cap>#<ordinal>`. `spec` is
   * the `change/capability` pair; `ordinal` is the 1-based position in
   * the spec's requirement list (the obligations cite "Requirement N").
   */
  final case class Requirement(spec: String, ordinal: Int, title: String) extends GraphNode:
    def id: String = s"req:$spec#$ordinal"

  /**
   * A proof-obligation table row — `oblig:<change>/<cap>#<n>` where `n`
   * is the 1-based row position. `sourceKind` is set when the Source cell
   * named a typed non-requirement source rather than a requirement.
   */
  final case class Obligation(
    spec: String,
    ordinal: Int,
    title: String,
    enforcement: String,
    sourceKind: Option[ObligationSourceKind]
  ) extends GraphNode:
    def id: String = s"oblig:$spec#$ordinal"

  /** An enforcement artifact named by an obligation — `artifact:<path>`. */
  final case class Artifact(path: String) extends GraphNode:
    def id: String = s"artifact:$path"

  /** A code file a concept's implementation map binds — `code:<path>`. */
  final case class Code(path: String) extends GraphNode:
    def id: String = s"code:$path"

  /** The wire `kind` field — the predecessor's lowercase node-kind word. */
  def kindName(n: GraphNode): String = n match
    case Concept(_, _)           => "concept"
    case Action(_, _)            => "action"
    case Sync(_, _)              => "sync"
    case TypeEntry(_, _, _)      => "type"
    case Spec(_, _, _)           => "spec"
    case Requirement(_, _, _)    => "req"
    case Obligation(_, _, _, _, _) => "oblig"
    case Artifact(_)             => "artifact"
    case Code(_)                 => "code"

  /**
   * Merge a re-declared node into the recorded one — the predecessor's
   * `dict.update(non-empty attrs)` semantics: incoming populated fields
   * overwrite, empty ones preserve. Mismatched kinds under one id cannot
   * occur (the id prefix determines the kind), so `incoming` of a
   * different case returns `recorded` unchanged.
   */
  def merge(recorded: GraphNode, incoming: GraphNode): GraphNode =
    (recorded, incoming) match
      case (a: Concept, b: Concept)     => a.copy(file = b.file.filter(_.nonEmpty).orElse(a.file))
      case (a: TypeEntry, b: TypeEntry) =>
        a.copy(
          section = b.section.filter(_.nonEmpty).orElse(a.section),
          provenance = b.provenance.filter(_.nonEmpty).orElse(a.provenance)
        )
      case (a, _) => a
