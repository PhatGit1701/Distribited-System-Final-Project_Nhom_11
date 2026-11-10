package vn.nhom11.bittorrent.downloader;

public record DownloadOptions(int workers, int connectTimeoutMs, int readTimeoutMs, int attemptTimeoutMs) {
    public DownloadOptions {
        if (workers < 1 || workers > 16) throw new IllegalArgumentException("workers must be between 1 and 16");
        if (connectTimeoutMs < 1 || readTimeoutMs < 1 || attemptTimeoutMs < 1) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
    }

    public static DownloadOptions defaults(int workers) {
        return new DownloadOptions(workers, 2_000, 5_000, 30_000);
    }
}
