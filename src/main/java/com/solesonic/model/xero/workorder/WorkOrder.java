package com.solesonic.model.xero.workorder;

import java.time.LocalDate;

/**
 * The billable facts of a property-management work order, as they were actually written down.
 * <p>
 * Every component is nullable, because a work order is a document rather than a form this
 * application controls — a field that was not printed on it is absent here rather than guessed.
 * <p>
 * What is <em>not</em> here is the whole point. There is no price, no quantity and no account code,
 * because no work order of this shape has ever carried one: the property manager describes the job
 * and the contractor decides what it costs. Making the absence structural means no future change to
 * the extractor can quietly start inventing a number — the type has nowhere to put it.
 *
 * @param reference      the work order number, which becomes the invoice's {@code Reference}
 * @param requestDate    the date the work was requested, which becomes the invoice's {@code Date}
 * @param scopeOfWork    the instruction paragraph describing the job, which becomes the one line
 *                       item's description
 * @param billingEntity  the entity the work order says to bill. Informational only: every invoice
 *                       this application creates is billed to the one contact named by
 *                       {@code xero.invoice.default-contact-id}
 * @param invoiceEmail   where the property manager wants the finished invoice sent. Nothing in this
 *                       application emails it; it is carried so a person can be told where to
 * @param photosRequested whether the work order asks for before-and-after photos. Recorded so the
 *                       assistant can say plainly that this flow does not attach them
 */
public record WorkOrder(String reference,
                        LocalDate requestDate,
                        String scopeOfWork,
                        String billingEntity,
                        String invoiceEmail,
                        boolean photosRequested) {
}
