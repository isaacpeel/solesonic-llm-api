package com.solesonic.tools.xero;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.model.xero.invoice.XeroLineItemRequest;
import com.solesonic.model.xero.workorder.WorkOrder;
import com.solesonic.tools.LocalTool;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Turning a work order into a draft invoice, as {@code /extract_xero_invoice_draft}.
 * <p>
 * The step in front of {@link CreateXeroInvoiceTools}. A property manager emails a work order, the
 * model reads it — the body arrives through the MCP server's {@code get_gmail_message_body} — and
 * hands the fields here as a {@link WorkOrder}. This builds the {@link XeroInvoiceRequest} from them.
 * <p>
 * Nothing here reads, parses or normalises message text, and that is deliberate. The document is
 * already in the model's context by the time this is called; a second reading of the same words in
 * this process could only disagree with the first, and the disagreement would be invisible.
 * <p>
 * Four things about this class are load-bearing:
 * <ul>
 *     <li><strong>It never prices anything.</strong> No work order of this kind carries a price — the
 *     property manager describes the job, the contractor decides what it costs — so any amount
 *     appearing here would have been invented, and it would land in a real accounting system looking
 *     exactly like a figure the property manager sent. {@link WorkOrder} has no price component at
 *     all, so there is nowhere for one to come from, and {@link #missingFields()} names what the
 *     person still has to supply so the model asks rather than guesses.</li>
 *     <li><strong>The billing entity is echoed, not applied.</strong> Every invoice this application
 *     creates goes to the one contact named by {@code xero.invoice.default-contact-id}
 *     ({@code XeroInvoiceService}); there is no per-invoice contact and this tool does not add one.
 *     The entity the work order names is reported so a person can notice a mismatch, and
 *     {@link #CONTACT_NOTE} says so in as many words — a silent echo would read as confirmation that
 *     the invoice is going to that entity.</li>
 *     <li><strong>The photo requirement is declared unhandled.</strong> These work orders make
 *     payment conditional on before-and-after photos. Nothing here attaches one, and saying nothing
 *     would let an assistant summarise a draft in a way that implies the requirement was dealt
 *     with.</li>
 *     <li><strong>The role is the same as the create tool's.</strong> Building a draft writes
 *     nothing, but the only thing this draft is for is becoming an invoice, and a second role would
 *     be one nobody is provisioned for — the draft step would fail for exactly the people entitled to
 *     finish the job.</li>
 * </ul>
 */
@Component
public class ExtractXeroInvoiceDraftTools implements LocalTool {
    private static final Logger log = LoggerFactory.getLogger(ExtractXeroInvoiceDraftTools.class);

    public static final String EXTRACT_XERO_INVOICE_DRAFT = "extract_xero_invoice_draft";

    /** Both spellings Xero accepts for what a line costs; either one satisfies the requirement. */
    static final String UNIT_AMOUNT = "unitAmount";
    static final String LINE_AMOUNT = "lineAmount";
    static final String ACCOUNT_CODE = "accountCode";

    static final String PRICING_NOTE =
            "A work order never states a price. Ask the user for the unit amount (or line amount) and "
                    + "the account code, and do not infer, estimate or carry over either one.";

    static final String CONTACT_NOTE =
            "The billing entity above is informational only. This invoice is billed to the one Xero "
                    + "contact this organisation has configured, which this flow cannot change. If the "
                    + "work order names a different entity, tell the user so they can decide.";

    static final String PHOTOS_NOTE =
            "The work order asks for BEFORE and AFTER photos. This flow does not attach photos to the "
                    + "invoice and has not handled that requirement; say so rather than implying it was.";

    static final String DATE_FALLBACK_NOTE =
            "The work order gave no request date, so today's date was used. Confirm it with the user.";

    static final String DERIVED_DESCRIPTION_NOTE =
            "The work order gave no scope of work, so the description was built from the work order "
                    + "number alone. Ask the user to describe the job before creating the invoice.";

    static final String NO_REFERENCE_NOTE =
            "The work order gave no work order number, so the invoice has no reference. These property "
                    + "managers pay against the work order number — get it from the user first.";

    private static final String NOTHING_TO_DRAFT =
            "A work order needs at least a work order number or a scope of work before it can become "
                    + "an invoice; neither was supplied.";

    private static final String DERIVED_DESCRIPTION_PREFIX = "Repairs and/or maintenance per work order ";

    /**
     * The work order as the model read it out of the message. Every component is optional, because a
     * work order is a document rather than a form this application controls.
     */
    public record ExtractXeroInvoiceDraftRequest(WorkOrder workOrder) {
    }

    /**
     * A draft invoice and everything a person needs to know before it becomes a real one.
     *
     * @param draft         the invoice as far as the work order supports it, with pricing left blank
     * @param billingEntity the entity the work order says to bill — reported, never applied
     * @param invoiceEmail  where the property manager asked for the finished invoice. Nothing here
     *                      sends it
     * @param missingFields the line item fields the user must still supply, named exactly as
     *                      {@link XeroLineItemRequest} names them
     * @param notes         what the assistant must tell the user before creating the invoice
     */
    public record XeroInvoiceDraft(XeroInvoiceRequest draft,
                                   String billingEntity,
                                   String invoiceEmail,
                                   List<String> missingFields,
                                   List<String> notes) {
    }

    @SuppressWarnings("unused")
    @Tool(name = EXTRACT_XERO_INVOICE_DRAFT, description = """
            Builds a draft Xero invoice from a property-management work order you have already read. \
            Pass the fields as they appear in the document: reference (the work order number), \
            requestDate (ISO YYYY-MM-DD), scopeOfWork (the instruction paragraph, verbatim), \
            billingEntity (the name after "Issue all invoices billable to:"), invoiceEmail, and \
            photosRequested. It creates nothing in Xero. A work order never states a price, so the \
            draft's unitAmount and accountCode come back blank: ask the user for them, never invent \
            them, and only then call create_xero_invoice with the completed draft. Report the returned \
            notes to the user verbatim — they cover which Xero contact is actually billed and the work \
            order's photo requirement, neither of which this flow handles.""")
    @PreAuthorize("hasAuthority('ROLE_xero-invoice-create')")
    public XeroInvoiceDraft extractXeroInvoiceDraft(ExtractXeroInvoiceDraftRequest request) {
        WorkOrder workOrder = (request == null) ? null : request.workOrder();

        if (workOrder == null
                || (StringUtils.isBlank(workOrder.reference()) && StringUtils.isBlank(workOrder.scopeOfWork()))) {

            log.warn("Refusing to draft an invoice from a work order carrying neither a number nor a scope");

            throw new XeroApiException(NOTHING_TO_DRAFT);
        }

        List<String> notes = new ArrayList<>();

        LocalDate date = workOrder.requestDate();

        if (date == null) {
            date = LocalDate.now();

            notes.add(DATE_FALLBACK_NOTE);
        }

        String description = workOrder.scopeOfWork();

        if (StringUtils.isBlank(description)) {
            description = DERIVED_DESCRIPTION_PREFIX + workOrder.reference();

            notes.add(DERIVED_DESCRIPTION_NOTE);
        }

        if (StringUtils.isBlank(workOrder.reference())) {
            notes.add(NO_REFERENCE_NOTE);
        }

        notes.add(PRICING_NOTE);

        if (StringUtils.isNotBlank(workOrder.billingEntity())) {
            notes.add(CONTACT_NOTE);
        }

        if (workOrder.photosRequested()) {
            notes.add(PHOTOS_NOTE);
        }

        XeroInvoiceRequest draft = draft(workOrder.reference(), date, description);

        log.info("Drafted an invoice from work order {}", workOrder.reference());

        return new XeroInvoiceDraft(
                draft,
                workOrder.billingEntity(),
                workOrder.invoiceEmail(),
                missingFields(),
                List.copyOf(notes));
    }

    /**
     * One line item, priced by nobody.
     * <p>
     * {@code dueDate}, {@code currencyCode} and {@code lineAmountTypes} are left null on purpose:
     * Xero applies the organisation's own terms, currency and tax treatment when they are absent, and
     * a value chosen here would silently override settings the user configured in Xero itself.
     */
    private static XeroInvoiceRequest draft(String reference, LocalDate date, String description) {
        XeroLineItemRequest lineItem =
                new XeroLineItemRequest(description, null, null, null, null, null, null);

        return new XeroInvoiceRequest(List.of(lineItem), date, null, reference, null, null);
    }

    /**
     * What the user still has to supply. Named after {@link XeroLineItemRequest}'s own components so
     * the model can fill the draft in rather than rebuild it.
     */
    private static List<String> missingFields() {
        return List.of(UNIT_AMOUNT, LINE_AMOUNT, ACCOUNT_CODE);
    }
}
