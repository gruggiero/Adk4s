package org.adk4s.core.interrupt

import upickle.default.*

/** Represents an agent's request to pause execution and await external input. */
sealed trait InterruptSignal derives ReadWriter:
  def address: List[AddressSegment]
  def info: String

  /** Returns this signal with an updated address. */
  def withAddress(newAddress: List[AddressSegment]): InterruptSignal

object InterruptSignal:
  /** A stateless interrupt — no agent state needs to be persisted. */
  final case class Simple(
    address: List[AddressSegment],
    info: String
  ) extends InterruptSignal derives ReadWriter:
    def withAddress(newAddress: List[AddressSegment]): Simple =
      copy(address = newAddress)

  /** A stateful interrupt — carries serialized agent state for resumption. */
  final case class Stateful(
    address: List[AddressSegment],
    info: String,
    state: ujson.Value
  ) extends InterruptSignal derives ReadWriter:
    def withAddress(newAddress: List[AddressSegment]): Stateful =
      copy(address = newAddress)

  /** A composite interrupt — wraps child interrupts from nested agent-tools. */
  final case class Composite(
    address: List[AddressSegment],
    info: String,
    state: ujson.Value,
    children: List[InterruptSignal]
  ) extends InterruptSignal derives ReadWriter:
    def withAddress(newAddress: List[AddressSegment]): Composite =
      copy(address = newAddress)

  def simple(info: String): Simple =
    Simple(address = List.empty[AddressSegment], info = info)

  def stateful(info: String, state: ujson.Value): Stateful =
    Stateful(address = List.empty[AddressSegment], info = info, state = state)

  def composite(info: String, state: ujson.Value, children: List[InterruptSignal]): Composite =
    Composite(address = List.empty[AddressSegment], info = info, state = state, children = children)
