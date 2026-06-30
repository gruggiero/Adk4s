package org.adk4s.core.component

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.ToolFunction
import upickle.default.*

final case class AdkToolInfo(
  name: String,
  description: String,
  parameters: ujson.Value
):
  def toToolFunction: ToolFunction[ujson.Value, ujson.Value] =
    val objSchema: org.llm4s.toolapi.ObjectSchema[ujson.Value] = buildObjectSchema(parameters)
    ToolFunction[ujson.Value, ujson.Value](
      name = name,
      description = description,
      schema = objSchema,
      handler = (_: SafeParameterExtractor) => Left("AdkToolInfo wrapper: execution not supported via ToolFunction")
    )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def buildObjectSchema(params: ujson.Value): org.llm4s.toolapi.ObjectSchema[ujson.Value] =
    val properties: Seq[org.llm4s.toolapi.PropertyDefinition[String]] = params.obj.get("properties") match
      case Some(props: ujson.Obj) =>
        val requiredFields: Set[String] = params.obj.get("required") match
          case Some(arr: ujson.Arr) => arr.value.map(_.str).toSet
          case _                   => Set.empty[String]
        props.obj.toSeq.map { case (fieldName: String, fieldSchema: ujson.Value) =>
          val fieldType: String = fieldSchema.obj.get("type").map(_.str).getOrElse("string")
          val fieldDesc: String = fieldSchema.obj.get("description").map(_.str).getOrElse(fieldName)
          val schemaDef: org.llm4s.toolapi.SchemaDefinition[String] = fieldType match
            case "integer" => org.llm4s.toolapi.IntegerSchema(fieldDesc).asInstanceOf[org.llm4s.toolapi.SchemaDefinition[String]]
            case "number"  => org.llm4s.toolapi.NumberSchema(fieldDesc).asInstanceOf[org.llm4s.toolapi.SchemaDefinition[String]]
            case "boolean" => org.llm4s.toolapi.BooleanSchema(fieldDesc).asInstanceOf[org.llm4s.toolapi.SchemaDefinition[String]]
            case _         => org.llm4s.toolapi.StringSchema(fieldDesc)
          org.llm4s.toolapi.PropertyDefinition[String](fieldName, schemaDef, required = requiredFields.contains(fieldName))
        }
      case _ => Seq.empty[org.llm4s.toolapi.PropertyDefinition[String]]
    org.llm4s.toolapi.ObjectSchema[ujson.Value](description, properties)

trait Tool[F[_]]:
  def info: AdkToolInfo

  def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]]

trait InvokableTool[F[_]] extends Tool[F]:
  def run(arguments: ujson.Value): F[ujson.Value]

trait StreamableTool[F[_]] extends Tool[F]:
  def runStream(arguments: ujson.Value): Stream[F, String]

object Tool:
  def fromLlm4s[F[_]](toolFunction: org.llm4s.toolapi.ToolFunction[Any, Any])(using F: Sync[F]): InvokableTool[F] =
    new InvokableTool[F]:
      def info: AdkToolInfo =
        val parametersJson = toolFunction.schema.toJsonSchema(strict = true)
        AdkToolInfo(toolFunction.name, toolFunction.description, parametersJson)

      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = Some(toolFunction)

      def run(arguments: ujson.Value): F[ujson.Value] =
        toolFunction.execute(arguments) match
          case Right(result) => F.pure(result)
          case Left(err) => F.raiseError(new RuntimeException(s"Tool execution failed: $err"))

  def invokable[F[_]](name: String, description: String, handler: ujson.Value => Either[String, ujson.Value])(using F: Sync[F]): InvokableTool[F] =
    invokable(name, description, ujson.Obj(), handler)

  def invokable[F[_]](name: String, description: String, parameters: ujson.Value, handler: ujson.Value => Either[String, ujson.Value])(using F: Sync[F]): InvokableTool[F] =
    new InvokableTool[F]:
      def info: AdkToolInfo =
        AdkToolInfo(name, description, parameters)

      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

      def run(arguments: ujson.Value): F[ujson.Value] =
        F.delay(handler(arguments)).flatMap {
          case Right(result) => F.pure(result)
          case Left(err) => F.raiseError(org.adk4s.core.error.ToolExecutionError(name, new Exception(err)))
        }

  def streamable[F[_]](name: String, description: String, handler: ujson.Value => Stream[F, String])(using F: Sync[F]): StreamableTool[F] =
    new StreamableTool[F]:
      def info: AdkToolInfo =
        AdkToolInfo(name, description, ujson.Obj())

      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

      def runStream(arguments: ujson.Value): Stream[F, String] =
        Stream.suspend {
          handler(arguments)
        }
