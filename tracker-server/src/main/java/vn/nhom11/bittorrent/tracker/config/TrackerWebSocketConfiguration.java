package vn.nhom11.bittorrent.tracker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class TrackerWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final JwtDecoder decoder;
    public TrackerWebSocketConfiguration(JwtDecoder decoder) {this.decoder=decoder;}
    @Override public void registerStompEndpoints(StompEndpointRegistry endpoints) {endpoints.addEndpoint("/ws/tracker");}
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {registry.enableSimpleBroker("/topic/swarm");}
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message,MessageChannel channel) {
                var headers=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
                if(headers==null) throw new AccessDeniedException("STOMP headers required");
                if(headers.getCommand()==StompCommand.CONNECT) {
                    String authorization=headers.getFirstNativeHeader("Authorization");
                    if(authorization==null||!authorization.startsWith("Bearer ")) throw new AccessDeniedException("Bearer token required");
                    var jwt=decoder.decode(authorization.substring(7));
                    headers.setUser(new JwtAuthenticationToken(jwt,java.util.List.of()));
                } else if(headers.getCommand()==StompCommand.SUBSCRIBE) {
                    if(!(headers.getUser() instanceof JwtAuthenticationToken token)||!token.isAuthenticated()
                        ||token.getToken().getExpiresAt()==null||!token.getToken().getExpiresAt().isAfter(java.time.Instant.now()))
                        throw new AccessDeniedException("Authentication required");
                    String destination=headers.getDestination();
                    if(destination==null||!destination.matches("/topic/swarm/[a-fA-F0-9]{64}")) throw new AccessDeniedException("Invalid subscription");
                } else if(headers.getCommand()==StompCommand.SEND) {
                    throw new AccessDeniedException("Tracker events are read-only");
                }
                return message;
            }
        });
    }
}
