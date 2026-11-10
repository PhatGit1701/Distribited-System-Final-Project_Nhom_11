package vn.nhom11.bittorrent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.nhom11.bittorrent.common.Json;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 4-byte big-endian header length, UTF-8 JSON header, then raw body bytes. */
public final class FrameCodec {
    public static final int MAX_HEADER_BYTES = 64 * 1024;

    private FrameCodec() { }

    public static ObjectNode readHeader(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        int length = data.readInt();
        if (length < 1 || length > MAX_HEADER_BYTES) throw new IOException("Invalid header length: " + length);
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        JsonNode node = Json.MAPPER.readTree(bytes);
        if (!(node instanceof ObjectNode object)) throw new IOException("Header must be a JSON object");
        return object;
    }

    public static void writeHeader(OutputStream output, ObjectNode header) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(header);
        if (bytes.length > MAX_HEADER_BYTES) throw new IOException("Header exceeds limit");
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(bytes.length);
        data.write(bytes);
        data.flush();
    }

    public static String text(ObjectNode header, String name) throws IOException {
        JsonNode value = header.get(name);
        if (value == null || !value.isTextual()) throw new IOException("Missing/invalid header field: " + name);
        return value.textValue();
    }

    public static long integer(ObjectNode header, String name) throws IOException {
        JsonNode value = header.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IOException("Missing/invalid integer field: " + name);
        }
        return value.longValue();
    }
}
