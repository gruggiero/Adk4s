$version: "2.0"

namespace org.adk4s.structured.test.vehicleinspection

structure VehicleInspection {
    @required inspectionId: String
    @required vin: String
    date: String
    mileage: Integer
    issuesFound: String
    status: String
}
