package com.solesonic.service.xero;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.exception.xero.XeroInvoiceValidationException;
import com.solesonic.model.xero.invoice.XeroContact;
import com.solesonic.model.xero.invoice.XeroInvoice;
import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.model.xero.invoice.XeroInvoicesEnvelope;
import com.solesonic.model.xero.invoice.XeroLineItem;
import com.solesonic.model.xero.invoice.XeroValidationError;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.solesonic.config.xero.XeroConstants.XERO_API_WEB_CLIENT;
import static com.solesonic.config.xero.XeroConstants.XERO_USER_ID;

/**
 * Creates draft sales invoices in the calling user's Xero organisation.
 * <p>
 * Nothing here obtains or renews a token: the call goes out on the API {@code WebClient}, whose
 * {@link com.solesonic.security.xero.XeroRequestAuthorizationFilter} reads the stored connection for
 * the user, refreshes it if it has expired, and adds both {@code Authorization} and
 * {@code xero-tenant-id}. Keeping that in one filter is what stops a second copy of the refresh
 * logic from appearing here — and each spare refresh burns a refresh token, since Xero rotates them.
 * <p>
 * Which user that is arrives through the Reactor subscription context rather than the request scope,
 * and this method is the only place it is put there. The filter runs inside the exchange, so it can
 * read nothing this method does not publish; and the two callers do not share a thread —
 * {@code XeroInvoiceController} has an HTTP request bound to its, {@code CreateXeroInvoiceTools} runs
 * on the scheduler the chat stream subscribes on and has none.
 * <p>
 * The two behaviours worth knowing before changing this class:
 * <ul>
 *     <li><strong>{@code summarizeErrors=false} means a {@code 200} can be a failure.</strong>
 *     Without the parameter, one bad line item fails the whole call with a bare {@code 400} carrying
 *     nothing a caller could act on. With it, Xero answers {@code 200} and reports the rejection
 *     per-invoice, so {@code HasErrors} has to be checked explicitly — that check is the only thing
 *     standing between a rejected invoice and a {@code 201 Created}.</li>
 *     <li><strong>{@code Type}, {@code Contact} and {@code Status} are set here, always.</strong>
 *     Never from {@link XeroInvoiceRequest}, which has no components for them. Every invoice is a
 *     draft {@code ACCREC} billed to the one configured contact, and a human authorises it in Xero's
 *     own UI — there is deliberately no path through this application that posts a live, sendable
 *     invoice.</li>
 * </ul>
 */
@Service
public class XeroInvoiceService {
    private static final Logger log = LoggerFactory.getLogger(XeroInvoiceService.class);

    private static final String INVOICES_PATH = "/api.xro/2.0/Invoices";

    /**
     * Turns a rejected invoice from a bare {@code 400} into a {@code 200} naming each reason. It is
     * what makes a rejection explainable to a REST caller or a chat user at all.
     */
    private static final String SUMMARIZE_ERRORS_PARAM = "summarizeErrors";

    /** A sales invoice. {@code ACCPAY} — a bill — is a different mental model and out of scope. */
    private static final String ACCREC = "ACCREC";

    /** Never {@code AUTHORISED}: a person reviews and authorises every invoice in Xero's own UI. */
    private static final String DRAFT = "DRAFT";

    private static final String NO_INVOICE_RETURNED = "xero_returned_no_invoice";

    private static final String NO_USER = "xero_invoice_without_a_user";

    /**
     * Xero flagged the invoice but named no reason. Rare, but the alternative is throwing a
     * validation exception whose message is the empty string.
     */
    private static final String UNEXPLAINED_REJECTION = "Xero rejected the invoice without giving a reason.";

    @Value("${xero.invoice.default-contact-id}")
    private String defaultContactId;

    private final WebClient apiWebClient;

    public XeroInvoiceService(@Qualifier(XERO_API_WEB_CLIENT) WebClient apiWebClient) {
        this.apiWebClient = apiWebClient;
    }

    public XeroInvoice create(XeroInvoiceRequest xeroInvoiceRequest, UUID userId) {
        if (userId == null) {
            log.error("Refusing to create a Xero invoice with no user");

            throw new XeroApiException(NO_USER);
        }

        log.info("Creating Xero invoice for user: {}", userId);

        XeroInvoicesEnvelope requestEnvelope =
                new XeroInvoicesEnvelope(List.of(outgoingInvoice(xeroInvoiceRequest)));

        XeroInvoicesEnvelope responseEnvelope = apiWebClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path(INVOICES_PATH)
                        .queryParam(SUMMARIZE_ERRORS_PARAM, false)
                        .build())
                .bodyValue(requestEnvelope)
                .retrieve()
                .bodyToMono(XeroInvoicesEnvelope.class)
                .contextWrite(context -> context.put(XERO_USER_ID, userId))
                .block();

        XeroInvoice created = single(responseEnvelope, userId);

        if (Boolean.TRUE.equals(created.hasErrors())) {
            List<String> messages = validationMessages(created);

            log.warn("Xero rejected the invoice for user {}: {}", userId, messages);

            throw new XeroInvoiceValidationException(messages);
        }

        log.info("Created Xero invoice {} for user: {}", created.invoiceNumber(), userId);

        return created;
    }

    /**
     * The outgoing body. Everything the caller may influence comes from the request; everything else
     * is fixed here.
     */
    private XeroInvoice outgoingInvoice(XeroInvoiceRequest xeroInvoiceRequest) {
        List<XeroLineItem> lineItems = xeroInvoiceRequest.lineItems() == null
                ? null
                : xeroInvoiceRequest.lineItems().stream()
                        .map(XeroLineItem::from)
                        .toList();

        return XeroInvoice.builder()
                .type(ACCREC)
                .contact(new XeroContact(defaultContactId, null))
                .status(DRAFT)
                .lineItems(lineItems)
                .date(isoDate(xeroInvoiceRequest.date()))
                .dueDate(isoDate(xeroInvoiceRequest.dueDate()))
                .reference(xeroInvoiceRequest.reference())
                .currencyCode(xeroInvoiceRequest.currencyCode())
                .lineAmountTypes(xeroInvoiceRequest.lineAmountTypes())
                .build();
    }

    /**
     * Xero takes {@code YYYY-MM-DD} on the way in, which is exactly {@link LocalDate#toString()}.
     * The Microsoft-style rendering Xero answers with is a read-side concern and never produced here.
     */
    private static String isoDate(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * The one invoice out of the bulk envelope Xero insists on even for a single document.
     * <p>
     * An envelope with nothing in it is refused rather than allowed to become a
     * {@link NullPointerException}: {@code GeneralExceptionHandler} renders any unrecognised
     * {@code RuntimeException} as {@code 200 OK} carrying a chat message, which a REST caller of a
     * creation endpoint reads as a successful creation.
     */
    private static XeroInvoice single(XeroInvoicesEnvelope responseEnvelope, UUID userId) {
        if (responseEnvelope == null
                || responseEnvelope.invoices() == null
                || responseEnvelope.invoices().isEmpty()) {
            log.error("Xero answered the invoice creation for user {} with no invoice", userId);

            throw new XeroApiException(NO_INVOICE_RETURNED);
        }

        if (responseEnvelope.invoices().size() > 1) {
            log.warn("Xero returned {} invoices for a single-invoice request; using the first one",
                    responseEnvelope.invoices().size());
        }

        return responseEnvelope.invoices().getFirst();
    }

    private static List<String> validationMessages(XeroInvoice created) {
        if (created.validationErrors() == null) {
            return List.of(UNEXPLAINED_REJECTION);
        }

        List<String> messages = created.validationErrors().stream()
                .map(XeroValidationError::message)
                .filter(StringUtils::isNotBlank)
                .toList();

        return messages.isEmpty() ? List.of(UNEXPLAINED_REJECTION) : messages;
    }
}
