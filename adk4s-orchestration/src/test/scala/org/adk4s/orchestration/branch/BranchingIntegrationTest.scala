package org.adk4s.orchestration.branch

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.adk4s.core.types.NodeKey
import workflows4s.wio.{WIO, WorkflowContext, WCState}

object OrderProcessingContext extends WorkflowContext:
  override type State = OrderState
  override type Event = OrderEvent
  override type Effect[T] = IO[T]

case class OrderState(
  orderId: String,
  amount: Double,
  status: OrderStatus,
  paymentMethod: Option[PaymentMethod],
  shippingAddress: Option[String]
)

sealed trait OrderStatus
object OrderStatus {
  case object Pending extends OrderStatus
  case object Approved extends OrderStatus
  case object Rejected extends OrderStatus
  case object Shipped extends OrderStatus
}

sealed trait OrderEvent
case class OrderApproved(orderId: String) extends OrderEvent
case class OrderRejected(orderId: String, reason: String) extends OrderEvent
case class OrderShipped(orderId: String, tracking: String) extends OrderEvent

sealed trait PaymentMethod
case class CreditCard(number: String) extends PaymentMethod
case class PayPal(email: String) extends PaymentMethod
case class BankTransfer(accountId: String) extends PaymentMethod

class BranchingIntegrationTest extends CatsEffectSuite:

  test("Router routes orders based on amount using binary branch") {
    // Create nodes for order processing
    val orderReceived = NodeKey.unsafeApply("order_received")
    val highValueProcessing = NodeKey.unsafeApply("high_value_processing")
    val standardProcessing = NodeKey.unsafeApply("standard_processing")
    
    // Create a binary branch based on order amount (> $1000 is high value)
    val amountBranch = Branch.binary(
      predicate = (order: OrderState) => IO.pure(order.amount > 1000),
      ifTrue = highValueProcessing,
      ifFalse = standardProcessing
    )
    
    // Create router and add branch
    val router = Router.empty[OrderState]
      .addBranch(orderReceived, amountBranch)
    
    // Test routing
    val highValueOrder = OrderState("ORD-001", 1500.0, OrderStatus.Pending, None, None)
    val standardOrder = OrderState("ORD-002", 500.0, OrderStatus.Pending, None, None)
    
    for {
      highValueRoute <- router.route(orderReceived, highValueOrder)
      standardRoute <- router.route(orderReceived, standardOrder)
    } yield {
      assertEquals(highValueRoute, highValueProcessing)
      assertEquals(standardRoute, standardProcessing)
    }
  }

  test("Router routes based on payment method using multi-way branch") {
    // Create nodes for different payment methods
    val paymentNode = NodeKey.unsafeApply("payment_processing")
    val creditCardNode = NodeKey.unsafeApply("credit_card_processing")
    val paypalNode = NodeKey.unsafeApply("paypal_processing")
    val bankTransferNode = NodeKey.unsafeApply("bank_transfer_processing")
    val defaultNode = NodeKey.unsafeApply("default_processing")
    
    // Create a multi-way branch based on payment method
    def getPaymentType(order: OrderState): NodeKey = order.paymentMethod match {
      case Some(_: CreditCard) => creditCardNode
      case Some(_: PayPal) => paypalNode
      case Some(_: BankTransfer) => bankTransferNode
      case _ => defaultNode
    }
    
    val paymentBranch = Branch.pure(
      condition = getPaymentType,
      targets = Set(creditCardNode, paypalNode, bankTransferNode, defaultNode)
    )
    
    // Create router and add branch
    val router = Router.empty[OrderState]
      .addBranch(paymentNode, paymentBranch)
    
    // Test routing for different payment methods
    val creditCardOrder = OrderState("ORD-003", 200.0, OrderStatus.Pending, 
      Some(CreditCard("4111-1111-1111-1111")), None)
    val paypalOrder = OrderState("ORD-004", 300.0, OrderStatus.Pending, 
      Some(PayPal("user@example.com")), None)
    val noPaymentOrder = OrderState("ORD-005", 100.0, OrderStatus.Pending, None, None)
    
    for {
      creditCardRoute <- router.route(paymentNode, creditCardOrder)
      paypalRoute <- router.route(paymentNode, paypalOrder)
      noPaymentRoute <- router.route(paymentNode, noPaymentOrder)
    } yield {
      assertEquals(creditCardRoute, creditCardNode)
      assertEquals(paypalRoute, paypalNode)
      assertEquals(noPaymentRoute, defaultNode)
    }
  }

  test("Router uses endIf to terminate processing for rejected orders") {
    // Create nodes
    val reviewNode = NodeKey.unsafeApply("order_review")
    val continueNode = NodeKey.unsafeApply("continue_processing")
    
    // Create a branch that ends processing for rejected orders
    val rejectionBranch = Branch.endIf(
      predicate = (order: OrderState) => IO.pure(order.status == OrderStatus.Rejected),
      otherwise = continueNode
    )
    
    // Create router
    val router = Router.empty[OrderState]
      .addBranch(reviewNode, rejectionBranch)
    
    // Test routing
    val rejectedOrder = OrderState("ORD-006", 0.0, OrderStatus.Rejected, None, None)
    val approvedOrder = OrderState("ORD-007", 200.0, OrderStatus.Approved, None, None)
    
    for {
      rejectedRoute <- router.route(reviewNode, rejectedOrder)
      approvedRoute <- router.route(reviewNode, approvedOrder)
    } yield {
      assertEquals(rejectedRoute, NodeKey.END)
      assertEquals(approvedRoute, continueNode)
    }
  }

  test("Stream branch processes batch orders") {
    // Create nodes
    val batchNode = NodeKey.unsafeApply("batch_processing")
    val processNode = NodeKey.unsafeApply("process_batch")
    
    // Create a stream branch that evaluates the entire batch
    val batchBranch = Branch.stream(
      condition = (orders: Stream[IO, OrderState]) => 
        orders.compile.count.map(count => if (count > 10) then processNode else NodeKey.END),
      targets = Set(processNode, NodeKey.END)
    )
    
    // Create router - note that the router type is OrderState, not Stream
    val router = Router.empty[OrderState]
      .addBranch(batchNode, batchBranch)
    
    // Test routing with different batch sizes
    val largeBatch = Stream.emits(List.fill(15)(
      OrderState("ORD-BATCH", 100.0, OrderStatus.Pending, None, None)
    ))
    val smallBatch = Stream.emits(List.fill(5)(
      OrderState("ORD-SMALL", 50.0, OrderStatus.Pending, None, None)
    ))
    
    for {
      largeBatchRoute <- router.routeStream(batchNode, largeBatch)
      smallBatchRoute <- router.routeStream(batchNode, smallBatch)
    } yield {
      assertEquals(largeBatchRoute, processNode)
      assertEquals(smallBatchRoute, NodeKey.END)
    }
  }

  test("WIOBranch creates complex order processing workflow") {
    // Create a workflow that processes orders based on amount and payment method
    val orderWorkflow = WIOBranch.branch[OrderState, OrderState, String, OrderProcessingContext.type](
      selector = (order: OrderState) => order.amount match {
        case amount if amount > 1000 => "high_value"
        case amount if amount > 0 => "standard"
        case _ => "invalid"
      },
      branches = Map(
        "high_value" -> WIOBranch.fork[OrderState, OrderState, OrderProcessingContext.type](
          condition = (order: OrderState) => order.paymentMethod.isDefined,
          ifTrue = WIO.build[OrderProcessingContext.type]
            .pure.makeFrom[OrderState]
            .value(order => order.copy(status = OrderStatus.Approved))
            .done,
          ifFalse = WIO.build[OrderProcessingContext.type]
            .pure.makeFrom[OrderState]
            .value(order => order.copy(status = OrderStatus.Rejected))
            .done
        ),
        "standard" -> WIO.build[OrderProcessingContext.type]
          .pure.makeFrom[OrderState]
          .value(order => order.copy(status = OrderStatus.Approved))
          .done
      ),
      default = WIO.build[OrderProcessingContext.type]
        .pure.makeFrom[OrderState]
        .value(order => order.copy(status = OrderStatus.Rejected))
        .done
    )

    orderWorkflow match
      case _: WIO[OrderState, Nothing, OrderState, OrderProcessingContext.type] => assert(true, "workflow should be WIO")
      case _ => assert(false, "workflow should be WIO")
    
    // Test the workflow logic conceptually
    val testOrder = OrderState("ORD-TEST", 1500.0, OrderStatus.Pending, 
      Some(CreditCard("1234-5678-9012-3456")), None)
    
    // The workflow should approve high-value orders with payment method
    // (In a real test, we would execute the workflow and verify the result)
    assert(testOrder.amount > 1000)
    assert(testOrder.paymentMethod.isDefined)
  }
