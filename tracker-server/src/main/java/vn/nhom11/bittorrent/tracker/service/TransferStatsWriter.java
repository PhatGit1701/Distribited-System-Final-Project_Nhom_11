package vn.nhom11.bittorrent.tracker.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;

/** Atomic additive updates preserve counters even when multiple trackers flush concurrently. */
@Repository
public class TransferStatsWriter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TransferStatsWriter(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc=jdbc;
        this.transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void write(TransferStatsBuffer.Batch batch) {
        transaction.executeWithoutResult(status -> {
            int inserted=jdbc.update("INSERT INTO stats_flush_receipts(batch_id) VALUES(?) ON CONFLICT DO NOTHING",batch.id());
            if(inserted==0) return;
            jdbc.update("""
                    INSERT INTO user_file_stats(user_id,file_id,total_uploaded,total_downloaded,verified_uploaded,verified_downloaded,started_at,completed_at,stopped_at,version)
                    VALUES(?,?,?,?,?,?,?,?,?,0)
                    ON CONFLICT(user_id,file_id) DO UPDATE SET
                      total_uploaded=user_file_stats.total_uploaded+EXCLUDED.total_uploaded,
                      total_downloaded=user_file_stats.total_downloaded+EXCLUDED.total_downloaded,
                      verified_uploaded=user_file_stats.verified_uploaded+EXCLUDED.verified_uploaded,
                      verified_downloaded=user_file_stats.verified_downloaded+EXCLUDED.verified_downloaded,
                      started_at=GREATEST(user_file_stats.started_at,EXCLUDED.started_at),
                      completed_at=GREATEST(user_file_stats.completed_at,EXCLUDED.completed_at),
                      stopped_at=GREATEST(user_file_stats.stopped_at,EXCLUDED.stopped_at),
                      version=user_file_stats.version+1
                    """,batch.key().userId(),batch.key().fileId(),batch.uploaded(),batch.downloaded(),
                    batch.verifiedUploaded(),batch.verifiedDownloaded(),
                    timestamp(batch.startedAt()),timestamp(batch.completedAt()),timestamp(batch.stoppedAt()));
            batch.suspiciousUntil().forEach((peer,until)->jdbc.update("""
                    INSERT INTO peer_reputation(user_id,peer_id,suspicion_until) VALUES(?,?,?)
                    ON CONFLICT(user_id,peer_id) DO UPDATE SET
                      suspicion_until=GREATEST(peer_reputation.suspicion_until,EXCLUDED.suspicion_until)
                    """,batch.key().userId(),peer,timestamp(until)));
        });
    }

    private static Timestamp timestamp(Instant instant) {return instant==null?null:Timestamp.from(instant);}
}
