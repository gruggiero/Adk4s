$version: "2.0"
namespace org.adk4s.structured.test.insuranceclaim

structure InsuranceClaim {
    @required claimId: String
    @required policyId: String
    claimantName: String
    incidentDate: String
    claimAmount: Float
    currency: String
    status: String
    description: String
}
