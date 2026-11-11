package vn.nhom11.bittorrent.tracker.repository;
import vn.nhom11.bittorrent.tracker.dto.UserReputation;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Reads persisted policy and aggregate contribution once per source request, never on heartbeat. */
@Repository
public class SourceReputationRepository {
    public record PeerKey(String userId, String peerId) {}
    public record Reputation(boolean userBanned, boolean peerBanned, long badChunkCount, double ratio) {}
    private final NamedParameterJdbcTemplate jdbc;
    public SourceReputationRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<PeerKey, Reputation> load(Collection<SwarmRepository.Peer> peers) {
        if (peers.isEmpty()) return Map.of();
        var ids = peers.stream().map(p -> Long.parseLong(p.userId())).distinct().toList();
        var params = Map.of("ids", ids);
        var users = new HashMap<String, Reputation>();
        jdbc.query("""
                SELECT u.id, u.is_banned, COALESCE(SUM(s.verified_uploaded),0) uploaded,
                       COALESCE(SUM(s.verified_downloaded),0) downloaded
                FROM users u LEFT JOIN user_file_stats s ON s.user_id=u.id
                WHERE u.id IN (:ids) GROUP BY u.id,u.is_banned
                """, params, rs -> {
            double ratio = rs.getDouble("uploaded") / Math.max(1.0, rs.getDouble("downloaded"));
            users.put(rs.getString("id"), new Reputation(rs.getBoolean("is_banned"), false, 0, ratio));
        });
        var result = new HashMap<PeerKey, Reputation>();
        for (var peer : peers) {
            var reputation = users.get(peer.userId());
            if (reputation != null) result.put(new PeerKey(peer.userId(), peer.peerId()), reputation);
        }
        jdbc.query("SELECT user_id,peer_id,(is_banned OR ban_until > CURRENT_TIMESTAMP) AS is_banned,bad_chunk_count FROM peer_reputation WHERE user_id IN (:ids)", params, rs -> {
            var key = new PeerKey(rs.getString("user_id"), rs.getString("peer_id"));
            var user = users.get(key.userId());
            if (user != null && result.containsKey(key))
                result.put(key, new Reputation(user.userBanned(), rs.getBoolean("is_banned"), rs.getLong("bad_chunk_count"), user.ratio()));
        });
        return result;
    }

    public Optional<UserReputation> user(long id) {
        var rows = jdbc.query("""
                SELECT u.id, COALESCE(SUM(s.verified_uploaded),0) uploaded,
                       COALESCE(SUM(s.verified_downloaded),0) downloaded
                FROM users u LEFT JOIN user_file_stats s ON s.user_id=u.id
                WHERE u.id=:id GROUP BY u.id
                """, Map.of("id",id), (rs, row) -> {
            long uploaded=rs.getLong("uploaded"), downloaded=rs.getLong("downloaded");
            double ratio=(double)uploaded/Math.max(1L,downloaded);
            return new UserReputation(id,uploaded,downloaded,ratio,score(ratio));
        });
        return rows.stream().findFirst();
    }

    public static double score(double ratio) { return 100.0 * (ratio / (1.0 + ratio)); }
}
