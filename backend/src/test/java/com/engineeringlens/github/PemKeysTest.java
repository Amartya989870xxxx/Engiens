package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class PemKeysTest {

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    @Test
    void readsBothPkcs8AndTheGitHubPkcs1Format() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey key = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        byte[] pkcs8 = key.getEncoded();
        // For a 2048-bit RSA key the PKCS#8 header before the PKCS#1 body is exactly 26 bytes.
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);

        assertThat(PemKeys.readRsaPrivateKey(pem("PRIVATE KEY", pkcs8))).isEqualTo(key);
        assertThat(PemKeys.readRsaPrivateKey(pem("RSA PRIVATE KEY", pkcs1))).isEqualTo(key);
    }
}
