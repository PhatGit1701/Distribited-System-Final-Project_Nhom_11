package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.repository.InMemorySwarmRepository;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;
import vn.nhom11.bittorrent.tracker.repository.SourceReputationRepository;
import vn.nhom11.bittorrent.tracker.service.SourceSelectionService;

import org.junit.jupiter.api.Test;
import vn.nhom11.bittorrent.shared.exception.AppException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceSelectionServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);
    private final SwarmRepository swarms = mock(SwarmRepository.class);
    private final SourceReputationRepository reputation = mock(SourceReputationRepository.class);
    private final Map<SourceReputationRepository.PeerKey, SourceReputationRepository.Reputation> policy = new HashMap<>();
    private final List<SwarmRepository.Peer> peers = new ArrayList<>();
    private final SourceSelectionService service = new SourceSelectionService(swarms, reputation, 3, new Random(42));
    private void setup(int total) {
        when(swarms.chunkCount("file")).thenReturn(OptionalInt.of(total));
        when(swarms.findSources("file")).thenReturn(peers);
        when(reputation.load(anyCollection())).thenReturn(policy);
    }
    private void peer(String id, Set<Integer> chunks, boolean userBan, boolean peerBan, int bad, double ratio) {
        peers.add(new SwarmRepository.Peer("file", id, "1", "user", "127.0.0.1", 6881, chunks, clock.instant()));
        policy.put(new SourceReputationRepository.PeerKey("1", id), new SourceReputationRepository.Reputation(userBan, peerBan, bad, ratio));
    }
    @Test void rarestFirstUsesFullEligibleCountBeforeCapAndZeroLast() {
        setup(5);
        peer("one", Set.of(0,1,2),false,false,3,1);
        peer("two", Set.of(0,1),false,false,0,1);
        peer("three", Set.of(0),false,false,0,1);
        var plan = service.select("file","requester",Set.of(3),1,1);
        assertEquals(List.of(2,1,0,4), plan.sources().stream().map(c -> c.chunkIndex()).toList());
        assertEquals(List.of(1,2,3,0), plan.sources().stream().map(c -> c.availability()).toList());
        assertEquals(List.of(1,1,1,0), plan.sources().stream().map(c -> c.peers().size()).toList());
        plan.sources().forEach(c -> c.peers().forEach(p -> assertEquals(Set.of(c.chunkIndex()),p.availableChunks())));
        plan.sources().forEach(c -> c.peers().forEach(p -> assertEquals(50.0,p.reputation())));
        assertEquals(3,plan.leechers());
        assertEquals(30,plan.heartbeatIntervalSeconds());
    }
    @Test void filtersSelfUserBanPeerBanExcessiveBadChunksAndUnknownUser() {
        setup(1);
        peer("self",Set.of(0),false,false,0,1);
        peer("userBan",Set.of(0),true,false,0,1);
        peer("peerBan",Set.of(0),false,true,0,1);
        peer("bad",Set.of(0),false,false,4,1);
        peer("unknown",Set.of(0),false,false,0,1);
        policy.remove(new SourceReputationRepository.PeerKey("1","unknown"));
        peer("boundary",Set.of(0),false,false,3,1);
        var plan=service.select("file","self",Set.of(),16,1);
        assertEquals(List.of("boundary"),plan.sources().getFirst().peers().stream().map(p -> p.peerId()).toList());
        assertEquals(1,plan.seeders());
    }
    @Test void validatesOwnershipCapsAndChunkBounds() {
        setup(2); peer("owned",Set.of(0),false,false,0,1);
        assertThrows(AppException.class,()->service.select("file","owned",Set.of(),1,2));
        for(int cap : List.of(0,17)) assertThrows(IllegalArgumentException.class,()->service.select("file","new",Set.of(),cap,1));
        for(int chunk : List.of(-1,2)) assertThrows(IllegalArgumentException.class,()->service.select("file","new",Set.of(chunk),1,1));
        assertThrows(IllegalArgumentException.class,()->service.select("file","new",null,1,1));
        assertThrows(IllegalArgumentException.class,()->service.select("file","new",new HashSet<>(Arrays.asList((Integer)null)),1,1));
    }
    @Test void expiredPeerIsNotReturnedWithoutPruning() {
        var movingClock=mock(Clock.class);
        when(movingClock.instant()).thenReturn(clock.instant());
        var actual=new InMemorySwarmRepository(movingClock);
        actual.registerMetadata("file",1);
        peer("expired",Set.of(0),false,false,0,1);
        actual.register(peers.getFirst());
        when(movingClock.instant()).thenReturn(clock.instant().plusSeconds(90));
        when(reputation.load(anyCollection())).thenReturn(policy);
        var result=new SourceSelectionService(actual,reputation,3,new Random(1)).select("file","new",Set.of(),5,1);
        assertEquals(0,result.sources().getFirst().availability());
    }
    @Test void equalRatiosAreFairAndHigherRatioIsFavoredWithoutDuplicates() {
        setup(1);
        peer("a",Set.of(0),false,false,0,1); peer("b",Set.of(0),false,false,0,1);
        int a=0;
        for(int i=0;i<4000;i++) if(service.select("file","new",Set.of(),1,1).sources().getFirst().peers().getFirst().peerId().equals("a")) a++;
        assertTrue(a>1800 && a<2200,"equal ratios: "+a);
        policy.put(new SourceReputationRepository.PeerKey("1","b"),new SourceReputationRepository.Reputation(false,false,0,9));
        int b=0;
        for(int i=0;i<4000;i++) if(service.select("file","new",Set.of(),1,1).sources().getFirst().peers().getFirst().peerId().equals("b")) b++;
        assertTrue(b>2500 && b<2800,"ratio bias: "+b);
        assertEquals(2,service.select("file","new",Set.of(),16,1).sources().getFirst().peers().stream().map(p->p.peerId()).distinct().count());
    }
}
