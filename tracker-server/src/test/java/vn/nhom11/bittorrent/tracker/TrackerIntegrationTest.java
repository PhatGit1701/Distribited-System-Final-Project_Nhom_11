package vn.nhom11.bittorrent.tracker;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import vn.nhom11.bittorrent.shared.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:tracker-test;DB_CLOSE_DELAY=-1","spring.jpa.hibernate.ddl-auto=create-drop"})
@AutoConfigureMockMvc
class TrackerIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
 @Test void rejectsExpiredSignedTokensAndMalformedAuth() throws Exception {
   var now=java.time.Instant.now();
   var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
       .issuer("bittorrent-lite-tracker").subject("1").claim("userId",1).claim("username","alice")
       .issuedAt(now.minusSeconds(120)).expiresAt(now.minusSeconds(60)).build();
   String token=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
       org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
   mvc.perform(get("/api/tracker/torrents").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
   mvc.perform(post("/api/auth/register").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
 }
 @Test void authenticatesRegistersAndUpdatesSwarm() throws Exception {
   mvc.perform(get("/api/tracker/torrents")).andExpect(status().isUnauthorized());
   String credentials = "{\"username\":\"alice\",\"password\":\"password123\"}";
   String response = mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials))
       .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
   String token = json.readTree(response).get("token").asText();
   String auth = "Bearer " + token;
   mvc.perform(post("/api/auth/register").contentType("application/json").content(credentials)).andExpect(status().isConflict());
   mvc.perform(post("/api/auth/login").contentType("application/json").content(credentials)).andExpect(status().isOk());
   mvc.perform(post("/api/auth/login").contentType("application/json").content(credentials.replace("password123","wrong"))).andExpect(status().isUnauthorized());
   var metadata = new TorrentMetadata(Hashing.fileId("test.bin",4,3),"test.bin",4,3,2,
       List.of(Hashing.sha256("abc"),Hashing.sha256("d")),"http://localhost:8080");
   mvc.perform(post("/api/tracker/torrents").param("peerId","p1").param("ipAddress","127.0.0.1").param("tcpPort","6881")
       .header("Authorization",auth).contentType("application/json").content(json.writeValueAsBytes(metadata))).andExpect(status().isCreated());
   mvc.perform(get("/api/tracker/torrents").header("Authorization",auth)).andExpect(jsonPath("$[0].fileName").value("test.bin"));
   mvc.perform(get("/api/tracker/torrents/"+metadata.fileId()).header("Authorization",auth)).andExpect(jsonPath("$.totalChunks").value(2));
   var collision = new TorrentMetadata(metadata.fileId(),"test.bin",4,3,2,
       List.of(Hashing.sha256("xxx"),Hashing.sha256("d")),"http://localhost:8080");
   mvc.perform(post("/api/tracker/torrents").param("peerId","p2").param("ipAddress","127.0.0.1").param("tcpPort","6882")
       .header("Authorization",auth).contentType("application/json").content(json.writeValueAsBytes(collision))).andExpect(status().isConflict());
   mvc.perform(get("/api/tracker/swarm/"+metadata.fileId()).header("Authorization",auth)).andExpect(jsonPath("$[0].availableChunks.length()").value(2));
   var heartbeat = new HeartbeatRequest("p1","alice","127.0.0.1",6881,metadata.fileId(),Set.of(0));
   mvc.perform(post("/api/tracker/heartbeat").header("Authorization",auth).contentType("application/json").content(json.writeValueAsBytes(heartbeat))).andExpect(status().isOk());
   mvc.perform(get("/api/tracker/swarm/"+metadata.fileId()).header("Authorization",auth)).andExpect(jsonPath("$[0].availableChunks.length()").value(1));
   var invalid = new HeartbeatRequest("p1","alice","127.0.0.1",6881,metadata.fileId(),Set.of(2));
   mvc.perform(post("/api/tracker/heartbeat").header("Authorization",auth).contentType("application/json").content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
   mvc.perform(get("/api/tracker/torrents/missing").header("Authorization",auth)).andExpect(status().isNotFound());
   mvc.perform(get("/api/tracker/torrents").header("Authorization","Bearer "+token+"x")).andExpect(status().isUnauthorized());
 }
}
