package vn.nhom11.bittorrent.tracker.dto;


public record ReportRequest(String fileId, String peerId, Long targetUserId, Integer chunkIndex,
                                String expectedHash, String actualHash) {}
