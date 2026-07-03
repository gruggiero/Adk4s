package org.adk4s.structured.sap

/**
 * Enum fuzzy matching with 4 escalating strategies.
 *
 * Each strategy has an increasing score penalty:
 * 1. Exact match — score 0
 * 2. Punctuation-stripped match — score 3
 * 3. Case-insensitive match — score 2
 * 4. Case-insensitive + punctuation-stripped match — score 5
 */
object EnumMatching:

  /**
   * Match an input string against a list of enum values.
   *
   * @param input The input string from the LLM
   * @param enumValues The allowed enum values from the schema
   * @return The matched enum value and the coercion flags applied, or None if no match
   */
  def matchEnum(
    input: String,
    enumValues: List[String]
  ): Option[(String, Vector[CoercionFlag])] =
    // Strategy 1: Exact match (score 0)
    enumValues
      .find(_ == input)
      .map(_ -> Vector.empty)

      // Strategy 3: Case-insensitive match (score 2)
      .orElse {
        enumValues
          .find(v => v.equalsIgnoreCase(input))
          .map(_ -> Vector(CoercionFlag.CaseInsensitive))
      }

      // Strategy 2: Punctuation-stripped match (score 3)
      .orElse {
        val strippedInput: String = stripPunctuation(input)
        enumValues
          .find(v => stripPunctuation(v) == strippedInput)
          .map(_ -> Vector(CoercionFlag.PunctuationStripped))
      }

      // Strategy 4: Case-insensitive + punctuation-stripped match (score 5)
      .orElse {
        val strippedInput: String = stripPunctuation(input)
        enumValues
          .find(v => stripPunctuation(v).equalsIgnoreCase(strippedInput))
          .map(_ -> Vector(CoercionFlag.CaseInsensitive, CoercionFlag.PunctuationStripped))
      }

  /**
   * Strip punctuation from a string, keeping only alphanumeric characters.
   */
  private def stripPunctuation(s: String): String =
    s.filter(c => c.isLetterOrDigit || c == ' ')
