package vn.nhom11.bittorrent.tracker.event;
import vn.nhom11.bittorrent.tracker.repository.SwarmRepository;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;

@Component
public class SwarmEvents {
    public record PeerLeft(String type, String fileId, String peerId, Instant timestamp) {}
    private final SimpMessagingTemplate messages;
    private final Clock clock;
    public SwarmEvents(SimpMessagingTemplate messages, Clock clock) { this.messages=messages; this.clock=clock; }
    public void peerLeft(SwarmRepository.Peer peer) {
        messages.convertAndSend("/topic/swarm/"+peer.fileId(),new PeerLeft("peer_left",peer.fileId(),peer.peerId(),clock.instant()));
    }
}
