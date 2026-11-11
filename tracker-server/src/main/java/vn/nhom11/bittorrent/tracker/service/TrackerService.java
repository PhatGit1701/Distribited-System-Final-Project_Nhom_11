package vn.nhom11.bittorrent.tracker.service;
import org.springframework.stereotype.Service;
import vn.nhom11.bittorrent.shared.*;
import java.util.stream.*;

@Service
public class TrackerService {
 private final TorrentRegistry registry;
 private final SwarmService swarms;
 public TrackerService(TorrentRegistry registry, SwarmService swarms) { this.registry=registry; this.swarms=swarms; }
 public TorrentMetadata register(TorrentMetadata metadata, String peerId, String ipAddress, int tcpPort, long userId, String username) {
   SwarmService.validateEndpoint(peerId,ipAddress,tcpPort);
   // The uploader initially advertises every chunk.
   var chunks=IntStream.range(0,metadata.totalChunks()).boxed().collect(Collectors.toSet());
   var heartbeat=new HeartbeatRequest(peerId,username,ipAddress,tcpPort,metadata.fileId(),chunks);
   synchronized(registry) {
     // Registry rejects hash collisions before altering the existing swarm.
     registry.register(metadata,userId);
     swarms.heartbeat(heartbeat,userId,username);
   }
   return metadata;
 }
 public java.util.List<TorrentMetadata> list() { return registry.list(); }
 public TorrentMetadata get(String fileId) { return registry.get(fileId); }
 public PeerNodeInfo heartbeat(HeartbeatRequest request, long userId, String username) { return swarms.heartbeat(request,userId,username); }
 public java.util.List<PeerNodeInfo> swarm(String fileId) { return swarms.peers(fileId); }
}
