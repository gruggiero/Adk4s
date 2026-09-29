package org.sinemenda.probatio.core

import hedgehog.*
import hedgehog.Gen
import hedgehog.Range
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.migration.ControlledVariable
import org.sinemenda.probatio.migration.HermeticEnv
import org.sinemenda.probatio.migration.HermeticEnvGens

/**
 * Test oracle for the gate-event-completeness spec — session identity.
 *
 * Session state files are keyed by a filename-safe encoding of the raw
 * identity. Two sessions must NEVER share suppression state: the encoding
 * is injective, so distinct raw identities always yield distinct file
 * names.
 *
 * spec: gate-event-completeness — Requirement: Two distinct sessions never share suppression state
 * spec: gate-event-completeness — Property: session-identity-encoding-is-injective
 */
final class SessionIdSpec extends ProbatioSuite:

  /**
   * The spec's deliberately filename-hostile alphabet — slash, backslash,
   * question mark, exclamation mark, space, colon, the base64 specials,
   * and non-ASCII. Unsafe for encoding purposes = not ASCII
   * alphanumeric.
   */
  private val genUnsafeChar: Gen[Char] =
    Gen.element1('/', '\\', '?', '!', ' ', ':', '+', '=', '-', '_', '.', 'é', '日', 'ß')

  private def isUnsafe(c: Char): Boolean = !(c.isLetterOrDigit && c.toInt < 128)

  /** `genSessionIdentity` — lengths 1–40 over the unsafe-heavy alphabet. */
  private val genRaw: Gen[String] =
    Gen.string(Gen.frequency1(6 -> genUnsafeChar, 4 -> Gen.alphaNum), Range.linear(1, 40))

  /**
   * A pair that differs ONLY at positions where both characters are
   * unsafe — emitted directly, per the spec's generator strategy. Each
   * position is either identical (any alphabet char) or an unsafe/unsafe
   * mismatch; at least one position differs by construction.
   */
  private val genUnsafeOnlyPair: Gen[(String, String)] =
    val genPosition: Gen[(Char, Char)] =
      Gen.frequency1(
        6 -> Gen.frequency1(5 -> genUnsafeChar, 5 -> Gen.alphaNum).map((c: Char) => (c, c)),
        4 -> (for
          a <- genUnsafeChar
          b <- genUnsafeChar.filter((c: Char) => c != a)
        yield (a, b))
      )
    for
      len   <- Gen.int(Range.linear(1, 40))
      elems <- genPosition.list(Range.singleton(len))
      force <- Gen.int(Range.linear(0, len - 1))
      // Guarantee at least one differing position.
      ensured: List[(Char, Char)] =
        if elems.exists((p: (Char, Char)) => p._1 != p._2) then elems
        else elems.updated(force, if elems(force)._1 == '+' then ('/', '+') else ('+', '/'))
    yield (ensured.map(_._1).mkString, ensured.map(_._2).mkString)

  /** `a != b` and every differing position is unsafe on both sides. */
  private def differsOnlyInUnsafe(a: String, b: String): Boolean =
    a != b && a.length == b.length &&
      a.zip(b).forall((p: (Char, Char)) => p._1 == p._2 || (isUnsafe(p._1) && isUnsafe(p._2)))

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  // ── Scenario: the same identity yields the same file ────────────────
  // spec: gate-event-completeness — Scenario: the same identity yields the same file

  test("the same raw identity yields the same filename encoding"):
    val a: SessionId = SessionId.fromRaw("session-123")
    val b: SessionId = SessionId.fromRaw("session-123")
    assertEquals(a.encoded, b.encoded)

  // ── Scenario: unsafe-character identities map to distinct files ─────
  // spec: gate-event-completeness — Scenario: identities differing only in unsafe characters get different files

  test("identities differing only in base64-unsafe characters get different files"):
    // `+`, `/`, `=` are exactly the characters the substitution maps —
    // a non-injective encoding would collide these against identities
    // already containing `-`, `_`, `.`.
    val plusSlashEq: SessionId = SessionId.fromRaw("a+b/c=")
    val minusUndDot: SessionId = SessionId.fromRaw("a-b_c.")
    assertNotEquals(plusSlashEq.encoded, minusUndDot.encoded)
    // Neither encoding may carry a filesystem-hostile character.
    List(plusSlashEq.encoded, minusUndDot.encoded).foreach { (enc: String) =>
      assert(!enc.contains('/'), s"encoding contains '/': $enc")
      assert(!enc.contains('+'), s"encoding contains '+': $enc")
    }

  // ── Scenario: an identity is resolved from the strongest signal ─────
  // spec: gate-event-completeness — Scenario: an identity is resolved from the strongest available signal

  test("resolve prefers the explicit --session flag over every env signal"):
    val s: SessionId = SessionId.resolve(
      explicit = Some("flag-sess"),
      harness = Some("claude-sess"),
      genericOverride = Some("legacy-sess"),
      parentPid = 42
    )
    assertEquals(s.raw, "flag-sess")

  test("resolve prefers CLAUDE_CODE_SESSION_ID over the legacy alias"):
    val s: SessionId = SessionId.resolve(
      explicit = None,
      harness = Some("claude-sess"),
      genericOverride = Some("legacy-sess"),
      parentPid = 42
    )
    assertEquals(s.raw, "claude-sess")

  test("resolve falls back to the legacy alias, then ppid-<pid>"):
    val legacy: SessionId = SessionId.resolve(
      explicit = None,
      harness = None,
      genericOverride = Some("legacy-sess"),
      parentPid = 42
    )
    assertEquals(legacy.raw, "legacy-sess")
    val ppid: SessionId = SessionId.resolve(
      explicit = None,
      harness = None,
      genericOverride = None,
      parentPid = 42
    )
    assertEquals(ppid.raw, "ppid-42")

  test("empty-string signals are treated as absent"):
    val s: SessionId = SessionId.resolve(
      explicit = Some(""),
      harness = Some(""),
      genericOverride = None,
      parentPid = 7
    )
    assertEquals(s.raw, "ppid-7")

  // ── Property: session-identity-encoding-is-injective ────────────────
  // spec: gate-event-completeness — Property: session-identity-encoding-is-injective

  property("session-identity-encoding-is-injective", coverConfig):
    for pair <- Gen
        .frequency1(
          2 -> (for
            a <- genRaw
            b <- genRaw
          yield (a, b)),
          3 -> genUnsafeOnlyPair
        )
        .forAll
        .cover(
          60,
          "contains-unsafe-char",
          (p: (String, String)) => p._1.exists(isUnsafe) || p._2.exists(isUnsafe)
        )
        .cover(20, "differs-only-in-unsafe-char", (p: (String, String)) => differsOnlyInUnsafe(p._1, p._2))
    yield
      val rawA: String = pair._1
      val rawB: String = pair._2
      val encA: String = SessionId.fromRaw(rawA).encoded
      val encB: String = SessionId.fromRaw(rawB).encoded
      // Injective in BOTH directions: same raw ⇒ same file; distinct raw ⇒
      // distinct files. The second direction is what keeps two sessions'
      // suppression state disjoint.
      if rawA == rawB then Result.diff(encA, encB)(_ == _)
      else Result.diff(encA, encB)(_ != _)

  // ── spec: hermetic-test-processes ─────────────────────────────────
  // The scenario and property for the declared-session requirement and
  // the hermetic-env invariant; the generators live in `HermeticEnvGens`
  // (shared with `DifferentialHarnessSpec`).

  // spec: hermetic-test-processes — Scenario: Happy path — a declared session reaches the tool
  test("a declared session reaches the tool"):
    val env: HermeticEnv = HermeticEnv.build(
      Map(ControlledVariable.VerifiedScala3SessionId -> "parity-session"),
      Map("PATH" -> "/usr/bin", "HOME" -> "/tmp", "TMPDIR" -> "/tmp")
    )
    assert(env.has(ControlledVariable.VerifiedScala3SessionId))
    assertEquals(env.value(ControlledVariable.VerifiedScala3SessionId), Some("parity-session"))
    // The process boundary: the child's actual environment, read back
    // through `env`.
    val childEnv: Map[String, String] = HermeticEnv.probeChild(env)
    assertEquals(childEnv.get("VERIFIED_SCALA3_SESSION_ID"), Some("parity-session"))

  // spec: hermetic-test-processes — Property: hermetic-env-contains-only-declared-controls
  property("hermetic-env-contains-only-declared-controls"):
    for {
      declared <- HermeticEnvGens.genDeclared.forAll
      inherited <- HermeticEnvGens.genInvokingEnvironment.forAll
        .cover(
          15,
          "declared-and-inherited",
          (i: Map[String, String]) =>
            declared.keySet.exists((v: ControlledVariable) => i.contains(v.envName))
        )
        .cover(
          15,
          "inherited-only-controlled",
          (i: Map[String, String]) =>
            i.keySet.exists((k: String) =>
              HermeticEnvGens.allControlled.exists((v: ControlledVariable) => v.envName == k) &&
                !declared.keySet.exists((v: ControlledVariable) => v.envName == k)
            )
        )
    } yield
      val env: HermeticEnv = HermeticEnv.build(declared, inherited)
      // The spec invariant: a controlled variable reaches the child iff
      // the test declared it — plus the same requirement's second half,
      // that nothing outside base+declared survives.
      Result
        .assert(
          HermeticEnvGens.allControlled.forall((v: ControlledVariable) =>
            env.has(v) == declared.contains(v)
          )
        )
        .and(Result.assert(!env.toMap.contains("UNCONTROLLED_NOISE")))
        .and(
          Result.assert(
            env.toMap.keySet.subsetOf(
              HermeticEnv.baseNames ++ declared.keySet.map((v: ControlledVariable) => v.envName)
            )
          )
        )
