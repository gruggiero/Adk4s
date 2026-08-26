package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.Validator

import java.time.Instant
import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

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
    parseArgsLoop(args.toList, knownFlags, Map.empty)

  /** Tail-recursive arg parsing loop. */
  private def parseArgsLoop(
    args: List[String],
    knownFlags: Set[String],
    acc: Map[String, String]
  ): Either[CliError, Map[String, String]] =
    args match
      case Nil => Right(acc)
      case flag :: rest if knownFlags.contains(flag) =>
        rest match
          case value :: remaining =>
            parseArgsLoop(remaining, knownFlags, acc + (flag -> value))
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
    if !Files.exists(filePath) then
      Outcome.Undetermined(s"UNDETERMINED — no ledger at $path")
    else if !Files.isRegularFile(filePath) then
      Outcome.Undetermined(s"UNDETERMINED — $path is not a regular file")
    else if !Files.isReadable(filePath) then
      Outcome.Undetermined(s"UNDETERMINED — $path is not readable")
    else
      Using.resource(Files.lines(filePath)) { lines =>
        val lineList: List[String] = lines.iterator().asScala.toList
        parseLedgerLines(lineList, path) match
          case Right(values) => Outcome.Ran(values)
          case Left(err)     => Outcome.Undetermined(s"UNDETERMINED — $err")
      }

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
        if line.isEmpty then
          Left(s"line ${index + 1} is empty; a ledger holds one record per line")
        else
          try
            val json: ujson.Value = ujson.read(line)
            // Validate against the 15-clause contract
            Validator.validateFull(json) match
              case Left(violation) =>
                Left(s"line ${index + 1} violates the record contract: clause ${violation.clauseIndex} — ${violation.description}")
              case Right(_) =>
                parseLedgerLinesLoop(rest, index + 1, json :: acc, path)
          catch
            case _: ujson.ParseException =>
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
        val lastByte: Byte = Files.readAllBytes(filePath).last
        if lastByte != '\n'.toByte then
          Files.write(filePath, "\n".getBytes, StandardOpenOption.APPEND)
      Files.write(filePath, (line + "\n").getBytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      Outcome.Ran(())
    catch
      case e: java.io.IOException =>
        Outcome.Undetermined(s"UNDETERMINED — could not append to $path: ${e.getMessage}")

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

end SubcommandWiring
