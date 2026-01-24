$version: "2.0"
namespace org.adk4s.structured.test.eventregistration

structure Attendee {
    name: String
    email: String
    ticketType: String
}

structure EventRegistration {
    @required registrationId: String
    @required eventId: String
    attendee: Attendee
    status: String
    paid: Boolean
    price: Float
    currency: String
}
