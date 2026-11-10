package vn.nhom11.bittorrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.nhom11.bittorrent.common.Json;
import vn.nhom11.bittorrent.protocol.FrameCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FrameCodecTest {
    @Test
    void reassemblesFragmentedHeaderWithoutConsumingBinaryBody() throws Exception {
        var header = Json.MAPPER.createObjectNode().put("body_length", 3);
        var encoded = new ByteArrayOutputStream();
        FrameCodec.writeHeader(encoded, header);
        encoded.write(new byte[]{0, (byte) 255, 42});
        var input = new FilterInputStream(new ByteArrayInputStream(encoded.toByteArray())) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };
        assertEquals(header, FrameCodec.readHeader(input));
        assertArrayEquals(new byte[]{0, (byte) 255, 42}, input.readAllBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65537, Integer.MAX_VALUE})
    void rejectsInvalidLengthBeforeReadingPayload(int length) throws Exception {
        var bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(length);
        assertThrows(IOException.class, () -> FrameCodec.readHeader(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    void rejectsTruncatedHeader() throws Exception {
        var bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(100);
        bytes.write(new byte[]{'{', '}'});
        assertThrows(IOException.class, () -> FrameCodec.readHeader(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
