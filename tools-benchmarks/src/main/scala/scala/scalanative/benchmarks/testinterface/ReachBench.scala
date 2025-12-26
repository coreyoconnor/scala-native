package scala.scalanative
package benchmarks

import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

import org.openjdk.jmh.annotations.Mode._
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import scala.scalanative.linker.{ ClassLoader, Reach }
import scala.scalanative.build.Config
import scala.scalanative.nir.Global

@Fork(1)
@State(Scope.Benchmark)
@BenchmarkMode(Array(AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = TimeUnit.SECONDS)
class ReachBench {
  var workdir: Path = _
  var config: Config = _
  var entries: Seq[Global] = _

  @Setup(Level.Iteration)
  def setup(): Unit = {
    workdir = Files.createTempDirectory("reach-bench")
    config = defaultConfig
      .withBaseDir(workdir)
      .withMainClass(Some(TestMain))

    entries = build.ScalaNative.entries(config)
  }

  @TearDown(Level.Iteration)
  def cleanup(): Unit = {
    Files
      .walk(workdir)
      .sorted(Comparator.reverseOrder())
      .forEach(Files.delete)
    workdir = null
    config = null
    entries = null
  }

  @Benchmark
  def reach(blackhole: Blackhole): Unit = util.Scope { implicit scope =>
    val reach = Reach(config, entries, ClassLoader.fromDisk(config))
    blackhole.consume(reach)
  }

  @Benchmark
  def load0(blackhole: Blackhole): Unit = util.Scope { implicit scope =>
    val reach = Reach(config, entries, ClassLoader.fromDisk(config))
    blackhole.consume(reach)
  }
}

