package vn.nhom11.bittorrent.peer.model;
import java.time.Duration;

public record DownloadSettings(int connectTimeoutMs, int readTimeoutMs, Duration cooldown,
                               Duration swarmRefresh, Duration totalTimeout) {
    public DownloadSettings {
        if (connectTimeoutMs < 1 || readTimeoutMs < 1 || cooldown == null || cooldown.isNegative()
                || swarmRefresh == null || swarmRefresh.isNegative() || swarmRefresh.isZero()
                || totalTimeout == null || totalTimeout.isNegative() || totalTimeout.isZero())
            throw new IllegalArgumentException("Invalid download timeouts");
    }
    public static DownloadSettings defaults() {
        return new DownloadSettings(3000, 5000, Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(120));
    }
}
