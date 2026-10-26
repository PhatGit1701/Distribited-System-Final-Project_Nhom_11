package vn.nhom11.bittorrent.model;

import vn.nhom11.bittorrent.common.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Proposed v1 model, shared with Student 1 when integrating the tracker. */
public record Manifest(int version, String filename, long fileSize, int chunkSize,
                       int chunkCount, String hashAlgorithm, List<String> chunkHashes,
                       String fileHash, String fileId, String trackerEndpoint) {
    public static final int MAX_CHUNK_BYTES = 16 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;

    public Manifest {
        if (version != 1) throw new IllegalArgumentException("Unsupported manifest version");
        if (filename == null || filename.isBlank() || filename.equals(".") || filename.equals("..")
                || filename.contains("/") || filename.contains("\\") || filename.contains(":")) {
            throw new IllegalArgumentException("filename must be a plain file name");
        }
        if (fileSize < 0 || chunkSize < 1 || chunkSize > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException("Invalid file_size or chunk_size (max 16 MiB)");
        }
        long expectedCount = fileSize == 0 ? 0 : 1 + (fileSize - 1) / chunkSize;
        if (chunkCount != expectedCount || chunkCount > 100_000) {
            throw new IllegalArgumentException("chunk_count does not match file_size/chunk_size");
        }
        if (!"SHA-256".equals(hashAlgorithm)) throw new IllegalArgumentException("Expected SHA-256");
        if (chunkHashes == null || chunkHashes.size() != chunkCount) {
            throw new IllegalArgumentException("chunk_hashes count does not match chunk_count");
        }
        for (String hash : chunkHashes) requireHash(hash);
        chunkHashes = List.copyOf(chunkHashes);
        requireHash(fileHash);
        if (!fileHash.equals(fileId)) throw new IllegalArgumentException("file_id must equal file_hash");
        if (trackerEndpoint == null || trackerEndpoint.isBlank()) {
            throw new IllegalArgumentException("tracker_endpoint is required (not yet queried in milestone 1)");
        }
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected lowercase SHA-256 hex digest");
        }
    }

    public int chunkLength(int index) {
        if (index < 0 || index >= chunkCount) throw new IllegalArgumentException("Invalid chunk index");
        return (int) Math.min(chunkSize, fileSize - (long) index * chunkSize);
    }

    public static Manifest read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Manifest exceeds 4 MiB");
            return Json.MAPPER.readValue(bytes, Manifest.class);
        }
    }
}
