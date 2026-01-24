$version: "2.0"

namespace org.adk4s.structured.test.order

list LineItems {
    member: String
}

structure Order {
    @required id: String
    @required customerId: String
    status: String
    total: Float
    currency: String
    items: LineItems
    createdAt: String
}
