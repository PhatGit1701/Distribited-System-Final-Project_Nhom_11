package vn.nhom11.bittorrent.peer;
import vn.nhom11.bittorrent.peer.network.PeerTcpServer;
import vn.nhom11.bittorrent.peer.repository.ChunkStore;
import vn.nhom11.bittorrent.peer.service.PeerRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerRuntimeTest {
    @TempDir Path temp;
    @Test void rejectedPublicationDoesNotLeakIntoHeartbeatsOrLocalSwarm() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var heartbeats=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/api/tracker",exchange->{
            try(exchange) {
                String path=exchange.getRequestURI().getPath();
                if(path.equals("/api/tracker/torrents")&&exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(409,-1);return;
                }
                if(path.endsWith("/heartbeat"))heartbeats.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                byte[] body="[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }
        });http.start();
        try(var context=new SpringApplicationBuilder(PeerApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(
                "--peer.id=rejected","--peer.username=alice","--peer.token=token","--peer.tcp.port=0",
                "--peer.storage="+temp.resolve("rejected"),"--peer.tracker-url=http://127.0.0.1:"+http.getAddress().getPort())) {
            Path source=temp.resolve("collision.bin");Files.write(source,new byte[]{9,8,7});
            assertThrows(java.io.IOException.class,()->context.getBean(PeerRuntime.class).publish(source));
            context.getBean(PeerRuntime.class).heartbeat();
            assertTrue(heartbeats.isEmpty(),"metadata rejected by tracker must never be advertised");
            assertTrue(context.getBean(ChunkStore.class).all().isEmpty());
        }finally{http.stop(0);}
    }
    @Test void bootNodesPublishDiscoverDownloadAndHeartbeatOverHttpAndTcp() throws Exception {
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        Map<String,vn.nhom11.bittorrent.shared.TorrentMetadata> metadata=new java.util.concurrent.ConcurrentHashMap<>();
        Map<String,vn.nhom11.bittorrent.shared.PeerNodeInfo> peers=new java.util.concurrent.ConcurrentHashMap<>();
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/api/auth",exchange->{
            try(exchange) {
                var request=json.readValue(exchange.getRequestBody(),vn.nhom11.bittorrent.shared.AuthRequest.class);
                byte[] body=json.writeValueAsBytes(new vn.nhom11.bittorrent.shared.AuthResponse(
                    "test-token","Bearer",1,request.username(),java.time.Instant.now().plusSeconds(3600)));
                exchange.sendResponseHeaders(201,body.length);exchange.getResponseBody().write(body);
            }
        });
        http.createContext("/api/tracker",exchange->{
            try(exchange) {
                if(!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401,-1);return;
                }
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                Object result=List.of();
                int status=200;
                if(path.equals("/api/tracker/torrents")&&method.equals("POST")) {
                    var m=json.readValue(exchange.getRequestBody(),vn.nhom11.bittorrent.shared.TorrentMetadata.class);
                    metadata.put(m.fileId(),m);result=m;status=201;
                } else if(path.startsWith("/api/tracker/torrents/")) {
                    result=metadata.get(path.substring(path.lastIndexOf('/')+1));
                } else if(path.equals("/api/tracker/heartbeat")) {
                    var r=json.readValue(exchange.getRequestBody(),vn.nhom11.bittorrent.shared.HeartbeatRequest.class);
                    var p=new vn.nhom11.bittorrent.shared.PeerNodeInfo(r.peerId(),r.username(),r.ipAddress(),r.tcpPort(),r.availableChunks(),java.time.Instant.now());
                    peers.put(r.peerId(),p);result=p;
                } else if(path.startsWith("/api/tracker/sources/")) {
                    var m=metadata.get(path.substring(path.lastIndexOf('/')+1));
                    var request=json.readTree(exchange.getRequestBody());
                    var chunks=new java.util.ArrayList<vn.nhom11.bittorrent.shared.SourcePlan.ChunkSources>();
                    for(int i=0;i<m.totalChunks();i++) {
                        final int chunk=i;
                        var candidates=peers.values().stream().filter(p->!p.peerId().equals(request.get("peerId").asText())&&p.availableChunks().contains(chunk)).toList();
                        chunks.add(new vn.nhom11.bittorrent.shared.SourcePlan.ChunkSources(i,candidates.size(),candidates));
                    }
                    result=new vn.nhom11.bittorrent.shared.SourcePlan(m.fileId(),chunks,1,0,30);
                } else if(path.startsWith("/api/tracker/swarm/")) {result=List.copyOf(peers.values());}
                byte[] bytes=json.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }
        });
        http.start();
        String tracker="http://127.0.0.1:"+http.getAddress().getPort();
        try(var seed=new SpringApplicationBuilder(PeerApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(
                "--peer.id=seed","--peer.username=alice","--peer.password=password123","--peer.register-user=true","--peer.tcp.port=0",
                "--peer.storage="+temp.resolve("seed"),"--peer.tracker-url="+tracker);
            var leecher=new SpringApplicationBuilder(PeerApplication.class).web(org.springframework.boot.WebApplicationType.NONE).run(
                "--peer.id=leecher","--peer.username=bob","--peer.token=test-token","--peer.tcp.port=0",
                "--peer.storage="+temp.resolve("leecher"),"--peer.tracker-url="+tracker)) {
            byte[] bytes=new byte[600123];new Random(8).nextBytes(bytes);
            Path source=temp.resolve("payload.bin");Files.write(source,bytes);
            var m=seed.getBean(PeerRuntime.class).publish(source);
            Path target=leecher.getBean(PeerRuntime.class).download(m.fileId());
            assertArrayEquals(bytes,Files.readAllBytes(target));
            assertEquals(m.totalChunks(),peers.get("leecher").availableChunks().size());
            assertTrue(seed.getBean(PeerTcpServer.class).port()>0);
        } finally {http.stop(0);}
    }
}
