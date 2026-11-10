package vn.nhom11.bittorrent.peer;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.*;
import org.springframework.web.server.ResponseStatusException;
import vn.nhom11.bittorrent.shared.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/node")
public class NodeController {
    public record NodeInfo(String peerId, String username, int tcpPort, int webPort, String trackerUrl,
                           boolean authenticated, HttpTrackerClient.Health heartbeat) {}
    public record Login(String username, String password, boolean register) {}
    public record SessionInfo(NodeInfo node, String csrfToken) {}
    public record DownloadRequest(String fileId) {}
    public record PeerView(String peerId, String username, String ipAddress, int tcpPort, Set<Integer> availableChunks,
                           java.time.Instant lastHeartbeat, String titForTatStatus) {}
    private final PeerRuntime runtime;
    private final PeerProperties properties;
    private final HttpTrackerClient tracker;
    private final ChunkStore store;
    private final NodeEventHub events;
    private final NodeDashboardService downloads;
    private final TitForTatManager policy;
    public NodeController(PeerRuntime runtime, PeerProperties properties, HttpTrackerClient tracker, ChunkStore store,
                          NodeEventHub events, NodeDashboardService downloads, TitForTatManager policy) {
        this.runtime = runtime; this.properties = properties; this.tracker = tracker; this.store = store;
        this.events = events; this.downloads = downloads; this.policy = policy;
    }
    @GetMapping("/status")
    public NodeInfo status(HttpServletRequest request) {
        var identity = runtime.identity();
        return new NodeInfo(identity.peerId(), identity.username(), identity.tcpPort(), request.getServerPort(),
                properties.trackerUrl(), runtime.authenticated(), tracker.health());
    }
    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionInfo login(@RequestBody Login login, HttpServletRequest request) throws IOException {
        if (login.username() == null || !login.username().matches("[a-zA-Z0-9_]{3,40}") || login.password() == null
                || login.password().isBlank()) throw new IllegalArgumentException("Username and password are required");
        if (downloads.active()) throw new IllegalStateException("Wait for the active download before logging in again");
        runtime.login(login.username(), login.password(), login.register());
        HttpSession old = request.getSession(false); if (old != null) old.invalidate();
        HttpSession session = request.getSession(true);
        session.setAttribute(NodeSessionFilter.USER, login.username());
        session.setAttribute(NodeSessionFilter.TOKEN, UUID.randomUUID().toString());
        return session(request);
    }
    @GetMapping("/session")
    public SessionInfo session(HttpServletRequest request) {
        return new SessionInfo(status(request), (String) request.getSession().getAttribute(NodeSessionFilter.TOKEN));
    }
    @DeleteMapping("/session")
    public void logout(HttpServletRequest request) { request.getSession().invalidate(); }

    @GetMapping("/snapshot")
    public NodeEventHub.Snapshot snapshot(HttpServletRequest request) throws IOException {
        List<TorrentMetadata> directory;
        try { directory = tracker.list(); }
        catch (IOException error) { directory = tracker.cachedList(); }
        return events.snapshot(status(request), directory);
    }
    @GetMapping("/torrents")
    public List<TorrentMetadata> torrents() throws IOException { return tracker.list(); }
    @GetMapping("/swarm/{fileId}")
    public List<PeerView> swarm(@PathVariable String fileId) throws IOException {
        var local = store.get(fileId);
        boolean seeding = local != null && local.complete();
        return tracker.swarm(fileId).stream().map(peer -> new PeerView(peer.peerId(), peer.username(), peer.ipAddress(),
                peer.tcpPort(), peer.availableChunks(), peer.lastHeartbeat(), seeding || policy.unchokedPeers().contains(peer.peerId()) ?
                "UNCHOKED" : Objects.equals(policy.optimisticPeer(), peer.peerId()) ? "OPTIMISTIC" : "CHOKED")).toList();
    }
    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TorrentMetadata publish(@RequestPart("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid file name");
        Path directory = Files.createTempDirectory("peer-upload-");
        Path source = directory.resolve(name);
        try {
            file.transferTo(source);
            return runtime.publish(source);
        } finally { Files.deleteIfExists(source); Files.deleteIfExists(directory); }
    }
    @PostMapping("/downloads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NodeEventHub.DownloadView download(@RequestBody DownloadRequest request) throws IOException {
        return downloads.start(request.fileId());
    }
    @DeleteMapping("/downloads/{fileId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void cancel(@PathVariable String fileId) { downloads.cancel(fileId); }
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> file(@PathVariable String fileId) {
        var file = store.get(fileId);
        if (file == null || !file.shareable() || !file.complete()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Verified file not available");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.metadata().fileName(),
                        java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentLength(file.metadata().fileSize()).body(new FileSystemResource(file.path()));
    }
}
