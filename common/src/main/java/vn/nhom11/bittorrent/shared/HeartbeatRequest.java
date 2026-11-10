package vn.nhom11.bittorrent.shared;

import java.util.Set;

public record HeartbeatRequest(String peerId, String username, String ipAddress, int tcpPort,
                               String fileId, Set<Integer> availableChunks) {
    public HeartbeatRequest {
        if (availableChunks != null) availableChunks = Set.copyOf(availableChunks);
    }
}
