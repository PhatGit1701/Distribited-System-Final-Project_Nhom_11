package vn.nhom11.bittorrent.tracker.controller;
import vn.nhom11.bittorrent.tracker.service.AuthService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import vn.nhom11.bittorrent.shared.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@RequestBody AuthRequest request) { return auth.register(request); }
    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) { return auth.login(request); }
}
