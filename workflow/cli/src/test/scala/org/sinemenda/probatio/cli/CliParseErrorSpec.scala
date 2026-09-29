package org.sinemenda.probatio.cli

import hedgehog.*

/**
 * Tests for arg-parse error attribution (R-P4).
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Arg parsing errors name the missing or invalid flag
 * spec: port-scanner-to-probatio/cli-protocol — Property: arg-parse-error-attribution
 */
final class CliParseErrorSpec extends ProbatioCliSuite:

  // ── Scenario: Missing required value names the flag
  // spec: cli-protocol — Scenario: Missing required value names the flag
  test("MissingValue error names the flag 'baseline'"):
    val err: CliError = CliError.MissingValue("baseline")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("baseline"), s"message '$msg' does not name 'baseline'")

  // ── Scenario: Unknown subcommand names the token
  // spec: cli-protocol — Scenario: Unknown subcommand names the token
  test("UnknownSubcommand error names the token 'foo'"):
    val err: CliError = CliError.UnknownSubcommand("foo")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("foo"), s"message '$msg' does not name 'foo'")

  // ── Scenario: Invalid enum value names the flag and value
  // spec: cli-protocol — Scenario: Invalid enum value names the flag and value
  test("InvalidEnum error names both flag 'event' and value 'invalid'"):
    val err: CliError = CliError.InvalidEnum("event", "invalid")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("event"), s"message '$msg' does not name flag 'event'")
    assert(msg.contains("invalid"), s"message '$msg' does not name value 'invalid'")

  test("UnknownFlag error names the flag 'unknown-flag'"):
    val err: CliError = CliError.UnknownFlag("unknown-flag")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("unknown-flag"), s"message '$msg' does not name 'unknown-flag'")

  // ── Scenario: Error without offending token is forbidden (adversarial)
  // spec: cli-protocol — Scenario: Error without offending token is forbidden (adversarial)
  test("every CliError variant's render output contains its offendingToken"):
    val errors: List[CliError] = List(
      CliError.UnknownSubcommand("foo"),
      CliError.MissingValue("baseline"),
      CliError.InvalidEnum("event", "invalid"),
      CliError.UnknownFlag("unknown-flag")
    )
    errors.foreach { err =>
      val msg: String = CliErrorRender.render(err)
      assert(
        msg.contains(err.offendingToken),
        s"message '$msg' does not contain offendingToken '${err.offendingToken}'"
      )
    }

  test("every CliError variant has a non-empty offendingToken"):
    val errors: List[CliError] = List(
      CliError.UnknownSubcommand("foo"),
      CliError.MissingValue("baseline"),
      CliError.InvalidEnum("event", "invalid"),
      CliError.UnknownFlag("unknown-flag")
    )
    errors.foreach { err =>
      assert(
        err.offendingToken.nonEmpty,
        s"variant ${err.getClass.getSimpleName} has empty offendingToken"
      )
    }

  // ── Property: arg-parse-error-attribution
  // spec: cli-protocol — Property: arg-parse-error-attribution
  property("arg-parse-error-attribution"):
    for
      kind  <- Gen.element1("unknown_subcommand", "missing_value", "invalid_enum", "unknown_flag").forAll
      token <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      flag  <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
    yield
      val error: CliError = kind match
        case "unknown_subcommand" => CliError.UnknownSubcommand(token)
        case "missing_value"      => CliError.MissingValue(flag)
        case "invalid_enum"       => CliError.InvalidEnum(flag, token)
        case "unknown_flag"       => CliError.UnknownFlag(flag)
      val msg: String           = CliErrorRender.render(error)
      val expectedToken: String = error.offendingToken
      Result
        .assert(msg.contains(expectedToken))
        .and(Result.assert(msg.nonEmpty))

  // ── spec: entrypoint-split — Step 2 oracle ────────────────────────────
  // A rejected invocation stays rejected identically: same message, same
  // termination status. The recorded before-split rows are the oracle;
  // each row must have been a rejection (non-zero exit) AND replay
  // identically through the artifact it names.
  //
  // spec: entrypoint-split — Scenario: Adversarial — a rejected invocation is still rejected identically
  test("a rejected invocation is still rejected identically after the split"):
    val repoRoot: java.nio.file.Path = EntrypointSplitOracle.repoRoot
    val native: String = repoRoot.resolve("workflow/cli/target/native-image/probatio").toString
    val jarDir: java.nio.file.Path =
      repoRoot.resolve("workflow/cli/target/scala-3.8.4")
    val jar: String =
      val candidates: List[java.nio.file.Path] =
        if java.nio.file.Files.isDirectory(jarDir) then
          scala.util
            .Using(java.nio.file.Files.list(jarDir))(
              _.filter((p: java.nio.file.Path) => p.getFileName.toString.matches("probatio-cli-assembly-.*\\.jar"))
                .toArray(Array.ofDim[java.nio.file.Path](_))
                .toList
            )
            .fold(
              (e: Throwable) => fail(s"could not list $jarDir: ${e.getMessage}"),
              (l: List[java.nio.file.Path]) => l
            )
        else List.empty
      candidates match
        case List(single) => single.toAbsolutePath.toString
        case Nil          => fail(s"no assembly jar under $jarDir — cannot replay the corpus")
        case many         => fail(s"multiple assembly jars under $jarDir: ${many.mkString}")
    val rejectionArgs: List[List[String]] = List(
      List("gate", "--repo"),
      List("spec-lint", "--format", "json", "--nonexistent-flag-xyz"),
      List("chain-state", "--change"),
      List("graph", "impact"),
      List("ledger", "read", "--file"),
      List("checkpoint", "--change"),
      List("reconcile", "--file"),
      List("danger-scan", "--nonexistent-flag-xyz"),
      List("metals"),
      List("install-skills", "--frobnicate"),
      List("install-hooks", "--agent")
    )
    val corpus: List[EntrypointSplitOracle.CorpusRow] = EntrypointSplitOracle.loadCorpus
    rejectionArgs.foreach { (argv: List[String]) =>
      List("native" -> List(native), "archive" -> List("java", "-jar", jar)).foreach {
        case (artifact: String, cmd: List[String]) =>
          val recorded: EntrypointSplitOracle.CorpusRow = corpus.find { (r: EntrypointSplitOracle.CorpusRow) =>
            r.artifact == artifact && r.argv == argv
          } match
            case Some(row) => row
            case None      => fail(s"no recorded corpus row for $artifact ${argv.mkString(" ")}")
          assert(recorded.exit != 0, s"the recorded rejection was not a rejection: ${argv.mkString(" ")}")
          val r: org.sinemenda.probatio.migration.HermeticResult =
            org.sinemenda.probatio.migration.HermeticEnv.capture(
              cmd ++ argv,
              org.sinemenda.probatio.migration.HermeticEnv.empty
            )
          assertEquals(
            (r.exitCode, r.out, r.err),
            (recorded.exit, recorded.out, recorded.err),
            s"rejection changed after the split: $artifact ${argv.mkString(" ")}"
          )
      }
    }
