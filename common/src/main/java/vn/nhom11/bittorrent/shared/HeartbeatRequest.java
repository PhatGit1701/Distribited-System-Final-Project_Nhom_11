package vn.nhom11.bittorrent.shared;

import java.util.Set;
import java.util.List;

/** Cumulative counters for one peer/file session; sequence rejects stale or repeated reports. */
public record HeartbeatRequest(String peerId, String username, String ipAddress, int tcpPort,
                               String fileId, Set<Integer> availableChunks, String event,
                               long uploaded, long downloaded, String sessionId, long sequence,
                               List<TransferReport> transfers) {
    public HeartbeatRequest(String peerId, String username, String ipAddress, int tcpPort,
                            String fileId, Set<Integer> availableChunks, String event,
                            long uploaded, long downloaded) {
        this(peerId, username, ipAddress, tcpPort, fileId, availableChunks, event, uploaded, downloaded, null, 0, List.of());
    }
    public HeartbeatRequest(String peerId, String username, String ipAddress, int tcpPort,
                            String fileId, Set<Integer> availableChunks) {
        this(peerId, username, ipAddress, tcpPort, fileId, availableChunks, null, 0, 0);
    }
    public HeartbeatRequest {
        if (availableChunks != null) availableChunks = Set.copyOf(availableChunks);
        transfers = transfers == null ? List.of() : List.copyOf(transfers);
    }
    /** Compatibility accessors; values have cumulative semantics. */
    @Deprecated public long uploadedDelta() { return uploaded; }
    @Deprecated public long downloadedDelta() { return downloaded; }
}
