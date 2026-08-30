package com.solesonic.tools.xero;

import com.solesonic.api.xero.XeroInvoiceController;
import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.exception.xero.XeroInvoiceValidationException;
import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.xero.invoice.XeroInvoice;
import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.model.xero.invoice.XeroLineItemRequest;
import com.solesonic.service.xero.XeroInvoiceService;
import com.solesonic.tools.LocalTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.solesonic.exception.xero.XeroErrorMessages.RECONNECT_MESSAGE;
import static com.solesonic.exception.xero.XeroErrorMessages.UPSTREAM_MESSAGE;
import static com.solesonic.security.JwtUserRequestFilter.SUB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The chat-callable half of invoice creation.
 * <p>
 * Two of these tests exist to stop the two exposure paths drifting apart rather than to describe
 * behaviour. {@link #gatesOnExactlyTheSameRoleAsTheRestEndpoint()} and
 * {@link #takesExactlyTheSameRequestTypeAsTheRestEndpoint()} compare this tool against
 * {@link XeroInvoiceController} by reflection, because "the tool and the endpoint are authorised the
 * same way and accept the same thing" is a property no single class can assert about itself — and
 * the failure it guards against is silent: a role renamed on one side leaves the other side an open
 * door into someone's accounting system.
 * <p>
 * The third is {@link #billsTheInvoiceToTheAuthenticatedUser()}. The user comes from the restored
 * security context, which is the same authentication {@code @PreAuthorize} just checked, so the
 * identity that passed the role gate and the identity the invoice is created for cannot disagree.
 */
class CreateXeroInvoiceToolsTest {

    private static final String EXPECTED_ROLE_EXPRESSION = "hasAuthority('ROLE_xero-invoice-create')";

    /** A Xero API error body, as the response filter on the API client would wrap one. */
    private static final String RAW_XERO_ERROR_BODY = """
            {"Type":null,"Title":"Unauthorized","Status":401,\
            "Detail":"AuthenticationUnsuccessful: TokenExpired for tenant 8f2b1c40-1111-2222-3333-abcdefabcdef",\
            "Instance":"c9f0e6a2-4d55-4a1e-9c77-5b6a7c8d9e01"}""";

    private UUID userId;
    private XeroInvoiceService xeroInvoiceService;
    private CreateXeroInvoiceTools createXeroInvoiceTools;

    @BeforeEach
    void beforeEach() {
        userId = UUID.randomUUID();
        xeroInvoiceService = mock(XeroInvoiceService.class);
        createXeroInvoiceTools = new CreateXeroInvoiceTools(xeroInvoiceService);
    }

    @AfterEach
    void afterEach() {
        SecurityContextHolder.clearContext();
    }

    /**
     * What {@code IdentityToolCallback} leaves on the thread before it delegates: the caller's own
     * JWT, decoded and converted, rather than the service account the application otherwise runs as.
     */
    private void authenticated() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim(SUB, userId.toString())
                .build();

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(securityContext);
    }

    private static XeroInvoiceRequest invoiceRequest() {
        XeroLineItemRequest lineItem = new XeroLineItemRequest(
                "Consulting",
                new BigDecimal("2"),
                new BigDecimal("150.00"),
                null,
                "200",
                null,
                "OUTPUT");

        return new XeroInvoiceRequest(
                List.of(lineItem),
                LocalDate.of(2026, 8, 30),
                LocalDate.of(2026, 9, 29),
                "PO-1234",
                "USD",
                "Exclusive");
    }

    /**
     * A created invoice as Xero answers one. Built through the canonical constructor rather than
     * {@code XeroInvoice.builder()}, which is the write-side builder and leaves every read-side
     * component — the id and number this tool reports back — null by design.
     */
    private static XeroInvoice created() {
        return new XeroInvoice(
                "ACCREC",
                null,
                List.of(),
                "2026-08-30",
                "2026-09-29",
                "PO-1234",
                "USD",
                "Exclusive",
                "DRAFT",
                "e0a9f3d1-6b5a-4a1d-9f7e-2a3c4d5e6f70",
                "INV-0042",
                "/Date(1788134400000+0000)/",
                "/Date(1790726400000+0000)/",
                new BigDecimal("300.00"),
                BigDecimal.ZERO,
                new BigDecimal("300.00"),
                null,
                null);
    }

    private static Method toolMethod() {
        return Arrays.stream(CreateXeroInvoiceTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .reduce((_, _) -> {
                    throw new IllegalStateException("Expected exactly one @Tool method");
                })
                .orElseThrow(() -> new IllegalStateException("No @Tool method found"));
    }

    @Test
    void isALocalToolNamedForItsSlashCommand() {
        assertThat(LocalTool.class).isAssignableFrom(CreateXeroInvoiceTools.class);
        assertThat(toolMethod().getAnnotation(Tool.class).name()).isEqualTo("create_xero_invoice");
    }

    /**
     * AC #3, asserted against the endpoint itself rather than against a copy of the string. Renaming
     * the role on one path and not the other is the failure this catches, and nothing else would:
     * both classes keep compiling and both keep passing their own tests.
     */
    @Test
    void gatesOnExactlyTheSameRoleAsTheRestEndpoint() throws NoSuchMethodException {
        PreAuthorize toolGate = toolMethod().getAnnotation(PreAuthorize.class);
        PreAuthorize endpointGate = XeroInvoiceController.class
                .getDeclaredMethod("create", XeroInvoiceRequest.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(toolGate).isNotNull();
        assertThat(endpointGate).isNotNull();
        assertThat(toolGate.value()).isEqualTo(endpointGate.value());
        assertThat(toolGate.value()).isEqualTo(EXPECTED_ROLE_EXPRESSION);
    }

    /**
     * AC #1. The tool takes {@link XeroInvoiceRequest} itself rather than a record shaped like it,
     * so "mirrors the REST request exactly" is a fact about the type system instead of a promise a
     * reviewer has to re-check. In particular, the absence of {@code contact}, {@code status} and
     * {@code type} is inherited rather than re-stated — a chat user cannot ask for a live, sendable
     * invoice any more than a REST caller can.
     */
    @Test
    void takesExactlyTheSameRequestTypeAsTheRestEndpoint() throws NoSuchMethodException {
        Class<?>[] endpointParameters = XeroInvoiceController.class
                .getDeclaredMethod("create", XeroInvoiceRequest.class)
                .getParameterTypes();

        assertThat(toolMethod().getParameterTypes()).containsExactly(endpointParameters);
    }

    @Test
    void billsTheInvoiceToTheAuthenticatedUser() {
        authenticated();
        when(xeroInvoiceService.create(any(XeroInvoiceRequest.class), any(UUID.class))).thenReturn(created());

        XeroInvoiceRequest invoiceRequest = invoiceRequest();

        createXeroInvoiceTools.createXeroInvoice(invoiceRequest);

        verify(xeroInvoiceService).create(invoiceRequest, userId);
    }

    /**
     * The tool result re-enters the model's context, so it carries a summary and not the invoice.
     * Echoing every line item back would spend context on text the user just supplied.
     */
    @Test
    void answersWithASummaryRatherThanTheWholeInvoice() {
        authenticated();
        when(xeroInvoiceService.create(any(XeroInvoiceRequest.class), any(UUID.class))).thenReturn(created());

        CreateXeroInvoiceTools.CreateXeroInvoiceResponse response =
                createXeroInvoiceTools.createXeroInvoice(invoiceRequest());

        assertThat(response.invoiceId()).isEqualTo("e0a9f3d1-6b5a-4a1d-9f7e-2a3c4d5e6f70");
        assertThat(response.invoiceNumber()).isEqualTo("INV-0042");
        assertThat(response.status()).isEqualTo("DRAFT");
        assertThat(response.currencyCode()).isEqualTo("USD");
        assertThat(response.total()).isEqualByComparingTo("300.00");

        assertThat(Arrays.stream(CreateXeroInvoiceTools.CreateXeroInvoiceResponse.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("lineItems", "contact");
    }

    /**
     * What a chat user is actually told when this tool throws.
     * <p>
     * Not the error path it looks like. Spring AI's {@code DefaultToolExecutionExceptionProcessor}
     * does not rethrow a {@code RuntimeException} raised by a tool — it returns the exception's
     * message as the tool <em>result</em>, which the model then narrates and which is persisted in
     * chat history. So the message on anything thrown here is user-visible output, and this is the
     * boundary that decides what a person reads.
     */
    private static String asChatUserWouldSee(RuntimeException thrown) {
        ToolDefinition toolDefinition = ToolCallbacks.from(new CreateXeroInvoiceTools(mock(XeroInvoiceService.class)))[0]
                .getToolDefinition();

        return DefaultToolExecutionExceptionProcessor.builder()
                .build()
                .process(new ToolExecutionException(toolDefinition, thrown));
    }

    private RuntimeException thrownByTheTool() {
        XeroInvoiceRequest invoiceRequest = invoiceRequest();

        try {
            createXeroInvoiceTools.createXeroInvoice(invoiceRequest);
        } catch (RuntimeException thrown) {
            return thrown;
        }

        throw new AssertionError("Expected the tool to throw");
    }

    /**
     * The leak this guards against is silent and permanent. {@code XeroApiException} carries Xero's
     * raw error body — its own javadoc says that body "is never returned to a caller", and over REST
     * {@code XeroExceptionHandler} keeps that promise by discarding it. A tool has no
     * {@code @ControllerAdvice} in front of it, so an uncensored exception here would be narrated to
     * the user and written into {@code chat_message} verbatim, upstream tenant and token detail
     * included.
     */
    @Test
    void neverLetsARawXeroErrorBodyReachTheChat() {
        authenticated();

        when(xeroInvoiceService.create(any(XeroInvoiceRequest.class), any(UUID.class)))
                .thenThrow(new XeroApiException(RAW_XERO_ERROR_BODY, null));

        RuntimeException thrown = thrownByTheTool();

        assertThat(thrown).isInstanceOf(XeroApiException.class);
        assertThat(asChatUserWouldSee(thrown))
                .isEqualTo(UPSTREAM_MESSAGE)
                .doesNotContain("AuthenticationUnsuccessful")
                .doesNotContain("TokenExpired")
                .doesNotContain("8f2b1c40-1111-2222-3333-abcdefabcdef");
    }

    /**
     * A dead grant is told to the chat user the same way it is told to a REST caller: reconnect. The
     * {@code invalid_grant} wording behind it is developer-facing and stays in the log.
     */
    @Test
    void tellsAChatUserToReconnectRatherThanRepeatingOauthDetail() {
        authenticated();

        when(xeroInvoiceService.create(any(XeroInvoiceRequest.class), any(UUID.class)))
                .thenThrow(new XeroTokenException(
                        "invalid_grant: refresh token has expired or been revoked",
                        HttpStatus.BAD_REQUEST,
                        false));

        RuntimeException thrown = thrownByTheTool();

        assertThat(asChatUserWouldSee(thrown))
                .isEqualTo(RECONNECT_MESSAGE)
                .doesNotContain("invalid_grant");
    }

    /**
     * AC #4's second half, asserted where it actually happens. Xero's rejection wording is written
     * for the person who submitted the document and is the one Xero failure safe to repeat verbatim —
     * so unlike every other failure it must survive the tool-failure boundary uncensored, or a chat
     * user is left knowing only that something went wrong with an invoice that does not exist.
     */
    @Test
    void letsXerosValidationMessagesReachTheChatUser() {
        authenticated();

        List<String> messages = List.of("Account code 'ZZZ' is not a valid code for this document.");

        when(xeroInvoiceService.create(any(XeroInvoiceRequest.class), any(UUID.class)))
                .thenThrow(new XeroInvoiceValidationException(messages));

        XeroInvoiceRequest invoiceRequest = invoiceRequest();

        assertThatThrownBy(() -> createXeroInvoiceTools.createXeroInvoice(invoiceRequest))
                .isInstanceOf(XeroInvoiceValidationException.class)
                .satisfies(thrown -> assertThat(((XeroInvoiceValidationException) thrown).getMessages())
                        .isEqualTo(messages));

        assertThat(asChatUserWouldSee(thrownByTheTool()))
                .contains("Account code 'ZZZ' is not a valid code for this document.");
    }

    /**
     * An unauthenticated call never reaches Xero. In production {@code @PreAuthorize} refuses it
     * first, but this tool is a plain bean as well as a proxied one, and an invoice created for
     * nobody would be billed to the configured contact with no user to attribute it to.
     */
    @Test
    void refusesToCreateAnInvoiceWithNoAuthenticatedUser() {
        XeroInvoiceRequest invoiceRequest = invoiceRequest();

        assertThatThrownBy(() -> createXeroInvoiceTools.createXeroInvoice(invoiceRequest))
                .isInstanceOf(XeroApiException.class);

        verify(xeroInvoiceService, never()).create(any(XeroInvoiceRequest.class), any(UUID.class));
    }
}
