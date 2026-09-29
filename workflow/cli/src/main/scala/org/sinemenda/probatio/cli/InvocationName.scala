package org.sinemenda.probatio.cli

/**
 * The name the process was invoked under, obtained from the runtime, never
 * from an argument.
 *
 * Constructed only via `fromRuntime`, which requires a classified
 * `InvocationSource` — the dispatcher distinguishes an archive path from
 * an executable name by constructor, never by inspecting string suffixes
 * at the decision site. Direct construction via `InvocationName("...")`
 * does not compile — the opaque type has no public `apply`. This prevents
 * an argument from being mistaken for the invocation name.
 *
 * spec: cli-entrypoint-contract — Concepts Introduced: InvocationName
 * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
 * spec: jar-launcher-dispatch — Concepts Used: InvocationName (modified — constructed from an InvocationSource)
 * spec: jar-launcher-dispatch — Compile-Negative: A program name built from an unclassified string
 */
opaque type InvocationName = InvocationSource

object InvocationName:

  /**
   * Constructs `InvocationName` from the classified runtime source.
   *
   * Returns `Left(reason)` if the source carries an empty name or path,
   * `Right(name)` otherwise. The entry point obtains and classifies the
   * raw name from `sun.java.command` or `ProcessHandle.current().info()
   * .command()` — an archive path classifies as `Archive`, anything else
   * as `NamedExecutable`.
   */
  def fromRuntime(source: InvocationSource): Either[String, InvocationName] =
    source match
      case InvocationSource.NamedExecutable(b) if b.nonEmpty => Right(source)
      case InvocationSource.Archive(p) if p.nonEmpty         => Right(source)
      case InvocationSource.NamedExecutable(_) | InvocationSource.Archive(_) =>
        Left("invocation name must be non-empty")

  extension (inv: InvocationName)
    /** The classified source the dispatcher decides over. */
    def source: InvocationSource = inv

    /** The underlying value — the executable basename or the archive path. */
    def value: String = inv match
      case InvocationSource.NamedExecutable(b) => b
      case InvocationSource.Archive(p)         => p

    /** The basename (after the last `/`), used for symlink dispatch. */
    def basename: String = inv.value.substring(inv.value.lastIndexOf('/') + 1)
