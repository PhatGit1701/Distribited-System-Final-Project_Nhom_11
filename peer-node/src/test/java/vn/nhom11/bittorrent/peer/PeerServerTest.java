package vn.nhom11.bittorrent.peer;
import vn.nhom11.bittorrent.peer.network.PeerTcpServer;
import vn.nhom11.bittorrent.peer.repository.ChunkStore;
import vn.nhom11.bittorrent.peer.service.TitForTatManager;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vn.nhom11.bittorrent.shared.*;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static vn.nhom11.bittorrent.shared.WireProtocol.*;

@Timeout(15)
class PeerServerTest {
    @TempDir Path temp;
    @Test void servesVerifiedChunksIncludingPartialLastChunkAndRequiresHandshake() throws Exception {
        Path source = temp.resolve("sample.bin");
        byte[] data = new byte[1031]; new Random(7).nextBytes(data); Files.write(source, data);
        try (var store = new ChunkStore(temp.resolve("store"))) {
            var seed = store.importSeed(source, 1024, "http://localhost:8080");
            var policy = new TitForTatManager(Clock.systemUTC(), new Random(1));
            try (var server = new PeerTcpServer(0, store, policy)) {
                server.start();
                try (var socket = new Socket("127.0.0.1", server.port())) {
                    socket.setSoTimeout(3000);
                    var out = new DataOutputStream(socket.getOutputStream());
                    var in = new DataInputStream(socket.getInputStream());
                    WireCodec.writeRequest(out, new WireCodec.Handshake("remote", "alice", 6882));
                    WireCodec.writeRequest(out, new WireCodec.ChunkRequest(seed.metadata().fileId(), 1)); out.flush();
                    var response = WireCodec.readResponse(in);
                    assertEquals(OK_DATA, response.status());
                    assertArrayEquals(Arrays.copyOfRange(data,1024,1031), response.data());
                    WireCodec.writeRequest(out, new WireCodec.ChunkRequest(seed.metadata().fileId(), 2)); out.flush();
                    assertEquals(CHUNK_NOT_FOUND, WireCodec.readResponse(in).status());
                }
                try (var socket = new Socket("127.0.0.1",server.port())) {
                    var out = new DataOutputStream(socket.getOutputStream());
                    WireCodec.writeRequest(out,new WireCodec.ChunkRequest(seed.metadata().fileId(),0)); out.flush();
                    assertEquals(ERROR,WireCodec.readResponse(new DataInputStream(socket.getInputStream())).status());
                }
            }
        }
    }
    @Test void partialLeecherIsChokedUntilOptimisticallyUnchoked() throws Exception {
        Path source=temp.resolve("part.bin"); Files.write(source,new byte[]{1,2,3,4});
        var metadata=TorrentGenerator.generate(source,2,"http://localhost:8080");
        try(var store=new ChunkStore(temp.resolve("partial"))) {
            var file=store.prepare(metadata);
            assertThrows(IOException.class,()->file.writeVerified(0,new byte[]{9,9}));
            assertTrue(file.availableChunks().isEmpty());
            file.writeVerified(0,new byte[]{1,2});
            var policy=new TitForTatManager(Clock.systemUTC(),new Random(1));
            try(var server=new PeerTcpServer(0,store,policy)) {
                server.start();
                try(var socket=new Socket("127.0.0.1",server.port())) {
                    socket.setSoTimeout(3000);
                    var out=new DataOutputStream(socket.getOutputStream());
                    var in=new DataInputStream(socket.getInputStream());
                    WireCodec.writeRequest(out,new WireCodec.Handshake("new","bob",6882));
                    WireCodec.writeRequest(out,new WireCodec.ChunkRequest(metadata.fileId(),0));out.flush();
                    assertEquals(CHOKED,WireCodec.readResponse(in).status());
                    policy.recalculate();
                    WireCodec.writeRequest(out,new WireCodec.ChunkRequest(metadata.fileId(),0));out.flush();
                    assertEquals(OK_DATA,WireCodec.readResponse(in).status());
                    WireCodec.writeRequest(out,new WireCodec.ChunkRequest(metadata.fileId(),1));out.flush();
                    assertEquals(CHUNK_NOT_FOUND,WireCodec.readResponse(in).status());
                }
            }
        }
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-11-05T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @Test void repeatedCumulativeReportsAcrossConnectionsDoNotMultiplyCredit() throws Exception {
        var policy=new TitForTatManager(Clock.systemUTC(),new Random(1));
        try(var store=new ChunkStore(temp.resolve("reports"));var server=new PeerTcpServer(0,store,policy)) {
            server.start();
            for(int i=0;i<2;i++) try(var socket=new Socket("127.0.0.1",server.port())) {
                socket.setSoTimeout(3000);
                var out=new DataOutputStream(socket.getOutputStream());
                WireCodec.writeRequest(out,new WireCodec.Handshake("reporter","alice",6882));
                WireCodec.writeRequest(out,new WireCodec.UploadReport(100));
                WireCodec.writeRequest(out,new WireCodec.ChunkRequest(Hashing.sha256("missing"),0));out.flush();
                assertEquals(CHUNK_NOT_FOUND,WireCodec.readResponse(new DataInputStream(socket.getInputStream())).status());
            }
            assertEquals(100,policy.contribution("reporter"));
        }
    }
    @Test void ranksRollingWindowAndRotatesOptimisticPeer() {
        var clock=new MutableClock(); var policy=new TitForTatManager(clock,new Random(3));
        for(int i=0;i<7;i++) policy.recordContribution("p"+i,100-i);
        policy.recalculate();
        assertEquals(Set.of("p0","p1","p2","p3"),policy.unchokedPeers());
        String first=policy.optimisticPeer();
        assertNotNull(first); assertFalse(policy.unchokedPeers().contains(first));
        clock.now=clock.now.plusSeconds(9);
        policy.recalculate(); assertEquals(first,policy.optimisticPeer());
        clock.now=clock.now.plusSeconds(22);
        policy.recalculate(); assertNotEquals(first,policy.optimisticPeer());
        assertEquals(0,policy.contribution("p0"));
        assertTrue(policy.mayUpload("any",true));
    }
    @Test void reportsCannotRecreditLocallyVerifiedBytesOrReviveExpiredCredit() {
        var clock=new MutableClock();var policy=new TitForTatManager(clock,new Random());
        policy.recordContribution("p",100);
        policy.recordReportedContribution("p",100);
        assertEquals(100,policy.contribution("p"));
        clock.now=clock.now.plusSeconds(11);
        policy.recordReportedContribution("p",100);
        assertEquals(0,policy.contribution("p"));
        policy.recordReportedContribution("p",120);
        policy.recordContribution("p",20);
        assertEquals(20,policy.contribution("p"));
    }
}
