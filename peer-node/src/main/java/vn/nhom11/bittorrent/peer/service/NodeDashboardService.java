package vn.nhom11.bittorrent.peer.service;
import vn.nhom11.bittorrent.peer.event.NodeEventHub;
import vn.nhom11.bittorrent.peer.client.HttpTrackerClient;

import org.springframework.stereotype.Service;
import vn.nhom11.bittorrent.shared.*;
import vn.nhom11.bittorrent.shared.exception.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NodeDashboardService implements AutoCloseable {
    private record Job(String fileId, AtomicBoolean cancelled) {}
    private final PeerRuntime runtime;
    private final HttpTrackerClient tracker;
    private final ParallelDownloadManager downloader;
    private final NodeEventHub events;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "node-download-coordinator"));
    private Job job;

    public NodeDashboardService(PeerRuntime runtime, HttpTrackerClient tracker, ParallelDownloadManager downloader, NodeEventHub events) {
        this.runtime = runtime; this.tracker = tracker; this.downloader = downloader; this.events = events;
    }
    public synchronized NodeEventHub.DownloadView start(String fileId) throws IOException {
        if (job != null) throw new AppException(ErrorCode.DOWNLOAD_CONFLICT, "Another download is active");
        TorrentMetadata metadata = tracker.metadata(fileId);
        Job next = new Job(fileId, new AtomicBoolean());
        NodeEventHub.DownloadView view = events.begin(metadata);
        job = next;
        executor.submit(() -> {
            try {
                if (next.cancelled().get()) throw new InterruptedIOException("Download cancelled");
                events.status(fileId, "DOWNLOADING", null);
                runtime.download(fileId, next.cancelled()::get);
                events.status(fileId, "COMPLETED", null);
            } catch (IOException | RuntimeException e) {
                events.status(fileId, next.cancelled().get() ? "CANCELLED" : "FAILED", e.getMessage());
            } finally {
                synchronized (this) { if (job == next) job = null; }
            }
        });
        return view;
    }
    public synchronized void cancel(String fileId) {
        if (job == null || !job.fileId().equals(fileId)) throw new AppException(ErrorCode.DOWNLOAD_CONFLICT, "This download is not active");
        job.cancelled().set(true); downloader.cancel();
    }
    public synchronized boolean active() { return job != null; }
    @Override public void close() {
        synchronized (this) { if (job != null) { job.cancelled().set(true); downloader.cancel(); } }
        executor.shutdown();
        try { if (!executor.awaitTermination(15, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
