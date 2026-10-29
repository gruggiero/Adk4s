package org.sinemenda.probatio.cli

/**
 * The arguments a process receives, program name EXCLUDED.
 *
 * Constructed only from a runtime entry point (`fromRuntime`) or from an
 * explicit test fixture (`fromFixture`). Direct construction via
 * `ProgramArgs(List(...))` does not compile — the opaque type has no public
 * `apply`. This makes "did this list include the program name?" a type-level
 * question rather than a convention: a test cannot silently adopt the
 * implementation's assumption by hand-building a program-name-prefixed list.
 *
 * spec: cli-entrypoint-contract — Concepts Introduced: ProgramArgs
 * spec: cli-entrypoint-contract — Requirement: An argument list that includes the program name is not constructible at the entry point
 * spec: cli-entrypoint-contract — Compile-Negative: ProgramArgs from a program-name-prefixed list
 */
opaque type ProgramArgs = List[String]

object ProgramArgs:

  /**
   * Constructs `ProgramArgs` from the runtime argument array.
   *
   * The runtime delivers `args` without the program name (JVM convention).
   * This is the only construction path the entry point uses.
   */
  def fromRuntime(args: Array[String]): ProgramArgs = args.toList

  /**
   * Constructs `ProgramArgs` from a test fixture.
   *
   * The caller explicitly declares this is a fixture, not runtime arguments.
   * The list MUST NOT include the program name — it represents what the
   * runtime would have delivered.
   */
  def fromFixture(args: List[String]): ProgramArgs = args

  /** The empty argument list. */
  val empty: ProgramArgs = Nil

  extension (pa: ProgramArgs)
    /** The underlying argument list. */
    def toList: List[String] = pa

    /** Converts to an array for delegation to entrypoints. */
    def toArray: Array[String] = pa.toArray

    /** The first argument, if any. */
    def headOption: Option[String] = pa.headOption

    /** The arguments after the first. */
    def tail: ProgramArgs = pa.drop(1)

    /** Whether the list is empty. */
    def isEmpty: Boolean = pa.isEmpty

    /** Whether the list is non-empty. */
    def nonEmpty: Boolean = pa.nonEmpty

    /** Whether the list contains a token. */
    def contains(token: String): Boolean = pa.contains(token)

    /** The number of arguments. */
    def length: Int = pa.length
