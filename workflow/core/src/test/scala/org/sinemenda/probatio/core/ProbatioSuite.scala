package org.sinemenda.probatio.core

import hedgehog.core.PropertyConfig
import hedgehog.core.Seed
import hedgehog.core.Status
import hedgehog.{runner => hr}
import munit.FunSuite
import munit.Location

/**
 * Base class for probatio test suites.
 *
 * Extends `FunSuite` directly (NOT `HedgehogSuite`) so that `assertEquals`,
 * `assert`, and `fail` throw exceptions on failure — which Stryker4s can
 * detect. `HedgehogSuite` overrides these to return `Result`, which is
 * silently discarded and invisible to mutation testing.
 *
 * The `property` method is replicated from `HedgehogSuite` so that
 * property-based tests still work.
 */
abstract class ProbatioSuite extends FunSuite:

  private val seedSource: hr.SeedSource = hr.SeedSource.fromEnvOrTime()
  private val seed: Seed = Seed.fromLong(seedSource.seed)

  /** Runs a hedgehog property-based test. */
  def property(
      name: String,
      withConfig: PropertyConfig => PropertyConfig = identity
  )(
      prop: => hedgehog.Property
  )(implicit loc: Location): Unit =
    val t: hr.Test = hedgehog.runner.property(name, prop).config(withConfig)
    test(name)(check(t, t.withConfig(PropertyConfig.default)))

  private def check(test: hr.Test, config: PropertyConfig)(implicit
      loc: Location
  ): Any =
    val report = hedgehog.Property.check(test.withConfig(config), test.result, seed)
    if report.status != Status.ok then
      val reason: String = hr.Test.renderReport(
        this.getClass.getName,
        test,
        report,
        ansiCodesSupported = true
      )
      fail(s"$reason\n${seedSource.renderLog}")
