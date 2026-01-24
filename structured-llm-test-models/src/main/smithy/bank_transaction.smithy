$version: "2.0"

namespace org.adk4s.structured.test.banktransaction

structure BankTransaction {
    @required transactionId: String
    @required accountId: String
    type: String
    amount: Float
    currency: String
    timestamp: String
    description: String
}
