package org.sinemenda.probatio.cli

/**
 * Resolved paths and env-var overrides read once at entrypoint start (R-P1 wiring).
 *
 * Carries the resolved paths (repo root, change dir, ledger file, git-dir) and
 * env-var overrides read once at entrypoint start; passed to core calls.
 * Avoids re-reading env per subcommand. An immutable case class constructed
 * once at dispatch — no mutation, no race conditions.
 *
 * The escape hatch (`PROBATIO_HOOKS=1`) is read here once and passed as a
 * boolean to the gate's predecessor-check and grant-waiver calls. The pure
 * core functions take booleans, not env vars (compile-negative enforced).
 *
 * spec: cli-wiring — Concepts Introduced: CliContext
 * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
 */
final case class CliContext(
  repoRoot: String,
  changeDir: String,
  ledgerFile: String,
  gitDir: String,
  escapeHatch: Boolean
)

object CliContext:

  /**
   * Resolve a `CliContext` from the environment and explicit paths.
   *
   * Reads `PROBATIO_HOOKS` once (the escape hatch). All paths are resolved
   * by the caller and passed in — this function does not perform file I/O.
   * The escape hatch is `true` when `PROBATIO_HOOKS` is set to `"1"`.
   *
   * spec: cli-wiring — Scenario: The escape hatch bypasses the tool-call lock
   */
  def resolve(
    repoRoot: String,
    changeDir: String,
    ledgerFile: String,
    gitDir: String,
    escapeHatch: Boolean
  ): CliContext =
    CliContext(
      repoRoot = repoRoot,
      changeDir = changeDir,
      ledgerFile = ledgerFile,
      gitDir = gitDir,
      escapeHatch = escapeHatch
    )

  /**
   * Read the escape hatch from a given environment — `true` when
   * `PROBATIO_HOOKS` is set to `"1"`. The gate boundary passes the process
   * environment.
   */
  def readEscapeHatch(env: Map[String, String]): Boolean =
    env.get("PROBATIO_HOOKS").contains("1")
