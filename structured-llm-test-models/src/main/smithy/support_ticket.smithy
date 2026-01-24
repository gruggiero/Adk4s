$version: "2.0"
namespace org.adk4s.structured.test.supportticket

structure SupportTicket {
    @required ticketId: String
    @required customerId: String
    subject: String
    description: String
    priority: String
    status: String
    createdAt: String
}
