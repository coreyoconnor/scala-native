package scala.scalanative
package build

import java.io.IOException
import java.nio.file.attribute.{BasicFileAttributes, DosFileAttributes}
import java.nio.file.{
  AccessDeniedException, FileSystems, FileVisitOption, FileVisitResult, Files,
  Path, Paths, SimpleFileVisitor, StandardCopyOption
}
import java.util.EnumSet
import java.util.zip.{ZipEntry, ZipInputStream}

import scala.util.control.NonFatal

/** Internal I/O utilities. */
private[scalanative] object IO {

  implicit class RichPath(val path: Path) extends AnyVal {
    def abs: String = path.toAbsolutePath.toString.norm
  }
  implicit class RichString(val s: String) extends AnyVal {
    // commands issued in shell environments require forward slash
    // clang and llvm command line tools accept forward slash
    def norm: String = s.replace('\\', '/')
  }

  /** Write bytes to given file. */
  def write(file: Path, bytes: Array[Byte]): Unit = {
    import java.nio.file.StandardOpenOption._
    Files.createDirectories(file.getParent)
    Files.write(file, bytes, CREATE, WRITE)
  }

  /** Write string to given file. */
  def write(path: Path, content: String): Unit =
    write(path, content.getBytes())

  // Read fully content of given file if it exists
  def readFully(path: Path): Option[String] = {
    if (!Files.exists(path)) None
    else {
      val source = scala.io.Source.fromFile(path.toFile())
      try Some(source.mkString)
      catch { case _: Exception => None }
      finally source.close()
    }
  }

  /** Finds all files starting in `base` that match `pattern`. */
  def getAll(base: Path, pattern: String): Seq[Path] = {
    val out = collection.mutable.ArrayBuffer.empty[Path]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)
    val visitor = new SimpleFileVisitor[Path] {
      override def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult =
        FileVisitResult.CONTINUE

      override def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult =
        FileVisitResult.CONTINUE

      override def visitFile(
          file: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        if (matcher.matches(file)) out += file
        FileVisitResult.CONTINUE
      }

      override def visitFileFailed(
          file: Path,
          exception: IOException
      ): FileVisitResult =
        FileVisitResult.CONTINUE
    }
    Files.walkFileTree(
      base,
      EnumSet.of(FileVisitOption.FOLLOW_LINKS),
      Int.MaxValue,
      visitor
    )
    out.toSeq
  }

  /** Does a `pattern` match starting at base */
  def existsInDir(base: Path, pattern: String): Boolean = {
    var out = false
    val matcher = base.getFileSystem.getPathMatcher(pattern)
    val visitor = new SimpleFileVisitor[Path] {
      override def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult =
        FileVisitResult.CONTINUE

      override def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult =
        FileVisitResult.CONTINUE

      override def visitFile(
          file: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        if (matcher.matches(file)) {
          out = true
          FileVisitResult.TERMINATE
        } else {
          FileVisitResult.CONTINUE
        }
      }

      override def visitFileFailed(
          file: Path,
          exception: IOException
      ): FileVisitResult =
        FileVisitResult.CONTINUE
    }
    Files.walkFileTree(
      base,
      EnumSet.of(FileVisitOption.FOLLOW_LINKS),
      Int.MaxValue,
      visitor
    )
    out
  }

  /** Look for a zip entry path string using a matcher function */
  def existsInJar(path: Path, matcher: String => Boolean): Boolean = {
    import java.util.zip.ZipFile
    val zf = new ZipFile(path.toFile)
    val it = zf.entries()
    while (it.hasMoreElements()) {
      if (matcher(it.nextElement().getName()))
        return true
    }
    false
  }

  /** Deletes recursively `directory` and all its content. */
  def deleteRecursive(directory: Path): Unit = {
    // On Windows the file permissions / locks are slow leading to AccessDeniedException
    // we might need to revisit the directory to ensure it is deleted
    var shouldRetry = false
    var remainingRetries = 3
    def tryDelete(path: Path, isRetry: Boolean = false): Unit =
      try Files.deleteIfExists(path)
      catch {
        case _: AccessDeniedException if Platform.isWindows && !isRetry =>
          if (Files.notExists(path)) ()
          else
            try {
              val attrs = Files.readAttributes(path, classOf[DosFileAttributes])
              if (attrs.isReadOnly()) {
                Files.setAttribute(path, "dos:readonly", false)
              }
              tryDelete(path, isRetry = true)
            } catch { case NonFatal(_) => shouldRetry = true }
        case NonFatal(_) => shouldRetry = true
      }

    while (Files.exists(directory) && remainingRetries > 0) {
      // If retrying the cleanup give OS a bit of time to close any pending locks
      if (shouldRetry) Thread.sleep(50)
      shouldRetry = false
      Files.walkFileTree(
        directory,
        new SimpleFileVisitor[Path]() {
          override def visitFile(
              file: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult = {
            tryDelete(file)
            FileVisitResult.CONTINUE
          }
          override def postVisitDirectory(
              dir: Path,
              exc: IOException
          ): FileVisitResult = {
            tryDelete(dir)
            FileVisitResult.CONTINUE
          }
        }
      )
      remainingRetries -= 1
    }
  }

  /** Compute a SHA-1 hash of `path`. */
  def sha1(path: Path, bufSize: Int = 1024): Array[Byte] = {
    val digest = new Sha1Digest()
    val stream = Files.newInputStream(path)
    try {
      val buf = new Array[Byte](bufSize)
      var n = stream.read(buf, 0, bufSize)
      while (n != -1) {
        digest.update(buf, 0, n)
        n = stream.read(buf, 0, bufSize)
      }
      digest.digest()
    } finally {
      stream.close()
    }
  }

  /** Compute a SHA-1 hash of `files`. */
  def sha1files(files: Seq[Path], bufSize: Int = 1024): Array[Byte] = {
    val digest = new Sha1Digest()
    files.foreach { file =>
      val stream = Files.newInputStream(file)
      val buf = new Array[Byte](bufSize)
      try {
        var n = stream.read(buf, 0, bufSize)
        while (n != -1) {
          digest.update(buf, 0, n)
          n = stream.read(buf, 0, bufSize)
        }
      } finally {
        stream.close()
      }
    }
    digest.digest()
  }

  // Standalone SHA-1 implementation -- Scala Native's own javalib doesn't
  // port java.security.MessageDigest/DigestInputStream, and this is the only
  // caller in the whole toolchain, so a private, self-contained digest here
  // avoids adding a half-finished JCA surface to javalib just for this.
  private final class Sha1Digest {
    private val buffer = new Array[Byte](64)
    private var bufferLen = 0
    private var messageLen = 0L
    private var h0 = 0x67452301
    private var h1 = 0xefcdab89
    private var h2 = 0x98badcfe
    private var h3 = 0x10325476
    private var h4 = 0xc3d2e1f0

    private val w = new Array[Int](80)

    private def processBlock(block: Array[Byte], off: Int): Unit = {
      var i = 0
      while (i < 16) {
        val j = off + i * 4
        w(i) = ((block(j) & 0xff) << 24) |
          ((block(j + 1) & 0xff) << 16) |
          ((block(j + 2) & 0xff) << 8) |
          (block(j + 3) & 0xff)
        i += 1
      }
      i = 16
      while (i < 80) {
        val v = w(i - 3) ^ w(i - 8) ^ w(i - 14) ^ w(i - 16)
        w(i) = (v << 1) | (v >>> 31)
        i += 1
      }

      var a = h0
      var b = h1
      var c = h2
      var d = h3
      var e = h4

      i = 0
      while (i < 80) {
        var f = 0
        var k = 0
        if (i < 20) {
          f = (b & c) | ((~b) & d)
          k = 0x5a827999
        } else if (i < 40) {
          f = b ^ c ^ d
          k = 0x6ed9eba1
        } else if (i < 60) {
          f = (b & c) | (b & d) | (c & d)
          k = 0x8f1bbcdc
        } else {
          f = b ^ c ^ d
          k = 0xca62c1d6
        }

        val temp = ((a << 5) | (a >>> 27)) + f + e + k + w(i)
        e = d
        d = c
        c = (b << 30) | (b >>> 2)
        b = a
        a = temp
        i += 1
      }

      h0 += a
      h1 += b
      h2 += c
      h3 += d
      h4 += e
    }

    private def updateByte(b: Byte): Unit = {
      buffer(bufferLen) = b
      bufferLen += 1
      messageLen += 1
      if (bufferLen == 64) {
        processBlock(buffer, 0)
        bufferLen = 0
      }
    }

    def update(input: Array[Byte], offset: Int, len: Int): Unit = {
      var i = 0
      while (i < len) {
        updateByte(input(offset + i))
        i += 1
      }
    }

    def digest(): Array[Byte] = {
      val bitLen = messageLen * 8
      updateByte(0x80.toByte)
      while (bufferLen != 56) updateByte(0.toByte)
      var i = 7
      while (i >= 0) {
        updateByte(((bitLen >>> (i * 8)) & 0xff).toByte)
        i -= 1
      }

      val result = new Array[Byte](20)
      val words = Array(h0, h1, h2, h3, h4)
      i = 0
      while (i < 5) {
        result(i * 4) = ((words(i) >>> 24) & 0xff).toByte
        result(i * 4 + 1) = ((words(i) >>> 16) & 0xff).toByte
        result(i * 4 + 2) = ((words(i) >>> 8) & 0xff).toByte
        result(i * 4 + 3) = (words(i) & 0xff).toByte
        i += 1
      }
      result
    }
  }

  /** Per-archive lock objects, keyed by absolute path -- see `unzip`'s doc
   *  comment for why unzipping needs one.
   */
  private val archiveLocks =
    new java.util.concurrent.ConcurrentHashMap[String, Object]()

  private def lockFor(archive: Path): Object =
    archiveLocks.computeIfAbsent(
      archive.toAbsolutePath.toString,
      _ => new Object
    )

  /** Unzip all members of the ZIP archive `archive` to `target`.
   *
   *  Concurrent invocations for the same archive serialize on a per-archive
   *  lock: `findAndCompileNativeLibraries` unzips native libraries
   *  concurrently (`Future.traverse`), and more than one of them can point
   *  at the exact same archive path (e.g. two native-lib units bundled in
   *  one jar) -- each `unzip()` call opens its own independent zip
   *  filesystem, so the lock isn't needed to avoid a collision exception,
   *  only to avoid N callers redundantly decompressing the same archive
   *  into the same target directory at once.
   */
  def unzip(archive: Path, target: Path): Unit = {
    Files.createDirectories(target)
    lockFor(archive).synchronized {
      val zipFS = FileSystems.newFileSystem(archive, null: ClassLoader)
      try {
        val rootDirectories = zipFS.getRootDirectories().iterator
        while (rootDirectories.hasNext) {
          val root = rootDirectories.next()
          copyRecursive(root, target)
        }
      } finally zipFS.close()
    }
  }

  /** Copy source directory and contents to target directory. */
  def copyDirectory(source: Path, target: Path): Unit = {
    Files.createDirectories(target)
    copyRecursive(source, target)
  }

  /** Copy recursively to existing target directory
   *
   *  Note: We need source.relativize(file) for copying to and from UNIX FS to
   *  get a relative path. We can't use the following code because you can't
   *  resolve across filesystems like UNIX FS to ZIP FS: val dest =
   *  target.resolve(source.relativize(file))
   */
  private def copyRecursive(source: Path, target: Path): Path = {
    Files.walkFileTree(
      source,
      new SimpleFileVisitor[Path]() {
        override def visitFile(
            file: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val dest =
            Paths.get(target.toString, source.relativize(file).toString())
          Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
          FileVisitResult.CONTINUE
        }

        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val dest =
            Paths.get(target.toString, source.relativize(dir).toString())
          Files.createDirectories(dest)
          FileVisitResult.CONTINUE
        }
      }
    )
  }
}
