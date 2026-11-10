package vn.nhom11.bittorrent.peer;

import vn.nhom11.bittorrent.shared.*;
import java.io.IOException;
import java.util.List;

public interface TrackerGateway {
    TorrentMetadata metadata(String fileId) throws IOException;
    List<PeerNodeInfo> swarm(String fileId) throws IOException;
    void heartbeat(HeartbeatRequest request) throws IOException;
    void register(TorrentMetadata metadata, PeerIdentity identity) throws IOException;
}
