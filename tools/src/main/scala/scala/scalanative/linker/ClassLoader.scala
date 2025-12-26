package scala.scalanative
package linker

import scala.collection.mutable

import scala.scalanative.build.Logger
import scalanative.io.VirtualDirectory
import scalanative.util.Scope

sealed abstract class ClassLoader {

  def classesWithEntryPoints: Iterable[nir.Global.Top]

  def definedServicesProviders: Map[nir.Global.Top, Iterable[nir.Global.Top]]

  def load(global: nir.Global.Top): Option[Seq[nir.Defn]]

  def loadable(name: nir.Global): Option[ClassLoader.Loadable]
}

object ClassLoader {
  sealed trait Loadable {
    val name: nir.Global
    val sourceId: Int
    def load: Option[Seq[nir.Defn]]
  }
  case class ClassPathLoadable(name: nir.Global, classpath: ClassPath) extends Loadable {
    val sourceId: Int = classpath.hashCode()
    def load: Option[Seq[nir.Defn]] = classpath.load(name.top)
  }
  case class MemoryLoadable(name: nir.Global, fromMemory: FromMemory) extends Loadable {
    val sourceId: Int = fromMemory.hashCode()
    def load: Option[Seq[nir.Defn]] = fromMemory.load(name.top)
  }

  object Loadable {
    implicit def loadableOrder: Ordering[Loadable] = Ordering.by[Loadable, Int](_.sourceId)
  }

  def fromDisk(config: build.Config)(implicit in: Scope): ClassLoader = {
    val classpath = config.classPath.map { path =>
      ClassPath(VirtualDirectory.real(path), config.logger)
    }
    new FromDisk(classpath)
  }

  def fromMemory(defns: Seq[nir.Defn]): ClassLoader =
    new FromMemory(defns)

  final class FromDisk(classpath: Seq[ClassPath]) extends ClassLoader {
    lazy val classesWithEntryPoints: Iterable[nir.Global.Top] = {
      classpath.flatMap(_.classesWithEntryPoints)
    }
    lazy val definedServicesProviders
        : Map[nir.Global.Top, Iterable[nir.Global.Top]] =
      classpath.flatMap(_.definedServicesProviders).toMap

    def load(global: nir.Global.Top): Option[Seq[nir.Defn]] =
      classpath.find(_.contains(global)).flatMap { path =>
          path.load(global)
      }

    def loadable(name: nir.Global): Option[ClassLoader.Loadable] =
      classpath.find(_.contains(name.top)).map { path =>
        ClassPathLoadable(name, path)
      }
  }

  final class FromMemory(defns: Seq[nir.Defn]) extends ClassLoader {

    private val scopes = {
      val out =
        mutable.Map.empty[nir.Global.Top, mutable.UnrolledBuffer[nir.Defn]]
      defns.foreach { defn =>
        val owner = defn.name.top
        val buf =
          out.getOrElseUpdate(owner, mutable.UnrolledBuffer.empty[nir.Defn])
        buf += defn
      }
      out
    }

    lazy val classesWithEntryPoints: Iterable[nir.Global.Top] = {
      scopes.filter {
        case (_, defns) => defns.exists(_.isEntryPoint)
      }.keySet
    }

    def definedServicesProviders
        : Map[nir.Global.Top, Iterable[nir.Global.Top]] =
      Map.empty

    def load(global: nir.Global.Top): Option[Seq[nir.Defn]] =
      scopes.get(global).map(_.toSeq)

    def loadable(name: nir.Global): Option[ClassLoader.Loadable] =
      scopes.get(name.top).map(_ => MemoryLoadable(name, this))
  }

}
