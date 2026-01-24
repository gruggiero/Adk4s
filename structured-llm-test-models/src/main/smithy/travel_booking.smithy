$version: "2.0"
namespace org.adk4s.structured.test.travelbooking

structure Traveler {
    name: String
    email: String
}

structure TravelBooking {
    @required bookingId: String
    @required traveler: Traveler
    destination: String
    departureDate: String
    returnDate: String
    price: Float
    currency: String
    status: String
}
