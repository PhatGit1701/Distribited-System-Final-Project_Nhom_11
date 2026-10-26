package vn.nhom11.bittorrent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.nhom11.bittorrent.common.Json;
import vn.nhom11.bittorrent.model.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ManifestTest {
    @TempDir Path directory;

    @Test
    void readsSnakeCaseAndComputesShortLastChunk() throws Exception {
        Manifest original = TestFixtures.fixture(2171, 1024).manifest();
        Path file = directory.resolve("manifest.json");
        Json.MAPPER.writeValue(file.toFile(), original);
        assertTrue(Files.readString(file).contains("chunk_size"));
        Manifest parsed = Manifest.read(file);
        assertEquals(original, parsed);
        assertEquals(1024, parsed.chunkLength(0));
        assertEquals(123, parsed.chunkLength(2));
        assertThrows(IllegalArgumentException.class, () -> parsed.chunkLength(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing_size", "negative_size", "wrong_count", "wrong_hash", "wrong_id",
            "path_filename", "huge_chunk", "version", "float_size", "string_size", "null_size", "duplicate"})
    void rejectsMalformedManifest(String variant) throws Exception {
        ObjectNode node = Json.MAPPER.valueToTree(TestFixtures.fixture(10, 1024).manifest());
        switch (variant) {
            case "missing_size" -> node.remove("file_size");
            case "negative_size" -> node.put("file_size", -1);
            case "wrong_count" -> node.put("chunk_count", 3);
            case "wrong_hash" -> node.put("file_hash", "bad");
            case "wrong_id" -> node.put("file_id", "0".repeat(64));
            case "path_filename" -> node.put("filename", "../escape.bin");
            case "huge_chunk" -> node.put("chunk_size", Integer.MAX_VALUE);
            case "version" -> node.put("version", 2);
            case "float_size" -> node.put("file_size", 10.5);
            case "string_size" -> node.put("file_size", "10");
            case "null_size" -> node.putNull("file_size");
            default -> { }
        }
        String json = Json.MAPPER.writeValueAsString(node);
        if (variant.equals("duplicate")) json = json.substring(0, json.length() - 1) + ",\"version\":1}";
        Path file = directory.resolve("bad.json");
        Files.writeString(file, json);
        assertThrows(IOException.class, () -> Manifest.read(file));
    }
}
