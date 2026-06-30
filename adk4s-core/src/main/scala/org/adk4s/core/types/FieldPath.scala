package org.adk4s.core.types

import cats.Show

opaque type FieldPath = Vector[String]

object FieldPath:
  val Root: FieldPath = Vector.empty

  def apply(path: String): FieldPath =
    if path.isEmpty then Root
    else Vector.from(path.split('.'))

  def fromSegments(segments: String*): FieldPath =
    Vector.from(segments)

  extension (path: FieldPath)
    def segments: Vector[String]        = path
    def isEmpty: Boolean                = path.isEmpty
    def nonEmpty: Boolean               = path.nonEmpty
    def head: Option[String]            = path.headOption
    def tail: FieldPath                 = if path.isEmpty then Root else path.drop(1)
    def :+(segment: String): FieldPath  = path :+ segment
    def ++(other: FieldPath): FieldPath = path ++ other
    def render: String                  = path.mkString(".")

  given Show[FieldPath] = Show.show(_.render)
