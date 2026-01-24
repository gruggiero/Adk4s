$version: "2.0"
namespace org.adk4s.structured.test.payment

structure Payment {
    @required paymentId: String
    @required orderId: String
    method: String
    amount: Float
    currency: String
    status: String
    processedAt: String
}
