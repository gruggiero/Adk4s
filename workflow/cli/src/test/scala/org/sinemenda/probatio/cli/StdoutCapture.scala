package org.sinemenda.probatio.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Serialises `System.setOut`/`System.setErr` capture across test suites.
 *
 * sbt runs test suites on parallel task groups in the same JVM
 * (`fork := false`), so two suites that redirect the global streams at
 * the same time interleave: one suite's output lands in the other's
 * buffer and both fail. Every capture in this package goes through this
 * single monitor.
 */
object StdoutCapture:

  /** Run `thunk` with both streams captured; returns (stdout, stderr, result). */
  def captureBoth[A](thunk: => A): (String, String, A) =
    StdoutCapture.synchronized {
      val outBuf: ByteArrayOutputStream = new ByteArrayOutputStream()
      val errBuf: ByteArrayOutputStream = new ByteArrayOutputStream()
      val outPs: PrintStream            = new PrintStream(outBuf, true, "UTF-8")
      val errPs: PrintStream            = new PrintStream(errBuf, true, "UTF-8")
      val oldOut: PrintStream           = System.out
      val oldErr: PrintStream           = System.err
      System.setOut(outPs)
      System.setErr(errPs)
      try
        val result: A = thunk
        outPs.flush()
        errPs.flush()
        (outBuf.toString("UTF-8"), errBuf.toString("UTF-8"), result)
      finally // scalafix:ok DisableSyntax.NoKeywordFinally
        // restores global streams; unmanaged JVM resources
        System.setOut(oldOut)
        System.setErr(oldErr)
    }

  /** Run `thunk` with stdout captured (stderr untouched). */
  def captureOut[A](thunk: => A): (String, A) =
    StdoutCapture.synchronized {
      val outBuf: ByteArrayOutputStream = new ByteArrayOutputStream()
      val outPs: PrintStream            = new PrintStream(outBuf, true, "UTF-8")
      val oldOut: PrintStream           = System.out
      System.setOut(outPs)
      try
        val result: A = thunk
        outPs.flush()
        (outBuf.toString("UTF-8"), result)
      finally // scalafix:ok DisableSyntax.NoKeywordFinally
        // restores the global stream; unmanaged JVM resource
        System.setOut(oldOut)
    }
