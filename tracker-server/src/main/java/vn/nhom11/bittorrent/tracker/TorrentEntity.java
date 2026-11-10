package vn.nhom11.bittorrent.tracker;
import jakarta.persistence.*;
@Entity @Table(name="torrents")
public class TorrentEntity {
 @Id @Column(length=64) private String fileId;
 @Lob @Column(nullable=false) private String metadataJson;
 protected TorrentEntity() {}
 TorrentEntity(String fileId,String metadataJson){this.fileId=fileId;this.metadataJson=metadataJson;}
 public String getMetadataJson(){return metadataJson;}
}
