package org.adk4s.orchestration.interrupt

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

/**
 * Test oracle for spec:checkpoint-store-fpoly — `CheckpointStore[F[_]]` scenarios
 * and the F-polymorphism property.
 *
 * Tests written from the spec + approved typed contract ONLY.
 * Every test cites its source: `// spec: checkpoint-store-fpoly — Scenario: <heading>`
 */
class CheckpointStoreSpec extends HedgehogSuite:

  // ── Generators (defined first to avoid initialization-order issues) ────────

  enum CheckpointOp:
    case Set(id: String, data: Array[Byte])
    case Get(id: String)
    case Delete(id: String)
    case Keys

  val genCheckpointId: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 10))

  val genCheckpointData: Gen[Array[Byte]] =
    Gen.string(Gen.alphaNum, Range.linear(0, 50)).map(_.getBytes("UTF-8"))

  val genSetOp: Gen[CheckpointOp] =
    for
      id   <- genCheckpointId
      data <- genCheckpointData
    yield CheckpointOp.Set(id, data)

  val genGetOp: Gen[CheckpointOp] =
    genCheckpointId.map(CheckpointOp.Get(_))

  val genDeleteOp: Gen[CheckpointOp] =
    genCheckpointId.map(CheckpointOp.Delete(_))

  val genKeysOp: Gen[CheckpointOp] =
    Gen.constant(CheckpointOp.Keys)

  val genCheckpointOp: Gen[CheckpointOp] =
    Gen.choice1(genSetOp, genGetOp, genDeleteOp, genKeysOp)

  val genCheckpointOpList: Gen[List[CheckpointOp]] =
    genCheckpointOp.list(Range.linear(1, 20))

  // ── Scenarios (munit) ─────────────────────────────────────────────────────

  test("inMemory get returns None for missing key") {
    // spec: checkpoint-store-fpoly — Scenario: inMemory get returns None for missing key
    val result: Option[Array[Byte]] =
      (for
        store  <- CheckpointStore.inMemory[IO]
        result <- store.get("missing")
      yield result).unsafeRunSync()
    assertEquals(result, None)
  }

  test("inMemory set then get round-trips bytes") {
    // spec: checkpoint-store-fpoly — Scenario: inMemory set then get round-trips bytes
    val data: Array[Byte] = "hello".getBytes("UTF-8")
    val result: Option[Array[Byte]] =
      (for
        store  <- CheckpointStore.inMemory[IO]
        _      <- store.set("key1", data)
        result <- store.get("key1")
      yield result).unsafeRunSync()
    assert(result.isDefined)
    val bytes: Array[Byte] = result match
      case Some(b) => b
      case None    => sys.error("expected checkpoint to be defined")
    assertEquals(new String(bytes, "UTF-8"), "hello")
  }

  test("inMemory set overwrites existing value") {
    // spec: checkpoint-store-fpoly — Scenario: inMemory set overwrites existing value
    val result: Option[Array[Byte]] =
      (for
        store  <- CheckpointStore.inMemory[IO]
        _      <- store.set("key1", "first".getBytes("UTF-8"))
        _      <- store.set("key1", "second".getBytes("UTF-8"))
        result <- store.get("key1")
      yield result).unsafeRunSync()
    val bytes: Array[Byte] = result match
      case Some(b) => b
      case None    => sys.error("expected checkpoint to be defined")
    assertEquals(new String(bytes, "UTF-8"), "second")
  }

  test("inMemory delete removes a key") {
    // spec: checkpoint-store-fpoly — Scenario: inMemory delete removes a key
    val result: Option[Array[Byte]] =
      (for
        store  <- CheckpointStore.inMemory[IO]
        _      <- store.set("key1", "data".getBytes("UTF-8"))
        _      <- store.delete("key1")
        result <- store.get("key1")
      yield result).unsafeRunSync()
    assertEquals(result, None)
  }

  test("inMemory delete is no-op for missing key") {
    // spec: checkpoint-store-fpoly — Scenario: inMemory delete is no-op for missing key
    val result: Option[Array[Byte]] =
      (for
        store  <- CheckpointStore.inMemory[IO]
        _      <- store.delete("missing")
        result <- store.get("missing")
      yield result).unsafeRunSync()
    assertEquals(result, None)
  }

  test("inMemory keys returns all stored keys") {
    // spec: checkpoint-store-fpoly — Scenario: inMemory keys returns all stored keys
    val result: List[String] =
      (for
        store  <- CheckpointStore.inMemory[IO]
        _      <- store.set("a", "1".getBytes("UTF-8"))
        _      <- store.set("b", "2".getBytes("UTF-8"))
        _      <- store.set("c", "3".getBytes("UTF-8"))
        result <- store.keys
      yield result).unsafeRunSync()
    assertEquals(result.sorted, List("a", "b", "c"))
  }

  test("inMemory keys returns empty list when store is empty") {
    // spec: checkpoint-store-fpoly — Scenario: inMemory keys returns empty list when store is empty
    val result: List[String] =
      (for
        store  <- CheckpointStore.inMemory[IO]
        result <- store.keys
      yield result).unsafeRunSync()
    assertEquals(result, List.empty[String])
  }

  // ── F-polymorphism compile tests ──────────────────────────────────────────

  test("F-polymorphism — a non-IO Sync compiles") {
    // spec: checkpoint-store-fpoly — Scenario: F-polymorphism — a non-IO Sync compiles
    // Kleisli[IO, Unit, *] is a non-IO Sync — proves inMemory does not bind to IO
    type F[A] = Kleisli[IO, Unit, A]
    val result: List[String] =
      (for
        store <- CheckpointStore.inMemory[F]
        keys  <- store.keys
      yield keys).run(()).unsafeRunSync()
    assertEquals(result, List.empty[String])
  }

  test("CheckpointId transparent alias preserves source compatibility") {
    // spec: checkpoint-store-fpoly — Scenario: Transparent alias preserves source compatibility
    val id1: CheckpointStore.CheckpointId = "ckpt-1"
    val id2: String                       = "ckpt-1"
    assertEquals(id1, id2)
  }

  test("Alias is not opaque — equality with String holds") {
    // spec: checkpoint-store-fpoly — Scenario: Alias is not opaque — equality with String holds
    val id1: CheckpointStore.CheckpointId = "ckpt-1"
    val id2: String                       = "ckpt-1"
    assert(id1 == id2)
  }

  // ── Compile-negative obligations ──────────────────────────────────────────

  test("CheckpointStore referenced without type parameter does not compile") {
    // spec: checkpoint-store-fpoly — Compile-Negative: CheckpointStore without type parameter
    val errors: String = compileErrors("val store: CheckpointStore = ???")
    assert(errors.nonEmpty)
  }

  // ── Property (Ring 3) ─────────────────────────────────────────────────────

  property("F-polymorphism — inMemory satisfies get/set/delete/keys semantics") {
    // spec: checkpoint-store-fpoly — Property: F-polymorphism — inMemory satisfies get/set/delete/keys semantics
    for ops <- genCheckpointOpList.forAll
    yield
      val (store, ref): (CheckpointStore[IO], Ref[IO, Map[String, Array[Byte]]]) =
        (for
          s <- CheckpointStore.inMemory[IO]
          r <- Ref.of[IO, Map[String, Array[Byte]]](Map.empty)
        yield (s, r)).unsafeRunSync()
      val ok: Boolean = ops.forall { (op: CheckpointOp) =>
        op match
          case CheckpointOp.Set(id, data) =>
            store.set(id, data).unsafeRunSync()
            ref.update(_.updated(id, data)).unsafeRunSync()
            true
          case CheckpointOp.Get(id) =>
            val storeVal: Option[Array[Byte]] = store.get(id).unsafeRunSync()
            val refVal: Option[Array[Byte]]   = ref.get.unsafeRunSync().get(id)
            storeVal == refVal
          case CheckpointOp.Delete(id) =>
            store.delete(id).unsafeRunSync()
            ref.update(_ - id).unsafeRunSync()
            true
          case CheckpointOp.Keys =>
            val storeKeys: List[String] = store.keys.unsafeRunSync()
            val refKeys: List[String]   = ref.get.unsafeRunSync().keys.toList
            storeKeys.sorted == refKeys.sorted
      }
      ok ==== true
  }
