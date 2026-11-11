package vn.nhom11.bittorrent.peer.model;

import java.util.List;

/** Immutable event/snapshot: safe for a later WebSocket subscriber. */
public record ChunkDownloadState(int chunkIndex, Status status, String sourcePeerId, String sourceUsername,
                                 String sourceEndpoint, int attemptCount, String expectedHash, String sha256Hash,
                                 long downloadDurationMs, List<String> failoverHistory) {
    public enum Status { PENDING, DOWNLOADING, VERIFYING, COMPLETED, FAILED_RETRYING }
    public ChunkDownloadState { failoverHistory = List.copyOf(failoverHistory); }
}
