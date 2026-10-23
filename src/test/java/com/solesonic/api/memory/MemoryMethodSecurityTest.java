package com.solesonic.api.memory;

import com.solesonic.scope.UserRequestContext;
import com.solesonic.service.memory.MemoryAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.solesonic.security.JwtUserRequestFilter.SUB;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * That the {@code izzy-bot-admin} gate on {@code /memories} and {@code /memories} is
 * enforced, not merely annotated: the controllers are stood up as Spring proxies under
 * {@code @EnableMethodSecurity}, the way they run, and every endpoint is called.
 * <p>
 * The gate is the <em>group</em>. A role that happens to carry the same name must not pass, which is
 * the reason groups are mapped under their own {@code GROUP_} prefix.
 */
class MemoryMethodSecurityTest {
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 50);

    private static MemoryAdminService memoryAdminService;
    private static UserRequestContext userRequestContext;

    @Configuration
    @EnableMethodSecurity
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class MethodSecurityConfiguration {

        @Bean
        MemoryController memoryController() {
            return new MemoryController(memoryAdminService, userRequestContext);
        }

        @Bean
        MemoryReflectionController memoryReflectionController() {
            return new MemoryReflectionController(memoryAdminService, userRequestContext);
        }
    }

    private static final List<ThrowingConsumer<AnnotationConfigApplicationContext>> ENDPOINTS = List.of(
            context -> {
                assert context != null;
                context.getBean(MemoryController.class).list(null, null, null, null, FIRST_PAGE);
            },
            context -> {
                assert context != null;
                context.getBean(MemoryController.class).status();
            },
            context -> {
                assert context != null;
                context.getBean(MemoryReflectionController.class).list(null, null, null, FIRST_PAGE);
            });

    @BeforeEach
    void beforeEach() {
        memoryAdminService = mock(MemoryAdminService.class);
        userRequestContext = mock(UserRequestContext.class);
    }

    @AfterEach
    void afterEach() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticated(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(SUB, UUID.randomUUID().toString())
                .build();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new JwtAuthenticationToken(jwt,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(securityContext);
    }

    private static void assertEveryEndpointRefused() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            for (ThrowingConsumer<AnnotationConfigApplicationContext> endpoint : ENDPOINTS) {
                assertThatThrownBy(() -> endpoint.accept(context)).isInstanceOf(AccessDeniedException.class);
            }

            verifyNoInteractions(memoryAdminService);
        }
    }

    @Test
    void refusesACallerOutsideTheGroup() {
        authenticated("ROLE_rag-admin", "ROLE_image-admin", "GROUP_model-admin");

        assertEveryEndpointRefused();
    }

    @Test
    void refusesARoleThatOnlySharesTheGroupsName() {
        authenticated("ROLE_izzy-bot-admin");

        assertEveryEndpointRefused();
    }

    @Test
    void letsAMemberOfTheGroupThrough() {
        when(memoryAdminService.listMemories(any(), any(), any(), any(), any())).thenReturn(Page.empty(FIRST_PAGE));
        when(memoryAdminService.reflections(any(), any(), any(), any())).thenReturn(Page.empty(FIRST_PAGE));

        authenticated("GROUP_izzy-bot-admin");

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            for (ThrowingConsumer<AnnotationConfigApplicationContext> endpoint : ENDPOINTS) {
                assertThatCode(() -> endpoint.accept(context)).doesNotThrowAnyException();
            }
        }
    }
}
