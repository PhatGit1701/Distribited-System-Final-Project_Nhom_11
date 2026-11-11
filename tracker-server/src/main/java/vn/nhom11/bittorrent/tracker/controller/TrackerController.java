package vn.nhom11.bittorrent.tracker.controller;
import vn.nhom11.bittorrent.tracker.service.TrackerService;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import vn.nhom11.bittorrent.shared.*;
import java.util.*;

@RestController @RequestMapping("/api/tracker")
public class TrackerController {
 private final TrackerService tracker;
 public TrackerController(TrackerService tracker){this.tracker=tracker;}
 @PostMapping("/torrents") @ResponseStatus(HttpStatus.CREATED)
 public TorrentMetadata register(@RequestBody TorrentMetadata metadata,@RequestParam String peerId,
     @RequestParam String ipAddress,@RequestParam int tcpPort,@AuthenticationPrincipal Jwt jwt) {
   return tracker.register(metadata,peerId,ipAddress,tcpPort,Long.parseLong(jwt.getSubject()),jwt.getClaimAsString("username"));
 }
 @GetMapping("/torrents") public List<TorrentMetadata> list(){return tracker.list();}
 @GetMapping("/torrents/{fileId}") public TorrentMetadata get(@PathVariable String fileId){return tracker.get(fileId);}
 @PostMapping("/heartbeat")
 public PeerNodeInfo heartbeat(@RequestBody HeartbeatRequest request,@AuthenticationPrincipal Jwt jwt){
   return tracker.heartbeat(request,Long.parseLong(jwt.getSubject()),jwt.getClaimAsString("username"));
 }
 @GetMapping("/swarm/{fileId}")
 public List<PeerNodeInfo> swarm(@PathVariable String fileId){return tracker.swarm(fileId);}
}
