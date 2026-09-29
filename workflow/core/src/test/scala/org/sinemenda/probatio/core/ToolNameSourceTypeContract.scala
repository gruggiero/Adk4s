package org.sinemenda.probatio.core

/**
 * Typed contract for oracle-fixture-repair (spec 6 of
 * `finish-probatio-replacement`, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3 implementation
 * must satisfy — compiled under the real probatio-core classpath,
 * `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `ToolNameSource` is a closed enum of two variants — `Supplied(name)`
 *    and `Absent` — the sealing is the constraint: the pre-execution
 *    tier's tool-name read can no longer smuggle absence through as the
 *    empty string (the `""` member of `readOnlyTools` is what made an
 *    absent name indistinguishable from a read-only tool).
 *  - `GateDecisions.preExecution(toolName: ToolNameSource): Boolean` is
 *    the decision signature the compile-negative obligation names —
 *    `preExecution(toolName = "")` cannot compile. The body is `???`
 *    until Step 3; the contract pins the shape, not the verdict.
 *  - `GateDecisions.isReadOnlyTool(String)` is UNCHANGED: the read-only
 *    name set stays keyed on the harness's literal names, and the
 *    `Absent`/`Supplied` split lives in `preExecution`, not in the set.
 *    `runToolCall`'s `toolName` String becomes a `ToolNameSource` at
 *    Step 3 — `Absent` when neither `--tool` nor a payload `tool_name`
 *    supplies one, `Supplied(name)` otherwise (an empty payload field is
 *    absence, not a supplied empty name).
 *  - `suppliedName`/`isAbsent` are the only projections: the caller
 *    renders the absence diagnostic from `Absent` itself; no name is
 *    fabricated for a name that never arrived.
 *
 * spec: oracle-fixture-repair — Step 1: typed contract (minimal — the source enum and the decision signature)
 * spec: oracle-fixture-repair — Concepts Introduced (new): ToolNameSource
 */
final class ToolNameSourceTypeContract extends ProbatioSuite:

  // ── ToolNameSource — the two variants ────────────────────────────────
  // Supplied: String => ToolNameSource
  val suppliedSig: String => ToolNameSource =
    ToolNameSource.Supplied.apply

  // Absent: ToolNameSource
  val absentSig: ToolNameSource =
    ToolNameSource.Absent

  // ── Projections ──────────────────────────────────────────────────────
  val isAbsentSig: ToolNameSource => Boolean =
    (t: ToolNameSource) => t.isAbsent

  val suppliedNameSig: ToolNameSource => Option[String] =
    (t: ToolNameSource) => t.suppliedName

  // ── The pre-execution decision takes a ToolNameSource ────────────────
  // preExecution: ToolNameSource => Boolean — the spec's compile-negative
  // names this signature: `preExecution(toolName = "")` must not compile
  // (pinned in GateEventCompletenessTypeContract per the proof-obligations
  // table).
  val preExecutionSig: ToolNameSource => Boolean =
    GateDecisions.preExecution

  // ── The pinned surface evaluates (no preExecution — the body is ???) ─
  test("the two variants are distinguishable by their projections"):
    val supplied: ToolNameSource = ToolNameSource.Supplied("edit")
    val absent: ToolNameSource   = ToolNameSource.Absent
    assertEquals(supplied.suppliedName, Some("edit"))
    assertEquals(absent.suppliedName, None)
    assert(!supplied.isAbsent && absent.isAbsent)

  test("a supplied name is carried verbatim, in either case"):
    assertEquals(ToolNameSource.Supplied("Edit").suppliedName, Some("Edit"))
    assertEquals(ToolNameSource.Supplied("read").suppliedName, Some("read"))

end ToolNameSourceTypeContract
