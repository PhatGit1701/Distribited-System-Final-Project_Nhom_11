package vn.nhom11.bittorrent.peer;

import vn.nhom11.bittorrent.shared.*;
import java.io.*;
import java.net.Socket;
import static vn.nhom11.bittorrent.shared.WireProtocol.*;

/** A handshake identifies one connection; subsequent requests may reuse it. */
public final class PeerConnectionWorker implements Runnable {
    private final Socket socket;
    private final ChunkStore store;
    private final TitForTatManager policy;
    public PeerConnectionWorker(Socket socket, ChunkStore store, TitForTatManager policy) {
        this.socket = socket; this.store = store; this.policy = policy;
    }
    @Override public void run() {
        try (socket) {
            var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            try {
                if (!(WireCodec.readRequest(in) instanceof WireCodec.Handshake handshake)) {
                    status(out, ERROR); return;
                }
                policy.registerPeer(handshake.peerId());
                while (!Thread.currentThread().isInterrupted()) {
                    WireCodec.Request request;
                    try { request = WireCodec.readRequest(in); } catch (EOFException e) { return; }
                    if (request instanceof WireCodec.UploadReport report) {
                        policy.recordReportedContribution(handshake.peerId(), report.bytesUploadedToYou());
                    } else if (request instanceof WireCodec.ChunkRequest chunk) {
                        var file = store.get(chunk.fileId());
                        if (file == null || !file.shareable() || chunk.chunkIndex() >= file.metadata().totalChunks()
                                || !file.hasChunk(chunk.chunkIndex())) { status(out, CHUNK_NOT_FOUND); continue; }
                        if (!policy.mayUpload(handshake.peerId(), file.complete())) { status(out, CHOKED); continue; }
                        byte[] bytes = file.readChunk(chunk.chunkIndex());
                        WireCodec.writeResponse(out, new WireCodec.Response(OK_DATA, chunk.chunkIndex(), bytes));
                        out.flush();
                        policy.recordUpload(handshake.peerId(), bytes.length);
                    } else { status(out, ERROR); return; }
                }
            } catch (IOException | IllegalArgumentException e) {
                try { status(out, ERROR); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) { /* Connection closed, timed out, or server shutdown. */ }
    }
    private void status(DataOutputStream out, byte status) throws IOException {
        WireCodec.writeResponse(out, new WireCodec.Response(status, -1, new byte[0])); out.flush();
    }
}
