package org.adk4s.structured

import munit.FunSuite
import scala.io.Source

class SmithyValidationTest extends FunSuite:

  test("smithy file exists in test resources") {
    val smithyFile = new java.io.File("structured-llm-test-models/src/main/smithy/resume.smithy")
    assert(smithyFile.exists(), "resume.smithy should exist in test-models/smithy directory")
    assert(smithyFile.isFile(), "resume.smithy should be a file")
  }

  test("smithy file contains required structures") {
    val smithyFile = new java.io.File("structured-llm-test-models/src/main/smithy/resume.smithy")
    val content    = Source.fromFile(smithyFile).mkString

    // Check for required structures
    assert(content.contains("structure Resume"), "Should contain Resume structure")
    assert(content.contains("structure Education"), "Should contain Education structure")
    assert(content.contains("enum SeniorityLevel"), "Should contain SeniorityLevel enum")

    // Check for required fields
    assert(content.contains("@required"), "Should have required fields marked")
    assert(content.contains("name: String"), "Should have name field")
    assert(content.contains("email: String"), "Should have email field")
    assert(content.contains("skills: StringList"), "Should have skills field")
    assert(content.contains("seniority: SeniorityLevel"), "Should have seniority field")
  }
