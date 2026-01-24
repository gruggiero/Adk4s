$version: "2.0"
namespace org.adk4s.structured.test.healthcareappointment

structure Patient {
    name: String
    dob: String
    email: String
}

structure HealthcareAppointment {
    @required appointmentId: String
    @required patient: Patient
    doctorName: String
    department: String
    scheduledAt: String
    status: String
    notes: String
}
