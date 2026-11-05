package com.solesonic.service.user;

import com.solesonic.config.user.UserDirectorySettings;
import com.solesonic.model.user.UserDirectoryEntry;
import com.solesonic.repository.user.UserProfileRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The people a user can pick to share a chat with. Deliberately narrow: id, username and display name
 * of active accounts, never the email and never the caller.
 */
@Service
public class UserDirectoryService {
    private static final int MAX_QUERY_CHARACTERS = 64;

    private final UserProfileRepository userProfileRepository;
    private final UserDirectorySettings userDirectorySettings;

    public UserDirectoryService(UserProfileRepository userProfileRepository, UserDirectorySettings userDirectorySettings) {
        this.userProfileRepository = userProfileRepository;
        this.userDirectorySettings = userDirectorySettings;
    }

    public boolean enabled() {
        return userDirectorySettings.enabled();
    }

    /**
     * @param limit how many to return at most; null, or beyond the configured maximum, means the
     *              maximum, and anything below 1 means 1
     */
    public List<UserDirectoryEntry> search(UUID callerUserId, String query, Integer limit) {
        if (!userDirectorySettings.enabled()) {
            return List.of();
        }

        int effectiveLimit = limit == null
                ? userDirectorySettings.maxResults()
                : Math.max(1, Math.min(limit, userDirectorySettings.maxResults()));

        String text = query == null ? "" : query.strip();

        //Nobody has a username longer than this, and an unbounded string would be a pattern for the database to scan with.
        if (text.length() > MAX_QUERY_CHARACTERS) {
            text = text.substring(0, MAX_QUERY_CHARACTERS);
        }

        Set<String> seen = new HashSet<>();

        return userProfileRepository.searchActive(text, callerUserId, effectiveLimit).stream()
                .filter(entry -> seen.add(entry.username().toLowerCase(Locale.ROOT)))
                .toList();
    }
}
