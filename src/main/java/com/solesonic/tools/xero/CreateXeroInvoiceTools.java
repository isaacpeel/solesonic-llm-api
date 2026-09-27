package com.solesonic.tools.xero;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.exception.xero.XeroErrorMessages;
import com.solesonic.exception.xero.XeroTokenException;
import com.solesonic.model.xero.invoice.XeroInvoice;
import com.solesonic.exception.xero.XeroInvoiceValidationException;
import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.service.xero.XeroInvoiceService;
import com.solesonic.tools.LocalTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

import static com.solesonic.exception.xero.XeroErrorMessages.UPSTREAM_MESSAGE;
import static com.solesonic.security.JwtUserRequestFilter.SUB;

/**
 * Creating a draft Xero invoice from a conversation, as {@code /create_xero_invoice}.
 * <p>
 * The same capability as {@code XeroInvoiceController}, reached a second way. Both call the one
 * {@link XeroInvoiceService}, so every policy that lives there — draft only, sales invoice only,
 * billed to the one configured contact — holds identically whichever path a caller takes; there is
 * deliberately no second copy of any of it here.
 * <p>
 * Three things about this class are load-bearing:
 * <ul>
 *     <li><strong>The parameter is {@link XeroInvoiceRequest} itself</strong>, not a record shaped
 *     like it. A copy would be one rename away from letting a chat user supply something a REST
 *     caller cannot, and the components that record leaves out — {@code type}, {@code contact},
 *     {@code status} — are exactly the ones that would turn a draft into a live, sendable invoice.
 *     Reusing the type makes that guarantee structural rather than a comment.</li>
 *     <li><strong>The role is the same string as the endpoint's.</strong> Two doors into a user's
 *     accounting system authorised differently is a door nobody meant to leave open, and
 *     {@code CreateXeroInvoiceToolsTest} compares the two annotations rather than trusting this
 *     note.</li>
 *     <li><strong>The user comes from the security context, not the tool context.</strong>
 *     {@code IdentityToolCallback} strips {@code userId} from the context map before it delegates,
 *     and restores the caller's own JWT onto the thread first — so the security context is both the
 *     only identity available here and the better one, being the very authentication
 *     {@code @PreAuthorize} has just accepted. Nothing can bill an invoice to a user who did not
 *     pass the role check.</li>
 *     <li><strong>An exception thrown here is not an error, it is output.</strong> Spring AI's
 *     {@code DefaultToolExecutionExceptionProcessor} does not rethrow a {@code RuntimeException} a
 *     tool raises; it returns the exception's message as the tool's <em>result</em>, which the model
 *     narrates and which is persisted in chat history. {@code XeroApiException} carries Xero's raw
 *     error body, so letting one escape would publish upstream detail into a conversation that
 *     {@code XeroExceptionHandler} is careful never to return over REST. Every failure leaving this
 *     class is censored to {@link XeroErrorMessages} wording, with the real detail kept to the
 *     log.</li>
 * </ul>
 */
@Component
public class CreateXeroInvoiceTools implements LocalTool {
    private static final Logger log = LoggerFactory.getLogger(CreateXeroInvoiceTools.class);

    public static final String CREATE_XERO_INVOICE = "create_xero_invoice";

    private static final String NO_AUTHENTICATED_USER = "xero_invoice_without_an_authenticated_user";

    private final XeroInvoiceService xeroInvoiceService;

    public CreateXeroInvoiceTools(XeroInvoiceService xeroInvoiceService) {
        this.xeroInvoiceService = xeroInvoiceService;
    }

    /**
     * What the model is told about the invoice it just created.
     * <p>
     * A summary rather than the {@link XeroInvoice}: a tool result is fed straight back into the
     * conversation, and echoing every line item would spend context re-reading the text the user
     * supplied a moment ago. The number and the status are what a person actually asks for next.
     */
    public record CreateXeroInvoiceResponse(String invoiceId,
                                            String invoiceNumber,
                                            String status,
                                            String currencyCode,
                                            BigDecimal total) {
    }

    @SuppressWarnings("unused")
    @Tool(name = CREATE_XERO_INVOICE, description = """
            Creates a DRAFT sales invoice in the user's connected Xero organisation and returns its \
            number and status. The invoice is always a draft billed to the organisation's configured \
            contact — it is never sent, and a person authorises it in Xero afterwards. Supply at \
            least one line item with a description and either a quantity and unit amount together or \
            a line amount; dates are ISO YYYY-MM-DD. Use responsibly: each call creates a separate \
            invoice, so never repeat a call for the same request.""")
    @PreAuthorize("hasAuthority('ROLE_xero-invoice-create')")
    public CreateXeroInvoiceResponse createXeroInvoice(XeroInvoiceRequest request) {
        UUID userId = authenticatedUserId();

        log.debug("Invoking create xero invoice tool for user: {}", userId);

        XeroInvoice created = create(request, userId);

        log.debug("Created Xero invoice: {}", created.invoiceNumber());

        return new CreateXeroInvoiceResponse(
                created.invoiceId(),
                created.invoiceNumber(),
                created.status(),
                created.currencyCode(),
                created.total());
    }

    /**
     * The invoice, with every upstream failure reduced to wording that is safe to say out loud.
     * <p>
     * {@link XeroInvoiceValidationException} is deliberately allowed through untouched: it is the one
     * Xero failure whose text is written for the person holding the invoice, and it is the only thing
     * that tells them which line item to fix. Everything else becomes a
     * {@link XeroErrorMessages} phrase, because the alternative is a raw OAuth or API error body
     * narrated into a conversation and stored there permanently.
     */
    private XeroInvoice create(XeroInvoiceRequest request, UUID userId) {
        try {
            return xeroInvoiceService.create(request, userId);
        } catch (XeroTokenException xeroTokenException) {
            log.warn("Xero token failure creating an invoice for user {}: {}",
                    userId, xeroTokenException.getMessage());

            throw new XeroTokenException(XeroErrorMessages.forTokenException(xeroTokenException),
                    xeroTokenException.getErrorCode(),
                    xeroTokenException.isRetriable());
        } catch (XeroApiException xeroApiException) {
            log.error("Xero API failure creating an invoice for user {}: {}",
                    userId, xeroApiException.getMessage());

            throw new XeroApiException(UPSTREAM_MESSAGE);
        }
    }

    /**
     * The caller, read the same way {@code JwtUserRequestFilter} reads it for an HTTP request — the
     * {@code sub} claim of the JWT on the thread.
     * <p>
     * Refused rather than defaulted when it is missing. An invoice created for nobody would still
     * land in a real organisation billed to the configured contact, with nothing recording who asked
     * for it.
     */
    private static UUID authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            log.error("No authenticated user on the thread creating a Xero invoice");

            throw new XeroApiException(NO_AUTHENTICATED_USER);
        }

        String subject = jwt.getClaimAsString(SUB);

        if (subject == null) {
            log.error("The authentication creating a Xero invoice carries no subject claim");

            throw new XeroApiException(NO_AUTHENTICATED_USER);
        }

        return UUID.fromString(subject);
    }
}
