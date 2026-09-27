package org.sinemenda.probatio.packaging

/**
 * The verdict of comparing a candidate native binary's embedded
 * toolchain identity against the tested identity.
 *
 * `Accepted` carries the identity that matched; `Rejected` carries both
 * halves of the mismatch; `Undetermined` is a could-not-determine —
 * the scan produced no identity — and is never a pass.
 *
 * spec: finish-probatio-replacement/delivery-verified — Requirement: The delivered binary is built with the toolchain that was tested
 * spec: finish-probatio-replacement/delivery-verified — Concepts Introduced: ToolchainIdentity (Embedded/verdict companion)
 */
enum ToolchainVerdict:
  case Accepted(binary: String, identity: ToolchainIdentity)
  case Rejected(
      binary: String,
      tested: ToolchainIdentity,
      candidate: ToolchainIdentity
  )
  case Undetermined(binary: String, reason: String)

  /** Whether the candidate was accepted. Only `Accepted` is true. */
  def accepted: Boolean =
    this match
      case ToolchainVerdict.Accepted(_, _)      => true
      case ToolchainVerdict.Rejected(_, _, _)   => false
      case ToolchainVerdict.Undetermined(_, _)  => false
