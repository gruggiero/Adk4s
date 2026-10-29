package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Compile-negative obligations for ledger-checkpoint-cutover (spec 8).
 *
 * A swap record constructed without its comparison evidence is
 * unconstructible: a swap whose justification is not attached cannot
 * be audited or reverted with reason. The field is required AND typed
 * — a bare string in the comparison position is a type error, and the
 * removed `oracleGreen` flag is no longer a parameter.
 *
 * This file lives in the migration test package (not
 * `CliWiringCompileNegativeSpec`) because `ShimSwap` is a probatio-core
 * test type — the cli module cannot see it (that is why `MigrationTypes`
 * is duplicated there). PO-table amendment recorded in
 * implementation-progress.md.
 *
 * spec: ledger-checkpoint-cutover — Compile-Negative: A swap record constructed without its comparison evidence
 */
final class LedgerCheckpointCutoverCompileNegative extends ProbatioSuite:

  test("a ShimSwap constructed without its comparison does not compile"):
    // The spec's forbidden construction: `ShimSwap(tool, pred, shim, bin)`
    // — no comparison argument position exists that accepts the record's
    // evidence implicitly; a String in the comparison position is a
    // type mismatch.
    val err: String = compileErrors(
      "ShimSwap(SeamTypes.ToolId.Ledger, \"scanner/ledger.sh.predecessor.bak\", \"scanner/ledger.sh\", \"bin/probatio\", \"2026-09-24T00:00:00Z\")"
    )
    assert(
      err.nonEmpty,
      "a swap record must require the comparison outcome — the timestamp cannot stand in for it"
    )

  test("a ShimSwap constructed via the removed oracleGreen flag does not compile"):
    // oracleGreen derives from the comparison — it is not a parameter.
    // A record whose green flag could contradict its own evidence is
    // unrepresentable.
    val err: String = compileErrors(
      "ShimSwap(SeamTypes.ToolId.Ledger, \"pred\", \"shim\", \"bin\", oracleGreen = true, \"ts\")"
    )
    assert(err.nonEmpty, "oracleGreen is derived from the comparison — not a constructor parameter")
