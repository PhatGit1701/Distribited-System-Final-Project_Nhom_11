package vn.nhom11.bittorrent.tracker.controller;
import vn.nhom11.bittorrent.tracker.dto.SourceRequest;
import vn.nhom11.bittorrent.tracker.service.SourceSelectionService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.nhom11.bittorrent.shared.SourcePlan;

@RestController
@RequestMapping("/api/tracker/sources")
public class SourceController {
    private final SourceSelectionService sources;
    public SourceController(SourceSelectionService sources) { this.sources = sources; }
    @PostMapping("/{fileId}")
    public SourcePlan select(@PathVariable String fileId, @RequestBody SourceRequest request, @AuthenticationPrincipal Jwt jwt) {
        return sources.select(fileId, request.peerId(), request.availableChunks(), request.maxSources(), Long.parseLong(jwt.getSubject()));
    }
}
