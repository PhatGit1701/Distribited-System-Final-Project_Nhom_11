package vn.nhom11.bittorrent.peer.dto;

import java.util.Set;

public record PeerView(String peerId, String username, String ipAddress, int tcpPort, Set<Integer> availableChunks,
                           java.time.Instant lastHeartbeat, String titForTatStatus) {}
