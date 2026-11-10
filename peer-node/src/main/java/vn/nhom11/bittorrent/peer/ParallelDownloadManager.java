package vn.nhom11.bittorrent.peer;

import vn.nhom11.bittorrent.shared.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import static vn.nhom11.bittorrent.peer.ChunkDownloadState.Status.*;
import static vn.nhom11.bittorrent.shared.WireProtocol.*;

/** A bounded five-worker scheduler; one active file per node in this phase. */
public final class ParallelDownloadManager implements AutoCloseable {
    private volatile PeerIdentity self;
    private final ChunkStore store;
    private final TrackerGateway tracker;
    private final TitForTatManager policy;
    private final DownloadSettings settings;
    private final BiConsumer<String, ChunkDownloadState> events;
    private final Object lifecycle = new Object(), heartbeatLock = new Object();
    private volatile Session current;
    private volatile AtomicBoolean preparing;
    private volatile List<ChunkDownloadState> lastStates = List.of();
    private boolean closed;

    public ParallelDownloadManager(PeerIdentity self, ChunkStore store, TrackerGateway tracker,
                                   TitForTatManager policy, DownloadSettings settings, Consumer<ChunkDownloadState> events) {
        this(self, store, tracker, policy, settings, (fileId, event) -> events.accept(event));
    }
    private ParallelDownloadManager(PeerIdentity self, ChunkStore store, TrackerGateway tracker,
                                   TitForTatManager policy, DownloadSettings settings, BiConsumer<String, ChunkDownloadState> events) {
        this.self = self; this.store = store; this.tracker = tracker; this.policy = policy;
        this.settings = settings; this.events = events;
    }
    public static ParallelDownloadManager withFileEvents(PeerIdentity self, ChunkStore store, TrackerGateway tracker,
                                   TitForTatManager policy, DownloadSettings settings, BiConsumer<String, ChunkDownloadState> events) {
        return new ParallelDownloadManager(self, store, tracker, policy, settings, events);
    }
    public void identity(PeerIdentity identity) {
        synchronized (lifecycle) {
            if (current != null || preparing != null) throw new IllegalStateException("Cannot change identity while downloading");
            self = identity;
        }
    }
    public List<ChunkDownloadState> states() {
        Session session = current;
        return session == null ? lastStates : session.snapshot();
    }

    public Path download(String fileId) throws IOException {
        return download(fileId, () -> false);
    }
    public Path download(String fileId, BooleanSupplier cancellation) throws IOException {
        AtomicBoolean preparation = new AtomicBoolean();
        synchronized (lifecycle) {
            if (cancellation.getAsBoolean()) throw new InterruptedIOException("Download cancelled");
            if (closed) throw new IOException("Downloader is closed");
            if (current != null || preparing != null) throw new IOException("A download is already active");
            preparing = preparation;
        }
        Session session;
        try {
            TorrentMetadata metadata = tracker.metadata(fileId);
            if (!metadata.fileId().equals(fileId)) throw new IOException("Tracker returned wrong fileId");
            synchronized (lifecycle) {
                if (preparation.get() || closed || cancellation.getAsBoolean()) throw new InterruptedIOException("Download cancelled during metadata lookup");
                session = new Session(store.prepare(metadata), cancellation);
                current = session;
                preparing = null;
            }
        } catch (IOException | RuntimeException e) {
            synchronized (lifecycle) { preparing = null; lifecycle.notifyAll(); }
            throw e;
        }
        try {
            session.refresh();
            advertise(session.file);
            session.initialize();
            for (int i = 0; i < 5; i++) session.completion.submit(() -> { session.work(); return null; });
            long refreshAt = System.nanoTime() + settings.swarmRefresh().toNanos();
            while (session.completed.get() < session.file.metadata().totalChunks()) {
                session.checkActive();
                Future<Void> finished = session.completion.poll(50, TimeUnit.MILLISECONDS);
                if (finished != null) finished.get(); // Propagate disk errors and unexpected worker failures.
                if (System.nanoTime() >= refreshAt) {
                    session.refresh();
                    refreshAt = System.nanoTime() + settings.swarmRefresh().toNanos();
                }
            }
            return session.file.path();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Download interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Download worker failed", e.getCause());
        } finally {
            session.stop();
            // Join even if the caller was interrupted; restore its flag afterwards.
            boolean interrupted = Thread.interrupted();
            try {
                if (!session.workers.awaitTermination(10, TimeUnit.SECONDS))
                    throw new IOException("Download workers did not terminate");
            } catch (InterruptedException e) { interrupted = true; throw new InterruptedIOException("Shutdown interrupted"); }
            finally {
                lastStates = session.snapshot();
                synchronized (lifecycle) { current = null; lifecycle.notifyAll(); }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Snapshot acquisition and transmission share a lock, preventing stale heartbeat rollback. */
    public void advertise(ChunkStore.StoredFile file) {
        advertise(file, () -> true);
    }
    private void advertise(ChunkStore.StoredFile file, BooleanSupplier active) {
        synchronized (heartbeatLock) {
            if (!active.getAsBoolean()) return;
            try {
                tracker.heartbeat(new HeartbeatRequest(self.peerId(), self.username(), self.ipAddress(), self.tcpPort(),
                        file.metadata().fileId(), file.availableChunks()));
            } catch (IOException e) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Heartbeat failed: " + e.getMessage());
            }
        }
    }
    public void cancel() {
        synchronized (lifecycle) {
            if (preparing != null) preparing.set(true);
            if (current != null) current.stop();
        }
    }
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        synchronized (lifecycle) {
            while (current != null || preparing != null) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lifecycle, remaining);
            }
            return true;
        }
    }
    @Override public void close() {
        synchronized (lifecycle) { closed = true; }
        cancel();
        try {
            if (!awaitIdle(Duration.ofSeconds(15))) throw new IllegalStateException("Download did not stop");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private final class Session {
        final ChunkStore.StoredFile file;
        final BooleanSupplier cancellation;
        final ExecutorService workers = Executors.newFixedThreadPool(5, r -> new Thread(r, "peer-download-worker"));
        final CompletionService<Void> completion = new ExecutorCompletionService<>(workers);
        final BlockingDeque<Integer> queue = new LinkedBlockingDeque<>();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicBoolean stopped = new AtomicBoolean();
        final long deadline = System.nanoTime() + settings.totalTimeout().toNanos();
        final Object selection = new Object();
        final Map<String, Long> cooldowns = new HashMap<>();
        final Map<String, Integer> loads = new HashMap<>();
        final List<ChunkDownloadState> chunks = new ArrayList<>();
        volatile List<PeerNodeInfo> peers = List.of();

        Session(ChunkStore.StoredFile file, BooleanSupplier cancellation) {
            this.file = file;
            this.cancellation = cancellation;
            for (int i = 0; i < file.metadata().totalChunks(); i++)
                chunks.add(new ChunkDownloadState(i, PENDING, null, null, null, 0,
                        file.metadata().chunkHashes().get(i), null, 0, List.of()));
        }
        synchronized List<ChunkDownloadState> snapshot() { return List.copyOf(chunks); }
        void initialize() {
            var missing = new ArrayList<Integer>();
            for (int i = 0; i < chunks.size(); i++) {
                if (file.hasChunk(i)) { completed.incrementAndGet(); update(i, COMPLETED, null, file.metadata().chunkHashes().get(i), 0, null); }
                else missing.add(i);
            }
            missing.sort(Comparator.<Integer>comparingLong(i -> peers.stream().filter(p -> p.availableChunks().contains(i)).count())
                    .thenComparingInt(i -> i));
            queue.addAll(missing);
        }
        void refresh() {
            try {
                peers = tracker.swarm(file.metadata().fileId()).stream()
                        .filter(p -> !p.peerId().equals(self.peerId()) && p.tcpPort() > 0 && p.tcpPort() <= 65535).toList();
            } catch (IOException e) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Swarm refresh failed: " + e.getMessage());
            }
        }
        void checkActive() throws IOException {
            if (stopped.get() || cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
            if (System.nanoTime() >= deadline) throw new SocketTimeoutException("Download deadline exceeded");
        }
        PeerNodeInfo choose(int index) {
            synchronized (selection) {
                long now = System.nanoTime();
                PeerNodeInfo peer = peers.stream().filter(p -> p.availableChunks().contains(index))
                        .filter(p -> cooldowns.getOrDefault(p.peerId(), Long.MIN_VALUE) <= now)
                        .min(Comparator.<PeerNodeInfo>comparingInt(p -> loads.getOrDefault(p.peerId(), 0))
                                .thenComparing(PeerNodeInfo::peerId)).orElse(null);
                if (peer != null) loads.merge(peer.peerId(), 1, Integer::sum);
                return peer;
            }
        }
        void work() throws IOException, InterruptedException {
            while (!stopped.get() && completed.get() < chunks.size()) {
                checkActive();
                Integer index = queue.pollFirst(100, TimeUnit.MILLISECONDS);
                if (index == null) continue;
                PeerNodeInfo peer = choose(index);
                if (peer == null) {
                    queue.offerLast(index); Thread.sleep(50); continue;
                }
                long started = System.nanoTime();
                byte[] data;
                try {
                    update(index, DOWNLOADING, peer, null, 0, null);
                    data = fetch(index, peer);
                    String hash = Hashing.sha256(data);
                    update(index, VERIFYING, peer, hash, elapsed(started), null);
                    if (!hash.equals(file.metadata().chunkHashes().get(index)))
                        throw new IOException("SHA-256 mismatch");
                } catch (IOException e) {
                    checkActive();
                    synchronized (selection) { cooldowns.put(peer.peerId(), System.nanoTime() + settings.cooldown().toNanos()); }
                    update(index, FAILED_RETRYING, peer, null, elapsed(started),
                            "WARN: " + peer.peerId() + " (" + endpoint(peer) + ") failed: " + e.getMessage() + "; switching peer");
                    queue.offerFirst(index);
                    continue;
                } finally {
                    synchronized (selection) { loads.computeIfPresent(peer.peerId(), (id, value) -> Math.max(0, value - 1)); }
                }
                checkActive();
                // Disk failure is fatal, not a remote-peer fault. FileChannel handles positional writes.
                file.writeVerified(index, data);
                policy.recordContribution(peer.peerId(), data.length);
                update(index, COMPLETED, peer, Hashing.sha256(data), elapsed(started), null);
                advertise(file, () -> !stopped.get());
                completed.incrementAndGet();
            }
        }
        byte[] fetch(int index, PeerNodeInfo peer) throws IOException {
            Socket socket = new Socket();
            sockets.add(socket);
            try (socket) {
                checkActive();
                socket.connect(new InetSocketAddress(peer.ipAddress(), peer.tcpPort()), settings.connectTimeoutMs());
                socket.setSoTimeout(settings.readTimeoutMs());
                var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                WireCodec.writeRequest(out, new WireCodec.Handshake(self.peerId(), self.username(), self.tcpPort()));
                // Report only bytes actually uploaded locally; the receiver treats reports as cumulative.
                WireCodec.writeRequest(out, new WireCodec.UploadReport(policy.uploadedTo(peer.peerId())));
                WireCodec.writeRequest(out, new WireCodec.ChunkRequest(file.metadata().fileId(), index));
                out.flush();
                var response = WireCodec.readResponse(new DataInputStream(new BufferedInputStream(socket.getInputStream())));
                if (response.status() != OK_DATA) throw new IOException("Peer status " + response.status());
                byte[] data = response.data();
                if (response.chunkIndex() != index || data.length != file.metadata().chunkLength(index))
                    throw new IOException("Unexpected chunk index/length");
                return data;
            } finally { sockets.remove(socket); }
        }
        void update(int index, ChunkDownloadState.Status status, PeerNodeInfo peer, String hash, long duration, String failure) {
            ChunkDownloadState event;
            synchronized (this) {
                var previous = chunks.get(index);
                List<String> history = new ArrayList<>(previous.failoverHistory());
                if (failure != null) history.add(failure);
                event = new ChunkDownloadState(index, status,
                        peer == null ? previous.sourcePeerId() : peer.peerId(),
                        peer == null ? previous.sourceUsername() : peer.username(),
                        peer == null ? previous.sourceEndpoint() : endpoint(peer),
                        previous.attemptCount() + (status == DOWNLOADING ? 1 : 0), previous.expectedHash(),
                        hash == null ? previous.sha256Hash() : hash, duration, history);
                chunks.set(index, event);
            }
            try { events.accept(file.metadata().fileId(), event); }
            catch (RuntimeException e) { System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Event listener failed", e); }
        }
        void stop() {
            stopped.set(true);
            for (var socket : sockets) try { socket.close(); } catch (IOException ignored) {}
            // Interrupting a FileChannel writer closes the shared channel. Closing sockets
            // unblocks network I/O; queue waits are short, so let disk writes finish normally.
            workers.shutdown();
        }
    }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static String endpoint(PeerNodeInfo p) { return p.ipAddress() + ":" + p.tcpPort(); }
}
