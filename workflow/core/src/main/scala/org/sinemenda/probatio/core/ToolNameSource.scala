package org.sinemenda.probatio.core

/**
 * Whether the pre-execution tier received a tool name (spec 6,
 * oracle-fixture-repair).
 *
 * Today the tier's tool-name read yields a plain `String`, and an absent
 * name arrives as `""` — indistinguishable from a read-only tool because
 * `""` is a member of `GateDecisions.readOnlyTools`. That is how the
 * lock fixtures went silently green-against-themselves: an edit without
 * a tool name took the read-only path and allowed, and the failure mode
 * ("a harness delivered a payload shape the gate cannot read") was
 * invisible.
 *
 * The sealed enumeration is the constraint: `Supplied` carries the name,
 * `Absent` carries nothing and is distinguishable at the decision site.
 * `Absent` keeps the predecessor's allow verdict — it changes the
 * diagnostic, never the decision.
 *
 * spec: oracle-fixture-repair — Concepts Introduced (new): ToolNameSource
 * spec: oracle-fixture-repair — Requirement: An absent tool name keeps parity and is stated
 * spec: oracle-fixture-repair — Compile-Negative: A tool name represented as a possibly-empty string at the decision site
 */
enum ToolNameSource:

  /**
   * The harness supplied a tool name — the `--tool` flag's value, or the
   * payload's `tool_name` field when the `--file`-absent branch reads it.
   * A supplied name is non-empty: an empty payload field is absence.
   */
  case Supplied(name: String)

  /**
   * No tool name arrived — no `--tool` flag, and no payload `tool_name`
   * the tier could read. The pre-execution tier allows, exactly as the
   * predecessor's `""` did, and its diagnostic states the absence.
   */
  case Absent

  /** True iff no tool name was supplied. */
  def isAbsent: Boolean = this match
    case ToolNameSource.Absent        => true
    case ToolNameSource.Supplied(_)   => false

  /** The supplied name when one arrived; `None` on `Absent`. */
  def suppliedName: Option[String] = this match
    case ToolNameSource.Supplied(name) => Some(name)
    case ToolNameSource.Absent         => None

end ToolNameSource
