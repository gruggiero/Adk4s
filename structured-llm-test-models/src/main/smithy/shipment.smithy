$version: "2.0"
namespace org.adk4s.structured.test.shipment

structure Address {
    street: String
    city: String
    state: String
    postalCode: String
    country: String
}

structure Shipment {
    @required shipmentId: String
    @required orderId: String
    carrier: String
    trackingNumber: String
    destination: Address
    status: String
    estimatedDelivery: String
}
