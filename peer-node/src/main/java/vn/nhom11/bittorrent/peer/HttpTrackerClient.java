package vn.nhom11.bittorrent.peer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.nhom11.bittorrent.shared.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class HttpTrackerClient implements TrackerGateway, AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final String base;
    private volatile String token;
    private volatile Instant lastSuccess;
    private volatile String lastError;
    private volatile List<TorrentMetadata> cachedDirectory = List.of();
    public List<TorrentMetadata> cachedList() { return cachedDirectory; }
    public record Health(boolean healthy, Instant lastSuccess, String error) {}
    public Health health() {
        return new Health(lastSuccess != null && lastError == null && lastSuccess.isAfter(Instant.now().minusSeconds(20)),
                lastSuccess, lastError);
    }

    public HttpTrackerClient(String base, String token) {
        URI uri = URI.create(base);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid tracker URL");
        this.base = base.replaceAll("/+$", ""); this.token = token;
    }
    public void authenticate(String username, String password, boolean register) throws IOException {
        if (token != null && !token.isBlank()) return;
        byte[] credentials = json.writeValueAsBytes(new AuthRequest(username, password));
        if (register) {
            var response = send("/api/auth/register", credentials, false);
            if (response.statusCode() == 201) {
                token = json.readValue(response.body(), AuthResponse.class).token(); return;
            }
            if (response.statusCode() != 409) throw failure(response);
        }
        var response = send("/api/auth/login", credentials, false);
        requireSuccess(response);
        token = json.readValue(response.body(), AuthResponse.class).token();
    }
    public synchronized AuthResponse login(String username, String password, boolean register) throws IOException {
        var response = send(register ? "/api/auth/register" : "/api/auth/login",
                json.writeValueAsBytes(new AuthRequest(username, password)), false);
        requireSuccess(response);
        AuthResponse auth = json.readValue(response.body(), AuthResponse.class);
        if (!username.equals(auth.username()) || auth.token() == null || auth.token().isBlank())
            throw new IOException("Tracker returned invalid authentication");
        token = auth.token();
        return auth;
    }
    public List<TorrentMetadata> list() throws IOException {
        var response = send("/api/tracker/torrents", null, true);
        requireSuccess(response);
        cachedDirectory = json.readValue(response.body(), new TypeReference<>() {});
        return cachedDirectory;
    }
    @Override public TorrentMetadata metadata(String fileId) throws IOException {
        requireFileId(fileId);
        var response = send("/api/tracker/torrents/" + fileId, null, true);
        requireSuccess(response); return json.readValue(response.body(), TorrentMetadata.class);
    }
    @Override public List<PeerNodeInfo> swarm(String fileId) throws IOException {
        requireFileId(fileId);
        var response = send("/api/tracker/swarm/" + fileId, null, true);
        requireSuccess(response); return json.readValue(response.body(), new TypeReference<>() {});
    }
    @Override public void heartbeat(HeartbeatRequest request) throws IOException {
        requireSuccess(send("/api/tracker/heartbeat", json.writeValueAsBytes(request), true));
    }
    @Override public void register(TorrentMetadata metadata, PeerIdentity identity) throws IOException {
        String query = "?peerId=" + encode(identity.peerId()) + "&ipAddress=" + encode(identity.ipAddress())
                + "&tcpPort=" + identity.tcpPort();
        requireSuccess(send("/api/tracker/torrents" + query, json.writeValueAsBytes(metadata), true));
    }
    private HttpResponse<byte[]> send(String path, byte[] body, boolean authorized) throws IOException {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json");
        if (authorized) {
            if (token == null || token.isBlank()) throw new IOException("No tracker token");
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        try { return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()); }
        catch (IOException e) { lastError = e.getMessage(); throw e; }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Tracker request interrupted");
        }
    }
    private void requireSuccess(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) { lastError = "Tracker HTTP " + response.statusCode(); throw failure(response); }
        lastSuccess = Instant.now(); lastError = null;
    }
    private IOException failure(HttpResponse<byte[]> response) {
        return new IOException("Tracker HTTP " + response.statusCode());
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static void requireFileId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid fileId");
    }
    @Override public void close() { http.close(); }
}
