package vn.nhom11.bittorrent.peer.exception;
import java.io.IOException;
/** Typed upstream failure; IOException preserves downloader retries and cached-directory fallback. */
public final class TrackerException extends IOException {
    private final int status;
    private final String code;
    public TrackerException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public int getStatus() { return status; }
    public String getCode() { return code; }
}
