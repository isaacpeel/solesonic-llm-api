package com.solesonic.model.atlassian.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.io.IOException;

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

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(atlassianAccessToken);
            return encryptor.encrypt(bytes);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RuntimeException(jsonProcessingException);
        }
    }

    @Override
    public AtlassianAccessToken convertToEntityAttribute(byte[] bytes) {
        if(bytes == null) {
            return null;
        }


        try {
            byte[] decrypted = encryptor.decrypt(bytes);
            return objectMapper.readValue(decrypted, AtlassianAccessToken.class);
        } catch (IOException ioException) {
            throw new RuntimeException(ioException);
        }
    }
}
