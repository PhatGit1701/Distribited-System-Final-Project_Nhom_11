package vn.nhom11.bittorrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.nhom11.bittorrent.downloader.*;
import vn.nhom11.bittorrent.model.Manifest;
import vn.nhom11.bittorrent.protocol.FrameCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static vn.nhom11.bittorrent.TestFixtures.*;

@Timeout(15)
class DownloaderTest {
    @TempDir Path directory;

    private DownloadManager manager(int workers) {
        return new DownloadManager(DownloadOptions.defaults(workers), event -> { });
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".bittorrent-download-")));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1023, 1024, 1025, 2171})
    void downloadsBinaryFilesIncludingEmptyAndShortLastChunk(int length) throws Exception {
        Fixture fixture = fixture(length, 1024);
        try (TestPeer peer = healthy("source", fixture)) {
            Path output = manager(2).download(fixture.manifest(), PeerSource.fixed(List.of(peer.endpoint())),
                    directory.resolve("result.bin"));
            assertArrayEquals(fixture.bytes(), Files.readAllBytes(output));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void transfersFromTwoPeersConcurrentlyWithBoundedWork() throws Exception {
        Fixture fixture = fixture(8192, 1024);
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<DownloadEvent> events = new CopyOnWriteArrayList<>();
        Handler handler = (socket, request) -> {
            bothStarted.countDown();
            if (!bothStarted.await(3, TimeUnit.SECONDS)) throw new IOException("Transfers were not parallel");
            int index = request.get("chunk_index").intValue();
            byte[] chunk = fixture.chunk(index);
            send(socket, response(request, index % 2 == 0 ? "a" : "b", chunk.length), chunk);
        };
        try (TestPeer first = new TestPeer("a", handler); TestPeer second = new TestPeer("b", handler)) {
            PeerSource sources = (manifest, index) -> List.of(index % 2 == 0 ? first.endpoint() : second.endpoint());
            Path output = new DownloadManager(DownloadOptions.defaults(2), event -> {
                events.add(event);
                if (event.type().equals("TRANSFER_STARTED")) maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                if (event.type().equals("HASH_OK") || event.type().equals("SOURCE_FAILED")) active.decrementAndGet();
            })
                    .download(fixture.manifest(), sources, directory.resolve("parallel.bin"));
            assertArrayEquals(fixture.bytes(), Files.readAllBytes(output));
            assertEquals(2, maximum.get());
            assertEquals(0, active.get());
            assertEquals(8, events.stream().filter(e -> e.type().equals("CHUNK_COMMITTED")).count());
            assertEquals(2, events.stream().filter(e -> e.type().equals("HASH_OK"))
                    .map(DownloadEvent::peerId).distinct().count());
        }
        assertNoTemporaryFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"corrupt", "disconnect", "choked"})
    void switchesToAnotherSourceAfterFailure(String failure) throws Exception {
        Fixture fixture = fixture(100, 1024);
        List<DownloadEvent> events = new CopyOnWriteArrayList<>();
        try (TestPeer bad = new TestPeer("bad", (socket, request) -> {
            byte[] bytes = fixture.bytes().clone();
            var header = response(request, "bad", bytes.length);
            if (failure.equals("corrupt")) bytes[0] ^= 1;
            if (failure.equals("choked")) {
                header.put("status", "CHOKED").put("body_length", 0).put("retry_after", 2);
                bytes = new byte[0];
            }
            if (failure.equals("disconnect")) bytes = java.util.Arrays.copyOf(bytes, bytes.length / 2);
            send(socket, header, bytes);
        }); TestPeer good = healthy("good", fixture)) {
            Path output = new DownloadManager(DownloadOptions.defaults(1), events::add).download(
                    fixture.manifest(), PeerSource.fixed(List.of(bad.endpoint(), good.endpoint())),
                    directory.resolve("recovered.bin"));
            assertArrayEquals(fixture.bytes(), Files.readAllBytes(output));
            assertEquals(1, events.stream().filter(e -> e.type().equals("SOURCE_FAILED")).count());
            assertEquals(List.of("good"), events.stream().filter(e -> e.type().equals("CHUNK_COMMITTED"))
                    .map(DownloadEvent::peerId).toList());
        }
        assertNoTemporaryFiles();
    }

    @Test
    void assemblesByIndexWhenChunksCompleteOutOfOrder() throws Exception {
        Fixture fixture = fixture(2048, 1024);
        CountDownLatch secondCommitted = new CountDownLatch(1);
        List<Integer> committed = new CopyOnWriteArrayList<>();
        try (TestPeer peer = new TestPeer("source", (socket, request) -> {
            int index = request.get("chunk_index").intValue();
            if (index == 0 && !secondCommitted.await(3, TimeUnit.SECONDS)) {
                throw new IOException("Second chunk did not finish first");
            }
            byte[] bytes = fixture.chunk(index);
            send(socket, response(request, "source", bytes.length), bytes);
        })) {
            Path output = new DownloadManager(DownloadOptions.defaults(2), event -> {
                if (event.type().equals("CHUNK_COMMITTED")) {
                    committed.add(event.chunkIndex());
                    if (event.chunkIndex() == 1) secondCommitted.countDown();
                }
            }).download(fixture.manifest(), PeerSource.fixed(List.of(peer.endpoint())), directory.resolve("ordered.bin"));
            assertEquals(List.of(1, 0), committed);
            assertArrayEquals(fixture.bytes(), Files.readAllBytes(output));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"request_id", "chunk_index", "file_id", "body_length", "peer_id", "corrupt"})
    void rejectsBadResponsesWithoutPublishingOutput(String field) throws Exception {
        Fixture fixture = fixture(100, 1024);
        try (TestPeer peer = new TestPeer("bad", (socket, request) -> {
            var header = response(request, "bad", fixture.bytes().length);
            byte[] body = fixture.bytes().clone();
            switch (field) {
                case "chunk_index" -> header.put(field, 99);
                case "body_length" -> header.put(field, Integer.MAX_VALUE);
                case "corrupt" -> body[0] ^= 1;
                default -> header.put(field, "wrong");
            }
            send(socket, header, body);
        })) {
            Path output = directory.resolve("must-not-exist.bin");
            assertThrows(IOException.class, () -> manager(1).download(
                    fixture.manifest(), PeerSource.fixed(List.of(peer.endpoint())), output));
            assertFalse(Files.exists(output));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void enforcesAttemptDeadlineAgainstSlowDripHeader() throws Exception {
        Fixture fixture = fixture(100, 1024);
        try (TestPeer peer = new TestPeer("slow", (socket, request) -> {
            var encoded = new ByteArrayOutputStream();
            FrameCodec.writeHeader(encoded, response(request, "slow", 100));
            for (byte b : encoded.toByteArray()) {
                socket.getOutputStream().write(b);
                socket.getOutputStream().flush();
                Thread.sleep(20); // Fault injection: makes progress without hitting the read timeout.
            }
        })) {
            var options = new DownloadOptions(1, 500, 500, 150);
            IOException error = assertThrows(IOException.class, () -> new DownloadManager(options, e -> { })
                    .download(fixture.manifest(), PeerSource.fixed(List.of(peer.endpoint())), directory.resolve("slow.bin")));
            assertEquals(1, error.getSuppressed().length);
            assertInstanceOf(java.net.SocketTimeoutException.class, error.getSuppressed()[0]);
        }
        assertNoTemporaryFiles();
    }

    @Test
    void failsClearlyWhenNoSourceExists() throws Exception {
        IOException error = assertThrows(IOException.class, () -> manager(1).download(fixture(1, 1024).manifest(),
                PeerSource.fixed(List.of()), directory.resolve("missing.bin")));
        assertTrue(error.getMessage().contains("No source for chunk 0"));
        assertNoTemporaryFiles();
    }

    @Test
    void doesNotOverwriteExistingOutput() throws Exception {
        Path output = directory.resolve("existing.bin");
        Files.writeString(output, "keep me");
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> manager(1).download(
                fixture(1, 1024).manifest(), (manifest, index) -> { fail("Should not query sources"); return List.of(); }, output));
        assertEquals("keep me", Files.readString(output));
    }

    @Test
    void rejectsFinalFileHashEvenWhenChunkHashesPass() throws Exception {
        Fixture fixture = fixture(100, 1024);
        Manifest m = fixture.manifest();
        Manifest wrong = new Manifest(1, m.filename(), m.fileSize(), m.chunkSize(), m.chunkCount(),
                m.hashAlgorithm(), m.chunkHashes(), "0".repeat(64), "0".repeat(64), m.trackerEndpoint());
        try (TestPeer peer = healthy("source", fixture)) {
            IOException error = assertThrows(IOException.class, () -> manager(1).download(wrong,
                    PeerSource.fixed(List.of(peer.endpoint())), directory.resolve("wrong.bin")));
            assertTrue(error.getMessage().contains("Final file"));
            assertFalse(Files.exists(directory.resolve("wrong.bin")));
        }
        assertNoTemporaryFiles();
    }

    @Test
    void cancellationClosesBlockedSocketsAndCleansWorkspace() throws Exception {
        Fixture fixture = fixture(100, 1024);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestPeer peer = new TestPeer("blocked", (socket, request) -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
        })) {
            var caller = Executors.newSingleThreadExecutor();
            try {
                var future = caller.submit(() -> manager(1).download(fixture.manifest(),
                        PeerSource.fixed(List.of(peer.endpoint())), directory.resolve("cancelled.bin")));
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertTrue(future.cancel(true));
                caller.shutdown();
                assertTrue(caller.awaitTermination(3, TimeUnit.SECONDS));
                assertFalse(Files.exists(directory.resolve("cancelled.bin")));
                assertNoTemporaryFiles();
            } finally {
                release.countDown();
                caller.shutdownNow();
            }
        }
    }
}
