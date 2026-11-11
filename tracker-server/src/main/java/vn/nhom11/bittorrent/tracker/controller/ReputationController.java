package vn.nhom11.bittorrent.tracker.controller;
import vn.nhom11.bittorrent.tracker.dto.UserReputation;
import vn.nhom11.bittorrent.tracker.service.ReputationService;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class ReputationController {
    private final ReputationService reputations;
    public ReputationController(ReputationService reputations) { this.reputations=reputations; }
    @GetMapping("/{id}/reputation")
    public UserReputation get(@PathVariable long id) {
        return reputations.get(id);
    }
}
