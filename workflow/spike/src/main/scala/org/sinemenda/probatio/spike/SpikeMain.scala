package org.sinemenda.probatio.spike

import mainargs.ParserForMethods
import mainargs.arg
import mainargs.main
import os.Path
import upickle.default.*

/**
 * V1 spike: prove GraalVM native-image can build a probatio-style CLI
 * (uPickle + os-lib + mainargs) WITHOUT hand-maintained reflection config.
 *
 * This is throwaway spike code, NOT spec-1 production. It exercises the
 * three GraalVM-risky libraries in the R-X3 allowed set:
 *   - uPickle/ujson: JSON read/write via derived ReadWriter (reflection)
 *   - os-lib: filesystem path operations (JNI)
 *   - mainargs: @main arg parsing (annotation reflection)
 *
 * If native-image builds this and it runs correctly, V1 is discharged for
 * the uPickle/os-lib/mainargs subset. scalameta is spike-gated separately
 * (R-N5) and not part of V1.
 */
object SpikeMain {

  /**
   * A case class with a derived uPickle ReadWriter — this is the reflection
   * path that native-image needs to handle.
   */
  case class GatePayload(
    decision: String,
    additionalContext: String,
    exitCode: Int
  )
  object GatePayload {
    given ReadWriter[GatePayload] = macroRW
  }

  @main
  def gate(
    @arg(name = "event", short = 'e')
    event: String
  ): Unit = {
    // Exercise os-lib (JNI / filesystem)
    val cwd: Path       = os.pwd
    val exists: Boolean = os.exists(cwd)

    // Exercise uPickle (reflection-based ReadWriter derivation)
    val payload: GatePayload = GatePayload(
      decision = "allow",
      additionalContext = s"event=$event cwd=$cwd exists=$exists",
      exitCode = 0
    )
    val json: String = write(payload)

    // Print JSON to stdout (the gate payload shape)
    println(json)
  }

  @main
  def version(): Unit =
    println("probatio-spike v1.0 (native-image V1 spike)")

  def main(args: Array[String]): Unit =
    ParserForMethods(this).runOrExit(args)
}
