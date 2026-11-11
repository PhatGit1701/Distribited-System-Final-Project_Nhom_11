package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.dto.ReportRequest;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;
import vn.nhom11.bittorrent.tracker.repository.SourceReputationRepository;
import vn.nhom11.bittorrent.tracker.repository.PeerReportRepository;
import vn.nhom11.bittorrent.tracker.repository.UserFileStatsRepository;
import vn.nhom11.bittorrent.tracker.repository.TorrentRepository;
import vn.nhom11.bittorrent.tracker.repository.UserRepository;
import vn.nhom11.bittorrent.tracker.model.UserFileStats;
import vn.nhom11.bittorrent.tracker.model.PeerReport;
import vn.nhom11.bittorrent.tracker.model.TorrentEntity;
import vn.nhom11.bittorrent.tracker.model.UserAccount;
import vn.nhom11.bittorrent.tracker.service.TorrentRegistry;
import vn.nhom11.bittorrent.tracker.service.ReportService;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.nhom11.bittorrent.shared.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles(value={"prod","test"},inheritProfiles=false)
@Testcontainers
class PostgresRepositoryTest extends TrackerIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired TorrentRepository files;
    @Autowired UserFileStatsRepository stats;
    @Autowired PeerReportRepository reports;
    @Autowired TorrentRegistry registry;
    @Autowired SourceReputationRepository reputations;
    @Autowired ReportService moderation;
    @Autowired SwarmRepository swarms;

    @Test void batchesReputationAndAggregatesAcrossFilesWithoutCrossAccountPeerBan() {
        var owner = users.saveAndFlush(new UserAccount("reputation_owner", "encoded-password"));
        var other = users.saveAndFlush(new UserAccount("reputation_other", "encoded-password"));
        for (String name : List.of("ratio-a.bin", "ratio-b.bin")) {
            var metadata = new TorrentMetadata(Hashing.fileId(name,1,1),name,1,1,1,
                    List.of(Hashing.sha256("x")),"http://localhost:8080");
            var file = files.saveAndFlush(new TorrentEntity(metadata,owner));
            var row = new UserFileStats(owner,file); row.recordTransfer(30,10); stats.saveAndFlush(row);
            jdbc.update("UPDATE user_file_stats SET verified_uploaded=30,verified_downloaded=10 WHERE user_id=? AND file_id=?",owner.getId(),metadata.fileId());
        }
        jdbc.update("INSERT INTO peer_reputation(user_id,peer_id,is_banned,bad_chunk_count) VALUES(?,'shared-peer',true,4)",owner.getId());
        jdbc.update("UPDATE users SET is_banned=true WHERE id=?",owner.getId());
        var now = java.time.Instant.now();
        var ownerPeer = new SwarmRepository.Peer("file","shared-peer",owner.getId().toString(),"owner","127.0.0.1",6881,java.util.Set.of(0),now);
        var otherPeer = new SwarmRepository.Peer("file","shared-peer",other.getId().toString(),"other","127.0.0.1",6882,java.util.Set.of(0),now);
        var unknown = new SwarmRepository.Peer("file","unknown","-999","unknown","127.0.0.1",6883,java.util.Set.of(0),now);
        var result = reputations.load(List.of(ownerPeer,otherPeer,unknown));
        assertEquals(2,result.size());
        var r = result.get(new SourceReputationRepository.PeerKey(ownerPeer.userId(),ownerPeer.peerId()));
        assertTrue(r.userBanned()); assertTrue(r.peerBanned()); assertEquals(4,r.badChunkCount()); assertEquals(3.0,r.ratio());
        var clean = result.get(new SourceReputationRepository.PeerKey(otherPeer.userId(),otherPeer.peerId()));
        assertFalse(clean.userBanned()); assertFalse(clean.peerBanned()); assertEquals(0,clean.badChunkCount()); assertEquals(0,clean.ratio());
        var userReputation=reputations.user(owner.getId()).orElseThrow();
        assertEquals(60,userReputation.verifiedUploaded()); assertEquals(20,userReputation.verifiedDownloaded());
        assertEquals(3,userReputation.ratio()); assertEquals(75,userReputation.score());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->jdbc.update("INSERT INTO peer_reputation(user_id,peer_id) VALUES(-999,'orphan')"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->jdbc.update("INSERT INTO peer_reputation(user_id,peer_id,bad_chunk_count) VALUES(?,'negative',-1)",owner.getId()));
    }

    @Test void mismatchQuorumQuarantinesTemporarilyAndRejectsDuplicatesAndForgedEvidence() throws Exception {
        var target=users.saveAndFlush(new UserAccount("report_target","encoded-password"));
        var reporters=new java.util.ArrayList<UserAccount>();
        for(int i=0;i<4;i++) reporters.add(users.saveAndFlush(new UserAccount("report_witness_"+i,"encoded-password")));
        var metadata=new TorrentMetadata(Hashing.fileId("reported.bin",1,1),"reported.bin",1,1,1,
                List.of(Hashing.sha256("x")),"http://localhost:8080");
        registry.register(metadata,target.getId());
        var peer=new SwarmRepository.Peer(metadata.fileId(),"reported-peer",target.getId().toString(),target.getUsername(),
                "127.0.0.1",6881,java.util.Set.of(0),java.time.Instant.now());
        swarms.register(peer);
        var request=new ReportRequest(metadata.fileId(),peer.peerId(),target.getId(),0,Hashing.sha256("x"),Hashing.sha256("bad"));
        assertThrows(IllegalArgumentException.class,()->moderation.report(target.getId(),request));
        assertThrows(IllegalArgumentException.class,()->moderation.report(reporters.getFirst().getId(),
                new ReportRequest(metadata.fileId(),peer.peerId(),target.getId(),0,Hashing.sha256("fake"),Hashing.sha256("bad"))));
        assertThrows(IllegalArgumentException.class,()->moderation.report(reporters.getFirst().getId(),
                new ReportRequest(metadata.fileId(),peer.peerId(),target.getId(),0,Hashing.sha256("x"),Hashing.sha256("x"))));
        assertThrows(IllegalArgumentException.class,()->moderation.report(reporters.getFirst().getId(),
                new ReportRequest(metadata.fileId(),peer.peerId(),reporters.getFirst().getId(),0,Hashing.sha256("x"),Hashing.sha256("bad"))));
        var inferredTarget=new ReportRequest(metadata.fileId(),peer.peerId(),null,0,Hashing.sha256("x"),Hashing.sha256("bad"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reports")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject(reporters.getFirst().getId().toString())))
                .contentType("application/json").content(json.writeValueAsBytes(inferredTarget)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.distinctReporters").value(1));
        var duplicate=assertThrows(vn.nhom11.bittorrent.shared.exception.AppException.class,
                ()->moderation.report(reporters.getFirst().getId(),request));
        assertEquals(409,duplicate.getErrorCode().status());
        for(int i=1;i<3;i++) assertNull(moderation.report(reporters.get(i).getId(),request).banUntil());
        var result=moderation.report(reporters.get(3).getId(),request);
        assertEquals(4,result.distinctReporters()); assertTrue(result.suspicious());
        assertTrue(result.banUntil().isAfter(java.time.Instant.now().plusSeconds(590)));
        var key=new SourceReputationRepository.PeerKey(peer.userId(),peer.peerId());
        assertTrue(reputations.load(List.of(peer)).get(key).peerBanned());
        jdbc.update("UPDATE peer_reputation SET ban_until=now()-interval '1 second' WHERE user_id=? AND peer_id=?",target.getId(),peer.peerId());
        jdbc.update("UPDATE peer_reports SET created_at=now()-interval '11 minutes' WHERE target_user_id=?",target.getId());
        assertFalse(reputations.load(List.of(peer)).get(key).peerBanned());
        var fresh=moderation.report(reporters.getFirst().getId(),request);
        assertEquals(1,fresh.distinctReporters()); assertNull(fresh.banUntil());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/users/"+target.getId()+"/reputation")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j->j.subject(target.getId().toString()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.score").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/reports")
                .contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test void migratesAndRoundTripsBinaryMetadata() {
        assertEquals("5", flyway.info().current().getVersion().toString());
        assertEquals(0, flyway.migrate().migrationsExecuted);
        var owner = users.saveAndFlush(new UserAccount("pg_owner", "encoded-password"));
        var metadata = new TorrentMetadata(Hashing.fileId("binary.bin",4,3), "binary.bin",4,3,2,
                List.of(Hashing.sha256("abc"),Hashing.sha256("d")), "http://localhost:8080");
        files.saveAndFlush(new TorrentEntity(metadata, owner));
        assertEquals(metadata, files.findById(metadata.fileId()).orElseThrow().toMetadata());
        assertEquals(64, jdbc.queryForObject("select octet_length(chunk_hashes) from shared_files where file_id = ?", Integer.class, metadata.fileId()));
        assertEquals("bytea", jdbc.queryForObject("select data_type from information_schema.columns where table_name='shared_files' and column_name='chunk_hashes'", String.class));
    }

    @Test void persistsStatsReportsAndRejectsStaleUpdates() {
        var owner = users.saveAndFlush(new UserAccount("pg_stats", "encoded-password"));
        var metadata = new TorrentMetadata(Hashing.fileId("stats.bin",1,1), "stats.bin",1,1,1,
                List.of(Hashing.sha256("x")), "http://localhost:8080");
        var file = files.saveAndFlush(new TorrentEntity(metadata, owner));
        var saved = stats.saveAndFlush(new UserFileStats(owner, file));
        var first = stats.findById(saved.getId()).orElseThrow();
        var stale = stats.findById(saved.getId()).orElseThrow();
        first.recordTransfer(7, 1);
        first.markCompleted(java.time.Instant.parse("2026-11-10T00:00:00Z"));
        stats.saveAndFlush(first);
        stale.recordTransfer(3, 0);
        assertThrows(org.springframework.orm.ObjectOptimisticLockingFailureException.class, () -> stats.saveAndFlush(stale));
        assertEquals(7, stats.findById(saved.getId()).orElseThrow().getTotalUploaded());
        assertEquals(1, stats.findById(saved.getId()).orElseThrow().getTotalDownloaded());
        assertEquals(java.time.Instant.parse("2026-11-10T00:00:00Z"),stats.findById(saved.getId()).orElseThrow().getCompletedAt());
        var report = reports.saveAndFlush(new PeerReport(owner, file, "peer-b", "Hash mismatch"));
        assertEquals("Hash mismatch", reports.findById(report.getId()).orElseThrow().getReason());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> stats.saveAndFlush(new UserFileStats(owner, file)));
    }

    @Test void databaseRejectsInvalidRowsAndDuplicateKeys() {
        var owner = users.saveAndFlush(new UserAccount("pg_constraints", "encoded-password"));
        var metadata = new TorrentMetadata(Hashing.fileId("checks.bin",1,1), "checks.bin",1,1,1,
                List.of(Hashing.sha256("x")), "http://localhost:8080");
        files.saveAndFlush(new TorrentEntity(metadata, owner));
        for (String assignment : List.of("size=0", "size=-1", "chunk_size=0", "chunk_count=2",
                "file_id='bad'", "chunk_hashes=decode('00','hex')", "owner_id=-1")) {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("update shared_files set " + assignment + " where file_id=?", metadata.fileId()), assignment);
        }
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> users.saveAndFlush(new UserAccount("pg_constraints", "other")));
        var other = users.saveAndFlush(new UserAccount("pg_other", "encoded-password"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("update users set passkey=(select passkey from users where id=?) where id=?", owner.getId(), other.getId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("insert into user_file_stats(user_id,file_id,total_uploaded,total_downloaded,version) values(?,?,-1,0,0)", owner.getId(), metadata.fileId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("insert into peer_reports(reporter_id,file_id,peer_id,reason,created_at) values(-1,?,'peer','reason',now())", metadata.fileId()));
    }

    @Test void repeatedPublicationPreservesOwnerAndRejectsConflictingHashes() {
        var owner=users.saveAndFlush(new UserAccount("first_owner","encoded-password"));
        var another=users.saveAndFlush(new UserAccount("second_owner","encoded-password"));
        var metadata=new TorrentMetadata(Hashing.fileId("owner.bin",1,1),"owner.bin",1,1,1,
                List.of(Hashing.sha256("x")),"http://localhost:8080");
        registry.register(metadata,owner.getId());
        registry.register(metadata,another.getId());
        assertEquals(owner.getId(),jdbc.queryForObject("select owner_id from shared_files where file_id=?",Long.class,metadata.fileId()));
        var conflict=new TorrentMetadata(metadata.fileId(),"owner.bin",1,1,1,
                List.of(Hashing.sha256("y")),metadata.trackerUrl());
        assertThrows(vn.nhom11.bittorrent.shared.exception.AppException.class,()->registry.register(conflict,another.getId()));
        assertEquals(metadata,registry.get(metadata.fileId()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                ()->files.saveAndFlush(new TorrentEntity(conflict,another)));
        assertEquals(metadata,registry.get(metadata.fileId()));
    }
}
