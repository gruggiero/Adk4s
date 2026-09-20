package org.sinemenda.probatio.plugin

/**
 * Pure function that generates 3-line hook shims idempotently (R-S5),
 * bound to the resolution result (native-gate-delivery).
 *
 * Each shim is exactly three lines:
 *   1. `#!/usr/bin/env bash`  (shebang)
 *   2. `exec "<resolved-path>" <subcommand> "$@"`  (exec line)
 *   3. trailing newline
 *
 * The shim's target is the artifact the resolution RETURNED — never a
 * literal path. A resolution that is blocked (`ResolutionResult.path` is
 * `None`) yields `Left(reason)`: no shim is written and the reason is
 * reported. Running `generateShim` twice with the same resolution produces
 * byte-identical output.
 *
 * This is a pure function with no side effects — it does not write to
 * disk. The `probatioGateShim` sbt task calls this function and writes the
 * `Right` result to the configured hook path; a `Left` is reported without
 * a write.
 *
 * spec: sbt-plugin — Requirement: probatioGateShim regenerates hook shims idempotently
 * spec: sbt-plugin — Property: shim-idempotency
 * spec: native-gate-delivery — Requirement: The resolution result determines the shim's target
 * spec: native-gate-delivery — Property: shim-target-equals-resolution-and-is-repeatable
 */
object ShimGenerator {

  /**
   * Generates the 3-line shim content for the artifact a resolution
   * returned, using the `gate` subcommand.
   *
   * @param resolution the binary-resolution result the shim binds to
   * @return Right(shim content) for a resolved target, Left(reason) when
   *   the resolution is blocked
   */
  def generateShim(resolution: ResolutionResult): Either[String, String] =
    generateShim(resolution, "gate")

  /**
   * Generates the 3-line shim content for the artifact a resolution
   * returned and subcommand.
   *
   * The output for a resolved target is always:
   *   `#!/usr/bin/env bash\nexec "<path>" <subcommand> "$@"\n`
   *
   * This is a pure function — same inputs always produce same output.
   * The trailing newline ensures the file ends cleanly.
   *
   * @param resolution the binary-resolution result the shim binds to
   * @param subcommand the probatio subcommand to invoke (e.g. "gate",
   *   "spec-lint", "chain-state", "danger-scan", "reconcile")
   * @return Right(shim content) for a resolved target, Left(reason) when
   *   the resolution is blocked — no shim is written and the reason is
   *   reported
   */
  def generateShim(resolution: ResolutionResult, subcommand: String): Either[String, String] =
    resolution.path match {
      case Some(path) if !isShimQuotable(path) =>
        Left(s"shim target path cannot be safely quoted for the exec line: $path")
      case Some(path) =>
        Right(s"""#!/usr/bin/env bash
exec "$path" $subcommand "$$@"
""")
      case None =>
        val reason: String =
          resolution.logLines.filter(_.nonEmpty).mkString(" ") match {
            case ""      => "resolution produced no target and no reason"
            case blocked => blocked
          }
        Left(reason)
    }

  /**
   * Whether `path` can sit inside the exec line's double quotes without
   * changing what bash executes. `"` and `\` alter quoting; `$`, `` ` ``,
   * and newlines invite expansion or injection. A path that cannot be
   * quoted is reported rather than emitted as a corrupt shim.
   */
  private def isShimQuotable(path: String): Boolean =
    path.forall(c => c != '"' && c != '\\' && c != '$' && c != '`' && c != '\n' && c != '\r')
}
