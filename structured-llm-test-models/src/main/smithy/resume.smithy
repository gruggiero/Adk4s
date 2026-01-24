$version: "2.0"

namespace org.adk4s.structured.test

/// A resume containing personal and professional information
structure Resume {
    /// The person's full name
    @required
    name: String
    
    /// Email address (optional)
    email: String
    
    /// List of technical and soft skills
    @required
    skills: StringList
    
    /// Educational background
    education: EducationList
    
    /// Experience level
    @required
    seniority: SeniorityLevel
}

/// An educational institution entry
structure Education {
    /// Name of the school or university
    @required
    school: String
    
    /// Degree or certificate obtained
    @required
    degree: String
    
    /// Graduation year
    year: Integer
}

/// Experience level enumeration
enum SeniorityLevel {
    /// 0-2 years of experience
    @documentation("0-2 years of experience")
    JUNIOR
    
    /// 2-5 years of experience
    @documentation("2-5 years of experience")
    MID
    
    /// 5-10 years of experience
    @documentation("5-10 years of experience")
    SENIOR
    
    /// 10+ years, technical leadership
    @documentation("10+ years, technical leadership")
    STAFF
}

/// List of strings
@length(min: 0)
list StringList {
    member: String
}

/// List of education entries
@length(min: 0)
list EducationList {
    member: Education
}
