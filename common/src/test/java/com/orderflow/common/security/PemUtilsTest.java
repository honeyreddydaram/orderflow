package com.orderflow.common.security;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PemUtilsTest {

    @Test
    void readPublicKey_and_readPrivateKey_roundTripGeneratedKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String publicPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        String privatePem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());

        RSAPublicKey publicKey = PemUtils.readPublicKey(publicPem);
        RSAPrivateKey privateKey = PemUtils.readPrivateKey(privatePem);

        assertThat(publicKey.getModulus()).isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus());
        assertThat(privateKey.getModulus()).isEqualTo(((RSAPrivateKey) keyPair.getPrivate()).getModulus());
    }

    @Test
    void readPublicKey_rejectsGarbage() {
        assertThatThrownBy(() -> PemUtils.readPublicKey("-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5\n-----END PUBLIC KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String toPem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }
}
