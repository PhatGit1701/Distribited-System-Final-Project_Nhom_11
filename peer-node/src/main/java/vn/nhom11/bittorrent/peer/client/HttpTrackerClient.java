package vn.nhom11.bittorrent.peer.client;
import vn.nhom11.bittorrent.peer.exception.TrackerException;
import vn.nhom11.bittorrent.peer.model.PeerIdentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.nhom11.bittorrent.shared.*;
import vn.nhom11.bittorrent.shared.exception.ErrorCode;
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
    @Override public void reportBadChunk(String fileId,String peerId,int chunkIndex,String expectedHash,String actualHash) throws IOException {
        requireSuccess(send("/api/reports",json.writeValueAsBytes(java.util.Map.of(
                "fileId",fileId,"peerId",peerId,"chunkIndex",chunkIndex,"expectedHash",expectedHash,"actualHash",actualHash)),true));
    }
    @Override public SourcePlan sources(String fileId, String peerId, java.util.Set<Integer> availableChunks, int maxSources) throws IOException {
        requireFileId(fileId);
        var response=send("/api/tracker/sources/"+fileId,json.writeValueAsBytes(java.util.Map.of(
                "peerId",peerId,"availableChunks",availableChunks,"maxSources",maxSources)),true);
        requireSuccess(response);
        return json.readValue(response.body(),SourcePlan.class);
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
            if (token == null || token.isBlank()) throw new TrackerException(401, "AUTHENTICATION_REQUIRED", "Tracker login required");
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        try { return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()); }
        catch (IOException e) {
            lastError = "Tracker could not be reached";
            var failure = new TrackerException(502, "TRACKER_UNAVAILABLE", lastError);
            failure.initCause(e); throw failure;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Tracker request interrupted");
        }
    }
    private void requireSuccess(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) { lastError = "Tracker HTTP " + response.statusCode(); throw failure(response); }
        lastSuccess = Instant.now(); lastError = null;
    }
    private TrackerException failure(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        String code = status >= 500 ? "TRACKER_ERROR" : ErrorCode.forStatus(status).name();
        String message = status >= 500 ? "Tracker request failed" : ErrorCode.forStatus(status).message();
        // Never forward raw HTML or server traces from a failed upstream response.
        try {
            var body = json.readTree(response.body());
            if (body != null && body.path("code").isTextual() && body.path("message").isTextual()
                    && !body.path("code").asText().isBlank() && !body.path("message").asText().isBlank()) {
                code = body.path("code").asText(); message = body.path("message").asText();
            }
        } catch (IOException ignored) { /* Non-JSON upstream response: use safe fallback. */ }
        return new TrackerException(status >= 400 && status <= 599 ? status : 502, code, message);
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static void requireFileId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid fileId");
    }
    @Override public void close() { http.close(); }
}
