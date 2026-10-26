package com.solesonic.api.llm;

import com.solesonic.exception.handler.LlmKeyExceptionHandler;
import com.solesonic.exception.llm.LlmKeyRejectedException;
import com.solesonic.model.llm.LlmKeyErrorCode;
import com.solesonic.model.llm.UserLlmKeyRequest;
import com.solesonic.model.llm.UserLlmKeySummary;
import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.llm.UserLlmKeyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.solesonic.security.JwtUserRequestFilter.SUB;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The routes and status codes through a standalone {@code MockMvc}, and the {@code llm-key-admin}
 * gate through a method-security proxy, since a standalone setup applies no method security at all.
 */
class UserLlmKeyControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String USER_KEY = "sk-user-key-00001234";

    private static UserLlmKeyService userLlmKeyService;
    private static UserRequestContext userRequestContext;

    private MockMvc mockMvc;
    private UUID userId;

    @Configuration
    @EnableMethodSecurity
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class MethodSecurityConfiguration {

        @Bean
        UserLlmKeyController userLlmKeyController() {
            return new UserLlmKeyController(userLlmKeyService, userRequestContext);
        }
    }

    @BeforeEach
    void setUp() {
        userLlmKeyService = mock(UserLlmKeyService.class);
        userRequestContext = new UserRequestContext();
        userRequestContext.setUserId(ADMIN_ID);
        userId = UUID.randomUUID();

        mockMvc = MockMvcBuilders.standaloneSetup(new UserLlmKeyController(userLlmKeyService, userRequestContext))
                .setControllerAdvice(new LlmKeyExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserLlmKeySummary summary() {
        ZonedDateTime now = ZonedDateTime.now();

        return new UserLlmKeySummary(userId, "sam", "1234", now, now, ADMIN_ID);
    }

    /**
     * The key goes in and never comes out: the response describes the key without carrying it.
     */
    @Test
    void registersAKeyAndNeverEchoesIt() throws Exception {
        when(userLlmKeyService.register(userId, USER_KEY, ADMIN_ID)).thenReturn(summary());

        mockMvc.perform(put("/llm-keys/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + USER_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyHint").value("1234"))
                .andExpect(jsonPath("$.username").value("sam"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(content().string(not(containsString(USER_KEY))));
    }

    @Test
    void answersBadRequestForAKeyTheModelServerRejects() throws Exception {
        when(userLlmKeyService.register(userId, USER_KEY, ADMIN_ID))
                .thenThrow(new LlmKeyRejectedException(LlmKeyErrorCode.INVALID_LLM_KEY, "The model server did not accept this key."));

        mockMvc.perform(put("/llm-keys/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + USER_KEY + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LLM_KEY"));
    }

    @Test
    void answersServiceUnavailableWhenTheKeyCannotBeChecked() throws Exception {
        when(userLlmKeyService.register(userId, USER_KEY, ADMIN_ID))
                .thenThrow(new LlmKeyRejectedException(LlmKeyErrorCode.LLM_KEY_VALIDATION_UNAVAILABLE, "Try again."));

        mockMvc.perform(put("/llm-keys/{userId}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + USER_KEY + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LLM_KEY_VALIDATION_UNAVAILABLE"));
    }

    @Test
    void readsOneUsersKeyHint() throws Exception {
        when(userLlmKeyService.summary(userId)).thenReturn(Optional.of(summary()));

        mockMvc.perform(get("/llm-keys/{userId}", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.keyHint").value("1234"));
    }

    @Test
    void answersNotFoundForAUserWithNoKey() throws Exception {
        when(userLlmKeyService.summary(userId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/llm-keys/{userId}", userId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsKeys() throws Exception {
        when(userLlmKeyService.list(any())).thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/llm-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].keyHint").value("1234"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void removesAKey() throws Exception {
        when(userLlmKeyService.delete(userId, ADMIN_ID)).thenReturn(true);

        mockMvc.perform(delete("/llm-keys/{userId}", userId))
                .andExpect(status().isNoContent());
    }

    @Test
    void answersNotFoundWhenThereIsNoKeyToRemove() throws Exception {
        when(userLlmKeyService.delete(userId, ADMIN_ID)).thenReturn(false);

        mockMvc.perform(delete("/llm-keys/{userId}", userId))
                .andExpect(status().isNotFound());
    }

    private static void authenticated(String authority) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(SUB, ADMIN_ID.toString())
                .build();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority))));
        SecurityContextHolder.setContext(securityContext);
    }

    /**
     * Every endpoint, reads included: a key hint, and who holds model access at all, are an
     * administrator's business only.
     */
    @Test
    void everyEndpointRefusesACallerWithoutTheAdminRole() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            UserLlmKeyController controller = context.getBean(UserLlmKeyController.class);

            authenticated("ROLE_rag-admin");

            assertThatThrownBy(() -> controller.list(PageRequest.of(0, 50))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.get(userId)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.register(userId, new UserLlmKeyRequest(USER_KEY)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.delete(userId)).isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(userLlmKeyService);
        }
    }

    @Test
    void anAdministratorIsAllowedThrough() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            UserLlmKeyController controller = context.getBean(UserLlmKeyController.class);

            when(userLlmKeyService.summary(userId)).thenReturn(Optional.empty());

            authenticated("ROLE_llm-key-admin");

            assertThatCode(() -> controller.get(userId)).doesNotThrowAnyException();
            verify(userLlmKeyService).summary(userId);
        }
    }
}
