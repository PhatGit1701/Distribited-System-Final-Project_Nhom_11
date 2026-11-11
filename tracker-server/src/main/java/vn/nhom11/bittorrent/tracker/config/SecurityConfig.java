package vn.nhom11.bittorrent.tracker.config;

import vn.nhom11.bittorrent.shared.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;

@Configuration
public class SecurityConfig {
 @Bean Clock clock() { return Clock.systemUTC(); }
 @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
 @Bean SecretKey jwtKey(@Value("${tracker.jwt.secret:}") String secret) {
   byte[] bytes;
   if (secret.isBlank()) { bytes=new byte[32]; new SecureRandom().nextBytes(bytes); }
   else { bytes=secret.getBytes(StandardCharsets.UTF_8); if(bytes.length<32) throw new IllegalArgumentException("JWT secret must be at least 32 UTF-8 bytes"); }
   return new SecretKeySpec(bytes,"HmacSHA256");
 }
 @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
 @Bean JwtDecoder jwtDecoder(SecretKey key) {
   var decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
   var timestamp=new JwtTimestampValidator(Duration.ZERO);
   decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
       timestamp,new JwtIssuerValidator("bittorrent-lite-tracker")));
   return decoder;
 }
 @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
   return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
     .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.POST,"/api/auth/register","/api/auth/login").permitAll()
       .requestMatchers("/ws/tracker").permitAll().anyRequest().authenticated())
     .exceptionHandling(e->e.authenticationEntryPoint((request,response,error)->writeError(json,request,response,ErrorCode.AUTHENTICATION_REQUIRED))
       .accessDeniedHandler((request,response,error)->writeError(json,request,response,ErrorCode.FORBIDDEN)))
     .oauth2ResourceServer(o->o.jwt(j->{})
       .authenticationEntryPoint((request,response,error)->writeError(json,request,response,ErrorCode.AUTHENTICATION_REQUIRED))
       .accessDeniedHandler((request,response,error)->writeError(json,request,response,ErrorCode.FORBIDDEN))).build();
 }
 private static void writeError(ObjectMapper json, jakarta.servlet.http.HttpServletRequest request,
     jakarta.servlet.http.HttpServletResponse response, ErrorCode code) throws java.io.IOException {
   response.setStatus(code.status());
   response.setContentType("application/json");
   response.setCharacterEncoding("UTF-8");
   if(code.status()==401) response.setHeader("WWW-Authenticate","Bearer");
   json.writeValue(response.getOutputStream(),ErrorResponse.of(code,null,request.getRequestURI()));
 }
}
