package vn.nhom11.bittorrent.tracker.controller;
import vn.nhom11.bittorrent.tracker.dto.ReportRequest;
import vn.nhom11.bittorrent.tracker.service.ReportService;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports) { this.reports=reports; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReportService.ReportResult report(@RequestBody ReportRequest request, @AuthenticationPrincipal Jwt jwt) {
        return reports.report(Long.parseLong(jwt.getSubject()), request);
    }
}
