# Redis-Rate-Limit
A cats friendly lightweight library that takes care of rate limiting using token bucket algorithm. This library requires [scala-redis](https://github.com/debasishg/scala-redis).

## Importing
Add the following to your `build.sbt`
```
libraryDependencies =+ "io.github.ashwinbhaskar" %% "redis.ratelimit" % "0.1.0"
```

## Usage

```scala
import java.time.ZonedDateTime
import com.redis.RedisClient //From scala-redis library
import com.redis.ratelimit._ //This library
import cats.effect.IO

// Scenario: Don't allow more than 5 requests per user per 5 seconds
val host: String = ???
val port: Int = ???
implicit val redisClient: RedisClient = new RedisClient(host, port)
val userId: String = ???
val now = ZonedDateTime.now.toEpochSecond

val effectToBeRateLimited: IO[String] = ???

val result: IO[String] = rateLimited[IO, String](key = userId, maxTokens = 5,
    timeWindowInSec = 5, nowInEpochSec = now)(effectToBeRateLimited)

result.handleErrorWith {
    case RedisConnectionError(msg) => ??? 
    case RateLimitExceeded => ???
}
```

