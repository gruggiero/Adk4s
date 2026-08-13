package org.adk4s.orchestration.branch

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.adk4s.core.types.{NodeKey, ReservedNodeKey, Reserved}
import org.adk4s.core.types.given

class BranchTest extends CatsEffectSuite:

  test("Branch.apply creates InvokeBranch with condition and targets") {
    val branch = Branch[Int](_ => IO.pure(RouteTarget.ToNode(NodeKey("node1"))), Set(RouteTarget.ToNode(NodeKey("node1")), RouteTarget.ToNode(NodeKey("node2"))))
    branch match
      case _: InvokeBranch[Int] => assert(true, "branch should be InvokeBranch")
      case _ => assert(false, "branch should be InvokeBranch")
    assertEquals(branch.endNodes, Set[RouteTarget](RouteTarget.ToNode(NodeKey("node1")), RouteTarget.ToNode(NodeKey("node2"))))
  }

  test("Branch.pure creates InvokeBranch with pure condition") {
    val branch = Branch.pure[Int](_ => RouteTarget.ToNode(NodeKey("node1")), Set(RouteTarget.ToNode(NodeKey("node1"))))
    branch match
      case _: InvokeBranch[Int] => assert(true, "branch should be InvokeBranch")
      case _ => assert(false, "branch should be InvokeBranch")
    assertEquals(branch.endNodes, Set[RouteTarget](RouteTarget.ToNode(NodeKey("node1"))))
  }

  test("Branch.stream creates StreamBranch") {
    val branch = Branch.stream[Int](_ => IO.pure(RouteTarget.ToNode(NodeKey("node1"))), Set(RouteTarget.ToNode(NodeKey("node1"))))
    branch match
      case _: StreamBranch[Int] => assert(true, "branch should be StreamBranch")
      case _ => assert(false, "branch should be StreamBranch")
    assertEquals(branch.endNodes, Set[RouteTarget](RouteTarget.ToNode(NodeKey("node1"))))
  }

  test("Branch.binary creates branch that routes to ifTrue when predicate returns true") {
    val ifTrue = NodeKey("trueNode")
    val ifFalse = NodeKey("falseNode")
    val branch = Branch.binary((i: Int) => IO.pure(i > 10), ifTrue, ifFalse)

    branch match
      case InvokeBranch(condition, _) =>
        for
          result1 <- condition(15)
          result2 <- condition(5)
        yield
          assertEquals(result1, RouteTarget.ToNode(ifTrue))
          assertEquals(result2, RouteTarget.ToNode(ifFalse))
      case _ => IO.raiseError(new Exception("Expected InvokeBranch"))
  }

  test("Branch.endIf creates branch that routes to END when predicate returns true") {
    val otherwise = NodeKey("otherwise")
    val branch = Branch.endIf((i: Int) => IO.pure(i > 10), otherwise)

    branch match
      case InvokeBranch(condition, _) =>
        for
          result1 <- condition(15)
          result2 <- condition(5)
        yield
          assertEquals(result1, RouteTarget.ToReserved(ReservedNodeKey.End))
          assertEquals(result2, RouteTarget.ToNode(otherwise))
      case _ => IO.raiseError(new Exception("Expected InvokeBranch"))
  }

  test("InvokeBranch.endNodes returns target nodes") {
    val branch = InvokeBranch[Int](_ => IO.pure(RouteTarget.ToNode(NodeKey("node1"))), Set(RouteTarget.ToNode(NodeKey("node1")), RouteTarget.ToNode(NodeKey("node2"))))
    assertEquals(branch.endNodes, Set[RouteTarget](RouteTarget.ToNode(NodeKey("node1")), RouteTarget.ToNode(NodeKey("node2"))))
  }

  test("StreamBranch.endNodes returns target nodes") {
    val branch = StreamBranch[Int](_ => IO.pure(RouteTarget.ToNode(NodeKey("node1"))), Set(RouteTarget.ToNode(NodeKey("node1")), RouteTarget.ToNode(NodeKey("node2"))))
    assertEquals(branch.endNodes, Set[RouteTarget](RouteTarget.ToNode(NodeKey("node1")), RouteTarget.ToNode(NodeKey("node2"))))
  }

class RouterTest extends CatsEffectSuite:

  test("Router.empty creates empty router") {
    val router = Router.empty[Int]
    assertEquals(router.branches, Map.empty[NodeKey, Branch[Int]])
  }

  test("Router.route with InvokeBranch returns target node") {
    val node1 = NodeKey("node1")
    val node2 = NodeKey("node2")
    val branch = Branch.binary((i: Int) => IO.pure(i > 10), node1, node2)
    val router = Router.empty[Int].addBranch(node1, branch)

    for
      result <- router.route(node1, 15)
    yield assertEquals(result, RouteTarget.ToNode(node1))
  }

  test("Router.route with InvokeBranch routes based on condition") {
    val source = NodeKey("source")
    val node1 = NodeKey("node1")
    val node2 = NodeKey("node2")
    val branch = Branch.binary((i: Int) => IO.pure(i > 10), node1, node2)
    val router = Router.empty[Int].addBranch(source, branch)

    for
      result1 <- router.route(source, 15)
      result2 <- router.route(source, 5)
    yield
      assertEquals(result1, RouteTarget.ToNode(node1))
      assertEquals(result2, RouteTarget.ToNode(node2))
  }

  test("Router.route with StreamBranch raises IllegalStateException") {
    val source = NodeKey("source")
    val node1 = NodeKey("node1")
    val branch = Branch.stream[Int](_ => IO.pure(RouteTarget.ToNode(node1)), Set(RouteTarget.ToNode(node1)))
    val router = Router.empty[Int].addBranch(source, branch)

    interceptIO[IllegalStateException](router.route(source, 5)).map { error =>
      assert(error.getMessage.contains("Cannot use invoke routing with stream branch"))
    }
  }

  test("Router.route with undefined node raises IllegalStateException") {
    val router = Router.empty[Int]
    val undefinedNode = NodeKey("undefined")

    interceptIO[IllegalStateException](router.route(undefinedNode, 5)).map { error =>
      assert(error.getMessage.contains("No branch defined for node"))
    }
  }

  test("Router.routeStream with InvokeBranch compiles stream and routes") {
    val source = NodeKey("source")
    val node1 = NodeKey("node1")
    val node2 = NodeKey("node2")
    val branch = Branch.binary((i: Int) => IO.pure(i > 10), node1, node2)
    val router = Router.empty[Int].addBranch(source, branch)

    for
      result1 <- router.routeStream(source, Stream.emits(List(5, 8, 12)))
      result2 <- router.routeStream(source, Stream.emits(List(3, 5, 7)))
    yield
      assertEquals(result1, RouteTarget.ToNode(node1))
      assertEquals(result2, RouteTarget.ToNode(node2))
  }

  test("Router.routeStream with StreamBranch evaluates with entire stream") {
    val source = NodeKey("source")
    val node1 = NodeKey("node1")
    val branch = Branch.stream[Int](_ => IO.pure(RouteTarget.ToNode(node1)), Set(RouteTarget.ToNode(node1)))
    val router = Router.empty[Int].addBranch(source, branch)

    for
      result <- router.routeStream(source, Stream.emits(List(1, 2, 3)))
    yield assertEquals(result, RouteTarget.ToNode(node1))
  }

  test("Router.routeStream with undefined node raises IllegalStateException") {
    val router = Router.empty[Int]
    val undefinedNode = NodeKey("undefined")

    interceptIO[IllegalStateException](router.routeStream(undefinedNode, Stream.emits(List(1, 2, 3)))).map { error =>
      assert(error.getMessage.contains("No branch defined for node"))
    }
  }

  test("Router.addBranch adds branch to router") {
    val source = NodeKey("source")
    val node1 = NodeKey("node1")
    val branch = Branch[Int](_ => IO.pure(RouteTarget.ToNode(node1)), Set(RouteTarget.ToNode(node1)))
    val router1 = Router.empty[Int]
    val router2 = router1.addBranch(source, branch)

    assert(router1.branches.isEmpty)
    assert(router2.branches.contains(source))
    assertEquals(router2.branches(source), branch)
  }
