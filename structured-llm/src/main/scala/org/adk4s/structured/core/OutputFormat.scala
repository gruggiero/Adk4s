package org.adk4s.structured.core

/**
 * Strategy for hoisting class definitions in rendered output.
 */
enum HoistStrategy:
  case Auto
  case All
  case None
  case Subset(classes: Vector[String])

/**
 * Map rendering style.
 */
enum MapStyle:
  case Inline
  case Verbose

/**
 * Configurable options for rendering Smithy IDL schemas into prompt text.
 *
 * @param prefix Optional prefix prepended to the schema block
 * @param unionSeparator Separator for union variants (default: " | ")
 * @param hoistClasses Whether to hoist class definitions to the top
 * @param quoteClassFields If true, quote field names in class definitions
 * @param enumValuePrefix Prefix for enum values (e.g., "EnumName.")
 * @param alwaysHoistEnums If true, always hoist enum definitions
 * @param hoistedClassPrefix Prefix for hoisted class names
 * @param mapStyle Style for rendering map types
 */
final case class OutputFormatOptions(
  prefix: Option[String] = None,
  unionSeparator: String = " | ",
  hoistClasses: HoistStrategy = HoistStrategy.Auto,
  quoteClassFields: Boolean = false,
  enumValuePrefix: Option[String] = None,
  alwaysHoistEnums: Boolean = false,
  hoistedClassPrefix: String = "",
  mapStyle: MapStyle = MapStyle.Inline
)

object OutputFormatOptions:
  /** Default options — matches the current outputFormatBlock format. */
  val default: OutputFormatOptions = OutputFormatOptions()

/**
 * Configurable schema-to-prompt renderer.
 *
 * Renders Smithy IDL schemas into prompt text with configurable options
 * for class hoisting, field quoting, enum prefixing, and map style.
 */
object OutputFormatRenderer:

  /**
   * Render a Smithy IDL string with the given options.
   *
   * @param smithyDefinition The Smithy IDL string
   * @param options Rendering options
   * @return The rendered output format block
   */
  def render(
    smithyDefinition: String,
    description: Option[String],
    options: OutputFormatOptions
  ): String =
    val sanitized: String = sanitizeForRendering(smithyDefinition, options)
    val descPart: String = description.fold("")(d => s"\n// $d\n")
    val prefixPart: String = options.prefix.fold("")(p => s"$p\n")
    s"""${prefixPart}Respond with JSON matching this schema:
       |```smithy$descPart
       |$sanitized
       |```
       |
       |Important:
       |- Respond ONLY with valid JSON, no additional text
       |- Use the exact field names shown
       |- Include all @required fields""".stripMargin

  /**
   * Sanitize the Smithy IDL for rendering with the given options.
   */
  private def sanitizeForRendering(idl: String, options: OutputFormatOptions): String =
    // Apply the existing list→array notation sanitization
    val listPattern: scala.util.matching.Regex =
      """(?s)list\s+(\w+)\s*\{\s*member\s*:\s*(\w+)\s*\}""".r

    val listMappings: Map[String, String] = listPattern.findAllMatchIn(idl).map { m =>
      m.group(1) -> m.group(2)
    }.toMap

    val withoutListDefs: String =
      if listMappings.isEmpty then idl
      else listPattern.replaceAllIn(idl, "")

    val withArrayNotation: String = listMappings.foldLeft(withoutListDefs) {
      case (acc, (listName, elementType)) =>
        val fieldRefPattern: scala.util.matching.Regex = s"""(:\\s*)$listName""".r
        fieldRefPattern.replaceAllIn(acc, m => s"${m.group(1)}$elementType[]")
    }

    // Apply quoteClassFields if enabled
    val withQuotedFields: String =
      if options.quoteClassFields then quoteClassFieldsIn(withArrayNotation)
      else withArrayNotation

    // Clean up whitespace
    withQuotedFields
      .replaceAll("""(?m)^\s*$[\n\r]+""", "\n")
      .replaceAll("""\n{3,}""", "\n\n")
      .trim

  /**
   * Quote field names in class definitions.
   */
  private def quoteClassFieldsIn(idl: String): String =
    val fieldPattern: scala.util.matching.Regex =
      """(?m)^(\s+)([a-zA-Z_][a-zA-Z0-9_]*)(\s*:)""".r
    fieldPattern.replaceAllIn(idl, m => s"""${m.group(1)}"${m.group(2)}"${m.group(3)}""")
