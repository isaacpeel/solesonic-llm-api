package com.solesonic.model.google.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Encrypts the whole token, refresh token included, before it reaches the database. Uses the same
 * {@link BytesEncryptor} as the Atlassian converter, so both are governed by one
 * {@code ENCRYPTION_PASSWORD}/{@code ENCRYPTION_SALT} pair.
 */
@Converter
public class GoogleAccessTokenConverter implements AttributeConverter<GoogleAccessToken, byte[]> {

    private final BytesEncryptor encryptor;
    private final ObjectMapper objectMapper;

    public GoogleAccessTokenConverter(BytesEncryptor encryptor,
                                      ObjectMapper objectMapper) {
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] convertToDatabaseColumn(GoogleAccessToken googleAccessToken) {
        if (googleAccessToken == null) {
            return null;
        }

        byte[] bytes = objectMapper.writeValueAsBytes(googleAccessToken);

        return encryptor.encrypt(bytes);
    }

    @Override
    public GoogleAccessToken convertToEntityAttribute(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        byte[] decrypted = encryptor.decrypt(bytes);

        return objectMapper.readValue(decrypted, GoogleAccessToken.class);
    }
}
