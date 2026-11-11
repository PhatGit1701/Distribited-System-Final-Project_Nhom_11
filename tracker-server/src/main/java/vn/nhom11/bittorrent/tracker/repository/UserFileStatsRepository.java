package vn.nhom11.bittorrent.tracker.repository;
import vn.nhom11.bittorrent.tracker.model.UserFileStats;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFileStatsRepository extends JpaRepository<UserFileStats,Long> {}
