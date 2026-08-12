package com.solesonic.service.user;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.google.auth.GoogleAccessToken;
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
                .orElseGet(() -> createDefaults(userId));

        return applyAuthenticationFlags(userPreferences);
    }

    /**
     * The first-touch path for a user with no row yet. Writes through the repository rather than
     * {@link #save(UUID, UserPreferences)} because a brand-new row has no stored tokens to
     * preserve, and routing through that method would cost a second lookup to discover as much.
     */
    private UserPreferences createDefaults(UUID userId) {
        UserPreferences newPreferences = new UserPreferences();
        newPreferences.setUserId(userId);
        newPreferences.setModel(chatModel);
        newPreferences.setSimilarityThreshold(similarityThreshold);
        newPreferences.setCreated(ZonedDateTime.now());
        newPreferences.setUpdated(ZonedDateTime.now());

        return userPreferencesRepository.saveAndFlush(newPreferences);
    }

    /**
     * Populates the transient booleans the client reads. Since neither token is serialized, these
     * are the <em>only</em> signal a client has for whether an integration is connected, so every
     * method that hands a {@link UserPreferences} back has to set them — not just {@code get}.
     */
    private static UserPreferences applyAuthenticationFlags(UserPreferences userPreferences) {
        userPreferences.setAtlassianAuthentication(userPreferences.getAtlassianAccessToken() != null);
        userPreferences.setGoogleAuthentication(userPreferences.getGoogleAccessToken() != null);

        return userPreferences;
    }

    public List<UserPreferences> findAll() {
        return userPreferencesRepository.findAll();
    }

    public UserPreferences save(UUID userId, UserPreferences userPreferences) {
        log.debug("Saving user preferences");

        userPreferences.setUserId(userId);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        preserveExistingTokens(userId, userPreferences);

        return applyAuthenticationFlags(userPreferencesRepository.saveAndFlush(userPreferences));
    }

    public UserPreferences update(UUID userId, UserPreferences userPreferences) {
        log.info("Updating user preferences");
        userPreferences.setUserId(userId);
        userPreferences.setUpdated(ZonedDateTime.now());

        preserveExistingTokens(userId, userPreferences);

        return applyAuthenticationFlags(userPreferencesRepository.save(userPreferences));
    }

    /**
     * Keeps stored tokens from being wiped by a write that simply does not carry them. Since
     * neither token is serialized to the client, no round trip can ever carry them back, so this
     * is the normal case for every write arriving from {@code UserController} — not an edge one.
     * <p>
     * Reads the row through the repository rather than {@link #get(UUID)}: that method creates and
     * saves a row when none exists, which is a surprising side effect for a guard whose only job
     * is to look.
     */
    private void preserveExistingTokens(UUID userId, UserPreferences userPreferences) {
        boolean missingAtlassianToken = userPreferences.getAtlassianAccessToken() == null;
        boolean missingGoogleToken = userPreferences.getGoogleAccessToken() == null;

        if (!missingAtlassianToken && !missingGoogleToken) {
            return;
        }

        userPreferencesRepository.findByUserId(userId).ifPresent(existingPreferences -> {
            if (missingAtlassianToken) {
                userPreferences.setAtlassianAccessToken(existingPreferences.getAtlassianAccessToken());
            }

            if (missingGoogleToken) {
                userPreferences.setGoogleAccessToken(existingPreferences.getGoogleAccessToken());
            }
        });
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
        log.debug("Updating atlassian access token");

        UserPreferences userPreferences = get(userId);

        AtlassianAccessToken updatedToken = AtlassianAccessToken.from(atlassianAccessToken)
                .updated(ZonedDateTime.now())
                .build();

        userPreferences.setAtlassianAccessToken(updatedToken);

        update(userId, userPreferences);
    }

    public void save(UUID userId, GoogleAccessToken googleAccessToken) {
        log.info("Saving google access token");

        UserPreferences userPreferences = get(userId);
        userPreferences.setGoogleAccessToken(googleAccessToken);

        save(userId, userPreferences);
    }

    public void update(UUID userId, GoogleAccessToken googleAccessToken) {
        log.debug("Updating google access token");

        UserPreferences userPreferences = get(userId);

        GoogleAccessToken updatedToken = GoogleAccessToken.from(googleAccessToken)
                .updated(ZonedDateTime.now())
                .build();

        userPreferences.setGoogleAccessToken(updatedToken);

        update(userId, userPreferences);
    }

    /**
     * Forgets a user's Google grant. Goes straight to the repository rather than through
     * {@link #update(UUID, UserPreferences)}, whose null-token guard exists to stop an unrelated
     * preferences update from wiping a token — and would therefore restore the very token this is
     * trying to remove.
     */
    public void clearGoogleAccessToken(UUID userId) {
        log.info("Clearing google access token");

        UserPreferences userPreferences = get(userId);
        userPreferences.setGoogleAccessToken(null);
        userPreferences.setGoogleAuthentication(false);
        userPreferences.setUpdated(ZonedDateTime.now());

        userPreferencesRepository.save(userPreferences);
    }

    public UserPreferences serviceAccount() {
        return get(serviceAccountUserId);
    }
}
