package vn.nhom11.bittorrent.tracker.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

/** Persistent per-file compare-and-set checkpoints; deliberately no lease TTL. */
@Component
@Profile("!dev & !test")
public class RedisAccountingCheckpoint {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> CAS=new DefaultRedisScript<>("""
        local old=redis.call('GET',KEYS[1]) or ''
        if old~=ARGV[1] then return 0 end
        redis.call('SET',KEYS[1],ARGV[2])
        return 1
        """,Long.class);
    public RedisAccountingCheckpoint(StringRedisTemplate redis){this.redis=redis;}
    public String read(String file){return redis.opsForValue().get("accounting:"+file);}
    public boolean save(String file,String before,String after){return Long.valueOf(1).equals(redis.execute(CAS,
            List.of("accounting:"+file),before==null?"":before,after));}
}
