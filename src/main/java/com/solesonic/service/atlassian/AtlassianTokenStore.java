package com.solesonic.service.atlassian;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;

import java.util.Optional;
import java.util.UUID;

public interface AtlassianTokenStore {

    Optional<AtlassianAccessToken> load(UUID userId);

    void save(AtlassianAccessToken token);

    Optional<AtlassianAccessToken> loadAdmin();

    void saveAdmin(AtlassianAccessToken token);

    Optional<Boolean> exists(UUID userId);
}