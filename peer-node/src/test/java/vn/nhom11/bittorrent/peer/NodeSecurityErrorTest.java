package vn.nhom11.bittorrent.peer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.nhom11.bittorrent.peer.controller.NodeController;
import vn.nhom11.bittorrent.peer.service.NodeService;
import vn.nhom11.bittorrent.peer.security.NodeSessionFilter;
import vn.nhom11.bittorrent.peer.exception.NodeApiErrors;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeSecurityErrorTest {
    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new NodeController(mock(NodeService.class)))
                .addInterceptors(new NodeSessionFilter()).setControllerAdvice(new NodeApiErrors()).build();
    }
    @Test void missingSessionReturnsApplicationEnvelope() throws Exception {
        mvc().perform(get("/api/node/snapshot")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/node/snapshot"));
    }
    @Test void invalidTokenReturnsApplicationEnvelope() throws Exception {
        var session=new MockHttpSession();
        session.setAttribute(NodeSessionFilter.USER,"alice"); session.setAttribute(NodeSessionFilter.TOKEN,"token");
        mvc().perform(delete("/api/node/session").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_TOKEN"));
    }
    @Test void foreignOriginReturnsApplicationEnvelope() throws Exception {
        mvc().perform(post("/api/node/session").header("Origin","https://foreign.example").header("Host","localhost")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SAME_ORIGIN_REQUIRED"));
    }
}
