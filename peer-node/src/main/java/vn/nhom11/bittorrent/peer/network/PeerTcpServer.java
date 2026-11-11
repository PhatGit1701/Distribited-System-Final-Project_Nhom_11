package vn.nhom11.bittorrent.peer.network;
import vn.nhom11.bittorrent.peer.repository.ChunkStore;
import vn.nhom11.bittorrent.peer.service.TitForTatManager;

import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One accept dispatcher, sixteen workers and a bounded pending-connection queue. */
public final class PeerTcpServer implements Runnable, AutoCloseable {
    private final int requestedPort;
    private final ChunkStore store;
    private final TitForTatManager policy;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers;
    private volatile boolean running;
    private ServerSocket listener;
    private Thread dispatcher;

    public PeerTcpServer(int port, ChunkStore store, TitForTatManager policy) {
        this.requestedPort = port; this.store = store; this.policy = policy;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
                r -> new Thread(r, "peer-upload-" + sequence.incrementAndGet()), new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized void start() throws IOException {
        if (running) return;
        if (workers.isShutdown()) throw new IOException("Server is closed");
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(requestedPort)); }
        catch (IOException e) { listener.close(); throw e; }
        running = true;
        dispatcher = new Thread(this, "peer-tcp-dispatcher");
        dispatcher.start();
    }
    public synchronized int port() { return listener == null ? requestedPort : listener.getLocalPort(); }
    @Override public void run() {
        while (running) try {
            Socket socket = listener.accept();
            clients.add(socket);
            try {
                socket.setSoTimeout(5000);
                workers.execute(() -> {
                    try { new PeerConnectionWorker(socket, store, policy).run(); }
                    finally { clients.remove(socket); }
                });
            } catch (IOException | RejectedExecutionException e) {
                clients.remove(socket);
                try { socket.close(); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            if (running) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Accept failed", e);
            break;
        }
    }
    @Override public void close() throws IOException {
        Thread thread;
        synchronized (this) {
            running = false;
            if (listener != null) listener.close();
            thread = dispatcher;
        }
        try {
            if (thread != null) thread.join(5000);
            for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) {}
            workers.shutdownNow();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) throw new IOException("Upload workers did not stop");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted closing server");
        }
    }
}
