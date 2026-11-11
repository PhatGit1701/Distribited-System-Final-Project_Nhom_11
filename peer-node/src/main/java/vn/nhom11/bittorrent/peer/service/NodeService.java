package vn.nhom11.bittorrent.peer.service;
import vn.nhom11.bittorrent.peer.event.NodeEventHub;
import vn.nhom11.bittorrent.peer.config.PeerProperties;
import vn.nhom11.bittorrent.peer.client.HttpTrackerClient;
import vn.nhom11.bittorrent.peer.repository.ChunkStore;
import org.springframework.stereotype.Service;
import vn.nhom11.bittorrent.peer.dto.*;
import vn.nhom11.bittorrent.shared.*;
import vn.nhom11.bittorrent.shared.exception.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
@Service
public class NodeService {
    private final PeerRuntime runtime;
    private final PeerProperties properties;
    private final HttpTrackerClient tracker;
    private final ChunkStore store;
    private final NodeEventHub events;
    private final NodeDashboardService downloads;
    private final TitForTatManager policy;
    public NodeService(PeerRuntime runtime, PeerProperties properties, HttpTrackerClient tracker, ChunkStore store,
                          NodeEventHub events, NodeDashboardService downloads, TitForTatManager policy) {
        this.runtime = runtime; this.properties = properties; this.tracker = tracker; this.store = store;
        this.events = events; this.downloads = downloads; this.policy = policy;
    }

    public NodeInfo status(int webPort) {
        var identity = runtime.identity();
        return new NodeInfo(identity.peerId(), identity.username(), identity.tcpPort(), webPort,
                properties.trackerUrl(), runtime.authenticated(), tracker.health());
    }
    public void login(Login login) throws IOException {
        if (login.username() == null || !login.username().matches("[a-zA-Z0-9_]{3,40}") || login.password() == null
                || login.password().isBlank()) throw new AppException(ErrorCode.INVALID_REQUEST, "Username and password are required");
        if (downloads.active()) throw new AppException(ErrorCode.DOWNLOAD_CONFLICT, "Wait for the active download before logging in again");
        runtime.login(login.username(), login.password(), login.register());
    }
    public NodeEventHub.Snapshot snapshot(int webPort) throws IOException {
        List<TorrentMetadata> directory;
        try { directory = tracker.list(); }
        catch (IOException error) { directory = tracker.cachedList(); }
        return events.snapshot(status(webPort), directory);
    }
    public List<TorrentMetadata> torrents() throws IOException { return tracker.list(); }
    public List<PeerView> swarm(String fileId) throws IOException {
        var local = store.get(fileId);
        boolean seeding = local != null && local.complete();
        return tracker.swarm(fileId).stream().map(peer -> new PeerView(peer.peerId(), peer.username(), peer.ipAddress(),
                peer.tcpPort(), peer.availableChunks(), peer.lastHeartbeat(), seeding || policy.unchokedPeers().contains(peer.peerId()) ?
                "UNCHOKED" : Objects.equals(policy.optimisticPeer(), peer.peerId()) ? "OPTIMISTIC" : "CHOKED")).toList();
    }
    public TorrentMetadata publish(String name, InputStream content) throws IOException {
        if (name == null || name.isBlank() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.chars().anyMatch(Character::isISOControl)) throw new AppException(ErrorCode.INVALID_REQUEST, "Invalid file name");
        Path directory = Files.createTempDirectory("peer-upload-");
        Path source = directory.resolve(name);
        try {
            Files.copy(content, source);
            return runtime.publish(source);
        } finally { Files.deleteIfExists(source); Files.deleteIfExists(directory); }
    }
    public NodeEventHub.DownloadView download(String fileId) throws IOException {
        return downloads.start(fileId);
    }
    public void cancel(String fileId) { downloads.cancel(fileId); }
    public ChunkStore.StoredFile file(String fileId) {
        var file=store.get(fileId);
        if(file==null || !file.shareable() || !file.complete()) throw new AppException(ErrorCode.FILE_NOT_AVAILABLE);
        return file;
    }
}
