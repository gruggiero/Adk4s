package org.adk4s.orchestration.branch

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.{WIO, WorkflowContext, WCState}

object WIOBranch:
  def fork[I, Out <: WCState[Ctx], Ctx <: WorkflowContext](
    condition: I => Boolean,
    ifTrue: WIO[I, Nothing, Out, Ctx],
    ifFalse: WIO[I, Nothing, Out, Ctx]
  ): WIO[I, Nothing, Out, Ctx] =
    WIO.build[Ctx].fork[I].matchCondition(condition)(
      onTrue = ifTrue,
      onFalse = ifFalse
    )

  def branch[I, Out <: WCState[Ctx], K, Ctx <: WorkflowContext](
    selector: I => K,
    branches: Map[K, WIO[I, Nothing, Out, Ctx]],
    default: WIO[I, Nothing, Out, Ctx]
  ): WIO[I, Nothing, Out, Ctx] = {
    // Create a branch for each key in the map
    val branchList = branches.map { case (key, wio) =>
      WIO.Branch(
        condition = (i: I) => Option.when(selector(i) == key)(i),
        wio = wio,
        name = Some(key.toString)
      )
    }.toVector
    
    // Add default branch
    val defaultBranch = WIO.Branch(
      condition = (i: I) => Option.when(!branches.contains(selector(i)))(i),
      wio = default,
      name = Some("default")
    )
    
    WIO.Fork(branchList :+ defaultBranch, None, None)
  }

  def endIf[I, Out <: WCState[Ctx], Ctx <: WorkflowContext](
    condition: I => Boolean,
    continueWith: WIO[I, Nothing, Out, Ctx],
    endValue: Out
  ): WIO[I, Nothing, Out, Ctx] =
    fork(condition, WIO.build[Ctx].pure.makeFrom[I].value(_ => endValue).done, continueWith)
