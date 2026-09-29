package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.migration.HermeticEnv

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Model-based parity oracle for install-tool-surface-parity (spec 7):
 * the predecessor `install-skills.sh` / `install-hooks.sh` are the model,
 * executed as `bash` subprocesses; the ported `InstallSkillsCmd` /
 * `InstallHooksCmd` are the system under test, run in-process.
 *
 * `genInstallInvocation` is constructive over the closed set of
 * predecessor-accepted flags crossed with value shapes (present, absent,
 * empty, repeated), UNIONED with argument shapes the predecessor rejects
 * — both directions covered by construction, never by filtering. Parity
 * is the termination status: for every invocation the predecessor
 * accepts the ported installer accepts it too with the same status; for
 * every invocation the predecessor rejects the ported installer rejects
 * it.
 *
 * Each arm gets an equivalent fixture of its own (writes never race),
 * and the ported side receives the same `PATH` plus the `PWD` /
 * `PROBATIO_SCHEMA_DIR` seams — the schema dir the binary sits inside
 * in production.
 *
 * spec: install-tool-surface-parity — Property: surface-parity-with-the-predecessor
 */
final class InstallSurfaceParitySpec extends ProbatioCliSuite:

  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(300, "s")

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(80))

  import InstallSurfaceParitySpec.*

  // spec: install-tool-surface-parity — Property: surface-parity-with-the-predecessor
  property("surface parity with the predecessor", coverConfig):
    for inv <- genInstallInvocation.forAll
        .cover(30, "hooks", (i: Invocation) => i.tool == InstallTool.Hooks)
        .cover(25, "skills", (i: Invocation) => i.tool == InstallTool.Skills)
        .cover(15, "rejected-shape", (i: Invocation) => i.rejectedShape)
        .cover(30, "accepted-shape", (i: Invocation) => !i.rejectedShape)
    yield
      val predExit: Int = LiveFactFixtures.withTempDir("install-parity-pred") { (root: Path) =>
        materialise(root, inv)
        runPredecessor(inv, root)
      }
      val portExit: Int = LiveFactFixtures.withTempDir("install-parity-port") { (root: Path) =>
        materialise(root, inv)
        runPorted(inv, root)
      }
      Result
        .assert(portExit == predExit)
        .log(
          s"parity mismatch: ${inv.name}\n  args: ${inv.args.mkString(" ")}\n" +
            s"  pred exit: $predExit  port exit: $portExit"
        )

/**
 * The invocation corpus and both arms for `InstallSurfaceParitySpec`.
 */
object InstallSurfaceParitySpec:

  enum InstallTool:
    case Skills, Hooks

  /**
   * One invocation: the tool, its argument list (`@ROOT@` and `@NOOPEN@`
   * are substituted per arm), the harness markers its fixture carries,
   * and whether its shape is one the predecessor rejects.
   */
  final case class Invocation(
    tool: InstallTool,
    args: List[String],
    markers: Set[String],
    rejectedShape: Boolean,
    foreignDevin: Boolean = false
  ):
    def name: String = s"$tool ${args.mkString("[", " ", "]")} markers=${markers.mkString(",")}"

  // ── locating the repository, the predecessors, the schema ─────────

  private val repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  private val schemaDir: Path = repoRoot.resolve("openspec/schemas/verified-scala3")

  private val skillsPredecessor: Path =
    schemaDir.resolve("scanner/install-skills.sh")

  private val hooksPredecessor: Path =
    schemaDir.resolve("hooks/install-hooks.sh")

  private val allMarkers: Set[String] = Set("claude", "pi", "devin")

  // ── materialisation ────────────────────────────────────────────────

  /** Build the arm's project fixture inside `root`. */
  private def materialise(root: Path, inv: Invocation): Unit =
    inv.tool match
      case InstallTool.Skills => ()
      case InstallTool.Hooks =>
        Files.createDirectories(root.resolve("openspec"))
        inv.markers.foreach((h: String) => Files.createDirectories(root.resolve("." + h)))
        if inv.foreignDevin then
          Files.writeString(
            root.resolve(".devin/hooks.v1.json"),
            "{\"foreign\": true}\n",
            StandardCharsets.UTF_8
          )
        // The --project value that names a directory with no openspec/.
        Files.createDirectories(root.resolve("no-openspec"))

  private def substitute(args: List[String], root: Path): List[String] =
    args.map {
      case "@ROOT@"   => root.toString
      case "@NOOPEN@" => root.resolve("no-openspec").toString
      case a          => a
    }

  // ── the two arms ───────────────────────────────────────────────────

  /** The predecessor script as a `bash` subprocess, cwd = its fixture. */
  private def runPredecessor(inv: Invocation, root: Path): Int =
    val script: Path = inv.tool match
      case InstallTool.Skills => skillsPredecessor
      case InstallTool.Hooks  => hooksPredecessor
    val argv: List[String] = "bash" +: script.toString +: substitute(inv.args, root)
    // spec: hermetic-test-processes — via the shared helper; streams merged
    // as before — parity measures the status, never the text.
    HermeticEnv.captureMerged(argv, HermeticEnv.empty, cwd = Some(root.toFile)).exitCode

  /** The ported subcommand in-process; returns the mapped exit code. */
  private def runPorted(inv: Invocation, root: Path): Int =
    val env: Map[String, String] = Map(
      "PATH"                -> sys.env.getOrElse("PATH", ""), // scalafix:ok DisableSyntax.NoSysEnv
      "PWD"                 -> root.toString,
      "PROBATIO_SCHEMA_DIR" -> schemaDir.toString
    )
    val argv: Array[String] = substitute(inv.args, root).toArray
    val (_, _, oc) = StdoutCapture.captureBoth(
      inv.tool match
        case InstallTool.Skills => InstallSkillsCmd.run(argv, env)
        case InstallTool.Hooks  => InstallHooksCmd.run(argv, env)
    )
    Outcome.toExitCode(oc)

  // ── genInstallInvocation ───────────────────────────────────────────
  //
  // Constructive: the predecessor's accepted flag set crossed with value
  // shapes, unioned with rejected argument shapes — both directions are
  // covered by construction (union), never by filtering.

  private def hooks(
    args: List[String],
    markers: Set[String] = allMarkers,
    rejected: Boolean = false,
    foreignDevin: Boolean = false
  ): Invocation =
    Invocation(InstallTool.Hooks, args, markers, rejected, foreignDevin)

  private def skills(args: List[String], rejected: Boolean = false): Invocation =
    Invocation(InstallTool.Skills, args, Set.empty, rejected)

  /** The accepted hook-installer shapes: agent × apply × project. */
  private def genHooksAccepted: Gen[Invocation] =
    for
      agent <- Gen.element[Option[String]](
        None,
        List(
          Some("claude"),
          Some("pi"),
          Some("devin"),
          Some("all"),
          Some("foo"), // unrecognised harness: diagnosed at apply time, exit 0
          Some("")     // empty: the predecessor's all-present default
        )
      )
      apply <- Gen.boolean
      project <- Gen.element[Option[String]](
        None,
        List(Some("@ROOT@"), Some(""))
      )
      markers <- Gen.element[Set[String]](
        allMarkers,
        List(Set("claude"), Set("devin"), Set.empty[String])
      )
      foreign <- Gen.boolean
      help    <- Gen.element[Option[String]](None, List(Some("-h"), Some("--help")))
    yield
      val args: List[String] =
        agent.toList.flatMap((a: String) => List("--agent", a)) ++
          (if apply then List("--apply") else Nil) ++
          project.toList.flatMap((p: String) => List("--project", p)) ++
          help.toList
      hooks(args, markers = markers, foreignDevin = foreign && markers.contains("devin"))

  /** The rejected hook-installer shapes. */
  private def genHooksRejected: Gen[Invocation] =
    Gen.element(
      hooks(List("--bogus"), rejected = true),
      List(
        hooks(List("positional"), rejected = true),
        hooks(List("--agent"), rejected = true),              // missing value: shift past $#
        hooks(List("--project"), rejected = true),            // missing value
        hooks(List("--project", "@NOOPEN@"), rejected = true) // no openspec/ dir
      )
    )

  /** The accepted skill-installer shapes: [project-root] + --check-installed. */
  private def genSkillsAccepted: Gen[Invocation] =
    Gen.element(
      skills(Nil),
      List(
        skills(List("@ROOT@")),
        skills(List("@ROOT@", "extra", "args")),
        skills(List("--check-installed")),
        skills(List("--check-installed", "extra")),
        skills(List("@ROOT@/fresh/nested/root")) // mkdir -p creates the whole chain
      )
    )

  /** The rejected skill-installer shapes — every flag is a would-be root. */
  private def genSkillsRejected: Gen[Invocation] =
    Gen.element(
      skills(List("-h"), rejected = true),
      List(
        skills(List("--bogus"), rejected = true),
        skills(List("--dir", "x"), rejected = true)
      )
    )

  /**
   * genInstallInvocation — the spec's generator: accepted shapes UNIONED
   * with rejected shapes so both directions are covered by construction.
   */
  def genInstallInvocation: Gen[Invocation] =
    Gen.frequency1(
      40 -> genHooksAccepted,
      15 -> genHooksRejected,
      30 -> genSkillsAccepted,
      15 -> genSkillsRejected
    )

end InstallSurfaceParitySpec
