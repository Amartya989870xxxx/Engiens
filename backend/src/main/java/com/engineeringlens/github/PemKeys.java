package com.engineeringlens.github;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** Reads the RSA private key GitHub issues for an App. */
final class PemKeys {

    // DER prefix of PKCS#8's AlgorithmIdentifier for rsaEncryption (OID 1.2.840.113549.1.1.1, NULL params).
    private static final byte[] RSA_ALGORITHM_ID = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00 };

    private PemKeys() {
    }

    /**
     * Accepts both PKCS#8 ("BEGIN PRIVATE KEY") and the PKCS#1 format GitHub
     * downloads ("BEGIN RSA PRIVATE KEY"), which Java cannot read directly.
     */
    static RSAPrivateKey readRsaPrivateKey(String pem) {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            byte[] pkcs8 = pkcs1 ? wrapPkcs1(der) : der;
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("GitHub App private key is not a valid RSA key", e);
        }
    }

    /** PKCS#8 = SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING { PKCS#1 key } }. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[] { 0x02, 0x01, 0x00 });
        body.writeBytes(RSA_ALGORITHM_ID);
        body.writeBytes(tlv(0x04, pkcs1));
        return tlv(0x30, body.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) {
                out.write(length >>> (8 * i));
            }
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
