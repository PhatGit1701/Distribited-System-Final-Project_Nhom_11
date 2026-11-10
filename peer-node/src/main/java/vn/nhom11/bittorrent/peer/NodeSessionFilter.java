package vn.nhom11.bittorrent.peer;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;

/** Checks the mapped controller, not raw URL prefixes (matrix/encoded paths normalize in MVC). */
@Component
public class NodeSessionFilter implements HandlerInterceptor {
    static final String USER = "nodeUser", TOKEN = "nodeCsrf";
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || !NodeController.class.isAssignableFrom(method.getBeanType())) return true;
        boolean mutation = !request.getMethod().equals("GET") && !request.getMethod().equals("HEAD");
        String origin = request.getHeader("Origin");
        if (mutation && origin != null && !origin.equals(request.getScheme() + "://" + request.getHeader("Host"))) {
            reject(response, 403, "Same-origin requests required"); return false;
        }
        boolean publicAction = method.getMethod().getName().equals("status") || method.getMethod().getName().equals("login");
        if (!publicAction) {
            HttpSession session = request.getSession(false);
            if (session == null || session.getAttribute(USER) == null) { reject(response, 401, "Login required"); return false; }
            if (mutation && !java.util.Objects.equals(session.getAttribute(TOKEN), request.getHeader("X-Node-Token"))) {
                reject(response, 403, "Invalid request token"); return false;
            }
        }
        return true;
    }
    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
