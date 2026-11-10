package vn.nhom11.bittorrent.tracker;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import vn.nhom11.bittorrent.shared.*;
import java.util.*;
import java.util.stream.*;

@RestController @RequestMapping("/api/tracker")
public class TrackerController {
 private final TorrentRegistry registry; private final SwarmService swarms;
 public TrackerController(TorrentRegistry registry,SwarmService swarms){this.registry=registry;this.swarms=swarms;}
 @PostMapping("/torrents") @ResponseStatus(HttpStatus.CREATED)
 public TorrentMetadata register(@RequestBody TorrentMetadata metadata,@RequestParam String peerId,
     @RequestParam String ipAddress,@RequestParam int tcpPort,@AuthenticationPrincipal Jwt jwt) {
   SwarmService.validateEndpoint(peerId,ipAddress,tcpPort);
   // The uploader initially advertises every chunk.
   var chunks=IntStream.range(0,metadata.totalChunks()).boxed().collect(Collectors.toSet());
   var heartbeat=new HeartbeatRequest(peerId,jwt.getClaimAsString("username"),ipAddress,tcpPort,metadata.fileId(),chunks);
   synchronized(registry) {
     // Registry rejects hash collisions before altering the existing swarm.
     registry.register(metadata);
     swarms.heartbeat(metadata,heartbeat,jwt.getClaimAsString("username"));
   }
   return metadata;
 }
 @GetMapping("/torrents") public List<TorrentMetadata> list(){return registry.list();}
 @GetMapping("/torrents/{fileId}") public TorrentMetadata get(@PathVariable String fileId){return registry.get(fileId);}
 @PostMapping("/heartbeat")
 public PeerNodeInfo heartbeat(@RequestBody HeartbeatRequest request,@AuthenticationPrincipal Jwt jwt){
   return swarms.heartbeat(registry.get(request.fileId()),request,jwt.getClaimAsString("username"));
 }
 @GetMapping("/swarm/{fileId}")
 public List<PeerNodeInfo> swarm(@PathVariable String fileId){registry.get(fileId);return swarms.peers(fileId);}
}
