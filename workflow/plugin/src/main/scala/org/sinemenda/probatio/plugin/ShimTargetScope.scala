package org.sinemenda.probatio.plugin

/**
 * The resolution scope a generated forwarding script states (R-W9).
 *
 * A shim's target is resolved in exactly one of two scopes, and the scope
 * is carried explicitly by the generation request — never inferred from
 * the target's shape. Inferring "this looks absolute, so it is an
 * install" is the reasoning that produced a committed absolute path
 * nobody noticed.
 *
 * - `RepositoryRelative` — an in-repository forwarding script: it
 *   resolves the tool relative to its own location, so every clone,
 *   worktree and CI runner reaches the tool inside its own copy.
 * - `AbsoluteInstall` — a user-level install shim: it resolves to the
 *   absolute installed path the resolution produced.
 *
 * spec: repair-probatio-cutover/workflow-delivery-hygiene — Requirement: The generated script states which scope it resolved
 * spec: repair-probatio-cutover/workflow-delivery-hygiene — Requirement: An in-repository forwarding script resolves its target relative to itself
 */
sealed abstract class ShimTargetScope extends Product with Serializable

object ShimTargetScope {

  /**
   * In-repository scope: the script computes its own directory at
   * runtime and execs `<script-dir>/<fromShimToBinary>`. The carried
   * path is a `RelPath` — an absolute path is unconstructible here.
   */
  final case class RepositoryRelative(fromShimToBinary: RelPath) extends ShimTargetScope

  /**
   * Install scope: the script execs the absolute installed path the
   * resolution produced. A non-absolute `path` is refused at
   * generation, not emitted.
   */
  final case class AbsoluteInstall(path: String) extends ShimTargetScope

  /**
   * A path guaranteed relative.
   *
   * The only way to obtain one is `RelPath.from`, which refuses empty,
   * root-anchored (`/…`), home-anchored (`~…`), drive-anchored
   * (`C:\…`/`C:/…`) and UNC (`\\…`) inputs. The private constructor
   * means a `RelPath` value in hand can never name an absolute
   * location — "the relative variant takes a relative path type" is a
   * type-level fact, not a convention.
   *
   * spec: repair-probatio-cutover/workflow-delivery-hygiene — Compile-Negative: A repository-relative scope carrying an absolute path
   */
  final class RelPath private (val value: String) extends AnyVal {
    override def toString: String = value
  }

  object RelPath {

    /**
     * Constructs a relative path or refuses naming why the input is
     * absolute. Refusal, not silent acceptance: a scope that silently
     * carries an absolute path is the defect this type exists to remove.
     */
    def from(value: String): Either[String, RelPath] =
      if (value.isEmpty)
        Left("a repository-relative shim target cannot be empty")
      else if (value.startsWith("/") || value.startsWith("\\\\"))
        Left(s"not a relative path (root-anchored): $value")
      else if (value.startsWith("~"))
        Left(s"not a relative path (home-anchored): $value")
      else if (value.length >= 2 && value.charAt(1) == ':' && value.charAt(0).isLetter)
        Left(s"not a relative path (drive-anchored): $value")
      else
        Right(new RelPath(value))
  }
}
