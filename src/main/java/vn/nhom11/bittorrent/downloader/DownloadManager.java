package vn.nhom11.bittorrent.downloader;

import vn.nhom11.bittorrent.common.Hashes;
import vn.nhom11.bittorrent.model.Manifest;
import vn.nhom11.bittorrent.model.PeerEndpoint;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Coordinator owns committed chunk state. Workers only fetch and verify private temporary files. */
public final class DownloadManager {
    private final DownloadOptions options;
    private final Consumer<DownloadEvent> events;

    public DownloadManager(DownloadOptions options, Consumer<DownloadEvent> events) {
        this.options = options;
        this.events = events;
    }

    public Path download(Manifest manifest, PeerSource sources, Path requestedOutput) throws IOException {
        Path output = requestedOutput.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());
        Files.createDirectories(output.getParent());
        try (Session session = new Session(output.getParent())) {
            var completions = new ExecutorCompletionService<ChunkResult>(session.executor);
            Path[] chunks = new Path[manifest.chunkCount()];
            int next = 0;
            int completed = 0;
            while (completed < manifest.chunkCount()) {
                // Submit only when there is capacity, keeping queued work bounded by workers.
                while (next < manifest.chunkCount() && session.pending.size() < options.workers()) {
                    int index = next++;
                    List<PeerEndpoint> candidates = List.copyOf(sources.sourcesFor(manifest, index));
                    if (candidates.isEmpty()) throw new IOException("No source for chunk " + index);
                    session.pending.add(completions.submit(() -> fetchWithFallback(session, manifest, index, candidates)));
                }
                ChunkResult result;
                try {
                    Future<ChunkResult> future = completions.take();
                    session.pending.remove(future);
                    result = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Download interrupted");
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException io) throw io;
                    throw new IOException("Chunk worker failed", e.getCause());
                }
                Path committed = session.workspace.resolve("chunk-" + result.index() + ".bin");
                Files.move(result.temporary(), committed);
                chunks[result.index()] = committed;
                completed++;
                events.accept(new DownloadEvent("CHUNK_COMMITTED", result.index(), result.peer().peerId(), "",
                        "verified_chunks=" + completed + "/" + manifest.chunkCount()));
            }

            Path assembled = session.workspace.resolve("assembled.part");
            try (var sink = Files.newOutputStream(assembled)) {
                for (Path chunk : chunks) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Assembly interrupted");
                    try (var input = Files.newInputStream(chunk)) {
                        input.transferTo(sink);
                    }
                }
            }
            if (Files.size(assembled) != manifest.fileSize()
                    || !Hashes.sha256(assembled).equals(manifest.fileHash())) {
                throw new IOException("Final file size or SHA-256 mismatch");
            }
            // Without REPLACE_EXISTING: do not overwrite a file created while the download was running.
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download interrupted before publishing");
            Files.move(assembled, output);
            return output;
        }
    }

    private ChunkResult fetchWithFallback(Session session, Manifest manifest, int index,
                                           List<PeerEndpoint> candidates) throws IOException {
        IOException failure = new IOException("All sources failed for chunk " + index);
        for (int offset = 0; offset < candidates.size(); offset++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
            PeerEndpoint peer = candidates.get((index + offset) % candidates.size());
            try {
                Path temporary = session.client.fetchChunk(manifest, index, peer, session.workspace);
                return new ChunkResult(index, peer, temporary);
            } catch (IOException e) {
                failure.addSuppressed(e);
                events.accept(new DownloadEvent("SOURCE_FAILED", index, peer.peerId(), "", e.getMessage()));
            }
        }
        // Milestone 1: one attempt per listed source; refresh/backoff belongs to the tracker milestone.
        throw failure;
    }

    private record ChunkResult(int index, PeerEndpoint peer, Path temporary) { }

    private final class Session implements AutoCloseable {
        final Path workspace;
        final PeerClient client = new PeerClient(options, events);
        final ExecutorService executor = Executors.newFixedThreadPool(options.workers());
        final Set<Future<ChunkResult>> pending = new HashSet<>();

        Session(Path parent) throws IOException {
            workspace = Files.createTempDirectory(parent, ".bittorrent-download-");
        }

        @Override
        public void close() throws IOException {
            for (Future<?> future : pending) future.cancel(true);
            client.close();
            executor.shutdownNow();
            boolean interrupted = Thread.interrupted();
            boolean stopped = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (!stopped && System.nanoTime() < deadline) {
                    try {
                        stopped = executor.awaitTermination(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (!stopped) throw new IOException("Workers did not stop; retained temporary files in " + workspace);
                // Only remove this session's private directory after all writers have stopped.
                List<Path> paths;
                try (var walk = Files.walk(workspace)) {
                    paths = walk.sorted(Comparator.reverseOrder()).toList();
                }
                IOException failure = null;
                for (Path path : paths) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        if (failure == null) failure = e;
                        else failure.addSuppressed(e);
                    }
                }
                if (failure != null) throw failure;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
