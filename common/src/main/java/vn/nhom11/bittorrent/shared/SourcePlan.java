package vn.nhom11.bittorrent.shared;

import java.util.List;

public record SourcePlan(String fileId, List<ChunkSources> sources, int seeders, int leechers,
                         int heartbeatIntervalSeconds) {
    public SourcePlan { sources = List.copyOf(sources); }
    public record ChunkSources(int chunkIndex, int availability, List<PeerNodeInfo> peers) {
        public ChunkSources { peers = List.copyOf(peers); }
    }
}
