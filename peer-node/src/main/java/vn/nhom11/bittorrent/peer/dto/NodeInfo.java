package vn.nhom11.bittorrent.peer.dto;

import vn.nhom11.bittorrent.peer.client.HttpTrackerClient;

public record NodeInfo(String peerId, String username, int tcpPort, int webPort, String trackerUrl,
                           boolean authenticated, HttpTrackerClient.Health heartbeat) {}
