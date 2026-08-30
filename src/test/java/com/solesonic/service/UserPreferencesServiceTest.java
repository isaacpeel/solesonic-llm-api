package com.solesonic.service;

import com.solesonic.model.atlassian.auth.AtlassianAccessToken;
import com.solesonic.model.user.UserPreferences;
import com.solesonic.model.xero.auth.XeroAccessToken;
import com.solesonic.repository.UserPreferencesRepository;
import com.solesonic.service.user.UserPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserPreferencesServiceTest {

    @Mock
    private UserPreferencesRepository userPreferencesRepository;


    @InjectMocks
    private UserPreferencesService userPreferencesService;

    private UUID userId;
    private UserPreferences userPreferences;
    private final Double defaultChatSimilarityThreshold = 0.5;
    private final Double defaultUserSimilarityThreshold = 0.7;
    private final Double defaultGlobalSimilarityThreshold = 0.7;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        // Set up UserPreferences
        userPreferences = new UserPreferences();
        userPreferences.setUserId(userId);
        userPreferences.setChatSimilarityThreshold(defaultChatSimilarityThreshold);
        userPreferences.setUserSimilarityThreshold(defaultUserSimilarityThreshold);
        userPreferences.setGlobalSimilarityThreshold(defaultGlobalSimilarityThreshold);
        userPreferences.setCreated(ZonedDateTime.now());
        userPreferences.setUpdated(ZonedDateTime.now());

        userPreferences.setAtlassianAccessToken(new AtlassianAccessToken(
                UUID.randomUUID(),
                "accessToken",
                "refreshToken",
                "tokenType",
                "scope",
                1,
                false,
                null,
                null,
                null,
                "error"
        ));

        // Set default values using ReflectionTestUtils
        ReflectionTestUtils.setField(userPreferencesService, "chatSimilarityThreshold", defaultChatSimilarityThreshold);
        ReflectionTestUtils.setField(userPreferencesService, "userSimilarityThreshold", defaultUserSimilarityThreshold);
        ReflectionTestUtils.setField(userPreferencesService, "globalSimilarityThreshold", defaultGlobalSimilarityThreshold);
    }

    @Test
    void testGetExistingUserPreferences() {
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));
        UserPreferences result = userPreferencesService.get(userId);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getChatSimilarityThreshold()).isEqualTo(defaultChatSimilarityThreshold);
        assertThat(result.getUserSimilarityThreshold()).isEqualTo(defaultUserSimilarityThreshold);
        assertThat(result.getGlobalSimilarityThreshold()).isEqualTo(defaultGlobalSimilarityThreshold);
        assertThat(result.isAtlassianAuthentication()).isTrue();
        verify(userPreferencesRepository).findByUserId(userId);
    }

    @Test
    void testGetNonExistingUserPreferences() {
        
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.empty());

        userPreferences.setAtlassianAccessToken(null);
        when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class))).thenReturn(userPreferences);

        UserPreferences result = userPreferencesService.get(userId);

        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getChatSimilarityThreshold()).isEqualTo(defaultChatSimilarityThreshold);
        assertThat(result.getUserSimilarityThreshold()).isEqualTo(defaultUserSimilarityThreshold);
        assertThat(result.getGlobalSimilarityThreshold()).isEqualTo(defaultGlobalSimilarityThreshold);
        assertThat(result.isAtlassianAuthentication()).isFalse();
        verify(userPreferencesRepository).findByUserId(userId);
        verify(userPreferencesRepository).saveAndFlush(any(UserPreferences.class));
    }

    @Test
    void testFindAll() {
        
        UserPreferences anotherUserPreferences = new UserPreferences();
        anotherUserPreferences.setUserId(UUID.randomUUID());
        List<UserPreferences> allPreferences = Arrays.asList(userPreferences, anotherUserPreferences);
        
        when(userPreferencesRepository.findAll()).thenReturn(allPreferences);

        
        List<UserPreferences> result = userPreferencesService.findAll();

        
        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result).contains(userPreferences, anotherUserPreferences);
        verify(userPreferencesRepository).findAll();
    }

    @Test
    void testSave() {
        
        when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class))).thenReturn(userPreferences);

        
        UserPreferences result = userPreferencesService.save(userId, userPreferences);

        
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        verify(userPreferencesRepository).saveAndFlush(any(UserPreferences.class));
    }

    @Test
    void testUpdate() {
        
        when(userPreferencesRepository.save(any(UserPreferences.class))).thenReturn(userPreferences);

        
        UserPreferences result = userPreferencesService.update(userId, userPreferences);


        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(userId);
        verify(userPreferencesRepository).save(any(UserPreferences.class));
    }

    @Test
    void testGetFlagsXeroAuthenticationWhenTokenStored() {
        userPreferences.setXeroAccessToken(storedXeroAccessToken());

        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));

        UserPreferences result = userPreferencesService.get(userId);

        assertThat(result.isXeroAuthentication()).isTrue();
    }

    /**
     * {@code isExpired()} treats a token with no {@code created} as expired, so a token stored
     * without one would force a refresh on every single call. Saving stamps both timestamps rather
     * than trusting the caller to have set them.
     */
    @Test
    void testSaveXeroAccessTokenStampsCreatedAndUpdated() {
        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));
        when(userPreferencesRepository.saveAndFlush(any(UserPreferences.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Exactly what Xero's token endpoint returns: no timestamps of its own. Untouched, this
        // token reads as already expired, so the assertions below fail unless save() stamps it.
        XeroAccessToken freshlyExchangedToken = XeroAccessToken.builder()
                .userId(userId)
                .accessToken("xero-access-token")
                .refreshToken("xero-refresh-token")
                .tokenType("Bearer")
                .scope("accounting.transactions offline_access")
                .expiresIn(1800)
                .tenantId("11111111-2222-3333-4444-555555555555")
                .tenantName("Demo Company")
                .build();

        assertThat(freshlyExchangedToken.created()).isNull();
        assertThat(freshlyExchangedToken.isExpired()).isTrue();

        ZonedDateTime beforeSave = ZonedDateTime.now();

        userPreferencesService.save(userId, freshlyExchangedToken);

        ArgumentCaptor<UserPreferences> captor = ArgumentCaptor.forClass(UserPreferences.class);
        verify(userPreferencesRepository).saveAndFlush(captor.capture());

        XeroAccessToken saved = captor.getValue().getXeroAccessToken();

        assertThat(saved).isNotNull();
        assertThat(saved.created()).isNotNull().isAfterOrEqualTo(beforeSave);
        assertThat(saved.updated()).isNotNull().isAfterOrEqualTo(beforeSave);
        assertThat(saved.isExpired()).isFalse();
        assertThat(saved.accessToken()).isEqualTo("xero-access-token");
        assertThat(saved.refreshToken()).isEqualTo("xero-refresh-token");
        assertThat(saved.tenantId()).isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    /**
     * No preferences write from a client can ever carry the Xero token back, since it is never
     * serialized. Without this guard every ordinary preferences update would silently disconnect
     * the user's Xero organisation.
     */
    @Test
    void testPreservesStoredXeroTokenWhenPreferencesWriteOmitsIt() {
        XeroAccessToken storedToken = storedXeroAccessToken();
        userPreferences.setXeroAccessToken(storedToken);

        UserPreferences incomingPreferences = new UserPreferences();
        incomingPreferences.setUserId(userId);
        incomingPreferences.setChatSimilarityThreshold(defaultChatSimilarityThreshold);

        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));
        when(userPreferencesRepository.save(any(UserPreferences.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserPreferences result = userPreferencesService.update(userId, incomingPreferences);

        assertThat(result.getXeroAccessToken()).isEqualTo(storedToken);
        assertThat(result.isXeroAuthentication()).isTrue();
    }

    /**
     * Disconnecting has to write through the repository directly: routing through
     * {@code update(UUID, UserPreferences)} would hit the preserve guard above and restore the very
     * token being removed.
     */
    @Test
    void testClearXeroAccessTokenRemovesStoredToken() {
        userPreferences.setXeroAccessToken(storedXeroAccessToken());

        when(userPreferencesRepository.findByUserId(userId)).thenReturn(Optional.of(userPreferences));

        userPreferencesService.clearXeroAccessToken(userId);

        ArgumentCaptor<UserPreferences> captor = ArgumentCaptor.forClass(UserPreferences.class);
        verify(userPreferencesRepository).save(captor.capture());

        assertThat(captor.getValue().getXeroAccessToken()).isNull();
        assertThat(captor.getValue().isXeroAuthentication()).isFalse();
    }

    private XeroAccessToken storedXeroAccessToken() {
        return XeroAccessToken.builder()
                .userId(userId)
                .accessToken("xero-access-token")
                .refreshToken("xero-refresh-token")
                .tokenType("Bearer")
                .scope("accounting.transactions offline_access")
                .expiresIn(1800)
                .tenantId("11111111-2222-3333-4444-555555555555")
                .tenantName("Demo Company")
                .created(ZonedDateTime.now())
                .updated(ZonedDateTime.now())
                .build();
    }
}