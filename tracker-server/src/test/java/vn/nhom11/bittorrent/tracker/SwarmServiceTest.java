package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.event.SwarmEvents;
import vn.nhom11.bittorrent.tracker.repository.InMemorySwarmRepository;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;
import vn.nhom11.bittorrent.tracker.service.SwarmMetadataCache;
import vn.nhom11.bittorrent.tracker.service.TransferStatsBuffer;
import vn.nhom11.bittorrent.tracker.service.CumulativeTransferAccounting;
import vn.nhom11.bittorrent.tracker.service.TorrentRegistry;
import vn.nhom11.bittorrent.tracker.service.SwarmService;
import vn.nhom11.bittorrent.tracker.controller.TrackerController;

import org.junit.jupiter.api.Test;
import vn.nhom11.bittorrent.shared.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SwarmServiceTest {
 static class MutableClock extends Clock {
   Instant now=Instant.parse("2026-11-05T00:00:00Z");
   public ZoneId getZone(){return ZoneOffset.UTC;}
   public Clock withZone(ZoneId zone){return this;}
   public Instant instant(){return now;}
 }
 private static HeartbeatRequest request(Set<Integer> chunks) {return new HeartbeatRequest("p1","alice","127.0.0.1",6881,"file",chunks);}
 @Test void expiresAtNinetySecondsAndEmitsPeerLeftOnlyOnce() {
   var clock=new MutableClock(); var repository=new InMemorySwarmRepository(clock);
   var events=mock(SwarmEvents.class); var stats=mock(TransferStatsBuffer.class);
   var service=new SwarmService(repository,clock,events,stats,new CumulativeTransferAccounting(stats,65536,0.05));repository.registerMetadata("file",1);
   service.heartbeat(request(Set.of(0)),42,"alice");
   clock.now=clock.now.plusSeconds(89);service.pruneDeadPeers();
   assertEquals(1,service.peers("file").size());verifyNoInteractions(events);
   service.heartbeat(request(Set.of(0)),42,"alice");
   clock.now=clock.now.plusSeconds(89);service.pruneDeadPeers();
   assertEquals(1,service.peers("file").size());
   clock.now=clock.now.plusSeconds(1);service.pruneDeadPeers();service.pruneDeadPeers();
   assertTrue(service.peers("file").isEmpty());verify(events,times(1)).peerLeft(any());
 }
 @Test void ownershipUsesAuthenticatedUserIdEvenWithSameUsername() {
   var clock=new MutableClock();var repository=new InMemorySwarmRepository(clock);repository.registerMetadata("file",1);
   var service=new SwarmService(repository,clock,mock(SwarmEvents.class),mock(TransferStatsBuffer.class),mock(CumulativeTransferAccounting.class));
   service.heartbeat(request(Set.of()),42,"alice");
   assertThrows(vn.nhom11.bittorrent.shared.exception.AppException.class,()->service.heartbeat(request(Set.of()),43,"alice"));
 }
 @Test void oldSnapshotsAreAdditiveAndRoutineHeartbeatDoesNotCallJpa() {
   var clock=new MutableClock();var repository=new InMemorySwarmRepository(clock);repository.registerMetadata("file",2);
   var stats=mock(TransferStatsBuffer.class);var service=new SwarmService(repository,clock,mock(SwarmEvents.class),stats,new CumulativeTransferAccounting(stats,65536,0.05));
   // No registry or JPA dependency is supplied: metadata is already in the runtime store.
   service.heartbeat(request(Set.of(0)),42,"alice");service.heartbeat(request(Set.of(1)),42,"alice");
   assertEquals(Set.of(0,1),service.peers("file").getFirst().availableChunks());
   verify(stats,times(2)).record(42,"file",0,0);verifyNoMoreInteractions(stats);
 }
 @Test void metadataCacheLoadsOnceAndRegistrationPushesSubsequentMetadata() {
   var registry=mock(TorrentRegistry.class);var repository=mock(SwarmRepository.class);
   var metadata=new TorrentMetadata(Hashing.fileId("a",1,1),"a",1,1,1,List.of(Hashing.sha256("a")),"http://localhost");
   when(registry.list()).thenReturn(List.of(metadata));var cache=new SwarmMetadataCache(registry,repository);
   cache.run(null);cache.registered(new SwarmMetadataCache.Registered(metadata));
   verify(registry,times(1)).list();verify(repository,times(2)).registerMetadata(metadata.fileId(),1);
 }
 @Test void controllerHeartbeatAndSwarmNeverReadRegistry() {
   var registry=mock(TorrentRegistry.class);var service=mock(SwarmService.class);
   var controller=new TrackerController(new vn.nhom11.bittorrent.tracker.service.TrackerService(registry,service));
   var jwt=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token").header("alg","HS256")
       .subject("42").claim("username","alice").build();
   var heartbeat=request(Set.of(0));controller.heartbeat(heartbeat,jwt);controller.swarm("file");
   verify(service).heartbeat(heartbeat,42,"alice");verify(service).peers("file");verifyNoInteractions(registry);
 }
 @Test void completedAndStoppedFlushStatsAndStopRemovesPeer() {
   var clock=new MutableClock();var repository=new InMemorySwarmRepository(clock);repository.registerMetadata("file",1);
   var stats=mock(TransferStatsBuffer.class);var events=mock(SwarmEvents.class);var service=new SwarmService(repository,clock,events,stats,new CumulativeTransferAccounting(stats,65536,0.05));
   service.heartbeat(new HeartbeatRequest("p1","alice","127.0.0.1",6881,"file",Set.of(0),"completed",3,4),42,"alice");
   verify(stats).record(42,"file",3,4);verify(stats).completed(42,"file");
   service.heartbeat(new HeartbeatRequest("p1","alice","127.0.0.1",6881,"file",Set.of(0),"stopped",0,0),42,"alice");
   assertTrue(service.peers("file").isEmpty());verify(stats).stopped(42,"file");verify(events).peerLeft(any());
 }
}
