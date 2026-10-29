package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

/** The `install-skills` subcommand — skill installation. */
object InstallSkillsCmd:

  /**
   * `command -v <name>` — an executable regular file named `name` under
   * some `PATH` entry (an empty entry means the current directory, as the
   * shell reads it). The adapter's presence check, injected into
   * `probePrerequisites`; the oracle controls `PATH` through the env seam.
   */
  private[cli] def onPath(name: String, pathEnv: String): Boolean =
    pathEnv.split(":", -1).toList.exists { (entry: String) =>
      val dir: String = if entry.isEmpty then "." else entry
      val p: Path     = Paths.get(dir).resolve(name)
      Files.isRegularFile(p) && Files.isExecutable(p)
    }

  /**
   * The prerequisite probe — the predecessor's `--check-installed` mode.
   * Pure over an injected presence check: the adapter supplies
   * `command -v <name>`; a prerequisite is reported present only when the
   * check finds it, and every declared prerequisite is probed, so the
   * report names each one it checked.
   *
   * spec: install-tool-surface-parity — Requirement: The skill installer probes the declared prerequisite set
   */
  private[cli] def probePrerequisites(
    present: String => Boolean
  ): PrerequisiteReport =
    PrerequisiteReport(
      InstallSurface.declaredPrerequisites.map((n: String) => PrerequisiteProbe(n, present(n)))
    )

  /**
   * The declared agent directories under `projectRoot` that do NOT hold
   * every skill in `skillNames` — the coverage check that makes a
   * single-directory install report the directories it skipped.
   *
   * spec: install-tool-surface-parity — Scenario: Adversarial — installing into a single directory is not the whole install
   */
  private[cli] def unwrittenAgentDirs(
    projectRoot: Path,
    skillNames: List[String]
  ): List[String] =
    InstallSurface.skillAgentDirs.filter { (d: String) =>
      skillNames.exists((s: String) => !Files.isRegularFile(projectRoot.resolve(d).resolve(s).resolve("SKILL.md")))
    }

  /**
   * The `skills/<name>/` source directories, sorted — the predecessor's
   * glob order. The predecessor's per-child directory glob never matches
   * dot-directories, so a `.git/` under `skills/` is not a skill.
   */
  private def skillDirs(skillsSource: Path): List[Path] =
    Using.resource(Files.list(skillsSource)) { (stream: java.util.stream.Stream[Path]) =>
      stream
        .iterator()
        .asScala
        .toList
        .filter(Files.isDirectory(_))
        .filter((p: Path) => !p.getFileName.toString.startsWith("."))
        .sortBy((p: Path) => p.getFileName.toString)
    }

  /** One agent directory: every skill's `SKILL.md` copied in, each install reported. */
  private def installIntoDir(skills: List[Path], target: Path): Int =
    Files.createDirectories(target)
    skills.foreach { (src: Path) =>
      val name: String = src.getFileName.toString
      val dst: Path    = target.resolve(name)
      Files.createDirectories(dst)
      val file: Path = dst.resolve("SKILL.md")
      Files.copy(src.resolve("SKILL.md"), file, StandardCopyOption.REPLACE_EXISTING)
      SubcommandWiring.emitStdout(s"installed $name -> $file\n")
    }
    skills.length

  /**
   * The full skill install — every skill document under `skillsSource`
   * copied into EVERY declared agent directory under `projectRoot`
   * (`InstallSurface.skillAgentDirs`), never a subset. An agent directory
   * that cannot be written is could-not-determine naming that directory;
   * a missing skills source is the predecessor's `no skills directory`
   * finding. The first failure stops the install — the predecessor's
   * `set -e` never writes a later directory after an earlier one failed.
   *
   * spec: install-tool-surface-parity — Requirement: The skill installer writes to every declared agent directory
   */
  private[cli] def installEverywhere(
    skillsSource: Path,
    projectRoot: Path
  ): Outcome[Int] =
    if !Files.isDirectory(skillsSource) then
      SubcommandWiring.emitStderr(s"install-skills: no skills directory at $skillsSource\n")
      Outcome.Finding(s"no skills directory at $skillsSource")
    else
      val skills: List[Path] = skillDirs(skillsSource)
      // Both shapes are the predecessor's exit-1 `cp` failure: an empty
      // skills/ iterates the literal `*/` glob and dies, and a skill
      // directory without SKILL.md dies on the copy — never a clean run,
      // never could-not-determine.
      val blocker: Option[String] =
        if skills.isEmpty then Some(s"no skills under $skillsSource")
        else
          skills
            .find((s: Path) => !Files.isRegularFile(s.resolve("SKILL.md")))
            .map((s: Path) => s"no SKILL.md in $s")
      blocker match
        case Some(reason) =>
          SubcommandWiring.emitStderr(s"install-skills: $reason\n")
          Outcome.Finding(reason)
        case None =>
          InstallSurface.skillAgentDirs
            .foldLeft[Either[String, Int]](Right(0)) { (acc: Either[String, Int], d: String) =>
              acc.flatMap { (n: Int) =>
                try Right(n + installIntoDir(skills, projectRoot.resolve(d)))
                catch
                  case e: java.io.IOException =>
                    Left(s"cannot write $d under $projectRoot: ${e.getMessage}")
              }
            } match
            case Left(reason) => Outcome.Undetermined(reason)
            case Right(count) =>
              SubcommandWiring.emitStdout(s"install-skills: $count skill copy(ies) installed.\n")
              SubcommandWiring.emitStdout(
                "NOTE: if an agent directory (.claude/, .pi/, .devin/) is git-ignored in\n" +
                  "this repo (check 'git check-ignore <dir>/skills' and your user-global\n" +
                  "ignore file), the installed copies are local-only by design — the\n" +
                  "schema's skills/ directory remains the shared source of truth.\n"
              )
              Outcome.Ran(0)

  /** `--check-installed` — probe the declared prerequisite set on `PATH`. */
  private def runProbe(env: Map[String, String]): Outcome[Int] =
    val report: PrerequisiteReport =
      probePrerequisites((n: String) => onPath(n, env.getOrElse("PATH", "")))
    report.probes.foreach { (p: PrerequisiteProbe) =>
      if p.present then SubcommandWiring.emitStdout(s"install-skills: ${p.name} — present\n")
      else SubcommandWiring.emitStderr(s"install-skills: ${p.name} — MISSING\n")
    }
    if report.allPresent then
      SubcommandWiring.emitStdout("install-skills: all prerequisites present\n")
      Outcome.Ran(0)
    else
      SubcommandWiring.emitStderr(s"install-skills: ${report.missingCount} prerequisite(s) missing\n")
      Outcome.Finding(s"${report.missingCount} prerequisite(s) missing")

  /**
   * The full install — the schema's `skills/` resolved like the
   * predecessor's `$SCRIPT_DIR/../skills`: relative to the TARGET
   * project, so `install-skills <root>` works from an unrelated cwd.
   */
  private def runInstall(projectRoot: Path, env: Map[String, String]): Outcome[Int] =
    val schemaDir: Path = SubcommandWiring.schemaDirOf(env, projectRoot)
    installEverywhere(schemaDir.resolve("skills"), projectRoot)

  /**
   * The env-injecting overload — the seam the test oracle drives.
   *
   * `PATH` feeds the prerequisite probe (`command -v` parity);
   * `PROBATIO_SCHEMA_DIR` locates the schema tree the skill documents
   * come from (the binary lives at `<schema>/bin/probatio`, as the
   * predecessor resolves `$SCRIPT_DIR/../skills`); `PWD` supplies the
   * `.` default for the positional project root.
   *
   * The predecessor reads ONLY `$1` — every first token is a project
   * root, extra arguments are ignored, and a dash-token dies inside
   * `mkdir -p` as an invalid option (exit 1), so the ported surface
   * rejects flag-shaped roots as findings rather than inventing flags.
   */
  private[cli] def run(args: Array[String], env: Map[String, String]): Outcome[Int] =
    args.toList match
      case "--check-installed" :: _ => runProbe(env)
      case head :: _ if head.startsWith("-") =>
        SubcommandWiring.emitStderr(s"install-skills: mkdir: invalid option -- '$head'\n")
        Outcome.Finding(s"invalid option: $head")
      case "" :: _ =>
        // `install-skills.sh ""` → `mkdir -p /.claude/skills` fails — exit 1.
        Outcome.Finding("empty project root")
      case root :: _ =>
        runInstall(Path.of(root), env)
      case Nil =>
        runInstall(Path.of(env.getOrElse("PWD", ".")), env)

  /**
   * Wire the install-skills subcommand to the predecessor's surface:
   * `install-skills [project-root]` installs every skill into every
   * declared agent directory; `install-skills --check-installed` probes
   * the declared prerequisite set. The `--dir` surface is removed — the
   * predecessor rejects it, and surface parity is the swap precondition.
   *
   * spec: install-tool-surface-parity — Requirement: The skill installer probes the declared prerequisite set
   * spec: install-tool-surface-parity — Requirement: The skill installer writes to every declared agent directory
   * spec: install-tool-surface-parity — Property: surface-parity-with-the-predecessor
   */
  def run(args: Array[String]): Outcome[Int] =
    run(args, sys.env) // scalafix:ok DisableSyntax.NoSysEnv
