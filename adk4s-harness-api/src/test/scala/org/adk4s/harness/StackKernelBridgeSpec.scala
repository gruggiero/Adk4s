package org.adk4s.harness

import hedgehog._
import hedgehog.munit.HedgehogSuite
import upickle.default.*
import org.adk4s.verified.StackKernel

/**
 * Ring 6 bridge spec — runs the real `HarnessState.project`/`mergeBack` and
 * the `StackKernel` model on the SAME generated cell sets and state values,
 * and asserts they agree on the proven invariants:
 *
 *  1. project sets Private cells to `initial`, Inherited/Shared to parent value
 *  2. mergeBack folds Shared cells, leaves Private/Inherited unchanged
 *  3. mergeBack with no children is identity
 *
 * The bridge maps real `StateCell[Int]` (with `initial = 0`) to model
 * `Cell(id, visibility, initial)` with `BigInt` keys and values.
 */
class StackKernelBridgeSpec extends HedgehogSuite:

  property("bridge-project-private — real and model agree on Private cells"):
    for
      parentVal <- Gen.int(Range.linear(0, 100)).forAll
      initial   <- Gen.int(Range.linear(0, 100)).forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] =
        StateCell[Int](MiddlewareName("m"), "priv", initial, visibility = CellVisibility.Private)
      val declared: List[StateCell[?]] = List(cell)
      val realParent: HarnessState     = HarnessState.initial(declared).set(cell)(parentVal)
      val realChild: HarnessState      = HarnessState.project(realParent, declared)
      val realGet: Int                 = realChild.get(cell)

      val modelCell: StackKernel.Cell =
        StackKernel.Cell(BigInt(0), StackKernel.PrivateV(), BigInt(initial))
      val modelParent: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(parentVal))
      val modelChild: StackKernel.State  = StackKernel.project(modelParent, stainless.collection.List(modelCell))
      val modelGet: BigInt               = modelChild.getOrElse(BigInt(0), BigInt(initial))
      val lemmaHolds: Boolean =
        StackKernel.projectPrivateInitial(modelParent, modelCell, BigInt(parentVal))

      (realGet ==== initial).and(modelGet ==== BigInt(initial)).and(lemmaHolds ==== true)

  property("bridge-project-inherited — real and model agree on Inherited cells"):
    for
      parentVal <- Gen.int(Range.linear(0, 100)).forAll
      initial   <- Gen.int(Range.linear(0, 100)).forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] =
        StateCell[Int](MiddlewareName("m"), "inh", initial, visibility = CellVisibility.Inherited)
      val declared: List[StateCell[?]] = List(cell)
      val realParent: HarnessState     = HarnessState.initial(declared).set(cell)(parentVal)
      val realChild: HarnessState      = HarnessState.project(realParent, declared)
      val realGet: Int                 = realChild.get(cell)

      val modelCell: StackKernel.Cell =
        StackKernel.Cell(BigInt(0), StackKernel.InheritedV(), BigInt(initial))
      val modelParent: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(parentVal))
      val modelChild: StackKernel.State  = StackKernel.project(modelParent, stainless.collection.List(modelCell))
      val modelGet: BigInt               = modelChild.getOrElse(BigInt(0), BigInt(initial))
      val lemmaHolds: Boolean =
        StackKernel.projectInheritedSharedCopiesParent(modelParent, modelCell, BigInt(parentVal))

      (realGet ==== parentVal).and(modelGet ==== BigInt(parentVal)).and(lemmaHolds ==== true)

  property("bridge-project-shared — real and model agree on Shared cells"):
    for
      parentVal <- Gen.int(Range.linear(0, 100)).forAll
      initial   <- Gen.int(Range.linear(0, 100)).forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] =
        StateCell[Int](
          MiddlewareName("m"),
          "shar",
          initial,
          visibility = CellVisibility.Shared,
          merge = (a: Int, b: Int) => a + b
        )
      val declared: List[StateCell[?]] = List(cell)
      val realParent: HarnessState     = HarnessState.initial(declared).set(cell)(parentVal)
      val realChild: HarnessState      = HarnessState.project(realParent, declared)
      val realGet: Int                 = realChild.get(cell)

      val modelCell: StackKernel.Cell =
        StackKernel.Cell(BigInt(0), StackKernel.SharedV((a: BigInt, b: BigInt) => a + b), BigInt(initial))
      val modelParent: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(parentVal))
      val modelChild: StackKernel.State  = StackKernel.project(modelParent, stainless.collection.List(modelCell))
      val modelGet: BigInt               = modelChild.getOrElse(BigInt(0), BigInt(initial))
      val lemmaHolds: Boolean =
        StackKernel.projectInheritedSharedCopiesParent(modelParent, modelCell, BigInt(parentVal))

      (realGet ==== parentVal).and(modelGet ==== BigInt(parentVal)).and(lemmaHolds ==== true)

  property("bridge-mergeBack-shared-folds — real and model agree"):
    for
      parentVal <- Gen.int(Range.linear(0, 100)).forAll
      c1Val     <- Gen.int(Range.linear(0, 100)).forAll
      c2Val     <- Gen.int(Range.linear(0, 100)).forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] =
        StateCell[Int](
          MiddlewareName("m"),
          "shar",
          0,
          visibility = CellVisibility.Shared,
          merge = (a: Int, b: Int) => a + b
        )
      val declared: List[StateCell[?]] = List(cell)
      val realParent: HarnessState     = HarnessState.initial(declared).set(cell)(parentVal)
      val realChild1: HarnessState     = HarnessState.project(realParent, declared).set(cell)(c1Val)
      val realChild2: HarnessState     = HarnessState.project(realParent, declared).set(cell)(c2Val)
      val realMerged: HarnessState =
        HarnessState.mergeBack(realParent, List(realChild1, realChild2), declared)
      val realGet: Int = realMerged.get(cell)

      val modelCell: StackKernel.Cell =
        StackKernel.Cell(BigInt(0), StackKernel.SharedV((a: BigInt, b: BigInt) => a + b), BigInt(0))
      val modelParent: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(parentVal))
      val modelChild1: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(c1Val))
      val modelChild2: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(c2Val))
      val modelMerged: StackKernel.State =
        StackKernel.mergeBack(
          modelParent,
          stainless.collection.List(modelChild1, modelChild2),
          stainless.collection.List(modelCell)
        )
      val modelGet: BigInt = modelMerged.getOrElse(BigInt(0), BigInt(0))
      val lemmaHolds: Boolean =
        StackKernel.mergeBackSharedFolds(
          modelParent,
          stainless.collection.List(modelChild1, modelChild2),
          modelCell,
          BigInt(parentVal)
        )

      val expected: Int = parentVal + c1Val + c2Val
      (realGet ==== expected).and(modelGet ==== BigInt(expected)).and(lemmaHolds ==== true)

  property("bridge-mergeBack-private-unchanged — real and model agree"):
    for
      parentVal <- Gen.int(Range.linear(0, 100)).forAll
      childVal  <- Gen.int(Range.linear(0, 100)).forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] =
        StateCell[Int](MiddlewareName("m"), "priv", 0, visibility = CellVisibility.Private)
      val declared: List[StateCell[?]] = List(cell)
      val realParent: HarnessState     = HarnessState.initial(declared).set(cell)(parentVal)
      val realChild: HarnessState      = HarnessState.project(realParent, declared).set(cell)(childVal)
      val realMerged: HarnessState =
        HarnessState.mergeBack(realParent, List(realChild), declared)
      val realGet: Int = realMerged.get(cell)

      val modelCell: StackKernel.Cell =
        StackKernel.Cell(BigInt(0), StackKernel.PrivateV(), BigInt(0))
      val modelParent: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(parentVal))
      val modelChild: StackKernel.State  = StackKernel.empty.updated(BigInt(0), BigInt(childVal))
      val modelMerged: StackKernel.State =
        StackKernel.mergeBack(modelParent, stainless.collection.List(modelChild), stainless.collection.List(modelCell))
      val modelGet: BigInt = modelMerged.getOrElse(BigInt(0), BigInt(0))
      val lemmaHolds: Boolean =
        StackKernel.mergeBackPrivateUnchanged(
          modelParent,
          stainless.collection.List(modelChild),
          modelCell,
          BigInt(parentVal)
        )

      (realGet ==== parentVal).and(modelGet ==== BigInt(parentVal)).and(lemmaHolds ==== true)

  property("bridge-mergeBack-no-children — real and model agree on identity"):
    for parentVal <- Gen.int(Range.linear(0, 100)).forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] =
        StateCell[Int](
          MiddlewareName("m"),
          "shar",
          0,
          visibility = CellVisibility.Shared,
          merge = (a: Int, b: Int) => a + b
        )
      val declared: List[StateCell[?]] = List(cell)
      val realParent: HarnessState     = HarnessState.initial(declared).set(cell)(parentVal)
      val realMerged: HarnessState     = HarnessState.mergeBack(realParent, Nil, declared)
      val realGet: Int                 = realMerged.get(cell)

      val modelCell: StackKernel.Cell =
        StackKernel.Cell(BigInt(0), StackKernel.SharedV((a: BigInt, b: BigInt) => a + b), BigInt(0))
      val modelParent: StackKernel.State = StackKernel.empty.updated(BigInt(0), BigInt(parentVal))
      val modelMerged: StackKernel.State =
        StackKernel.mergeBack(
          modelParent,
          stainless.collection.List.empty[StackKernel.State],
          stainless.collection.List(modelCell)
        )
      val modelGet: BigInt = modelMerged.getOrElse(BigInt(0), BigInt(0))
      val lemmaHolds: Boolean =
        StackKernel.mergeBackNoChildrenIsIdentity(modelParent, stainless.collection.List(modelCell))

      (realGet ==== parentVal).and(modelGet ==== BigInt(parentVal)).and(lemmaHolds ==== true)
