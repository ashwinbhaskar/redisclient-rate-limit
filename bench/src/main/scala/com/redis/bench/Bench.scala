package com.redis.bench

import java.io.{InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.annotation.nowarn
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.dimafeng.testcontainers.GenericContainer
import com.redis.{RedisClient, RedisClientPool}
import com.redis.common._
import com.redis.common.Config
import zio.{Clock => ZClock, Ref => ZRef, Runtime, Unsafe, ZIO}

/** Compares ways of running the rate-limit check under concurrent load.
  *
  * Each variant runs `Fibers` fibers that each make `CallsPerFiber` calls on
  * their own key, with a limit high enough that every call is allowed. A
  * heartbeat fiber sleeps 10ms in a loop on the same runtime and records how
  * late it wakes up: if Redis calls block the compute threads, the heartbeat
  * can't be scheduled and its lateness grows.
  *
  * Runs against redis:7.4 in Docker, both directly and through a local proxy
  * that delays every request by `ProxyDelayMs` to approximate a network round
  * trip. Set REDIS_HOST/REDIS_PORT to use an existing Redis instead.
  *
  * Run with `sbt bench/run`.
  */
object Bench {

  val Fibers = 200
  val CallsPerFiber = 50
  val Repeats = 3
  val ProxyDelayMs = 1
  val MaxTokens = 1000000L
  val WindowSec = 60L

  sealed trait Variant { def name: String }
  final case class Ce(name: String, call: String => IO[Unit]) extends Variant
  final case class Zio(name: String, call: String => ZIO[Any, Throwable, Unit])
      extends Variant

  final case class Result(
      name: String,
      callsPerSec: Double,
      p50Us: Long,
      p99Us: Long,
      heartbeatLateMs: Long,
      newConnections: Long
  )

  def main(args: Array[String]): Unit = {
    val (host, port, stopRedis) = startRedis()
    val proxy = new DelayProxy(host, port, ProxyDelayMs)
    try {
      concurrent("Redis on localhost", host, port)
      concurrent(
        s"Redis behind a +${ProxyDelayMs}ms proxy",
        "127.0.0.1",
        proxy.port
      )
      sequential("Redis on localhost", host, port)
      sequential(
        s"Redis behind a +${ProxyDelayMs}ms proxy",
        "127.0.0.1",
        proxy.port
      )
    } finally {
      proxy.close()
      stopRedis()
    }
  }

  private def concurrent(title: String, host: String, port: Int): Unit = {
    val config = Config(host, port, MaxTokens, WindowSec)
    val pool8 = newPool(host, port, maxIdle = 8)
    val pool32 = newPool(host, port, maxIdle = 32)
    val pool64 = newPool(host, port, maxIdle = 64)
    val variants = List(
      Ce("4.1 CE, F.blocking (library)", ceLibrary(config)),
      Ce("4.1 CE, F.delay", ceCall(pool32, blocking = false)),
      Zio(
        "4.1 ZIO, attemptBlocking (library)",
        zioCall(ClientCache.pool(host, port), blocking = true)
      ),
      Zio("4.1 ZIO, attempt", zioCall(pool32, blocking = false)),
      Ce("4.0 CE, EVAL + SET, F.blocking, pooled", ceOldCall(pool32)),
      Ce("4.1 CE, F.blocking, pool maxIdle 8", ceCall(pool8, blocking = true)),
      Ce("4.1 CE, F.blocking, pool maxIdle 64", ceCall(pool64, blocking = true))
    )
    println(
      s"\n## Concurrent, $title: $Fibers fibers x $CallsPerFiber calls, median of $Repeats\n"
    )
    report(variants, host, port, Fibers, CallsPerFiber)
    List(pool8, pool32, pool64).foreach(_.close())
  }

  private def sequential(title: String, host: String, port: Int): Unit = {
    val pool = newPool(host, port, maxIdle = 8)
    val variants = List(
      Ce("4.0, EVAL + SET", ceOldCall(pool)),
      Ce("4.1, EVALSHA", ceCall(pool, blocking = true))
    )
    println(
      s"\n## Sequential, $title: 1 fiber x 1000 calls, median of $Repeats\n"
    )
    report(variants, host, port, 1, 1000)
    pool.close()
  }

  private def report(
      variants: List[Variant],
      host: String,
      port: Int,
      fibers: Int,
      callsPerFiber: Int
  ): Unit = {
    // Warm up the JIT, the connection pools and the script cache.
    variants.foreach(measure(_, host, port, fibers, 5))
    val results = variants.map { v =>
      val runs =
        List.fill(Repeats)(measure(v, host, port, fibers, callsPerFiber))
      runs.sortBy(_.callsPerSec).apply(Repeats / 2)
    }
    println(
      "| Variant | calls/s | p50 µs | p99 µs | heartbeat max late ms | new connections |"
    )
    println("|---|---:|---:|---:|---:|---:|")
    results.foreach(r =>
      println(
        f"| ${r.name} | ${r.callsPerSec}%.0f | ${r.p50Us} | ${r.p99Us} | ${r.heartbeatLateMs} | ${r.newConnections} |"
      )
    )
  }

  private def measure(
      variant: Variant,
      host: String,
      port: Int,
      fibers: Int,
      callsPerFiber: Int
  ): Result = {
    val connectionsBefore = totalConnections(host, port)
    val (seconds, latenciesNs, lateMs) = variant match {
      case Ce(_, call)  => ceDrive(call, fibers, callsPerFiber)
      case Zio(_, call) => zioDrive(call, fibers, callsPerFiber)
    }
    // Minus one for the INFO connection made by totalConnections itself.
    val newConnections = totalConnections(host, port) - connectionsBefore - 1
    val sorted = latenciesNs.sorted
    Result(
      variant.name,
      sorted.size / seconds,
      sorted(sorted.size / 2) / 1000,
      sorted(sorted.size * 99 / 100) / 1000,
      lateMs,
      newConnections
    )
  }

  // ---- Cats Effect ---------------------------------------------------------

  private def ceLibrary(config: Config)(key: String): IO[Unit] = {
    implicit val c: Config = config
    com.redis.ratelimit.rateLimited[IO, Unit](IO.unit, key)
  }

  private def ceCall(pool: RedisClientPool, blocking: Boolean)(
      key: String
  ): IO[Unit] =
    for {
      now <- IO.realTime
      member <- IO.delay(SlidingWindow.newMember(now.toMillis))
      check = () =>
        pool.withClient(c =>
          ScriptRunner.run(c, key, MaxTokens, WindowSec, now.toMillis, member)
        )
      decision <- if (blocking) IO.blocking(check()) else IO.delay(check())
      _ <- IO.raiseUnless(decision.allowed)(RateLimitExceeded)
    } yield ()

  // The 4.0 algorithm (EVAL with the full script, then SET), but on a pool:
  // 4.0's single shared client hangs under concurrent use.
  @nowarn("cat=deprecation")
  private def ceOldCall(pool: RedisClientPool)(key: String): IO[Unit] =
    for {
      now <- IO.realTime.map(_.toSeconds)
      remaining <- IO.blocking(pool.withClient { c =>
        val lastReset = key ++ lastResetTimeSuffix
        val args =
          List(now, WindowSec, lastReset, key ++ counterSuffix, MaxTokens)
        val remaining = c.evalInt(luaCode, List.empty, args).get
        if (remaining >= 0) c.set(lastReset, now)
        remaining
      })
      _ <- IO.raiseUnless(remaining >= 0)(RateLimitExceeded)
    } yield ()

  private def ceDrive(
      call: String => IO[Unit],
      fibers: Int,
      callsPerFiber: Int
  ): (Double, Vector[Long], Long) =
    (for {
      lateness <- IO.ref(0L)
      heartbeat <- (for {
        t0 <- IO.monotonic
        _ <- IO.sleep(10.millis)
        t1 <- IO.monotonic
        _ <- lateness.update(_ max (t1 - t0 - 10.millis).toMillis)
      } yield ()).foreverM.start
      start <- IO.monotonic
      latencies <- (0 until fibers).toList.parTraverse { _ =>
        val key = s"bench-${UUID.randomUUID()}"
        (0 until callsPerFiber).toList.traverse(_ =>
          IO.monotonic.flatMap(t0 =>
            call(key) *> IO.monotonic.map(t1 => (t1 - t0).toNanos)
          )
        )
      }
      end <- IO.monotonic
      _ <- heartbeat.cancel
      late <- lateness.get
    } yield ((end - start).toNanos / 1e9, latencies.flatten.toVector, late))
      .unsafeRunSync()

  // ---- ZIO -----------------------------------------------------------------

  private def zioCall(pool: RedisClientPool, blocking: Boolean)(
      key: String
  ): ZIO[Any, Throwable, Unit] =
    for {
      nowMs <- ZClock.currentTime(TimeUnit.MILLISECONDS)
      member <- ZIO.succeed(SlidingWindow.newMember(nowMs))
      check = () =>
        pool.withClient(c =>
          ScriptRunner.run(c, key, MaxTokens, WindowSec, nowMs, member)
        )
      decision <-
        if (blocking) ZIO.attemptBlocking(check()) else ZIO.attempt(check())
      _ <- ZIO.fail(RateLimitExceeded).unless(decision.allowed)
    } yield ()

  private def zioDrive(
      call: String => ZIO[Any, Throwable, Unit],
      fibers: Int,
      callsPerFiber: Int
  ): (Double, Vector[Long], Long) =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe
        .run(for {
          lateness <- ZRef.make(0L)
          heartbeat <- (for {
            t0 <- ZClock.nanoTime
            _ <- ZIO.sleep(zio.Duration.fromMillis(10))
            t1 <- ZClock.nanoTime
            _ <- lateness.update(_ max ((t1 - t0) / 1000000 - 10))
          } yield ()).forever.fork
          start <- ZClock.nanoTime
          latencies <- ZIO.foreachPar((0 until fibers).toList) { _ =>
            val key = s"bench-${UUID.randomUUID()}"
            ZIO.foreach((0 until callsPerFiber).toList)(_ =>
              ZClock.nanoTime
                .flatMap(t0 => call(key) *> ZClock.nanoTime.map(t1 => t1 - t0))
            )
          }
          end <- ZClock.nanoTime
          _ <- heartbeat.interrupt
          late <- lateness.get
        } yield ((end - start) / 1e9, latencies.flatten.toVector, late))
        .getOrThrow()
    }

  // ---- Redis ---------------------------------------------------------------

  private def newPool(host: String, port: Int, maxIdle: Int) =
    new RedisClientPool(host, port, maxIdle = maxIdle, timeout = 5000)

  private def totalConnections(host: String, port: Int): Long = {
    val client = new RedisClient(host, port)
    try
      client.info
        .getOrElse("")
        .linesIterator
        .collectFirst {
          case l if l.startsWith("total_connections_received:") =>
            l.stripPrefix("total_connections_received:").trim.toLong
        }
        .getOrElse(0L)
    finally {
      client.disconnect
      ()
    }
  }

  private def startRedis(): (String, Int, () => Unit) =
    sys.env.get("REDIS_HOST") match {
      case Some(host) =>
        (host, sys.env.getOrElse("REDIS_PORT", "6379").toInt, () => ())
      case None =>
        val c = GenericContainer("redis:7.4", exposedPorts = Seq(6379))
        c.start()
        (c.host, c.mappedPort(6379), () => c.stop())
    }

  /** A TCP proxy that delays each client-to-server chunk by `delayMs`. */
  final class DelayProxy(targetHost: String, targetPort: Int, delayMs: Int)
      extends AutoCloseable {
    private val server = new ServerSocket()
    server.bind(new InetSocketAddress("127.0.0.1", 0), 1024)
    val port: Int = server.getLocalPort

    daemon {
      while (!server.isClosed) {
        val client =
          try server.accept()
          catch { case _: Exception => null }
        if (client != null) {
          val upstream = new Socket(targetHost, targetPort)
          client.setTcpNoDelay(true)
          upstream.setTcpNoDelay(true)
          pump(
            client.getInputStream,
            upstream.getOutputStream,
            delayMs,
            client,
            upstream
          )
          pump(
            upstream.getInputStream,
            client.getOutputStream,
            0,
            client,
            upstream
          )
        }
      }
    }

    private def pump(
        in: InputStream,
        out: OutputStream,
        delay: Int,
        a: Socket,
        b: Socket
    ): Unit =
      daemon {
        val buf = new Array[Byte](16384)
        try {
          var n = in.read(buf)
          while (n >= 0) {
            if (delay > 0) Thread.sleep(delay.toLong)
            out.write(buf, 0, n)
            out.flush()
            n = in.read(buf)
          }
        } catch { case _: Exception => () }
        finally { a.close(); b.close() }
      }

    private def daemon(body: => Unit): Unit = {
      val t = new Thread(() => body)
      t.setDaemon(true)
      t.start()
    }

    def close(): Unit = server.close()
  }
}
