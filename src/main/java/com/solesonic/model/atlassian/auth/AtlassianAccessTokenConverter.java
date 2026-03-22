package com.solesonic.model.atlassian.auth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import tools.jackson.databind.ObjectMapper;

@Converter
public class AtlassianAccessTokenConverter implements AttributeConverter<AtlassianAccessToken, byte[]> {

    private final BytesEncryptor encryptor;
    private final ObjectMapper objectMapper;

    public AtlassianAccessTokenConverter(BytesEncryptor encryptor,
                                         ObjectMapper objectMapper) {
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
    }


    @Override
    public byte[] convertToDatabaseColumn(AtlassianAccessToken atlassianAccessToken) {
        if(atlassianAccessToken == null) {
            return null;
        }

        byte[] bytes = objectMapper.writeValueAsBytes(atlassianAccessToken);
        return encryptor.encrypt(bytes);
    }

    @Override
    public AtlassianAccessToken convertToEntityAttribute(byte[] bytes) {
        if(bytes == null) {
            return null;
        }

        byte[] decrypted = encryptor.decrypt(bytes);
        return objectMapper.readValue(decrypted, AtlassianAccessToken.class);
    }
}
