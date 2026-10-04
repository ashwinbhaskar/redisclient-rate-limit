# Changelog

## 4.1.0 (unreleased)

A bug-fix release. The public API is unchanged and binary compatible with
4.0.0, but rate limiting behaves differently (see "Upgrading").

### Fixed

- **Clients under the limit were locked out.** Every allowed call restarted
  the time window, so a client whose calls were never a full window apart was
  eventually denied, even far below the limit (for example one call every 4s
  against 6 per 5s). The limit is now a sliding window: a call is allowed if
  fewer than `maxTokens` calls were allowed in the last `timeWindowInSec`
  seconds. Bursts across a window boundary are still capped at `maxTokens`.
- **Concurrent calls could hang or read each other's replies.** A scala-redis
  `RedisClient` is a single, non-thread-safe connection, and 4.0 shared one per
  `Config` across all fibers. The `Config` overloads now use a connection pool
  per Redis host and port. Calls on a `RedisClient` you pass in are serialised
  on that client.
- **Redis keys never expired.** Each key now expires one window after its last
  allowed call.
- **The check was not atomic.** It now runs as a single Lua script.
- `RedisConnectionError` now has a message (`getMessage` returned `null`) and
  keeps the original exception as its cause.
- Redis calls run on the blocking thread pool instead of the compute pool.

### Changed

- One Redis round trip per call instead of two, using `EVALSHA` instead of
  sending the whole script each time.
- Different limits on the same key are now counted separately.
- `maxTokens <= 0` denies every call without contacting Redis;
  `timeWindowInSec <= 0` fails with `IllegalArgumentException`.
- Requires Java 11 or later (ZIO 2.1.14+ targets Java 11).
- Dependencies: cats-effect 3.7.1, zio 2.1.26.

### Upgrading

- **New key format.** Rate-limit state is now stored in one sorted set per key
  and limit, named `rate_limit:{<key>}:<maxTokens>:<timeWindowInSec>`. Existing
  windows start empty after the upgrade, and while 4.0 and 4.1 instances run
  side by side during a rolling deploy they count separately, so up to twice
  the limit can be allowed for one window.
- **Memory.** The sorted set holds one entry per allowed call in the window, so
  a key uses memory proportional to `maxTokens`. This is fine for typical
  per-user limits; very large limits (hundreds of thousands per window) use
  correspondingly more memory.
- **Cleaning up 4.0 keys.** 4.0 keys never expire. On a standalone Redis you
  can delete them with:

  ```sh
  redis-cli --scan --pattern '*:rate_limit_counter' | xargs -r -d '\n' -n 100 redis-cli unlink
  redis-cli --scan --pattern '*:rate_limit_last_reset' | xargs -r -d '\n' -n 100 redis-cli unlink
  ```

  Add `-h`/`-p`/`-a` to both `redis-cli` calls as needed, and check what the
  patterns match first if other applications share the Redis instance.
- Redis Cluster is still not supported: scala-redis doesn't follow cluster
  redirects.
