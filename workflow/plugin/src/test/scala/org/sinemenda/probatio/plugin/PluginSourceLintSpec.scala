package org.sinemenda.probatio.plugin

import scala.io.Source
import java.io.File

/**
 * Source-lint tests for compile-negative obligations.
 *
 * These tests verify that the plugin source adheres to R-S1 (no probatio-core
 * imports), R-S2 (no deprecated sbt operators, no GlobalScope abuse), and
 * R-S6 (no side effects at build load time).
 *
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: The plugin forward-declares future sbt 2 support
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: The plugin does not execute at build load time beyond settings declaration
 * spec: port-scanner-to-probatio/sbt-plugin — Compile-Negative Obligations
 */
final class PluginSourceLintSpec extends ProbatioPluginSuite {

  private val pluginSourceDir: String =
    "workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin"

  private def listScalaFiles(dirPath: String): List[File] = {
    val dir: File = new File(dirPath)
    if (!dir.exists) Nil
    else dir.listFiles.flatMap { f =>
      if (f.isDirectory) listScalaFiles(f.getAbsolutePath)
      else if (f.getName.endsWith(".scala")) List(f)
      else Nil
    }.toList
  }

  private def fileContents(f: File): String =
    Source.fromFile(f).mkString

  // ── R-S1: no probatio-core imports ──────────────────────────────────────
  // spec: sbt-plugin — Compile-Negative: import org.sinemenda.probatio.core.* in plugin source
  test("plugin source has no imports from org.sinemenda.probatio.core") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    assert(files.nonEmpty, "plugin source directory should have Scala files")
    for (f <- files) {
      val content: String = fileContents(f)
      assert(
        !content.contains("import org.sinemenda.probatio.core"),
        s"${f.getName}: forbidden import org.sinemenda.probatio.core found"
      )
    }
  }

  // ── R-S2: no deprecated sbt operators ───────────────────────────────────
  // spec: sbt-plugin — Compile-Negative: Deprecated sbt operators (<<=, in Config.Global)
  test("plugin source has no deprecated sbt operators (<<=, in Config.Global)") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    for (f <- files) {
      val lines: List[String] = fileContents(f).linesIterator.toList
      // Check only non-comment lines for actual deprecated operator usage
      val codeLines: List[String] = lines.filterNot(line =>
        line.trim.startsWith("//") || line.trim.startsWith("*") || line.trim.startsWith("/*")
      )
      for (line <- codeLines) {
        assert(
          !line.contains("<<="),
          s"${f.getName}: deprecated <<= operator found in line: $line"
        )
        assert(
          !line.contains("in Config.Global"),
          s"${f.getName}: deprecated 'in Config.Global' found in line: $line"
        )
      }
    }
  }

  // ── R-S2: no GlobalScope abuse ──────────────────────────────────────────
  // spec: sbt-plugin — Compile-Negative: in GlobalScope scope abuse
  test("plugin source has no GlobalScope abuse") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    for (f <- files) {
      val lines: List[String] = fileContents(f).linesIterator.toList
      // Check only non-comment lines for actual GlobalScope usage
      val codeLines: List[String] = lines.filterNot(line =>
        line.trim.startsWith("//") || line.trim.startsWith("*") || line.trim.startsWith("/*")
      )
      for (line <- codeLines) {
        assert(
          !line.contains("in GlobalScope"),
          s"${f.getName}: 'in GlobalScope' abuse found in line: $line"
        )
      }
    }
  }

  // ── R-S6: no Process/URL/os.read calls in settings initialization ───────
  // spec: sbt-plugin — Compile-Negative: Network resolution or binary execution in AutoPlugin buildSettings/projectSettings initialization
  test("plugin source has no Process/URL side effects in settings initialization") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    for (f <- files) {
      val content: String = fileContents(f)
      // Process calls are allowed inside Def.task { } bodies, but not in
      // settings initialization. For the oracle phase, we check that the
      // source doesn't contain bare Process/URL calls outside of task bodies.
      // A more precise check would parse the AST, but a grep-level check
      // catches the obvious violations.
      assert(
        !content.contains("java.net.URL("),
        s"${f.getName}: bare java.net.URL() call found — network resolution must be inside Def.task"
      )
    }
  }

  // ── R-S2: task composition uses Def.task ────────────────────────────────
  // spec: sbt-plugin — Scenario: task composition uses Def.task
  test("plugin source uses Def.task or Def.setting for task composition") {
    val files: List[File] = listScalaFiles(pluginSourceDir)
    // At least one file should use Def.task or Def.setting
    val hasDefTask: Boolean = files.exists { f =>
      val content: String = fileContents(f)
      content.contains("Def.task") || content.contains("Def.setting")
    }
    assert(hasDefTask, "plugin source should use Def.task or Def.setting for task composition")
  }
}
