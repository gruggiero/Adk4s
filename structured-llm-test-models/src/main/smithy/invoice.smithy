$version: "2.0"
namespace org.adk4s.structured.test.invoice

list LineItems {
    member: String
}

structure Invoice {
    @required invoiceId: String
    @required customerId: String
    amountDue: Float
    currency: String
    lineItems: LineItems
    dueDate: String
}
