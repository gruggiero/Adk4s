$version: "2.0"
namespace org.adk4s.structured.test.subscriptionplan

list FeatureList {
    member: String
}

structure SubscriptionPlan {
    @required planId: String
    @required name: String
    price: Float
    currency: String
    billingCycle: String
    features: FeatureList
    active: Boolean
}
