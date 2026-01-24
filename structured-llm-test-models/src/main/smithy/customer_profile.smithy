$version: "2.0"
namespace org.adk4s.structured.test.customerprofile

list StringList {
    member: String
}

structure CustomerProfile {
    @required id: String
    @required name: String
    email: String
    age: Integer
    active: Boolean
    tags: StringList
}
