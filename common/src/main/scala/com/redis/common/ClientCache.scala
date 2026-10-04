package com.redis.common

import java.util.concurrent.ConcurrentHashMap
import com.redis.RedisClientPool

/** One connection pool per Redis server, shared by every limit that uses it.
  * Pools live for the lifetime of the JVM.
  */
private[redis] object ClientCache {

  // Connections kept open between calls. Extra connections opened during a
  // burst are closed when they're returned.
  private[redis] val MaxIdle = 32

  // Connect and read timeout. Without one, a call to an unresponsive Redis
  // blocks its thread indefinitely.
  private[redis] val TimeoutMs = 5000

  private val pools = new ConcurrentHashMap[(String, Int), RedisClientPool]()

  def pool(host: String, port: Int): RedisClientPool =
    pools.computeIfAbsent(
      (host, port),
      _ =>
        new RedisClientPool(host, port, maxIdle = MaxIdle, timeout = TimeoutMs)
    )
}
