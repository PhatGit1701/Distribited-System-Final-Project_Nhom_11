package vn.nhom11.bittorrent.tracker;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestControllerAdvice
public class ApiErrors {
 @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})
 ResponseEntity<Map<String,String>> badRequest(Exception e){return ResponseEntity.badRequest().body(Map.of("error","Invalid request","message",e instanceof IllegalArgumentException?e.getMessage():"Malformed or invalid JSON"));}
 @ExceptionHandler(ResponseStatusException.class)
 ResponseEntity<Map<String,String>> status(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"Request failed":e.getReason()));}
}
