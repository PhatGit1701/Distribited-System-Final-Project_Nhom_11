package vn.nhom11.bittorrent.tracker;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import vn.nhom11.bittorrent.shared.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
 private final UserRepository users; private final PasswordEncoder passwords; private final JwtEncoder encoder;
 private final Clock clock; private final long ttl; private final String dummyHash;
 public AuthController(UserRepository users,PasswordEncoder passwords,JwtEncoder encoder,Clock clock,
     @Value("${tracker.jwt.ttl-seconds:3600}") long ttl) {
   this.users=users;this.passwords=passwords;this.encoder=encoder;this.clock=clock;this.ttl=ttl;
   if(ttl<1) throw new IllegalArgumentException("JWT TTL must be positive");
   dummyHash=passwords.encode("dummy-password-for-timing");
 }
 @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
 public AuthResponse register(@RequestBody AuthRequest request) {
   validate(request, true);
   if(users.findByUsername(request.username()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists");
   try { return token(users.saveAndFlush(new UserAccount(request.username(),passwords.encode(request.password())))); }
   catch(DataIntegrityViolationException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists"); }
 }
 @PostMapping("/login")
 public AuthResponse login(@RequestBody AuthRequest request) {
   validate(request, false);
   var user=users.findByUsername(request.username());
   boolean matches=passwords.matches(request.password(),user.map(UserAccount::getPasswordHash).orElse(dummyHash));
   if(user.isEmpty()||!matches) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
   return token(user.get());
 }
 private void validate(AuthRequest r, boolean registering) {
   if(r.username()==null||!r.username().matches("[a-zA-Z0-9_]{3,40}")||r.password()==null||(registering && r.password().length()<8)
       ||r.password().getBytes(StandardCharsets.UTF_8).length>72)
     throw new IllegalArgumentException("Username: 3-40 letters/digits/underscore; password: 8+ characters, max 72 UTF-8 bytes");
 }
 private AuthResponse token(UserAccount user) {
   Instant now=clock.instant(),expiry=now.plusSeconds(ttl);
   var claims=JwtClaimsSet.builder().issuer("bittorrent-lite-tracker").subject(user.getId().toString())
     .claim("userId",user.getId()).claim("username",user.getUsername()).issuedAt(now).expiresAt(expiry).build();
   String jwt=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
   return new AuthResponse(jwt,"Bearer",user.getId(),user.getUsername(),expiry);
 }
}
