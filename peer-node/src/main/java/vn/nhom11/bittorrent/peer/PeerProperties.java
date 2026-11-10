package vn.nhom11.bittorrent.peer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.nio.file.Path;
import java.util.UUID;

@ConfigurationProperties("peer")
public record PeerProperties(@DefaultValue("") String id, @DefaultValue("") String username,
                             @DefaultValue("") String password, @DefaultValue("") String token,
                             @DefaultValue("http://localhost:8080") String trackerUrl,
                             @DefaultValue("127.0.0.1") String ipAddress,
                             @DefaultValue Tcp tcp, @DefaultValue("peer-storage") Path storage,
                             @DefaultValue("") String seedFile, @DefaultValue("") String downloadFileId,
                             @DefaultValue("false") boolean registerUser,
                             @DefaultValue("120") long downloadTimeoutSeconds) {
    public record Tcp(@DefaultValue("6881") int port) {}
    public PeerProperties {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (!id.matches("[a-zA-Z0-9_-]{1,128}") || username == null
                || tcp == null || tcp.port() < 0 || tcp.port() > 65535 || downloadTimeoutSeconds < 1)
            throw new IllegalArgumentException("Configure peer.username, valid peer.id, peer.tcp.port and timeout");
    }
}
