$version: "2.0"
namespace org.adk4s.structured.test.productcatalog

list StringList {
    member: String
}

structure Product {
    @required sku: String
    @required name: String
    description: String
    price: Float
    currency: String
    categories: StringList
    active: Boolean
}
