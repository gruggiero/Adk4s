package org.sinemenda.probatio.migration

import upickle.default.*

/** Types for the exactly-one-implementation invariant (R-M4) and the
  * atomic skill-doc update (R-M5).
  *
  * During migration, precisely one implementation per tool is active at
  * any commit — no dual bash+scala installations. The installation step
  * asserts this via the shim's resolved target.
  *
  * This file lives in probatio-cli test sources because the install
  * assertion and skill-doc lint are CLI-side concerns. The shared
  * `ToolId` enum is duplicated here (not imported from probatio-core test
  * sources, which are not visible to probatio-cli tests) — the two
  * definitions are kept in sync by the conformance property test.
  *
  * spec: migration-protocol — Requirement: Exactly one implementation per tool during migration
  * spec: migration-protocol — Requirement: Skill documents updated atomically with tool swap
  * spec: migration-protocol — Property: exactly-one-implementation-invariant
  */
object MigrationTypes:

  /** The tools that have override seams in the predecessor implementation. */
  enum ToolId:
    case SpecLint
    case ChainState
    case DangerScan
    case Reconcile
    case Gate

  object ToolId:
    given ReadWriter[ToolId] = readwriter[ujson.Value].bimap(
      (t: ToolId) => ujson.Str(t.toString),
      (v: ujson.Value) => v match
        case ujson.Str("SpecLint")   => ToolId.SpecLint
        case ujson.Str("ChainState") => ToolId.ChainState
        case ujson.Str("DangerScan") => ToolId.DangerScan
        case ujson.Str("Reconcile")  => ToolId.Reconcile
        case ujson.Str("Gate")       => ToolId.Gate
        case other                   => sys.error(s"invalid ToolId: $other")
    )

    /** The R-M3 swap order: purest, best-covered tools first; gate last. */
    val swapOrder: List[ToolId] = List(
      ToolId.ChainState,
      ToolId.SpecLint,
      ToolId.DangerScan,
      ToolId.Reconcile,
      ToolId.Gate
    )

  /** The state of migration: which tools have been ported. */
  final case class MigrationState(portedTools: Set[ToolId]):
    /** The tools still on the predecessor implementation. */
    def predecessorTools: Set[ToolId] = ToolId.swapOrder.toSet -- portedTools

    /** True if migration is complete (all tools ported). */
    def isComplete: Boolean = portedTools.size == ToolId.swapOrder.length

  object MigrationState:
    given ReadWriter[MigrationState] = readwriter[ujson.Value].bimap(
      (s: MigrationState) => ujson.Obj(
        "portedTools" -> ujson.Arr(s.portedTools.toList.map(t => ujson.Str(t.toString))*)
      ),
      (v: ujson.Value) => v match
        case obj: ujson.Obj =>
          val m: Map[String, ujson.Value] = obj.value.toMap
          m("portedTools") match
            case arr: ujson.Arr =>
              val tools: Set[ToolId] = arr.value.toList.flatMap {
                case ujson.Str("SpecLint")   => Some(ToolId.SpecLint)
                case ujson.Str("ChainState") => Some(ToolId.ChainState)
                case ujson.Str("DangerScan") => Some(ToolId.DangerScan)
                case ujson.Str("Reconcile")  => Some(ToolId.Reconcile)
                case ujson.Str("Gate")       => Some(ToolId.Gate)
                case _                       => None
              }.toSet
              MigrationState(tools)
            case other => sys.error(s"invalid portedTools: $other")
        case other => sys.error(s"invalid MigrationState: $other")
    )

  /** A resolved shim target for one tool.
    *
    * `resolvedTarget = None` means the shim resolves to a non-existent
    * path (missing installation — R-M4 compile-negative).
    * `candidateTargets` is the list of reachable implementations:
    *   - size 0 = missing (broken install)
    *   - size 1 = exactly one (correct)
    *   - size 2+ = dual installation (ambiguous — R-M4 compile-negative)
    */
  final case class ShimTarget(
    tool: ToolId,
    resolvedTarget: Option[String],
    candidateTargets: List[String]
  ):
    /** True iff exactly one implementation is reachable. */
    def isExactlyOne: Boolean = resolvedTarget.isDefined && candidateTargets.length == 1

    /** True iff the installation is missing (zero implementations). */
    def isMissing: Boolean = resolvedTarget.isEmpty || candidateTargets.isEmpty

    /** True iff there is a dual installation (two or more implementations). */
    def isDual: Boolean = candidateTargets.length >= 2

  /** The result of resolving all shim targets for a migration state. */
  final case class ShimResolution(targets: List[ShimTarget]):
    /** True iff every tool has exactly one implementation. */
    def allExactlyOne: Boolean = targets.forall(_.isExactlyOne)

    /** The tools with missing installations. */
    def missing: List[ShimTarget] = targets.filter(_.isMissing)

    /** The tools with dual installations. */
    def dual: List[ShimTarget] = targets.filter(_.isDual)

  /** A reference from a skill document to a tool path. */
  final case class SkillDocReference(
    skillDocPath: String,
    referencedPath: String,
    line: Int
  ):
    /** True iff the referenced path is a predecessor script path. */
    def isPredecessorReference: Boolean =
      referencedPath.contains("scanner/") && referencedPath.endsWith(".sh")

    /** True iff the referenced path is a ported tool invocation. */
    def isPortedReference: Boolean =
      referencedPath.startsWith("probatio ") || referencedPath.contains("probatio")

  /** The result of linting skill documents for broken/forward references. */
  final case class SkillDocLintResult(
    brokenReferences: List[SkillDocReference],
    forwardReferences: List[SkillDocReference]
  ):
    /** True iff no broken or forward references exist. */
    def isClean: Boolean = brokenReferences.isEmpty && forwardReferences.isEmpty

  /** The swap order for hook shims (R-M3). */
  val hookShimSwapOrder: List[ToolId] = ToolId.swapOrder
