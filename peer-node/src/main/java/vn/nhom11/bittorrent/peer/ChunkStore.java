package vn.nhom11.bittorrent.peer;

import vn.nhom11.bittorrent.shared.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Owns local files and publishes only fully written, verified chunks. */
public final class ChunkStore implements AutoCloseable {
    private final Path root;
    private final Map<String, StoredFile> files = new ConcurrentHashMap<>();
    private boolean closed;

    public ChunkStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    public synchronized StoredFile prepare(TorrentMetadata metadata) throws IOException {
        return prepare(metadata, true);
    }
    private synchronized StoredFile prepare(TorrentMetadata metadata, boolean shareable) throws IOException {
        if (closed) throw new IOException("Store is closed");
        StoredFile existing = files.get(metadata.fileId());
        if (existing != null) {
            if (!existing.metadata().equals(metadata)) throw new IOException("Conflicting local metadata");
            return existing;
        }
        Path directory = root.resolve(metadata.fileId());
        Files.createDirectories(directory);
        // Never resolve the untrusted fileName into a path; metadata retains the display name.
        Path path = directory.resolve("content.bin");
        Files.createFile(path); // No truncation or overwrite, including files from earlier runs.
        try {
            try (var file = new RandomAccessFile(path.toFile(), "rw")) { file.setLength(metadata.fileSize()); }
            FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
            var entry = new StoredFile(metadata, path, channel, shareable);
            files.put(metadata.fileId(), entry);
            return entry;
        } catch (IOException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    public StoredFile importSeed(Path source, int chunkSize, String trackerUrl) throws IOException {
        return importSeed(source, chunkSize, trackerUrl, true);
    }
    public StoredFile stageSeed(Path source, int chunkSize, String trackerUrl) throws IOException {
        return importSeed(source, chunkSize, trackerUrl, false);
    }
    private StoredFile importSeed(Path source, int chunkSize, String trackerUrl, boolean shareable) throws IOException {
        TorrentMetadata metadata = TorrentGenerator.generate(source, chunkSize, trackerUrl);
        StoredFile entry = prepare(metadata, shareable);
        try (var input = Files.newInputStream(source)) {
            for (int i = 0; i < metadata.totalChunks(); i++)
                entry.writeVerified(i, input.readNBytes(metadata.chunkLength(i)));
            if (input.read() != -1) throw new IOException("Seed changed while importing");
        } catch (IOException | RuntimeException error) {
            try { discardUnpublished(entry); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        return entry;
    }
    public synchronized void discardUnpublished(StoredFile entry) throws IOException {
        if (entry.shareable() || !files.remove(entry.metadata().fileId(), entry)) return;
        if (!entry.path().normalize().startsWith(root)) throw new IOException("Storage path escaped root");
        entry.channel.close();
        Files.deleteIfExists(entry.path());
        Files.deleteIfExists(entry.path().resolveSibling("metadata.torrent.json"));
        Files.deleteIfExists(entry.path().getParent());
    }

    public StoredFile get(String fileId) { return files.get(fileId); }
    public Collection<StoredFile> all() { return files.values().stream().filter(StoredFile::shareable).toList(); }

    @Override public synchronized void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (var file : files.values()) try { file.channel.close(); }
        catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }

    public static final class StoredFile {
        private final TorrentMetadata metadata;
        private final Path path;
        private final FileChannel channel;
        private final BitSet available = new BitSet();
        private volatile boolean shareable;

        private StoredFile(TorrentMetadata metadata, Path path, FileChannel channel, boolean shareable) {
            this.metadata = metadata; this.path = path; this.channel = channel;
            this.shareable = shareable;
        }
        public boolean shareable() { return shareable; }
        public void announce() { shareable = true; }
        public TorrentMetadata metadata() { return metadata; }
        public Path path() { return path; }
        public synchronized Set<Integer> availableChunks() {
            Set<Integer> result = new HashSet<>();
            available.stream().forEach(result::add);
            return Set.copyOf(result);
        }
        public synchronized boolean complete() { return available.cardinality() == metadata.totalChunks(); }
        public synchronized boolean hasChunk(int index) { return index >= 0 && available.get(index); }

        public void writeVerified(int index, byte[] bytes) throws IOException {
            if (bytes.length != metadata.chunkLength(index)
                    || !Hashing.sha256(bytes).equals(metadata.chunkHashes().get(index)))
                throw new IOException("Chunk length/hash mismatch");
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            long offset = (long) index * metadata.chunkSize();
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer, offset);
                if (written <= 0) throw new IOException("No progress writing chunk");
                offset += written;
            }
            synchronized (this) { available.set(index); }
        }
        public byte[] readChunk(int index) throws IOException {
            if (!hasChunk(index)) throw new FileNotFoundException("Chunk not available");
            byte[] bytes = new byte[metadata.chunkLength(index)];
            try (var file = new RandomAccessFile(path.toFile(), "r")) {
                file.seek((long) index * metadata.chunkSize());
                file.readFully(bytes);
            }
            return bytes;
        }
    }
}
