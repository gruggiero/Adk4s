$version: "2.0"
namespace org.adk4s.structured.test.hrcandidate

list SkillList {
    member: String
}

structure HRCandidate {
    @required candidateId: String
    @required name: String
    email: String
    phone: String
    skills: SkillList
    experienceYears: Integer
    desiredSalary: Float
}
