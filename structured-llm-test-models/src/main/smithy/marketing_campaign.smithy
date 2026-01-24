$version: "2.0"
namespace org.adk4s.structured.test.marketingcampaign

list StringList {
    member: String
}

structure MarketingCampaign {
    @required campaignId: String
    @required name: String
    channel: String
    budget: Float
    currency: String
    startDate: String
    endDate: String
    goals: StringList
}
