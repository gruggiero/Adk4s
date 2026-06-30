package org.adk4s.structured.sap

/**
 * Normalizes Unicode smart quotes to standard ASCII quotes.
 *
 * LLMs (especially Claude) sometimes produce typographic/smart quotes
 * in JSON output, which breaks standard JSON parsing. This normalizer
 * replaces Unicode smart double quotes with `"` and Unicode smart single
 * quotes with `'` before further JSON processing.
 *
 * Replaced characters:
 *  - U+2018 LEFT SINGLE QUOTATION MARK → '
 *  - U+2019 RIGHT SINGLE QUOTATION MARK → '
 *  - U+201A SINGLE LOW-9 QUOTATION MARK → '
 *  - U+201B SINGLE HIGH-REVERSED-9 QUOTATION MARK → '
 *  - U+201C LEFT DOUBLE QUOTATION MARK → "
 *  - U+201D RIGHT DOUBLE QUOTATION MARK → "
 *  - U+201E DOUBLE LOW-9 QUOTATION MARK → "
 *  - U+201F DOUBLE HIGH-REVERSED-9 QUOTATION MARK → "
 */
object UnicodeQuoteNormalizer:

  /**
   * Normalize all Unicode smart quotes in the input string to ASCII equivalents.
   *
   * @param input The string potentially containing Unicode smart quotes
   * @return The string with all smart quotes replaced by ASCII quotes
   */
  def normalize(input: String): String =
    input.map { c =>
      c match
        case '\u2018' | '\u2019' | '\u201A' | '\u201B' => '\''
        case '\u201C' | '\u201D' | '\u201E' | '\u201F' => '"'
        case other                                     => other
    }
