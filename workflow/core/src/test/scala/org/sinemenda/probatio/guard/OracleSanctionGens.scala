package org.sinemenda.probatio.guard

import hedgehog.Gen
import hedgehog.Range

/**
 * Generators and fixtures for the oracle-sanction guard's test oracle
 * (spec 5, `oracle-independence`, Step 2 — derived from the spec, not
 * from any implementation).
 *
 * Everything here is constructive: modification histories are assembled
 * from small closed alphabets of oracle filenames and commit ids, and
 * each modification's sanction disposition is DRAWN from the closed set
 * {absent, names the file, names a test, unrelated, nonexistent
 * requirement} so every sanction outcome arises by construction — the
 * expected `accepted` set is recorded alongside the record rather than
 * recomputed by the decision under test.
 *
 * spec: oracle-independence — Property: guard-passes-iff-all-sanctioned (generator strategy)
 * spec: oracle-independence — Property: sanction-record-round-trips (generator strategy)
 */
object OracleSanctionGens:

  // ════════════════════════════════════════════════════════════════════
  // Closed alphabets — oracle filenames, commit ids, spec/requirement
  // names. Distinct alphabets so a citation drawn for one modification
  // can never collide with another's key.
  // ════════════════════════════════════════════════════════════════════

  private val oracleFiles: List[String] =
    List(
      "workflow-hygiene.bats",
      "fact-extraction.bats",
      "correctness-invariant.bats",
      "gate-payload.bats",
      "hook-tiers.bats",
      "evidence-ledger.bats"
    )

  /** Commit ids drawn from a fixed pool — deterministic, all 40-hex. */
  private val commitPool: List[String] =
    (1 to 24).toList.map(i => i.toHexString.reverse.padTo(40, '0').reverse)

  private val specNames: List[String] =
    List("oracle-independence", "hermetic-test-processes", "archive-safe-fixtures")

  private val reqNames: List[String] =
    List(
      "The acceptance oracle holds only behavioural tests",
      "Every oracle modification cites the requirement that made it necessary",
      "Test code locates a change through the archive-aware resolver",
      "A spawned tool sees only the variables its test declares"
    )

  /**
   * `@test` titles per file — the domain `requirementNames` matches
   * verbatim. Titles are drawn so that an unrelated requirement text can
   * never contain one by accident (they all begin "the ").
   */
  val testTitlesOf: Map[String, List[String]] =
    oracleFiles.map { f =>
      val base: String = f.stripSuffix(".bats")
      f -> List(
        s"the $base gate rejects the malformed payload",
        s"the $base ledger records the decision"
      )
    }.toMap

  /** Basename as `requirementNames` sees it — `a/b/c.bats` → `c.bats`. */
  def basename(file: String): String =
    file.substring(file.lastIndexOf('/') + 1)

  // ════════════════════════════════════════════════════════════════════
  // genModificationHistory — 0–6 distinct (file, commit) pairs from the
  // closed alphabets. Edge cases the spec names — no modifications,
  // exactly one — fall out of the range.
  // ════════════════════════════════════════════════════════════════════

  private val genFile: Gen[String] =
    Gen.element(oracleFiles(0), oracleFiles.drop(1))

  private val genCommit: Gen[String] =
    Gen.element(commitPool(0), commitPool.drop(1))

  val genModificationHistory: Gen[List[OracleModification]] =
    for
      n    <- Gen.int(Range.linear(0, 6))
      mods <-
        (for
          f <- genFile
          c <- genCommit
        yield OracleModification(f, c)).list(Range.singleton(n))
    yield mods.distinct

  // ════════════════════════════════════════════════════════════════════
  // genSanctionFixture — for every modification, a disposition drawn from
  // the closed set; the record and the lookup tables are built so the
  // drawn disposition IS the outcome.
  // ════════════════════════════════════════════════════════════════════

  /** How a modification's sanction is drawn — every outcome by construction. */
  enum Disposition:
    /** No sanction recorded for the modification. */
    case Absent
    /** A sanction whose cited requirement's text names the file verbatim. */
    case NamesFile
    /** A sanction whose cited requirement's text names a test in the file. */
    case NamesTest
    /** A sanction whose cited requirement resolves but names neither. */
    case Unrelated
    /** A sanction citing a requirement absent from the cited spec. */
    case Nonexistent

  /**
   * A generated history plus its sanction record, the lookup tables the
   * check consults, and the expected accepted set — the constructive
   * oracle: `accepted` is exactly the modifications whose disposition
   * named the file or a test.
   */
  final case class SanctionFixture(
    history: List[OracleModification],
    record: List[OracleSanction],
    requirementTexts: Map[(String, String), String],
    testTitles: String => List[String],
    accepted: Set[OracleModification]
  )

  private val genDisposition: Gen[Disposition] =
    import Disposition.*
    Gen.element(Absent, List(NamesFile, NamesTest, Unrelated, Nonexistent))

  /**
   * One disposition's contribution to the record and the requirement
   * texts. `spec`/`requirement` keys carry the index so two modifications
   * can never collide on a citation.
   */
  private def dispositionOutcome(
    mod: OracleModification,
    disposition: Disposition,
    index: Int
  ): (Option[OracleSanction], Option[((String, String), String)], Boolean) =
    val spec: String = s"${specNames(index % specNames.length)}"
    val req: String  = s"${reqNames(index % reqNames.length)} [$index]"
    disposition match
      case Disposition.Absent =>
        (None, None, false)
      case Disposition.NamesFile =>
        val s: OracleSanction = OracleSanction(mod.file, mod.commit, spec, req)
        val text: String      = s"The suite names ${basename(mod.file)} verbatim."
        (Some(s), Some((spec, req) -> text), true)
      case Disposition.NamesTest =>
        val title: String     = testTitlesOf(mod.file)(0)
        val s: OracleSanction = OracleSanction(mod.file, mod.commit, spec, req)
        val text: String      = s"The requirement cites the test: $title."
        (Some(s), Some((spec, req) -> text), true)
      case Disposition.Unrelated =>
        val s: OracleSanction = OracleSanction(mod.file, mod.commit, spec, req)
        val text: String      = "The system SHALL emit diagnostics in order."
        (Some(s), Some((spec, req) -> text), false)
      case Disposition.Nonexistent =>
        val s: OracleSanction = OracleSanction(mod.file, mod.commit, spec, req)
        (Some(s), None, false)

  /** A fixture whose `accepted` set is the drawn naming dispositions. */
  def genSanctionFixture(history: List[OracleModification]): Gen[SanctionFixture] =
    for dispositions <- genDisposition.list(Range.singleton(history.length))
    yield
      val outcomes: List[(Option[OracleSanction], Option[((String, String), String)], Boolean)] =
        history.zipWithIndex.map { case (m, i) =>
          dispositionOutcome(m, dispositions(i), i)
        }
      SanctionFixture(
        history = history,
        record = outcomes.flatMap(_._1),
        requirementTexts = outcomes.flatMap(_._2).toMap,
        testTitles = (f: String) => testTitlesOf.getOrElse(basename(f), testTitlesOf.getOrElse(f, Nil)),
        accepted = history.zip(outcomes).collect { case (m, (_, _, true)) => m }.toSet
      )

  // ════════════════════════════════════════════════════════════════════
  // Readability — which input the adapter could not produce. `None`
  // means all inputs readable; the kernel's `readable: Boolean` is the
  // disjunction of the three.
  // ════════════════════════════════════════════════════════════════════

  enum UnreadableInput:
    case Baseline, History, Record

  /** Mostly readable, sometimes one input fails — both verdict arms live. */
  val genUnreadable: Gen[Option[UnreadableInput]] =
    import UnreadableInput.*
    Gen.frequency1(
      70 -> Gen.constant(None),
      10 -> Gen.constant(Some(Baseline)),
      10 -> Gen.constant(Some(History)),
      10 -> Gen.constant(Some(Record))
    )

  // ════════════════════════════════════════════════════════════════════
  // Round-trip alphabet — requirement titles carry the characters real
  // titles use: quotes, em-dashes, non-ASCII (Ring 4).
  // ════════════════════════════════════════════════════════════════════

  /** A character alphabet with quotes, em-dash, JSON metacharacters, and non-ASCII members. */
  private val genTitleChar: Gen[Char] =
    Gen.frequency1(
      66 -> Gen.alphaNum,
      10 -> Gen.element(' ', List('-', '/', ':')),
      10 -> Gen.element('"', List('\'', '—', '–', '\\', '{', '}', '[', ']')),
      14 -> Gen.element('é', List('ü', 'ñ', 'ø', 'ß', '日', '本'))
    )

  private val genNastyText: Gen[String] =
    Gen.string(genTitleChar, Range.linear(1, 40)).map(_.filter(_ != '\n'))

  private val genSafeCommit: Gen[String] =
    Gen.string(Gen.element('a', List('b', 'c', 'd', 'e', 'f', '0', '1', '2')), Range.singleton(40))

  val genSanction: Gen[OracleSanction] =
    for
      file <- genFile
      commit <- genSafeCommit
      spec   <- Gen.element(specNames(0), specNames.drop(1))
      req    <- genNastyText.map(t => if t.isEmpty then "req" else t)
    yield OracleSanction(file, commit, spec, req)

  val genSanctionList: Gen[List[OracleSanction]] =
    genSanction.list(Range.linear(0, 8))
