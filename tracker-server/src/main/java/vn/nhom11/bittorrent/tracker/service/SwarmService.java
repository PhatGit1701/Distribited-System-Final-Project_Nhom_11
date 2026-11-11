package vn.nhom11.bittorrent.tracker.service;
import vn.nhom11.bittorrent.tracker.event.SwarmEvents;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;
import vn.nhom11.bittorrent.shared.exception.*;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import vn.nhom11.bittorrent.shared.*;
import java.time.*;
import java.util.*;

@Service
public class SwarmService {
 private final SwarmRepository repository;
 private final Clock clock;
 private final SwarmEvents events;
 private final TransferStatsBuffer stats;
 private final CumulativeTransferAccounting accounting;
 public SwarmService(SwarmRepository repository, Clock clock, SwarmEvents events, TransferStatsBuffer stats,
                     CumulativeTransferAccounting accounting) {
   this.repository=repository; this.clock=clock; this.events=events; this.stats=stats; this.accounting=accounting;
 }
 public static void validateEndpoint(String peerId,String address,int port) {
   if(peerId==null||!peerId.matches("[a-zA-Z0-9_-]{1,128}")||address==null||address.length()>253
       ||!address.matches("[a-zA-Z0-9:.%-]+")||port<1||port>65535)
     throw new IllegalArgumentException("Invalid peerId, ipAddress or tcpPort");
 }
 public PeerNodeInfo heartbeat(HeartbeatRequest r, long authenticatedUserId, String username) {
   validateEndpoint(r.peerId(),r.ipAddress(),r.tcpPort());
   if(r.username()!=null&&!r.username().equals(username)) throw new AppException(ErrorCode.FORBIDDEN, "Username does not match token");
   if(r.fileId()==null||r.fileId().isBlank()) throw new IllegalArgumentException("fileId is required");
   int chunks=repository.chunkCount(r.fileId()).orElseThrow(()->new AppException(ErrorCode.TORRENT_NOT_FOUND, "Torrent not found"));
   if(r.availableChunks()==null||r.availableChunks().stream().anyMatch(i->i<0||i>=chunks))
     throw new IllegalArgumentException("Invalid availableChunks");
   CumulativeTransferAccounting.validate(r);
   if(r.event()!=null&&!Set.of("started","completed","stopped").contains(r.event())) throw new IllegalArgumentException("Invalid lifecycle event");
   if("completed".equals(r.event())&&r.availableChunks().size()!=chunks) throw new IllegalArgumentException("Completed event requires every chunk");
   String userId=Long.toString(authenticatedUserId);
   try {
     SwarmRepository.Peer peer;
     if("stopped".equals(r.event())) {
       var removed=repository.remove(r.fileId(),r.peerId(),userId);
       removed.ifPresent(events::peerLeft);
       peer=removed.orElseGet(()->new SwarmRepository.Peer(r.fileId(),r.peerId(),userId,username,r.ipAddress(),r.tcpPort(),r.availableChunks(),clock.instant()));

     } else {
       peer=repository.register(new SwarmRepository.Peer(r.fileId(),r.peerId(),userId,username,r.ipAddress(),r.tcpPort(),r.availableChunks(),clock.instant()));
     }
     var owners=new HashMap<String,Long>();
     repository.findSources(r.fileId()).forEach(p->owners.put(p.peerId(),Long.parseLong(p.userId())));
     accounting.record(authenticatedUserId,r,owners);
     if("completed".equals(r.event())) stats.completed(authenticatedUserId,r.fileId());
     if("stopped".equals(r.event())) stats.stopped(authenticatedUserId,r.fileId());
     if("started".equals(r.event())) stats.started(authenticatedUserId,r.fileId());
     return toInfo(peer);
   } catch(SecurityException error) {
     throw new AppException(ErrorCode.FORBIDDEN, "Peer belongs to another user");
   }
 }
 public List<PeerNodeInfo> peers(String fileId) {
   if(repository.chunkCount(fileId).isEmpty()) throw new AppException(ErrorCode.TORRENT_NOT_FOUND, "Torrent not found");
   return repository.findSources(fileId).stream().map(SwarmService::toInfo).sorted(Comparator.comparing(PeerNodeInfo::peerId)).toList();
 }
 private static PeerNodeInfo toInfo(SwarmRepository.Peer p) {
   return new PeerNodeInfo(p.peerId(),p.username(),p.ip(),p.port(),p.chunks(),p.lastSeen());
 }
 @Scheduled(fixedDelayString="${tracker.swarm.prune-ms:5000}")
 public void pruneDeadPeers() { repository.removeExpired().forEach(events::peerLeft); }
}
