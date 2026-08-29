package org.sinemenda.probatio.plugin

/**
 * Pure function that generates 3-line hook shims idempotently (R-S5).
 *
 * Each shim is exactly three lines:
 *   1. `#!/usr/bin/env bash`  (shebang)
 *   2. `exec "<resolved-path>" gate "$@"`  (exec line invoking the resolved binary)
 *   3. trailing newline
 *
 * Running `generateShim` twice with the same resolved binary path produces
 * byte-identical output. The shim tracks the resolved binary path — if the
 * path changes, the shim content changes.
 *
 * This is a pure function with no side effects — it does not write to disk.
 * The `probatioGateShim` sbt task calls this function and writes the result
 * to the configured hook path.
 *
 * spec: sbt-plugin — Requirement: probatioGateShim regenerates hook shims idempotently
 * spec: sbt-plugin — Property: shim-idempotency
 */
object ShimGenerator {

  /**
   * Generates the 3-line shim content for the given resolved binary path,
   * using the `gate` subcommand.
   *
   * The output is always:
   *   `#!/usr/bin/env bash\nexec "<path>" gate "$@"\n`
   *
   * This is a pure function — same input always produces same output.
   * The trailing newline ensures the file ends cleanly.
   *
   * @param resolvedPath the absolute path to the resolved probatio binary
   * @return the shim file content (shebang + exec + trailing newline)
   */
  def generateShim(resolvedPath: String): String =
    generateShim(resolvedPath, "gate")

  /**
   * Generates the 3-line shim content for the given resolved binary path
   * and subcommand.
   *
   * The output is always:
   *   `#!/usr/bin/env bash\nexec "<path>" <subcommand> "$@"\n`
   *
   * This is a pure function — same inputs always produce same output.
   * The trailing newline ensures the file ends cleanly.
   *
   * @param resolvedPath the absolute path to the resolved probatio binary
   * @param subcommand   the probatio subcommand to invoke (e.g. "gate",
   *                     "spec-lint", "chain-state", "danger-scan",
   *                     "reconcile")
   * @return the shim file content (shebang + exec + trailing newline)
   */
  def generateShim(resolvedPath: String, subcommand: String): String =
    s"""#!/usr/bin/env bash
       |exec "$resolvedPath" $subcommand "$$@"
       |""".stripMargin
}
