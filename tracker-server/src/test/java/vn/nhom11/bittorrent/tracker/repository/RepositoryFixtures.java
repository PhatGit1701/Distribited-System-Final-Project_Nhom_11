package vn.nhom11.bittorrent.tracker.repository;

import java.time.Clock;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RepositoryFixtures {
    private RepositoryFixtures() {}
    public static RedisSwarmRepository withClock(StringRedisTemplate redis, Clock clock) {
        return new RedisSwarmRepository(redis, clock, false);
    }
}
