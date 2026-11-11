package vn.nhom11.bittorrent.tracker.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import vn.nhom11.bittorrent.shared.exception.*;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AppException.class)
    ResponseEntity<ErrorResponse> application(AppException error, HttpServletRequest request) {
        return response(error.getErrorCode(), error.getMessage(), request.getRequestURI());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException error, HttpServletRequest request) {
        return response(ErrorCode.INVALID_REQUEST, error.getMessage(), request.getRequestURI());
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
        logger.error("Unhandled request failure", error);
        return response(ErrorCode.INTERNAL_ERROR, null, request.getRequestURI());
    }
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException error,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(ErrorResponse.of(ErrorCode.MALFORMED_JSON, null, path(request)), headers, status);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ErrorCode code = ErrorCode.forStatus(status.value());
        String message = code.message();
        if (error instanceof org.springframework.web.bind.MissingServletRequestParameterException missing)
            message = "Missing required parameter: " + missing.getParameterName();
        else if (error instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException mismatch)
            message = "Invalid value for parameter: " + mismatch.getName();
        else if (error instanceof org.springframework.web.bind.MethodArgumentNotValidException invalid)
            message = invalid.getBindingResult().getFieldErrors().stream()
                    .map(field -> field.getField() + ": " + field.getDefaultMessage()).collect(java.util.stream.Collectors.joining("; "));
        return new ResponseEntity<>(new ErrorResponse(code.name(), message, status.value(), path(request)), headers, status);
    }
    private static String path(WebRequest request) { return ((ServletWebRequest) request).getRequest().getRequestURI(); }
    private ResponseEntity<ErrorResponse> response(ErrorCode code, String message, String path) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message, path));
    }
}
