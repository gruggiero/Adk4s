package org.adk4s.core.interrupt

import upickle.default.*

/** Identifies a position in the agent hierarchy — either an agent or a tool. */
sealed trait AddressSegment derives ReadWriter:
  def name: String

object AddressSegment:
  final case class Agent(name: String) extends AddressSegment derives ReadWriter
  final case class Tool(name: String) extends AddressSegment derives ReadWriter
