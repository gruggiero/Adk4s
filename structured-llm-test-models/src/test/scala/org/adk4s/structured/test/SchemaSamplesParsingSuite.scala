package org.adk4s.structured.test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import scala.io.Source
import scala.util.Using

import munit.FunSuite
import org.adk4s.structured.core.ParseResult
import org.adk4s.structured.core.Schema
import org.adk4s.structured.sap.SchemaAlignedParser
import org.adk4s.structured.test.banktransaction.BankTransaction
import org.adk4s.structured.test.customerprofile.CustomerProfile
import org.adk4s.structured.test.eventregistration.EventRegistration
import org.adk4s.structured.test.healthcareappointment.HealthcareAppointment
import org.adk4s.structured.test.hrcandidate.HRCandidate
import org.adk4s.structured.test.insuranceclaim.InsuranceClaim
import org.adk4s.structured.test.inventoryitem.InventoryItem
import org.adk4s.structured.test.invoice.Invoice
import org.adk4s.structured.test.loyaltyprogram.LoyaltyProgram
import org.adk4s.structured.test.marketingcampaign.MarketingCampaign
import org.adk4s.structured.test.order.Order
import org.adk4s.structured.test.payment.Payment
import org.adk4s.structured.test.productcatalog.Product
import org.adk4s.structured.test.projecttask.ProjectTask
import org.adk4s.structured.test.shipment.Shipment
import org.adk4s.structured.test.subscriptionplan.SubscriptionPlan
import org.adk4s.structured.test.supportticket.SupportTicket
import org.adk4s.structured.test.travelbooking.TravelBooking
import org.adk4s.structured.test.vehicleinspection.VehicleInspection
import smithy4s.Schema as Smithy4sSchema

final class SchemaSamplesParsingSuite extends FunSuite:

  private def loadDefinition(path: String): String =
    val definition: String = Using.resource(Source.fromFile(path))(_.mkString)
    definition

  private val sampleSeparator: String = "---SAMPLE---"
  private val smithyRoot: String      = "structured-llm-test-models/src/main/smithy/"

  private def loadSamples(resourceName: String): List[String] =
    val content: String = Using.resource(Source.fromResource(resourceName))(_.mkString)
    content
      .split(sampleSeparator)
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)

  private case class SampleReport(
    cleanParsed: Int,
    recoveredParsed: Int,
    recoveredWithWarnings: List[(String, List[String])],
    failures: List[(String, List[String])]
  )

  private def summarizeSamples[A](samples: List[String], schema: Schema[A], label: String): SampleReport =
    val parsed: List[(String, ParseResult[A])] = samples.map { sample =>
      sample -> SchemaAlignedParser.parse[A](sample)(using schema)
    }
    val cleanParsed: Int = parsed.count {
      case (_, ParseResult.Success(_, warnings)) if warnings.isEmpty => true
      case _                                                         => false
    }
    val recoveredParsed: Int = parsed.count {
      case (_, ParseResult.Success(_, warnings)) if warnings.nonEmpty => true
      case _                                                          => false
    }
    val recoveredWithWarnings: List[(String, List[String])] = parsed.collect {
      case (sample, ParseResult.Success(_, warnings)) if warnings.nonEmpty => sample -> warnings
    }
    val failures: List[(String, List[String])] = parsed.collect { case (sample, ParseResult.Failure(errors)) =>
      sample -> errors.map(_.message)
    }
    SampleReport(cleanParsed, recoveredParsed, recoveredWithWarnings, failures)

  private def writeReport(label: String, report: SampleReport): Unit =
    val directory = Paths.get("target/sample-reports")
    Files.createDirectories(directory)
    val recoveredReport: String =
      if report.recoveredWithWarnings.isEmpty then "none"
      else
        report.recoveredWithWarnings
          .map { case (sample, warnings) =>
            val preview: String = sample.replaceAll("\\s+", " ").take(120)
            s"- $preview | warnings: ${warnings.mkString("; ")}"
          }
          .mkString("\n")
    val failureReport: String =
      if report.failures.isEmpty then "none"
      else
        report.failures
          .map { case (sample, errors) =>
            val preview: String = sample.replaceAll("\\s+", " ").take(120)
            s"- $preview | errors: ${errors.mkString("; ")}"
          }
          .mkString("\n")
    val content: String =
      s"""[$label] cleanParsed=${report.cleanParsed}, recoveredParsed=${report.recoveredParsed}, remainingFailures=${report.failures.size}
         |Recovered with warnings:
         |$recoveredReport
         |Failures:
         |$failureReport
         |""".stripMargin
    Files.write(
      directory.resolve(s"$label-report.txt"),
      content.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )

  private def assertAnyParsed[A](samples: List[String], schema: Schema[A], label: String): Unit =
    val report: SampleReport = summarizeSamples[A](samples, schema, label)
    writeReport(label, report)
    val hasSuccess: Boolean = report.cleanParsed + report.recoveredParsed > 0
    if !hasSuccess then
      val firstError: String = report.failures.headOption
        .map { case (_, errors) => errors.mkString("; ") }
        .getOrElse("unknown")
      val message: String = s"$label samples all failed; first error: $firstError"
      fail(message)

  private def runSampleTest[A](label: String)(using smithy4sSchema: Smithy4sSchema[A]): Unit =
    val definition: String    = loadDefinition(s"${smithyRoot}${label}.smithy")
    given Schema[A]           = Schema.instance[A](definition)(using smithy4sSchema)
    val samples: List[String] = loadSamples(s"${label}_samples.txt")
    assertAnyParsed[A](samples, summon[Schema[A]], label)

  test("customer_profile samples parse") {
    runSampleTest[CustomerProfile]("customer_profile")
  }

  test("order samples parse") {
    runSampleTest[Order]("order")
  }

  test("invoice samples parse") {
    runSampleTest[Invoice]("invoice")
  }

  test("shipment samples parse") {
    runSampleTest[Shipment]("shipment")
  }

  test("payment samples parse") {
    runSampleTest[Payment]("payment")
  }

  test("product_catalog samples parse") {
    runSampleTest[Product]("product_catalog")
  }

  test("support_ticket samples parse") {
    runSampleTest[SupportTicket]("support_ticket")
  }

  test("marketing_campaign samples parse") {
    runSampleTest[MarketingCampaign]("marketing_campaign")
  }

  test("loyalty_program samples parse") {
    runSampleTest[LoyaltyProgram]("loyalty_program")
  }

  test("travel_booking samples parse") {
    runSampleTest[TravelBooking]("travel_booking")
  }

  test("hr_candidate samples parse") {
    runSampleTest[HRCandidate]("hr_candidate")
  }

  test("vehicle_inspection samples parse") {
    runSampleTest[VehicleInspection]("vehicle_inspection")
  }

  test("bank_transaction samples parse") {
    runSampleTest[BankTransaction]("bank_transaction")
  }

  test("healthcare_appointment samples parse") {
    runSampleTest[HealthcareAppointment]("healthcare_appointment")
  }

  test("inventory_item samples parse") {
    runSampleTest[InventoryItem]("inventory_item")
  }

  test("insurance_claim samples parse") {
    runSampleTest[InsuranceClaim]("insurance_claim")
  }

  test("project_task samples parse") {
    runSampleTest[ProjectTask]("project_task")
  }

  test("subscription_plan samples parse") {
    runSampleTest[SubscriptionPlan]("subscription_plan")
  }

  test("event_registration samples parse") {
    runSampleTest[EventRegistration]("event_registration")
  }

  test("resume samples parse") {
    runSampleTest[Resume]("resume")
  }
