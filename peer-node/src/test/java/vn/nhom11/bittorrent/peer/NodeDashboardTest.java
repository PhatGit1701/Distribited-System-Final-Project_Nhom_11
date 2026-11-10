package vn.nhom11.bittorrent.peer;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import vn.nhom11.bittorrent.shared.*;
import java.lang.reflect.Type;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class NodeDashboardTest {
    @TempDir Path temp;
    @Test void authenticatedWebSocketReceivesRealChunkHashesAndSources() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        byte[] data={1,2,3,4,5};
        Path source=temp.resolve("sample.bin");Files.write(source,data);
        var metadata=TorrentGenerator.generate(source,3,"http://localhost");
        try(var seed=new ParallelDownloadManagerTest.TestPeer("seed",(socket,r)->
                ParallelDownloadManagerTest.send(socket,r.chunkIndex(),Arrays.copyOfRange(data,r.chunkIndex()*3,
                        r.chunkIndex()*3+metadata.chunkLength(r.chunkIndex()))))) {
            var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            http.createContext("/api",exchange->{
                try(exchange) {
                    String path=exchange.getRequestURI().getPath();Object result;
                    if(path.startsWith("/api/auth/")) result=new AuthResponse("token","Bearer",1,"alice",Instant.now().plusSeconds(3600));
                    else if(!"Bearer token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401,-1);return;
                    } else if(path.equals("/api/tracker/torrents")) result=List.of(metadata);
                    else if(path.startsWith("/api/tracker/torrents/")) result=metadata;
                    else if(path.startsWith("/api/tracker/swarm/")) result=List.of(seed.info(2));
                    else result=Map.of("ok",true);
                    byte[] bytes=json.writeValueAsBytes(result);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }
            });http.start();
            try(var context=new SpringApplicationBuilder(PeerApplication.class).run("--server.port=0",
                    "--peer.tcp.port=0","--peer.id=dashboard","--peer.storage="+temp.resolve("storage"),
                    "--peer.tracker-url=http://127.0.0.1:"+http.getAddress().getPort())) {
                int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
                String base="http://127.0.0.1:"+port;
                try(var client=HttpClient.newHttpClient()) {
                    assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/snapshot")).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode());
                    assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(base+"/api/node;ignored/snapshot")).GET().build(),
                            HttpResponse.BodyHandlers.ofString()).statusCode(),"matrix parameters must not bypass authentication");
                    var login=client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/session"))
                            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"username\":\"alice\",\"password\":\"password123\"}")).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(200,login.statusCode(),login.body());
                    String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
                    String csrf=json.readTree(login.body()).get("csrfToken").asText();
                    assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/downloads"))
                            .header("Cookie",cookie).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"fileId\":\""+metadata.fileId()+"\"}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                    var stomp=new WebSocketStompClient(new StandardWebSocketClient());
                    var converter=new MappingJackson2MessageConverter();converter.setObjectMapper(json);stomp.setMessageConverter(converter);
                    var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();scheduler.initialize();
                    stomp.setTaskScheduler(scheduler);
                    var messages=new LinkedBlockingQueue<JsonNode>();
                    var headers=new WebSocketHttpHeaders();headers.add("Cookie",cookie);
                    StompSession session=null;
                    try {
                        assertThrows(ExecutionException.class,()->stomp.connectAsync("ws://127.0.0.1:"+port+"/ws",
                                new StompSessionHandlerAdapter(){}).get(5,TimeUnit.SECONDS));
                        session=stomp.connectAsync("ws://127.0.0.1:"+port+"/ws",headers,new StompSessionHandlerAdapter(){}).get(5,TimeUnit.SECONDS);
                        session.subscribe("/topic/node-events",new StompFrameHandler(){
                            public Type getPayloadType(StompHeaders h){return JsonNode.class;}
                            public void handleFrame(StompHeaders h,Object payload){messages.add((JsonNode)payload);}
                        });
                        // The server emits SYNC only after the broker registers this subscription.
                        JsonNode probe=null;
                        for(int i=0;i<30&&probe==null;i++) {
                            JsonNode next=messages.poll(100,TimeUnit.MILLISECONDS);
                            if(next!=null&&next.path("type").asText().equals("SYNC"))probe=next;
                        }
                        assertNotNull(probe,"WebSocket subscription must receive a message");
                        var download=client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/downloads"))
                                .header("Cookie",cookie).header("X-Node-Token",csrf).header("Content-Type","application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"fileId\":\""+metadata.fileId()+"\"}")).build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(202,download.statusCode(),download.body());
                        Set<Integer> received=new HashSet<>();long sequence=0,until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                        while(received.size()<2&&System.nanoTime()<until) {
                            JsonNode event=messages.poll(1,TimeUnit.SECONDS);if(event==null)continue;
                            assertTrue(event.get("sequence").asLong()>sequence);sequence=event.get("sequence").asLong();
                            if(event.path("type").asText().equals("CHUNK")&&event.path("chunk").path("status").asText().equals("COMPLETED")) {
                                var chunk=event.get("chunk");int index=chunk.get("chunkIndex").asInt();
                                assertEquals(metadata.chunkHashes().get(index),chunk.get("sha256Hash").asText());
                                assertEquals("seed",chunk.get("sourceUsername").asText());
                                assertTrue(chunk.get("sourceEndpoint").asText().startsWith("127.0.0.1:"));
                                assertEquals(metadata.fileId(),event.get("fileId").asText());received.add(index);
                            }
                        }
                        assertEquals(Set.of(0,1),received);
                        var exported=client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/files/"+metadata.fileId()))
                                .header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                        assertEquals(200,exported.statusCode());assertArrayEquals(data,exported.body());
                        var crossOrigin=client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/downloads"))
                                .header("Cookie",cookie).header("X-Node-Token",csrf).header("Origin","https://untrusted.example")
                                .header("Content-Type","application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"fileId\":\""+metadata.fileId()+"\"}")).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(403,crossOrigin.statusCode());
                        String boundary="dashboard-test-boundary";
                        String unsafe="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../unsafe.bin\"\r\n"
                                +"Content-Type: application/octet-stream\r\n\r\nunsafe\r\n--"+boundary+"--\r\n";
                        var rejected=client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/publish"))
                                .header("Cookie",cookie).header("X-Node-Token",csrf)
                                .header("Content-Type","multipart/form-data; boundary="+boundary)
                                .POST(HttpRequest.BodyPublishers.ofString(unsafe)).build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(400,rejected.statusCode());
                        String safe=unsafe.replace("../unsafe.bin","uploaded.bin");
                        var uploaded=client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/publish"))
                                .header("Cookie",cookie).header("X-Node-Token",csrf)
                                .header("Content-Type","multipart/form-data; boundary="+boundary)
                                .POST(HttpRequest.BodyPublishers.ofString(safe)).build(),HttpResponse.BodyHandlers.ofString());
                        assertEquals(201,uploaded.statusCode(),uploaded.body());
                        assertEquals("uploaded.bin",json.readTree(uploaded.body()).get("fileName").asText());
                        var snapshot=client.send(HttpRequest.newBuilder(URI.create(base+"/api/node/snapshot")).header("Cookie",cookie).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200,snapshot.statusCode());
                        assertTrue(snapshot.body().contains(metadata.fileId()));
                        assertFalse(snapshot.body().contains("\"token\""),"tracker JWT must stay server-side");
                    } finally {if(session!=null&&session.isConnected())session.disconnect();stomp.stop();scheduler.shutdown();}
                }
            }finally{http.stop(0);}
        }
    }
}
