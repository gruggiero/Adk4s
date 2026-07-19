//> using scala 3.5.2
//> using dep "com.lihaoyi::os-lib:0.11.3"
//> using options -Wunused:all, -deprecation

package scanner

import scala.util.matching.Regex

// ═══════════════════════════════════════════════════════════════════════════
//  Concept Scanner for Scala 3 Projects
//
//  Scans a Scala 3 source tree and extracts domain concepts:
//  - Refined/opaque types (Iron-style constraints or plain opaque types)
//  - Sealed traits and enums (with variants)
//  - Case classes (domain value objects)
//  - Service traits (tagless final, F[_])
//  - Smithy models (from .smithy files)
//  - Property generators (Gen[_], Arbitrary[_] — ScalaCheck or Hedgehog)
//
//  Output: Markdown tables matching the concept-inventory.md template.
//
//  Usage:
//    scala-cli run concept-scanner.scala -- /path/to/project
//    scala-cli run concept-scanner.scala -- /path/to/project --json
// ═══════════════════════════════════════════════════════════════════════════

// ---------------------------------------------------------------------------
//  Concept types
// ---------------------------------------------------------------------------

case class OpaqueType(
    name: String,
    underlying: String,
    constraint: String,
    pkg: String,
    file: String
)

case class SealedType(
    name: String,
    kind: String, // "sealed trait", "enum", "sealed abstract class"
    variants: List[String],
    pkg: String,
    file: String
)

case class CaseClassDef(
    name: String,
    fields: String,
    pkg: String,
    file: String
)

case class ServiceTrait(
    name: String,
    typeParam: String,
    methods: List[String],
    pkg: String,
    file: String
)

case class SmithyModel(
    name: String,
    kind: String, // "service", "structure", "operation"
    members: String,
    file: String
)

case class GeneratorDef(
    name: String,
    generates: String,
    file: String
)

case class ScanResult(
    opaqueTypes: List[OpaqueType],
    sealedTypes: List[SealedType],
    caseClasses: List[CaseClassDef],
    serviceTraits: List[ServiceTrait],
    smithyModels: List[SmithyModel],
    generators: List[GeneratorDef]
)

// ---------------------------------------------------------------------------
//  Scanner implementation
// ---------------------------------------------------------------------------

object ConceptScanner:

  def scan(projectDir: os.Path): ScanResult =
    // MULTI-MODULE: discover every `src/` root in the repo (top-level or
    // per-module, e.g. adk4s-core/src/...), not just <projectDir>/src.
    // A scanner that silently finds 0 concepts in a multi-module build
    // looks like an empty project — that failure mode is why this walks.
    val srcRoots = findSrcRoots(projectDir)
    val mainScala = srcRoots.flatMap(s => findScalaFiles(s / "main" / "scala"))
    val testScala = srcRoots.flatMap(s => findScalaFiles(s / "test" / "scala"))
    val smithyFiles = srcRoots
      .flatMap(s => List(s / "main" / "smithy", s / "main" / "resources"))
      .filter(os.exists)
      .flatMap(d => os.walk(d).filter(_.ext == "smithy").toList)

    ScanResult(
      opaqueTypes = mainScala.flatMap(scanOpaqueTypes),
      sealedTypes = mainScala.flatMap(scanSealedTypes),
      caseClasses = mainScala.flatMap(scanCaseClasses),
      serviceTraits = mainScala.flatMap(scanServiceTraits),
      smithyModels = smithyFiles.flatMap(scanSmithyModels),
      generators = testScala.flatMap(scanGenerators)
    )

  private val skipDirNames: Set[String] =
    Set("target", "project", "openspec", "node_modules")

  private def findSrcRoots(projectDir: os.Path): List[os.Path] =
    os.walk(
      projectDir,
      skip = p => p.last.startsWith(".") || skipDirNames.contains(p.last)
    ).filter(p => os.isDir(p) && p.last == "src").toList.sortBy(_.toString)

  private def findScalaFiles(dir: os.Path): List[os.Path] =
    if os.exists(dir) then
      os.walk(dir).filter(_.ext == "scala").toList
    else Nil

  // ── Opaque types ───────────────────────────────────────────────────────

  private val opaqueTypeRegex: Regex =
    """opaque\s+type\s+(\w+)\s*=\s*(\w+)\s*:\|\s*(.+)""".r

  private val opaqueTypeSimpleRegex: Regex =
    """opaque\s+type\s+(\w+)\s*=\s*(\w+)""".r

  def scanOpaqueTypes(file: os.Path): List[OpaqueType] =
    val content = os.read(file)
    val pkg = extractPackage(content)

    val refined = opaqueTypeRegex.findAllMatchIn(content).map { m =>
      OpaqueType(
        name = m.group(1).trim,
        underlying = m.group(2).trim,
        constraint = m.group(3).trim,
        pkg = pkg,
        file = file.last
      )
    }.toList

    // Also catch opaque types without Iron constraint
    val simple = opaqueTypeSimpleRegex.findAllMatchIn(content)
      .map(m => m.group(1).trim)
      .filterNot(name => refined.exists(_.name == name))
      .map { name =>
        val m = opaqueTypeSimpleRegex.findFirstMatchIn(
          content.linesIterator.find(_.contains(s"opaque type $name")).getOrElse("")
        ).get
        OpaqueType(
          name = m.group(1).trim,
          underlying = m.group(2).trim,
          constraint = "(none)",
          pkg = pkg,
          file = file.last
        )
      }.toList

    refined ++ simple

  // ── Sealed traits and enums ────────────────────────────────────────────

  private val sealedTraitRegex: Regex =
    """sealed\s+trait\s+(\w+)""".r

  private val sealedAbstractClassRegex: Regex =
    """sealed\s+abstract\s+class\s+(\w+)""".r

  private val enumRegex: Regex =
    """enum\s+(\w+)""".r

  private val enumCaseRegex: Regex =
    """case\s+(\w+)""".r

  def scanSealedTypes(file: os.Path): List[SealedType] =
    val content = os.read(file)
    val lines = content.linesIterator.toVector
    val pkg = extractPackage(content)

    val sealedTraits = sealedTraitRegex.findAllMatchIn(content).map { m =>
      SealedType(m.group(1), "sealed trait", Nil, pkg, file.last)
    }.toList

    val sealedClasses = sealedAbstractClassRegex.findAllMatchIn(content).map { m =>
      SealedType(m.group(1), "sealed abstract class", Nil, pkg, file.last)
    }.toList

    val enums = enumRegex.findAllMatchIn(content).map { m =>
      val enumName = m.group(1)
      // Extract variants: find lines after "enum X:" that start with "case"
      val startIdx = lines.indexWhere(_.contains(s"enum $enumName"))
      val variants = if startIdx >= 0 then
        lines.drop(startIdx + 1)
          .takeWhile(l => l.trim.startsWith("case ") || l.trim.isEmpty || l.trim.startsWith("//"))
          .filter(_.trim.startsWith("case "))
          .flatMap { l =>
            val trimmed = l.trim.stripPrefix("case ").trim
            val name = trimmed.takeWhile(c => c.isLetterOrDigit || c == '_')
            // Filter out "case class" — those are case class definitions, not enum variants
            if trimmed.startsWith("class ") then None
            else Some(name)
          }
          .toList
      else Nil
      SealedType(enumName, "enum", variants, pkg, file.last)
    }.toList

    sealedTraits ++ sealedClasses ++ enums

  // ── Case classes ───────────────────────────────────────────────────────

  private val caseClassRegex: Regex =
    """case\s+class\s+(\w+)\s*\(([\s\S]*?)\)""".r

  def scanCaseClasses(file: os.Path): List[CaseClassDef] =
    val content = os.read(file)
    val pkg = extractPackage(content)

    // Exclude enum case variants (they're inside an enum block)
    val enumNames = enumRegex.findAllMatchIn(content).map(_.group(1)).toSet

    caseClassRegex.findAllMatchIn(content).map { m =>
      val name = m.group(1)
      val rawFields = m.group(2).trim
      // Collapse multiline fields to single line
      val fields = rawFields.replaceAll("\\s+", " ").trim
      CaseClassDef(name, fields, pkg, file.last)
    }.toList
      // Filter out enum variant-like names (heuristic: skip if starts with uppercase
      // and appears right after an enum definition)
      .filter(cc => !isEnumVariant(content, cc.name))

  private def isEnumVariant(content: String, name: String): Boolean =
    // Check if this case class appears inside an enum block
    val lines = content.linesIterator.toVector
    val idx = lines.indexWhere(l => l.contains(s"case class $name"))
    if idx < 0 then false
    else
      // Walk backwards to find if we're inside an enum
      val preceding = lines.take(idx).reverse.take(20)
      val inEnum = preceding.exists(l =>
        l.matches(""".*enum\s+\w+.*:""") || l.matches(""".*enum\s+\w+.*""")
      )
      // Also check indentation — enum variants are indented
      val indent = lines(idx).takeWhile(_.isSpaceChar).length
      inEnum && indent > 0

  // ── Service traits (tagless final) ─────────────────────────────────────

  private val serviceTraitRegex: Regex =
    """trait\s+(\w+)\s*\[\s*(\w+)\s*\[\s*_\s*\]""".r

  private val defMethodRegex: Regex =
    """def\s+(\w+)""".r

  def scanServiceTraits(file: os.Path): List[ServiceTrait] =
    val content = os.read(file)
    val lines = content.linesIterator.toVector
    val pkg = extractPackage(content)

    serviceTraitRegex.findAllMatchIn(content).map { m =>
      val traitName = m.group(1)
      val typeParam = s"${m.group(2)}[_]"

      // Extract method names from the trait body
      val startIdx = lines.indexWhere(_.contains(s"trait $traitName"))
      val methods = if startIdx >= 0 then
        lines.drop(startIdx + 1)
          .takeWhile(l => !l.matches("""^\S.*""") || l.trim.isEmpty) // until next top-level decl
          .filter(_.trim.startsWith("def "))
          .flatMap(l => defMethodRegex.findFirstMatchIn(l.trim).map(_.group(1)))
          .toList
      else Nil

      ServiceTrait(traitName, typeParam, methods, pkg, file.last)
    }.toList

  // ── Smithy models ──────────────────────────────────────────────────────

  private val smithyServiceRegex: Regex =
    """service\s+(\w+)\s*\{""".r

  private val smithyStructureRegex: Regex =
    """structure\s+(\w+)\s*\{""".r

  private val smithyOperationRegex: Regex =
    """operation\s+(\w+)\s*\{""".r

  def scanSmithyModels(file: os.Path): List[SmithyModel] =
    val content = os.read(file)

    val services = smithyServiceRegex.findAllMatchIn(content).map { m =>
      // Extract operations from the service block
      val ops = smithyOperationRegex.findAllMatchIn(content).map(_.group(1)).mkString(", ")
      SmithyModel(m.group(1), "service", ops, file.last)
    }.toList

    val structures = smithyStructureRegex.findAllMatchIn(content).map { m =>
      SmithyModel(m.group(1), "structure", "", file.last)
    }.toList

    services ++ structures

  // ── ScalaCheck generators ──────────────────────────────────────────────

  private val genRegex: Regex =
    """val\s+(gen\w+)\s*:\s*Gen\[(\w+)\]""".r

  private val genInferredRegex: Regex =
    """val\s+(gen\w+)\s*=\s*Gen\.""".r

  private val arbitraryRegex: Regex =
    """(?:implicit|given)\s+.*Arbitrary\[(\w+)\]""".r

  def scanGenerators(file: os.Path): List[GeneratorDef] =
    val content = os.read(file)

    val explicit = genRegex.findAllMatchIn(content).map { m =>
      GeneratorDef(m.group(1), s"Gen[${m.group(2)}]", file.last)
    }.toList

    val inferred = genInferredRegex.findAllMatchIn(content)
      .map(_.group(1))
      .filterNot(name => explicit.exists(_.name == name))
      .map(name => GeneratorDef(name, "Gen[?]", file.last))
      .toList

    val arbitraries = arbitraryRegex.findAllMatchIn(content).map { m =>
      GeneratorDef(s"arbitrary${m.group(1)}", s"Arbitrary[${m.group(1)}]", file.last)
    }.toList

    explicit ++ inferred ++ arbitraries

  // ── Helpers ────────────────────────────────────────────────────────────

  private val packageRegex: Regex = """package\s+([\w.]+)""".r

  private def extractPackage(content: String): String =
    packageRegex.findFirstMatchIn(content).map(_.group(1)).getOrElse("(default)")

// ---------------------------------------------------------------------------
//  Markdown formatter
// ---------------------------------------------------------------------------

object MarkdownFormatter:

  def format(result: ScanResult, projectName: String): String =
    val sb = new StringBuilder
    sb.append(s"# Concept Inventory\n\n")
    sb.append(s"<!-- Auto-generated by concept-scanner for project: $projectName -->\n")
    sb.append(s"<!-- Scan date: ${java.time.LocalDate.now} -->\n\n")

    // Opaque types
    sb.append("## Refined / Opaque Types\n\n")
    sb.append("| Type | Underlying | Constraint | Package | Introduced By |\n")
    sb.append("|------|-----------|------------|---------|---------------|\n")
    result.opaqueTypes.foreach { t =>
      sb.append(s"| ${t.name} | ${t.underlying} | ${esc(t.constraint)} | ${t.pkg} | scan:${t.file} |\n")
    }
    if result.opaqueTypes.isEmpty then sb.append("| *(none found)* | | | | |\n")
    sb.append("\n")

    // Sealed types
    sb.append("## Sealed Traits and Enums\n\n")
    sb.append("| Type | Kind | Variants | Package | Introduced By |\n")
    sb.append("|------|------|----------|---------|---------------|\n")
    result.sealedTypes.foreach { t =>
      val variants = if t.variants.nonEmpty then t.variants.mkString(", ") else "—"
      sb.append(s"| ${t.name} | ${t.kind} | $variants | ${t.pkg} | scan:${t.file} |\n")
    }
    if result.sealedTypes.isEmpty then sb.append("| *(none found)* | | | | |\n")
    sb.append("\n")

    // Case classes
    sb.append("## Case Classes (Domain Value Objects)\n\n")
    sb.append("| Type | Fields | Package | Introduced By |\n")
    sb.append("|------|--------|---------|---------------|\n")
    result.caseClasses.foreach { c =>
      sb.append(s"| ${c.name} | ${esc(c.fields)} | ${c.pkg} | scan:${c.file} |\n")
    }
    if result.caseClasses.isEmpty then sb.append("| *(none found)* | | | |\n")
    sb.append("\n")

    // Service traits
    sb.append("## Service Traits\n\n")
    sb.append("| Trait | Type Param | Methods | Package | Introduced By |\n")
    sb.append("|-------|-----------|---------|---------|---------------|\n")
    result.serviceTraits.foreach { s =>
      val methods = s.methods.mkString(", ")
      sb.append(s"| ${s.name} | ${s.typeParam} | $methods | ${s.pkg} | scan:${s.file} |\n")
    }
    if result.serviceTraits.isEmpty then sb.append("| *(none found)* | | | | |\n")
    sb.append("\n")

    // Smithy models
    sb.append("## Smithy Models\n\n")
    sb.append("| Model | Kind | Operations/Fields | Location | Introduced By |\n")
    sb.append("|-------|------|-------------------|----------|---------------|\n")
    result.smithyModels.foreach { m =>
      sb.append(s"| ${m.name} | ${m.kind} | ${m.members} | ${m.file} | scan:${m.file} |\n")
    }
    if result.smithyModels.isEmpty then sb.append("| *(none found)* | | | | |\n")
    sb.append("\n")

    // Generators
    sb.append("## Property Generators\n\n")
    sb.append("| Generator | Generates | Location | Introduced By |\n")
    sb.append("|-----------|----------|----------|---------------|\n")
    result.generators.foreach { g =>
      sb.append(s"| ${g.name} | ${g.generates} | ${g.file} | scan:${g.file} |\n")
    }
    if result.generators.isEmpty then sb.append("| *(none found)* | | | |\n")
    sb.append("\n")

    // Resources placeholder
    sb.append("## Cats Effect Resources and Middleware\n\n")
    sb.append("| Resource | Type | Purpose | Package | Introduced By |\n")
    sb.append("|----------|------|---------|---------|---------------|\n")
    sb.append("| *(manual entry — not detectable by scanner)* | | | | |\n")

    sb.toString

  private def esc(s: String): String =
    s.replace("|", "\\|").replace("\n", " ")

// ---------------------------------------------------------------------------
//  JSON formatter
// ---------------------------------------------------------------------------

object JsonFormatter:

  def format(result: ScanResult): String =
    import result.*
    val parts = List(
      "opaqueTypes" -> opaqueTypes.map(t =>
        s"""    {"name":"${t.name}","underlying":"${t.underlying}","constraint":"${je(t.constraint)}","package":"${t.pkg}","file":"${t.file}"}"""),
      "sealedTypes" -> sealedTypes.map(t =>
        s"""    {"name":"${t.name}","kind":"${t.kind}","variants":[${t.variants.map(v => s""""$v"""").mkString(",")}],"package":"${t.pkg}","file":"${t.file}"}"""),
      "caseClasses" -> caseClasses.map(c =>
        s"""    {"name":"${c.name}","fields":"${je(c.fields)}","package":"${c.pkg}","file":"${c.file}"}"""),
      "serviceTraits" -> serviceTraits.map(s =>
        s"""    {"name":"${s.name}","typeParam":"${s.typeParam}","methods":[${s.methods.map(m => s""""$m"""").mkString(",")}],"package":"${s.pkg}","file":"${s.file}"}"""),
      "smithyModels" -> smithyModels.map(m =>
        s"""    {"name":"${m.name}","kind":"${m.kind}","members":"${je(m.members)}","file":"${m.file}"}"""),
      "generators" -> generators.map(g =>
        s"""    {"name":"${g.name}","generates":"${g.generates}","file":"${g.file}"}""")
    )
    val sections = parts.map { case (key, items) =>
      s"""  "$key": [\n${items.mkString(",\n")}\n  ]"""
    }
    s"{\n${sections.mkString(",\n")}\n}"

  private def je(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

// ---------------------------------------------------------------------------
//  CLI entry point
// ---------------------------------------------------------------------------

@main def main(args: String*): Unit =
  if args.isEmpty then
    System.err.println("Usage: concept-scanner <project-dir> [--json] [--output <file>]")
    System.err.println("")
    System.err.println("Scans a Scala 3 project and extracts domain concepts.")
    System.err.println("Output: Markdown tables for concept-inventory.md (default) or JSON.")
    System.exit(1)

  val projectDir = os.Path(args.head, os.pwd)
  val jsonMode = args.contains("--json")
  val outputIdx = args.indexOf("--output")
  val outputFile = if outputIdx >= 0 && outputIdx + 1 < args.length then
    Some(os.Path(args(outputIdx + 1), os.pwd))
  else None

  if !os.exists(projectDir) then
    System.err.println(s"Error: directory not found: $projectDir")
    System.exit(1)

  System.err.println(s"Scanning: $projectDir")
  val result = ConceptScanner.scan(projectDir)

  System.err.println(s"Found: ${result.opaqueTypes.size} opaque types, " +
    s"${result.sealedTypes.size} sealed types, " +
    s"${result.caseClasses.size} case classes, " +
    s"${result.serviceTraits.size} service traits, " +
    s"${result.smithyModels.size} smithy models, " +
    s"${result.generators.size} generators")

  val output = if jsonMode then JsonFormatter.format(result)
    else MarkdownFormatter.format(result, projectDir.last)

  outputFile match
    case Some(path) =>
      os.write.over(path, output)
      System.err.println(s"Written to: $path")
    case None =>
      println(output)
