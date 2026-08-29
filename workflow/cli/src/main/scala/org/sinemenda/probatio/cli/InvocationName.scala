package org.sinemenda.probatio.cli

/**
 * The name the process was invoked under, obtained from the runtime, never
 * from an argument.
 *
 * Constructed only via `fromRuntime`, which requires a non-empty string.
 * Direct construction via `InvocationName("...")` does not compile — the
 * opaque type has no public `apply`. This prevents an argument from being
 * mistaken for the invocation name.
 *
 * spec: cli-entrypoint-contract — Concepts Introduced: InvocationName
 * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
 */
opaque type InvocationName = String

object InvocationName:

  /**
   * Constructs `InvocationName` from the runtime.
   *
   * Returns `Left(reason)` if the name is empty, `Right(name)` otherwise.
   * The entry point obtains the raw name from `sun.java.command` or
   * `ProcessHandle.current().info().command()` (JAR path) or the executable
   * name (native-image).
   */
  def fromRuntime(name: String): Either[String, InvocationName] =
    if name.nonEmpty then Right(name) else Left("invocation name must be non-empty")

  extension (inv: InvocationName)
    /** The underlying invocation name string. */
    def value: String = inv

    /** The basename (after the last `/`), used for symlink dispatch. */
    def basename: String = inv.substring(inv.lastIndexOf('/') + 1)
