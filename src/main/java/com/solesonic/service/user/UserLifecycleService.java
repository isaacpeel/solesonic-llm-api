package com.solesonic.service.user;

import com.solesonic.model.user.KeycloakAccount;
import com.solesonic.repository.user.UserLifecycleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Purges the memory data of users who are no longer around: those Keycloak no longer has, or has
 * disabled, and those who have not written a message in {@code dormant-days}. Reflection skips the
 * same users between sweeps ({@code ChatMemoryReflectionRepository}), so nothing purged here is
 * seeded straight back.
 * <p>
 * Removal is only ever concluded from Keycloak saying so. If the realm cannot be verified the
 * Keycloak half of the sweep is skipped, and an individual lookup that fails leaves that user as
 * they were.
 */
@Service
public class UserLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(UserLifecycleService.class);

    private final KeycloakUserClient keycloakUserClient;
    private final UserLifecycleRepository userLifecycleRepository;
    private final UserProfileService userProfileService;
    private final long dormantDays;
    private final long keycloakRecheckHours;

    public UserLifecycleService(KeycloakUserClient keycloakUserClient,
                                UserLifecycleRepository userLifecycleRepository,
                                UserProfileService userProfileService,
                                @Value("${solesonic.user.lifecycle.dormant-days}") long dormantDays,
                                @Value("${solesonic.user.lifecycle.keycloak-recheck-hours}") long keycloakRecheckHours) {
        this.keycloakUserClient = keycloakUserClient;
        this.userLifecycleRepository = userLifecycleRepository;
        this.userProfileService = userProfileService;
        this.dormantDays = dormantDays;
        this.keycloakRecheckHours = keycloakRecheckHours;
    }

    public UserLifecycleRun sweep() {
        ZonedDateTime now = ZonedDateTime.now();
        boolean keycloakReachable = verifyRealm();
        int checked = 0;
        int removed = 0;
        int purged = 0;
        int failures = 0;

        if (keycloakReachable) {
            for (UUID userId : userLifecycleRepository.ownersToCheck(now.minusHours(keycloakRecheckHours))) {
                try {
                    if (check(userId)) {
                        removed++;
                    }

                    checked++;
                } catch (RuntimeException exception) {
                    failures++;
                    log.warn("Could not look up user {} in Keycloak ({})", userId, exception.getClass().getSimpleName());
                }
            }
        }

        for (UUID userId : userLifecycleRepository.inactiveOwners(now.minusDays(dormantDays))) {
            try {
                int deleted = userLifecycleRepository.purgeMemoryData(userId);
                purged++;
                log.info("Purged {} memory row(s) of inactive user {}", deleted, userId);
            } catch (RuntimeException exception) {
                failures++;
                log.warn("Could not purge the memory data of user {} ({})", userId, exception.getClass().getSimpleName());
            }
        }

        UserLifecycleRun run = new UserLifecycleRun(keycloakReachable, checked, removed, purged, failures);
        log.info("User lifecycle sweep: {}", run);

        return run;
    }

    /**
     * @return true when Keycloak no longer has the user, or has disabled them
     */
    private boolean check(UUID userId) {
        Optional<KeycloakAccount> account = keycloakUserClient.find(userId);

        if (account.isPresent() && account.get().isActive()) {
            userLifecycleRepository.recordFound(userId, account.get().username(), account.get().email());
            return false;
        }

        userLifecycleRepository.recordRemoved(userId);
        userProfileService.forget(userId);

        return true;
    }

    private boolean verifyRealm() {
        try {
            keycloakUserClient.verifyRealm();
            return true;
        } catch (RuntimeException exception) {
            log.warn("Keycloak realm could not be verified; no user will be checked or marked removed ({})",
                    exception.getClass().getSimpleName());
            return false;
        }
    }
}
