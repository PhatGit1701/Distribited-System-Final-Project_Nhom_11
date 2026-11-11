package vn.nhom11.bittorrent.peer.client;
import vn.nhom11.bittorrent.peer.model.PeerIdentity;

import vn.nhom11.bittorrent.shared.*;
import java.io.IOException;
import java.util.List;

public interface TrackerGateway {
    TorrentMetadata metadata(String fileId) throws IOException;
    List<PeerNodeInfo> swarm(String fileId) throws IOException;
    default SourcePlan sources(String fileId, String peerId, java.util.Set<Integer> availableChunks, int maxSources) throws IOException {
        var metadata = metadata(fileId);
        var peers = swarm(fileId).stream().filter(p -> !p.peerId().equals(peerId)).toList();
        var chunks = new java.util.ArrayList<SourcePlan.ChunkSources>();
        for (int i=0; i<metadata.totalChunks(); i++) {
            if (availableChunks.contains(i)) continue;
            final int index=i;
            var candidates=peers.stream().filter(p -> p.availableChunks().contains(index)).toList();
            chunks.add(new SourcePlan.ChunkSources(i,candidates.size(),candidates.stream().limit(maxSources).toList()));
        }
        chunks.sort(java.util.Comparator.comparingInt((SourcePlan.ChunkSources c) -> c.availability()==0?Integer.MAX_VALUE:c.availability())
                .thenComparingInt(SourcePlan.ChunkSources::chunkIndex));
        return new SourcePlan(fileId,chunks,0,0,5);
    }
    void heartbeat(HeartbeatRequest request) throws IOException;
    void register(TorrentMetadata metadata, PeerIdentity identity) throws IOException;
    default void reportBadChunk(String fileId,String peerId,int chunkIndex,String expectedHash,String actualHash) throws IOException {}
}
