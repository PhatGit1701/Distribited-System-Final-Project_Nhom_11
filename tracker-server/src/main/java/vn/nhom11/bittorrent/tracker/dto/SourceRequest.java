package vn.nhom11.bittorrent.tracker.dto;

import java.util.Set;

public record SourceRequest(String peerId, Set<Integer> availableChunks, int maxSources) {}
