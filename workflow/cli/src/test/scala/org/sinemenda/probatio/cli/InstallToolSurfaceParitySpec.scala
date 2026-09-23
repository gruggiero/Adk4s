package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.InstallMode
import org.sinemenda.probatio.core.InstallSurface
import org.sinemenda.probatio.core.InstallTarget
import org.sinemenda.probatio.core.Outcome

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

/**
 * The adapter-level oracle for install-tool-surface-parity (spec 7).
 *
 * Written against the spec and the Step-1 contract BEFORE the adapter
 * implementation exists. Every scenario drives the `run` boundary (or
 * the pinned `private[cli]` seams) through the environment the
 * predecessor honours: `PATH` for the prerequisite probe,
 * `PROBATIO_SCHEMA_DIR` for the skill/adapter sources, `PWD` for the
 * `.` project-root default — the deterministic substitution the design
 * prescribes in place of a wall clock or a real process table.
 *
 * Covers: the four requirements' twelve scenarios (the consumer-surface
 * scenario lives in `CliHelpSpec`) plus the `dry-run-writes-nothing`
 * and `install-covers-every-declared-directory` properties. The
 * `surface-parity-with-the-predecessor` property lives in
 * `InstallSurfaceParitySpec` — the predecessor subprocess machinery
 * mirrors `ChainStateParitySpec`.
 *
 * spec: install-tool-surface-parity — all requirements
 */
final class InstallToolSurfaceParitySpec extends ProbatioCliSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(100))

  // ── helpers ────────────────────────────────────────────────────────

  private def runSkills(
    args: Array[String],
    env: Map[String, String]
  ): (String, String, Outcome[Int]) =
    StdoutCapture.captureBoth(InstallSkillsCmd.run(args, env))

  private def runHooks(
    args: Array[String],
    env: Map[String, String]
  ): (String, String, Outcome[Int]) =
    StdoutCapture.captureBoth(InstallHooksCmd.run(args, env))

  private val repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find(p => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  private val schemaDir: Path =
    repoRoot.resolve("openspec/schemas/verified-scala3")

  private val adaptersDir: Path =
    schemaDir.resolve("hooks/adapters")

  /** A PATH dir holding executable stubs for `names` minus `missing`. */
  private def stubPathDir(root: Path, missing: List[String]): Path =
    val bin: Path = Files.createDirectories(root.resolve("pathbin"))
    InstallSurface.declaredPrerequisites.filterNot(missing.contains).foreach { (n: String) =>
      val p: Path = bin.resolve(n)
      Files.writeString(p, "#!/usr/bin/env bash\nexit 0\n", StandardCharsets.UTF_8)
      p.toFile.setExecutable(true)
    }
    bin

  /** A schema tree carrying `skills` directories for `skillNames`. */
  private def schemaWithSkills(root: Path, skillNames: List[String]): Path =
    val skills: Path = Files.createDirectories(root.resolve("skills"))
    skillNames.foreach { (n: String) =>
      val dir: Path = Files.createDirectories(skills.resolve(n))
      Files.writeString(dir.resolve("SKILL.md"), s"# $n\n", StandardCharsets.UTF_8)
    }
    root

  /** A project root with `openspec/` and the named harness markers. */
  private def hookProject(root: Path, markers: Set[String]): Path =
    Files.createDirectories(root.resolve("openspec"))
    markers.foreach((h: String) => Files.createDirectories(root.resolve("." + h)))
    root

  /** Sorted (relpath, kind-or-digest) entries — the tree's comparable digest. */
  private def digestTree(root: Path): List[(String, String)] =
    Using.resource(Files.walk(root)) { (walk: java.util.stream.Stream[Path]) =>
      walk
        .iterator()
        .asScala
        .toList
        .filter((p: Path) => p != root)
        .sortBy((p: Path) => root.relativize(p).toString)
        .map { (p: Path) =>
          val rel: String = root.relativize(p).toString
          if Files.isDirectory(p) then (rel + "/", "dir")
          else (rel, SubcommandWiring.sha256Hex(Files.readAllBytes(p)))
        }
    }

  private def envFor(root: Path, extra: (String, String)*): Map[String, String] =
    Map(
      "PATH"                -> sys.env.getOrElse("PATH", ""), // scalafix:ok DisableSyntax.NoSysEnv
      "PWD"                 -> root.toString,
      "PROBATIO_SCHEMA_DIR" -> schemaDir.toString
    ) ++ extra

  // ═══════════════════════════════════════════════════════════════════
  // Requirement: The skill installer probes the declared prerequisite set
  // ═══════════════════════════════════════════════════════════════════

  // spec: install-tool-surface-parity — Scenario: Happy path — all prerequisites present terminates clean
  test("probe: all prerequisites present terminates clean"):
    LiveFactFixtures.withTempDir("install-probe-all") { (fx: Path) =>
      val bin: Path = stubPathDir(fx, missing = Nil)
      val (out, err, oc) = runSkills(
        Array("--check-installed"),
        Map("PATH" -> bin.toString)
      )
      assertEquals(Outcome.toExitCode(oc), 0, s"expected clean status; stderr: $err")
      InstallSurface.declaredPrerequisites.foreach { (n: String) =>
        assert((out + err).contains(n), s"probe output missing prerequisite '$n'")
      }
      assert(
        (out + err).contains("present"),
        "probe output must report each prerequisite as present"
      )
      assert(
        !(out + err).contains("MISSING"),
        "an all-present probe must not report any prerequisite missing"
      )
    }

  // `command -v` scans PATH entries in order and stops at the first hit —
  // a binary in a later entry is present, not missing.
  test("probe: an executable in a later PATH entry is found"):
    LiveFactFixtures.withTempDir("install-probe-later") { (fx: Path) =>
      val bin: Path   = stubPathDir(fx, missing = Nil)
      val empty: Path = Files.createDirectories(fx.resolve("empty-bin"))
      val (out, err, oc) = runSkills(
        Array("--check-installed"),
        Map("PATH" -> s"$empty:$bin")
      )
      assertEquals(
        Outcome.toExitCode(oc),
        0,
        s"stubs in the second PATH entry must be found; stderr: $err"
      )
      assert(
        !(out + err).contains("MISSING"),
        "a later-PATH-entry executable must not be reported missing"
      )
    }

  // spec: install-tool-surface-parity — Scenario: Adversarial — a missing prerequisite is reported and not counted as present
  test("probe: a missing prerequisite is reported and counted once"):
    LiveFactFixtures.withTempDir("install-probe-miss") { (fx: Path) =>
      val bin: Path = stubPathDir(fx, missing = List("jq"))
      val (out, err, oc) = runSkills(
        Array("--check-installed"),
        Map("PATH" -> bin.toString)
      )
      val combined: String = out + err
      assertEquals(
        Outcome.toExitCode(oc),
        1,
        "a missing prerequisite must terminate with the finding status"
      )
      assert(combined.contains("jq"), "the missing prerequisite must be named")
      assert(
        combined.contains("jq — MISSING"),
        s"the missing prerequisite must be marked MISSING, not present: $combined"
      )
      assert(
        combined.contains("1 prerequisite"),
        s"the missing count must be exactly one: $combined"
      )
      assert(
        !combined.contains("all prerequisites present"),
        "a report with a missing prerequisite must not claim all present"
      )
    }

  // spec: install-tool-surface-parity — Scenario: Consumer surface — the probe names each prerequisite it checked
  test("probe: the output names every declared prerequisite"):
    LiveFactFixtures.withTempDir("install-probe-names") { (fx: Path) =>
      val bin: Path = stubPathDir(fx, missing = List("bats", "shfmt"))
      val (out, err, _) = runSkills(
        Array("--check-installed"),
        Map("PATH" -> bin.toString)
      )
      val combined: String    = out + err
      val lines: List[String] = combined.split("\n").toList
      InstallSurface.declaredPrerequisites.foreach { (n: String) =>
        assert(
          lines.exists { (l: String) =>
            l.contains(s"install-skills: $n — present") ||
            l.contains(s"install-skills: $n — MISSING")
          },
          s"probe output must report '$n' with its status on one line"
        )
      }
    }

  // ═══════════════════════════════════════════════════════════════════
  // Requirement: The skill installer writes to every declared agent dir
  // ═══════════════════════════════════════════════════════════════════

  // spec: install-tool-surface-parity — Scenario: Happy path — three agent directories each receive every skill
  test("install: every declared agent directory receives every skill"):
    LiveFactFixtures.withTempDir("install-skills-all") { (fx: Path) =>
      val schema: Path = schemaWithSkills(fx.resolve("schema"), List("skill-a", "skill-b"))
      val proj: Path   = Files.createDirectories(fx.resolve("proj"))
      val env: Map[String, String] =
        Map("PROBATIO_SCHEMA_DIR" -> schema.toString, "PWD" -> proj.toString)
      val (out, err, oc) = runSkills(Array(proj.toString), env)
      assertEquals(Outcome.toExitCode(oc), 0, s"install failed: $out$err")
      InstallSurface.skillAgentDirs.foreach { (d: String) =>
        List("skill-a", "skill-b").foreach { (s: String) =>
          assert(
            Files.isRegularFile(proj.resolve(d).resolve(s).resolve("SKILL.md")),
            s"$s missing from $d"
          )
        }
      }
    }

  // spec: install-tool-surface-parity — Scenario: Adversarial — installing into a single directory is not the whole install
  test("install: a single-directory install is reported incomplete"):
    LiveFactFixtures.withTempDir("install-skills-one") { (fx: Path) =>
      val proj: Path = Files.createDirectories(fx.resolve("proj"))
      // Simulate the narrowed install: only .claude/skills received a skill.
      val onlyDir: Path = Files.createDirectories(proj.resolve(".claude/skills/skill-a"))
      Files.writeString(onlyDir.resolve("SKILL.md"), "# skill-a\n", StandardCharsets.UTF_8)
      val unwritten: List[String] =
        InstallSkillsCmd.unwrittenAgentDirs(proj, List("skill-a"))
      assertEquals(
        unwritten.sorted,
        List(".devin/skills", ".pi/skills"),
        "the coverage check must name the directories that were not written"
      )
    }

  // spec: install-tool-surface-parity — Scenario: Error path — an unwritable agent directory is could-not-determine
  test("install: an unwritable agent directory is could-not-determine"):
    LiveFactFixtures.withTempDir("install-skills-blocked") { (fx: Path) =>
      val schema: Path = schemaWithSkills(fx.resolve("schema"), List("skill-a"))
      val proj: Path   = Files.createDirectories(fx.resolve("proj"))
      // .claude exists as a regular file — .claude/skills cannot be created.
      Files.writeString(proj.resolve(".claude"), "not a directory", StandardCharsets.UTF_8)
      val env: Map[String, String] =
        Map("PROBATIO_SCHEMA_DIR" -> schema.toString, "PWD" -> proj.toString)
      val (out, err, oc) = runSkills(Array(proj.toString), env)
      oc match
        case Outcome.Undetermined(reason) =>
          assert(
            reason.contains(".claude"),
            s"the undetermined reason must name the unwritable directory: $reason"
          )
        case other =>
          fail(s"an unwritable agent directory must be could-not-determine, got $other ($out$err)")
    }

  // ═══════════════════════════════════════════════════════════════════
  // Requirement: The hook installer reports before it writes
  // ═══════════════════════════════════════════════════════════════════

  // spec: install-tool-surface-parity — Scenario: Happy path — the default invocation writes nothing
  test("hooks: the default invocation writes nothing"):
    LiveFactFixtures.withTempDir("hooks-dryrun") { (fx: Path) =>
      val proj: Path                     = hookProject(fx.resolve("proj"), Set("claude", "pi"))
      val before: List[(String, String)] = digestTree(proj)
      val (out, _, oc) = runHooks(
        Array("--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0, "the default invocation must run clean")
      assertEquals(
        digestTree(proj),
        before,
        "a dry run must leave the project tree byte-identical"
      )
      assert(
        out.contains("would"),
        s"the dry run must report what it would write: $out"
      )
      assert(
        out.contains("DRY RUN"),
        s"the dry run must name itself before any write report: $out"
      )
    }

  // spec: install-tool-surface-parity — Scenario: Happy path — the explicit invocation writes
  test("hooks: the explicit invocation writes"):
    LiveFactFixtures.withTempDir("hooks-apply") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("claude", "pi"))
      val (out, err, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0, s"--apply failed: $out$err")
      assert(
        !out.contains("DRY RUN"),
        s"an apply must not print the dry-run banner: $out"
      )
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "the pi adapter must be written"
      )
      assert(
        Files.isRegularFile(proj.resolve(".claude/settings.json")),
        "the claude settings must be written"
      )
    }

  // spec: install-tool-surface-parity — Scenario: Adversarial — a default invocation does not modify an existing configuration
  test("hooks: a default invocation does not modify an existing configuration"):
    LiveFactFixtures.withTempDir("hooks-dryrun-foreign") { (fx: Path) =>
      val proj: Path      = hookProject(fx.resolve("proj"), Set("claude"))
      val settings: Path  = proj.resolve(".claude/settings.json")
      val foreign: String = "{\"foreign\": \"configuration\"}\n"
      Files.writeString(settings, foreign, StandardCharsets.UTF_8)
      val (_, _, oc) = runHooks(
        Array("--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assertEquals(
        Files.readString(settings),
        foreign,
        "a dry run must not modify an existing configuration"
      )
    }

  // ═══════════════════════════════════════════════════════════════════
  // Requirement: The hook installer selects harnesses and refuses to
  // clobber
  // ═══════════════════════════════════════════════════════════════════

  // spec: install-tool-surface-parity — Scenario: Happy path — a named harness is wired alone
  test("hooks: a named harness is wired alone"):
    LiveFactFixtures.withTempDir("hooks-named") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("claude", "pi"))
      val (_, _, oc) = runHooks(
        Array("--agent", "pi", "--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "the named harness must be wired"
      )
      assert(
        !Files.exists(proj.resolve(".claude/settings.json")),
        "an un-named harness must not be wired"
      )
    }

  // spec: install-tool-surface-parity — Scenario: Happy path — no name wires every present harness
  test("hooks: no name wires every present harness"):
    LiveFactFixtures.withTempDir("hooks-all-present") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("claude", "pi"))
      val (_, _, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")))
      assert(Files.isRegularFile(proj.resolve(".claude/settings.json")))
      assert(
        !Files.exists(proj.resolve(".devin/hooks.v1.json")),
        "an absent harness must not be wired"
      )
    }

  // spec: install-tool-surface-parity — Scenario: Adversarial — an unrecognised configuration is not overwritten
  test("hooks: an unrecognised configuration is not overwritten"):
    LiveFactFixtures.withTempDir("hooks-foreign-devin") { (fx: Path) =>
      val proj: Path      = hookProject(fx.resolve("proj"), Set("devin"))
      val dst: Path       = proj.resolve(".devin/hooks.v1.json")
      val foreign: String = "{\"not\": \"ours\"}\n"
      Files.writeString(dst, foreign, StandardCharsets.UTF_8)
      val (out, err, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(
        Files.readString(dst),
        foreign,
        "an existing configuration must not be overwritten"
      )
      assert(
        (out + err).contains("hooks.v1.json"),
        s"the refusal must name the file: $out$err"
      )
      assertEquals(
        Outcome.toExitCode(oc),
        0,
        "the predecessor skips a foreign file and exits clean — parity holds the status"
      )
    }

  // ═══════════════════════════════════════════════════════════════════
  // Ring-5 surgical kills — mutants the parity-property-only shapes and
  // the message-adjacent branches could not distinguish
  // ═══════════════════════════════════════════════════════════════════

  test("probe: a non-executable PATH entry is missing"):
    LiveFactFixtures.withTempDir("install-probe-nonexec") { (fx: Path) =>
      val bin: Path = stubPathDir(fx, missing = Nil)
      bin.resolve("jq").toFile.setExecutable(false)
      val (out, err, oc) = runSkills(
        Array("--check-installed"),
        Map("PATH" -> bin.toString)
      )
      val combined: String = out + err
      assertEquals(Outcome.toExitCode(oc), 1)
      assert(
        combined.contains("jq"),
        s"a non-executable prerequisite must be reported missing, not present: $combined"
      )
    }

  test("install: a partially-written directory is still reported incomplete"):
    LiveFactFixtures.withTempDir("install-skills-partial") { (fx: Path) =>
      val proj: Path  = Files.createDirectories(fx.resolve("proj"))
      val piDir: Path = Files.createDirectories(proj.resolve(".pi/skills/skill-a"))
      Files.writeString(piDir.resolve("SKILL.md"), "# skill-a\n", StandardCharsets.UTF_8)
      val unwritten: List[String] =
        InstallSkillsCmd.unwrittenAgentDirs(proj, List("skill-a", "skill-b"))
      assert(
        unwritten.contains(".pi/skills"),
        s"a directory missing ANY declared skill is unwritten: $unwritten"
      )
    }

  test("install: a missing skills source is a finding naming it"):
    LiveFactFixtures.withTempDir("install-skills-nosrc") { (fx: Path) =>
      val schema: Path = Files.createDirectories(fx.resolve("schema"))
      val proj: Path   = Files.createDirectories(fx.resolve("proj"))
      val env: Map[String, String] =
        Map("PROBATIO_SCHEMA_DIR" -> schema.toString, "PWD" -> proj.toString)
      val (out, err, oc) = runSkills(Array(proj.toString), env)
      assertEquals(Outcome.toExitCode(oc), 1)
      assert(
        (out + err).contains("skills"),
        s"the finding must name the missing source: $out$err"
      )
    }

  test("install: an empty project-root argument is a finding"):
    LiveFactFixtures.withTempDir("install-skills-emptyarg") { (fx: Path) =>
      val proj: Path = Files.createDirectories(fx.resolve("proj"))
      val env: Map[String, String] =
        Map("PROBATIO_SCHEMA_DIR" -> schemaDir.toString, "PWD" -> proj.toString)
      val (_, _, oc) = runSkills(Array(""), env)
      assertEquals(Outcome.toExitCode(oc), 1)
    }

  test("hooks: -h prints usage and exits clean"):
    LiveFactFixtures.withTempDir("hooks-help") { (fx: Path) =>
      val proj: Path   = hookProject(fx.resolve("proj"), Set("pi"))
      val (out, _, oc) = runHooks(Array("-h"), envFor(proj))
      assertEquals(Outcome.toExitCode(oc), 0)
      // The long form takes the same parser branch — the predecessor's
      // `-h|--help) usage` alternative.
      val (outLong, _, ocLong) = runHooks(Array("--help"), envFor(proj))
      assertEquals(Outcome.toExitCode(ocLong), 0, "--help must exit clean")
      assertEquals(outLong, out, "--help must print the same usage as -h")
      assert(out.contains("install-hooks"), s"usage must name the tool: $out")
      assert(out.contains("--agent"), s"usage must document --agent: $out")
      assert(out.contains("--project"), s"usage must document --project: $out")
      assert(out.contains("--apply"), s"usage must document --apply: $out")
      val statusText: String =
        val idx: Int = out.toLowerCase.indexOf("exit")
        if idx < 0 then "" else out.substring(idx)
      assert(statusText.nonEmpty, s"usage must name the exit statuses: $out")
      assert(
        statusText.contains("0") && statusText.contains("1") && statusText.contains("2"),
        s"usage must name all three termination statuses: $out"
      )
    }

  test("hooks: a missing flag value is the predecessor's finding status"):
    LiveFactFixtures.withTempDir("hooks-missing-value") { (fx: Path) =>
      val proj: Path      = hookProject(fx.resolve("proj"), Set("pi"))
      val (_, errAgent, ocAgent) = runHooks(Array("--agent"), envFor(proj))
      assertEquals(Outcome.toExitCode(ocAgent), 1, "--agent without a value must exit 1")
      assert(
        errAgent.contains("--agent") && errAgent.contains("requires a value"),
        s"the diagnosis must name the flag: $errAgent"
      )
      val (_, errProj, ocProj) = runHooks(Array("--project"), envFor(proj))
      assertEquals(Outcome.toExitCode(ocProj), 1, "--project without a value must exit 1")
      assert(
        errProj.contains("--project") && errProj.contains("requires a value"),
        s"the diagnosis must name the flag: $errProj"
      )
    }

  test("hooks: an unrecognised argument names it and exits 2"):
    LiveFactFixtures.withTempDir("hooks-unknown-arg") { (fx: Path) =>
      val proj: Path      = hookProject(fx.resolve("proj"), Set("pi"))
      val (_, err, oc)    = runHooks(Array("--bogus"), envFor(proj))
      assertEquals(Outcome.toExitCode(oc), 2, "an unknown argument is the exit-2 undetermined")
      assert(err.contains("--bogus"), s"the diagnosis must name the token: $err")
    }

  test("hooks: a project without openspec/ is undetermined"):
    LiveFactFixtures.withTempDir("hooks-no-openspec") { (fx: Path) =>
      val proj: Path = Files.createDirectories(fx.resolve("proj"))
      Files.createDirectories(proj.resolve(".pi"))
      val (_, err, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 2)
      assert(err.contains("openspec"), s"the diagnosis must name the missing openspec/: $err")
    }

  test("hooks: --agent all wires every present harness"):
    LiveFactFixtures.withTempDir("hooks-agent-all") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("pi"))
      val (_, _, oc) = runHooks(
        Array("--agent", "all", "--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "--agent all must wire the present harness"
      )
    }

  test("hooks: an empty --agent value selects every present harness"):
    LiveFactFixtures.withTempDir("hooks-agent-empty") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("pi"))
      // The predecessor's `AGENT="${2:-}"` leaves an empty value, and the
      // empty/`all` branch discovers the present harnesses.
      val (_, _, oc) = runHooks(
        Array("--agent", "", "--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "an empty --agent must wire the present harnesses like 'all'"
      )
    }

  test("hooks: --project selects the project root over PWD"):
    LiveFactFixtures.withTempDir("hooks-project-wins") { (fx: Path) =>
      val proj: Path  = hookProject(fx.resolve("proj"), Set("pi"))
      val other: Path = Files.createDirectories(fx.resolve("other"))
      val (_, _, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(other)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "--project must win over PWD"
      )
      assert(!Files.exists(other.resolve(".pi")))
    }

  test("hooks: no --project resolves the project from PWD"):
    LiveFactFixtures.withTempDir("hooks-pwd-project") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("pi"))
      val (_, _, oc) = runHooks(Array("--apply"), envFor(proj))
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "PWD must supply the default project root"
      )
    }

  test("hooks: a devin harness with no existing configuration is wired"):
    LiveFactFixtures.withTempDir("hooks-devin-fresh") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("devin"))
      val (_, _, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(
        Files.isRegularFile(proj.resolve(".devin/hooks.v1.json")),
        "an absent devin configuration must be written, not skipped"
      )
    }

  test("hooks: an apply merges into an existing configuration without destroying it"):
    LiveFactFixtures.withTempDir("hooks-apply-foreign") { (fx: Path) =>
      val proj: Path     = hookProject(fx.resolve("proj"), Set("claude"))
      val settings: Path = proj.resolve(".claude/settings.json")
      val foreign: String =
        "{\"keep\": true, \"hooks\": {\"SessionStart\": [{\"matcher\": \"\", \"hooks\": [{\"type\": \"command\", \"command\": \"keep-me\"}]}]}}\n"
      Files.writeString(settings, foreign, StandardCharsets.UTF_8)
      val (_, _, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      val merged: String = Files.readString(settings)
      // Structural, not substring: the merge must produce valid JSON
      // that keeps the foreign key, the foreign hook, and gains every
      // adapter-declared SessionStart command.
      val reparsed: ujson.Value = ujson.read(merged)
      assertEquals(
        reparsed.obj.get("keep").map((v: ujson.Value) => v.bool),
        Some(true),
        s"foreign keys must survive the merge: $merged"
      )
      val commands: List[String] =
        reparsed("hooks")("SessionStart").arr.toList.flatMap { (e: ujson.Value) =>
          e.obj
            .get("hooks")
            .toList
            .flatMap(_.arr.toList)
            .flatMap(_.obj.get("command").map(_.str))
        }
      assert(commands.contains("keep-me"), s"existing hook entries must survive: $merged")
      assert(commands.exists(_.contains("gate.sh")), s"adapter hooks must be merged in: $merged")
      // The merge copies every fragment entry key, not only `hooks` —
      // the adapter's PreToolUse matcher is the observable.
      assert(
        merged.contains("Bash|Edit|Write|MultiEdit"),
        s"the fragment's matcher keys must survive the merge: $merged"
      )
    }

  // A SessionStart entry holding a foreign hook AND an adapter command:
  // dedup must still see the adapter command as already present (the
  // predecessor's python scans every hook in the entry).
  test("hooks: a mixed multi-hook entry still deduplicates"):
    LiveFactFixtures.withTempDir("hooks-mixed-entry") { (fx: Path) =>
      val proj: Path     = hookProject(fx.resolve("proj"), Set("claude"))
      val settings: Path = proj.resolve(".claude/settings.json")
      val adapterCmd: String =
        "\\\"$CLAUDE_PROJECT_DIR/openspec/schemas/verified-scala3/hooks/gate.sh\\\" --event session-start --format hook-json --session \\\"$CLAUDE_CODE_SESSION_ID\\\""
      val foreign: String =
        s"{\"hooks\": {\"SessionStart\": [{\"matcher\": \"\", \"hooks\": [{\"type\": \"command\", \"command\": \"keep-me\"}, {\"type\": \"command\", \"command\": \"$adapterCmd\"}]}]}}\n"
      Files.writeString(settings, foreign, StandardCharsets.UTF_8)
      val (_, _, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      val merged: String = Files.readString(settings)
      assertEquals(
        "session-start".r.findAllIn(merged).length,
        1,
        s"the adapter command already inside a mixed entry must not be re-added: $merged"
      )
      assert(merged.contains("keep-me"), s"the foreign hook must survive: $merged")
    }

  test("hooks: a second apply adds no duplicates"):
    LiveFactFixtures.withTempDir("hooks-apply-twice") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("claude"))
      val (_, _, oc1) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc1), 0)
      val (out2, err2, oc2) = runHooks(
        Array("--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc2), 0)
      val merged: String = Files.readString(proj.resolve(".claude/settings.json"))
      val commands: Int =
        "gate\\.sh".r.findAllIn(merged).length
      assertEquals(commands, 6, s"re-applying must not duplicate hook commands: $merged")
      assert(
        (out2 + err2).contains("already present"),
        s"the second apply must report the dedup: $out2$err2"
      )
    }

  // ═══════════════════════════════════════════════════════════════════
  // Ring-8 adversarial findings — divergences the fresh-context review
  // isolated against the predecessor scripts
  // ═══════════════════════════════════════════════════════════════════

  // Ring-8 finding: `for a in $AGENT` word-splits — `--agent "claude pi"`
  // wires both harnesses, it is not one unknown name.
  test("hooks: a multi-word --agent wires every named harness"):
    LiveFactFixtures.withTempDir("hooks-agent-multi") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("claude", "pi"))
      val (_, err, oc) = runHooks(
        Array("--agent", "claude pi", "--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0, s"multi-word --agent failed: $err")
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "pi must be wired by a multi-word --agent"
      )
      assert(
        Files.isRegularFile(proj.resolve(".claude/settings.json")),
        "claude must be wired by a multi-word --agent"
      )
    }

  test("hooks: --agent with surrounding whitespace wires the named harness"):
    LiveFactFixtures.withTempDir("hooks-agent-ws") { (fx: Path) =>
      val proj: Path = hookProject(fx.resolve("proj"), Set("pi"))
      val (_, _, oc) = runHooks(
        Array("--agent", " pi ", "--apply", "--project", proj.toString),
        envFor(proj)
      )
      assertEquals(Outcome.toExitCode(oc), 0)
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "shell word-splitting strips whitespace — ' pi ' wires pi"
      )
    }

  // Ring-8 finding: bash's `*/` glob never matches dot-directories.
  test("install: a dot-directory under skills/ is not a skill"):
    LiveFactFixtures.withTempDir("install-skills-dotdir") { (fx: Path) =>
      val schema: Path = schemaWithSkills(fx.resolve("schema"), List("skill-a"))
      Files.createDirectories(schema.resolve("skills/.hidden"))
      val proj: Path = Files.createDirectories(fx.resolve("proj"))
      val env: Map[String, String] =
        Map("PROBATIO_SCHEMA_DIR" -> schema.toString, "PWD" -> proj.toString)
      val (_, err, oc) = runSkills(Array(proj.toString), env)
      assertEquals(Outcome.toExitCode(oc), 0, s"install failed on a dot-dir: $err")
      InstallSurface.skillAgentDirs.foreach { (d: String) =>
        assert(
          !Files.exists(proj.resolve(d).resolve(".hidden")),
          s"the dot-directory must not be installed into $d"
        )
      }
    }

  // Ring-8 finding: an empty skills/ iterates the literal `*/` glob and
  // the predecessor dies on `cp` — exit 1, never a clean empty install.
  test("install: an empty skills source is the predecessor's finding"):
    LiveFactFixtures.withTempDir("install-skills-empty") { (fx: Path) =>
      val schema: Path = fx.resolve("schema")
      Files.createDirectories(schema.resolve("skills"))
      val proj: Path = Files.createDirectories(fx.resolve("proj"))
      val env: Map[String, String] =
        Map("PROBATIO_SCHEMA_DIR" -> schema.toString, "PWD" -> proj.toString)
      val (_, _, oc) = runSkills(Array(proj.toString), env)
      assertEquals(Outcome.toExitCode(oc), 1)
    }

  // Ring-8 finding: a skill directory without SKILL.md is the
  // predecessor's `cp` failure — exit 1, not could-not-determine.
  test("install: a skill without SKILL.md is the predecessor's finding"):
    LiveFactFixtures.withTempDir("install-skills-nodoc") { (fx: Path) =>
      val schema: Path = fx.resolve("schema")
      Files.createDirectories(schema.resolve("skills/skill-a"))
      val proj: Path = Files.createDirectories(fx.resolve("proj"))
      val env: Map[String, String] =
        Map("PROBATIO_SCHEMA_DIR" -> schema.toString, "PWD" -> proj.toString)
      val (_, _, oc) = runSkills(Array(proj.toString), env)
      assertEquals(Outcome.toExitCode(oc), 1)
    }

  // Ring-8 finding: the adapter sources resolve from the TARGET project,
  // not the invocation cwd — `--project` works from an unrelated cwd.
  test("hooks: --project supplies the schema source from an unrelated cwd"):
    LiveFactFixtures.withTempDir("hooks-project-schema") { (fx: Path) =>
      val proj: Path = fx.resolve("proj")
      val adapters: Path = Files.createDirectories(
        proj.resolve("openspec/schemas/verified-scala3/hooks/adapters/pi")
      )
      Files.writeString(
        adapters.resolve("verified-scala3-gate.ts"),
        "// pi adapter\n",
        StandardCharsets.UTF_8
      )
      Files.createDirectories(proj.resolve(".pi"))
      val elsewhere: Path = Files.createDirectories(fx.resolve("elsewhere"))
      val (_, err, oc) = runHooks(
        Array("--apply", "--project", proj.toString),
        Map("PWD" -> elsewhere.toString)
      )
      assertEquals(
        Outcome.toExitCode(oc),
        0,
        s"--project must resolve adapters relative to the target: $err"
      )
      assert(
        Files.isRegularFile(proj.resolve(".pi/extensions/verified-scala3-gate.ts")),
        "the vendored adapter must be wired into the target project"
      )
    }

  // Ring-8 finding: the dispatcher's blanket `--help` interception must
  // not fire for the installer subcommands — the predecessor's parser
  // decides, and `--help` in a value position is a value.
  test("dispatch: install-skills --help is the predecessor's invalid-option finding"):
    val code: Int = ProbatioMain.dispatch(
      invocation("probatio"),
      ProgramArgs.fromFixture(List("install-skills", "--help"))
    )
    assertEquals(code, 1, "install-skills has no --help — the predecessor dies in mkdir")

  test("dispatch: install-hooks --bogus --help rejects before reaching help"):
    val code: Int = ProbatioMain.dispatch(
      invocation("probatio"),
      ProgramArgs.fromFixture(List("install-hooks", "--bogus", "--help"))
    )
    assertEquals(code, 2, "an unknown argument precedes --help — the predecessor exits 2")

  test("dispatch: install-hooks --project --help consumes --help as the value"):
    val code: Int = ProbatioMain.dispatch(
      invocation("probatio"),
      ProgramArgs.fromFixture(List("install-hooks", "--project", "--help"))
    )
    assertEquals(
      code,
      2,
      "--help as --project's value resolves to a directory without openspec/ — exit 2"
    )

  test("dispatch: install-hooks --agent --help consumes --help as the value"):
    // sbt runs in the repo root, so openspec/ resolves and the
    // "--help" agent word is the predecessor's unknown-agent diagnostic.
    val code: Int = ProbatioMain.dispatch(
      invocation("probatio"),
      ProgramArgs.fromFixture(List("install-hooks", "--agent", "--help"))
    )
    assertEquals(code, 0, "--help as --agent's value is an unknown agent — exit 0")

  test("dispatch: install-hooks -h precedes a later bad argument"):
    val helpFirst: Int = ProbatioMain.dispatch(
      invocation("probatio"),
      ProgramArgs.fromFixture(List("install-hooks", "-h", "--bogus"))
    )
    assertEquals(helpFirst, 0, "-h reached first prints usage — exit 0")
    val bogusFirst: Int = ProbatioMain.dispatch(
      invocation("probatio"),
      ProgramArgs.fromFixture(List("install-hooks", "--bogus", "-h"))
    )
    assertEquals(bogusFirst, 2, "the bad argument is named before -h is reached — exit 2")

  test("dispatch: install-skills <root> --help installs — the extra token is ignored"):
    LiveFactFixtures.withTempDir("dispatch-skills-help") { (fx: Path) =>
      val root: Path = fx.resolve("proj")
      val skillDir: Path = Files.createDirectories(
        root.resolve("openspec/schemas/verified-scala3/skills/skill-a")
      )
      Files.writeString(skillDir.resolve("SKILL.md"), "# skill-a\n", StandardCharsets.UTF_8)
      val code: Int = ProbatioMain.dispatch(
        invocation("probatio"),
        ProgramArgs.fromFixture(List("install-skills", root.toString, "--help"))
      )
      assertEquals(code, 0, "the predecessor installs into $1 and ignores the rest")
      assert(
        Files.isDirectory(root.resolve(".claude/skills")),
        "the install must have run — a help intercept would write nothing"
      )
    }

  private def invocation(name: String): InvocationName =
    InvocationName.fromRuntime(name) match
      case Right(v)  => v
      case Left(err) => fail(s"invalid invocation name '$name': $err")

  // ═══════════════════════════════════════════════════════════════════
  // Property: dry-run-writes-nothing
  // ═══════════════════════════════════════════════════════════════════

  enum ConfigKind:
    case Absent, Generated, Foreign

  /**
   * genProjectFixture — constructive over the harnesses present in the
   * project (0–3 markers) and each present harness's configuration state
   * drawn from {absent, generated-by-installer, foreign}.
   */
  def genProjectFixture: Gen[List[(String, ConfigKind)]] =
    val kindGen: Gen[ConfigKind] =
      Gen.element(ConfigKind.Absent, List(ConfigKind.Generated, ConfigKind.Foreign))
    for
      present <- Gen.element(
        List.empty[String],
        List(
          List("claude"),
          List("pi"),
          List("devin"),
          List("claude", "pi"),
          List("claude", "devin"),
          List("pi", "devin"),
          List("claude", "pi", "devin")
        )
      )
      kinds <- kindGen.list(hedgehog.Range.singleton(present.length))
    yield present.zip(kinds)

  /** Materialise a project fixture: openspec/ + markers + config files. */
  private def materialiseProject(root: Path, configs: List[(String, ConfigKind)]): Path =
    Files.createDirectories(root.resolve("openspec"))
    configs.foreach { case (harness: String, kind: ConfigKind) =>
      val marker: Path = Files.createDirectories(root.resolve("." + harness))
      kind match
        case ConfigKind.Absent => ()
        case ConfigKind.Foreign =>
          val dst: Path = marker.resolve(configFile(harness))
          Option(dst.getParent).foreach((par: Path) => Files.createDirectories(par))
          Files.writeString(
            dst,
            s"{\"foreign\": \"$harness\"}\n",
            StandardCharsets.UTF_8
          )
        case ConfigKind.Generated =>
          val dst: Path = marker.resolve(configFile(harness))
          Option(dst.getParent).foreach((par: Path) => Files.createDirectories(par))
          adapterContent(harness).foreach { (src: Path) =>
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          }
    }
    root

  /** The configuration file each harness wiring writes (destination name). */
  private def configFile(harness: String): String = harness match
    case "claude" => "settings.json"
    case "pi"     => "extensions/verified-scala3-gate.ts"
    case "devin"  => "hooks.v1.json"
    case _        => "config.json"

  /**
   * The adapter source for a harness's "generated" configuration — the
   * real schema's adapter files (what the installer copies/merges).
   */
  private def adapterContent(harness: String): Option[Path] = harness match
    case "claude" =>
      val p: Path = adaptersDir.resolve("claude.settings.json")
      if Files.isRegularFile(p) then Some(p) else None
    case "pi" =>
      val p: Path = adaptersDir.resolve("pi/verified-scala3-gate.ts")
      if Files.isRegularFile(p) then Some(p) else None
    case "devin" =>
      val p: Path = adaptersDir.resolve("devin.hooks.v1.json")
      if Files.isRegularFile(p) then Some(p) else None
    case _ => None

  // spec: install-tool-surface-parity — Property: dry-run-writes-nothing
  property("a dry run writes nothing", coverConfig):
    for configs <- genProjectFixture.forAll
    yield
      val unchanged: Boolean = LiveFactFixtures.withTempDir("install-dryrun-prop") { (fx: Path) =>
        val proj: Path                     = materialiseProject(fx.resolve("proj"), configs)
        val before: List[(String, String)] = digestTree(proj)
        val _: Outcome[Int] = InstallHooksCmd.runInstaller(
          adaptersDir,
          proj,
          InstallTarget.AllPresentHarnesses,
          InstallMode.DryRun
        )
        digestTree(proj) == before
      }
      Result
        .assert(unchanged)
        .log(s"dry run modified the tree: $configs")

  // ═══════════════════════════════════════════════════════════════════
  // Property: install-covers-every-declared-directory
  // ═══════════════════════════════════════════════════════════════════

  /** genSkillSet — skill document sets of size 1–6, unique names. */
  def genSkillSet: Gen[List[String]] =
    Gen
      .int(hedgehog.Range.linear(1, 6))
      .map((n: Int) => (1 to n).map((i: Int) => s"skill-$i").toList)

  // spec: install-tool-surface-parity — Property: install-covers-every-declared-directory
  property("install covers every declared directory", coverConfig):
    for
      skills  <- genSkillSet.forAll
      configs <- genProjectFixture.forAll
    yield
      val covered: Boolean = LiveFactFixtures.withTempDir("install-cover-prop") { (fx: Path) =>
        val schema: Path = schemaWithSkills(fx.resolve("schema"), skills)
        val proj: Path   = materialiseProject(fx.resolve("proj"), configs)
        val oc: Outcome[Int] =
          InstallSkillsCmd.installEverywhere(schema.resolve("skills"), proj)
        oc match
          case Outcome.Ran(_) =>
            InstallSurface.skillAgentDirs.forall { (d: String) =>
              skills.forall((s: String) => Files.isRegularFile(proj.resolve(d).resolve(s).resolve("SKILL.md")))
            } && InstallSkillsCmd.unwrittenAgentDirs(proj, skills).isEmpty
          case _ => false
      }
      Result
        .assert(covered)
        .log(s"install did not cover every declared directory: skills=$skills configs=$configs")

end InstallToolSurfaceParitySpec
