package com.solesonic.service.user;

import com.solesonic.model.user.UserProfile;
import com.solesonic.repository.user.UserProfileRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who each user id belongs to — username, email and name, as their identity provider gives them —
 * for work that has no token to read it from. Recording it never fails a request, and neither does
 * reading many at once.
 */
@Service
public class UserProfileService {
    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    static final String GIVEN_NAME = "given_name";
    static final String NAME = "name";
    static final String PREFERRED_USERNAME = "preferred_username";
    static final String EMAIL = "email";
    static final int MAX_DISPLAY_NAME_CHARACTERS = 128;
    static final int MAX_USERNAME_CHARACTERS = 255;
    static final int MAX_EMAIL_CHARACTERS = 320;

    private final UserProfileRepository userProfileRepository;
    private final Map<UUID, UserProfile> recordedProfiles = new ConcurrentHashMap<>();

    public UserProfileService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public void record(UUID userId, Jwt jwt) {
        UserProfile userProfile = profile(userId, jwt);

        if (userProfile.isEmpty() || userProfile.equals(recordedProfiles.get(userId))) {
            return;
        }

        try {
            userProfileRepository.save(userProfile);
            recordedProfiles.put(userId, userProfile);
        } catch (RuntimeException exception) {
            log.warn("Could not record the profile of user {} ({})", userId, exception.getClass().getSimpleName());
        }
    }

    public Optional<UserProfile> profile(UUID userId) {
        return userProfileRepository.findById(userId);
    }

    /**
     * One query for every id. A user with no recorded profile is absent from the map, and a failed
     * read is an empty map: a missing name is never worth failing a listing over.
     */
    public Map<UUID, UserProfile> profiles(Collection<UUID> userIds) {
        Set<UUID> distinctUserIds = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        try {
            return userProfileRepository.findAllById(distinctUserIds)
                    .stream()
                    .collect(Collectors.toMap(UserProfile::userId, Function.identity()));
        } catch (RuntimeException exception) {
            log.warn("Could not read the profiles of {} user(s) ({})", distinctUserIds.size(), exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * @return each user's username, absent for a user with none recorded
     */
    public Map<UUID, String> usernames(Collection<UUID> userIds) {
        Map<UUID, String> usernames = new HashMap<>();

        profiles(userIds).forEach((userId, userProfile) -> {
            if (userProfile.username() != null) {
                usernames.put(userId, userProfile.username());
            }
        });

        return usernames;
    }

    public Optional<String> username(UUID userId) {
        return profile(userId).map(UserProfile::username);
    }

    public Optional<String> email(UUID userId) {
        return profile(userId).map(UserProfile::email);
    }

    public Optional<String> displayName(UUID userId) {
        return profile(userId).map(UserProfile::displayName);
    }

    static UserProfile profile(UUID userId, Jwt jwt) {
        return new UserProfile(userId,
                claim(jwt.getClaimAsString(PREFERRED_USERNAME), MAX_USERNAME_CHARACTERS),
                claim(jwt.getClaimAsString(EMAIL), MAX_EMAIL_CHARACTERS),
                displayName(jwt));
    }

    static String displayName(Jwt jwt) {
        return claim(StringUtils.firstNonBlank(jwt.getClaimAsString(GIVEN_NAME), jwt.getClaimAsString(NAME)),
                MAX_DISPLAY_NAME_CHARACTERS);
    }

    private static String claim(String value, int maxCharacters) {
        if (StringUtils.isBlank(value)) {
            return null;
        }

        return StringUtils.truncate(StringUtils.normalizeSpace(value), maxCharacters);
    }
}
