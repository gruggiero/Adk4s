package org.adk4s.eval

/**
 * One evaluation datum: an input, its labeled gold output, an optional
 * identifier, and a metadata map.
 *
 * Mirrors DSPy's `Example` with input-key marking implicit (gold is always
 * the labeled output). The `id` is `Option[String]` — `None` for unlabeled
 * examples, `Some(id)` for labeled. The `meta` map carries arbitrary
 * string-to-string metadata (e.g. source, difficulty) that flows through to
 * CSV/JSON export.
 *
 * @param input the program input
 * @param gold  the labeled expected output
 * @param id    optional identifier (None for unlabeled)
 * @param meta  arbitrary string-keyed metadata
 */
final case class Example[I, O](
  input: I,
  gold: O,
  id: Option[String] = None,
  meta: Map[String, String] = Map.empty[String, String]
)
