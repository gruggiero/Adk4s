package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ProbatioSuite

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

import SeamTypes.*

/**
 * Runner for the measured per-seam swap (spec 8).
 *
 * Invoked as `testOnly` with `PROBATIO_SEAM=<ToolId name>` in the
 * environment — the same convention as `probatioOracleDiff`. With no
 * seam named the test reports SKIPPED and does nothing; it never swaps
 * unconditionally in a plain `sbt test`.
 *
 * The runner calls `SeamSwapRunner.attempt` against the live schema
 * directory at HEAD and prints the outcome. A `Ran` means the swap was
 * measured, authorised, performed, and recorded; the emitted
 * `ShimSwap`'s `comparison` is the gate record it rested on.
 *
 * spec: ledger-checkpoint-cutover — Requirement: Each seam is swapped only after its oracle files reach control parity
 */
final class SeamSwapExec extends ProbatioSuite:

  // The differential runs the full bats suite in each of two arms.
  override val munitTimeout: FiniteDuration =
    FiniteDuration(10, TimeUnit.MINUTES)

  test("probatioSeamSwap: measured swap of the seam named by PROBATIO_SEAM"):
    val seamName: String   = sys.env.getOrElse("PROBATIO_SEAM", "") // scalafix:ok DisableSyntax.NoSysEnv
    val schemaDir: os.Path = os.pwd / "openspec" / "schemas" / "verified-scala3"
    if seamName.isEmpty || !os.exists(schemaDir / "tests") then
      println("[probatioSeamSwap] SKIPPED — set PROBATIO_SEAM=Ledger|Checkpoint to run a measured swap")
    else
      val seam: ToolId = ToolId.valueOf(seamName)
      println(s"[probatioSeamSwap] attempting measured swap of $seam at HEAD...")
      SeamSwapRunner.attempt(
        seam,
        schemaDir,
        "HEAD",
        os.temp.dir(prefix = "probatio-seam-swap-", deleteOnExit = true),
        Instant.now().toString
      ) match
        case Outcome.Ran(swap) =>
          println(s"[probatioSeamSwap] SWAPPED — ${swap.shimPath}")
          println(s"[probatioSeamSwap] predecessor preserved at ${swap.predecessorPath}")
          swap.comparison.evidence.files.foreach { (f: FileComparison) =>
            println(
              s"  ${f.fileName}: total=${f.total} pred=${f.predecessorFailures} ported=${f.portedFailures}"
            )
          }
        case Outcome.Finding(desc) =>
          println(s"[probatioSeamSwap] REFUSED — $desc")
          fail(desc)
        case Outcome.Undetermined(reason) =>
          println(s"[probatioSeamSwap] UNDETERMINED — $reason")
          fail(reason)
