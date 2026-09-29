package org.sinemenda.probatio.plugin

/**
 * Pure function that generates hook shims idempotently (R-S5),
 * bound to the resolution result (native-gate-delivery).
 *
 * The emitted shape depends on the declared `ShimTargetScope`:
 *   - `AbsoluteInstall`: shebang + `exec "<resolved-path>" <sub> "$@"`
 *   - `RepositoryRelative`: shebang + a `SCRIPT_DIR` self-location line +
 *     `exec "$SCRIPT_DIR/<rel>" <sub> "$@"`
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
   * Generates the shim content for the artifact a resolution returned,
   * using the `gate` subcommand.
   *
   * @param resolution the binary-resolution result the shim binds to
   * @param scope the resolution scope the generated script states —
   *   required, never inferred from the target's shape
   * @return Right(shim content) for a resolved target, Left(reason) when
   *   the resolution is blocked
   */
  def generateShim(resolution: ResolutionResult, scope: ShimTargetScope): Either[String, String] =
    generateShim(resolution, scope, "gate")

  /**
   * Generates the shim content for the artifact a resolution returned,
   * the stated scope and subcommand.
   *
   * For `AbsoluteInstall(path)` the output is the 3-line exec shim:
   *   `#!/usr/bin/env bash\nexec "<path>" <subcommand> "$@"\n`
   * For `RepositoryRelative(fromShimToBinary)` the script resolves its
   * own directory at runtime and execs the target relative to it.
   *
   * This is a pure function — same inputs always produce same output.
   * The trailing newline ensures the file ends cleanly.
   *
   * @param resolution the binary-resolution result the shim binds to
   * @param scope the resolution scope the generated script states
   * @param subcommand the probatio subcommand to invoke (e.g. "gate",
   *   "spec-lint", "chain-state", "danger-scan", "reconcile")
   * @return Right(shim content) for a resolved target, Left(reason) when
   *   the resolution is blocked — no shim is written and the reason is
   *   reported
   */
  def generateShim(
    resolution: ResolutionResult,
    scope: ShimTargetScope,
    subcommand: String
  ): Either[String, String] =
    resolution.path match {
      case None =>
        val reason: String =
          resolution.logLines.filter(_.nonEmpty).mkString(" ") match {
            case ""      => "resolution produced no target and no reason"
            case blocked => blocked
          }
        Left(reason)
      case Some(resolvedPath) =>
        scope match {
          case ShimTargetScope.AbsoluteInstall(path) =>
            if (!path.startsWith("/"))
              Left(s"an install-scope shim target must be an absolute path: $path")
            else if (path != resolvedPath)
              Left(
                s"the install scope names a different artifact than the resolution: scope=$path resolved=$resolvedPath"
              )
            else if (!isShimQuotable(path))
              Left(s"shim target path cannot be safely quoted for the exec line: $path")
            else
              Right(s"""#!/usr/bin/env bash
exec "$path" $subcommand "$$@"
""")
          case ShimTargetScope.RepositoryRelative(rel) =>
            if (!isShimQuotable(rel.value))
              Left(s"shim target path cannot be safely quoted for the exec line: ${rel.value}")
            else
              Right(s"""#!/usr/bin/env bash
SCRIPT_DIR="$$(cd "$$(dirname "$${BASH_SOURCE[0]}")" && pwd)"
exec "$$SCRIPT_DIR/${rel.value}" $subcommand "$$@"
""")
        }
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
