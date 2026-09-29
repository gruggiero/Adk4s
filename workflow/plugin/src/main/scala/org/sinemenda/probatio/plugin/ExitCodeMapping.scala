package org.sinemenda.probatio.plugin

/**
 * Exit-code mapping for delegating sbt tasks (R-S4).
 *
 * The three-way exit protocol (0/1/2) is the porting invariant the whole
 * schema exists to preserve. This module maps the binary's exit code to
 * a task outcome that distinguishes a finding (exit 1) from an undetermined
 * result (exit 2). Both exit 1 and exit 2 fail the build — a CI operator
 * distinguishing them in logs is by design, not by accident.
 *
 * - Exit 0 → task success (no message needed)
 * - Exit 1 → task failure: `"probatio <tool> reported N finding(s): …"`
 * - Exit 2 → task failure: `"probatio <tool> could not determine: …"`
 *
 * The messages are distinguishable: the exit-1 message contains "reported"
 * and "finding(s)"; the exit-2 message contains "could not determine". A
 * CI operator can tell them apart without inspecting the exit code.
 *
 * spec: sbt-plugin — Requirement: Task exit-code mapping distinguishes finding from undetermined
 * spec: sbt-plugin — Property: exit-code-mapping-distinct
 */
object ExitCodeMapping {

  /**
   * Maps a binary exit code to a task outcome.
   *
   * Returns `Right(())` for exit 0 (success), or `Left(message)` for
   * exit 1 (finding) or exit 2 (undetermined) with the appropriate
   * distinguishable failure message.
   *
   * @param toolName the subcommand name (e.g. "spec-lint", "chain-state")
   * @param exitCode the binary's exit code (0, 1, or 2)
   * @param stdout the binary's stdout content (findings summary or reason)
   * @param findingCount the number of findings (for exit 1); defaults to
   *   extracting a leading integer from stdout, or 0 if none found
   */
  def mapExitCode(
    toolName: String,
    exitCode: Int,
    stdout: String,
    findingCount: Int = -1
  ): Either[String, Unit] = exitCode match {
    case 0 => Right(())
    case 1 =>
      val count: Int = if (findingCount >= 0) findingCount else extractCount(stdout)
      Left(findingMessage(toolName, count, stdout))
    case 2     => Left(undeterminedMessage(toolName, stdout))
    case other => Left(s"probatio $toolName exited with unexpected code $other: $stdout")
  }

  /**
   * Constructs the finding failure message for exit 1.
   *
   * The message contains "reported" and "finding(s)" and the tool name,
   * making it distinguishable from the undetermined message. The count N
   * is included per the spec format:
   *   `"probatio <tool> reported N finding(s): …"`
   *
   * @param toolName the subcommand name
   * @param findingCount the number of findings (N in the spec format)
   * @param stdout the finding summary from stdout
   */
  def findingMessage(toolName: String, findingCount: Int, stdout: String): String =
    s"probatio $toolName reported $findingCount finding(s): $stdout"

  /**
   * Backwards-compatible overload that extracts the count from stdout.
   * Used by tests and callers that don't have a separate count.
   */
  def findingMessage(toolName: String, stdout: String): String =
    findingMessage(toolName, extractCount(stdout), stdout)

  /**
   * Constructs the undetermined failure message for exit 2.
   *
   * The message contains "could not determine" and the tool name,
   * making it distinguishable from the finding message.
   */
  def undeterminedMessage(toolName: String, stdout: String): String =
    s"probatio $toolName could not determine: $stdout"

  /**
   * Extracts a leading integer from the stdout string, used as the finding
   * count when no explicit count is provided. Returns 0 if no integer is
   * found at the start of the string.
   */
  private def extractCount(stdout: String): Int = {
    val trimmed: String = stdout.trim
    val digits: String  = trimmed.takeWhile(c => c.isDigit)
    if (digits.isEmpty) 0 else digits.toInt
  }
}
