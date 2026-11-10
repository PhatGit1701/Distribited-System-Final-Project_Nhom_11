package vn.nhom11.bittorrent.peer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import vn.nhom11.bittorrent.shared.TorrentMetadata;
import java.time.Instant;
import java.util.*;

/** Serializes event publication and snapshots so reconnect never rolls chunk state backwards. */
@Component
public class NodeEventHub {
    public record DownloadView(String fileId, TorrentMetadata metadata, String status, Instant startedAt,
                               Instant finishedAt, String error, List<ChunkDownloadState> chunks) {}
    public record NodeEvent(long sequence, Instant time, String type, String fileId,
                            ChunkDownloadState chunk, DownloadView download, String message) {}
    public record Snapshot(long sequence, Object node, List<TorrentMetadata> torrents,
                           List<DownloadView> downloads, List<NodeEvent> events) {}
    private static final class Entry {
        final TorrentMetadata metadata;
        final Instant started = Instant.now();
        final ChunkDownloadState[] chunks;
        String status = "QUEUED", error;
        Instant finished;
        Entry(TorrentMetadata metadata) {
            this.metadata = metadata;
            chunks = new ChunkDownloadState[metadata.totalChunks()];
            for (int i = 0; i < chunks.length; i++)
                chunks[i] = new ChunkDownloadState(i, ChunkDownloadState.Status.PENDING, null, null, null,
                        0, metadata.chunkHashes().get(i), null, 0, List.of());
        }
        DownloadView view() {
            return new DownloadView(metadata.fileId(), metadata, status, started, finished, error, List.of(chunks.clone()));
        }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Deque<NodeEvent> history = new ArrayDeque<>();
    private final ObjectProvider<SimpMessagingTemplate> messaging;
    private long sequence;

    public NodeEventHub(ObjectProvider<SimpMessagingTemplate> messaging) { this.messaging = messaging; }
    public synchronized DownloadView begin(TorrentMetadata metadata) {
        Entry entry = new Entry(metadata);
        entries.put(metadata.fileId(), entry);
        emit("DOWNLOAD", metadata.fileId(), null, entry.view(), "Queued " + metadata.fileName());
        return entry.view();
    }
    public synchronized void status(String fileId, String status, String error) {
        Entry entry = entries.get(fileId);
        if (entry == null) return;
        entry.status = status; entry.error = error;
        if (Set.of("COMPLETED", "CANCELLED", "FAILED", "SEEDING").contains(status)) entry.finished = Instant.now();
        emit("DOWNLOAD", fileId, null, entry.view(), status + " " + entry.metadata.fileName() + (error == null ? "" : ": " + error));
    }
    public synchronized void chunk(String fileId, ChunkDownloadState chunk) {
        Entry entry = entries.get(fileId);
        if (entry == null || chunk.chunkIndex() < 0 || chunk.chunkIndex() >= entry.chunks.length) return;
        entry.chunks[chunk.chunkIndex()] = chunk;
        emit("CHUNK", fileId, chunk, null, "#" + chunk.chunkIndex() + " " + chunk.status() + " from "
                + chunk.sourceUsername() + "@" + chunk.sourceEndpoint());
    }
    public synchronized void seeded(ChunkStore.StoredFile file, PeerIdentity identity) {
        Entry entry = new Entry(file.metadata());
        entry.status = "SEEDING"; entry.finished = Instant.now();
        for (int i = 0; i < entry.chunks.length; i++)
            entry.chunks[i] = new ChunkDownloadState(i, ChunkDownloadState.Status.COMPLETED, identity.peerId(),
                    identity.username(), identity.ipAddress() + ":" + identity.tcpPort(), 0,
                    entry.metadata.chunkHashes().get(i), entry.metadata.chunkHashes().get(i), 0, List.of());
        entries.put(file.metadata().fileId(), entry);
        emit("DOWNLOAD", file.metadata().fileId(), null, entry.view(), "Seeding " + file.metadata().fileName());
    }
    public synchronized void log(String type, String message) { emit(type, null, null, null, message); }
    public synchronized Snapshot snapshot(Object node, List<TorrentMetadata> torrents) {
        return new Snapshot(sequence, node, torrents, entries.values().stream().map(Entry::view).toList(), List.copyOf(history));
    }
    public synchronized DownloadView view(String fileId) {
        Entry entry = entries.get(fileId); return entry == null ? null : entry.view();
    }
    private void emit(String type, String fileId, ChunkDownloadState chunk, DownloadView download, String message) {
        NodeEvent event = new NodeEvent(++sequence, Instant.now(), type, fileId, chunk, download, message);
        history.addLast(new NodeEvent(sequence, event.time(), type, fileId, null, null, message));
        while (history.size() > 400) history.removeFirst();
        var template = messaging.getIfAvailable();
        if (template != null) template.convertAndSend("/topic/node-events", event);
    }
}
