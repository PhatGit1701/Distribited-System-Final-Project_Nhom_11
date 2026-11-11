package vn.nhom11.bittorrent.tracker.repository;
import vn.nhom11.bittorrent.tracker.model.PeerReport;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PeerReportRepository extends JpaRepository<PeerReport,Long> {}
