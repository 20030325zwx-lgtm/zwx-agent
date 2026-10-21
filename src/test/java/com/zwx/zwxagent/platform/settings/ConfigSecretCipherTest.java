package com.zwx.zwxagent.platform.settings;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConfigSecretCipherTest {

    private final ConfigSecretCipher cipher = new ConfigSecretCipher("unit-test-encryption-key");

    @Test
    void encryptsAndDecryptsWithoutStoringPlaintext() {
        String ciphertext = cipher.encrypt("sk-secret-1234");
        Assertions.assertNotNull(ciphertext);
        Assertions.assertFalse(ciphertext.contains("sk-secret"));
        Assertions.assertEquals("sk-secret-1234", cipher.decrypt(ciphertext));
        Assertions.assertEquals("****1234", cipher.hint("sk-secret-1234"));
    }

    @Test
    void differentNonceProducesDifferentCiphertext() {
        Assertions.assertNotEquals(cipher.encrypt("same-value"), cipher.encrypt("same-value"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String ciphertext = cipher.encrypt("sk-secret-1234");
        Assertions.assertThrows(IllegalStateException.class,
                () -> cipher.decrypt(ciphertext.substring(0, ciphertext.length() - 4)));
    }
}
