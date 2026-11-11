package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.config.TrackerWebSocketConfiguration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.*;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TrackerWebSocketConfigurationTest {
 @Test void bearerConnectAuthenticatesSubscriptionButCannotPublish() {
   var decoder=mock(JwtDecoder.class);var registration=mock(ChannelRegistration.class);
   var jwt=Jwt.withTokenValue("token").header("alg","HS256").subject("42")
       .issuedAt(Instant.now().minusSeconds(1)).expiresAt(Instant.now().plusSeconds(60)).build();
   when(decoder.decode("token")).thenReturn(jwt);
   new TrackerWebSocketConfiguration(decoder).configureClientInboundChannel(registration);
   var captor=ArgumentCaptor.forClass(ChannelInterceptor.class);verify(registration).interceptors(captor.capture());
   var interceptor=captor.getValue();
   var connect=StompHeaderAccessor.create(StompCommand.CONNECT);connect.setNativeHeader("Authorization","Bearer token");
   interceptor.preSend(message(connect),null);assertNotNull(connect.getUser());
   var subscribe=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);subscribe.setUser(connect.getUser());
   subscribe.setDestination("/topic/swarm/"+"a".repeat(64));
   assertNotNull(interceptor.preSend(message(subscribe),null));
   var send=StompHeaderAccessor.create(StompCommand.SEND);send.setUser(connect.getUser());
   assertThrows(AccessDeniedException.class,()->interceptor.preSend(message(send),null));
   var unauthenticated=StompHeaderAccessor.create(StompCommand.SUBSCRIBE);unauthenticated.setDestination(subscribe.getDestination());
   assertThrows(AccessDeniedException.class,()->interceptor.preSend(message(unauthenticated),null));
 }
 private Message<byte[]> message(StompHeaderAccessor accessor) {
   accessor.setLeaveMutable(true);return MessageBuilder.createMessage(new byte[0],accessor.getMessageHeaders());
 }
}
