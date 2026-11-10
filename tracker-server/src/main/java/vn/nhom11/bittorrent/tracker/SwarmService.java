package vn.nhom11.bittorrent.tracker;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.nhom11.bittorrent.shared.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SwarmService {
 private final ConcurrentHashMap<String,ConcurrentHashMap<String,PeerNodeInfo>> swarms=new ConcurrentHashMap<>();
 private final Clock clock;
 public SwarmService(Clock clock){this.clock=clock;}
 public static void validateEndpoint(String peerId,String address,int port) {
   if(peerId==null||!peerId.matches("[a-zA-Z0-9_-]{1,128}")||address==null||address.length()>253
       ||!address.matches("[a-zA-Z0-9:.%-]+")||port<1||port>65535)
     throw new IllegalArgumentException("Invalid peerId, ipAddress or tcpPort");
 }
 public PeerNodeInfo heartbeat(TorrentMetadata metadata,HeartbeatRequest r,String username) {
   validateEndpoint(r.peerId(),r.ipAddress(),r.tcpPort());
   if(r.username()!=null&&!r.username().equals(username)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Username does not match token");
   if(r.availableChunks()==null||r.availableChunks().stream().anyMatch(i->i<0||i>=metadata.totalChunks()))
     throw new IllegalArgumentException("Invalid availableChunks");
   return swarms.computeIfAbsent(metadata.fileId(),k->new ConcurrentHashMap<>()).compute(r.peerId(),(id,old)->{
     if(old!=null&&!old.username().equals(username)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Peer belongs to another user");
     return new PeerNodeInfo(id,username,r.ipAddress(),r.tcpPort(),r.availableChunks(),clock.instant());
   });
 }
 public List<PeerNodeInfo> peers(String fileId) {
   var swarm=swarms.get(fileId);
   if(swarm==null)return List.of();
   Instant now=clock.instant();
   return swarm.values().stream().filter(p->!expired(p,now)).sorted(Comparator.comparing(PeerNodeInfo::peerId)).toList();
 }
 private boolean expired(PeerNodeInfo peer,Instant now){return Duration.between(peer.lastHeartbeat(),now).getSeconds()>20;}
 @Scheduled(fixedRate=5000)
 public void pruneDeadPeers() {
   Instant now=clock.instant();
   swarms.values().forEach(swarm->swarm.forEach((id,peer)->{
     if(expired(peer,now))swarm.remove(id,peer);
   }));
 }
}
