package org.adk4s.examples.eino.batch

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.toFoldableOps
import org.adk4s.core.batch.BatchExecutor
import org.adk4s.core.runnable.Runnable
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Eino equivalent: compose/batch
 *
 * Demonstrates BatchExecutor for processing multiple inputs through a Runnable
 * with configurable concurrency and per-item error isolation.
 *
 * Scenarios:
 *   1. Sequential processing — process documents one at a time
 *   2. Concurrent processing — process multiple documents in parallel
 *   3. Error handling — per-item error isolation (one failure doesn't abort the batch)
 *   4. Streaming — emit results as they complete
 *   5. Parent pipeline — batch results aggregated into a summary report
 */
object BatchExample extends IOApp.Simple:

  // --- Domain types ---

  final case class ReviewRequest(
    documentId: String,
    content: String,
    priority: String
  )

  final case class ReviewResult(
    documentId: String,
    approved: Boolean,
    score: Double,
    comments: String
  )

  final case class ReviewReport(
    totalDocuments: Int,
    approvedCount: Int,
    rejectedCount: Int,
    averageScore: Double
  )

  // --- Sample data ---

  private val sampleDocuments: List[ReviewRequest] = List(
    ReviewRequest("DOC-001", "Compliance document about data privacy regulations.", "high"),
    ReviewRequest("DOC-002", "Internal memo regarding office policy updates.", "medium"),
    ReviewRequest("DOC-003", "Financial audit report for Q3 2025.", "high"),
    ReviewRequest("DOC-004", "Employee handbook revision draft.", "low"),
    ReviewRequest("DOC-005", "Security incident response plan.", "high")
  )

  // --- Review Runnable (simulates automated document review) ---

  private val reviewRunnable: Runnable[ReviewRequest, ReviewResult] =
    Runnable.fromInvoke[ReviewRequest, ReviewResult] { (req: ReviewRequest) =>
      IO.delay {
        val score: Double = req.priority match
          case "high"   => 0.85
          case "medium" => 0.70
          case _        => 0.55
        val approved: Boolean = score >= 0.7
        ReviewResult(
          documentId = req.documentId,
          approved = approved,
          score = score,
          comments = s"Auto-reviewed ${req.documentId} (priority: ${req.priority})"
        )
      }
    }

  // --- Failing Runnable (for error handling demo) ---

  private val failingReviewRunnable: Runnable[ReviewRequest, ReviewResult] =
    Runnable.fromInvoke[ReviewRequest, ReviewResult] { (req: ReviewRequest) =>
      if req.priority == "low" then
        IO.raiseError(new RuntimeException(s"Review failed for ${req.documentId}: low-priority documents rejected"))
      else
        reviewRunnable.invoke(req)
    }

  // --- Scenarios ---

  private def runSequential: IO[Unit] =
    val executor: BatchExecutor[ReviewRequest, ReviewResult] =
      BatchExecutor.fromRunnable(reviewRunnable)
    for
      _ <- ExampleUtils.printSubSection("Scenario 1: Sequential Processing")
      results <- executor.invokeAll(sampleDocuments.take(3))
      _ <- IO.println(s"   Processed ${results.length} documents sequentially")
      _ <- results.traverse_ { (result: Either[Throwable, ReviewResult]) =>
        result match
          case Right(r) => IO.println(s"   - ${r.documentId}: approved=${r.approved}, score=${r.score}")
          case Left(e)  => IO.println(s"   - ERROR: ${e.getMessage}")
      }
    yield ()

  private def runConcurrent: IO[Unit] =
    val executor: BatchExecutor[ReviewRequest, ReviewResult] =
      BatchExecutor.fromRunnable(reviewRunnable)
    for
      _ <- ExampleUtils.printSubSection("Scenario 2: Concurrent Processing (concurrency=3)")
      results <- executor.invokeAllPar(sampleDocuments, 3)
      _ <- IO.println(s"   Processed ${results.length} documents concurrently")
      _ <- results.traverse_ { (result: Either[Throwable, ReviewResult]) =>
        result match
          case Right(r) => IO.println(s"   - ${r.documentId}: approved=${r.approved}, score=${r.score}")
          case Left(e)  => IO.println(s"   - ERROR: ${e.getMessage}")
      }
    yield ()

  private def runWithErrors: IO[Unit] =
    val executor: BatchExecutor[ReviewRequest, ReviewResult] =
      BatchExecutor.fromRunnable(failingReviewRunnable)
    for
      _ <- ExampleUtils.printSubSection("Scenario 3: Error Handling (per-item isolation)")
      results <- executor.invokeAll(sampleDocuments)
      successes = results.collect { case Right(r) => r }
      failures = results.collect { case Left(e) => e }
      _ <- IO.println(s"   Total: ${results.length}, Succeeded: ${successes.length}, Failed: ${failures.length}")
      _ <- successes.traverse_ { (r: ReviewResult) =>
        IO.println(s"   - OK: ${r.documentId} (score=${r.score})")
      }
      _ <- failures.traverse_ { (e: Throwable) =>
        IO.println(s"   - FAIL: ${e.getMessage}")
      }
    yield ()

  private def runStreaming: IO[Unit] =
    val executor: BatchExecutor[ReviewRequest, ReviewResult] =
      BatchExecutor.fromRunnable(reviewRunnable)
    for
      _ <- ExampleUtils.printSubSection("Scenario 4: Streaming Results")
      _ <- IO.println("   Emitting results as they complete:")
      _ <- executor
        .stream(sampleDocuments, 2)
        .evalMap { (result: Either[Throwable, ReviewResult]) =>
          result match
            case Right(r) => IO.println(s"   >> ${r.documentId}: approved=${r.approved}")
            case Left(e)  => IO.println(s"   >> ERROR: ${e.getMessage}")
        }
        .compile
        .drain
    yield ()

  private def runWithReduce: IO[Unit] =
    val executor: BatchExecutor[ReviewRequest, ReviewResult] =
      BatchExecutor.fromRunnable(reviewRunnable)
    for
      _ <- ExampleUtils.printSubSection("Scenario 5: Parent Pipeline with Reduce")
      results <- executor.invokeAllPar(sampleDocuments, 3)
      successes = results.collect { case Right(r) => r }
      report = ReviewReport(
        totalDocuments = results.length,
        approvedCount = successes.count(_.approved),
        rejectedCount = successes.count(!_.approved),
        averageScore = if successes.nonEmpty then successes.map(_.score).sum / successes.length else 0.0
      )
      _ <- IO.println(s"   Review Report:")
      _ <- IO.println(s"     Total documents:  ${report.totalDocuments}")
      _ <- IO.println(s"     Approved:         ${report.approvedCount}")
      _ <- IO.println(s"     Rejected:         ${report.rejectedCount}")
      _ <- IO.println(s"     Average score:    ${report.averageScore}")
    yield ()

  // --- Main ---

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Batch Example (Eino: compose/batch)")
      _ <- runSequential
      _ <- runConcurrent
      _ <- runWithErrors
      _ <- runStreaming
      _ <- runWithReduce
      _ <- IO.println("\n=== All Batch Scenarios Completed ===")
    yield ()
