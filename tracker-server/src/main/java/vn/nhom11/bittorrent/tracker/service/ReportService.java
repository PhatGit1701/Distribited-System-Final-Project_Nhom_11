package vn.nhom11.bittorrent.tracker.service;
import vn.nhom11.bittorrent.tracker.dto.ReportRequest;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;
import vn.nhom11.bittorrent.shared.exception.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.sql.Timestamp;

/** A mismatch claim is a suspicion signal, not proof. Quarantine requires independent reporters. */
@Service
public class ReportService {
    public record ReportResult(int distinctReporters, boolean suspicious, Instant banUntil) {}
    private final JdbcTemplate jdbc;
    private final TorrentRegistry torrents;
    private final SwarmRepository swarms;
    private final Clock clock;
    public ReportService(JdbcTemplate jdbc, TorrentRegistry torrents, SwarmRepository swarms, Clock clock) {
        this.jdbc=jdbc; this.torrents=torrents; this.swarms=swarms; this.clock=clock;
    }

    @Transactional
    public ReportResult report(long reporter, ReportRequest r) {
        if((r.targetUserId()!=null && r.targetUserId()<=0) || r.chunkIndex()==null || r.fileId()==null
                || r.peerId()==null || !r.peerId().matches("[a-zA-Z0-9_-]{1,128}")
                || r.expectedHash()==null || !r.expectedHash().matches("[0-9a-f]{64}")
                || r.actualHash()==null || !r.actualHash().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid report evidence");
        var metadata=torrents.get(r.fileId());
        if(r.chunkIndex()<0 || r.chunkIndex()>=metadata.totalChunks()
                || !metadata.chunkHashes().get(r.chunkIndex()).equals(r.expectedHash())
                || r.expectedHash().equals(r.actualHash())) throw new IllegalArgumentException("Invalid chunk hash mismatch");
        var target=swarms.findSources(r.fileId()).stream().filter(p -> p.peerId().equals(r.peerId())
                && p.chunks().contains(r.chunkIndex())).findFirst()
                .orElseThrow(()->new AppException(ErrorCode.NOT_FOUND, "Target peer is not advertising this chunk"));
        long targetUserId=Long.parseLong(target.userId());
        if(r.targetUserId()!=null && r.targetUserId()!=targetUserId) throw new IllegalArgumentException("Target account does not own this peer");
        if(reporter==targetUserId) throw new IllegalArgumentException("Cannot report your own account");
        // One transaction-scoped lock per account/peer serializes duplicates and threshold transitions.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {}, targetUserId+":"+r.peerId());
        var allowed=jdbc.queryForObject("SELECT count(*) FROM users WHERE id=? AND NOT is_banned",Integer.class,reporter);
        if(allowed==null || allowed!=1) throw new AppException(ErrorCode.FORBIDDEN, "Reporter is not eligible");
        Instant now=clock.instant(), cutoff=now.minusSeconds(600);
        int duplicates=jdbc.queryForObject("SELECT count(*) FROM peer_reports WHERE target_user_id=? AND peer_id=? AND reporter_id=? AND created_at>?",
                Integer.class,targetUserId,r.peerId(),reporter,Timestamp.from(cutoff));
        if(duplicates>0) throw new AppException(ErrorCode.CONFLICT, "Already reported this peer within the report window");
        jdbc.update("""
                INSERT INTO peer_reports(reporter_id,file_id,peer_id,reason,created_at,target_user_id,chunk_index,expected_hash,actual_hash)
                VALUES(?,?,?,'Chunk hash mismatch',?,?,?,?,?)
                """, reporter,r.fileId(),r.peerId(),Timestamp.from(now),targetUserId,r.chunkIndex(),r.expectedHash(),r.actualHash());
        int distinct=jdbc.queryForObject("SELECT count(DISTINCT reporter_id) FROM peer_reports WHERE target_user_id=? AND peer_id=? AND created_at>?",
                Integer.class,targetUserId,r.peerId(),Timestamp.from(cutoff));
        jdbc.update("""
                INSERT INTO peer_reputation(user_id,peer_id,suspicion_until,ban_until) VALUES(?,?,?,?)
                ON CONFLICT(user_id,peer_id) DO UPDATE SET suspicion_until=EXCLUDED.suspicion_until,
                ban_until=CASE WHEN peer_reputation.ban_until > ? THEN peer_reputation.ban_until
                               ELSE EXCLUDED.ban_until END
                """,targetUserId,r.peerId(),Timestamp.from(now.plusSeconds(600)),
                distinct>3 ? Timestamp.from(now.plusSeconds(600)) : null,Timestamp.from(now));
        var until=jdbc.queryForObject("SELECT ban_until FROM peer_reputation WHERE user_id=? AND peer_id=?",Timestamp.class,targetUserId,r.peerId());
        return new ReportResult(distinct,true,until==null ? null : until.toInstant());
    }
}
