package com.solesonic.service.user;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.repository.UserPreferencesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class UserPreferencesService {
    private static final Logger log = LoggerFactory.getLogger(UserPreferencesService.class);
    private final UserPreferencesRepository userPreferencesRepository;

    @Value("${spring.ai.ollama.chat.model}")
    private String chatModel;

    @Value("${spring.ai.similarity-threshold}")
    private Double similarityThreshold;

    @Value("${atlassian.service.account.user.id}")
    private UUID serviceAccountUserId;

    public UserPreferencesService(UserPreferencesRepository userPreferencesRepository) {
        this.userPreferencesRepository = userPreferencesRepository;
    }

    public UserPreferences get(UUID userId) {
        log.debug("Getting user preferences for user ID: {}", userId);

        UserPreferences userPreferences = userPreferencesRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserPreferences newPreferences = new UserPreferences();
                    newPreferences.setModel(chatModel);
                    newPreferences.setSimilarityThreshold(similarityThreshold);
                    return save(userId, newPreferences);
                });

        boolean hasAtlassianToken = userPreferences.getAtlassianAccessToken() != null;

        userPreferences.setAtlassianAuthentication(hasAtlassianToken);
        
        return userPreferences;
    }

    public List<UserPreferences> findAll() {
        return userPreferencesRepository.findAll();
    }

    public UserPreferences save(UUID userId, UserPreferences userPreferences) {
        log.info("Saving user preferences");

        userPreferences.setUserId(userId);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        return userPreferencesRepository.saveAndFlush(userPreferences);
    }

    public UserPreferences update(UUID userId, UserPreferences userPreferences) {
        log.info("Updating user preferences");
        userPreferences.setUserId(userId);
        userPreferences.setUpdated(ZonedDateTime.now());

        AtlassianAccessToken atlassianAccessToken = userPreferences.getAtlassianAccessToken();

        //Ensure that when updating user preferences, the token is preserved
        if (atlassianAccessToken == null) {
            UserPreferences existingPreferences = get(userId);
            AtlassianAccessToken existingToken = existingPreferences.getAtlassianAccessToken();
            userPreferences.setAtlassianAccessToken(existingToken);
        }

        return userPreferencesRepository.save(userPreferences);
    }

    public void save(UUID userId, AtlassianAccessToken atlassianAccessToken) {
        log.info("Saving atlassian access token");

        AtlassianAccessToken newToken = AtlassianAccessToken.from(atlassianAccessToken)
                .created(ZonedDateTime.now())
                .updated(ZonedDateTime.now())
                .build();

        UserPreferences userPreferences = get(userId);
        userPreferences.setAtlassianAccessToken(newToken);
        save(userId, userPreferences);
    }

    public void update(UUID userId, AtlassianAccessToken atlassianAccessToken) {
        log.info("Updating atlassian access token");

        UserPreferences userPreferences = get(userId);

        AtlassianAccessToken updatedToken = AtlassianAccessToken.from(atlassianAccessToken)
                .updated(ZonedDateTime.now())
                .build();

        userPreferences.setAtlassianAccessToken(updatedToken);

        update(userId, userPreferences);
    }

    public UserPreferences serviceAccount() {
        return get(serviceAccountUserId);
    }
}
