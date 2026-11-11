package vn.nhom11.bittorrent.peer;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import vn.nhom11.bittorrent.peer.client.HttpTrackerClient;
import vn.nhom11.bittorrent.peer.exception.TrackerException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TrackerErrorPropagationTest {
    @Test void preservesStructuredTrackerErrorInsteadOfParsingMessage() throws Exception {
        var error = loginFailure(409, "{\"code\":\"USERNAME_ALREADY_EXISTS\",\"message\":\"Username already exists\",\"status\":400}");
        assertEquals(409, error.getStatus(), "The actual HTTP status is authoritative");
        assertEquals("USERNAME_ALREADY_EXISTS", error.getCode());
        assertEquals("Username already exists", error.getMessage());
    }
    @Test void nonJsonUpstreamFailureDoesNotExposeResponseBody() throws Exception {
        var error = loginFailure(503, "<html>database password secret</html>");
        assertEquals(503, error.getStatus());
        assertEquals("TRACKER_ERROR", error.getCode());
        assertEquals("Tracker request failed", error.getMessage());
    }
    @Test void emptyUnauthorizedBodyRetainsStatus() throws Exception {
        var error = loginFailure(401, "");
        assertEquals(401, error.getStatus());
        assertEquals("AUTHENTICATION_REQUIRED", error.getCode());
    }
    private TrackerException loginFailure(int status, String body) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/auth/login", exchange -> {
            try(exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try(var client=new HttpTrackerClient("http://127.0.0.1:"+server.getAddress().getPort(),null)) {
            return assertThrows(TrackerException.class,()->client.login("alice","password123",false));
        } finally { server.stop(0); }
    }
}
