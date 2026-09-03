package com.solesonic.api.document;

import com.solesonic.model.document.UriIngestRequest;
import com.solesonic.model.ingestion.IngestedDocumentUpdateRequest;
import com.solesonic.service.ingestion.IngestedDocumentService;
import com.solesonic.service.ingestion.StatusHistoryService;
import com.solesonic.service.ingestion.UriIngestionService;
import com.solesonic.service.rag.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * That the {@code rag-admin} gate on the shared collection is enforced, and not merely annotated.
 * <p>
 * {@link IngestedGlobalDocumentControllerTest} builds the controller with {@code new} through a
 * standalone {@code MockMvc}, which is the right way to test routing and response shapes but applies
 * no method security at all — every request there succeeds regardless of role. What actually holds
 * the gate is that the Spring-managed bean is a proxy, so this stands the controller up in a context
 * with {@code @EnableMethodSecurity} and calls it as a caller without the role would.
 * <p>
 * The reads are covered too, and covered as <em>allowed</em>: {@code GLOBAL} documents are already
 * retrievable by every user through RAG, so a gate on the listing would hide only the record of what
 * the assistant is answering from. That is a decision, and a decision worth failing a test if it is
 * quietly reversed by annotating the class instead of its methods.
 */
class IngestedDocumentMethodSecurityTest {

    /**
     * The mocks are static fields rather than {@code @Bean} methods, and only the controllers are
     * beans.
     * <p>
     * A mock registered as a bean is still field-injected like any other:
     * {@code VectorStoreService} carries {@code @Value("${solesonic.llm.retrieval...}")} fields, and
     * this context has no property source to resolve them against, so the placeholder arrives as a
     * literal string and the context fails to refresh. The controllers themselves have no injected
     * fields, so constructing them by hand keeps the mocks out of the container entirely.
     */
    private static IngestedDocumentService ingestedDocumentService;
    private static UriIngestionService uriIngestionService;
    private static VectorStoreService vectorStoreService;
    private static StatusHistoryService statusHistoryService;

    @Configuration
    @EnableMethodSecurity
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class MethodSecurityConfiguration {

        @Bean
        IngestedGlobalDocumentController ingestedGlobalDocumentController() {
            return new IngestedGlobalDocumentController(ingestedDocumentService, uriIngestionService);
        }

        @Bean
        DocumentController documentController() {
            return new DocumentController(vectorStoreService, statusHistoryService);
        }
    }

    @BeforeEach
    void beforeEach() {
        ingestedDocumentService = mock(IngestedDocumentService.class);
        uriIngestionService = mock(UriIngestionService.class);
        vectorStoreService = mock(VectorStoreService.class);
        statusHistoryService = mock(StatusHistoryService.class);
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

    private static void authenticatedWithoutRagAdmin() {
        authenticated(List.of(new SimpleGrantedAuthority("ROLE_token-mint-jira")));
    }

    @Test
    void everyMutationOfTheSharedCollectionRefusesACallerWithoutRagAdmin() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            IngestedGlobalDocumentController controller =
                    context.getBean(IngestedGlobalDocumentController.class);
            UUID documentId = UUID.randomUUID();

            authenticatedWithoutRagAdmin();

            assertThatThrownBy(() -> controller.upload(null))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.ingestUri(new UriIngestRequest("https://example.com")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.rename(documentId, new IngestedDocumentUpdateRequest("x.pdf")))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.delete(documentId))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.refresh(documentId))
                    .isInstanceOf(AccessDeniedException.class);

            verifyNoInteractions(uriIngestionService);
            verify(ingestedDocumentService, never()).deleteGlobal(any());
            verify(ingestedDocumentService, never()).refreshGlobal(any());
        }
    }

    /**
     * The shared corpus is readable by everyone, by design. If this ever starts throwing, the gate
     * has been moved from the mutating methods onto the class.
     */
    @Test
    void readsOfTheSharedCollectionAreOpenToAnyAuthenticatedCaller() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            IngestedGlobalDocumentController controller =
                    context.getBean(IngestedGlobalDocumentController.class);

            when(ingestedDocumentService.listGlobal(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            authenticatedWithoutRagAdmin();

            assertThatCode(() -> controller.list(PageRequest.of(0, 20))).doesNotThrowAnyException();
            assertThatCode(() -> controller.get(UUID.randomUUID())).doesNotThrowAnyException();
        }
    }

    @Test
    void drainingTheIngestionQueueRequiresRagAdmin() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            DocumentController documentController = context.getBean(DocumentController.class);

            authenticatedWithoutRagAdmin();

            assertThatThrownBy(documentController::processQueue)
                    .isInstanceOf(AccessDeniedException.class);

            verify(statusHistoryService, never()).processQueued();
        }
    }

    @Test
    void aRagAdminIsAllowedThrough() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(MethodSecurityConfiguration.class)) {

            DocumentController documentController = context.getBean(DocumentController.class);

            authenticated(List.of(new SimpleGrantedAuthority("ROLE_rag-admin")));

            assertThatCode(documentController::processQueue).doesNotThrowAnyException();

            verify(statusHistoryService).processQueued();
        }
    }
}
