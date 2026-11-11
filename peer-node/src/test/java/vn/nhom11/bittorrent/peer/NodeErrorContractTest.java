package vn.nhom11.bittorrent.peer;
import vn.nhom11.bittorrent.peer.exception.NodeApiErrors;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeErrorContractTest {
    @RestController
    static class Probe {
        @GetMapping("/invalid") void invalid() { throw new IllegalArgumentException("Invalid file name"); }
        @PostMapping("/json") void json(@RequestBody java.util.Map<String, String> body) {}
        @GetMapping("/unexpected") void unexpected() { throw new IllegalStateException("database password secret"); }
        @GetMapping("/upstream") void upstream() throws java.io.IOException {
            throw new vn.nhom11.bittorrent.peer.exception.TrackerException(409, "USERNAME_ALREADY_EXISTS", "Username already exists");
        }
    }
    @Test void validationPreservesSpecificMessageAndHasStableCode() throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new NodeApiErrors()).build()
            .perform(get("/invalid")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid file name"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.path").value("/invalid"));
    }
    @Test void malformedJsonHasItsOwnCode() throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new NodeApiErrors()).build()
            .perform(post("/json").contentType("application/json").content("{"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }
    @Test void unexpectedFailureDoesNotLeakInternals() throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new NodeApiErrors()).build()
            .perform(get("/unexpected")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
    @Test void upstreamFailureReachesBrowserAsStructuredError() throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new NodeApiErrors()).build()
            .perform(get("/upstream")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"))
            .andExpect(jsonPath("$.message").value("Username already exists"));
    }
}
