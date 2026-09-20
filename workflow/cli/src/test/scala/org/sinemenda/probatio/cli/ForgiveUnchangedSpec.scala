package org.sinemenda.probatio.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.sinemenda.probatio.core.Outcome
import scala.sys.process.*

/**
 * Direct tests for the forgive-unchanged oracle (spec 9): the batched
 * `git diff --name-only` membership rule must answer exactly what the
 * predecessor's per-row `git diff --quiet <baseline> HEAD -- <artifact>`
 * answers — and any artifact the membership rule cannot model must take
 * the literal command. Every verdict below was verified against a real
 * git invocation; the fixture's second commit changes `tracked.txt`,
 * `tests/x`, `q`, and `a/b`.
 *
 * spec: chain-state-attribution — Property: forgive-unchanged agrees with git diff
 * spec: native-gate-delivery — Requirement: the per-turn tool's start-up latency is measured and the forgive oracle is batched without changing observable behavior
 */
final class ForgiveUnchangedSpec extends ProbatioCliSuite:

  // ── Fixture ─────────────────────────────────────────────────────────

  private def git(dir: Path, args: String*): Unit =
    val cmd: List[String] = "git" :: "-C" :: dir.toString :: args.toList
    val exit: Int         = cmd.!(ProcessLogger(_ => (), _ => ()))
    assertEquals(exit, 0, s"git ${args.mkString(" ")} must succeed")

  private def headSha(dir: Path): String =
    List("git", "-C", dir.toString, "rev-parse", "HEAD")
      .!!(ProcessLogger(_ => (), _ => ()))
      .trim

  /**
   * A repository whose baseline commit contains `tracked.txt`,
   * `untouched.txt`, `q`, `!`, `tests/x`, `a/b` and whose HEAD commit
   * modifies `tracked.txt`, `tests/x`, `q`, `a/b`.
   */
  private def repoWithChanges(): (Path, String) =
    val dir: Path = Files.createTempDirectory("forgive-repo")
    git(dir, "init", "-q")
    List("tracked.txt", "untouched.txt", "q", "!", "tests/x", "a/b").foreach { (rel: String) =>
      val p: Path = dir.resolve(rel)
      val parent: Path = p.getParent
      if parent != null then { val _: Path = Files.createDirectories(parent); () }
      val _: Path = Files.write(p, "v1".getBytes(StandardCharsets.UTF_8))
    }
    git(dir, "add", "-A")
    git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
    val base: String = headSha(dir)
    List("tracked.txt", "tests/x", "q", "a/b").foreach { (rel: String) =>
      val _: Path = Files.write(dir.resolve(rel), "v2".getBytes(StandardCharsets.UTF_8))
    }
    git(dir, "add", "-A")
    git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "second")
    (dir, base)

  private def predicateIn(dir: Path): (String, String) => Boolean =
    SubcommandWiring.forgivePredicate(dir.resolve("evidence-ledger.jsonl").toString)

  // ── Batched membership verdicts ─────────────────────────────────────

  test("a changed artifact is not forgiven; an unchanged one is"):
    val (dir, base) = repoWithChanges()
    val pred = predicateIn(dir)
    assertEquals(pred(base, "tracked.txt"), false, "a changed artifact is 'changed'")
    assertEquals(pred(base, "untouched.txt"), true, "an unchanged artifact is forgivable")
    assertEquals(pred(base, "q"), false, "a changed single-char path is 'changed'")

  test("directory-boundary matching: 'tests' changes via tests/x, 'tests2' does not"):
    val (dir, base) = repoWithChanges()
    val pred = predicateIn(dir)
    assertEquals(pred(base, "tests"), false, "a changed file under the directory marks it changed")
    assertEquals(pred(base, "tests2"), true, "a raw-prefix sibling is NOT a directory match")
    assertEquals(pred(base, "a"), false, "'a/b' changed marks directory 'a' changed")

  test("a raw prefix that is not a directory boundary does not match"):
    val (dir, base) = repoWithChanges()
    val pred = predicateIn(dir)
    // 'test' is a strict prefix of 'tests/x' — literal `git diff -- test`
    // reports a clean diff; a mutant matching on startsWith(p) without the
    // '/' boundary would call it changed.
    assertEquals(pred(base, "test"), true, "a raw-prefix artifact is unchanged")
    assertEquals(pred(base, "tracked"), true, "'tracked.txt' is not under directory 'tracked'")

  test("a clean baseline forgives everything, through either path"):
    val (dir, _) = repoWithChanges()
    val pred     = predicateIn(dir)
    val head     = headSha(dir)
    // BASELINE == HEAD: every pathspec's diff is clean → forgiven.
    List("tracked.txt", "tests", "*", ".", ":").foreach { (artifact: String) =>
      assertEquals(pred(head, artifact), true, s"a clean diff forgives '$artifact'")
    }

  test("an unknown baseline is 'changed', never 'unchanged'"):
    val (dir, _) = repoWithChanges()
    val pred     = predicateIn(dir)
    assertEquals(
      pred("0000000000000000000000000000000000000000", "tracked.txt"),
      false,
      "a baseline git cannot resolve must not forgive the row"
    )

  test("a ledger outside any repository forgives nothing"):
    val outside: Path = Files.createTempDirectory("forgive-norepo")
    val pred          = predicateIn(outside)
    assertEquals(pred("abc1234", "a"), false, "no repository → no forgiveness")

  // ── Literal-fallback pathspecs ──────────────────────────────────────
  // For each artifact below the literal `git diff --quiet` verdict and
  // the batched membership answer DIFFER (literal: changed/error → false;
  // membership: no literal match → true). A test that only exercised the
  // batched branch would accept a mutation that drops the guard.

  test("non-plain pathspecs take the predecessor's literal command"):
    val (dir, base) = repoWithChanges()
    val pred = predicateIn(dir)
    List(
      ".",                  // whole tree — changed
      "./tracked.txt",      // git normalizes ./ — the file changed
      "../outside",         // outside the repo — git errors
      "/definitely/absent", // absolute path — git errors
      "tests/",             // trailing slash — directory changed
      "*",                  // glob — matches changes
      "?",                  // glob — matches the changed single-char path 'q'
      ":",                  // root magic — matches changes
      "a/./b",              // git normalizes to a/b — changed
      ""                    // empty pathspec — git errors
    ).foreach { (artifact: String) =>
      assertEquals(
        pred(base, artifact),
        false,
        s"literal `git diff --quiet base HEAD -- '$artifact'` reports changed — the batched membership rule must not answer 'unchanged'"
      )
    }
    // An exclude-only pathspec matches nothing positive — the literal
    // command reports a clean diff; batched membership happens to agree.
    assertEquals(
      pred(base, "!x"),
      true,
      "literal `git diff --quiet base HEAD -- '!x'` is clean — an exclude-only pathspec is forgiven"
    )

  // ── Guard truth table ───────────────────────────────────────────────

  test("isPlainPathspec truth table: plain relatives in, everything else out"):
    List(
      "tracked.txt", "tests/x", "a-b_c.txt", "deep/nested/dir/f.md",
      "..x", "x..y", "é/ü.txt", "a..b/c.d"
    ).foreach { (artifact: String) =>
      assert(
        SubcommandWiring.isPlainPathspec(artifact),
        s"'$artifact' is a plain relative path — batched membership is faithful"
      )
    }
    List(
      "", ".", "..", "/abs", "dir/", "./x", "a/./b", "a/../b", "x/..", "x/.",
      "*", "a*", "?", "[", ":", "!", "sub?", "a:b", "x[!y]"
    ).foreach { (artifact: String) =>
      assert(
        !SubcommandWiring.isPlainPathspec(artifact),
        s"'$artifact' must take the literal per-row command"
      )
    }

  // ── Shared adapter edges (mutation survivors from pre-existing code) ─

  test("readLedgerFile names each unreadable-file reason"):
    val dir: Path = Files.createTempDirectory("wiring-readledger")
    SubcommandWiring.readLedgerFile(dir.resolve("absent.jsonl").toString) match
      case Outcome.Undetermined(reason) =>
        assert(reason.contains("no ledger at"), s"a missing file is named: $reason")
      case other => fail(s"a missing ledger is undetermined, got $other")
    SubcommandWiring.readLedgerFile(dir.toString) match
      case Outcome.Undetermined(reason) =>
        assert(reason.contains("is not a regular file"), s"a directory is named: $reason")
      case other => fail(s"a directory ledger is undetermined, got $other")
    val malformed: Path = dir.resolve("malformed.jsonl")
    val _: Path = Files.write(malformed, Array[Byte](0xC3.toByte, 0x28)) // invalid UTF-8
    SubcommandWiring.readLedgerFile(malformed.toString) match
      case Outcome.Undetermined(reason) =>
        assert(reason.contains("could not read"), s"a mid-read failure is named: $reason")
      case other => fail(s"a malformed ledger is undetermined, got $other")

  test("readTextFile maps a malformed-UTF-8 file to a named Left"):
    val dir: Path       = Files.createTempDirectory("wiring-readtext")
    val malformed: Path = dir.resolve("bad.txt")
    val _: Path = Files.write(malformed, Array[Byte](0xC3.toByte, 0x28))
    SubcommandWiring.readTextFile(malformed) match
      case Left(reason) =>
        assert(reason.contains("could not read"), s"the mid-read failure is named: $reason")
      case Right(_) => fail("a malformed file must not read clean")

  test("shellParses accepts well-formed shell and rejects a syntax error"):
    assert(SubcommandWiring.shellParses("true"), "'true' parses")
    assert(!SubcommandWiring.shellParses("if ("), "'if (' does not parse")

  test("executeCaptured records a positive wall time for a sleeping command"):
    val dir: Path = Files.createTempDirectory("wiring-captured")
    val (exit, _, wallMs) = SubcommandWiring.executeCaptured(dir, "sleep 0.2")
    assertEquals(exit, 0, "sleep exits clean")
    assert(wallMs >= 50, s"a 200ms command measures a positive wall time, got $wallMs")

  test("appendLedgerLine separates a ledger that lacks a trailing newline"):
    val dir: Path    = Files.createTempDirectory("wiring-append")
    val ledger: Path = dir.resolve("ledger.jsonl")
    val _: Path      = Files.write(ledger, "{\"v\":1}".getBytes(StandardCharsets.UTF_8))
    SubcommandWiring.appendLedgerLine(ledger.toString, "{\"v\":2}") match
      case Outcome.Ran(()) =>
        val lines: List[String] =
          Files.readAllLines(ledger, StandardCharsets.UTF_8).toArray.toList.map(_.toString)
        assertEquals(lines, List("{\"v\":1}", "{\"v\":2}"), "the append lands on its own line")
      case other => fail(s"a normal append runs, got $other")

  test("repoContaining answers None for a non-directory and outside a repo"):
    val dir: Path  = Files.createTempDirectory("wiring-repoof")
    val file: Path = dir.resolve("f.txt")
    val _: Path    = Files.write(file, "x".getBytes(StandardCharsets.UTF_8))
    assertEquals(SubcommandWiring.repoContaining(file), None, "a file is not a repository dir")
    assertEquals(SubcommandWiring.repoContaining(dir), None, "a non-repo dir finds no repo")

  test("sha256OfFile answers None for a non-file"):
    val dir: Path = Files.createTempDirectory("wiring-sha")
    assertEquals(SubcommandWiring.sha256OfFile(dir), None, "a directory has no file hash")

end ForgiveUnchangedSpec
