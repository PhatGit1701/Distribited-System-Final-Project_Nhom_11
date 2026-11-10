package vn.nhom11.bittorrent.tracker;

import org.junit.jupiter.api.Test;
import vn.nhom11.bittorrent.shared.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SwarmServiceTest {
 static class MutableClock extends Clock {
   Instant now=Instant.parse("2026-11-05T00:00:00Z");
   public ZoneId getZone(){return ZoneOffset.UTC;}
   public Clock withZone(ZoneId zone){return this;}
   public Instant instant(){return now;}
 }
 @Test void expiresAfterTwentyWholeSecondsAndPreservesRefreshedHeartbeat() {
   var clock=new MutableClock(); var service=new SwarmService(clock);
   var metadata=new TorrentMetadata(Hashing.fileId("a",1,1),"a",1,1,1,List.of(Hashing.sha256("a")),"http://localhost");
   var request=new HeartbeatRequest("p1","alice","127.0.0.1",6881,metadata.fileId(),Set.of(0));
   service.heartbeat(metadata,request,"alice");
   clock.now=clock.now.plusSeconds(20);service.pruneDeadPeers();
   assertEquals(1,service.peers(metadata.fileId()).size());
   service.heartbeat(metadata,request,"alice");
   clock.now=clock.now.plusSeconds(1);service.pruneDeadPeers();
   assertEquals(1,service.peers(metadata.fileId()).size());
   clock.now=clock.now.plusSeconds(20);service.pruneDeadPeers();
   assertTrue(service.peers(metadata.fileId()).isEmpty());
   service.heartbeat(metadata,request,"alice");
   assertEquals(1,service.peers(metadata.fileId()).size());
 }
 @Test void peerIdentityCannotBeClaimedByAnotherUser() {
   var service=new SwarmService(Clock.systemUTC());
   var m=new TorrentMetadata(Hashing.fileId("a",0,1),"a",0,1,0,List.of(),"http://localhost");
   service.heartbeat(m,new HeartbeatRequest("p1","alice","127.0.0.1",6881,m.fileId(),Set.of()),"alice");
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.heartbeat(m,
       new HeartbeatRequest("p1","bob","127.0.0.1",6881,m.fileId(),Set.of()),"bob"));
 }
}
