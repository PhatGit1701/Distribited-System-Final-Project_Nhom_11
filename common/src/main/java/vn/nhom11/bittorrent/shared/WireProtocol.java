package vn.nhom11.bittorrent.shared;

public final class WireProtocol {
    private WireProtocol() {}
    public static final byte HANDSHAKE = 0x01, REQUEST_CHUNK = 0x02, REPORT_UPLOAD_BYTES = 0x03;
    public static final byte OK_DATA = 0x10, CHOKED = 0x11, CHUNK_NOT_FOUND = 0x12, ERROR = 0x13;
    public static final int MAX_STRING_BYTES = 4096;
}
