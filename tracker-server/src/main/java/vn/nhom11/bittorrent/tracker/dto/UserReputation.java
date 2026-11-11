package vn.nhom11.bittorrent.tracker.dto;

public record UserReputation(long userId, long verifiedUploaded, long verifiedDownloaded, double ratio, double score) {}
