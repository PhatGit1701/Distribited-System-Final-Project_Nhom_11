package vn.nhom11.bittorrent.peer;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class NodeHttpConfiguration implements WebMvcConfigurer {
    private final NodeSessionFilter sessions;
    public NodeHttpConfiguration(NodeSessionFilter sessions) { this.sessions = sessions; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(sessions); }
}
