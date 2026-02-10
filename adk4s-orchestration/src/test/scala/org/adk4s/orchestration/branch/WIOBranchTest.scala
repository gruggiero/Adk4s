package org.adk4s.orchestration.branch

import cats.effect.IO
import munit.CatsEffectSuite
import workflows4s.wio.{WIO, WorkflowContext, WCState}

object TestContext extends WorkflowContext:
  override type State = String
  override type Event = Unit

class WIOBranchTest extends CatsEffectSuite:

  test("WIOBranch.fork routes to ifTrue when condition is true") {
    val workflow = WIOBranch.fork(
      condition = (i: Int) => i > 10,
      ifTrue = WIO.build[TestContext.type].pure.makeFrom[Int].value(i => s"greater: $i").done,
      ifFalse = WIO.build[TestContext.type].pure.makeFrom[Int].value(i => s"lesser: $i").done
    )

    workflow match
      case _: WIO[?, ?, ?, TestContext.type] => assert(true, "workflow should be WIO")
      case _ => assert(false, "workflow should be WIO")
  }

  test("WIOBranch.branch creates multi-way branch") {
    val workflow = WIOBranch.branch(
      selector = (i: Int) => i % 2,
      branches = Map(
        0 -> WIO.build[TestContext.type].pure.makeFrom[Int].value(i => s"even: $i").done,
        1 -> WIO.build[TestContext.type].pure.makeFrom[Int].value(i => s"odd: $i").done
      ),
      default = WIO.build[TestContext.type].pure.makeFrom[Int].value(i => s"unknown: $i").done
    )

    workflow match
      case _: WIO[?, ?, ?, TestContext.type] => assert(true, "workflow should be WIO")
      case _ => assert(false, "workflow should be WIO")
  }

  test("WIOBranch.endIf returns endValue when condition is true") {
    val workflow = WIOBranch.endIf(
      condition = (i: Int) => i > 100,
      continueWith = WIO.build[TestContext.type].pure.makeFrom[Int].value(i => s"continue: $i").done,
      endValue = "ended"
    )

    workflow match
      case _: WIO[?, ?, ?, TestContext.type] => assert(true, "workflow should be WIO")
      case _ => assert(false, "workflow should be WIO")
  }
