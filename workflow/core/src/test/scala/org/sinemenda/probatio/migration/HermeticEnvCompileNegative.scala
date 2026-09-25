package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Compile-negative oracle for spec: hermetic-test-processes.
 *
 * Asserts the type-level guarantees that make wholesale environment
 * inheritance unrepresentable in test code: the constructor is private, and
 * no factory builds a HermeticEnv straight from the invoking environment.
 *
 * spec: hermetic-test-processes — Compile-Negative Obligations
 */
final class HermeticEnvCompileNegative extends ProbatioSuite:

  // ── Compile-Negative: a hermetic environment built outside the shared helper
  // spec: hermetic-test-processes — Compile-Negative: A hermetic environment built outside the shared helper
  test("HermeticEnv cannot be directly constructed"):
    val err: String = compileErrors("new HermeticEnv(sys.env)") // scalafix:ok DisableSyntax.NoSysEnv
    // A generic "did not compile" passes on an unrelated error; assert the
    // rejection is the constructor's privacy, not a typo.
    assert(
      err.contains("private") || err.contains("cannot be accessed"),
      s"new HermeticEnv(...) must fail because the constructor is private, not incidentally:\n$err"
    )

  // ── Compile-Negative: a hermetic environment built from the inherited environment
  // spec: hermetic-test-processes — Compile-Negative: A hermetic environment built from the inherited environment
  test("no factory inherits the invoking environment wholesale"):
    val err: String = compileErrors("HermeticEnv.inherit()")
    assert(
      err.contains("inherit") && (err.contains("not a member") || err.contains("Not found")),
      s"HermeticEnv.inherit() must fail because no such factory exists, not incidentally:\n$err"
    )
