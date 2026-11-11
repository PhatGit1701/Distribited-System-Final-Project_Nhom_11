package vn.nhom11.bittorrent.shared.exception;

/** Stable machine-readable errors shared by tracker, peer and browser clients. */
public enum ErrorCode {
    INVALID_REQUEST(400, "Invalid request"),
    MALFORMED_JSON(400, "Malformed or invalid JSON"),
    INVALID_CREDENTIALS(401, "Invalid credentials"),
    AUTHENTICATION_REQUIRED(401, "Login required"),
    USER_NOT_FOUND(404, "User not found"),
    USERNAME_ALREADY_EXISTS(409, "Username already exists"),
    FORBIDDEN(403, "Access denied"),
    INVALID_REQUEST_TOKEN(403, "Invalid request token"),
    SAME_ORIGIN_REQUIRED(403, "Same-origin requests required"),
    NOT_FOUND(404, "Resource not found"),
    TORRENT_NOT_FOUND(404, "Torrent not found"),
    FILE_NOT_AVAILABLE(404, "Verified file not available"),
    METHOD_NOT_ALLOWED(405, "Method not allowed"),
    CONFLICT(409, "Operation conflicts with current state"),
    DOWNLOAD_CONFLICT(409, "Download state does not allow this operation"),
    UPLOAD_TOO_LARGE(413, "File exceeds upload limit"),
    UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
    TRACKER_UNAVAILABLE(502, "Tracker could not be reached"),
    TRACKER_ERROR(502, "Tracker request failed"),
    IO_ERROR(500, "File operation failed"),
    INTERNAL_ERROR(500, "An unexpected error occurred");

    private final int status;
    private final String message;
    ErrorCode(int status, String message) { this.status = status; this.message = message; }
    public int status() { return status; }
    public String message() { return message; }
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> UPLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_ERROR : INVALID_REQUEST;
        };
    }
}
