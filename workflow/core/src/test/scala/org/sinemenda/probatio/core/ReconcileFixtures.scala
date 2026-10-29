package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range

/**
 * Constructive generators for the reconcile oracle (spec 6, Step 2).
 *
 * `genRecordSet` builds a change plus a record list in which every
 * corroboration class is reachable by construction: claims carry a
 * verdict target (`Testimony`/`Witnessed`/`Contradicted`) and emit the
 * ambient rows that produce it; padding rows cover the exempt and
 * self-observed classes. No filtering of the generated set — coverage
 * is guaranteed by construction, `cover` labels make it visible.
 *
 * spec: danger-reconcile-engines — Property: corroboration-is-total-and-exclusive
 * spec: danger-reconcile-engines — Property: witness-requires-key-agreement
 */
object ReconcileFixtures:

  /** A claim/witness key — (spec, ring, baseline, command). */
  final case class ClaimKey(spec: String, ring: Ring, baseline: String, command: String)

  /** Who observed the row's exit. */
  enum RecKind:
    /** A bare `append` row — the writer typed the exit code. */
    case Written

    /** A `run` row — carries a digest; self-observed. */
    case DigestRow

    /** An ambient row — `source: "ambient"`; the witness kind. */
    case AmbientRow

  /** The verdict a generated claim is constructed to receive. */
  enum ClaimTarget:
    case Testimony, Witnessed, Contradicted

  /** Build a record at `key` for `change` with the given kind and exit. */
  def record(
    key: ClaimKey,
    change: String,
    exit: Int,
    kind: RecKind,
    ring: Option[Ring] = None
  ): ValidatedRecord =
    ValidatedRecord(
      record = LedgerRecord(
        v = 1,
        ts = "2026-09-17T00:00:00Z",
        change = change,
        spec = key.spec,
        ring = ring.getOrElse(key.ring),
        obligation = "obligation text",
        artifact = "artifact/path.scala",
        command = key.command,
        exit = exit,
        baseline = key.baseline,
        optional = LedgerRecordOptional(
          digest = kind match
            case RecKind.DigestRow => Some("0123456789abcdef")
            case _ => None // danger-scan:allow kind-exhaustive — Written/AmbientRow carry no digest by construction
          ,
          source = kind match
            case RecKind.AmbientRow => Some("ambient")
            case _ => None // danger-scan:allow kind-exhaustive — Written/DigestRow carry no ambient source
        )
      )
    )

  /** A key on a deterministic ring (claims live only there). */
  val genDeterministicKey: Gen[ClaimKey] =
    for
      spec <- Gen.string(Gen.alphaNum, Range.linear(1, 6)).map(s => s"sp-$s")
      ring <- Gen.element(
        Ring.R0,
        List(Ring.R1, Ring.R3, Ring.R4, Ring.R5, Ring.R6, Ring.R7, Ring.R9)
      )
      base <- Gen.string(Gen.alphaNum, Range.linear(4, 7))
      cmd  <- Gen.element("sbt test", List("make check", "./run.sh", "bats t.bats"))
    yield ClaimKey(spec, ring, base, cmd)

  /** Any key — judgment rings included (for padding and ambient rows). */
  val genAnyKey: Gen[ClaimKey] =
    for
      spec <- Gen.string(Gen.alphaNum, Range.linear(1, 6)).map(s => s"sp-$s")
      ring <- Gen.element(Ring.R0, Ring.values.toList.drop(1))
      base <- Gen.string(Gen.alphaNum, Range.linear(4, 7))
      cmd  <- Gen.element("sbt test", List("make check", "./run.sh", "bats t.bats"))
    yield ClaimKey(spec, ring, base, cmd)

  /** A claim plus its target verdict. */
  val genClaimPlan: Gen[(ClaimKey, ClaimTarget)] =
    for
      key <- genDeterministicKey
      target <- Gen.frequency1(
        40 -> Gen.constant(ClaimTarget.Testimony),
        35 -> Gen.constant(ClaimTarget.Witnessed),
        25 -> Gen.constant(ClaimTarget.Contradicted)
      )
    yield (key, target)

  /**
   * The rows a claim plan emits: the written green claim plus the
   * ambient rows realising its target — none for testimony, an exit-0
   * witness for witnessed, disagreeing witnesses for contradicted. A
   * testimony claim may additionally emit an ambient row at a PERTURBED
   * key (wrong spec/ring/baseline/command) — the observer-at-wrong-key
   * cover case.
   */
  private def rowsForClaim(
    change: String,
    key: ClaimKey,
    target: ClaimTarget
  ): Gen[List[ValidatedRecord]] =
    val claim: ValidatedRecord = record(key, change, exit = 0, RecKind.Written)
    target match
      case ClaimTarget.Testimony =>
        // Optionally a witness at a deliberately perturbed key — it must
        // NOT corroborate the claim.
        Gen.boolean.map { (perturb: Boolean) =>
          if perturb then
            val wrong: ClaimKey = key.copy(command = key.command + "-other")
            List(claim, record(wrong, change, exit = 0, RecKind.AmbientRow))
          else List(claim)
        }
      case ClaimTarget.Witnessed =>
        Gen.int(Range.linear(1, 2)).map { (extra: Int) =>
          claim :: record(key, change, exit = 0, RecKind.AmbientRow) ::
            List.fill(extra - 1)(record(key, change, exit = 1, RecKind.AmbientRow))
        }
      case ClaimTarget.Contradicted =>
        Gen.int(Range.linear(1, 2)).map { (n: Int) =>
          claim :: List.fill(n)(record(key, change, exit = 1, RecKind.AmbientRow))
        }

  /** A padding row: self-observed or exempt, never a claim. */
  private def genPaddingRow(change: String): Gen[ValidatedRecord] =
    Gen.frequency1(
      30 -> genAnyKey.map(k => record(k, change, exit = 0, RecKind.DigestRow)),
      25 -> genDeterministicKey.map(k => record(k, change, exit = 1, RecKind.Written)),
      25 -> genDeterministicKey.map(k => record(k, change, exit = 0, RecKind.Written, ring = Some(Ring.R8))),
      20 -> genAnyKey.map(k => record(k, change, exit = 0, RecKind.AmbientRow))
    )

  /**
   * A change name plus a record list: 1–4 claims at generated verdict
   * targets, plus padding rows. Every record carries the generated
   * `change` — the engine's change filter keeps them all in scope.
   */
  val genRecordSet: Gen[(String, List[ValidatedRecord])] =
    for
      change <- Gen.string(Gen.alpha, Range.linear(3, 8)).map(c => s"chg-$c")
      plans  <- genClaimPlan.list(Range.linear(1, 4))
      claimRows <- plans.foldRight(Gen.constant(List.empty[ValidatedRecord])) { case ((k, t), acc) =>
        rowsForClaim(change, k, t).flatMap(xs => acc.map(ys => xs ++ ys))
      }
      padding <- genPaddingRow(change).list(Range.linear(0, 5))
    yield (change, claimRows ++ padding)
