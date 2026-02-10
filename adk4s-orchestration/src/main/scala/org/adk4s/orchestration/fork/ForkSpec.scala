package org.adk4s.orchestration.fork

import workflows4s.wio.{ErrorMeta, WCState, WIO, WorkflowContext}

/** ForkSpec defines a branching point with multiple conditional paths.
  *
  * Type parameters:
  * - I: Input type to the fork
  * - O: Output type
  */
case class ForkSpec[I, O](
  cases: Vector[ForkSpec.Case[I, O, ?]],
  name: Option[String]
):
  
  /** Add a case to this fork. */
  def addCase[CaseIn](
    predicate: I => Option[CaseIn],
    caseName: Option[String] = None
  ): ForkSpec[I, O] =
    copy(cases = cases :+ ForkSpec.Case(predicate, caseName))

  /** Set the fork name. */
  def named(n: String): ForkSpec[I, O] =
    copy(name = Some(n))

object ForkSpec:
  /** A single case in a fork. */
  case class Case[I, O, CaseIn](
    predicate: I => Option[CaseIn],
    name: Option[String]
  )

  /** Create an empty ForkSpec. */
  def apply[I, O](): ForkSpec[I, O] =
    ForkSpec(Vector.empty, None)

  /** Create a ForkSpec with a name. */
  def named[I, O](name: String): ForkSpec[I, O] =
    ForkSpec(Vector.empty, Some(name))
