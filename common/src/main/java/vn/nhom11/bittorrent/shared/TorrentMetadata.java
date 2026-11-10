package vn.nhom11.bittorrent.shared;

import java.net.URI;
import java.util.List;

public record TorrentMetadata(String fileId, String fileName, long fileSize, int chunkSize,
                              int totalChunks, List<String> chunkHashes, String trackerUrl) {
    public static final int DEFAULT_CHUNK_SIZE = 262144;
    public static final int MAX_CHUNK_SIZE = 16 * 1024 * 1024;
    public static final int MAX_CHUNKS = 100_000;
    public TorrentMetadata {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255
                || fileName.equals(".") || fileName.equals("..")
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains(":")
                || fileName.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("fileName must be a plain file name");
        if (fileSize < 0 || chunkSize < 1 || chunkSize > MAX_CHUNK_SIZE)
            throw new IllegalArgumentException("Invalid fileSize or chunkSize");
        long expected = fileSize == 0 ? 0 : 1 + (fileSize - 1) / chunkSize;
        if (totalChunks != expected || totalChunks > MAX_CHUNKS)
            throw new IllegalArgumentException("Invalid totalChunks");
        if (!Hashing.fileId(fileName, fileSize, chunkSize).equals(fileId))
            throw new IllegalArgumentException("fileId does not match name, size and chunk size");
        if (chunkHashes == null || chunkHashes.size() != totalChunks
                || chunkHashes.stream().anyMatch(h -> h == null || !h.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid chunkHashes");
        chunkHashes = List.copyOf(chunkHashes);
        if (trackerUrl == null) throw new IllegalArgumentException("trackerUrl is required");
        URI uri = URI.create(trackerUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null)
            throw new IllegalArgumentException("trackerUrl must be an HTTP(S) URL");
    }
    public int chunkLength(int index) {
        if (index < 0 || index >= totalChunks) throw new IllegalArgumentException("Invalid chunk index");
        return (int) Math.min(chunkSize, fileSize - (long) index * chunkSize);
    }
}
