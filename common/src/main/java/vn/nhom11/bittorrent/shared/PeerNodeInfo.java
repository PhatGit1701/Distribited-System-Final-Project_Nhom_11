package vn.nhom11.bittorrent.shared;

import java.time.Instant;
import java.util.Set;

public record PeerNodeInfo(String peerId, String username, String ipAddress, int tcpPort,
                           Set<Integer> availableChunks, Instant lastHeartbeat, Double reputation) {
    public PeerNodeInfo(String peerId,String username,String ipAddress,int tcpPort,Set<Integer> availableChunks,Instant lastHeartbeat) {
        this(peerId,username,ipAddress,tcpPort,availableChunks,lastHeartbeat,null);
    }
    public PeerNodeInfo { availableChunks = Set.copyOf(availableChunks); }
}
