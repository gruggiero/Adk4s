package org.sinemenda.probatio.core

/**
 * The closed ring domain (R0–R9, manual).
 *
 * A ring outside this set is unrepresentable at the type level.
 *
 * spec: probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
 * spec: probatio-core — Compile-Negative: Ring with a value outside the closed domain
 */
enum Ring:
  case R0, R1, R2, R3, R4, R5, R6, R7, R8, R9, Manual

object Ring:

  /**
   * Parses a ring name from a string, returning Option to model the
   * closed domain — a ring outside the set yields None, not an exception.
   *
   * spec: probatio-core — Scenario: a record with a ring outside the closed domain is rejected
   */
  def fromString(s: String): Option[Ring] = s match
    case "R0"     => Some(R0)
    case "R1"     => Some(R1)
    case "R2"     => Some(R2)
    case "R3"     => Some(R3)
    case "R4"     => Some(R4)
    case "R5"     => Some(R5)
    case "R6"     => Some(R6)
    case "R7"     => Some(R7)
    case "R8"     => Some(R8)
    case "R9"     => Some(R9)
    case "manual" => Some(Manual)
    case _        => None // danger-scan:allow type-rejection — invalid ring maps to None, never a valid value

  /** The string representation used in the ledger record. */
  def asString(r: Ring): String = r match
    case R0     => "R0"
    case R1     => "R1"
    case R2     => "R2"
    case R3     => "R3"
    case R4     => "R4"
    case R5     => "R5"
    case R6     => "R6"
    case R7     => "R7"
    case R8     => "R8"
    case R9     => "R9"
    case Manual => "manual"
