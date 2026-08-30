package com.solesonic.tools.xero;

import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.service.xero.XeroInvoiceService;
import com.solesonic.tools.LocalTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static com.solesonic.security.JwtUserRequestFilter.SUB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * That the role gate on {@code /create_xero_invoice} is actually enforced, and not merely annotated.
 * <p>
 * {@link CreateXeroInvoiceToolsTest} constructs the tool with {@code new}, which is the right way to
 * test what the method does but says nothing about whether {@code @PreAuthorize} runs — and in
 * production nothing calls this method directly either. {@code LocalToolRegistry} hands the
 * Spring-managed bean to {@code ToolCallbacks.from(...)}, so the gate holds only if that bean is a
 * proxy that still exposes {@code createXeroInvoice}.
 * <p>
 * That is less obvious than it looks. This class implements {@link LocalTool}, and a bean with an
 * interface is JDK-proxied by default — a proxy of {@code LocalTool}, which declares no methods,
 * would not expose the tool method at all. What saves it is class-based proxying, which Spring Boot
 * forces on by default; {@link EnableAspectJAutoProxy} here reproduces that, so this test fails if
 * the assumption ever stops holding rather than leaving a role check that silently does nothing.
 */
class CreateXeroInvoiceToolsMethodSecurityTest {

    @Configuration
    @EnableMethodSecurity
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class MethodSecurityConfiguration {

        @Bean
        XeroInvoiceService xeroInvoiceService() {
            return mock(XeroInvoiceService.class);
        }

        @Bean
        CreateXeroInvoiceTools createXeroInvoiceTools(XeroInvoiceService xeroInvoiceService) {
            return new CreateXeroInvoiceTools(xeroInvoiceService);
        }
    }

    @AfterEach
    void afterEach() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticated(Collection<SimpleGrantedAuthority> authorities) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(SUB, UUID.randomUUID().toString())
                .build();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        SecurityContextHolder.setContext(securityContext);
    }

    private static XeroInvoiceRequest invoiceRequest() {
        return new XeroInvoiceRequest(List.of(), null, null, "PO-1234", "USD", "Exclusive");
    }

    /**
     * The bean {@code LocalToolRegistry} would receive still exposes the tool method. Without
     * class-based proxying this fails outright, which is the point — a tool nobody can invoke is a
     * better failure than a role check nobody enforces.
     */
    @Test
    void theProxiedBeanStillExposesTheToolMethod() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            assertThat(context.getBeansOfType(LocalTool.class).values())
                    .singleElement()
                    .isInstanceOf(CreateXeroInvoiceTools.class);
        }
    }

    @Test
    void refusesAUserWithoutTheXeroInvoiceCreateRole() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            CreateXeroInvoiceTools createXeroInvoiceTools = context.getBean(CreateXeroInvoiceTools.class);
            XeroInvoiceService xeroInvoiceService = context.getBean(XeroInvoiceService.class);
            XeroInvoiceRequest invoiceRequest = invoiceRequest();

            authenticated(List.of(new SimpleGrantedAuthority("ROLE_rag-admin")));

            assertThatThrownBy(() -> createXeroInvoiceTools.createXeroInvoice(invoiceRequest))
                    .isInstanceOf(AccessDeniedException.class);

            verify(xeroInvoiceService, never()).create(any(XeroInvoiceRequest.class), any(UUID.class));
        }
    }

    /**
     * Refused by the gate itself, before the method body runs — so the tool's own "no authenticated
     * user" check is a second line of defence, reached only when the bean is invoked unproxied.
     * Method security answers a missing authentication with
     * {@link AuthenticationCredentialsNotFoundException} rather than {@link AccessDeniedException},
     * which is worth pinning: the two are unrelated types, and a handler that caught only the latter
     * would let this one through.
     */
    @Test
    void refusesAnUnauthenticatedCaller() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            CreateXeroInvoiceTools createXeroInvoiceTools = context.getBean(CreateXeroInvoiceTools.class);
            XeroInvoiceService xeroInvoiceService = context.getBean(XeroInvoiceService.class);
            XeroInvoiceRequest invoiceRequest = invoiceRequest();

            assertThatThrownBy(() -> createXeroInvoiceTools.createXeroInvoice(invoiceRequest))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

            verify(xeroInvoiceService, never()).create(any(XeroInvoiceRequest.class), any(UUID.class));
        }
    }
}
