package com.solesonic.security.local;

import com.solesonic.model.user.UserPreferences;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.user.UserPreferencesService;
import jakarta.annotation.Nonnull;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
@Order(1)
@Profile({"test", "local"})
public class LocalJwtUserRequestFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(LocalJwtUserRequestFilter.class);
    public static final String ISS = "iss";

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    public static final String SUB = "sub";
    private final UserRequestContext userRequestContext;

    private final UserPreferencesService userPreferencesService;

    public LocalJwtUserRequestFilter(UserRequestContext userRequestContext,
                                     UserPreferencesService userPreferencesService) {
        this.userRequestContext = userRequestContext;
        this.userPreferencesService = userPreferencesService;
    }

    @Override
    protected void doFilterInternal(@Nonnull HttpServletRequest request,
                                    @Nonnull HttpServletResponse response,
                                    @Nonnull FilterChain filterChain) throws ServletException, IOException {
        log.info("Filtering request for local dev.");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID userId;

        //If running the UI locally there will be an authentication
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String issuer = jwt.getClaimAsString(ISS);

//            if(issuerUri.equalsIgnoreCase(issuer)) {
//                //This is direct from Oauth2
//                userId = defaultUserId();
//            } else {
                //This is from UI
                String jwtClaim = jwt.getClaimAsString(SUB);
                userId = UUID.fromString(jwtClaim);
//            }
        } else {
            userId = defaultUserId();
        }

        userRequestContext.setUserId(userId);

        filterChain.doFilter(request, response);
    }

    private UUID defaultUserId() {
        List<UserPreferences> allUserPreferences = userPreferencesService.findAll();

        UserPreferences userPreferences;

        if(CollectionUtils.isEmpty(allUserPreferences)) {
            //This will save new user preferences with the random ID
            userPreferences = userPreferencesService.get(UUID.randomUUID());
        } else {
            //Get the first saved user preferences if any
            userPreferences = allUserPreferences.getFirst();
        }

        return userPreferences.getUserId();
    }
}
