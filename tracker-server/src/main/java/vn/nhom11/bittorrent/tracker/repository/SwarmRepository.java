package vn.nhom11.bittorrent.tracker.repository;

import java.time.*;
import java.util.*;

/** Runtime swarm state. Ownership is always the immutable authenticated user ID. */
public interface SwarmRepository {
    Duration PEER_TTL = Duration.ofSeconds(90);
    record Peer(String fileId, String peerId, String userId, String username, String ip,
                int port, Set<Integer> chunks, Instant lastSeen) {
        public Peer { chunks = Set.copyOf(chunks); }
    }
    record Counts(int seeders, int leechers) {}
    void registerMetadata(String fileId, int chunkCount);
    OptionalInt chunkCount(String fileId);
    Peer register(Peer peer);
    Optional<Peer> heartbeat(String fileId, String peerId, String userId, String ip, int port);
    Optional<Peer> updateChunks(String fileId, String peerId, String userId, Set<Integer> chunks);
    Optional<Peer> remove(String fileId, String peerId, String userId);
    List<Peer> findSources(String fileId);
    Counts countSeedersLeechers(String fileId);
    List<Peer> removeExpired();
}
