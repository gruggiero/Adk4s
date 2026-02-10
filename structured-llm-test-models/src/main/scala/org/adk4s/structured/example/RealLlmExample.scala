package org.adk4s.structured.example

import cats.effect.IO
import cats.effect.IOApp
import cats.implicits._
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.error.LLMError
import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.PromptTemplate
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM
import org.adk4s.structured.core.StructuredLLMError
import org.adk4s.structured.core.ParseError
import org.adk4s.structured.test.Resume
import smithy4s.Schema as Smithy4sSchema
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import scala.util.{ Try, Success, Failure }
import scala.collection.mutable.ListBuffer

object RealLlmExample extends IOApp.Simple {

  case class AppConfig(
    llmModel: String,
    smithySchemaPath: Path,
    resumeInputPath: Path,
    logPrompt: Boolean,
    providerConfig: ProviderConfig,
    completionOptions: CompletionOptions
  )

  def run: IO[Unit] =
    for
      config <- IO.blocking(validateAndLoadConfig())

      _ <- IO.println(s"\nRunning with model: ${config.llmModel}")
      _ <- IO.println(s"Smithy schema: ${config.smithySchemaPath.toAbsolutePath}")
      _ <- IO.println(s"Resume input: ${config.resumeInputPath.toAbsolutePath}")

      _ <- IO.println("\nLoading Smithy schema...")
      smithyDefinition <- IO.blocking(
        Files.readString(config.smithySchemaPath, StandardCharsets.UTF_8)
      )
      _ <- IO.println(s"Smithy schema loaded (${smithyDefinition.length} chars)")

      given Schema[Resume] = Schema.instance[Resume](smithyDefinition)(using Resume.schema)

      template = PromptTemplate
        .withSystem("You are an expert resume parser. Extract information accurately and preserve exact wording.")
        .expecting[Resume]

      _ <- IO.println("Loading resume text...")
      resumeText <- IO.blocking(
        Files.readString(config.resumeInputPath, StandardCharsets.UTF_8)
      )
      _ <- IO.println(s"Resume text loaded (${resumeText.length} chars)")

      _ <- IO.println("Creating LLM client...")
      client <- IO.fromEither(
        LLMConnect
          .getClient(config.providerConfig)
          .left
          .map(err => new RuntimeException(s"Failed to create LLM client: ${err.message}"))
      )
      _ <- IO.println("LLM client created successfully")

      structured = StructuredLLM.fromClientWithLogging[IO](client)
      prompt     = template.render(resumeText)

      _ <- IO.whenA(config.logPrompt)(logFullPrompt(prompt, config.llmModel, config.completionOptions)) *>
        IO.whenA(!config.logPrompt)(IO.println("\nPrompt logging is disabled (app.log.prompt = false)"))

      _ <- IO.println("\nSending request to LLM...")
      _ <- IO.println(
        s"Completion options: temp=${config.completionOptions.temperature}, topP=${config.completionOptions.topP}"
      )
      startTime <- IO(System.currentTimeMillis())
      result    <- structured.complete[Resume](prompt).attempt
      endTime   <- IO(System.currentTimeMillis())
      _         <- IO.println(s"LLM call completed in ${endTime - startTime}ms")
      _         <- IO.println(s"Result status: ${result.fold(_ => "ERROR", _ => "SUCCESS")}")

      _ <- result match {
        case Left(cause) =>
          val error = cause match {
            case e: StructuredLLMError => e
            case other =>
              StructuredLLMError.ParseFailed(List(ParseError.JsonSyntaxError(other.getMessage, None, false)), "")
          }
          IO.println(s"Error type: ${error.getClass.getSimpleName}") *> handleLLMError(error)
        case Right(resume) => displayParsedResume(resume)
      }
    yield ()

  def validateAndLoadConfig(): AppConfig = {
    val config = ConfigFactory.load("application")

    if (sysDebugEnabled) {
      println(s"[DEBUG] Loading configuration from: ${config.origin.description}")
    }

    val llmModelOpt   = Try(config.getString("llm4s.llm.model")).toOption
    val smithyPathOpt = Try(config.getString("app.smithy.schema.path")).toOption
    val resumePathOpt = Try(config.getString("app.resume.input.path")).toOption
    val logPromptOpt  = Try(config.getBoolean("app.log.prompt")).toOption.getOrElse(true)

    val providerOpt = llmModelOpt.map(_.split("/", 2)).flatMap {
      case parts if parts.length == 2 && parts(0).nonEmpty && parts(1).nonEmpty =>
        Some((parts(0).toLowerCase, parts(1)))
      case _ => None
    }

    val errors = ListBuffer.empty[String]

    if (llmModelOpt.isEmpty || llmModelOpt.exists(_.isBlank))
      errors += "Missing mandatory configuration: llm4s.llm.model"
      errors += "  Example: llm4s.llm.model = \"openai/gpt-4o\""
    else if (providerOpt.isEmpty)
      errors += "Invalid llm4s.llm.model format"
      errors += "  Expected: provider/model-name (e.g., \"openai/gpt-4o\")"

    val apiKeyValid = providerOpt match {
      case Some(("openai", _)) | Some(("openrouter", _)) =>
        Try(config.getString("llm4s.openai.apiKey")).toOption.exists(_.nonEmpty)
      case Some(("anthropic", _)) =>
        Try(config.getString("llm4s.anthropic.apiKey")).toOption.exists(_.nonEmpty)
      case Some(("azure", _)) =>
        val endpointOpt = Try(config.getString("llm4s.azure.endpoint")).toOption
        val apiKeyOpt   = Try(config.getString("llm4s.azure.apiKey")).toOption
        endpointOpt.isDefined && apiKeyOpt.isDefined
      case Some(("ollama", _)) => true
      case _                   => false
    }

    if (!apiKeyValid)
      providerOpt match {
        case Some(("openai", _)) | Some(("openrouter", _)) =>
          errors += "Missing mandatory configuration for OpenAI provider: llm4s.openai.apiKey"
          errors += "  Set in application.conf or environment variable OPENAI_API_KEY"
        case Some(("anthropic", _)) =>
          errors += "Missing mandatory configuration for Anthropic provider: llm4s.anthropic.apiKey"
          errors += "  Set in application.conf or environment variable ANTHROPIC_API_KEY"
        case Some(("azure", _)) =>
          errors += "Missing mandatory configuration for Azure provider"
          errors += "  llm4s.azure.endpoint and llm4s.azure.apiKey must be set"
          errors += "  Or set environment variables: AZURE_API_BASE, AZURE_API_KEY"
        case _ => ()
      }

    if (smithyPathOpt.isEmpty || smithyPathOpt.exists(_.isBlank))
      errors += "Missing mandatory configuration: app.smithy.schema.path"
      errors += "  Example: app.smithy.schema.path = \"structured-llm-test-models/src/main/smithy/resume.smithy\""

    if (resumePathOpt.isEmpty || resumePathOpt.exists(_.isBlank))
      errors += "Missing mandatory configuration: app.resume.input.path"
      errors += "  Example: app.resume.input.path = \"samples/resume/sample.txt\""

    if (errors.nonEmpty)
      throw new RuntimeException("Configuration validation failed:\n" + errors.mkString("\n"))

    val fileErrors = ListBuffer.empty[String]

    smithyPathOpt.foreach { smithyPathStr =>
      val smithyFromProjectRoot = resolveFromProjectRoot(smithyPathStr)
      val smithyFromCurrentDir  = resolveFromCurrentDir(smithyPathStr)
      val smithyPath = if (Files.exists(smithyFromProjectRoot)) smithyFromProjectRoot else smithyFromCurrentDir

      if (!Files.exists(smithyPath))
        fileErrors += "Smithy schema file not found"
        fileErrors += s"  Attempted (project root): ${smithyFromProjectRoot.toAbsolutePath}"
        fileErrors += s"  Attempted (current dir): ${smithyFromCurrentDir.toAbsolutePath}"
        fileErrors += s"  Relative path from config: $smithyPathStr"
      else if (!Files.isReadable(smithyPath))
        fileErrors += s"Smithy schema file not readable: ${smithyPath.toAbsolutePath}"
      else if (!Files.isRegularFile(smithyPath))
        fileErrors += s"Smithy schema path is not a file: ${smithyPath.toAbsolutePath}"
    }

    resumePathOpt.foreach { resumePathStr =>
      val resumeFromProjectRoot = resolveFromProjectRoot(resumePathStr)
      val resumeFromCurrentDir  = resolveFromCurrentDir(resumePathStr)
      val resumePath = if (Files.exists(resumeFromProjectRoot)) resumeFromProjectRoot else resumeFromCurrentDir

      if (!Files.exists(resumePath))
        fileErrors += "Resume input file not found"
        fileErrors += s"  Attempted (project root): ${resumeFromProjectRoot.toAbsolutePath}"
        fileErrors += s"  Attempted (current dir): ${resumeFromCurrentDir.toAbsolutePath}"
        fileErrors += s"  Relative path from config: $resumePathStr"
      else if (!Files.isReadable(resumePath))
        fileErrors += s"Resume input file not readable: ${resumePath.toAbsolutePath}"
      else if (!Files.isRegularFile(resumePath))
        fileErrors += s"Resume input path is not a file: ${resumePath.toAbsolutePath}"
    }

    if (fileErrors.nonEmpty)
      throw new RuntimeException("File validation failed:\n" + fileErrors.mkString("\n"))

    val smithyPath = {
      val pathStr         = smithyPathOpt.get
      val fromProjectRoot = resolveFromProjectRoot(pathStr)
      val fromCurrentDir  = resolveFromCurrentDir(pathStr)
      if (Files.exists(fromProjectRoot)) fromProjectRoot else fromCurrentDir
    }

    val resumePath = {
      val pathStr         = resumePathOpt.get
      val fromProjectRoot = resolveFromProjectRoot(pathStr)
      val fromCurrentDir  = resolveFromCurrentDir(pathStr)
      if (Files.exists(fromProjectRoot)) fromProjectRoot else fromCurrentDir
    }

    val providerConfig = Llm4sConfig
      .provider()
      .getOrElse(throw new RuntimeException("Failed to load provider config"))

    val completionOptions = loadCompletionOptions(config)

    AppConfig(
      llmModel = llmModelOpt.get,
      smithySchemaPath = smithyPath,
      resumeInputPath = resumePath,
      logPrompt = logPromptOpt,
      providerConfig = providerConfig,
      completionOptions = completionOptions
    )
  }

  def resolveFromProjectRoot(relativePath: String): Path = {
    val projectRoot = resolveProjectRoot()
    val pathObj     = Path.of(relativePath)
    if (pathObj.isAbsolute) pathObj
    else projectRoot.resolve(pathObj).normalize()
  }

  def resolveFromCurrentDir(relativePath: String): Path = {
    val currentDir = Path.of("").toAbsolutePath
    val pathObj    = Path.of(relativePath)
    if (pathObj.isAbsolute) pathObj
    else currentDir.resolve(pathObj).normalize()
  }

  def resolveProjectRoot(): Path = {
    val currentDir = Path.of("").toAbsolutePath
    if (currentDir.getFileName.toString == "structured-llm-test-models")
      currentDir.getParent
    else if (
      currentDir.getParent != null &&
      currentDir.getParent.getFileName.toString == "structured-llm-test-models"
    )
      currentDir
    else
      currentDir
  }

  def loadCompletionOptions(config: Config): CompletionOptions = {
    val temperature      = Try(config.getDouble("llm4s.completion.temperature")).getOrElse(0.7)
    val topP             = Try(config.getDouble("llm4s.completion.topP")).getOrElse(1.0)
    val maxTokens        = Try(config.getInt("llm4s.completion.maxTokens")).toOption
    val presencePenalty  = Try(config.getDouble("llm4s.completion.presencePenalty")).getOrElse(0.0)
    val frequencyPenalty = Try(config.getDouble("llm4s.completion.frequencyPenalty")).getOrElse(0.0)
    val reasoningStr     = Try(config.getString("llm4s.completion.reasoning")).getOrElse("none").toLowerCase

    val budgetTokens = Try(config.getInt("llm4s.completion.budgetTokens")).toOption

    val reasoning = reasoningStr match {
      case "low"    => Some(ReasoningEffort.Low)
      case "medium" => Some(ReasoningEffort.Medium)
      case "high"   => Some(ReasoningEffort.High)
      case _        => Some(ReasoningEffort.None)
    }

    CompletionOptions(
      temperature = temperature,
      topP = topP,
      maxTokens = maxTokens,
      presencePenalty = presencePenalty,
      frequencyPenalty = frequencyPenalty,
      reasoning = reasoning,
      budgetTokens = budgetTokens
    )
  }

  def logFullPrompt(prompt: Prompt, model: String, options: CompletionOptions): IO[Unit] =
    IO.println("\n" + "=" * 80) *>
      IO.println("FULL PROMPT BEING SENT TO LLM:") *>
      IO.println("=" * 80) *>
      IO.println(s"\nProvider/Model: $model") *>
      IO.println(s"Temperature: ${options.temperature}, TopP: ${options.topP}") *>
      IO.println(s"Reasoning: ${options.reasoning.map(_.name).getOrElse("none")}") *>
      options.maxTokens.fold(IO.unit)(max => IO.println(s"Max Tokens: $max")) *>
      IO.println("") *>
      IO.println("MESSAGES:") *>
      IO.println("-" * 80) *>
      prompt.messages.zipWithIndex
        .map { case (msg, idx) =>
          IO.println(s"\n[${idx + 1}] Role: ${msg.role}") *>
            IO.println("-" * 40) *>
            IO.println(msg.content)
        }
        .sequence_
        .void *>
      IO.println("") *>
      IO.println("=" * 80)

  def handleLLMError(error: StructuredLLMError): IO[Unit] =
    IO.println(s"[DEBUG] Handling error: ${error.getClass.getSimpleName}") *> (
      error match {
        case StructuredLLMError.LLMCallFailed(underlying, _) =>
          IO.println(s"[DEBUG] LLMCallFailed - underlying type: ${underlying.getClass.getSimpleName}") *>
            IO.println(s"[DEBUG] LLMCallFailed - underlying message: ${underlying.message}") *>
            IO.println("\n" + "=" * 80) *>
            IO.println("LLM CALL FAILED:") *>
            IO.println("=" * 80) *>
            IO.println(s"\n${underlying.formatted}") *> {
              val msg = underlying.message
              IO.println(s"[DEBUG] Error message contains 401: ${msg.contains("401")}") *>
                IO.println(s"[DEBUG] Error message contains 429: ${msg.contains("429")}") *>
                IO.println(s"[DEBUG] Error message contains timeout: ${msg.contains("timeout")}") *>
                IO.println(s"[DEBUG] Error message contains connection: ${msg.contains("connection")}") *>
                (if (msg.contains("401")) {
                   IO.println("\nCheck your API key configuration")
                 } else if (msg.contains("429")) {
                   val retryDelay = underlying.code.flatMap(_.split("retryAfter=").lastOption)
                   IO.whenA(retryDelay.isDefined)(IO.println(s"\nRate limited. Retry after: ${retryDelay.get} seconds"))
                 } else if (msg.contains("timeout") || msg.contains("connection")) {
                   IO.whenA(underlying.context.get("endpoint").isDefined)(
                     IO.println(s"\nNetwork error connecting to: ${underlying.context.get("endpoint").get}")
                   )
                 } else {
                   IO.println("")
                 })
            }

        case StructuredLLMError.ParseFailed(errors, _) =>
          IO.println(s"[DEBUG] ParseFailed with ${errors.size} error(s)") *>
            IO.println("\n" + "=" * 80) *>
            IO.println("FAILED TO PARSE LLM RESPONSE:") *>
            IO.println("=" * 80) *>
            errors.map(e => IO.println(s"  - ${e.message}")).sequence_

        case StructuredLLMError.EmptyResponse(_) =>
          IO.println("[DEBUG] EmptyResponse error") *>
            IO.println("\n" + "=" * 80) *>
            IO.println("LLM RETURNED EMPTY RESPONSE:") *>
            IO.println("=" * 80) *>
            IO.println("") *>
            IO.println("=" * 80)
      }
    )

  def displayParsedResume(resume: Resume): IO[Unit] =
    IO.println("[DEBUG] Successfully parsed resume, displaying results...") *>
      IO.println("\n" + "=" * 80) *>
      IO.println("SUCCESSFULLY PARSED RESUME:") *>
      IO.println("=" * 80) *>
      IO.println(s"\nName: ${resume.name}") *>
      IO.println(s"Email: ${resume.email.getOrElse("Not provided")}") *>
      IO.println(s"Seniority: ${resume.seniority}") *>
      IO.println("\nSkills:") *>
      IO.println(s"  ${resume.skills.mkString(", ")}") *>
      resume.education.fold(IO.unit) { edu =>
        IO.println("\nEducation:") *>
          edu.traverse(e => IO.println(s"  - ${e.school}: ${e.degree} (${e.year.getOrElse("?")})")).void
      } *>
      IO.println("=" * 80)

  private def sysDebugEnabled: Boolean =
    System.getProperty("app.log.debug", "false").toBoolean
}
