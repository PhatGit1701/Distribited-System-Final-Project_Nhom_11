package vn.nhom11.bittorrent.tracker.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="user_file_stats",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","file_id"}))
public class UserFileStats {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="user_id",nullable=false) private UserAccount user;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="file_id",nullable=false) private TorrentEntity file;
    @Column(nullable=false) private long totalUploaded;
    @Column(nullable=false) private long totalDownloaded;
    private Instant completedAt;
    @Version private Long version;

    protected UserFileStats() {}
    public UserFileStats(UserAccount user, TorrentEntity file) {
        this.user=java.util.Objects.requireNonNull(user);
        this.file=java.util.Objects.requireNonNull(file);
    }
    public void recordTransfer(long uploaded, long downloaded) {
        if (uploaded < 0 || downloaded < 0) throw new IllegalArgumentException("Transfer bytes must be nonnegative");
        long nextUploaded=Math.addExact(totalUploaded,uploaded);
        long nextDownloaded=Math.addExact(totalDownloaded,downloaded);
        totalUploaded=nextUploaded; totalDownloaded=nextDownloaded;
    }
    public void markCompleted(Instant at) { completedAt=java.util.Objects.requireNonNull(at); }
    public Long getId() { return id; }
    public long getTotalUploaded() { return totalUploaded; }
    public long getTotalDownloaded() { return totalDownloaded; }
    public Instant getCompletedAt() { return completedAt; }
}
