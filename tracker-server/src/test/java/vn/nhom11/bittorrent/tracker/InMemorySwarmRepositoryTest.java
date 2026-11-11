package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.repository.InMemorySwarmRepository;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;

import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class InMemorySwarmRepositoryTest {
    static class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
        void advance(int seconds){now=now.plusSeconds(seconds);}
    }
    protected final MutableClock clock = new MutableClock();
    protected SwarmRepository repository;
    @BeforeEach void setup(){repository=new InMemorySwarmRepository(clock);}
    SwarmRepository.Peer peer(String id){return new SwarmRepository.Peer("file",id,"owner","alice","127.0.0.1",9000,Set.of(0),clock.instant());}
    @Test void expiresAtNinetySecondsAndReportsDepartureExactlyOnce(){
        repository.registerMetadata("file",4);repository.register(peer("a"));clock.advance(90);
        assertTrue(repository.findSources("file").isEmpty());
        assertEquals(1,repository.removeExpired().size());assertTrue(repository.removeExpired().isEmpty());
    }
    @Test void heartbeatRenewsOnlyItsOwnLease(){
        repository.registerMetadata("file",4);repository.register(peer("a"));repository.register(peer("b"));
        clock.advance(80);repository.heartbeat("file","a","owner","localhost",9001);clock.advance(11);
        assertEquals(List.of("a"),repository.findSources("file").stream().map(SwarmRepository.Peer::peerId).toList());
        assertEquals("b",repository.removeExpired().getFirst().peerId());
    }
    @Test void ownershipCannotBeChanged(){
        repository.registerMetadata("file",4);repository.register(peer("a"));
        assertThrows(SecurityException.class,()->repository.heartbeat("file","a","other","localhost",9001));
        assertThrows(SecurityException.class,()->repository.updateChunks("file","a","other",Set.of(1)));
        assertThrows(SecurityException.class,()->repository.remove("file","a","other"));
    }
    @Test void concurrentChunkAdditionsNeverLoseBits() throws Exception {
        repository.registerMetadata("file",64);repository.register(peer("a"));
        try(var pool=Executors.newFixedThreadPool(12)){
            var futures=new ArrayList<Future<?>>();
            for(int i=1;i<64;i++){int chunk=i;futures.add(pool.submit(()->repository.updateChunks("file","a","owner",Set.of(chunk))));}
            for(var future:futures)future.get();
        }
        assertEquals(64,repository.findSources("file").getFirst().chunks().size());
        assertEquals(new SwarmRepository.Counts(1,0),repository.countSeedersLeechers("file"));
    }
}
