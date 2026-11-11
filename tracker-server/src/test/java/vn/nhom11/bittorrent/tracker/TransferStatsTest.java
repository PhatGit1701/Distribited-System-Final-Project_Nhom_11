package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.service.TransferStatsWriter;
import vn.nhom11.bittorrent.tracker.service.TransferStatsBuffer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TransferStatsTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    static final String FILE = "a".repeat(64);
    static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    JdbcTemplate jdbc;
    TransferStatsWriter writer;
    TransferStatsBuffer buffer;
    long userId;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("TRUNCATE stats_flush_receipts, user_file_stats, peer_reports, shared_files, users RESTART IDENTITY CASCADE");
        userId = jdbc.queryForObject("INSERT INTO users(username,password_hash,passkey) VALUES('stats','hash',?) RETURNING id", Long.class, "b".repeat(64));
        jdbc.update("INSERT INTO shared_files(file_id,name,size,chunk_size,chunk_count,chunk_hashes,tracker_url,owner_id) VALUES(?,'x',1,1,1,?,'http://localhost',?)", FILE,new byte[32],userId);
        writer = new TransferStatsWriter(jdbc,new DataSourceTransactionManager(ds));
        buffer = new TransferStatsBuffer(writer,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Test void heartbeatBuffersAndSignificantEventsFlushImmediately() {
        buffer.record(userId,FILE,5,9);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_file_stats",Integer.class));
        buffer.started(userId,FILE);
        assertTotals(5,9);
        assertEquals(NOW,jdbc.queryForObject("SELECT started_at FROM user_file_stats",java.sql.Timestamp.class).toInstant());
        buffer.completed(userId,FILE);
        assertTotals(5,9);
        assertEquals(NOW,jdbc.queryForObject("SELECT completed_at FROM user_file_stats",java.sql.Timestamp.class).toInstant());
        buffer.record(userId,FILE,2,3);
        buffer.stopped(userId,FILE);
        assertTotals(7,12);
        assertEquals(NOW,jdbc.queryForObject("SELECT stopped_at FROM user_file_stats",java.sql.Timestamp.class).toInstant());
        assertEquals(2,jdbc.queryForObject("SELECT version FROM user_file_stats",Long.class));
    }

    @Test void failedWritesRemainPendingAndRetriesDoNotDoubleCountCommittedBatch() {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var flaky = new TransferStatsWriter(jdbc,new DataSourceTransactionManager(jdbc.getDataSource())) {
            @Override public void write(TransferStatsBuffer.Batch batch) {
                super.write(batch);
                if(attempts.getAndIncrement()==0) throw new IllegalStateException("Connection lost after commit");
            }
        };
        buffer = new TransferStatsBuffer(flaky,Clock.fixed(NOW,ZoneOffset.UTC));
        buffer.record(userId,FILE,7,11);
        assertThrows(IllegalStateException.class,()->buffer.completed(userId,FILE));
        buffer.record(userId,FILE,3,5);
        buffer.flushPending();
        assertTotals(10,16);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM stats_flush_receipts",Integer.class));
    }

    @Test void rollbackRequeuesBatchAndConcurrentRecordingAndFlushingLoseNothing() throws Exception {
        jdbc.execute("ALTER TABLE user_file_stats ADD CONSTRAINT temporary_failure CHECK(total_uploaded < 1)");
        buffer.record(userId,FILE,1,2);
        buffer.flushPending();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stats_flush_receipts",Integer.class));
        jdbc.execute("ALTER TABLE user_file_stats DROP CONSTRAINT temporary_failure");
        try(var executor=Executors.newFixedThreadPool(6)) {
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<4;i++) jobs.add(executor.submit(()->{for(int j=0;j<250;j++) buffer.record(userId,FILE,1,2);}));
            for(int i=0;i<2;i++) jobs.add(executor.submit(()->{for(int j=0;j<20;j++) buffer.flushPending();}));
            for(var job:jobs) job.get(30,TimeUnit.SECONDS);
        }
        buffer.flushPending();
        assertTotals(1001,2002);
    }

    @Test void independentTrackersAtomicallyIncrementTheSameRow() throws Exception {
        try(var executor=Executors.newFixedThreadPool(4)) {
            List<Future<?>> jobs=new ArrayList<>();
            for(int i=0;i<4;i++) jobs.add(executor.submit(()->{
                var tracker=new TransferStatsBuffer(writer,Clock.fixed(NOW,ZoneOffset.UTC));
                for(int j=0;j<20;j++) {
                    tracker.record(userId,FILE,3,5);
                    tracker.completed(userId,FILE);
                }
            }));
            for(var job:jobs)job.get(30,TimeUnit.SECONDS);
        }
        assertTotals(240,400);
        assertEquals(79,jdbc.queryForObject("SELECT version FROM user_file_stats",Long.class));
    }

    @Test void recordingDoesNotWaitForDatabaseFlush() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var blocked=new TransferStatsWriter(jdbc,new DataSourceTransactionManager(jdbc.getDataSource())) {
            @Override public void write(TransferStatsBuffer.Batch batch) {
                entered.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                super.write(batch);
            }
        };
        var tracker=new TransferStatsBuffer(blocked,Clock.fixed(NOW,ZoneOffset.UTC));
        tracker.record(userId,FILE,1,2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var flush=executor.submit(tracker::flushPending);
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            try {executor.submit(()->tracker.record(userId,FILE,3,4)).get(2,TimeUnit.SECONDS);}
            finally {release.countDown();}
            flush.get(10,TimeUnit.SECONDS);
        }
        tracker.flushPending();
        assertTotals(4,6);
    }

    void assertTotals(long uploaded,long downloaded) {
        assertEquals(uploaded,jdbc.queryForObject("SELECT total_uploaded FROM user_file_stats",Long.class));
        assertEquals(downloaded,jdbc.queryForObject("SELECT total_downloaded FROM user_file_stats",Long.class));
    }
}
