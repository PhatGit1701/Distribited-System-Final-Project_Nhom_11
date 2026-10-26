package vn.nhom11.bittorrent.downloader;

/** Events originate on worker/coordinator threads; listeners must be thread-safe and non-blocking. */
public record DownloadEvent(String type, int chunkIndex, String peerId, String requestId, String detail) { }
