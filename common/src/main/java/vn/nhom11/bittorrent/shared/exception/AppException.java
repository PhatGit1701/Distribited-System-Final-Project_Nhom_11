package vn.nhom11.bittorrent.shared.exception;

import java.util.Objects;

/** Expected application failure; message must be safe to display to the caller. */
public class AppException extends RuntimeException {
    private final ErrorCode errorCode;
    public AppException(ErrorCode errorCode) { this(errorCode, errorCode.message()); }
    public AppException(ErrorCode errorCode, String message) {
        super(message == null || message.isBlank() ? errorCode.message() : message);
        this.errorCode = Objects.requireNonNull(errorCode);
    }
    public ErrorCode getErrorCode() { return errorCode; }
}
