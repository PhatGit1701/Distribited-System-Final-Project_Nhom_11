package vn.nhom11.bittorrent.shared;

import java.time.Instant;
import java.util.Set;

public record PeerNodeInfo(String peerId, String username, String ipAddress, int tcpPort,
                           Set<Integer> availableChunks, Instant lastHeartbeat) {
    public PeerNodeInfo { availableChunks = Set.copyOf(availableChunks); }
}
