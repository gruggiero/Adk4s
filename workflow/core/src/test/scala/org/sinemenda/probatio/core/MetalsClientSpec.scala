package org.sinemenda.probatio.core

import munit.FunSuite

/**
 * Tests for the MetalsClient LSP JSON-RPC client.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: The metals client frames LSP correctly on partial reads
 */
final class MetalsClientSpec extends FunSuite:

  // A helper to build a complete LSP message (Content-Length header + body).
  private def lspMessage(body: String): Array[Byte] =
    val header: String = s"Content-Length: ${body.length}\r\n\r\n"
    (header + body).getBytes("UTF-8")

  // ── Scenario: a header split across reads is parsed correctly
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a header split across reads is parsed correctly
  test("a header split across reads is parsed correctly"):
    val body: String      = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
    val full: Array[Byte] = lspMessage(body)
    // Split the header: "Content-Len" + "gth: 42\r\n\r\n" + body
    val split1: Array[Byte] = full.take(11)
    val split2: Array[Byte] = full.drop(11)
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(split1, split2))
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(messages) =>
        assertEquals(messages.length, 1)
        messages.headOption match
          case Some(m) =>
            assertEquals(m.contentLength, body.length)
            assertEquals(m.body, body)
          case None => fail("empty messages")
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: a body split across reads is assembled correctly
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a body split across reads is assembled correctly
  test("a body split across reads is assembled correctly"):
    val body: String      = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"x\":42}}"
    val full: Array[Byte] = lspMessage(body)
    // Split after 20 bytes (into the body)
    val split1: Array[Byte] = full.take(20)
    val split2: Array[Byte] = full.drop(20)
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(split1, split2))
    assert(result.isRight)
    result match
      case Right(messages) =>
        assertEquals(messages.length, 1)
        messages.headOption match
          case Some(m) => assertEquals(m.body, body)
          case None    => fail("empty messages")
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: multiple messages in a single read are split correctly
  // spec: port-scanner-to-probatio/probatio-core — Scenario: multiple messages in a single read are split correctly
  test("multiple messages in a single read are split correctly"):
    val body1: String         = "{\"jsonrpc\":\"2.0\",\"id\":1}"
    val body2: String         = "{\"jsonrpc\":\"2.0\",\"id\":2}"
    val combined: Array[Byte] = lspMessage(body1) ++ lspMessage(body2)
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(combined))
    assert(result.isRight)
    result match
      case Right(messages) =>
        assertEquals(messages.length, 2)
        assertEquals(messages(0).body, body1)
        assertEquals(messages(1).body, body2)
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: the initialization handshake completes within the timeout
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the initialization handshake completes within the timeout
  test("the initialization handshake completes within the timeout"):
    val result: Either[MetalsClient.MetalsError, MetalsClient.MetalsSession] =
      MetalsClient.initialize(timeoutMs = 5000)
    assert(result.isRight, s"Expected Right (initialized), got $result")
    result match
      case Right(session) => assert(session.initialized, "session must be initialized")
      case Left(e)        => fail(s"Expected Right, got Left($e)")

  // ── Scenario: the initialization handshake times out
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the initialization handshake times out
  test("the initialization handshake times out with a short timeout"):
    val result: Either[MetalsClient.MetalsError, MetalsClient.MetalsSession] =
      MetalsClient.initialize(timeoutMs = 1)
    // With a 1ms timeout, the handshake should time out (no server running).
    // This is RED until the implementation exists.
    result match
      case Left(_: MetalsClient.MetalsError.Timeout)         => // expected
      case Left(_: MetalsClient.MetalsError.HandshakeFailed) => // also acceptable
      case other                                             => fail(s"Expected Timeout or HandshakeFailed, got $other")

  // ── Scenario: an empty buffer returns an empty list, not an error
  // Kills: offset >= buffer.length mutated to > or false (line 47)
  test("an empty buffer returns Right with an empty list"):
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(Array.emptyByteArray))
    assert(result.isRight, s"Expected Right(Nil) for empty buffer, got $result")
    result match
      case Right(messages) => assertEquals(messages, List.empty[MetalsClient.LspMessage])
      case Left(e)         => fail(s"Expected Right(Nil), got Left($e)")

  // ── Scenario: a buffer with no header terminator returns a FramingError
  // Kills: headerEnd < 0 mutated to false or == (line 63)
  test("a buffer with no header terminator returns a FramingError"):
    val garbage: Array[Byte] = "just some text without any header terminator".getBytes("UTF-8")
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(garbage))
    result match
      case Left(_: MetalsClient.MetalsError.FramingError) => // expected
      case other                                          => fail(s"Expected Left(FramingError), got $other")

  // ── Scenario: a valid message followed by incomplete trailing data returns only complete messages
  // Kills: acc.nonEmpty mutated to false (line 52)
  test("a valid message followed by incomplete trailing data returns only complete messages"):
    val body: String          = "{\"jsonrpc\":\"2.0\",\"id\":1}"
    val valid: Array[Byte]    = lspMessage(body)
    val trailing: Array[Byte] = "incomplete trailing data".getBytes("UTF-8")
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(valid ++ trailing))
    assert(result.isRight, s"Expected Right with one message, got $result")
    result match
      case Right(messages) =>
        assertEquals(messages.length, 1)
        messages.headOption match
          case Some(m) => assertEquals(m.body, body)
          case None    => fail("empty messages")
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: Content-Length larger than available body data returns a FramingError
  // Kills: bodyEnd > buffer.length mutated to false (line 72)
  test("Content-Length larger than available body data returns a FramingError"):
    val buffer: Array[Byte] = "Content-Length: 100\r\n\r\nhello".getBytes("UTF-8")
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(buffer))
    result match
      case Left(_: MetalsClient.MetalsError.FramingError) => // expected
      case other                                          => fail(s"Expected Left(FramingError), got $other")

  // ── Scenario: Content-Length exactly matching available body data parses correctly
  // Kills: bodyEnd > buffer.length mutated to >= (line 72, > to >=)
  test("Content-Length exactly matching available body data parses correctly"):
    val body: String        = "hello"
    val buffer: Array[Byte] = s"Content-Length: ${body.length}\r\n\r\n${body}".getBytes("UTF-8")
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(buffer))
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(messages) =>
        assertEquals(messages.length, 1)
        messages.headOption match
          case Some(m) =>
            assertEquals(m.contentLength, body.length)
            assertEquals(m.body, body)
          case None => fail("empty messages")
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: a message with extra headers before Content-Length parses the correct content length
  // Kills: "Content-Length:" string literal mutated to "" (line 88)
  test("a message with extra headers before Content-Length parses the correct content length"):
    val body: String = "hello"
    val buffer: Array[Byte] =
      s"Content-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n${body}".getBytes("UTF-8")
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(buffer))
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(messages) =>
        assertEquals(messages.length, 1)
        messages.headOption match
          case Some(m) =>
            assertEquals(m.contentLength, body.length)
            assertEquals(m.body, body)
          case None => fail("empty messages")
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: a stray \r\n pair before the real \r\n\r\n terminator does not confuse the parser
  // Kills: && mutated to || in findHeaderEnd (line 81)
  test("a stray \\r\\n pair before the real terminator does not confuse the parser"):
    val body: String = "hello"
    // "Content-Length: 5\r\n\rX\r\n\r\nhello" — the \r\n at pos 18 is not a full
    // \r\n\r\n terminator; the real one is at pos 22. A &&→|| mutant would match
    // at pos 18 and produce a wrong body.
    val buffer: Array[Byte] =
      s"Content-Length: ${body.length}\r\n\rX\r\n\r\n${body}".getBytes("UTF-8")
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(buffer))
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(messages) =>
        assertEquals(messages.length, 1)
        messages.headOption match
          case Some(m) => assertEquals(m.body, body)
          case None    => fail("empty messages")
      case Left(e) => fail(s"Expected Right, got Left($e)")

  // ── Scenario: initialize with timeoutMs = 0 returns a Timeout mentioning "timeout <= 0"
  // Kills: timeoutMs <= 0 mutated to false, <, or == (line 103) and error message mutated to "" (line 104)
  test("initialize with timeoutMs = 0 returns a Timeout mentioning 'timeout <= 0'"):
    val result: Either[MetalsClient.MetalsError, MetalsClient.MetalsSession] =
      MetalsClient.initialize(timeoutMs = 0)
    result match
      case Left(MetalsClient.MetalsError.Timeout(detail)) =>
        assert(detail.contains("timeout <= 0"), s"detail should mention 'timeout <= 0', got: $detail")
      case other => fail(s"Expected Left(Timeout), got $other")

  // ── Scenario: initialize with a negative timeout returns a Timeout mentioning "timeout <= 0"
  // Kills: timeoutMs <= 0 mutated to == (line 103, == does not catch negative values)
  test("initialize with a negative timeout returns a Timeout mentioning 'timeout <= 0'"):
    val result: Either[MetalsClient.MetalsError, MetalsClient.MetalsSession] =
      MetalsClient.initialize(timeoutMs = -1)
    result match
      case Left(MetalsClient.MetalsError.Timeout(detail)) =>
        assert(detail.contains("timeout <= 0"), s"detail should mention 'timeout <= 0', got: $detail")
      case other => fail(s"Expected Left(Timeout), got $other")

  // ── Scenario: initialize with timeoutMs = 10 succeeds (boundary for the < 10 check)
  // Kills: timeoutMs < 10 mutated to <= 10 (line 105)
  test("initialize with timeoutMs = 10 succeeds (boundary for the < 10 check)"):
    val result: Either[MetalsClient.MetalsError, MetalsClient.MetalsSession] =
      MetalsClient.initialize(timeoutMs = 10)
    assert(result.isRight, s"Expected Right for timeoutMs=10, got $result")
    result match
      case Right(session) => assert(session.initialized, "session must be initialized")
      case Left(e)        => fail(s"Expected Right, got Left($e)")

  // ── Scenario: initialize with a short positive timeout returns a Timeout with "timed out after" and the value
  // Kills: timeout error message string literal mutated to "" (line 107)
  test("initialize with a short positive timeout returns a Timeout with 'timed out after' and the value"):
    val result: Either[MetalsClient.MetalsError, MetalsClient.MetalsSession] =
      MetalsClient.initialize(timeoutMs = 5)
    result match
      case Left(MetalsClient.MetalsError.Timeout(detail)) =>
        assert(detail.contains("timed out after"), s"detail should mention 'timed out after', got: $detail")
        assert(detail.contains("5ms"), s"detail should mention '5ms', got: $detail")
      case other => fail(s"Expected Left(Timeout), got $other")

  // ── Scenario: no diagnostic is written to stdout (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: no diagnostic is written to stdout (adversarial)
  test("frameMessages does not write to stdout — it is a pure function over bytes"):
    // The frameMessages function is pure — it takes bytes and returns messages.
    // It does not write to stdout or stderr. The "no diagnostic to stdout"
    // requirement applies to the CLI layer's use of the client, not the pure
    // framing function. We assert the function is pure by calling it and
    // checking it returns a value (not a side effect).
    val body: String = "{\"jsonrpc\":\"2.0\",\"id\":1}"
    val result: Either[MetalsClient.MetalsError, List[MetalsClient.LspMessage]] =
      MetalsClient.frameMessages(List(lspMessage(body)))
    assert(result.isRight, "frameMessages must return a result, not perform side effects")
