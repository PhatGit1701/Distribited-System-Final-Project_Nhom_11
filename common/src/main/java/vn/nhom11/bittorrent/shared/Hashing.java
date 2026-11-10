package vn.nhom11.bittorrent.shared;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {}
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String sha256(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    public static String fileId(String fileName, long fileSize, int chunkSize) {
        return sha256(fileName + fileSize + chunkSize);
    }
}
