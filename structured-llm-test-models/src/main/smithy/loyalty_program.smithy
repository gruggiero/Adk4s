$version: "2.0"
namespace org.adk4s.structured.test.loyaltyprogram

list StringList {
    member: String
}

structure LoyaltyProgram {
    @required memberId: String
    @required programName: String
    points: Integer
    tier: String
    benefits: StringList
    expiresAt: String
}
