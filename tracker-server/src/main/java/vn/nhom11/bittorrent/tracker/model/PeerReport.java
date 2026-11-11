package vn.nhom11.bittorrent.tracker.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="peer_reports")
public class PeerReport {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="reporter_id",nullable=false) private UserAccount reporter;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="file_id",nullable=false) private TorrentEntity file;
    @Column(nullable=false,length=128) private String peerId;
    @Column(nullable=false,length=1000) private String reason;
    @Column(nullable=false,updatable=false) private Instant createdAt;

    protected PeerReport() {}
    public PeerReport(UserAccount reporter, TorrentEntity file, String peerId, String reason) {
        if (peerId==null || peerId.isBlank() || peerId.length()>128 || reason==null || reason.isBlank() || reason.length()>1000)
            throw new IllegalArgumentException("Peer ID and reason are required and must fit their column limits");
        this.reporter=java.util.Objects.requireNonNull(reporter);
        this.file=java.util.Objects.requireNonNull(file);
        this.peerId=peerId; this.reason=reason; this.createdAt=Instant.now();
    }
    public Long getId() { return id; }
    public String getReason() { return reason; }
}
