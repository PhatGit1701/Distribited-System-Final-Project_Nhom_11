package vn.nhom11.bittorrent.shared;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import static vn.nhom11.bittorrent.shared.WireProtocol.*;

/** Big-endian frames; strings are int byte length + standard UTF-8 (not writeUTF). */
public final class WireCodec {
    private WireCodec() {}
    public sealed interface Request permits Handshake, ChunkRequest, UploadReport {}
    public record Handshake(String peerId, String username, int listenPort) implements Request {}
    public record ChunkRequest(String fileId, int chunkIndex) implements Request {}
    public record UploadReport(long bytesUploadedToYou) implements Request {}
    public record Response(byte status, int chunkIndex, byte[] data) {
        public Response { data = data.clone(); }
        @Override public byte[] data() { return data.clone(); }
    }
    public static void writeRequest(DataOutputStream out, Request request) throws IOException {
        validate(request);
        if (request instanceof Handshake h) {
            out.writeByte(HANDSHAKE); writeString(out, h.peerId()); writeString(out, h.username()); out.writeInt(h.listenPort());
        } else if (request instanceof ChunkRequest c) {
            out.writeByte(REQUEST_CHUNK); writeString(out, c.fileId()); out.writeInt(c.chunkIndex());
        } else if (request instanceof UploadReport r) {
            out.writeByte(REPORT_UPLOAD_BYTES); out.writeLong(r.bytesUploadedToYou());
        }
    }
    public static Request readRequest(DataInputStream in) throws IOException {
        Request request = switch (in.readByte()) {
            case HANDSHAKE -> new Handshake(readString(in), readString(in), in.readInt());
            case REQUEST_CHUNK -> new ChunkRequest(readString(in), in.readInt());
            case REPORT_UPLOAD_BYTES -> new UploadReport(in.readLong());
            default -> throw new IOException("Unknown message type");
        };
        validate(request);
        return request;
    }
    private static void validate(Request request) throws IOException {
        if (request == null) throw new IOException("Missing request");
        if (request instanceof Handshake h) {
            checkString(h.peerId()); checkString(h.username());
            if (h.listenPort() < 1 || h.listenPort() > 65535) throw new IOException("Invalid port");
        } else if (request instanceof ChunkRequest c) {
            if (c.fileId() == null || !c.fileId().matches("[0-9a-f]{64}") || c.chunkIndex() < 0)
                throw new IOException("Invalid chunk request");
        } else if (request instanceof UploadReport r && r.bytesUploadedToYou() < 0)
            throw new IOException("Negative upload report");
    }
    public static void writeResponse(DataOutputStream out, Response response) throws IOException {
        checkStatus(response.status());
        if (response.status() == OK_DATA && (response.chunkIndex() < 0 || response.data.length > TorrentMetadata.MAX_CHUNK_SIZE))
            throw new IOException("Invalid chunk response");
        if (response.status() != OK_DATA && response.data.length != 0)
            throw new IOException("Status frame cannot carry data");
        out.writeByte(response.status());
        if (response.status() == OK_DATA) {
            out.writeInt(response.chunkIndex()); out.writeInt(response.data.length); out.write(response.data);
        }
    }
    public static Response readResponse(DataInputStream in) throws IOException {
        byte status = in.readByte(); checkStatus(status);
        if (status != OK_DATA) return new Response(status, -1, new byte[0]);
        int index = in.readInt(), length = in.readInt();
        if (index < 0 || length < 0 || length > TorrentMetadata.MAX_CHUNK_SIZE)
            throw new IOException("Invalid chunk response");
        byte[] data = new byte[length]; in.readFully(data);
        return new Response(status, index, data);
    }
    private static void checkStatus(byte status) throws IOException {
        if (status != OK_DATA && status != CHOKED && status != CHUNK_NOT_FOUND && status != ERROR)
            throw new IOException("Unknown response status");
    }
    private static void checkString(String value) throws IOException {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES)
            throw new IOException("Invalid protocol string");
    }
    private static void writeString(DataOutputStream out, String value) throws IOException {
        checkString(value);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > MAX_STRING_BYTES) throw new IOException("Invalid string length");
        byte[] bytes = new byte[length]; in.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
