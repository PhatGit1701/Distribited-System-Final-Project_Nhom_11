package vn.nhom11.bittorrent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.nhom11.bittorrent.common.Hashes;
import vn.nhom11.bittorrent.model.Manifest;
import vn.nhom11.bittorrent.model.PeerEndpoint;
import vn.nhom11.bittorrent.protocol.FrameCodec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class TestFixtures {
    record Fixture(byte[] bytes, Manifest manifest) {
        byte[] chunk(int index) {
            int start = index * manifest.chunkSize();
            return Arrays.copyOfRange(bytes, start, start + manifest.chunkLength(index));
        }
    }

    static Fixture fixture(int size, int chunkSize) {
        byte[] bytes = new byte[size];
        new Random(11).nextBytes(bytes);
        List<String> hashes = new ArrayList<>();
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            hashes.add(Hashes.sha256(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + chunkSize))));
        }
        String hash = Hashes.sha256(bytes);
        return new Fixture(bytes, new Manifest(1, "fixture.bin", size, chunkSize, hashes.size(),
                "SHA-256", hashes, hash, hash, "127.0.0.1:8000"));
    }

    static ObjectNode response(ObjectNode request, String peerId, int length) {
        ObjectNode result = request.deepCopy();
        result.put("message_type", "CHUNK_RESPONSE");
        result.put("peer_id", peerId);
        result.put("status", "OK");
        result.put("body_length", length);
        return result;
    }

    static void send(Socket socket, ObjectNode header, byte[] body) throws IOException {
        FrameCodec.writeHeader(socket.getOutputStream(), header);
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
    }

    @FunctionalInterface
    interface Handler {
        void reply(Socket socket, ObjectNode request) throws Exception;
    }

    static TestPeer healthy(String id, Fixture fixture) throws IOException {
        return new TestPeer(id, (socket, request) -> {
            byte[] chunk = fixture.chunk(request.get("chunk_index").intValue());
            send(socket, response(request, id, chunk.length), chunk);
        });
    }

    static final class TestPeer implements AutoCloseable {
        private final String id;
        private final ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
        private final ExecutorService handlers = Executors.newFixedThreadPool(4);
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        TestPeer(String id, Handler handler) throws IOException {
            this.id = id;
            acceptor.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        handlers.submit(() -> {
                            try (socket) {
                                socket.setSoTimeout(3_000);
                                handler.reply(socket, FrameCodec.readHeader(socket.getInputStream()));
                            } catch (Exception ignored) {
                                // Tests intentionally close/truncate connections; assertions run on the client.
                            } finally {
                                sockets.remove(socket);
                            }
                        });
                    } catch (IOException e) {
                        if (!server.isClosed()) throw new IllegalStateException(e);
                    }
                }
            });
        }

        PeerEndpoint endpoint() {
            return new PeerEndpoint(id, server.getInetAddress().getHostAddress(), server.getLocalPort());
        }

        @Override
        public void close() throws Exception {
            server.close();
            acceptor.shutdownNow();
            if (!acceptor.awaitTermination(3, TimeUnit.SECONDS)) throw new IOException("Acceptor did not stop");
            for (Socket socket : sockets) socket.close();
            handlers.shutdownNow();
            if (!handlers.awaitTermination(3, TimeUnit.SECONDS)) throw new IOException("Test handlers did not stop");
        }
    }
}
