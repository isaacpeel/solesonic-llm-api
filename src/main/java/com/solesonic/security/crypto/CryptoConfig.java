package com.solesonic.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;

@Configuration
public class CryptoConfig {

    @Bean
    public BytesEncryptor bytesEncryptor(@Value("${encryption.password}") String password,
                                         @Value("${encryption.salt}") String salt) {
        return Encryptors.stronger(password, salt);
    }
}
