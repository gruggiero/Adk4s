package org.sinemenda.probatio.cli

import hedgehog.*
import org.sinemenda.probatio.verified.DispatchKernel

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge spec — binds the shipped `MulticallDispatch.resolveAndSplit`
 * to its PureScala mirror `DispatchKernel.resolveAndSplit`.
 *
 * The model reduces the invocation name to `Option[toolIndex]` (`Some(n)`
 * for a tool-name basename, `None` for a generic basename) and the argument
 * list to `List[BigInt]` of token classifications (`0` = not a tool name,
 * `n >= 1` = tool index n, `-1` = the POSIX `--` separator). A basename that
 * is neither a tool name nor generic is OUT OF THE MODEL'S DOMAIN — the
 * shipped code rejects it unconditionally, which this spec asserts directly.
 *
 * The bridge compares the shipped result and the model result after
 * normalising both to `Option[(BigInt, List[BigInt])]`.
 *
 * spec: cli-entrypoint-contract — Formal Contracts (Ring 6)
 * spec: cli-entrypoint-contract — Contract: resolveAndSplit
 */
final class EntrypointBridgeSpec extends ProbatioCliSuite:

  // ── Helpers: shipped types → model domain ────────────────────────────────

  /** Constructs an InvocationName from a raw string (fails the test if empty). */
  private def inv(name: String): InvocationName =
    InvocationName.fromRuntime(InvocationSource.classify(name)) match
      case Right(value) => value
      case Left(err)    => fail(s"invalid invocation name '$name': $err")

  /** Constructs ProgramArgs from a fixture list. */
  private def fixture(xs: String*): ProgramArgs =
    ProgramArgs.fromFixture(xs.toList)

  /** The 1-based tool index used by the model. */
  private def toolIndex(sub: Subcommand): BigInt =
    BigInt(Subcommand.values.indexOf(sub) + 1)

  /**
   * Maps an invocation name into the model domain. Returns `None` when the
   * basename is neither a tool name nor a generic name — the model does not
   * encode that case because the shipped code rejects it unconditionally.
   */
  private def nameToModel(name: InvocationName): Option[Option[BigInt]] =
    val base: String = name.basename
    Subcommand.fromString(base) match
      case Right(sub) => Some(Some(toolIndex(sub)))
      case Left(_) =>
        if MulticallDispatch.genericNames.contains(base) then Some(None)
        else None

  /** Classifies a single argument token for the model. */
  private def tokenToModel(tok: String): BigInt =
    if tok == "--" then DispatchKernel.Sep
    else
      Subcommand.fromString(tok) match
        case Right(sub) => toolIndex(sub)
        case Left(_)    => BigInt(0)

  /** Converts shipped ProgramArgs to the model's token-classification list. */
  private def toScalaTokens(pa: ProgramArgs): ScalaList[BigInt] =
    pa.toList.map(tokenToModel)

  /** Converts a Scala list to a Stainless list. */
  private def toStainless(xs: ScalaList[BigInt]): stainless.collection.List[BigInt] =
    stainless.collection.List.fromScala(xs)

  /** Converts a Scala Option to a Stainless Option. */
  private def toStainlessOpt(o: Option[BigInt]): stainless.lang.Option[BigInt] =
    o match
      case Some(v) => stainless.lang.Some(v)
      case None    => stainless.lang.None()

  /** Converts a Stainless list back to a Scala list for comparison. */
  private def toScalaList(xs: stainless.collection.List[BigInt]): ScalaList[BigInt] =
    xs match
      case stainless.collection.Cons(h, t) => h :: toScalaList(t)
      case stainless.collection.Nil()      => ScalaList.empty

  /** Normalises the shipped result to `Option[(toolIndex, tokenClasses)]`. */
  private def shippedNorm(
    r: Either[CliError, (Subcommand, ProgramArgs)]
  ): Option[(BigInt, ScalaList[BigInt])] =
    r match
      case Right((sub, rest)) => Some((toolIndex(sub), toScalaTokens(rest)))
      case Left(_)            => None

  /** Normalises the model result to `Option[(toolIndex, tokenClasses)]`. */
  private def modelNorm(
    r: stainless.lang.Option[(BigInt, stainless.collection.List[BigInt])]
  ): Option[(BigInt, ScalaList[BigInt])] =
    r match
      case stainless.lang.Some((tool, rest)) => Some((tool, toScalaList(rest)))
      case _                                 => None

  /** Runs shipped and model on the same input and asserts agreement. */
  private def assertAgreement(name: InvocationName, pa: ProgramArgs): Unit =
    val shipped: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(name, pa)
    nameToModel(name) match
      case None =>
        assert(shipped.isLeft, s"out-of-domain basename '${name.basename}' must be rejected, got $shipped")
      case Some(nameTool) =>
        val model: stainless.lang.Option[(BigInt, stainless.collection.List[BigInt])] =
          DispatchKernel.resolveAndSplit(toStainlessOpt(nameTool), toStainless(toScalaTokens(pa)))
        assertEquals(
          shippedNorm(shipped),
          modelNorm(model),
          s"shipped and model disagree on name='${name.basename}' args=${pa.toList}"
        )

  // ── spec: jar-launcher-dispatch — the resolveSource bridge ───────────────
  //
  // `SourceModel` lifts the name reduction: the shipped `InvocationName` now
  // carries an `InvocationSource`, and the model's `resolveSource` covers all
  // four source shapes — tool-named, generic, archive, foreign. A foreign
  // basename is IN the model's domain (rejected by its own name code).

  /**
   * A positive code for a foreign executable name — the model's offender
   * domain distinguishes name codes (>= 1) from token classifications (0,
   * Sep, NoArg). `hashCode` collisions are harmless here: the bridge only
   * ever compares a name against itself.
   */
  private def nameCode(s: String): BigInt =
    BigInt(s.hashCode).abs + 1

  /**
   * Maps the shipped invocation source to the model's `SourceModel`. Total:
   * every source shape is in the model's domain.
   */
  private def sourceToModel(name: InvocationName): DispatchKernel.SourceModel =
    name.source match
      case InvocationSource.Archive(_) => DispatchKernel.ArchiveNamed()
      case InvocationSource.NamedExecutable(base) =>
        Subcommand.fromString(base) match
          case Right(sub) => DispatchKernel.ToolNamed(toolIndex(sub))
          case Left(_)    =>
            if MulticallDispatch.genericNames.contains(base) then DispatchKernel.GenericNamed()
            else DispatchKernel.ForeignNamed(nameCode(base))

  /**
   * Normalises the shipped result to `Either[offenderCode, (tool, tokens)]`.
   * The offender code convention mirrors the model's: the foreign basename's
   * code under a foreign source, `"(none)"` → `NoArg`, otherwise the head
   * token's classification (`0` for a non-tool token, `Sep` for `--`).
   */
  private def shippedSourceNorm(
    r: Either[CliError, (Subcommand, ProgramArgs)],
    isForeign: Boolean
  ): Either[BigInt, (BigInt, ScalaList[BigInt])] =
    r match
      case Right((sub, rest)) => Right((toolIndex(sub), toScalaTokens(rest)))
      case Left(CliError.UnknownSubcommand(tok)) =>
        Left(
          if isForeign then nameCode(tok)
          else if tok == "(none)" then DispatchKernel.NoArg
          else tokenToModel(tok)
        )
      case Left(other) => fail(s"unexpected non-UnknownSubcommand error: $other")

  /** Normalises the model result to the same shape. */
  private def modelSourceNorm(
    r: DispatchKernel.DispatchModel
  ): Either[BigInt, (BigInt, ScalaList[BigInt])] =
    r match
      case DispatchKernel.SelectedTool(tool, rest) => Right((tool, toScalaList(rest)))
      case DispatchKernel.Rejected(offender)       => Left(offender)

  /** Runs shipped and model `resolveSource` on the same input and asserts agreement. */
  private def assertSourceAgreement(name: InvocationName, pa: ProgramArgs): Unit =
    val shipped: Either[CliError, (Subcommand, ProgramArgs)] =
      MulticallDispatch.resolveAndSplit(name, pa)
    val isForeign: Boolean = name.source match
      case InvocationSource.NamedExecutable(base) =>
        Subcommand.fromString(base).isLeft && !MulticallDispatch.genericNames.contains(base)
      case InvocationSource.Archive(_) => false
    val model: DispatchKernel.DispatchModel =
      DispatchKernel.resolveSource(sourceToModel(name), toStainless(toScalaTokens(pa)))
    assertEquals(
      shippedSourceNorm(shipped, isForeign),
      modelSourceNorm(model),
      s"shipped and model disagree on source='${name.source}' args=${pa.toList}"
    )

  // spec: jar-launcher-dispatch — Scenario: Adversarial — the archive's file name is never reported as an unknown subcommand
  test("bridge — archive invocation: <assembly.jar> gate --event session-start"):
    assertSourceAgreement(
      inv("/opt/builds/probatio-cli-assembly-0.1.0-SNAPSHOT.jar"),
      fixture("gate", "--event", "session-start")
    )

  // spec: jar-launcher-dispatch — Scenario: Adversarial — an unknown first argument through the archive is still rejected
  test("bridge — archive invocation with unknown first argument: <assembly.jar> frobnicate"):
    assertSourceAgreement(inv("x.jar"), fixture("frobnicate"))

  // spec: jar-launcher-dispatch — Scenario: Happy path — help runs through the archive
  test("bridge — archive invocation with --help first argument"):
    assertSourceAgreement(inv("probatio-cli-assembly.jar"), fixture("--help"))

  // spec: jar-launcher-dispatch — Scenario: Edge case — an executable with an unrecognised name is still rejected
  test("bridge — foreign executable is rejected by its own name"):
    assertSourceAgreement(inv("/usr/local/bin/frobnicate"), fixture("gate"))

  // spec: jar-launcher-dispatch — Contract: resolveSource (bridge property test)
  property("bridge-source-property — shipped and model agree over all source kinds"):
    for
      nameKind    <- Gen.element1("tool-name", "generic", "archive", "foreign").forAll
      toolIdx     <- Gen.int(Range.linear(0, Subcommand.values.length - 1)).forAll
      foreignName <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).map((s: String) => s"x-$s").forAll
      argList     <- genBridgeArgs.forAll
    yield
      val name: InvocationName = nameKind match
        case "tool-name" =>
          inv(s"/usr/local/bin/${Subcommand.cliName(Subcommand.values(toolIdx))}")
        case "generic" => inv("probatio")
        case "archive" => inv(s"/opt/builds/$foreignName.jar")
        case _         => inv(s"/usr/local/bin/$foreignName")
      val pa: ProgramArgs = ProgramArgs.fromFixture(argList)
      val shipped: Either[CliError, (Subcommand, ProgramArgs)] =
        MulticallDispatch.resolveAndSplit(name, pa)
      val isForeign: Boolean = nameKind == "foreign"
      val model: DispatchKernel.DispatchModel =
        DispatchKernel.resolveSource(sourceToModel(name), toStainless(toScalaTokens(pa)))
      Result
        .assert(shippedSourceNorm(shipped, isForeign) == modelSourceNorm(model))
        .log(s"shipped=${shippedSourceNorm(shipped, isForeign)} model=${modelSourceNorm(model)} kind=$nameKind name='${name.source}' args=$argList")

  // ── Bridge scenarios — spec scenarios through both implementations ───────

  test("bridge — named-tool invocation: probatio gate --event session-start --format hook-json"):
    assertAgreement(inv("probatio"), fixture("gate", "--event", "session-start", "--format", "hook-json"))

  test("bridge — symlink invocation: chain-state --change-dir d --change c --baseline abc1234"):
    assertAgreement(
      inv("/usr/local/bin/chain-state"),
      fixture("--change-dir", "d", "--change", "c", "--baseline", "abc1234")
    )

  test("bridge — error path: probatio frobnicate --x"):
    assertAgreement(inv("probatio"), fixture("frobnicate", "--x"))

  test("bridge — edge case: probatio with no arguments"):
    assertAgreement(inv("probatio"), ProgramArgs.empty)

  test("bridge — adversarial: chain-state --change gate --baseline abc1234 (value spells a tool name)"):
    assertAgreement(
      inv("chain-state"),
      fixture("--change", "gate", "--baseline", "abc1234", "--change-dir", "d")
    )

  test("bridge — DEFECT-3: probatio -- gate --event session-start (separator consumed)"):
    assertAgreement(inv("probatio"), fixture("--", "gate", "--event", "session-start"))

  test("bridge — DEFECT-3: probatio -- with nothing after (lone separator fails)"):
    assertAgreement(inv("probatio"), fixture("--"))

  test("bridge — out of domain: non-tool non-generic basename is always rejected"):
    assertAgreement(inv("/usr/local/bin/frobnicate"), fixture("gate", "--event", "x"))
    assertAgreement(inv("/usr/local/bin/frobnicate"), ProgramArgs.empty)

  // ── Bridge property: shipped and model agree on generated inputs ─────────

  // spec: cli-entrypoint-contract — Contract: resolveAndSplit (bridge property test)
  property("bridge-entrypoint-property — shipped and model agree"):
    for
      nameKind <- Gen.element1("tool-name", "generic", "unknown").forAll
      toolIdx  <- Gen.int(Range.linear(0, Subcommand.values.length - 1)).forAll
      argList  <- genBridgeArgs.forAll
    yield
      val name: InvocationName = nameKind match
        case "tool-name" =>
          inv(s"/usr/local/bin/${Subcommand.cliName(Subcommand.values(toolIdx))}")
        case "generic" => inv("probatio")
        case _         => inv("/usr/local/bin/frobnicate")
      val pa: ProgramArgs = ProgramArgs.fromFixture(argList)
      val shipped: Either[CliError, (Subcommand, ProgramArgs)] =
        MulticallDispatch.resolveAndSplit(name, pa)
      nameToModel(name) match
        case None =>
          Result
            .assert(shipped.isLeft)
            .log(s"out-of-domain basename must be rejected: name='${name.basename}' args=$argList")
        case Some(nameTool) =>
          val model: stainless.lang.Option[(BigInt, stainless.collection.List[BigInt])] =
            DispatchKernel.resolveAndSplit(toStainlessOpt(nameTool), toStainless(toScalaTokens(pa)))
          Result
            .assert(shippedNorm(shipped) == modelNorm(model))
            .log(s"shipped=${shippedNorm(shipped)} model=${modelNorm(model)} name='${name.basename}' args=$argList")

  // ── Generators ──────────────────────────────────────────────────────────

  /**
   * Argument lists mixing flags, values, tool-name-shaped tokens, and the
   * `--` separator; sizes 0–10.
   */
  private def genBridgeArgs: Gen[ScalaList[String]] =
    val flagTokens: Gen[String]  = Gen.element1("--event", "--change", "--baseline", "--format", "--help")
    val valueTokens: Gen[String] = Gen.element1("session-start", "abc1234", "my-change", "frobnicate", "d")
    val toolTokens: Gen[String]  = Gen.element1("gate", "ledger", "chain-state", "metals", "registry-check")
    val sepToken: Gen[String]    = Gen.constant("--")
    val tokenPool: Gen[String] = Gen.frequency1(
      3 -> flagTokens,
      3 -> valueTokens,
      2 -> toolTokens,
      1 -> sepToken
    )
    for
      size <- Gen.int(Range.linear(0, 10))
      list <- tokenPool.list(Range.singleton(size))
    yield list
