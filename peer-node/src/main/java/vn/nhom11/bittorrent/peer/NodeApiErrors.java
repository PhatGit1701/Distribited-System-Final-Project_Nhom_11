package vn.nhom11.bittorrent.peer;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class NodeApiErrors {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception error) { return response(400, "Invalid request"); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> conflict(Exception error) { return response(409, error.getMessage()); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(Exception error) { return response(413, "File exceeds upload limit"); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException error) { return response(error.getStatusCode().value(), error.getReason()); }
    @ExceptionHandler(IOException.class)
    ResponseEntity<?> io(IOException error) {
        String message = error.getMessage() == null ? "I/O error" : error.getMessage();
        int status = message.contains("HTTP 401") ? 401 : message.contains("HTTP 409") ? 409 :
                message.contains("HTTP 404") ? 404 : message.contains("HTTP 400") ? 400 : 502;
        return response(status, message);
    }
    private ResponseEntity<?> response(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message == null ? "Request failed" : message));
    }
}
