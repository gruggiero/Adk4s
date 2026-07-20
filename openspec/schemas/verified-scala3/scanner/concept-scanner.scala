//> using scala 3.5.2
//> using dep "com.lihaoyi::os-lib:0.11.3"
//> using dep "org.scalameta::scalameta:4.13.6"
//> using options -Wunused:all, -deprecation

package scanner

import scala.meta.*
import scala.util.matching.Regex

// ═══════════════════════════════════════════════════════════════════════════
//  Concept Scanner for Scala 3 Projects — SEMANTIC (Scalameta) edition
//
//  Scans a Scala 3 source tree and extracts domain concepts by PARSING the
//  sources with Scalameta (dialects.Scala3) — not by regexing lines. Regex
//  scanning demonstrably catalogued prose words from comments as sealed
//  types and missed nested/multiline declarations; trees cannot make those
//  mistakes. Files that fail to parse are counted and reported on stderr.
//
//  Extracted:
//  - Refined/opaque types (Iron-style `:|` constraints or plain opaque types)
//  - Sealed traits / sealed abstract classes (with same-file direct subtypes
//    as variants) and enums (with cases)
//  - Case classes (public domain value objects; enum cases excluded by
//    construction — they are different AST nodes)
//  - Service traits (any trait with a higher-kinded type param, e.g. F[_])
//  - Property generators (Gen[_] vals/defs, Arbitrary instances)
//  - Smithy models (from .smithy files — regex; smithy is not Scala)
//
//  Nested declarations are reported with their enclosing path
//  (e.g. `GraphWorkflowContext.Event`, `RunnableOps.FallbackSemantic`) —
//  matching how Metals/SemanticDB name them.
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
    impls: List[String], // cross-file implementations, linked by extends clauses
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
    generators: List[GeneratorDef],
    parseFailures: List[String]
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

    val mainParsed = mainScala.map(f => f -> parseFile(f))
    val testParsed = testScala.map(f => f -> parseFile(f))
    val failures =
      (mainParsed ++ testParsed).collect { case (f, Left(err)) => s"${f.relativeTo(projectDir)}: $err" }

    val mainTrees = mainParsed.collect { case (f, Right(t)) => (f, t) }
    val testTrees = testParsed.collect { case (f, Right(t)) => (f, t) }

    // Cross-file hierarchy: sealed types' DIRECT children are same-file by
    // language rule (already complete), but service-trait IMPLEMENTATIONS
    // live anywhere in the repo — link them via extends clauses (including
    // anonymous `new Trait[F]: ...` factory implementations) across all
    // parsed files. Name resolution: a simple-name match links when the
    // trait's simple name is unique in the scan, or the implementor shares
    // its package (ambiguous cross-package matches are skipped, not guessed).
    val edges = mainTrees.flatMap(extendsEdges.tupled)
    val serviceRaw = mainTrees.flatMap(scanServiceTraits.tupled)
    val simpleNameCount: Map[String, Int] =
      serviceRaw.groupBy(_.name.split('.').last).view.mapValues(_.size).toMap
    val serviceLinked = serviceRaw.map { s =>
      val simple = s.name.split('.').last
      val impls = edges.collect {
        case (child, parent, childPkg)
            if parent == simple && child != simple
              && (simpleNameCount.getOrElse(simple, 0) == 1 || childPkg == s.pkg) =>
          child
      }.distinct.sorted
      s.copy(impls = impls)
    }

    ScanResult(
      opaqueTypes = mainTrees.flatMap(scanOpaqueTypes.tupled),
      sealedTypes = mainTrees.flatMap(scanSealedTypes.tupled),
      caseClasses = mainTrees.flatMap(scanCaseClasses.tupled),
      serviceTraits = serviceLinked,
      smithyModels = smithyFiles.flatMap(scanSmithyModels),
      generators = testTrees.flatMap(scanGenerators.tupled),
      parseFailures = failures
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

  // ── Parsing + tree navigation ──────────────────────────────────────────

  private def parseFile(file: os.Path): Either[String, Source] =
    try
      dialects.Scala3(os.read(file)).parse[Source] match
        case Parsed.Success(tree) => Right(tree)
        case e: Parsed.Error      => Left(e.message.linesIterator.nextOption.getOrElse("parse error"))
    catch case ex: Throwable => Left(ex.getMessage)

  private def all(t: Tree): LazyList[Tree] =
    t #:: LazyList.from(t.children).flatMap(all)

  /** Package of a node: concatenation of enclosing Pkg names. */
  private def pkgOf(source: Source, node: Tree): String =
    val pkgs = ancestors(source, node).collect { case p: Pkg => p.ref.syntax }
    if pkgs.isEmpty then "(default)" else pkgs.mkString(".")

  /** Enclosing object/class/trait path, e.g. "RunnableOps" for a nested enum
    * — reported as `Outer.Inner`, matching Metals/SemanticDB naming. */
  private def ownerPath(source: Source, node: Tree): String =
    ancestors(source, node).collect {
      case o: Defn.Object => o.name.value
      case c: Defn.Class  => c.name.value
      case t: Defn.Trait  => t.name.value
      case e: Defn.Enum   => e.name.value
    }.mkString(".")

  private def qualified(source: Source, node: Tree, name: String): String =
    val owner = ownerPath(source, node)
    if owner.isEmpty then name else s"$owner.$name"

  private def ancestors(source: Source, node: Tree): List[Tree] =
    // parent chain from root to node (excluding the node itself)
    def chain(t: Tree): List[Tree] =
      t.parent match
        case Some(p) => chain(p) :+ p
        case None    => Nil
    chain(node)

  private def isPublic(mods: List[Mod]): Boolean =
    !mods.exists(m => m.is[Mod.Private] || m.is[Mod.Protected])

  // ── Opaque types ───────────────────────────────────────────────────────

  private val scanOpaqueTypes: (os.Path, Source) => List[OpaqueType] = (file, source) =>
    all(source).collect {
      case d: Defn.Type if d.mods.exists(_.is[Mod.Opaque]) && isPublic(d.mods) =>
        val (underlying, constraint) = d.body match
          case Type.ApplyInfix(lhs, Type.Name(":|"), rhs) => (lhs.syntax, rhs.syntax)
          case other                                      => (other.syntax, "(none — plain opaque type)")
        OpaqueType(qualified(source, d, d.name.value), underlying, constraint,
          pkgOf(source, d), file.last)
    }.toList

  // ── Sealed traits, sealed abstract classes, enums ──────────────────────

  private val scanSealedTypes: (os.Path, Source) => List[SealedType] = (file, source) =>
    val nodes = all(source).toList

    // same-file direct subtypes of a sealed type, by init reference
    def subtypesOf(name: String): List[String] =
      nodes.collect {
        case c: Defn.Class if extendsName(c.templ, name)  => c.name.value
        case o: Defn.Object if extendsName(o.templ, name) => o.name.value
        case t: Defn.Trait if extendsName(t.templ, name)  => t.name.value
      }.distinct

    val sealedTraits = nodes.collect {
      case t: Defn.Trait if t.mods.exists(_.is[Mod.Sealed]) && isPublic(t.mods) =>
        SealedType(qualified(source, t, t.name.value), "sealed trait",
          subtypesOf(t.name.value), pkgOf(source, t), file.last)
    }

    val sealedClasses = nodes.collect {
      case c: Defn.Class
          if c.mods.exists(_.is[Mod.Sealed]) && c.mods.exists(_.is[Mod.Abstract])
            && isPublic(c.mods) =>
        SealedType(qualified(source, c, c.name.value), "sealed abstract class",
          subtypesOf(c.name.value), pkgOf(source, c), file.last)
    }

    val enums = nodes.collect {
      case e: Defn.Enum if isPublic(e.mods) =>
        val variants = e.templ.stats.flatMap {
          case c: Defn.EnumCase         => List(c.name.value)
          case r: Defn.RepeatedEnumCase => r.cases.map(_.value)
          case _                        => Nil
        }
        SealedType(qualified(source, e, e.name.value), "enum", variants,
          pkgOf(source, e), file.last)
    }

    sealedTraits ++ sealedClasses ++ enums

  private def parentNames(templ: Template): List[String] =
    templ.inits.flatMap(init => init.tpe match
      case Type.Name(n)                  => Some(n)
      case Type.Apply(Type.Name(n), _)   => Some(n)
      case Type.Select(_, Type.Name(n))  => Some(n)
      case _                             => None)

  private def extendsName(templ: Template, name: String): Boolean =
    parentNames(templ).contains(name)

  /** (childName, parentSimpleName, childPkg) for every extends clause —
    * including ANONYMOUS implementations (`new Trait[F]: ...` inside a
    * factory), attributed to their enclosing definition, e.g.
    * `MemoryRetriever (anonymous)`. */
  private val extendsEdges: (os.Path, Source) => List[(String, String, String)] =
    (_, source) =>
      all(source).flatMap {
        case c: Defn.Class  => parentNames(c.templ).map(p => (c.name.value, p, pkgOf(source, c)))
        case o: Defn.Object => parentNames(o.templ).map(p => (o.name.value, p, pkgOf(source, o)))
        case t: Defn.Trait  => parentNames(t.templ).map(p => (t.name.value, p, pkgOf(source, t)))
        case n: Term.NewAnonymous =>
          val owner = ownerPath(source, n)
          val child = if owner.isEmpty then "(anonymous)" else s"$owner (anonymous)"
          parentNames(n.templ).map(p => (child, p, pkgOf(source, n)))
        case _ => Nil
      }.toList

  // ── Case classes ───────────────────────────────────────────────────────

  private val scanCaseClasses: (os.Path, Source) => List[CaseClassDef] = (file, source) =>
    all(source).collect {
      // enum cases are Defn.EnumCase nodes, not Defn.Class — excluded by
      // construction, no indentation heuristics needed
      case c: Defn.Class if c.mods.exists(_.is[Mod.Case]) && isPublic(c.mods) =>
        val fields = c.ctor.paramClauses.headOption
          .map(_.values.map(p => s"${p.name.value}: ${p.decltpe.map(_.syntax).getOrElse("?")}")
            .mkString(", "))
          .getOrElse("")
        CaseClassDef(qualified(source, c, c.name.value), fields, pkgOf(source, c), file.last)
    }.toList

  // ── Service traits (any trait with a higher-kinded type param) ─────────

  private val scanServiceTraits: (os.Path, Source) => List[ServiceTrait] = (file, source) =>
    all(source).collect {
      case t: Defn.Trait
          if isPublic(t.mods)
            && t.tparamClause.values.exists(_.tparamClause.values.nonEmpty) =>
        val hk = t.tparamClause.values.find(_.tparamClause.values.nonEmpty).get
        val methods = t.templ.stats.collect {
          case d: Decl.Def => d.name.value
          case d: Defn.Def => d.name.value
        }
        ServiceTrait(qualified(source, t, t.name.value), hk.syntax, methods,
          Nil, pkgOf(source, t), file.last)
    }.toList

  // ── Smithy models (regex — smithy is not Scala) ────────────────────────

  private val smithyServiceRegex: Regex = """service\s+(\w+)\s*\{""".r
  private val smithyStructureRegex: Regex = """structure\s+(\w+)\s*\{""".r
  private val smithyOperationRegex: Regex = """operation\s+(\w+)\s*\{""".r

  def scanSmithyModels(file: os.Path): List[SmithyModel] =
    val content = os.read(file)

    val services = smithyServiceRegex.findAllMatchIn(content).map { m =>
      val ops = smithyOperationRegex.findAllMatchIn(content).map(_.group(1)).mkString(", ")
      SmithyModel(m.group(1), "service", ops, file.last)
    }.toList

    val structures = smithyStructureRegex.findAllMatchIn(content).map { m =>
      SmithyModel(m.group(1), "structure", "", file.last)
    }.toList

    services ++ structures

  // ── Property generators (ScalaCheck or Hedgehog — both use `Gen`) ──────

  private val scanGenerators: (os.Path, Source) => List[GeneratorDef] = (file, source) =>
    def genType(tpe: Option[Type]): Option[String] = tpe.map(_.syntax).filter(s =>
      s.startsWith("Gen[") || s.startsWith("Arbitrary["))

    val valsAndDefs = all(source).collect {
      case v: Defn.Val =>
        val name = v.pats.collectFirst { case Pat.Var(n) => n.value }.getOrElse("?")
        (name, genType(v.decltpe), v.rhs.syntax)
      case d: Defn.Def =>
        (d.name.value, genType(d.decltpe), d.body.syntax)
    }.toList

    val gens = valsAndDefs.flatMap {
      case (name, Some(tpe), _)                             => Some(GeneratorDef(name, tpe, file.last))
      case (name, None, rhs) if name.startsWith("gen")
          && (rhs.startsWith("Gen.") || rhs.contains("Gen.")) => Some(GeneratorDef(name, "Gen[?]", file.last))
      case _                                                => None
    }

    val arbitraries = all(source).collect {
      case g: Defn.GivenAlias if g.decltpe.syntax.startsWith("Arbitrary[") =>
        val inner = g.decltpe.syntax.stripPrefix("Arbitrary[").stripSuffix("]")
        GeneratorDef(s"arbitrary$inner", g.decltpe.syntax, file.last)
    }.toList

    (gens ++ arbitraries).distinctBy(g => (g.name, g.generates))

// ---------------------------------------------------------------------------
//  Markdown formatter
// ---------------------------------------------------------------------------

object MarkdownFormatter:

  def format(result: ScanResult, projectName: String): String =
    val sb = new StringBuilder
    sb.append(s"# Concept Inventory\n\n")
    sb.append(s"<!-- Auto-generated by concept-scanner (Scalameta) for project: $projectName -->\n")
    sb.append(s"<!-- Scan date: ${java.time.LocalDate.now} -->\n")
    if result.parseFailures.nonEmpty then
      sb.append(s"<!-- WARNING: ${result.parseFailures.size} file(s) failed to parse — see scanner stderr -->\n")
    sb.append("\n")

    // Opaque types
    sb.append("## Refined / Opaque Types\n\n")
    sb.append("| Type | Underlying | Constraint | Package | Introduced By |\n")
    sb.append("|------|-----------|------------|---------|---------------|\n")
    result.opaqueTypes.foreach { t =>
      sb.append(s"| ${t.name} | ${esc(t.underlying)} | ${esc(t.constraint)} | ${t.pkg} | scan:${t.file} |\n")
    }
    if result.opaqueTypes.isEmpty then sb.append("| *(none found)* | | | | |\n")
    sb.append("\n")

    // Sealed types
    sb.append("## Sealed Traits and Enums\n\n")
    sb.append("| Type | Kind | Variants | Package | Introduced By |\n")
    sb.append("|------|------|----------|---------|---------------|\n")
    result.sealedTypes.foreach { t =>
      val variants = if t.variants.nonEmpty then t.variants.mkString(", ") else "—"
      sb.append(s"| ${t.name} | ${t.kind} | ${esc(variants)} | ${t.pkg} | scan:${t.file} |\n")
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
    sb.append("| Trait | Type Param | Methods | Implementations | Package | Introduced By |\n")
    sb.append("|-------|-----------|---------|-----------------|---------|---------------|\n")
    result.serviceTraits.foreach { s =>
      val methods = s.methods.mkString(", ")
      val impls = if s.impls.nonEmpty then s.impls.mkString(", ") else "—"
      sb.append(s"| ${s.name} | ${esc(s.typeParam)} | ${esc(methods)} | ${esc(impls)} | ${s.pkg} | scan:${s.file} |\n")
    }
    if result.serviceTraits.isEmpty then sb.append("| *(none found)* | | | | | |\n")
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
      sb.append(s"| ${g.name} | ${esc(g.generates)} | ${g.file} | scan:${g.file} |\n")
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
        s"""    {"name":"${t.name}","underlying":"${je(t.underlying)}","constraint":"${je(t.constraint)}","package":"${t.pkg}","file":"${t.file}"}"""),
      "sealedTypes" -> sealedTypes.map(t =>
        s"""    {"name":"${t.name}","kind":"${t.kind}","variants":[${t.variants.map(v => s""""$v"""").mkString(",")}],"package":"${t.pkg}","file":"${t.file}"}"""),
      "caseClasses" -> caseClasses.map(c =>
        s"""    {"name":"${c.name}","fields":"${je(c.fields)}","package":"${c.pkg}","file":"${c.file}"}"""),
      "serviceTraits" -> serviceTraits.map(s =>
        s"""    {"name":"${s.name}","typeParam":"${je(s.typeParam)}","methods":[${s.methods.map(m => s""""$m"""").mkString(",")}],"implementations":[${s.impls.map(i => s""""$i"""").mkString(",")}],"package":"${s.pkg}","file":"${s.file}"}"""),
      "smithyModels" -> smithyModels.map(m =>
        s"""    {"name":"${m.name}","kind":"${m.kind}","members":"${je(m.members)}","file":"${m.file}"}"""),
      "generators" -> generators.map(g =>
        s"""    {"name":"${g.name}","generates":"${je(g.generates)}","file":"${g.file}"}"""),
      "parseFailures" -> parseFailures.map(f => s"""    "${je(f)}"""")
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
    System.err.println("Scans a Scala 3 project (Scalameta parsing, multi-module) and")
    System.err.println("extracts domain concepts.")
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

  System.err.println(s"Scanning: $projectDir (Scalameta)")
  val result = ConceptScanner.scan(projectDir)

  System.err.println(s"Found: ${result.opaqueTypes.size} opaque types, " +
    s"${result.sealedTypes.size} sealed types, " +
    s"${result.caseClasses.size} case classes, " +
    s"${result.serviceTraits.size} service traits, " +
    s"${result.smithyModels.size} smithy models, " +
    s"${result.generators.size} generators")
  if result.parseFailures.nonEmpty then
    System.err.println(s"WARNING: ${result.parseFailures.size} file(s) failed to parse:")
    result.parseFailures.foreach(f => System.err.println(s"  $f"))

  val output = if jsonMode then JsonFormatter.format(result)
    else MarkdownFormatter.format(result, projectDir.last)

  outputFile match
    case Some(path) =>
      os.write.over(path, output)
      System.err.println(s"Written to: $path")
    case None =>
      println(output)
