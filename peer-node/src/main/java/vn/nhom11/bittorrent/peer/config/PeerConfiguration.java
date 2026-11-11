package vn.nhom11.bittorrent.peer.config;
import vn.nhom11.bittorrent.peer.event.NodeEventHub;
import vn.nhom11.bittorrent.peer.network.PeerTcpServer;
import vn.nhom11.bittorrent.peer.client.HttpTrackerClient;
import vn.nhom11.bittorrent.peer.repository.ChunkStore;
import vn.nhom11.bittorrent.peer.model.DownloadSettings;
import vn.nhom11.bittorrent.peer.model.PeerIdentity;
import vn.nhom11.bittorrent.peer.service.TitForTatManager;
import vn.nhom11.bittorrent.peer.service.ParallelDownloadManager;

import org.springframework.context.annotation.*;
import java.io.IOException;
import java.time.*;
import java.util.Random;

@Configuration
public class PeerConfiguration {
    @Bean(destroyMethod = "close")
    ChunkStore chunkStore(PeerProperties properties) throws IOException {
        return new ChunkStore(properties.storage().resolve(properties.id()));
    }
    @Bean TitForTatManager titForTatManager() { return new TitForTatManager(Clock.systemUTC(), new Random()); }
    @Bean(initMethod = "start", destroyMethod = "close")
    PeerTcpServer peerTcpServer(PeerProperties properties, ChunkStore store, TitForTatManager policy) {
        return new PeerTcpServer(properties.tcp().port(), store, policy);
    }
    @Bean PeerIdentity peerIdentity(PeerProperties properties, PeerTcpServer server) {
        return new PeerIdentity(properties.id(), properties.username().isBlank() ? "guest" : properties.username(), properties.ipAddress(), server.port());
    }
    @Bean HttpTrackerClient trackerClient(PeerProperties properties) {
        return new HttpTrackerClient(properties.trackerUrl(), properties.token());
    }
    @Bean(destroyMethod = "close")
    ParallelDownloadManager parallelDownloadManager(PeerIdentity identity, ChunkStore store,
                                                     HttpTrackerClient tracker, TitForTatManager policy, PeerProperties properties, NodeEventHub events) {
        var defaults = DownloadSettings.defaults();
        var settings = new DownloadSettings(defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.cooldown(),
                defaults.swarmRefresh(), Duration.ofSeconds(properties.downloadTimeoutSeconds()));
        var logger = System.getLogger("peer.events");
        return ParallelDownloadManager.withFileEvents(identity, store, tracker, policy, settings, (fileId, event) -> {
                events.chunk(fileId, event);
                logger.log(System.Logger.Level.INFO, "chunk=" + event.chunkIndex() + " " + event.status()
                        + " from=" + event.sourceUsername() + "@" + event.sourceEndpoint() + " attempts="
                        + event.attemptCount() + " history=" + event.failoverHistory());
        });
    }
}
