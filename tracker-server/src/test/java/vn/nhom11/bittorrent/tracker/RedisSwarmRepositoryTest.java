package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.repository.RedisAccountingCheckpoint;
import vn.nhom11.bittorrent.tracker.repository.RedisSwarmRepository;
import vn.nhom11.bittorrent.tracker.service.CumulativeTransferAccounting;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisSwarmRepositoryTest extends InMemorySwarmRepositoryTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    @Override @BeforeEach void setup(){
        connection=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
        try(var c=connection.getConnection()){c.serverCommands().flushDb();}
        repository=vn.nhom11.bittorrent.tracker.repository.RepositoryFixtures.withClock(redis,clock);
    }
    @AfterEach void close(){if(connection!=null)connection.destroy();}
    @Test void accountingCheckpointSurvivesTrackerRestartWithoutCreditingTheSameTransferAgain() {
        var fixture=new CumulativeTransferAccountingTest();
        var checkpoint=new RedisAccountingCheckpoint(redis);
        var first=vn.nhom11.bittorrent.tracker.service.AccountingFixtures.withCheckpoint(fixture.stats,checkpoint);
        var a=fixture.report("A","s",1,0,100,new vn.nhom11.bittorrent.shared.TransferReport("B",0,100));
        var b=fixture.report("B","t",1,100,0,new vn.nhom11.bittorrent.shared.TransferReport("A",100,0));
        first.record(1,a,fixture.owners);first.record(2,b,fixture.owners);
        var restarted=vn.nhom11.bittorrent.tracker.service.AccountingFixtures.withCheckpoint(fixture.stats,checkpoint);
        restarted.record(1,a,fixture.owners);restarted.record(2,b,fixture.owners);
        assertArrayEquals(new long[]{0,100,0,100},fixture.stats.get(1));
        assertArrayEquals(new long[]{100,0,100,0},fixture.stats.get(2));
        restarted.record(2,fixture.report("B","t",2,120,0,new vn.nhom11.bittorrent.shared.TransferReport("A",120,0)),fixture.owners);
        assertEquals(100,fixture.stats.get(2)[2]);
        first.record(1,fixture.report("A","s",2,0,120,new vn.nhom11.bittorrent.shared.TransferReport("B",0,120)),fixture.owners);
        assertEquals(120,fixture.stats.get(2)[2]);
    }
    @Test void independentTrackerInstancesDoNotDoubleCreditConcurrentReports() throws Exception {
        var fixture=new CumulativeTransferAccountingTest();
        var one=vn.nhom11.bittorrent.tracker.service.AccountingFixtures.withCheckpoint(fixture.stats,new RedisAccountingCheckpoint(redis));
        var two=vn.nhom11.bittorrent.tracker.service.AccountingFixtures.withCheckpoint(fixture.stats,new RedisAccountingCheckpoint(redis));
        var request=fixture.report("A","session",1,100,0);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->one.record(1,request,fixture.owners));
            var second=pool.submit(()->two.record(1,request,fixture.owners));
            first.get();second.get();
        }
        assertEquals(100,fixture.stats.get(1)[0]);
    }
    @Test void productionHeartbeatExtendsActualRedisTtl() {
        repository=new RedisSwarmRepository(redis,clock);
        repository.registerMetadata("file",16);
        repository.register(peer("a"));
        redis.expire("chunks:file:a",java.time.Duration.ofSeconds(2));
        redis.expire("lease:file:a",java.time.Duration.ofSeconds(2));
        repository.heartbeat("file","a","owner","localhost",9001).orElseThrow();
        assertTrue(redis.getExpire("chunks:file:a",TimeUnit.MILLISECONDS)>85000);
        assertTrue(redis.getExpire("lease:file:a",TimeUnit.MILLISECONDS)>85000);
        assertEquals(1,repository.findSources("file").size());
    }
    @Test void storesCompactBinaryChunksAndIndependentLeaseTtls(){
        repository.registerMetadata("file",16);repository.register(peer("a"));
        repository.updateChunks("file","a","owner",Set.of(15));
        assertEquals(2L,redis.opsForValue().size("chunks:file:a"));
        assertTrue(redis.getExpire("chunks:file:a",TimeUnit.SECONDS)>0);
        assertTrue(redis.getExpire("lease:file:a",TimeUnit.SECONDS)>0);
        assertEquals(-1L,redis.getExpire("peers:file",TimeUnit.SECONDS));
        assertEquals(16,new RedisSwarmRepository(redis,clock).chunkCount("file").orElseThrow());
    }
    @Test void cleanupKeepsIdentityAfterChunkAndLeaseKeysExpire(){
        repository.registerMetadata("file",16);repository.register(peer("a"));
        redis.delete("chunks:file:a");redis.delete("lease:file:a");clock.advance(90);
        var departed=repository.removeExpired();
        assertEquals(1,departed.size());assertEquals("owner",departed.getFirst().userId());assertEquals("alice",departed.getFirst().username());
    }
}
