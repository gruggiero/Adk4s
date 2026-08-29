package org.sinemenda.probatio.cli

/**
 * Multicall dispatch — selects a tool from the invocation name and the first
 * user argument, never by consuming two user arguments.
 *
 * The surface determines which tool to run from (a) the name the process was
 * started under and (b) at most the first argument the caller supplied, and
 * passes every remaining caller argument, in order and without omission, to
 * that tool.
 *
 * Dispatch priority:
 *   1. If the invocation name's basename names a tool, select that tool and
 *      pass ALL arguments unchanged (no argument is consumed as a tool name).
 *   2. Else if the basename is the generic name (`probatio` or `prob`), check
 *      the first argument:
 *      a. If it names a tool, select that tool and pass the tail.
 *      b. If it does not, report `UnknownSubcommand` with the first argument
 *         (or "(none)" if no arguments).
 *   3. Else the invocation name names nothing — report `UnknownSubcommand`
 *      with the basename.
 *
 * Both invocation signals (generic name + tool-as-first-arg, and
 * symlink-name-as-invocation) select the same tool and deliver the same
 * remaining arguments.
 *
 * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
 * spec: cli-entrypoint-contract — Property: dispatch-equivalence-across-signals
 * spec: cli-entrypoint-contract — Formal Contract: resolveAndSplit (Ring 6)
 */
object MulticallDispatch:

  /** The short alias for the multicall binary. */
  val aliasName: String = "prob"

  /** The canonical binary name. */
  val binaryName: String = "probatio"

  /** The generic invocation names that dispatch by the first argument. */
  val genericNames: Set[String] = Set(aliasName, binaryName)

  /**
   * Resolves the subcommand from the invocation name and the argument list,
   * returning the selected tool and the remaining arguments.
   *
   * The remainder is the supplied list with at most its first element removed:
   * the first element is removed if and only if it named the selected tool
   * (which happens only under the generic invocation name).
   *
   * spec: cli-entrypoint-contract — Scenario: Named-tool invocation reaches the tool with all its arguments
   * spec: cli-entrypoint-contract — Scenario: Symlink invocation reaches the tool with all its arguments
   * spec: cli-entrypoint-contract — Scenario: Error path — the first argument names nothing and the invocation name names nothing
   * spec: cli-entrypoint-contract — Scenario: Edge case — no arguments at all under the generic invocation name
   * spec: cli-entrypoint-contract — Scenario: Adversarial — a flag value that happens to spell a tool name is not treated as a tool name
   */
  def resolveAndSplit(
    name: InvocationName,
    args: ProgramArgs
  ): Either[CliError, (Subcommand, ProgramArgs)] =
    val base: String = name.basename
    // Priority 1: the invocation name's basename names a tool — select it
    // and pass ALL arguments unchanged (no argument is consumed as a tool
    // name).
    Subcommand.fromString(base) match
      case Right(sub) => Right((sub, args))
      case Left(_)    =>
        // Priority 2: the basename is a generic name — dispatch by the first
        // argument.
        if genericNames.contains(base) then
          // POSIX `--` separator: consume it and dispatch by the next arg.
          val effectiveArgs: ProgramArgs =
            if args.headOption.contains("--") then args.tail else args
          effectiveArgs.headOption match
            case Some(firstArg) =>
              Subcommand.fromString(firstArg) match
                case Right(sub) => Right((sub, effectiveArgs.tail))
                case Left(_)    => Left(CliError.UnknownSubcommand(firstArg))
            case None =>
              // Edge case: no arguments at all under the generic name
              // (after consuming `--` if present).
              Left(CliError.UnknownSubcommand("(none)"))
        else
          // Priority 3: the invocation name names nothing.
          Left(CliError.UnknownSubcommand(base))
