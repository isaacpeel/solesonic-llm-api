package com.solesonic.tools.xero;

import com.solesonic.exception.xero.XeroApiException;
import com.solesonic.model.xero.invoice.XeroInvoiceRequest;
import com.solesonic.model.xero.invoice.XeroLineItemRequest;
import com.solesonic.model.xero.workorder.WorkOrder;
import com.solesonic.tools.LocalTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;

import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.ACCOUNT_CODE;
import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.CONTACT_NOTE;
import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.DATE_FALLBACK_NOTE;
import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.DERIVED_DESCRIPTION_NOTE;
import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.NO_REFERENCE_NOTE;
import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.PHOTOS_NOTE;
import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.PRICING_NOTE;
import static com.solesonic.tools.xero.ExtractXeroInvoiceDraftTools.UNIT_AMOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Building a draft invoice out of a work order the model has already read.
 * <p>
 * The fixture is a redacted derivation of a real work order — every name, number and mailbox
 * replaced, the shape of the document left as the property manager's system produces it. It arrives
 * here as fields rather than as text on purpose: the message body is read once, by the model, and a
 * second reading in this process could only disagree with the first.
 * <p>
 * The assertion that matters most is the one about what is <em>not</em> filled in. A work order of
 * this kind states no price anywhere — that is the fact the whole design follows from — so a draft
 * that came back with a unit amount on it would mean a number had been invented, and it would reach
 * a real accounting system indistinguishable from a figure the property manager wrote down.
 */
class ExtractXeroInvoiceDraftToolsTest {

    private static final String REFERENCE = "XN4402318";
    private static final LocalDate REQUEST_DATE = LocalDate.of(2026, 6, 18);
    private static final String BILLING_ENTITY = "Northgate Park Homeowners Association";
    private static final String INVOICE_EMAIL = "invoices@example-cm.com";

    private static final String SCOPE_OF_WORK =
            "Please inspect and make repairs to loose boards on the ENTIRE Community fence "
                    + "(there are several loose pickets/top rails throughout).";

    private ExtractXeroInvoiceDraftTools extractXeroInvoiceDraftTools;

    @BeforeEach
    void beforeEach() {
        extractXeroInvoiceDraftTools = new ExtractXeroInvoiceDraftTools();
    }

    private static WorkOrder workOrder() {
        return new WorkOrder(REFERENCE, REQUEST_DATE, SCOPE_OF_WORK, BILLING_ENTITY, INVOICE_EMAIL, true);
    }

    private ExtractXeroInvoiceDraftTools.XeroInvoiceDraft draftFrom(WorkOrder workOrder) {
        return extractXeroInvoiceDraftTools.extractXeroInvoiceDraft(
                new ExtractXeroInvoiceDraftTools.ExtractXeroInvoiceDraftRequest(workOrder));
    }

    private static XeroLineItemRequest onlyLineItem(ExtractXeroInvoiceDraftTools.XeroInvoiceDraft draft) {
        return draft.draft().lineItems().getFirst();
    }

    private static Method toolMethod() {
        return Arrays.stream(ExtractXeroInvoiceDraftTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .reduce((_, _) -> {
                    throw new IllegalStateException("Expected exactly one @Tool method");
                })
                .orElseThrow(() -> new IllegalStateException("No @Tool method found"));
    }

    @Test
    void isALocalToolNamedForItsSlashCommand() {
        assertThat(LocalTool.class).isAssignableFrom(ExtractXeroInvoiceDraftTools.class);
        assertThat(toolMethod().getAnnotation(Tool.class).name()).isEqualTo("extract_xero_invoice_draft");
    }

    /**
     * Drafting and creating share one role deliberately. A role of its own would be one nobody is
     * provisioned for, so the drafting step would fail for exactly the people entitled to finish the
     * job — and the comparison is made against the create tool rather than a copied string, so a
     * rename on either side is caught.
     */
    @Test
    void gatesOnTheSameRoleAsTheCreateTool() {
        PreAuthorize draftGate = toolMethod().getAnnotation(PreAuthorize.class);

        PreAuthorize createGate = Arrays.stream(CreateXeroInvoiceTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .findFirst()
                .orElseThrow()
                .getAnnotation(PreAuthorize.class);

        assertThat(draftGate).isNotNull();
        assertThat(createGate).isNotNull();
        assertThat(draftGate.value()).isEqualTo(createGate.value());
    }

    /**
     * The whole mapping in one place: the work order number becomes the invoice's reference, the
     * request date its date, and the instruction paragraph the one line item's description.
     */
    @Test
    void mapsTheWorkOrderOntoTheInvoice() {
        ExtractXeroInvoiceDraftTools.XeroInvoiceDraft draft = draftFrom(workOrder());

        XeroInvoiceRequest invoice = draft.draft();

        assertThat(invoice.reference()).isEqualTo(REFERENCE);
        assertThat(invoice.date()).isEqualTo(REQUEST_DATE);
        assertThat(invoice.lineItems()).hasSize(1);
        assertThat(onlyLineItem(draft).description()).isEqualTo(SCOPE_OF_WORK);

        assertThat(draft.billingEntity()).isEqualTo(BILLING_ENTITY);
        assertThat(draft.invoiceEmail()).isEqualTo(INVOICE_EMAIL);
        assertThat(draft.notes()).doesNotContain(DATE_FALLBACK_NOTE, DERIVED_DESCRIPTION_NOTE, NO_REFERENCE_NOTE);
    }

    /**
     * The one that would matter if it ever broke. A work order states no price, so a populated amount
     * here could only have been invented — and it would post to a real accounting system looking
     * exactly like a figure the property manager supplied.
     */
    @Test
    void neverPricesTheLineItem() {
        ExtractXeroInvoiceDraftTools.XeroInvoiceDraft draft = draftFrom(workOrder());

        XeroLineItemRequest lineItem = onlyLineItem(draft);

        assertThat(lineItem.quantity()).isNull();
        assertThat(lineItem.unitAmount()).isNull();
        assertThat(lineItem.lineAmount()).isNull();
        assertThat(lineItem.accountCode()).isNull();
        assertThat(lineItem.itemCode()).isNull();
        assertThat(lineItem.taxType()).isNull();

        assertThat(draft.missingFields()).contains(UNIT_AMOUNT, ACCOUNT_CODE);
        assertThat(draft.notes()).contains(PRICING_NOTE);
    }

    /**
     * The strongest form of the rule above: there is no component on {@link WorkOrder} a price could
     * have arrived in, so no future change to this class can start populating one from the document.
     */
    @Test
    void theWorkOrderTypeCannotCarryAPrice() {
        assertThat(Arrays.stream(WorkOrder.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("quantity", "unitAmount", "lineAmount", "amount", "price", "accountCode");
    }

    /**
     * The billing entity is reported so a person can catch a mismatch, and said out loud to be
     * informational — the invoice goes to the one configured Xero contact whatever the work order
     * names. An entity echoed without that sentence reads as confirmation that it was applied.
     */
    @Test
    void echoesTheBillingEntityAsInformationOnly() {
        ExtractXeroInvoiceDraftTools.XeroInvoiceDraft draft = draftFrom(workOrder());

        assertThat(draft.billingEntity()).isEqualTo(BILLING_ENTITY);
        assertThat(draft.notes()).contains(CONTACT_NOTE);

        assertThat(Arrays.stream(XeroInvoiceRequest.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("contact", "contactId", "status", "type");
    }

    /**
     * Payment on these work orders is conditional on before-and-after photos, and nothing in this
     * flow attaches one. Saying so is what stops a summary of the draft from implying otherwise.
     */
    @Test
    void saysPlainlyThatPhotosAreNotAttached() {
        assertThat(draftFrom(workOrder()).notes()).contains(PHOTOS_NOTE);

        WorkOrder withoutPhotoRequirement = new WorkOrder(
                REFERENCE, REQUEST_DATE, SCOPE_OF_WORK, BILLING_ENTITY, INVOICE_EMAIL, false);

        assertThat(draftFrom(withoutPhotoRequirement).notes()).doesNotContain(PHOTOS_NOTE);
    }

    /**
     * A work order with no date is dated today, because Xero needs one — but never silently. The same
     * holds for one that describes no work, and for one with no number: these property managers pay
     * against the work order number, so an invoice missing it is one that does not get paid.
     */
    @Test
    void flagsEveryFieldItHadToFallBackOn() {
        WorkOrder numberOnly = new WorkOrder(REFERENCE, null, null, null, null, false);

        ExtractXeroInvoiceDraftTools.XeroInvoiceDraft draft = draftFrom(numberOnly);

        assertThat(draft.draft().reference()).isEqualTo(REFERENCE);
        assertThat(draft.draft().date()).isEqualTo(LocalDate.now());
        assertThat(onlyLineItem(draft).description()).contains(REFERENCE);
        assertThat(draft.notes()).contains(DATE_FALLBACK_NOTE, DERIVED_DESCRIPTION_NOTE);

        WorkOrder scopeOnly = new WorkOrder(null, REQUEST_DATE, SCOPE_OF_WORK, null, null, false);

        assertThat(draftFrom(scopeOnly).notes()).contains(NO_REFERENCE_NOTE);
    }

    /**
     * A work order with neither a number nor a scope is refused rather than answered with a blank
     * draft. The alternative is an invoice that names no job and references no document, which looks
     * like a successful reading of a work order nobody supplied.
     */
    @Test
    void refusesToDraftFromNothing() {
        WorkOrder empty = new WorkOrder(null, REQUEST_DATE, "  ", BILLING_ENTITY, INVOICE_EMAIL, true);

        assertThatThrownBy(() -> draftFrom(empty)).isInstanceOf(XeroApiException.class);
        assertThatThrownBy(() -> draftFrom(null)).isInstanceOf(XeroApiException.class);
        assertThatThrownBy(() -> extractXeroInvoiceDraftTools.extractXeroInvoiceDraft(null))
                .isInstanceOf(XeroApiException.class);
    }
}
