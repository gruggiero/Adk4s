package org.sinemenda.probatio.cli

/**
 * Where the program name came from — an adapter fact produced at the
 * runtime boundary, never re-derived inside the dispatcher.
 *
 * `NamedExecutable` carries the basename the process was started under
 * (native image, symlink). `Archive` carries the assembly archive's path:
 * under `java -jar` the runtime reports the archive path as the program
 * name, and that path is a build artifact, not a tool name — its presence
 * means "no name was given, dispatch by the first argument".
 *
 * The sealed enumeration is the constraint: every match over the source is
 * total, and an archive can never carry a tool name.
 *
 * spec: jar-launcher-dispatch — Concepts Introduced: InvocationSource
 * spec: jar-launcher-dispatch — Requirement: A tool run through its archive dispatches by its first argument
 */
enum InvocationSource:

  /** The process was started under an executable's basename. */
  case NamedExecutable(basename: String)

  /** The process was started through the assembly archive; `path` is the archive's location, not a tool name. */
  case Archive(path: String)

object InvocationSource:

  /** The archive suffix the runtime reports under `java -jar`. */
  private val archiveSuffix: String = ".jar"

  /**
   * Classifies a raw runtime program name into its source.
   *
   * Under `java -jar <archive>` the runtime (`sun.java.command`) reports
   * the archive's path as the program name — that path is a build
   * artifact, so it classifies as `Archive`. Any other name is the
   * executable the process was started under; only its basename is kept.
   */
  def classify(raw: String): InvocationSource =
    if raw.endsWith(archiveSuffix) then InvocationSource.Archive(raw)
    else InvocationSource.NamedExecutable(raw.substring(raw.lastIndexOf('/') + 1))
