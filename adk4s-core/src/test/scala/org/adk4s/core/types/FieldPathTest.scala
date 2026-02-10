package org.adk4s.core.types

import munit.CatsEffectSuite

class FieldPathTest extends CatsEffectSuite:

  test("parse dotted path string") {
    val path = FieldPath("user.profile.name")
    assertEquals(path.segments, Vector("user", "profile", "name"))
  }

  test("parse empty path string") {
    val path = FieldPath("")
    assert(path.isEmpty)
  }

  test("parse single segment path") {
    val path = FieldPath("name")
    assertEquals(path.segments, Vector("name"))
  }

  test("Root is empty") {
    assert(FieldPath.Root.isEmpty)
  }

  test("Root segments are empty") {
    assertEquals(FieldPath.Root.segments, Vector.empty)
  }

  test("create path from segments") {
    val path = FieldPath.fromSegments("response", "data", "items")
    assertEquals(path.segments, Vector("response", "data", "items"))
  }

  test("create empty path from no segments") {
    val path = FieldPath.fromSegments()
    assert(path.isEmpty)
  }

  test("segments method returns vector") {
    val path = FieldPath("a.b.c")
    assertEquals(path.segments, Vector("a", "b", "c"))
  }

  test("isEmpty returns true for empty path") {
    val path = FieldPath.Root
    assert(path.isEmpty)
  }

  test("isEmpty returns false for non-empty path") {
    val path = FieldPath("a")
    assert(!path.isEmpty)
  }

  test("nonEmpty returns false for empty path") {
    val path = FieldPath.Root
    assert(!path.nonEmpty)
  }

  test("nonEmpty returns true for non-empty path") {
    val path = FieldPath("a")
    assert(path.nonEmpty)
  }

  test("head returns first segment") {
    val path = FieldPath("a.b.c")
    assertEquals(path.head, Some("a"))
  }

  test("head returns None for empty path") {
    val path = FieldPath.Root
    assertEquals(path.head, None)
  }

  test("tail returns remaining segments") {
    val path = FieldPath("a.b.c")
    assertEquals(path.tail.segments, Vector("b", "c"))
  }

  test("tail of single-element path is Root") {
    val path = FieldPath("a")
    assert(path.tail.isEmpty)
  }

  test("tail of empty path is Root") {
    val path = FieldPath.Root
    assert(path.tail.isEmpty)
  }

  test("append segment to path") {
    val path     = FieldPath("a.b")
    val extended = path :+ "c"
    assertEquals(extended.segments, Vector("a", "b", "c"))
  }

  test("append segment to empty path") {
    val path     = FieldPath.Root
    val extended = path :+ "a"
    assertEquals(extended.segments, Vector("a"))
  }

  test("concatenate two paths") {
    val path1    = FieldPath("a.b")
    val path2    = FieldPath("c.d")
    val combined = path1 ++ path2
    assertEquals(combined.segments, Vector("a", "b", "c", "d"))
  }

  test("concatenate with empty path") {
    val path1    = FieldPath("a.b")
    val path2    = FieldPath.Root
    val combined = path1 ++ path2
    assertEquals(combined.segments, Vector("a", "b"))
  }

  test("concatenate empty path with non-empty path") {
    val path1    = FieldPath.Root
    val path2    = FieldPath("a.b")
    val combined = path1 ++ path2
    assertEquals(combined.segments, Vector("a", "b"))
  }

  test("render path to dotted string") {
    val path = FieldPath("user.profile.name")
    assertEquals(path.render, "user.profile.name")
  }

  test("render empty path to empty string") {
    assertEquals(FieldPath.Root.render, "")
  }

  test("render single segment path") {
    val path = FieldPath("name")
    assertEquals(path.render, "name")
  }

  test("Show instance uses render") {
    val path = FieldPath("a.b.c")
    assertEquals(cats.Show[FieldPath].show(path), "a.b.c")
  }

  test("Show instance for empty path") {
    assertEquals(cats.Show[FieldPath].show(FieldPath.Root), "")
  }

  test("path fromSegments with single segment") {
    val path = FieldPath.fromSegments("test")
    assertEquals(path.segments, Vector("test"))
  }

  test("path with dots in segment names") {
    val path = FieldPath("user.profile.name")
    assertEquals(path.segments, Vector("user", "profile", "name"))
  }
