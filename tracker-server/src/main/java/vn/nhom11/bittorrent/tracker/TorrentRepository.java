package vn.nhom11.bittorrent.tracker;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TorrentRepository extends JpaRepository<TorrentEntity,String> {}
