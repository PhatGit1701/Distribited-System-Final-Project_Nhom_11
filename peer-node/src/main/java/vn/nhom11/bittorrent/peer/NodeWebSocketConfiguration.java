package vn.nhom11.bittorrent.peer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.config.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSocketMessageBroker
public class NodeWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final NodeEventHub events;
    public NodeWebSocketConfiguration(NodeEventHub events) { this.events = events; }
    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setPreservePublishOrder(true);
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        HandshakeInterceptor authenticated = new HandshakeInterceptor() {
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler handler, Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servlet) {
                    var session = servlet.getServletRequest().getSession(false);
                    if (session != null && session.getAttribute(NodeSessionFilter.USER) != null) {
                        attributes.put(NodeSessionFilter.USER, session.getAttribute(NodeSessionFilter.USER));
                        return true;
                    }
                }
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED); return false;
            }
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}
        };
        registry.addEndpoint("/ws").addInterceptors(authenticated);
        registry.addEndpoint("/ws-sockjs").addInterceptors(authenticated).withSockJS();
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ExecutorChannelInterceptor() {
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                var headers = StompHeaderAccessor.wrap(message);
                StompCommand command = headers.getCommand();
                if (command == StompCommand.SEND) throw new IllegalArgumentException("Client publishing is disabled");
                if (command == StompCommand.SUBSCRIBE && !"/topic/node-events".equals(headers.getDestination()))
                    throw new IllegalArgumentException("Invalid topic");
                return message;
            }
            @Override public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception error) {
                var headers = StompHeaderAccessor.wrap(message);
                if (error == null && handler instanceof org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler
                        && headers.getCommand() == StompCommand.SUBSCRIBE && "/topic/node-events".equals(headers.getDestination()))
                    events.log("SYNC", "WebSocket subscription ready");
            }
        });
    }
}
