package scala.scalanative.nir

import java.nio.file.{Path, Paths}

import scala.util.Try

sealed case class NIRSource(directory: Path, path: Path) {
  def debugName = s"${directory}:${path}"
  def exists: Boolean = this ne NIRSource.None

  // Path.hashCode/equals for a non-default FileSystemProvider (e.g. a
  // jar/zip-backed dependency path) folds in the owning FileSystem
  // instance's identity, and each build run opens a dependency jar via a
  // fresh FileSystem. Two builds' NIRSource for the same library defn
  // therefore hash differently even though directory/path stringify
  // identically, which propagates into every Defn's cached hashCode via
  // DebugInfo.lexicalScopes and defeats IncrementalCodeGenContext's cache
  // for defns from dependency jars. Compare/hash by path string content
  // instead.
  private def pathToString(p: Path): String = if (p eq null) null else p.toString

  override def hashCode: Int =
    pathToString(directory).## * 31 + pathToString(path).##

  override def equals(other: Any): Boolean = other match {
    case that: NIRSource =>
      pathToString(directory) == pathToString(that.directory) &&
        pathToString(path) == pathToString(that.path)
    case _ => false
  }
}
object NIRSource {
  object None extends NIRSource(null, null) {
    override def debugName: String = "<no-source>"
    override def toString(): String = s"NIRSource($debugName)"
  }
}

final case class SourcePosition(
    /** Scala source file containing definition of element */
    source: SourceFile,
    /** Zero-based line number in the source. */
    line: Int,
    /** Zero-based column number in the source */
    column: Int,
    /** NIR file coordinates used to deserialize the symbol, populated only when
     *  linking
     */
    nirSource: NIRSource = NIRSource.None
) {

  /** One-based line number */
  def sourceLine: Int = line + 1

  /** One-based column number */
  def sourceColumn: Int = column + 1
  def show: String = {
    val source = this.source match {
      case SourceFile.Virtual              => "<virtual>"
      case SourceFile.Relative(pathString) => pathString
    }
    s"$source:$sourceLine:$sourceColumn"
  }

  def isEmpty: Boolean = this eq SourcePosition.NoPosition
  def isDefined: Boolean = !isEmpty
  def orElse(other: => SourcePosition): SourcePosition =
    if (isEmpty) other
    else this
}

object SourcePosition {
  val NoPosition = SourcePosition(SourceFile.Virtual, 0, 0)
}

sealed trait SourceFile {
  def filename: Option[String] = this match {
    case SourceFile.Virtual          => None
    case source: SourceFile.Relative =>
      Option(source.path.getFileName()).map(_.toString())
  }
  def directory: Option[String] = this match {
    case SourceFile.Virtual          => None
    case source: SourceFile.Relative =>
      Option(source.path.getParent()).map(_.toString())
  }
}
object SourceFile {

  /** An abstract file without location, e.g. in-memory source or generated */
  case object Virtual extends SourceFile

  /** Relative path to source file based on the workspace path. Used for
   *  providing source files defined from the local project dependencies.
   *  @param pathString
   *    path relative to `-sourceroot` setting defined when compiling source -
   *    typically it's root directory of workspace
   */
  case class Relative(pathString: String) extends SourceFile {
    lazy val path: Path = Paths.get(pathString)
  }
}
