package org.sinemenda.probatio.cli

/**
 * Multicall dispatch (R-P6).
 *
 * Resolves the subcommand from argv(1) (the subcommand argument, e.g.
 * `probatio gate --event …`) or argv(0) (the symlink basename, e.g. a
 * symlink named `gate` pointing at the binary). Both dispatch paths
 * produce identical behavior for the same subcommand. The `prob` alias
 * resolves to argv(1) dispatch.
 *
 * The CLI MUST NOT fail when only one dispatch signal is present.
 *
 * spec: cli-protocol — Requirement: Multicall dispatch by argv(1) and argv(0)
 * spec: cli-protocol — Property: multicall-dispatch-equivalence
 */
object MulticallDispatch:

  /** The short alias for the multicall binary. */
  val aliasName: String = "prob"

  /** The canonical binary name. */
  val binaryName: String = "probatio"

  /**
   * Resolves the subcommand from argv(0) and argv(1).
   *
   * Dispatch priority:
   *   1. If argv(1) names a valid subcommand, dispatch by argv(1).
   *   2. Else if argv(0) (basename) names a valid subcommand, dispatch by
   *      argv(0).
   *   3. Else if argv(0) is the alias `prob`, dispatch by argv(1) (which
   *      must name a subcommand).
   *   4. Else report `UnknownSubcommand` with the argv(1) token (or argv(0)
   *      if argv(1) is absent).
   *
   * Both paths select the same subcommand and produce identical stdout,
   * stderr, and exit code for the same arguments.
   *
   * spec: cli-protocol — Scenario: argv(1) dispatch works
   * spec: cli-protocol — Scenario: argv(0) dispatch via symlink works
   * spec: cli-protocol — Scenario: argv(0) dispatch with alias works
   * spec: cli-protocol — Scenario: Dispatch failing on one signal is forbidden (adversarial)
   */
  def resolve(argv0: String, argv1: Option[String]): Either[CliError, Subcommand] =
    argv1 match
      case Some(token) =>
        Subcommand.fromString(token).orElse {
          // argv(1) is not a subcommand — try argv(0) basename
          val basename: String = argv0.substring(argv0.lastIndexOf('/') + 1)
          if basename == aliasName || basename == binaryName then
            // The binary is invoked by name; argv(1) should have been the
            // subcommand but it wasn't recognized.
            Subcommand.fromString(token)
          else Subcommand.fromString(basename)
        }
      case None =>
        // No argv(1) — dispatch by argv(0) basename
        val basename: String = argv0.substring(argv0.lastIndexOf('/') + 1)
        if basename == aliasName || basename == binaryName then Left(CliError.UnknownSubcommand("(none)"))
        else Subcommand.fromString(basename)
