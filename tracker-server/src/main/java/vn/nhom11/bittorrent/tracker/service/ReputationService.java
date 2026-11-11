package vn.nhom11.bittorrent.tracker.service;
import vn.nhom11.bittorrent.tracker.dto.UserReputation;
import vn.nhom11.bittorrent.tracker.repository.SourceReputationRepository;
import org.springframework.stereotype.Service;
import vn.nhom11.bittorrent.shared.exception.*;
@Service
public class ReputationService {
    private final SourceReputationRepository reputations;
    public ReputationService(SourceReputationRepository reputations) { this.reputations=reputations; }
    public UserReputation get(long id) {
        return reputations.user(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }
}
