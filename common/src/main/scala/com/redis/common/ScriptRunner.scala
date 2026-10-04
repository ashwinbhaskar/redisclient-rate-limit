package com.redis.common

import com.redis.RedisClient
import com.redis.serialization.Parse.Implicits.parseLong

/** Runs the sliding-window script with one blocking round trip. */
private[redis] object ScriptRunner {

  def run(
      client: RedisClient,
      key: String,
      maxTokens: Long,
      timeWindowInSec: Long,
      nowMs: Long,
      member: String
  ): Decision = {
    val keys = List(SlidingWindow.redisKey(key, maxTokens, timeWindowInSec))
    val args = List(maxTokens, timeWindowInSec * 1000, nowMs, member)
    val reply = keepInSync(client) {
      try client.evalMultiSHA[Long](SlidingWindow.sha, keys, args)
      catch {
        // Not cached on this server yet (first call, restart, SCRIPT FLUSH).
        // EVAL runs it and caches it for the next EVALSHA.
        case e: Exception if isErrorReply(e, "NOSCRIPT") =>
          client.evalMultiBulk[Long](SlidingWindow.script, keys, args)
      }
    }
    SlidingWindow.decision(reply.getOrElse(Nil))
  }

  /** scala-redis surfaces Redis error replies as a plain `Exception` after
    * reading the whole reply, so the connection is still usable. Anything else
    * (timeouts, dropped connections, protocol errors) can leave unread bytes on
    * the socket, and the next caller would read them as its own reply. Drop the
    * connection in that case: the client reconnects on next use, and a pooled
    * client fails validation and is discarded.
    */
  private def keepInSync[A](client: RedisClient)(body: => A): A =
    try body
    catch {
      case e: Throwable if !isErrorReply(e, "") =>
        client.disconnect
        throw e
    }

  private def isErrorReply(e: Throwable, prefix: String): Boolean =
    e.getClass == classOf[Exception] && Option(e.getMessage).exists(m =>
      m.startsWith(prefix) && !m.startsWith("Protocol error")
    )
}
