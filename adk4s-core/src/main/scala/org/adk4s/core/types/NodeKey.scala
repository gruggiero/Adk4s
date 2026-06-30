package org.adk4s.core.types

import cats.{ Eq, Show, Order }
import org.adk4s.core.error.NodeKeyError

opaque type NodeKey = String

object NodeKey:
  val START: NodeKey = "__start__"
  val END: NodeKey   = "__end__"

  private val reservedKeys: Set[String] = Set("__start__", "__end__")

  def apply(key: String): Either[String, NodeKey] =
    if key.isEmpty then Left("Node key cannot be empty")
    else if reservedKeys.contains(key) then Left(s"Node key '$key' is reserved")
    else Right(key)

  /** Total smart constructor returning a typed error. */
  def from(key: String): Either[NodeKeyError, NodeKey] =
    if key.isEmpty then Left(NodeKeyError(key))
    else if reservedKeys.contains(key) then Left(NodeKeyError(key))
    else Right(key)

  /** Direct construction without validation. For use with hardcoded,
   * known-valid keys only. Does not throw. */
  def unsafeApply(key: String): NodeKey = key

  extension (key: NodeKey)
    def value: String       = key
    def isStart: Boolean    = key == START
    def isEnd: Boolean      = key == END
    def isReserved: Boolean = reservedKeys.contains(key)

  given Eq[NodeKey]    = Eq.fromUniversalEquals
  given Show[NodeKey]  = Show.show(_.value)
  given Order[NodeKey] = Order.by(_.value)
