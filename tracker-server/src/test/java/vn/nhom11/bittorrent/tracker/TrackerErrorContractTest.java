package vn.nhom11.bittorrent.tracker;
import vn.nhom11.bittorrent.tracker.exception.ApiErrors;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TrackerErrorContractTest {
    @RestController
    static class Probe {
        @GetMapping("/invalid") void invalid() { throw new IllegalArgumentException("Invalid peer endpoint"); }
        @GetMapping("/parameter") void parameter(@RequestParam int count) {}
    }
    @Test void validationReturnsSharedEnvelope() throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiErrors()).build()
            .perform(get("/invalid")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("Invalid peer endpoint"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.path").value("/invalid"));
    }
    @Test void missingParameterUsesSameEnvelope() throws Exception {
        MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiErrors()).build()
            .perform(get("/parameter")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
