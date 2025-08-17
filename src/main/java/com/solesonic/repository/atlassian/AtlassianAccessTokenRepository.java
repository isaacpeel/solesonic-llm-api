package com.solesonic.repository.atlassian;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AtlassianAccessTokenRepository extends JpaRepository<AtlassianAccessToken, UUID> {

    @Query(value = """
                from AtlassianAccessToken atlassianAccessToken
                    where atlassianAccessToken.administrator = true
            """)
    Optional<AtlassianAccessToken> findAdminUser();
}
