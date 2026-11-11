package vn.nhom11.bittorrent.tracker.repository;
import vn.nhom11.bittorrent.tracker.model.TorrentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TorrentRepository extends JpaRepository<TorrentEntity,String> {}
