package org.sinemenda.probatio.core

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

/**
 * Test oracle for the gate-event-completeness spec — tool-response
 * classification.
 *
 * Scenarios and the classification property are derived from the SPEC
 * (and the predecessor's documented outcome predicate), NOT from the
 * implementation. An observed outcome exists only when the harness
 * actually reported one: a refusal, an interruption, or an
 * unrecognised shape records NOTHING.
 *
 * spec: gate-event-completeness — Requirement: An observed outcome is recorded only when the harness reported one
 * spec: gate-event-completeness — Property: outcome-classification-is-total-and-conservative
 */
final class ToolOutcomeSpec extends ProbatioSuite:

  /** A generator over arbitrary harness-response shapes (depth-bounded). */
  private def genJson(depth: Int): Gen[ujson.Value] =
    if depth <= 0 then
      Gen.choice1(
        Gen.string(Gen.unicodeAll, Range.linear(0, 40)).map(ujson.Str(_)),
        Gen.int(Range.linear(-100, 500)).map((n: Int) => ujson.Num(n.toDouble)),
        Gen.boolean.map(ujson.Bool(_)),
        Gen.constant(ujson.Null)
      )
    else
      Gen.choice1(
        Gen.string(Gen.unicodeAll, Range.linear(0, 40)).map(ujson.Str(_)),
        Gen.int(Range.linear(-100, 500)).map((n: Int) => ujson.Num(n.toDouble)),
        Gen.boolean.map(ujson.Bool(_)),
        Gen.constant(ujson.Null),
        genJson(depth - 1).list(Range.linear(0, 5)).map(ujson.Arr(_*)),
        (for
          k <- Gen.string(Gen.alphaNum, Range.linear(1, 12))
          v <- genJson(depth - 1)
        yield (k, v))
          .list(Range.linear(0, 5))
          .map((kvs: List[(String, ujson.Value)]) => ujson.Obj.from(kvs))
      )

  /**
   * `genHarnessResponse` — the spec's constructive generator: the
   * object-shaped success response (with and without the interruption
   * flag), the error-string shape carrying a generated exit code 0–255,
   * refusal strings drawn from a fixed corpus (plus arbitrary other
   * strings), and unrecognised non-object non-string shapes. The
   * weights keep every cover class comfortably above its spec-pinned
   * threshold.
   */
  private val genHarnessResponse: Gen[ujson.Value] =
    val refusalCorpus: List[String] = List(
      "Error: This command requires approval",
      "Error: Path does not exist: /nonexistent",
      "Error: Command timed out",
      "Interrupted by user",
      "permission denied"
    )
    val genObjectSuccess: Gen[ujson.Value] =
      Gen.choice1(
        Gen.constant(ujson.Obj("output" -> ujson.Str("ok"), "stdout" -> ujson.Str(""))),
        Gen.constant(ujson.Obj("interrupted" -> ujson.Bool(false), "output" -> ujson.Str("ok"))),
        Gen.constant(ujson.Obj())
      )
    val genErrorWithCode: Gen[ujson.Value] =
      Gen.int(Range.linear(0, 255)).map((n: Int) => ujson.Str(s"Error: Exit code $n"))
    val genRefusal: Gen[ujson.Value] =
      Gen.choice1(
        Gen.elementUnsafe(refusalCorpus).map(ujson.Str(_)),
        Gen.string(Gen.alphaNum, Range.linear(0, 40)).map(ujson.Str(_))
      )
    val genInterrupted: Gen[ujson.Value] =
      Gen.constant(ujson.Obj("interrupted" -> ujson.Bool(true), "output" -> ujson.Str("partial")))
    val genUnrecognised: Gen[ujson.Value] =
      Gen.choice1(
        Gen.int(Range.linear(-100, 500)).map((n: Int) => ujson.Num(n.toDouble)),
        Gen.boolean.map(ujson.Bool(_)),
        Gen.constant(ujson.Null),
        genJson(2).list(Range.linear(0, 3)).map(ujson.Arr(_*))
      )
    Gen.frequency1(
      22 -> genObjectSuccess,
      30 -> genErrorWithCode,
      22 -> genRefusal,
      16 -> genInterrupted,
      22 -> genUnrecognised
    )

  /** The cover class of a response shape — the spec's five labels. */
  private def responseClass(v: ujson.Value): String = v match
    case o: ujson.Obj =>
      o.obj.get("interrupted") match
        case Some(ujson.Bool(true)) => "interrupted"
        case _ => "object-success" // danger-scan:allow label predicate — non-interrupted objects are the success class
    case ujson.Str(s) =>
      if s.matches("(?s)Error: Exit code [0-9]+.*") then "error-with-code" else "refusal"
    case _ => "unrecognised" // danger-scan:allow label predicate — non-object non-string shapes are unrecognised

  /**
   * The two shapes the predecessor treats as genuine command outcomes.
   * The digit string must also be Int-representable — an overflow code
   * is unrecordable, so it is a Skip, not a genuine outcome.
   */
  private def isGenuineOutcomeShape(v: ujson.Value): Boolean = v match
    case o: ujson.Obj =>
      o.obj.get("interrupted") match
        case Some(ujson.Bool(true)) => false
        case _ => true // danger-scan:allow test predicate — object-without-interrupted IS the success shape
    case ujson.Str(s) =>
      "Error: Exit code ([0-9]+)".r
        .findFirstMatchIn(s)
        .exists((m: scala.util.matching.Regex.Match) => m.group(1).toIntOption.isDefined) &&
      s.matches("(?s)Error: Exit code [0-9]+.*")
    case _ => false // danger-scan:allow test predicate — other shapes are not genuine outcomes

  // ── Scenario: a successful response records a green row ─────────────
  // spec: gate-event-completeness — Scenario: a successful response records a green row

  test("an object tool_response classifies as exit 0"):
    // The object shape IS the success report — there is no exit field.
    val outcome: ToolOutcome = ToolOutcome.classify(
      ujson.Obj("output" -> ujson.Str("ok"))
    )
    outcome match
      case ToolOutcome.Exit(code) => assertEquals(code, 0)
      case ToolOutcome.Skip(r)    => fail(s"object response should be Exit(0), got Skip($r)")

  test("an object tool_response with interrupted=false classifies as exit 0"):
    val outcome: ToolOutcome = ToolOutcome.classify(
      ujson.Obj("interrupted" -> ujson.Bool(false), "output" -> ujson.Str("ok"))
    )
    outcome match
      case ToolOutcome.Exit(code) => assertEquals(code, 0)
      case ToolOutcome.Skip(r)    => fail(s"non-interrupted object should be Exit(0), got Skip($r)")

  // ── Scenario: a failing response records the reported code ──────────
  // spec: gate-event-completeness — Scenario: a failing response records the reported code

  test("an 'Error: Exit code N' tool_response classifies as Exit(N)"):
    val outcome: ToolOutcome = ToolOutcome.classify(
      ujson.Str("Error: Exit code 1")
    )
    outcome match
      case ToolOutcome.Exit(code) => assertEquals(code, 1)
      case ToolOutcome.Skip(r)    => fail(s"exit-code string should be Exit(1), got Skip($r)")

  test("the reported code is preserved verbatim (multi-digit)"):
    val outcome: ToolOutcome = ToolOutcome.classify(
      ujson.Str("Error: Exit code 127")
    )
    outcome match
      case ToolOutcome.Exit(code) => assertEquals(code, 127)
      case ToolOutcome.Skip(r)    => fail(s"should be Exit(127), got Skip($r)")

  test("an exit code wider than Int records nothing instead of throwing"):
    // Ring 8 adversarial finding: `[0-9]+` accepts ≥11 digits and a bare
    // `.toInt` throws NumberFormatException — crashing the never-blocking
    // post-bash tier. The predecessor carries the digits as a string and
    // the ledger's exit-grammar check rejects the row: observable result
    // is no row + exit 0, which `Skip` reproduces exactly.
    val huge: ToolOutcome = ToolOutcome.classify(
      ujson.Str("Error: Exit code 99999999999999999999")
    )
    huge match
      case ToolOutcome.Skip(_)    => ()
      case ToolOutcome.Exit(code) => fail(s"an unrepresentable code must not become Exit($code)")
    // Boundary sanity: Int.MaxValue still classifies; MaxValue+1 does not.
    ToolOutcome.classify(ujson.Str(s"Error: Exit code ${Int.MaxValue}")) match
      case ToolOutcome.Exit(code) => assertEquals(code, Int.MaxValue)
      case ToolOutcome.Skip(r)    => fail(s"Int.MaxValue should be Exit, got Skip($r)")
    ToolOutcome.classify(ujson.Str(s"Error: Exit code ${Int.MaxValue.toLong + 1L}")) match
      case ToolOutcome.Skip(_)    => ()
      case ToolOutcome.Exit(code) => fail(s"Int.MaxValue+1 must not become Exit($code)")

  // ── Scenario: a harness refusal records nothing ─────────────────────
  // spec: gate-event-completeness — Scenario: a harness refusal records nothing

  test("an 'Error: This command requires approval' response records nothing"):
    // The harness DECLINING to run the command is not a command outcome.
    // Reading "string ⇒ failure" would file a permission denial as a red
    // test run — a fabricated fact.
    val outcome: ToolOutcome = ToolOutcome.classify(
      ujson.Str("Error: This command requires approval")
    )
    outcome match
      case ToolOutcome.Skip(_)    => ()
      case ToolOutcome.Exit(code) => fail(s"a refusal must not become Exit($code)")

  test("an 'Error: Path does not exist' response records nothing"):
    val outcome: ToolOutcome = ToolOutcome.classify(
      ujson.Str("Error: Path does not exist: /nonexistent")
    )
    outcome match
      case ToolOutcome.Skip(_)    => ()
      case ToolOutcome.Exit(code) => fail(s"a harness error must not become Exit($code)")

  // ── Scenario: an interrupted run records nothing ────────────────────
  // spec: gate-event-completeness — Scenario: an interrupted run records nothing

  test("an interrupted tool_response records nothing"):
    val outcome: ToolOutcome = ToolOutcome.classify(
      ujson.Obj("interrupted" -> ujson.Bool(true))
    )
    outcome match
      case ToolOutcome.Skip(_)    => ()
      case ToolOutcome.Exit(code) => fail(s"an interrupted run must not become Exit($code)")

  // ── Scenario: an unrecognised response shape records nothing ────────

  test("a non-object non-string tool_response records nothing"):
    val responses: List[ujson.Value] = List(
      ujson.Arr(ujson.Str("x")),
      ujson.Num(0),
      ujson.Bool(true),
      ujson.Null
    )
    responses.foreach { (r: ujson.Value) =>
      ToolOutcome.classify(r) match
        case ToolOutcome.Skip(_)    => ()
        case ToolOutcome.Exit(code) => fail(s"$r must not become Exit($code)")
    }

  // ── Property: outcome-classification-is-total-and-conservative ──────
  // spec: gate-event-completeness — Property: outcome-classification-is-total-and-conservative

  private def coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(500))

  property("outcome-classification-is-total-and-conservative", coverConfig):
    for response <- genHarnessResponse.forAll
        .cover(15, "object-success", (r: ujson.Value) => responseClass(r) == "object-success")
        .cover(20, "error-with-code", (r: ujson.Value) => responseClass(r) == "error-with-code")
        .cover(15, "refusal", (r: ujson.Value) => responseClass(r) == "refusal")
        .cover(10, "interrupted", (r: ujson.Value) => responseClass(r) == "interrupted")
        .cover(15, "unrecognised", (r: ujson.Value) => responseClass(r) == "unrecognised")
    yield
      // Total: classification returns a ToolOutcome for every shape.
      val outcome: ToolOutcome = ToolOutcome.classify(response)
      outcome match
        case ToolOutcome.Exit(code) =>
          // Conservative: an Exit exists only for the two genuine shapes…
          Result
            .assert(isGenuineOutcomeShape(response))
            // …and the reported code is never fabricated: for the string
            // shape it equals the reported code; for the object shape it
            // is exactly 0 (the shape's own meaning).
            .and(
              response match
                case ujson.Str(s) =>
                  val reported: Int =
                    "Error: Exit code ([0-9]+)".r
                      .findFirstMatchIn(s)
                      .map((m: scala.util.matching.Regex.Match) => m.group(1).toInt)
                      .getOrElse(-1)
                  Result.assert(code == reported)
                case _ => // danger-scan:allow test assertion — object/other shapes report exit 0
                  Result.assert(code == 0)
            )
        case ToolOutcome.Skip(reason) =>
          Result
            .assert(reason.nonEmpty)
            .and(Result.assert(!isGenuineOutcomeShape(response)))

  // ── Compile-Negative: core decision code cannot perform file I/O ────
  // spec: gate-event-completeness — Compile-Negative: file I/O or env reads inside core gate decision functions
  //
  // The gate decision functions live in probatio-core, whose classpath
  // carries fs2-core only — fs2-io is a module-level exclusion, so the
  // file-IO API does not resolve here at all. (Environment reads are JDK
  // and cannot be excluded by classpath; the absence of IO-typed
  // parameters on GateDecisions is pinned by the positive control in
  // GateEventSpec.)

  test("compile-negative: fs2.io.file is not on the core classpath"):
    val err: String = compileErrors("fs2.io.file.Files")
    assert(err.nonEmpty, "fs2-io must not be reachable from probatio-core")
