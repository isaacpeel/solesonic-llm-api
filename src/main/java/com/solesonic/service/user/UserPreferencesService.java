package com.solesonic.service.user;

import com.solesonic.model.address.Address;
import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.google.auth.GoogleAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.model.xero.auth.XeroAccessToken;
import com.solesonic.repository.AddressRepository;
import com.solesonic.repository.UserPreferencesRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class UserPreferencesService {
    private static final Logger log = LoggerFactory.getLogger(UserPreferencesService.class);
    private final UserPreferencesRepository userPreferencesRepository;
    private final AddressRepository addressRepository;

    @Value("${solesonic.llm.retrieval.similarity-threshold.chat}")
    private Double chatSimilarityThreshold;

    @Value("${solesonic.llm.retrieval.similarity-threshold.user}")
    private Double userSimilarityThreshold;

    @Value("${solesonic.llm.retrieval.similarity-threshold.global}")
    private Double globalSimilarityThreshold;

    @Value("${atlassian.service.account.user.id}")
    private UUID serviceAccountUserId;

    public UserPreferencesService(UserPreferencesRepository userPreferencesRepository, AddressRepository addressRepository) {
        this.userPreferencesRepository = userPreferencesRepository;
        this.addressRepository = addressRepository;
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
        newPreferences.setChatSimilarityThreshold(chatSimilarityThreshold);
        newPreferences.setUserSimilarityThreshold(userSimilarityThreshold);
        newPreferences.setGlobalSimilarityThreshold(globalSimilarityThreshold);
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
        userPreferences.setXeroAuthentication(userPreferences.getXeroAccessToken() != null);

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
     * Backs {@code PUT /users/{userId}/preferences/{addressId}}, the one place
     * {@code UserPreferences.addressId} is ever set. A {@code 404} rather than a silent no-op if
     * the address does not exist, since a dangling foreign key would only surface later, on
     * whatever tries to resolve it through {@code AddressService}.
     */
    public UserPreferences linkAddress(UUID userId, UUID addressId) {
        log.info("Linking address {} to user {}", addressId, userId);

        if (!addressRepository.existsById(addressId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found: " + addressId);
        }

        UserPreferences userPreferences = get(userId);
        userPreferences.setAddressId(addressId);
        userPreferences.setUpdated(ZonedDateTime.now());

        return applyAuthenticationFlags(userPreferencesRepository.save(userPreferences));
    }

    /**
     * {@code UserPreferences.addressId} is a plain foreign key, never a mapped relationship (see its
     * Javadoc), so resolving it to an {@link Address} goes through {@link AddressRepository} here
     * rather than through the entity. Reuses {@code findByIdAndUserId} rather than a plain
     * {@code findById} so a dangling or mismatched reference surfaces as {@code 404} instead of
     * silently returning someone else's address.
     */
    public Address getAddress(UUID userId) {
        log.debug("Getting address for user {}", userId);

        UserPreferences userPreferences = get(userId);
        UUID addressId = userPreferences.getAddressId();

        if (addressId == null) {
            return null;
        }

        return addressRepository.findByIdAndUserId(addressId, userId)
                .orElse(null);
    }

    public String getTimeZone(UUID userId) {
        log.debug("Getting time zone for user {}", userId);

        return get(userId).getTimeZone();
    }

    public ZoneId getZone(UUID userId) {
        return resolveZone(getTimeZone(userId));
    }

    /**
     * {@code UserPreferences.timeZone} has no validation at its write boundary yet, so a malformed
     * IANA id reaching here falls back to UTC rather than failing the turn.
     */
    private static ZoneId resolveZone(String timeZone) {
        if (StringUtils.isEmpty(timeZone)) {
            return ZoneOffset.UTC;
        }

        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            return ZoneOffset.UTC;
        }
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
        boolean missingXeroToken = userPreferences.getXeroAccessToken() == null;

        if (!missingAtlassianToken && !missingGoogleToken && !missingXeroToken) {
            return;
        }

        userPreferencesRepository.findByUserId(userId).ifPresent(existingPreferences -> {
            if (missingAtlassianToken) {
                userPreferences.setAtlassianAccessToken(existingPreferences.getAtlassianAccessToken());
            }

            if (missingGoogleToken) {
                userPreferences.setGoogleAccessToken(existingPreferences.getGoogleAccessToken());
            }

            if (missingXeroToken) {
                userPreferences.setXeroAccessToken(existingPreferences.getXeroAccessToken());
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

    /**
     * Unlike the Google equivalent, this stamps {@code created} as well as {@code updated} rather
     * than trusting the caller to have done it. {@link XeroAccessToken#isExpired()} reads a token
     * with no {@code created} as expired, so an unstamped token would force a refresh on every
     * single call — and Xero invalidates the old refresh token on each rotation, making that
     * needless churn expensive rather than merely wasteful.
     */
    public void save(UUID userId, XeroAccessToken xeroAccessToken) {
        log.info("Saving xero access token");

        XeroAccessToken newToken = XeroAccessToken.from(xeroAccessToken)
                .created(ZonedDateTime.now())
                .updated(ZonedDateTime.now())
                .build();

        UserPreferences userPreferences = get(userId);
        userPreferences.setXeroAccessToken(newToken);

        save(userId, userPreferences);
    }

    /**
     * Persists whatever the refresh returned, rotated refresh token included. Xero invalidates the
     * previous refresh token the moment a new one is issued, so there is deliberately no
     * carry-the-old-one-forward branch here — the Google equivalent needs one only because Google
     * omits the refresh token from most responses.
     */
    public void update(UUID userId, XeroAccessToken xeroAccessToken) {
        log.debug("Updating xero access token");

        UserPreferences userPreferences = get(userId);

        XeroAccessToken updatedToken = XeroAccessToken.from(xeroAccessToken)
                .updated(ZonedDateTime.now())
                .build();

        userPreferences.setXeroAccessToken(updatedToken);

        update(userId, userPreferences);
    }

    /**
     * Forgets a user's Xero grant locally. Goes straight to the repository rather than through
     * {@link #update(UUID, UserPreferences)}, whose null-token guard exists to stop an unrelated
     * preferences update from wiping a token — and would therefore restore the very token this is
     * trying to remove.
     * <p>
     * Local only: Xero publishes no app-level token revocation endpoint, so the grant itself stays
     * live until the user removes the app from Xero's own "Connected apps" screen.
     */
    public void clearXeroAccessToken(UUID userId) {
        log.info("Clearing xero access token");

        UserPreferences userPreferences = get(userId);
        userPreferences.setXeroAccessToken(null);
        userPreferences.setXeroAuthentication(false);
        userPreferences.setUpdated(ZonedDateTime.now());

        userPreferencesRepository.save(userPreferences);
    }

    public UserPreferences serviceAccount() {
        return get(serviceAccountUserId);
    }
}
