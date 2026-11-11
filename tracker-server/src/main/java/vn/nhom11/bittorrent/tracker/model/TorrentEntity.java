package vn.nhom11.bittorrent.tracker.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.nhom11.bittorrent.shared.TorrentMetadata;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;

@Entity
@Table(name="shared_files")
public class TorrentEntity {
    @Id @Column(name="file_id",length=64) private String fileId;
    @Column(nullable=false,length=255) private String name;
    @Column(nullable=false) private long size;
    @Column(nullable=false) private int chunkSize;
    @Column(nullable=false) private int chunkCount;
    // VARBINARY maps to PostgreSQL BYTEA. Do not use @Lob (PostgreSQL OID).
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable=false,length=3_200_000) private byte[] chunkHashes;
    @Column(nullable=false,columnDefinition="text") private String trackerUrl;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="owner_id",nullable=false) private UserAccount owner;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Version private Long version;

    protected TorrentEntity() {}

    public TorrentEntity(TorrentMetadata metadata, UserAccount owner) {
        if (metadata.fileSize() <= 0) throw new IllegalArgumentException("File must not be empty");
        this.fileId=metadata.fileId(); this.name=metadata.fileName(); this.size=metadata.fileSize();
        this.chunkSize=metadata.chunkSize(); this.chunkCount=metadata.totalChunks();
        this.trackerUrl=metadata.trackerUrl(); this.owner=java.util.Objects.requireNonNull(owner);
        this.createdAt=Instant.now();
        this.chunkHashes=new byte[chunkCount * 32];
        for (int i=0; i<chunkCount; i++) {
            System.arraycopy(HexFormat.of().parseHex(metadata.chunkHashes().get(i)),0,chunkHashes,i*32,32);
        }
    }

    public TorrentMetadata toMetadata() {
        var hashes=new ArrayList<String>(chunkCount);
        for (int i=0; i<chunkCount; i++) hashes.add(HexFormat.of().formatHex(chunkHashes,i*32,(i+1)*32));
        return new TorrentMetadata(fileId,name,size,chunkSize,chunkCount,hashes,trackerUrl);
    }
}
