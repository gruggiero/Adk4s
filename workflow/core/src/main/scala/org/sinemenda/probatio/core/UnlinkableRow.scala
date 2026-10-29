package org.sinemenda.probatio.core

/**
 * Why a source row could not be bound to a graph node — non-empty by
 * construction, so a row recorded as unbindable without saying why is
 * unrepresentable rather than merely unrecommended.
 *
 * The opaque type has no public `apply`: `UnlinkableReason("")` does not
 * compile. `of` is the validating route; `stated` is the total route for
 * parser text that must never record a bare failure — empty input maps to
 * `unclassifiable`, which still names the row under inspection.
 *
 * spec: graph-tool-port — Requirement: A row that cannot be bound is reported, never dropped
 * spec: graph-tool-port — Compile-Negative: An unlinkable row without a reason
 */
opaque type UnlinkableReason = String

object UnlinkableReason:

  /**
   * The validating construction route. `Left` when `text` is empty — an
   * unlinkable row with no stated reason is a dropped row wearing a label.
   */
  def of(text: String): Either[String, UnlinkableReason] =
    if text.isEmpty then
      Left("an unlinkable reason must be a non-empty string naming why the row could not be bound")
    else Right(text)

  /**
   * The total construction route for parser-side reason text: an empty
   * input yields `unclassifiable` — which still names the row under
   * inspection rather than recording a bare failure marker. Non-empty
   * input is taken verbatim.
   */
  def stated(text: String): UnlinkableReason =
    of(text).fold(_ => unclassifiable, identity)

  /** The reason for a cause that could not be classified further. */
  val unclassifiable: UnlinkableReason = "the row under inspection"

  extension (r: UnlinkableReason)
    /** The stated reason text. */
    def text: String = r

/**
 * One row a parser read but could not bind to a graph node: where it was
 * read (`source`, `line`), what it said (`text`), and why it could not be
 * bound (`reason`). A graph that omits this field reports reachability
 * optimistically — the confidently-wrong failure mode the tool exists to
 * detect.
 *
 * spec: graph-tool-port — Concepts Introduced (new): UnlinkableRow
 * spec: graph-tool-port — Compile-Negative: An unlinkable row without a reason
 */
final case class UnlinkableRow(
  source: String,
  line: Int,
  text: String,
  reason: UnlinkableReason
)
