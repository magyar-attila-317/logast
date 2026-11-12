package dev.logast.parse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private Hashing() {
    }

    public static String sha256(String s) {
        return hex("SHA-256", s);
    }

    public static String shortId(String s) {
        return hex("SHA-1", s).substring(0, 6);
    }

    private static String hex(String alg, String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
