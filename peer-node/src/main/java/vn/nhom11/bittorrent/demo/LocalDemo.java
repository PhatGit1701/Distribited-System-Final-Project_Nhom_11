package vn.nhom11.bittorrent.demo;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.nhom11.bittorrent.common.Hashes;
import vn.nhom11.bittorrent.common.Json;
import vn.nhom11.bittorrent.downloader.DownloadEvent;
import vn.nhom11.bittorrent.downloader.DownloadManager;
import vn.nhom11.bittorrent.downloader.DownloadOptions;
import vn.nhom11.bittorrent.downloader.PeerSource;
import vn.nhom11.bittorrent.model.Manifest;
import vn.nhom11.bittorrent.model.PeerEndpoint;
import vn.nhom11.bittorrent.protocol.FrameCodec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Loopback fixture for Student 3; not the production tracker or Student 2's uploader. */
public final class LocalDemo {
    private LocalDemo() { }

    public static Path run(Path requestedDirectory, Consumer<DownloadEvent> events) throws IOException {
        Path directory = requestedDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory.getParent());
        Files.createDirectory(directory); // Explicitly require a fresh directory.
        byte[] source = new byte[1024 * 1024 + 123];
        new Random(11).nextBytes(source);
        Files.write(directory.resolve("original.bin"), source);
        int chunkSize = 256 * 1024;
        List<String> hashes = new ArrayList<>();
        for (int offset = 0; offset < source.length; offset += chunkSize) {
            hashes.add(Hashes.sha256(Arrays.copyOfRange(source, offset, Math.min(source.length, offset + chunkSize))));
        }
        String hash = Hashes.sha256(source);
        Manifest manifest = new Manifest(1, "original.bin", source.length, chunkSize, hashes.size(),
                "SHA-256", hashes, hash, hash, "unused-in-local-demo");
        Path manifestFile = directory.resolve("sample.torrent.json");
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
        try (DemoPeer first = new DemoPeer("peer-a", source, manifest);
             DemoPeer second = new DemoPeer("peer-b", source, manifest)) {
            return new DownloadManager(DownloadOptions.defaults(4), events).download(
                    Manifest.read(manifestFile), PeerSource.fixed(List.of(first.endpoint(), second.endpoint())),
                    directory.resolve("downloaded.bin"));
        }
    }

    private static final class DemoPeer implements AutoCloseable {
        private final String id;
        private final ServerSocket server;
        private final ExecutorService loop = Executors.newSingleThreadExecutor();
        private final Future<?> task;

        DemoPeer(String id, byte[] source, Manifest manifest) throws IOException {
            this.id = id;
            server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            task = loop.submit(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(5_000);
                        ObjectNode request = FrameCodec.readHeader(socket.getInputStream());
                        long requestedIndex = FrameCodec.integer(request, "chunk_index");
                        boolean valid = FrameCodec.text(request, "message_type").equals("GET_CHUNK")
                                && FrameCodec.text(request, "file_id").equals(manifest.fileId())
                                && requestedIndex >= 0 && requestedIndex < manifest.chunkCount();
                        ObjectNode response = request.deepCopy();
                        response.put("message_type", "CHUNK_RESPONSE");
                        response.put("peer_id", id);
                        response.put("status", valid ? "OK" : "NOT_FOUND");
                        int index = valid ? (int) requestedIndex : 0;
                        int length = valid ? manifest.chunkLength(index) : 0;
                        response.put("body_length", length);
                        FrameCodec.writeHeader(socket.getOutputStream(), response);
                        if (valid) socket.getOutputStream().write(source, index * manifest.chunkSize(), length);
                    } catch (IOException e) {
                        if (!server.isClosed()) throw new IllegalStateException("Demo peer failed", e);
                    }
                }
            });
        }

        PeerEndpoint endpoint() {
            return new PeerEndpoint(id, server.getInetAddress().getHostAddress(), server.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            server.close();
            loop.shutdownNow();
            try {
                if (!loop.awaitTermination(6, TimeUnit.SECONDS)) throw new IOException("Demo peer failed to stop");
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping demo peer", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException("Demo peer failed", e.getCause());
            }
        }
    }
}
