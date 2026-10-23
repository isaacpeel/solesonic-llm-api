package com.solesonic.service.user;

import com.solesonic.repository.user.UserProfileRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The user's own name, as their identity provider gives it, for work that has no token to read it
 * from. Recording it never fails a request.
 */
@Service
public class UserProfileService {
    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    static final String GIVEN_NAME = "given_name";
    static final String NAME = "name";
    static final int MAX_DISPLAY_NAME_CHARACTERS = 128;

    private final UserProfileRepository userProfileRepository;
    private final Map<UUID, String> recordedNames = new ConcurrentHashMap<>();

    public UserProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public void record(UUID userId, Jwt jwt) {
        String displayName = displayName(jwt);

        if (displayName == null || displayName.equals(recordedNames.get(userId))) {
            return;
        }

        try {
            userProfileRepository.saveDisplayName(userId, displayName);
            recordedNames.put(userId, displayName);
        } catch (RuntimeException exception) {
            log.warn("Could not record the display name of user {} ({})", userId, exception.getClass().getSimpleName());
        }
    }

    public Optional<String> displayName(UUID userId) {
        return userProfileRepository.findDisplayName(userId);
    }

    static String displayName(Jwt jwt) {
        String name = StringUtils.firstNonBlank(jwt.getClaimAsString(GIVEN_NAME), jwt.getClaimAsString(NAME));

        if (name == null) {
            return null;
        }

        return StringUtils.truncate(StringUtils.normalizeSpace(name), MAX_DISPLAY_NAME_CHARACTERS);
    }
}
