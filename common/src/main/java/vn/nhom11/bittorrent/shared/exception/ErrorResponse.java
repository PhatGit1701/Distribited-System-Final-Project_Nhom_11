package vn.nhom11.bittorrent.shared.exception;

public record ErrorResponse(String code, String message, int status, String path) {
    public static ErrorResponse of(ErrorCode code, String message, String path) {
        return new ErrorResponse(code.name(), message == null || message.isBlank() ? code.message() : message,
                code.status(), path);
    }
}
