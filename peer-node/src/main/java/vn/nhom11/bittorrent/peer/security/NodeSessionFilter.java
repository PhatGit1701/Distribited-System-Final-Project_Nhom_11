package vn.nhom11.bittorrent.peer.security;
import vn.nhom11.bittorrent.peer.controller.NodeController;

import jakarta.servlet.http.*;
import vn.nhom11.bittorrent.shared.exception.*;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;

/** Checks the mapped controller, not raw URL prefixes (matrix/encoded paths normalize in MVC). */
@Component
public class NodeSessionFilter implements HandlerInterceptor {
    public static final String USER = "nodeUser", TOKEN = "nodeCsrf";
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || !NodeController.class.isAssignableFrom(method.getBeanType())) return true;
        boolean mutation = !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
        String origin = request.getHeader("Origin");
        if (mutation && origin != null && !origin.equals(request.getScheme() + "://" + request.getHeader("Host"))) {
            throw new AppException(ErrorCode.SAME_ORIGIN_REQUIRED);
        }
        boolean publicAction = method.getMethod().getName().equals("status") || method.getMethod().getName().equals("login");
        if (!publicAction) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute(USER) == null) { throw new AppException(ErrorCode.AUTHENTICATION_REQUIRED); }
            if (mutation && !java.util.Objects.equals(session.getAttribute(TOKEN), request.getHeader("X-Node-Token"))) {
                throw new AppException(ErrorCode.INVALID_REQUEST_TOKEN);
            }
        }
        return true;
    }
}
