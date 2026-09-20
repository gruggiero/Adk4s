package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.Validator

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal // danger-scan:allow typed-catch — boundary IO degrades to named Lefts/defaults, never fakes a success

/**
 * The I/O adapter layer: reads files via os-lib, parses args, calls core,
 * renders via StdoutRenderer, maps to Outcome[Int] (R-P1 wiring).
 *
 * Pure where possible (arg parse + render); side-effecting only at the
 * os-lib boundary. The wiring layer delegates all decision logic to
 * probatio-core — it does not re-implement validation, chain-state
 * computation, or gate decisions.
 *
 * spec: cli-wiring — Concepts Introduced: SubcommandWiring
 * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
 */
object SubcommandWiring:

  /**
   * Parse args into a flag map. Returns Left(error) on missing values or
   * unknown flags. Each flag consumes a value; a flag with no value is a
   * Finding — never a loop (matching the predecessor's `need_value`).
   *
   * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
   */
  def parseArgs(
    args: Array[String],
    knownFlags: Set[String]
  ): Either[CliError, Map[String, String]] =
    parseArgs(args, knownFlags, Set.empty)

  /**
   * Parse args into a flag map with a boolean-flag set — flags in
   * `booleanFlags` consume no value and record their presence as `"1"`
   * (the predecessor's `FLAG=1; shift` discipline: `--forgive-unchanged`,
   * `--write`, `--check-installed`). Value flags still require a value;
   * unknown tokens are still `UnknownFlag`, never skipped.
   */
  def parseArgs(
    args: Array[String],
    knownFlags: Set[String],
    booleanFlags: Set[String]
  ): Either[CliError, Map[String, String]] =
    parseArgsLoop(args.toList, knownFlags, booleanFlags, Set.empty, Map.empty)

  /**
   * As above, plus a `forbiddenFlags` set: a flag the parser knows but
   * this mode refuses (the predecessor's `run` dies on `--exit`/`--source`
   * where they appear — before a missing value or an unknown token that
   * follows them would be named). Checked in flag position only: a
   * forbidden name as another flag's VALUE is data, not a refusal.
   */
  def parseArgs(
    args: Array[String],
    knownFlags: Set[String],
    booleanFlags: Set[String],
    forbiddenFlags: Set[String]
  ): Either[CliError, Map[String, String]] =
    parseArgsLoop(args.toList, knownFlags, booleanFlags, forbiddenFlags, Map.empty)

  /** Tail-recursive arg parsing loop. */
  private def parseArgsLoop(
    args: List[String],
    knownFlags: Set[String],
    booleanFlags: Set[String],
    forbiddenFlags: Set[String],
    acc: Map[String, String]
  ): Either[CliError, Map[String, String]] =
    args match
      case Nil => Right(acc)
      case flag :: _ if forbiddenFlags.contains(flag) =>
        Left(CliError.ForbiddenFlag(flag))
      case flag :: rest if booleanFlags.contains(flag) =>
        parseArgsLoop(rest, knownFlags, booleanFlags, forbiddenFlags, acc + (flag -> "1"))
      case flag :: rest if knownFlags.contains(flag) =>
        rest match
          case value :: remaining =>
            parseArgsLoop(remaining, knownFlags, booleanFlags, forbiddenFlags, acc + (flag -> value))
          case Nil =>
            Left(CliError.MissingValue(flag))
      case unknown :: _ =>
        Left(CliError.UnknownFlag(unknown))

  /**
   * Read a ledger file as a list of JSON values. Returns Undetermined on
   * I/O failure (nonexistent file, not a regular file, unreadable, or
   * a line that doesn't parse as JSON).
   *
   * spec: cli-wiring — Scenario: An unreadable ledger file produces undetermined
   * spec: cli-wiring — Requirement: The chain-state subcommand wires to ChainState.compute and emits the contract-conformant JSON report
   */
  def readLedgerFile(
    path: String
  ): Outcome[List[ujson.Value]] =
    val filePath: java.nio.file.Path = Paths.get(path)
    // The reason is data, not a diagnostic line — the `UNDETERMINED —`
    // marker is written exactly once, by the reporting layer, when the
    // diagnostic is emitted (spec 5: the embedded prefix produced the
    // `UNDETERMINED — UNDETERMINED —` double marker).
    if !Files.exists(filePath) then Outcome.Undetermined(s"no ledger at $path")
    else if !Files.isRegularFile(filePath) then Outcome.Undetermined(s"$path is not a regular file")
    else if !Files.isReadable(filePath) then Outcome.Undetermined(s"$path is not readable")
    else
      try
        Using.resource(Files.lines(filePath)) { lines =>
          val lineList: List[String] = lines.iterator().asScala.toList
          parseLedgerLines(lineList, path) match
            case Right(values) => Outcome.Ran(values)
            case Left(err)     => Outcome.Undetermined(err)
        }
      catch
        // A non-UTF-8 file fails mid-iteration (MalformedInputException);
        // a file removed between the checks and the open fails on it.
        // Either way the predecessor's per-line read failure is
        // UNDETERMINED (exit 2), never a JVM crash.
        case NonFatal(e) => // danger-scan:allow typed-catch — an unreadable file is could-not-determine, not a crash
          Outcome.Undetermined(s"could not read $path: ${e.getMessage}")

  /** Parse each line as JSON, validating against the 15-clause contract. */
  private def parseLedgerLines(
    lines: List[String],
    path: String
  ): Either[String, List[ujson.Value]] =
    parseLedgerLinesLoop(lines, index = 0, acc = List.empty, path)

  /** Tail-recursive ledger line parser. */
  private def parseLedgerLinesLoop(
    lines: List[String],
    index: Int,
    acc: List[ujson.Value],
    path: String
  ): Either[String, List[ujson.Value]] =
    lines match
      case Nil => Right(acc.reverse)
      case line :: rest =>
        if line.isEmpty then Left(s"line ${index + 1} is empty; a ledger holds one record per line")
        else
          try
            val json: ujson.Value = ujson.read(line)
            // Validate against the 15-clause contract
            Validator.validateFull(json) match
              case Left(violation) =>
                Left(
                  s"line ${index + 1} violates the record contract: clause ${violation.clauseIndex} — ${violation.description}"
                )
              case Right(validated) if validated.record.v != supportedVersion =>
                // The contract admits any integer v >= 1; the reader
                // accepts only the version it knows — matching the
                // predecessor's separate `v != SUPPORTED_V` refusal.
                Left(
                  s"line ${index + 1} has format version ${validated.record.v}; this reader knows $supportedVersion. Refusing to report a partial result."
                )
              case Right(_) =>
                parseLedgerLinesLoop(rest, index + 1, json :: acc, path)
          catch
            // ujson.read wraps its ParseException in
            // upickle.core.TraceVisitor.TraceException — catch the failure,
            // not the single exception class.
            case NonFatal(_) => // danger-scan:allow typed-catch — bad JSON maps to a named Left
              Left(s"line ${index + 1} does not parse as JSON")

  /**
   * Append a record line to the ledger file. Returns Undetermined on I/O
   * failure. Ensures the file ends with a newline before appending (matching
   * the predecessor's record-separator handling).
   *
   * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
   * spec: cli-wiring — Scenario: A conformant record is appended successfully
   */
  def appendLedgerLine(
    path: String,
    line: String
  ): Outcome[Unit] =
    try
      val filePath: java.nio.file.Path = Paths.get(path)
      // Ensure file ends with newline before appending
      if Files.exists(filePath) && Files.size(filePath) > 0 then
        // lastOption: a file truncated between the size check and the
        // read yields no last byte — an empty ledger needs no separator
        // (a leading '\n' would be an invalid blank first row).
        val lastByte: Option[Byte] = Files.readAllBytes(filePath).lastOption
        if lastByte.exists((b: Byte) => b != '\n'.toByte) then
          Files.write(filePath, "\n".getBytes, StandardOpenOption.APPEND)
      Files.write(filePath, (line + "\n").getBytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      Outcome.Ran(())
    catch
      case e: java.io.IOException =>
        // The reason is data, not a diagnostic line — the `UNDETERMINED —`
        // marker is written once by the emitting call site.
        Outcome.Undetermined(s"could not append to $path: ${e.getMessage}")

  /** Write a string to stdout. */
  def emitStdout(s: String): Unit =
    System.out.print(s)

  /** Write a string to stderr. */
  def emitStderr(s: String): Unit =
    System.err.print(s)

  /**
   * Stamp the current UTC timestamp in ISO-8601 format (matching the
   * predecessor's `date -u +%Y-%m-%dT%H:%M:%SZ`).
   */
  def stampTimestamp: String =
    Instant.now().toString.substring(0, 19) + "Z"

  /** The supported format version (matching the predecessor's SUPPORTED_V). */
  val supportedVersion: Int = 1

  // ── Shared git/file I/O adapters (spec 7) ──────────────────────────────

  /** The repository containing `dir` — `git -C <dir> rev-parse --show-toplevel`. */
  private[cli] def repoContaining(dir: java.nio.file.Path): Option[java.nio.file.Path] =
    if Files.isDirectory(dir) then SpecLintCmd.gitOut(dir, List("rev-parse", "--show-toplevel")).map(Paths.get(_))
    else None

  /**
   * The ledger file's repository root — the predecessor's
   * `cd "$(dirname "$FILE")" && git rev-parse --show-toplevel || echo
   * <ledger_dir>`: the repo root when the ledger sits inside a work tree,
   * the ledger's own directory otherwise. Never fails.
   */
  private[cli] def repoRootOf(ledgerDir: java.nio.file.Path): java.nio.file.Path =
    repoContaining(ledgerDir).getOrElse(ledgerDir)

  /** `git <args>` under `dir`, returning the exit code. */
  private[cli] def gitExit(dir: java.nio.file.Path, args: List[String]): Int =
    try
      val pb: ProcessBuilder = new ProcessBuilder(("git" +: args)*)
      pb.directory(dir.toFile)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.start().waitFor()
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a git failure is "changed", never "unchanged"
        128

  /**
   * The forgive-unchanged oracle — the predecessor's
   * `git diff --quiet <row.baseline> HEAD -- <artifact>` under the ledger
   * file's repository root. Anything that is not a clean diff (changed
   * artifact, unknown baseline, no repo) is "changed" — a stale row that
   * cannot be forgiven stays absent evidence.
   */
  private[cli] def forgivePredicate(ledgerFile: String): (String, String) => Boolean =
    val ledgerDir: Option[java.nio.file.Path] =
      Option(Paths.get(ledgerFile).toAbsolutePath.normalize.getParent)
    val repo: Option[java.nio.file.Path] = ledgerDir.flatMap(repoContaining)
    (rowBaseline: String, artifact: String) =>
      repo match
        case Some(r) =>
          gitExit(r, List("diff", "--quiet", rowBaseline, "HEAD", "--", artifact)) == 0
        case None => false

  /**
   * The repository's absolute git dir — `git rev-parse --absolute-git-dir`
   * under `dir` (worktree-aware; plain repos answer `<dir>/.git`).
   */
  private[cli] def absoluteGitDirOf(dir: java.nio.file.Path): Option[java.nio.file.Path] =
    SpecLintCmd.gitOut(dir, List("rev-parse", "--absolute-git-dir")).map(Paths.get(_))

  /**
   * `[ -e <path> ]` — true when the path exists (links followed), false
   * when missing or on I/O error. Kept as a seam: `Files.exists` mutates
   * to the non-compiling `Files.forall`, so the call lives here, outside
   * the spec-8 mutation set.
   */
  private[cli] def fileExists(path: java.nio.file.Path): Boolean =
    Files.exists(path)

  /** `sha256sum <file>` — the hex digest, or None when the file can't be hashed. */
  private[cli] def sha256OfFile(path: java.nio.file.Path): Option[String] =
    if !Files.isRegularFile(path) then None
    else
      try Some(sha256Hex(Files.readAllBytes(path)))
      catch
        case NonFatal(_) => // danger-scan:allow fail-open — an unhashable file is an absence of hash, never a fake one
          None

  /** The hex SHA-256 of a byte string — `sha256sum` over captured output. */
  private[cli] def sha256Hex(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map((b: Byte) => f"${b & 0xff}%02x")
      .mkString

  /** Render an arg-parse error as the predecessor's message text. */
  private[cli] def argErrorMessage(err: CliError): String = err match
    case CliError.MissingValue(flag)       => s"$flag requires a value"
    case CliError.UnknownFlag(flag)        => s"unrecognised argument: $flag"
    case CliError.UnknownSubcommand(token) => s"unrecognised argument: $token"
    case CliError.InvalidEnum(flag, value) => s"unrecognised argument: $value"
    case CliError.ForbiddenFlag(flag)      => s"flag not accepted here: $flag"

  /** `printf '%s' "$cmd" | bash -n` — the predecessor's syntax check (no execution). */
  private[cli] def shellParses(command: String): Boolean =
    try
      val pb: ProcessBuilder = new ProcessBuilder("bash", "-n", "-c", command)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.start().waitFor() == 0
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a non-runnable shell is "does not parse"
        false

  /**
   * `(cd "$cwd" && eval "$cmd") >/dev/null 2>&1` — the predecessor's
   * replay: output discarded, exit observed.
   */
  private[cli] def replayCommand(cwd: java.nio.file.Path, command: String): Int =
    try
      val pb: ProcessBuilder = new ProcessBuilder("bash", "-c", command)
      pb.directory(cwd.toFile)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.start().waitFor()
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a failed spawn is a divergent replay, never a clean one
        127

  /**
   * `(cd "$cwd" && eval "$cmd") >"$tmp" 2>&1` — the predecessor's `run`
   * capture: the command's merged stdout+stderr bytes, its observed exit,
   * and wall time in milliseconds.
   */
  private[cli] def executeCaptured(cwd: java.nio.file.Path, command: String): (Int, Array[Byte], Long) =
    val start: Long = System.currentTimeMillis()
    try
      val pb: ProcessBuilder = new ProcessBuilder("bash", "-c", command)
      pb.directory(cwd.toFile)
      // The predecessor's `>"$tmp" 2>&1`: stdout and stderr are merged in
      // arrival order, exactly the bytes the run row's digest covers.
      pb.redirectErrorStream(true)
      val p: Process          = pb.start()
      val output: Array[Byte] = p.getInputStream.readAllBytes()
      val exitCode: Int       = p.waitFor()
      val wallMs: Long        = math.max(0L, System.currentTimeMillis() - start)
      (exitCode, output, wallMs)
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a failed spawn is the honest exit-127 observation
        val wallMs: Long = math.max(0L, System.currentTimeMillis() - start)
        (127, Array.emptyByteArray, wallMs)

  /** Read a text file; `Left` carries the reason it could not be read. */
  private[cli] def readTextFile(path: java.nio.file.Path): Either[String, String] =
    if !Files.isRegularFile(path) then Left(s"not found at $path")
    else if !Files.isReadable(path) then Left(s"not readable at $path")
    else
      try Right(Files.readString(path, StandardCharsets.UTF_8))
      catch
        case NonFatal(e) => // danger-scan:allow typed-catch — an unreadable file maps to a named Left
          Left(s"could not read $path: ${e.getMessage}")

  /** Write a text file; `Left` carries the reason it could not be written. */
  private[cli] def writeTextFile(path: java.nio.file.Path, content: String): Either[String, Unit] =
    try
      Files.writeString(path, content, StandardCharsets.UTF_8)
      Right(())
    catch
      case NonFatal(e) => // danger-scan:allow typed-catch — an unwritable file maps to a named Left
        Left(s"could not write $path: ${e.getMessage}")

end SubcommandWiring
