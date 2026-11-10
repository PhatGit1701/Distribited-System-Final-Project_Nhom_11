package vn.nhom11.bittorrent.downloader;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.nhom11.bittorrent.common.Hashes;
import vn.nhom11.bittorrent.common.Json;
import vn.nhom11.bittorrent.model.Manifest;
import vn.nhom11.bittorrent.model.PeerEndpoint;
import vn.nhom11.bittorrent.protocol.FrameCodec;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Thread-safe transport; closing it also closes active sockets to unblock workers. */
public final class PeerClient implements AutoCloseable {
    private final DownloadOptions options;
    private final Consumer<DownloadEvent> events;
    private final Set<Socket> activeSockets = new HashSet<>();
    private boolean closed;

    public PeerClient(DownloadOptions options, Consumer<DownloadEvent> events) {
        this.options = options;
        this.events = events;
    }

    public Path fetchChunk(Manifest manifest, int index, PeerEndpoint peer, Path workspace) throws IOException {
        int expectedLength = manifest.chunkLength(index);
        String requestId = UUID.randomUUID().toString();
        Path temporary = Files.createTempFile(workspace, "chunk-" + index + "-", ".part");
        boolean verified = false;
        Socket socket = new Socket();
        try (socket) {
            register(socket);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.attemptTimeoutMs());
            socket.connect(new InetSocketAddress(peer.host(), peer.port()),
                    Math.min(options.connectTimeoutMs(), options.attemptTimeoutMs()));
            ObjectNode request = Json.MAPPER.createObjectNode();
            request.put("version", 1);
            request.put("request_id", requestId);
            request.put("message_type", "GET_CHUNK");
            // Downloader identity is per client session; tracker membership comes in the next milestone.
            request.put("peer_id", clientId);
            request.put("file_id", manifest.fileId());
            request.put("chunk_index", index);
            request.put("body_length", 0);
            FrameCodec.writeHeader(socket.getOutputStream(), request);
            events.accept(new DownloadEvent("TRANSFER_STARTED", index, peer.peerId(), requestId, ""));

            InputStream input = new DeadlineInput(socket, deadline, options.readTimeoutMs());
            ObjectNode response = FrameCodec.readHeader(input);
            if (FrameCodec.integer(response, "version") != 1
                    || !FrameCodec.text(response, "request_id").equals(requestId)
                    || !FrameCodec.text(response, "message_type").equals("CHUNK_RESPONSE")
                    || !FrameCodec.text(response, "peer_id").equals(peer.peerId())
                    || !FrameCodec.text(response, "file_id").equals(manifest.fileId())
                    || FrameCodec.integer(response, "chunk_index") != index) {
                throw new IOException("Response identity does not match request");
            }
            String status = FrameCodec.text(response, "status");
            long length = FrameCodec.integer(response, "body_length");
            if (!status.equals("OK")) {
                if (length != 0) throw new IOException("Error response must have an empty body");
                throw new IOException("Peer returned " + status);
            }
            if (length != expectedLength) throw new IOException("Unexpected chunk body_length: " + length);

            MessageDigest digest = Hashes.sha256Digest();
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int remaining = expectedLength;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (count == -1) throw new EOFException("Peer disconnected before the chunk was complete");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
            }
            if (!Hashes.hex(digest.digest()).equals(manifest.chunkHashes().get(index))) {
                throw new IOException("SHA-256 mismatch for chunk " + index);
            }
            events.accept(new DownloadEvent("HASH_OK", index, peer.peerId(), requestId,
                    "bytes=" + expectedLength));
            verified = true;
            return temporary;
        } finally {
            unregister(socket);
            if (!verified) Files.deleteIfExists(temporary);
        }
    }

    private final String clientId = "downloader-" + UUID.randomUUID();

    private synchronized void register(Socket socket) throws IOException {
        if (closed) throw new IOException("Peer client is closed");
        activeSockets.add(socket);
    }

    private synchronized void unregister(Socket socket) {
        activeSockets.remove(socket);
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Socket socket : activeSockets) {
            try { socket.close(); } catch (IOException ignored) { /* Close the remaining sockets too. */ }
        }
        activeSockets.clear();
    }

    private static final class DeadlineInput extends FilterInputStream {
        private final Socket socket;
        private final long deadline;
        private final int readTimeoutMs;

        DeadlineInput(Socket socket, long deadline, int readTimeoutMs) throws IOException {
            super(socket.getInputStream());
            this.socket = socket;
            this.deadline = deadline;
            this.readTimeoutMs = readTimeoutMs;
        }

        private void beforeRead() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Chunk attempt deadline exceeded");
            long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
            socket.setSoTimeout((int) Math.min(readTimeoutMs, remainingMs));
        }

        @Override
        public int read() throws IOException {
            beforeRead();
            return in.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            beforeRead();
            return in.read(bytes, offset, length);
        }
    }
}
