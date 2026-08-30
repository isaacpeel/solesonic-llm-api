package com.solesonic.model.xero.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Encrypts the whole token, refresh token included, before it reaches the database. Uses the same
 * {@link BytesEncryptor} as the Atlassian and Google converters, so all three are governed by one
 * {@code ENCRYPTION_PASSWORD}/{@code ENCRYPTION_SALT} pair.
 */
@Converter
public class XeroAccessTokenConverter implements AttributeConverter<XeroAccessToken, byte[]> {

    private final BytesEncryptor encryptor;
    private final ObjectMapper objectMapper;

    public XeroAccessTokenConverter(BytesEncryptor encryptor,
                                    ObjectMapper objectMapper) {
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] convertToDatabaseColumn(XeroAccessToken xeroAccessToken) {
        if (xeroAccessToken == null) {
            return null;
        }

        byte[] bytes = objectMapper.writeValueAsBytes(xeroAccessToken);

        return encryptor.encrypt(bytes);
    }

    @Override
    public XeroAccessToken convertToEntityAttribute(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        byte[] decrypted = encryptor.decrypt(bytes);

        return objectMapper.readValue(decrypted, XeroAccessToken.class);
    }
}
