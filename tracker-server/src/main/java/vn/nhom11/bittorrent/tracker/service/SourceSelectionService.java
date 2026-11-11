package vn.nhom11.bittorrent.tracker.service;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;
import vn.nhom11.bittorrent.tracker.repository.SourceReputationRepository;
import vn.nhom11.bittorrent.shared.exception.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vn.nhom11.bittorrent.shared.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

@Service
public class SourceSelectionService {
    private final SwarmRepository swarms;
    private final SourceReputationRepository reputations;
    private final int badChunkThreshold;
    private final RandomGenerator random;

    @Autowired
    public SourceSelectionService(SwarmRepository swarms, SourceReputationRepository reputations,
            @Value("${tracker.sources.bad-chunk-threshold:3}") int badChunkThreshold) {
        this(swarms, reputations, badChunkThreshold, ThreadLocalRandom.current());
    }
    public SourceSelectionService(SwarmRepository swarms, SourceReputationRepository reputations,
            int badChunkThreshold, RandomGenerator random) {
        if (badChunkThreshold < 0) throw new IllegalArgumentException("Invalid bad chunk threshold");
        this.swarms = swarms; this.reputations = reputations;
        this.badChunkThreshold = badChunkThreshold; this.random = random;
    }

    public SourcePlan select(String fileId, String peerId, Set<Integer> availableChunks, int maxSources, long userId) {
        if (peerId == null || !peerId.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid peerId");
        if (maxSources < 1 || maxSources > 16) throw new IllegalArgumentException("maxSources must be between 1 and 16");
        int total = swarms.chunkCount(fileId).orElseThrow(() -> new AppException(ErrorCode.TORRENT_NOT_FOUND, "Torrent not found"));
        if (availableChunks == null || availableChunks.stream().anyMatch(i -> i == null || i < 0 || i >= total))
            throw new IllegalArgumentException("Invalid availableChunks");
        var live = swarms.findSources(fileId);
        for (var peer : live) {
            if (peer.peerId().equals(peerId) && !peer.userId().equals(Long.toString(userId)))
                throw new AppException(ErrorCode.FORBIDDEN, "Peer belongs to another user");
        }
        var policy = reputations.load(live);
        var eligible = live.stream().filter(p -> !p.peerId().equals(peerId)).filter(p -> {
            var r = policy.get(new SourceReputationRepository.PeerKey(p.userId(), p.peerId()));
            return r != null && !r.userBanned() && !r.peerBanned() && r.badChunkCount() <= badChunkThreshold;
        }).toList();
        var byChunk = new HashMap<Integer, List<SwarmRepository.Peer>>();
        for (var peer : eligible) for (int chunk : peer.chunks()) {
            if (chunk >= 0 && chunk < total && !availableChunks.contains(chunk))
                byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(peer);
        }
        var result = new ArrayList<SourcePlan.ChunkSources>();
        for (int chunk = 0; chunk < total; chunk++) if (!availableChunks.contains(chunk)) {
            var candidates = byChunk.getOrDefault(chunk, List.of());
            result.add(new SourcePlan.ChunkSources(chunk, candidates.size(), sample(candidates, policy, maxSources, chunk)));
        }
        result.sort(Comparator.comparingInt((SourcePlan.ChunkSources c) -> c.availability() == 0 ? Integer.MAX_VALUE : c.availability())
                .thenComparingInt(SourcePlan.ChunkSources::chunkIndex));
        int seeders = (int) eligible.stream().filter(p -> p.chunks().size() == total).count();
        return new SourcePlan(fileId, result, seeders, eligible.size() - seeders, 30);
    }

    /** Weighted draws without replacement. A neutral peer has weight 1, contribution increases its chance. */
    private List<PeerNodeInfo> sample(List<SwarmRepository.Peer> peers,
            Map<SourceReputationRepository.PeerKey, SourceReputationRepository.Reputation> policy, int limit, int chunk) {
        var remaining = new ArrayList<>(peers);
        var selected = new ArrayList<PeerNodeInfo>();
        while (!remaining.isEmpty() && selected.size() < limit) {
            double sum = remaining.stream().mapToDouble(p -> weight(p, policy)).sum();
            double ticket = random.nextDouble() * sum;
            int chosen = remaining.size() - 1;
            for (int i = 0; i < remaining.size(); i++) {
                ticket -= weight(remaining.get(i), policy);
                if (ticket < 0) { chosen = i; break; }
            }
            var p = remaining.remove(chosen);
            // Availability is scoped to this entry to keep response size linear in requested chunks.
            double ratio=policy.get(new SourceReputationRepository.PeerKey(p.userId(),p.peerId())).ratio();
            selected.add(new PeerNodeInfo(p.peerId(), p.username(), p.ip(), p.port(), Set.of(chunk), p.lastSeen(),
                    SourceReputationRepository.score(ratio)));
        }
        return selected;
    }
    private double weight(SwarmRepository.Peer p, Map<SourceReputationRepository.PeerKey, SourceReputationRepository.Reputation> policy) {
        return 1.0 + Math.log1p(policy.get(new SourceReputationRepository.PeerKey(p.userId(), p.peerId())).ratio());
    }
}
