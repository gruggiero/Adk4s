package org.sinemenda.probatio.core

/**
 * Test oracle for the 15-variant ContractViolation sealed trait.
 *
 * spec: port-scanner-to-probatio/provenance-validation — Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause
 * spec: port-scanner-to-probatio/provenance-validation — Compile-Negative: ContractViolation with a 16th variant
 * spec: port-scanner-to-probatio/provenance-validation — Compile-Negative: session field on LedgerRecord (the core type)
 */
final class ContractViolation15Spec extends ProbatioSuite:

  // ── Scenario: all 15 variants are constructible
  // spec: provenance-validation — Scenario: all 15 variants are constructible
  test("all 15 ContractViolation variants are constructible with clause indices 0-14"):
    val v0: ContractViolation  = ContractViolation.NotAJsonObject()
    val v1: ContractViolation  = ContractViolation.MissingRequiredFields(List("x"))
    val v2: ContractViolation  = ContractViolation.VersionInvalid()
    val v3: ContractViolation  = ContractViolation.TimestampInvalid()
    val v4: ContractViolation  = ContractViolation.ChangeInvalid()
    val v5: ContractViolation  = ContractViolation.SpecInvalid()
    val v6: ContractViolation  = ContractViolation.RingOutsideDomain()
    val v7: ContractViolation  = ContractViolation.ObligationEmpty()
    val v8: ContractViolation  = ContractViolation.ArtifactEmpty()
    val v9: ContractViolation  = ContractViolation.CommandEmpty()
    val v10: ContractViolation = ContractViolation.ExitNotInteger()
    val v11: ContractViolation = ContractViolation.BaselineInvalid()
    val v12: ContractViolation = ContractViolation.OptionalFieldTypeInvalid()
    val v13: ContractViolation = ContractViolation.ObserverProvenanceInvalid()
    val v14: ContractViolation = ContractViolation.SessionProvenanceInvalid()
    assertEquals(v0.clauseIndex, 0)
    assertEquals(v1.clauseIndex, 1)
    assertEquals(v2.clauseIndex, 2)
    assertEquals(v3.clauseIndex, 3)
    assertEquals(v4.clauseIndex, 4)
    assertEquals(v5.clauseIndex, 5)
    assertEquals(v6.clauseIndex, 6)
    assertEquals(v7.clauseIndex, 7)
    assertEquals(v8.clauseIndex, 8)
    assertEquals(v9.clauseIndex, 9)
    assertEquals(v10.clauseIndex, 10)
    assertEquals(v11.clauseIndex, 11)
    assertEquals(v12.clauseIndex, 12)
    assertEquals(v13.clauseIndex, 13)
    assertEquals(v14.clauseIndex, 14)

  // ── Scenario: a 16th variant is unconstructible (adversarial)
  // spec: provenance-validation — Scenario: a 16th variant is unconstructible (adversarial)
  test("ContractViolation is sealed — no 16th variant"):
    val err: String = compileErrors(
      "val v: ContractViolation = new ContractViolation { def clauseIndex = 99; def description = \"x\" }"
    )
    assert(err.nonEmpty, "ContractViolation should be sealed — no new variants")

  // ── Compile-Negative: session field on LedgerRecord core type
  // spec: provenance-validation — Compile-Negative: session field on LedgerRecord (the core type)
  test("LedgerRecord does not have a session field — compile-negative"):
    val err: String = compileErrors(
      "val r: LedgerRecord = LedgerRecord(v = 1, ts = \"x\", change = \"c\", spec = \"s\", " +
        "ring = Ring.R3, obligation = \"o\", artifact = \"a\", command = \"cmd\", exit = 0, " +
        "baseline = \"abc1234\", session = \"x\")"
    )
    assert(err.nonEmpty, "LedgerRecord should not have a session field — it lives on ProvenanceFields")
