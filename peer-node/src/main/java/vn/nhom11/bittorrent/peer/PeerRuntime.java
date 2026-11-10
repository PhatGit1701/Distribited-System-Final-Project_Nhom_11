package vn.nhom11.bittorrent.peer;

import org.springframework.boot.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.nhom11.bittorrent.shared.*;
import java.io.IOException;
import java.nio.file.Path;

@Component
public class PeerRuntime implements ApplicationRunner {
    private final PeerProperties properties;
    private volatile PeerIdentity identity;
    private final ChunkStore store;
    private final TitForTatManager policy;
    private final HttpTrackerClient tracker;
    private final ParallelDownloadManager downloader;
    private final NodeEventHub events;
    private volatile boolean ready;

    public PeerRuntime(PeerProperties properties, PeerIdentity identity, ChunkStore store,
                       TitForTatManager policy, HttpTrackerClient tracker, ParallelDownloadManager downloader, NodeEventHub events) {
        this.properties = properties; this.identity = identity; this.store = store;
        this.policy = policy; this.tracker = tracker; this.downloader = downloader;
        this.events = events;
    }
    @Override public void run(ApplicationArguments arguments) throws IOException {
        if (!properties.username().isBlank() && (!properties.password().isBlank() || !properties.token().isBlank())) {
            tracker.authenticate(properties.username(), properties.password(), properties.registerUser());
            ready = true;
        }
        if (!ready && (!properties.seedFile().isBlank() || !properties.downloadFileId().isBlank()))
            throw new IOException("Startup sharing requires peer username and password/token");
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                "Peer " + identity.peerId() + " TCP " + identity.ipAddress() + ":" + identity.tcpPort());
        if (!properties.seedFile().isBlank()) publish(Path.of(properties.seedFile()));
        if (!properties.downloadFileId().isBlank()) download(properties.downloadFileId());
    }
    public synchronized TorrentMetadata publish(Path source) throws IOException {
        if (!ready) throw new IOException("Login required");
        var file = store.stageSeed(source, TorrentMetadata.DEFAULT_CHUNK_SIZE, properties.trackerUrl());
        try {
            TorrentGenerator.write(file.metadata(), file.path().resolveSibling("metadata.torrent.json"));
            tracker.register(file.metadata(), identity);
            file.announce();
        } catch (IOException | RuntimeException error) {
            try { store.discardUnpublished(file); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        downloader.advertise(file);
        events.seeded(file, identity);
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, "Published fileId=" + file.metadata().fileId());
        return file.metadata();
    }
    public Path download(String fileId) throws IOException {
        return download(fileId, () -> false);
    }
    public Path download(String fileId, java.util.function.BooleanSupplier cancellation) throws IOException {
        if (!ready) throw new IOException("Login required");
        boolean startup = events.view(fileId) == null;
        if (startup) { events.begin(tracker.metadata(fileId)); events.status(fileId, "DOWNLOADING", null); }
        Path result;
        try { result = downloader.download(fileId, cancellation); }
        catch (IOException error) {
            if (startup) events.status(fileId, cancellation.getAsBoolean() ? "CANCELLED" : "FAILED", error.getMessage());
            throw error;
        }
        if (startup) events.status(fileId, "COMPLETED", null);
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO, "Download completed: " + result);
        return result;
    }
    public synchronized void login(String username, String password, boolean register) throws IOException {
        if (ready && !identity.username().equals(username))
            throw new IllegalStateException("This node belongs to " + identity.username() + "; use another peer for another account");
        tracker.login(username, password, register);
        PeerIdentity updated = new PeerIdentity(identity.peerId(), username, identity.ipAddress(), identity.tcpPort());
        downloader.identity(updated);
        identity = updated;
        ready = true;
        events.log("LOG", "Authenticated as " + username);
    }
    public PeerIdentity identity() { return identity; }
    public boolean authenticated() { return ready; }

    @Scheduled(fixedDelay = 5000)
    public void heartbeat() {
        if (ready) {
            if (store.all().isEmpty()) try { tracker.list(); } catch (IOException ignored) {}
            for (var file : store.all()) downloader.advertise(file);
            events.log("HEARTBEAT", tracker.health().healthy() ? "Tracker connected" : "Tracker heartbeat unavailable");
        }
    }
    @Scheduled(fixedRate = 10000)
    public void updateChoking() {
        policy.recalculate();
        events.log("POLICY", "Unchoked=" + policy.unchokedPeers() + ", optimistic=" + policy.optimisticPeer());
        System.getLogger(getClass().getName()).log(System.Logger.Level.DEBUG,
                "Unchoked=" + policy.unchokedPeers() + ", optimistic=" + policy.optimisticPeer());
    }
}
