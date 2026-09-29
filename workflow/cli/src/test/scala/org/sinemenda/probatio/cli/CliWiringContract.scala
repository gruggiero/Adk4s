package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.BannerOutput
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.GatePayload
import org.sinemenda.probatio.core.LintReport
import org.sinemenda.probatio.core.Outcome

/**
 * Typed contract for the cli-wiring spec — test-source type definitions that
 * verify the signatures of `CliContext`, `StdoutRenderer[A]`, and
 * `SubcommandWiring` compile and are usable.
 *
 * This file defines the type signatures in test sources FIRST (before
 * implementation). The test oracle runs RED against these signatures. Step 3
 * (implementation) moves the types to main sources and populates the stubs.
 *
 * spec: cli-wiring — Concepts Introduced: CliContext
 * spec: cli-wiring — Concepts Introduced: StdoutRenderer
 * spec: cli-wiring — Concepts Introduced: SubcommandWiring
 * spec: cli-wiring — Implementation Anchors: CliContext, StdoutRenderer, SubcommandWiring
 */
object CliWiringContract:

  // ── CliContext — resolved paths + env-var overrides ────────────────────
  // spec: cli-wiring — Concepts Introduced: CliContext

  /** Carries resolved paths and env-var overrides read once at entrypoint start. */
  final case class CliContext(
    repoRoot: String,
    changeDir: String,
    ledgerFile: String,
    gitDir: String,
    escapeHatch: Boolean
  )

  object CliContext:
    /** Resolve a CliContext from explicit paths + escape-hatch boolean. */
    def resolve(
      repoRoot: String,
      changeDir: String,
      ledgerFile: String,
      gitDir: String,
      escapeHatch: Boolean
    ): CliContext =
      CliContext(repoRoot, changeDir, ledgerFile, gitDir, escapeHatch)

  // ── StdoutRenderer[A] — typeclass for byte-compatible stdout rendering ──
  // spec: cli-wiring — Concepts Introduced: StdoutRenderer

  /** Renders a core result type to the exact stdout string the bash original emits. */
  trait StdoutRenderer[A]:
    /** Render the value to the stdout string. */
    def render(value: A): String

  object StdoutRenderer:
    /** Summon the renderer for a given type. */
    def apply[A](using renderer: StdoutRenderer[A]): StdoutRenderer[A] = renderer

    // Given instances — one per subcommand output format.
    // These are stubs that will be populated during implementation (Step 3).

    given StdoutRenderer[ChainStateReport] with
      def render(report: ChainStateReport): String =
        // Stub — populated in Step 3 (implementation)
        val _: ChainStateReport = report
        ""

    given StdoutRenderer[LintReport] with
      def render(report: LintReport): String =
        // Stub — populated in Step 3 (implementation)
        val _: LintReport = report
        ""

    given StdoutRenderer[GatePayload] with
      def render(payload: GatePayload): String =
        // Stub — populated in Step 3 (implementation)
        val _: GatePayload = payload
        ""

    given StdoutRenderer[BannerOutput] with
      def render(banner: BannerOutput): String =
        // Stub — populated in Step 3 (implementation)
        val _: BannerOutput = banner
        ""

  // ── SubcommandWiring — I/O adapter layer signatures ────────────────────
  // spec: cli-wiring — Concepts Introduced: SubcommandWiring

  /** The I/O adapter layer: reads files, parses args, calls core, renders, maps to Outcome[Int]. */
  object SubcommandWiring:

    /** Parse args into a flag map. Returns Left(error) on missing values or unknown flags. */
    def parseArgs(
      args: Array[String],
      knownFlags: Set[String]
    ): Either[CliError, Map[String, String]] =
      // Stub — populated in Step 3 (implementation)
      val _: Array[String] = args
      val _: Set[String]   = knownFlags
      Right(Map.empty)

    /** Read a ledger file as a list of JSON values. Returns Undetermined on I/O failure. */
    def readLedgerFile(
      path: String
    ): Outcome[List[ujson.Value]] =
      // Stub — populated in Step 3 (implementation)
      val _: String = path
      Outcome.Ran(List.empty)

    /** Append a record line to the ledger file. Returns Undetermined on I/O failure. */
    def appendLedgerLine(
      path: String,
      line: String
    ): Outcome[Unit] =
      // Stub — populated in Step 3 (implementation)
      val _: String = path
      val _: String = line
      Outcome.Ran(())

    /** Write a string to stdout. */
    def emitStdout(s: String): Unit =
      System.out.print(s)

    /** Write a string to stderr. */
    def emitStderr(s: String): Unit =
      System.err.print(s)

  end SubcommandWiring

end CliWiringContract
