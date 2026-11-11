package vn.nhom11.bittorrent.shared;

/** Cumulative bytes exchanged with a counterpart peer in the heartbeat's file/session. */
public record TransferReport(String peerId, long uploadedTo, long downloadedFrom) {}
