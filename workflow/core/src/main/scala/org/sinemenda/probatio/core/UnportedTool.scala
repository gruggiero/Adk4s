package org.sinemenda.probatio.core

/**
 * What blocks a tool's port (spec 11 of `repair-probatio-cutover`).
 *
 * A closed set, so "no stated reason" is not a representable blocker: an
 * entry that cannot say why its tool is not ported is indistinguishable
 * from an oversight, and a free-text blocker cannot be checked.
 *
 * `SupersededByPortedTool` carries the subcommand's CLI NAME (e.g.
 * `"graph"`), not `cli.Subcommand`: that enum lives in probatio-cli, which
 * depends on this module — the dependency direction forbids the stronger
 * type here.
 *
 * spec: unported-tool-register — Concepts Introduced (new): PortBlocker
 * spec: unported-tool-register — Compile-Negative: A free-text blocker
 */
enum PortBlocker:

  /**
   * The port waits on a named investigation that has not been run (e.g.
   * the `native-packaging` scalameta spike).
   */
  case GatedOnSpike(spike: String)

  /**
   * The tool is not hook-invoked and not exercised by the acceptance
   * oracle — its port is unscheduled work, not a blocked seam.
   */
  case NotOnEnforcementPath

  /**
   * The tool's work moved to a ported subcommand; the field is that
   * subcommand's CLI name.
   */
  case SupersededByPortedTool(subcommand: String)

/**
 * One tool remaining on its predecessor implementation (spec 11 of
 * `repair-probatio-cutover`): its name, its location, what blocks the
 * port, and the workflow instructions that still cite it.
 *
 * The blocker is a required `PortBlocker` — an entry with no stated
 * reason is unconstructible, and the citations are what make the blast
 * radius of a future port visible.
 *
 * spec: unported-tool-register — Concepts Introduced (new): UnportedTool
 * spec: unported-tool-register — Compile-Negative: A register entry without a blocker
 */
final case class UnportedTool(
  name: String,
  path: String,
  blocker: PortBlocker,
  citedBy: List[String]
)

/**
 * The total classification of an executable tool in the workflow's tool
 * directories (spec 11 of `repair-probatio-cutover`).
 *
 * Exactly two variants: a tool is either named on the ported surface or
 * recorded in the register. The absence of a third variant IS the
 * mechanism — a tool in neither is unrepresentable as a classification,
 * so the check reports it as a finding rather than a state.
 *
 * `Ported` carries the subcommand's CLI name for the same layering
 * reason as `SupersededByPortedTool` (core cannot see `cli.Subcommand`).
 *
 * spec: unported-tool-register — Concepts Introduced (new): ToolSurfaceClassification
 * spec: unported-tool-register — Compile-Negative: A third tool classification
 */
enum ToolSurfaceClassification:

  /** The tool is named on the ported surface; the field is its subcommand CLI name. */
  case Ported(subcommand: String)

  /** The tool is recorded in the unported-tool register. */
  case Registered(tool: UnportedTool)
