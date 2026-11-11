package vn.nhom11.bittorrent.peer.model;

/** Advertised address is separate from the server bind address. */
public record PeerIdentity(String peerId, String username, String ipAddress, int tcpPort) {
    public PeerIdentity {
        if (peerId == null || !peerId.matches("[a-zA-Z0-9_-]{1,128}")
                || username == null || username.isBlank() || ipAddress == null || ipAddress.isBlank()
                || tcpPort < 1 || tcpPort > 65535) throw new IllegalArgumentException("Invalid peer identity");
    }
}
