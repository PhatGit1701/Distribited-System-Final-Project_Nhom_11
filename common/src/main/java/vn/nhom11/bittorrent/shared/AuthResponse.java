package vn.nhom11.bittorrent.shared;

import java.time.Instant;

public record AuthResponse(String token, String tokenType, long userId, String username, Instant expiresAt) {}
