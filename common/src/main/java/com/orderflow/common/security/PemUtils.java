package com.orderflow.common.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Parses PEM-encoded RSA keys (PKCS#8 private / X.509 public, the formats produced by
 * {@code openssl genpkey}/{@code openssl rsa -pubout}) into JCA key objects. Used to load the
 * Auth Service's signing key and every resource server's verification key from files mounted
 * outside version control (see docker/generate-jwt-keys.sh).
 */
public final class PemUtils {

    private PemUtils() {
    }

    public static RSAPublicKey readPublicKey(String pem) {
        try {
            byte[] der = stripPemHeaders(pem);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid RSA public key PEM", e);
        }
    }

    public static RSAPrivateKey readPrivateKey(String pem) {
        try {
            byte[] der = stripPemHeaders(pem);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalArgumentException("Invalid RSA private key PEM (must be PKCS#8)", e);
        }
    }

    private static byte[] stripPemHeaders(String pem) {
        String cleaned = pem
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }
}
