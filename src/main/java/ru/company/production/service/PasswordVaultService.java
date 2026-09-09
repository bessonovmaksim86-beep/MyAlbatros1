package ru.company.production.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class PasswordVaultService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordVaultService(
            @Value("${application.password-encryption-key}")
            String base64Key
    ) {
        byte[] key = Base64.getDecoder().decode(base64Key);

        if (key.length != 32) {
            throw new IllegalArgumentException(
                    "PASSWORD_ENCRYPTION_KEY должен содержать "
                            + "32 байта в формате Base64"
            );
        }

        this.secretKey = new SecretKeySpec(key, "AES");
    }

    public String encrypt(String password) {
        if (password == null) {
            return null;
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_LENGTH, iv)
            );

            byte[] encrypted = cipher.doFinal(
                    password.getBytes(StandardCharsets.UTF_8)
            );

            byte[] result = ByteBuffer
                    .allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Не удалось зашифровать пароль",
                    exception
            );
        }
    }

    public String decrypt(String encryptedPassword) {
        if (encryptedPassword == null || encryptedPassword.isBlank()) {
            return "Недоступен";
        }

        try {
            byte[] source = Base64.getDecoder()
                    .decode(encryptedPassword);

            ByteBuffer buffer = ByteBuffer.wrap(source);

            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);

            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(TAG_LENGTH, iv)
            );

            return new String(
                    cipher.doFinal(encrypted),
                    StandardCharsets.UTF_8
            );
        } catch (Exception exception) {
            return "Ошибка расшифровки";
        }
    }
}
