package vn.nhom11.bittorrent.model;

public record PeerEndpoint(String peerId, String host, int port) {
    public PeerEndpoint {
        if (peerId == null || peerId.isBlank() || peerId.length() > 128
                || host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid peer endpoint");
        }
    }

    /** CLI format: peer-a@127.0.0.1:9001, or peer-a@[::1]:9001. */
    public static PeerEndpoint parse(String value) {
        int at = value.indexOf('@');
        int colon = value.lastIndexOf(':');
        if (at <= 0 || colon <= at + 1 || colon == value.length() - 1) {
            throw new IllegalArgumentException("Expected --peer id@host:port");
        }
        String host = value.substring(at + 1, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return new PeerEndpoint(value.substring(0, at), host, Integer.parseInt(value.substring(colon + 1)));
    }
}
