package vn.nhom11.bittorrent.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class TorrentGenerator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_METADATA_BYTES = 8 * 1024 * 1024;
    private TorrentGenerator() {}
    public static TorrentMetadata generate(Path file, String trackerUrl) throws IOException {
        return generate(file, TorrentMetadata.DEFAULT_CHUNK_SIZE, trackerUrl);
    }
    public static TorrentMetadata generate(Path file, int chunkSize, String trackerUrl) throws IOException {
        if (chunkSize < 1 || chunkSize > TorrentMetadata.MAX_CHUNK_SIZE)
            throw new IllegalArgumentException("Invalid chunkSize");
        long size = Files.size(file);
        if (size > (long) TorrentMetadata.MAX_CHUNKS * chunkSize)
            throw new IllegalArgumentException("Too many chunks");
        List<String> hashes = new ArrayList<>();
        long bytesRead = 0;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] chunk;
            while ((chunk = input.readNBytes(chunkSize)).length != 0) {
                bytesRead += chunk.length;
                if (bytesRead > size) throw new IOException("File changed while hashing");
                hashes.add(Hashing.sha256(chunk));
            }
        }
        if (bytesRead != size) throw new IOException("File changed while hashing");
        String name = file.getFileName().toString();
        return new TorrentMetadata(Hashing.fileId(name, size, chunkSize), name, size, chunkSize,
                hashes.size(), hashes, trackerUrl);
    }
    public static void write(TorrentMetadata metadata, Path output) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), metadata);
    }
    public static TorrentMetadata read(Path input) throws IOException {
        try (var stream = Files.newInputStream(input)) {
            byte[] data = stream.readNBytes(MAX_METADATA_BYTES + 1);
            if (data.length > MAX_METADATA_BYTES) throw new IOException("Metadata exceeds 8 MiB");
            return JSON.readValue(data, TorrentMetadata.class);
        }
    }
}
