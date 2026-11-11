package vn.nhom11.bittorrent.tracker.service;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.nhom11.bittorrent.shared.TorrentMetadata;

/** PostgreSQL is consulted once on startup, never from the heartbeat path. */
@Component
public class SwarmMetadataCache implements ApplicationRunner {
    public record Registered(TorrentMetadata metadata) {}
    private final TorrentRegistry registry;
    private final SwarmRepository swarms;
    public SwarmMetadataCache(TorrentRegistry registry,SwarmRepository swarms) {this.registry=registry;this.swarms=swarms;}
    @Override public void run(ApplicationArguments args) {registry.list().forEach(this::cache);}
    @EventListener public void registered(Registered event) {cache(event.metadata());}
    private void cache(TorrentMetadata metadata) {swarms.registerMetadata(metadata.fileId(),metadata.totalChunks());}
}
