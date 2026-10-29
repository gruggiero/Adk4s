package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Compile-negative oracle for spec: differential-harness-integrity.
 *
 * Asserts the type-level guarantees that make the defect class
 * unrepresentable: a comparison side cannot be built without
 * materialisation, a divergence verdict cannot be built from paths alone,
 * and a seam cannot be named by a free string.
 *
 * spec: differential-harness-integrity — Compile-Negative Obligations
 */
final class DifferentialHarnessCompileNegative extends ProbatioSuite:

  // ── Compile-Negative: a comparison side constructed without materialisation
  // spec: differential-harness-integrity — Compile-Negative: A comparison side constructed without materialisation
  test("ArmTree cannot be directly constructed"):
    val err: String = compileErrors("ArmTree(Nil)")
    assert(err.nonEmpty, "ArmTree(...) should not compile — constructor is private, materialise is the only path")

  // ── Compile-Negative: a divergence verdict constructed from paths alone
  // spec: differential-harness-integrity — Compile-Negative: A divergence verdict constructed from paths alone
  test("ArmDivergence.Identical cannot be built from path strings"):
    val err: String = compileErrors("ArmDivergence.Identical(List(\"a.sh\"))")
    assert(err.nonEmpty, "Identical(List[String]) should not compile — the variant carries SeamResolution")

  // ── Compile-Negative: a seam identifier written as a free string
  // spec: differential-harness-integrity — Compile-Negative: A seam identifier written as a free string
  test("SeamConfiguration cannot be built from a free string"):
    val errCtor: String = compileErrors("SeamTypes.SeamConfiguration(Set(\"ledger\"))")
    assert(errCtor.nonEmpty, "SeamConfiguration(Set[String]) should not compile — private ctor, Set[ToolId] field")
    val errFactory: String = compileErrors("SeamTypes.SeamConfiguration.fromPorted(Set(\"ledger\"))")
    assert(errFactory.nonEmpty, "fromPorted(Set[String]) should not compile — the field is typed by the seam enum")

  // ── Compile-Negative: a seam named outside the enum
  // spec: differential-harness-integrity — Requirement: Every swapped seam is represented in the comparison
  test("no ToolId variant exists outside the seven declared seams"):
    val err: String = compileErrors("SeamTypes.ToolId.InstallSkills")
    assert(err.nonEmpty, "ToolId.InstallSkills should not exist — install-skills is not a swapped seam")
