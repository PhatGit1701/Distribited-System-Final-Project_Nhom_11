package vn.nhom11.bittorrent.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonTest {
    @TempDir Path temp;
    @Test void generatesHashesForPartialAndEmptyFilesAndRoundTripsJson() throws Exception {
        Path file = temp.resolve("dữ liệu.bin");
        Files.write(file, new byte[]{1,2,3,4,5});
        var metadata = TorrentGenerator.generate(file, 3, "http://localhost:8080");
        assertEquals(2, metadata.totalChunks());
        assertEquals(Hashing.sha256(new byte[]{4,5}), metadata.chunkHashes().get(1));
        assertEquals(Hashing.sha256("dữ liệu.bin53"), metadata.fileId());
        Path torrent = temp.resolve("sample.torrent");
        TorrentGenerator.write(metadata, torrent);
        assertEquals(metadata, TorrentGenerator.read(torrent));
        Files.write(file, new byte[0]);
        assertEquals(0, TorrentGenerator.generate(file, "http://localhost:8080").totalChunks());
        assertThrows(IllegalArgumentException.class, () -> TorrentGenerator.generate(file, 0, "http://localhost:8080"));
    }
    @Test void validatesMetadataAndProtectsCollections() {
        String hash = Hashing.sha256(new byte[0]);
        var hashes = new ArrayList<>(List.of(hash));
        var m = new TorrentMetadata(Hashing.fileId("a", 1, 3), "a", 1, 3, 1, hashes, "http://localhost:8080");
        hashes.clear();
        assertEquals(1, m.chunkHashes().size());
        assertThrows(IllegalArgumentException.class, () -> new TorrentMetadata(hash,"a",1,3,0,List.of(),"http://localhost"));
        assertThrows(IllegalArgumentException.class, () -> new TorrentMetadata(hash,"../a",0,3,0,List.of(),"http://localhost"));
    }
    @Test void roundTripsAllWireFramesUsingStandardUtf8() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        WireCodec.writeRequest(out, new WireCodec.Handshake("p1", "Nguyễn", 6881));
        WireCodec.writeRequest(out, new WireCodec.ChunkRequest(Hashing.sha256("file"), 2));
        WireCodec.writeRequest(out, new WireCodec.UploadReport(123L));
        WireCodec.writeResponse(out, new WireCodec.Response(WireProtocol.OK_DATA, 2, new byte[]{1,2}));
        WireCodec.writeResponse(out, new WireCodec.Response(WireProtocol.CHOKED, -1, new byte[0]));
        var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(new WireCodec.Handshake("p1", "Nguyễn", 6881), WireCodec.readRequest(in));
        assertEquals(new WireCodec.ChunkRequest(Hashing.sha256("file"), 2), WireCodec.readRequest(in));
        assertEquals(new WireCodec.UploadReport(123L), WireCodec.readRequest(in));
        assertArrayEquals(new byte[]{1,2}, WireCodec.readResponse(in).data());
        assertEquals(WireProtocol.CHOKED, WireCodec.readResponse(in).status());
    }
    @Test void rejectsUnboundedAndTruncatedFrames() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeByte(WireProtocol.OK_DATA); out.writeInt(0); out.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> WireCodec.readResponse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertThrows(IOException.class, () -> WireCodec.readRequest(new DataInputStream(new ByteArrayInputStream(new byte[]{99}))));
        assertThrows(EOFException.class, () -> WireCodec.readRequest(new DataInputStream(new ByteArrayInputStream(new byte[]{1,0}))));
    }
}
