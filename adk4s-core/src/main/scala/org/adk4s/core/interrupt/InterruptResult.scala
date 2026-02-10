package org.adk4s.core.interrupt

import upickle.default.*

/** Pairs an address with resume data provided by the user/system for a specific interrupt point. */
final case class InterruptResult(
  address: List[AddressSegment],
  data: ujson.Value
) derives ReadWriter
