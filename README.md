# Redis Rate Limit
A cats friendly and lightweight library that does rate limiting using token bucket algorithm. The library executes the redis commands as `Lua` code which makes the operations atomic. This execution happens in a blocking effect (`Sync[F].blocking`).  The library requires [scala-redis](https://github.com/debasishg/scala-redis).

## Importing
Add the following to your `build.sbt`
```
libraryDependencies =+ "io.github.ashwinbhaskar" %% "redis-rate-limit" % "1.0.0"
```

## Usage

```scala
import cats.effect.IO
import com.redis.RedisClient //From scala-redis library
import com.redis.ratelimit._ //This library

// Example Scenario: Don't allow more than 5 requests per user per 5 seconds
val host: String = ???
val port: Int = ???
implicit val redisClient: RedisClient = new RedisClient(host, port)
val userId: String = ???

val effectToBeRateLimited: IO[String] = ???

val result: IO[String] = rateLimited[IO, String](effectToBeRateLimited)(key = userId, maxTokens = 5,
    timeWindowInSec = 5)

result.handleErrorWith {
    case RedisConnectionError(msg) => ??? 
    case RateLimitExceeded => ???
}
```

