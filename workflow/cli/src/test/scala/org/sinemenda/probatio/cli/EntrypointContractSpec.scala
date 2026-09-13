package org.sinemenda.probatio.cli

import hedgehog.*

/**
 * Test oracle for spec: cli-entrypoint-contract
 *
 * Derived from the spec (NOT from the implementation). The implementation
 * (`resolveAndSplit`) has a `???` body in Step 1; these tests are the oracle
 * that the Step 3 implementation must satisfy.
 *
 * ORACLE POLARITY (Step 2):
 *   - Scenario tests that exercise `resolveAndSplit` → RED (??? throws)
 *   - Property tests that exercise `resolveAndSplit` → RED (??? throws)
 *   - Compile-negative tests → GREEN (forbidden constructions don't compile)
 *   - Type-level / construction tests → GREEN
 *
 * spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments
 * spec: cli-entrypoint-contract — Requirement: An argument list that includes the program name is not constructible at the entry point
 * spec: cli-entrypoint-contract — Requirement: A tool that has no implementation is not nameable on the tool surface
 * spec: cli-entrypoint-contract — Property: argument-preservation
 * spec: cli-entrypoint-contract — Property: dispatch-equivalence-across-signals
 * spec: cli-entrypoint-contract — Property: no-silent-selection
 * spec: cli-entrypoint-contract — Property: subprocess-agrees-with-in-process
 */
final class EntrypointContractSpec extends ProbatioCliSuite:

  // ── Helpers ─────────────────────────────────────────────────────────────

  /** Constructs an InvocationName from a raw string (fails the test if empty). */
  private def inv(name: String): InvocationName =
    InvocationName.fromRuntime(name) match
      case Right(value) => value
      case Left(err)    => fail(s"invalid invocation name '$name': $err")

  /** Constructs ProgramArgs from a fixture list. */
  private def args(xs: String*): ProgramArgs =
    ProgramArgs.fromFixture(xs.toList)

  /** The set of exposed tool names (the shrunk enum). */
  private val exposedToolNames: Set[String] =
    Subcommand.values.map(Subcommand.cliName).toSet

  /** The removed tool names (unported — must be unnameable). */
  private val removedToolNames: List[String] =
    List("registry-check", "scan", "removal-audit", "impact-scan", "concept-scanner", "graph")

  /** The mutation tool names (must be unnameable). */
  private val mutationToolNames: List[String] =
    List("update", "delete", "rewrite", "edit")

  // ── Requirement: The tool surface resolves its command from the invocation
  //    name and the first user argument, never by consuming two user arguments
  //
  // spec: cli-entrypoint-contract — Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments

  // ── Scenario: Named-tool invocation reaches the tool with all its arguments
  // spec: cli-entrypoint-contract — Scenario: Named-tool invocation reaches the tool with all its arguments
  test(
    "Named-tool invocation: probatio gate --event session-start --format hook-json → Gate receives [--event, session-start, --format, hook-json]"
  ):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(
        inv("probatio"),
        args("gate", "--event", "session-start", "--format", "hook-json")
      )
    assertEquals(result, Right((Subcommand.Gate, args("--event", "session-start", "--format", "hook-json"))))

  // ── Scenario: Symlink invocation reaches the tool with all its arguments
  // spec: cli-entrypoint-contract — Scenario: Symlink invocation reaches the tool with all its arguments
  test(
    "Symlink invocation: chain-state --change-dir d --change c --baseline abc1234 → ChainState receives all args unchanged"
  ):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(
        inv("chain-state"),
        args("--change-dir", "d", "--change", "c", "--baseline", "abc1234")
      )
    assertEquals(
      result,
      Right((Subcommand.ChainState, args("--change-dir", "d", "--change", "c", "--baseline", "abc1234")))
    )

  // ── Scenario: Error path — the first argument names nothing and the invocation name names nothing
  // spec: cli-entrypoint-contract — Scenario: Error path — the first argument names nothing and the invocation name names nothing
  test("Error path: probatio frobnicate --x → UnknownSubcommand(frobnicate)"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("probatio"), args("frobnicate", "--x"))
    assert(result.isLeft)
    result match
      case Left(CliError.UnknownSubcommand(token)) => assertEquals(token, "frobnicate")
      case other                                   => fail(s"expected UnknownSubcommand, got $other")

  // ── Scenario: Edge case — no arguments at all under the generic invocation name
  // spec: cli-entrypoint-contract — Scenario: Edge case — no arguments at all under the generic invocation name
  test("Edge case: probatio with no args → Left (no tool supplied)"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("probatio"), ProgramArgs.empty)
    assert(result.isLeft)

  // ── Scenario: Adversarial — a flag value that happens to spell a tool name is not treated as a tool name
  // spec: cli-entrypoint-contract — Scenario: Adversarial — a flag value that happens to spell a tool name is not treated as a tool name
  test(
    "Adversarial: chain-state --change gate --baseline abc1234 --change-dir d → ChainState (not Gate), args unchanged"
  ):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(
        inv("chain-state"),
        args("--change", "gate", "--baseline", "abc1234", "--change-dir", "d")
      )
    assertEquals(
      result,
      Right((Subcommand.ChainState, args("--change", "gate", "--baseline", "abc1234", "--change-dir", "d")))
    )

  // ── Requirement: An argument list that includes the program name is not
  //    constructible at the entry point
  //
  // spec: cli-entrypoint-contract — Requirement: An argument list that includes the program name is not constructible at the entry point

  // ── Scenario: Happy path — a runtime entry point produces the argument value
  // spec: cli-entrypoint-contract — Scenario: Happy path — a runtime entry point produces the argument value
  test("ProgramArgs.fromRuntime wraps the runtime array without the program name"):
    val runtimeArgs: Array[String] = Array("gate", "--event", "session-start")
    val wrapped: ProgramArgs       = ProgramArgs.fromRuntime(runtimeArgs)
    assertEquals(wrapped.toList, List("gate", "--event", "session-start"))

  test("InvocationName.fromRuntime obtains the name separately from the runtime"):
    val result: Either[String, InvocationName] = InvocationName.fromRuntime("/usr/local/bin/probatio")
    assert(result.isRight)
    result match
      case Right(name) => assertEquals(name.value, "/usr/local/bin/probatio")
      case Left(err)   => fail(s"expected Right, got Left($err)")

  test("InvocationName.fromRuntime rejects an empty name"):
    val result: Either[String, InvocationName] = InvocationName.fromRuntime("")
    assert(result.isLeft)

  // ── Requirement: A tool that has no implementation is not nameable on the
  //    tool surface
  //
  // spec: cli-entrypoint-contract — Requirement: A tool that has no implementation is not nameable on the tool surface

  // ── Scenario: Adversarial — an unported tool name is rejected, not silently accepted
  // spec: cli-entrypoint-contract — Scenario: Adversarial — an unported tool name is rejected, not silently accepted
  test("Unported tool 'registry-check' is rejected with UnknownSubcommand"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("registry-check")
    assert(result.isLeft)
    result match
      case Left(CliError.UnknownSubcommand(token)) => assertEquals(token, "registry-check")
      case other                                   => fail(s"expected UnknownSubcommand, got $other")

  // ── Scenario: Happy path — a ported tool is still selectable
  // spec: cli-entrypoint-contract — Scenario: Happy path — a ported tool is still selectable
  test("Ported tool 'ledger' is selectable"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("ledger")
    assertEquals(result, Right(Subcommand.Ledger))

  // ── Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
  // spec: cli-entrypoint-contract — Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
  test("Metals tool is selectable (retained sub-action 'start')"):
    val result: Either[CliError, Subcommand] = Subcommand.fromString("metals")
    assertEquals(result, Right(Subcommand.Metals))

  test("Metals SubAction has only Start (stop and call removed)"):
    val subActions: Set[String] = MetalsCmd.SubAction.values.map(_.toString).toSet
    assertEquals(subActions, Set("Start"))

  // ── Requirement: Every tool is exercised through the built artifact, not
  //    only through in-process calls
  // (Subprocess scenarios are in SubprocessConformanceSpec.scala)

  // ── Properties (Ring 3) ─────────────────────────────────────────────────

  // ── Property: argument-preservation
  // spec: cli-entrypoint-contract — Property: argument-preservation
  property("argument-preservation"):
    for
      nameKind <- Gen.element1("tool-name", "generic", "non-tool").forAll
      toolIdx  <- Gen.int(Range.linear(0, Subcommand.values.length - 1)).forAll
      argList  <- genArgList.forAll
    yield
      val name: InvocationName = nameKind match
        case "tool-name" =>
          val sub: Subcommand = Subcommand.values(toolIdx)
          inv(s"/usr/local/bin/${Subcommand.cliName(sub)}")
        case "generic"  => inv("probatio")
        case "non-tool" => inv("/usr/local/bin/frobnicate")
      val inputArgs: ProgramArgs = ProgramArgs.fromFixture(argList)
      MulticallDispatch.resolveAndSplit(name, inputArgs) match
        case Right((_, rest)) =>
          val expected: ProgramArgs =
            if nameKind == "generic" && inputArgs.headOption.exists(exposedToolNames.contains) then inputArgs.tail
            else inputArgs
          Result.assert(rest.toList == expected.toList)
        case Left(_) => Result.success

  // ── Property: dispatch-equivalence-across-signals
  // spec: cli-entrypoint-contract — Property: dispatch-equivalence-across-signals
  property("dispatch-equivalence-across-signals"):
    for
      sub     <- Gen.element(Subcommand.Gate, Subcommand.values.toList.drop(1)).forAll
      argList <- genArgListNoToolFirst.forAll
    yield
      val cliName: String      = Subcommand.cliName(sub)
      val fixture: ProgramArgs = ProgramArgs.fromFixture(argList)
      // generic-name dispatch: probatio <sub> <args> — consumes the tool token
      val byGeneric: Either[CliError, (Subcommand, ProgramArgs)] =
        MulticallDispatch.resolveAndSplit(inv("probatio"), ProgramArgs.fromFixture(cliName :: argList))
      // symlink dispatch: <sub> <args> — no tool token to consume
      val bySymlink: Either[CliError, (Subcommand, ProgramArgs)] =
        MulticallDispatch.resolveAndSplit(inv(s"/usr/local/bin/$cliName"), fixture)
      Result
        .assert(byGeneric == Right((sub, fixture)))
        .and(Result.assert(bySymlink == Right((sub, fixture))))
        .and(Result.assert(byGeneric == bySymlink))

  // ── Property: no-silent-selection
  // spec: cli-entrypoint-contract — Property: no-silent-selection
  property("no-silent-selection"):
    for
      nameKind  <- Gen.element1("generic", "non-tool").forAll
      tokenKind <- Gen.element1("removed-tool", "mutation", "random").forAll
      randomTok <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
    yield
      val name: InvocationName = nameKind match
        case "generic"  => inv("probatio")
        case "non-tool" => inv("/usr/local/bin/frobnicate")
      val token: String = tokenKind match
        case "removed-tool" => removedToolNames(tokenKind.hashCode().abs % removedToolNames.length)
        case "mutation"     => mutationToolNames(tokenKind.hashCode().abs % mutationToolNames.length)
        case "random"       => randomTok
      // Only test if the token is NOT an exposed tool name (rejection filter)
      if exposedToolNames.contains(token) then Result.success
      else
        val result: Either[CliError, (Subcommand, ProgramArgs)] =
          MulticallDispatch.resolveAndSplit(name, ProgramArgs.fromFixture(List(token)))
        Result.assert(result.isLeft)

  // ── Compile-Negative Obligations ────────────────────────────────────────
  //
  // spec: cli-entrypoint-contract — Compile-Negative: ProgramArgs from a program-name-prefixed list
  // spec: cli-entrypoint-contract — Compile-Negative: Subcommand.RegistryCheck (and Scan, RemovalAudit, ImpactScan, ConceptScanner, Graph)
  // spec: cli-entrypoint-contract — Compile-Negative: MulticallDispatch.resolve(argv0, argv1)

  test("Compile-Negative: ProgramArgs(List(...)) does not compile"):
    val err: String = compileErrors("ProgramArgs(List(\"probatio\", \"gate\", \"--event\", \"x\"))")
    assert(err.nonEmpty, "ProgramArgs(List(...)) should not compile — no public apply")

  test("Compile-Negative: Subcommand.RegistryCheck does not compile"):
    val err: String = compileErrors("Subcommand.RegistryCheck")
    assert(err.nonEmpty, "Subcommand.RegistryCheck should not exist — unported tool removed")

  test("Compile-Negative: Subcommand.Scan does not compile"):
    val err: String = compileErrors("Subcommand.Scan")
    assert(err.nonEmpty, "Subcommand.Scan should not exist — unported tool removed")

  test("Compile-Negative: Subcommand.RemovalAudit does not compile"):
    val err: String = compileErrors("Subcommand.RemovalAudit")
    assert(err.nonEmpty, "Subcommand.RemovalAudit should not exist — unported tool removed")

  test("Compile-Negative: Subcommand.ImpactScan does not compile"):
    val err: String = compileErrors("Subcommand.ImpactScan")
    assert(err.nonEmpty, "Subcommand.ImpactScan should not exist — unported tool removed")

  test("Compile-Negative: Subcommand.ConceptScanner does not compile"):
    val err: String = compileErrors("Subcommand.ConceptScanner")
    assert(err.nonEmpty, "Subcommand.ConceptScanner should not exist — unported tool removed")

  test("Compile-Negative: Subcommand.Graph does not compile"):
    val err: String = compileErrors("Subcommand.Graph")
    assert(err.nonEmpty, "Subcommand.Graph should not exist — unported tool removed")

  test("Compile-Negative: MulticallDispatch.resolve(argv0, argv1) does not compile"):
    val err: String = compileErrors("MulticallDispatch.resolve(\"probatio\", Some(\"gate\"))")
    assert(err.nonEmpty, "MulticallDispatch.resolve(argv0, argv1) should not exist — removed")

  test("Compile-Negative: InvocationName(...) does not compile"):
    val err: String = compileErrors("InvocationName(\"probatio\")")
    assert(err.nonEmpty, "InvocationName(...) should not compile — no public apply")

  // ── DEFECT-1: gate without --event is a Finding, not a silent default
  // spec: cli-entrypoint-contract — Requirement: A missing required flag is a Finding, not a clean run
  test("DEFECT-1: gate without --event exits 1 (Finding), not 0 (silent default)"):
    val outcome: org.sinemenda.probatio.core.Outcome[Int] =
      GateCmd.run(Array("--change", "test-change"))
    outcome match
      case org.sinemenda.probatio.core.Outcome.Finding(msg) =>
        assert(msg.contains("--event"), s"error should mention --event, got: $msg")
      case other =>
        fail(s"gate without --event should be a Finding, got $other")

  // ── DEFECT-2: top-level `probatio --help` shows usage, not UnknownSubcommand
  // spec: cli-entrypoint-contract — Requirement: Top-level --help shows usage
  test("DEFECT-2: top-level 'probatio --help' dispatches to usage, exit 0"):
    val code: Int = ProbatioMain.dispatch(inv("probatio"), args("--help"))
    assertEquals(code, 0)

  // ── DEFECT-3: POSIX `--` separator is consumed before subcommand resolution
  // spec: cli-entrypoint-contract — Requirement: POSIX -- separator is consumed before dispatch
  test("DEFECT-3: 'probatio -- gate --event session-start' resolves to Gate"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(
        inv("probatio"),
        args("--", "gate", "--event", "session-start")
      )
    assertEquals(result, Right((Subcommand.Gate, args("--event", "session-start"))))

  test("DEFECT-3: 'probatio --' with no further args is UnknownSubcommand((none))"):
    val result: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(inv("probatio"), args("--"))
    assertEquals(result, Left(CliError.UnknownSubcommand("(none)")))

  // ── Ring 5 mutation coverage ────────────────────────────────────────────
  // These tests pin observable behaviour that mutants otherwise survive:
  // the subcommand --help path, the absence of help output on a normal run,
  // the InvocationName error message, and the top-level usage listing.
  //
  // spec: cli-entrypoint-contract — Requirement: Top-level --help shows usage
  // spec: cli-protocol — Requirement: Help lists every flag with its default

  /** Runs `dispatch` with stdout redirected, returning (exit code, stdout). */
  private def captureStdout(name: InvocationName, pa: ProgramArgs): (Int, String) =
    val baos: java.io.ByteArrayOutputStream = new java.io.ByteArrayOutputStream()
    val ps: java.io.PrintStream             = new java.io.PrintStream(baos, true, "UTF-8")
    val old: java.io.PrintStream            = System.out
    System.setOut(ps)
    try
      val code: Int = ProbatioMain.dispatch(name, pa)
      ps.flush()
      (code, baos.toString("UTF-8"))
    finally System.setOut(old)

  test("subcommand --help prints that subcommand's help on stdout and exits 0"):
    val (code, out): (Int, String) = captureStdout(inv("probatio"), args("gate", "--help"))
    assertEquals(code, 0)
    assert(out.contains("probatio gate"), s"gate help missing header, got: $out")
    assert(out.contains("--event"), s"gate help missing --event flag, got: $out")

  test("subcommand without --help runs normally — no help on stdout"):
    val (code, out): (Int, String) = captureStdout(inv("probatio"), args("gate"))
    assertEquals(code, 1)
    assert(!out.contains("Options:"), s"help output leaked onto stdout, got: $out")

  test("InvocationName.fromRuntime error message names the violation"):
    InvocationName.fromRuntime("") match
      case Left(msg)   => assert(msg.contains("non-empty"), s"error message must describe the violation, got: '$msg'")
      case Right(name) => fail(s"empty invocation name must be rejected, got $name")

  test("topLevelUsage lists every subcommand name on its own line"):
    val usage: String      = HelpRegistry.topLevelUsage
    val lines: Set[String] = usage.split("\n").map(_.trim).toSet
    assert(usage.contains("Subcommands:"), s"usage missing Subcommands header, got: $usage")
    Subcommand.values.foreach { sub =>
      val name: String = Subcommand.cliName(sub)
      assert(lines.contains(name), s"usage missing subcommand line for '$name', got: $usage")
    }

  test("helpFor render names the subcommand and lists its flags"):
    Subcommand.values.foreach { sub =>
      val rendered: String = HelpRegistry.helpFor(sub).render
      assert(
        rendered.contains(s"probatio ${Subcommand.cliName(sub)}"),
        s"help for ${Subcommand.cliName(sub)} missing header, got: $rendered"
      )
    }

  // ── Generators ──────────────────────────────────────────────────────────

  /**
   * Generates an argument list from a pool of flag tokens, value tokens, and
   * tool-name-shaped value tokens; sizes 0–12.
   */
  private def genArgList: Gen[List[String]] =
    val flagTokens: Gen[String] =
      Gen.element1("--event", "--change", "--baseline", "--format", "--file", "--dir", "--help")
    val valueTokens: Gen[String]    = Gen.element1("session-start", "abc1234", "my-change", "hook-json", "d", "c")
    val toolNameTokens: Gen[String] = Gen.element1("gate", "ledger", "chain-state", "metals", "scan", "registry-check")
    val tokenPool: Gen[String] = Gen.frequency1(
      3 -> flagTokens,
      3 -> valueTokens,
      1 -> toolNameTokens
    )
    for
      size <- Gen.int(Range.linear(0, 12))
      list <- tokenPool.list(Range.singleton(size))
    yield list

  /** Generates an argument list whose first element is NOT a tool name. */
  private def genArgListNoToolFirst: Gen[List[String]] =
    val nonToolFirst: Gen[String] =
      Gen.element1("--event", "--change", "--baseline", "--help", "session-start", "abc1234")
    for
      head <- nonToolFirst
      tail <- genArgList
    yield head :: tail
