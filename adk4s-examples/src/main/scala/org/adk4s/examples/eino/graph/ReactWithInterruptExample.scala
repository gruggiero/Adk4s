package org.adk4s.examples.eino.graph

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.interrupt.CheckpointStore
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.adk4s.orchestration.interrupt.InterruptInfo
import org.adk4s.orchestration.interrupt.InterruptResult
import org.adk4s.orchestration.interrupt.InterruptibleNode

/**
 * Eino equivalent: compose/graph/react_with_interrupt
 *
 * Demonstrates human-in-the-loop interruption using InterruptibleNode
 * and CheckpointStore. A ticket booking workflow pauses for human
 * approval before executing the actual booking.
 *
 * Scenarios:
 *   1. Booking that requires approval → interrupt → approve → complete
 *   2. Booking that requires approval → interrupt → reject
 *   3. Booking that does NOT require approval → completes immediately
 */
object ReactWithInterruptExample extends IOApp.Simple:

  // --- Domain types ---

  final case class BookingRequest(
    ticketId: String,
    destination: String,
    price: Double,
    requiresApproval: Boolean
  )

  final case class BookingConfirmation(
    ticketId: String,
    destination: String,
    price: Double,
    status: String
  )

  // --- Booking action ---

  private val bookTicket: BookingRequest => IO[BookingConfirmation] =
    (req: BookingRequest) => IO.delay {
      BookingConfirmation(
        ticketId = req.ticketId,
        destination = req.destination,
        price = req.price,
        status = "CONFIRMED"
      )
    }

  // --- Interrupt predicate ---

  private val needsApproval: BookingRequest => Boolean =
    (req: BookingRequest) => req.requiresApproval

  // --- Interrupt info builder ---

  private val buildInterruptInfo: BookingRequest => IO[InterruptInfo] =
    (req: BookingRequest) => IO.pure(InterruptInfo(
      checkpointId = s"booking-${req.ticketId}",
      description = s"Booking ${req.ticketId} to ${req.destination} for $$${req.price} requires human approval",
      serializedState = s"${req.ticketId}|${req.destination}|${req.price}".getBytes
    ))

  // --- Scenarios ---

  private def runApproveScenario(node: InterruptibleNode[BookingRequest, BookingConfirmation]): IO[Unit] =
    val request: BookingRequest = BookingRequest("TKT-001", "Tokyo", 1200.0, requiresApproval = true)
    for
      _ <- ExampleUtils.printSubSection("Scenario 1: Interrupt → Approve → Complete")
      _ <- IO.println(s"   Submitting booking: ${request.ticketId} to ${request.destination}")
      result1 <- node.invoke(request)
      _ <- result1 match
        case InterruptResult.Interrupted(info) =>
          IO.println(s"   ⏸ Interrupted: ${info.description}") *>
          IO.println(s"   [Human approves booking...]") *>
          node.resume(info.checkpointId, approved = true, input = request).flatMap {
            case InterruptResult.Completed(conf) =>
              IO.println(s"   ✓ Booking completed: ${conf.ticketId} → ${conf.status}")
            case other =>
              IO.println(s"   Unexpected result: $other")
          }
        case InterruptResult.Completed(conf) =>
          IO.println(s"   ✓ Booking completed immediately: ${conf.ticketId}")
        case InterruptResult.Rejected(cpId) =>
          IO.println(s"   ✗ Rejected: $cpId")
    yield ()

  private def runRejectScenario(node: InterruptibleNode[BookingRequest, BookingConfirmation]): IO[Unit] =
    val request: BookingRequest = BookingRequest("TKT-002", "Paris", 2500.0, requiresApproval = true)
    for
      _ <- ExampleUtils.printSubSection("Scenario 2: Interrupt → Reject")
      _ <- IO.println(s"   Submitting booking: ${request.ticketId} to ${request.destination}")
      result1 <- node.invoke(request)
      _ <- result1 match
        case InterruptResult.Interrupted(info) =>
          IO.println(s"   ⏸ Interrupted: ${info.description}") *>
          IO.println(s"   [Human rejects booking...]") *>
          node.resume(info.checkpointId, approved = false, input = request).flatMap {
            case InterruptResult.Rejected(cpId) =>
              IO.println(s"   ✗ Booking rejected: $cpId")
            case other =>
              IO.println(s"   Unexpected result: $other")
          }
        case other =>
          IO.println(s"   Unexpected: $other")
    yield ()

  private def runNoApprovalScenario(node: InterruptibleNode[BookingRequest, BookingConfirmation]): IO[Unit] =
    val request: BookingRequest = BookingRequest("TKT-003", "Osaka", 400.0, requiresApproval = false)
    for
      _ <- ExampleUtils.printSubSection("Scenario 3: No Approval Needed → Complete Immediately")
      _ <- IO.println(s"   Submitting booking: ${request.ticketId} to ${request.destination}")
      result <- node.invoke(request)
      _ <- result match
        case InterruptResult.Completed(conf) =>
          IO.println(s"   ✓ Booking completed immediately: ${conf.ticketId} → ${conf.status}")
        case other =>
          IO.println(s"   Unexpected: $other")
    yield ()

  // --- Main ---

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("ReactWithInterrupt Example (Eino: graph/react_with_interrupt)")
      store <- InMemoryCheckpointStore.create
      node = InterruptibleNode.create[BookingRequest, BookingConfirmation](
        shouldInterrupt = needsApproval,
        innerAction = bookTicket,
        onInterrupt = buildInterruptInfo,
        store = store
      )
      _ <- runApproveScenario(node)
      _ <- runRejectScenario(node)
      _ <- runNoApprovalScenario(node)
      _ <- IO.println("\n=== ReactWithInterrupt Example Completed ===")
    yield ()
